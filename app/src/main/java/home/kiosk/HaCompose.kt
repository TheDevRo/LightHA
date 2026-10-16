package home.kiosk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.produceState
import org.json.JSONObject

val LocalHa = compositionLocalOf<HaClient> { error("HaClient not provided") }

/**
 * A live HA subscription as Compose state: subscribes while on screen, re-keys when [key] changes,
 * and cancels when the card leaves. Null until the first event.
 */
@Composable
fun <T> rememberSubscription(key: Any?, type: String, fill: JSONObject.() -> Unit, parse: (JSONObject) -> T?): T? {
    val ha = LocalHa.current
    return produceState<T?>(null, ha, key) {
        val sub = ha.subscribe(type, fill) { event -> parse(event)?.let { value = it } }
        awaitDispose { sub.cancel() }
    }.value
}

/** Renders a Jinja template in HA, live. `entity` is available inside it, as in Mushroom cards. */
@Composable
fun rememberTemplate(template: String?, entityId: String? = null): String? {
    if (template == null || ("{{" !in template && "{%" !in template)) return template
    return rememberSubscription(template to entityId, "render_template", {
        put("template", template)
        entityId?.let { put("variables", JSONObject().put("entity", it)) }
        put("report_errors", false)
    }) { event -> templateResult(event) }
}

/** render_template's event carries `result` (any JSON type) or `error`. */
fun templateResult(event: JSONObject): String? =
    if (event.has("error")) null else event.opt("result")?.takeUnless { it == JSONObject.NULL }?.toString()?.trim()
