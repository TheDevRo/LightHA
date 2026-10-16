package home.kiosk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Username/password sign-in through HA's own login flow (the one its login page uses), then refresh-token renewal.
 * Only the refresh token is kept; the password never leaves the sign-in form.
 */
object HaAuth {
    /** HA wants a URL as the app's ID. The redirect is on the same host, so HA never fetches it (tablets are offline). */
    const val CLIENT_ID = "http://lightha.local/"
    private const val REDIRECT = "http://lightha.local/auth"
    private val http = OkHttpClient()

    /** The refresh token was revoked (signed out in HA's profile page, or the user was removed). */
    class Revoked : IOException("Signed out in Home Assistant")

    /** One login-flow step: a form to fill ([stepId] "init" = username/password, "mfa" = code), or the finished [code]. */
    data class Step(val flowId: String, val stepId: String, val error: String?, val code: String?)

    data class Tokens(val access: String, val refresh: String, val expiresInSec: Long)

    suspend fun begin(base: String): Step = postJson(
        base, "/auth/login_flow",
        JSONObject().put("client_id", CLIENT_ID).put("handler", org.json.JSONArray().put("homeassistant").put(JSONObject.NULL)).put("redirect_uri", REDIRECT),
    ).let(::parseStep)

    suspend fun answer(base: String, flowId: String, fields: Map<String, String>): Step =
        postJson(base, "/auth/login_flow/$flowId", JSONObject(fields).put("client_id", CLIENT_ID)).let(::parseStep)

    suspend fun exchange(base: String, code: String): Tokens =
        token(base, FormBody.Builder().add("grant_type", "authorization_code").add("code", code).add("client_id", CLIENT_ID).build())

    /** A fresh 30-minute access token. Throws [Revoked] when HA no longer knows the refresh token. */
    suspend fun refresh(base: String, refreshToken: String): Tokens =
        token(base, FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refreshToken).add("client_id", CLIENT_ID).build())

    /** Best effort: removes this tablet's login from the user's HA profile. */
    suspend fun revoke(base: String, refreshToken: String) {
        runCatching { call(base, "/auth/revoke", FormBody.Builder().add("token", refreshToken).build()) }
    }

    fun parseStep(j: JSONObject): Step = when (j.optString("type")) {
        "create_entry" -> Step("", "", null, j.optString("result"))
        "form" -> Step(
            j.getString("flow_id"), j.optString("step_id"),
            j.optJSONObject("errors")?.let { it.optString("base").ifEmpty { it.keys().asSequence().firstOrNull()?.let(it::optString) } }
                ?.let(::loginError),
            null,
        )
        else -> Step("", "", loginError(j.optString("reason").ifEmpty { j.optString("message", "unknown") }), null) // abort
    }

    /** HA's error keys as words. */
    fun loginError(key: String): String = when (key) {
        "invalid_auth" -> "Wrong username or password"
        "invalid_code" -> "Wrong code"
        "login_expired", "unknown_flow" -> "Sign-in timed out. Try again."
        "user_not_allowed", "not_allowed" -> "This account can't sign in from here"
        else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private suspend fun token(base: String, body: RequestBody): Tokens {
        val j = call(base, "/auth/token", body)
        return Tokens(j.getString("access_token"), j.optString("refresh_token"), j.optLong("expires_in", 1800))
    }

    private suspend fun postJson(base: String, path: String, body: JSONObject) = call(base, path, body.toString().toRequestBody("application/json".toMediaType()))

    private suspend fun call(base: String, path: String, body: RequestBody): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(base.trimEnd('/') + path).post(body).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            // HA answers a dead refresh token with 400 invalid_grant.
            if (r.code == 400 && "invalid_grant" in text) throw Revoked()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} from $path")
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }
}
