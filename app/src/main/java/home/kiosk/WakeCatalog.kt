package home.kiosk

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Tater Wake Words (MIT, https://github.com/TaterTotterson/Tater-Wake-Words): about 800 microWakeWord models.
 * Not bundled. The picked one is downloaded once into filesDir/wakeword and works offline from then on.
 */
object WakeCatalog {
    /** Settings.wakeWord prefix for a downloaded word, so a slug like hey_jarvis can't clash with the bundled one. */
    const val PREFIX = "tater/"
    private const val MANIFEST = "https://raw.githubusercontent.com/TaterTotterson/Tater-Wake-Words/main/wake_word_manifest.json"

    data class Word(val slug: String, val label: String, val jsonUrl: String, val modelUrl: String)

    private val http = OkHttpClient()
    private var cached: List<Word>? = null

    /** The newest version of each word (a V8 hey_alfred over a V3 one), sorted by name. */
    fun parse(manifest: String): List<Word> {
        val entries = JSONObject(manifest).getJSONArray("entries")
        val newest = HashMap<String, Pair<Int, Word>>()
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            val version = e.optString("source").substringAfterLast('V').toIntOrNull() ?: 0
            val slug = e.optString("slug")
            // V8 entries point url at a multi-engine bundle; esphome_url is the plain microWakeWord manifest.
            val json = e.optString("esphome_url").ifEmpty { e.optString("url") }
            val model = e.optString("model_url")
            if (!slug.matches(Regex("[a-z0-9_]+")) || json.isEmpty() || model.isEmpty()) continue
            if ((newest[slug]?.first ?: -1) < version) newest[slug] = version to Word(slug, e.optString("label").ifEmpty { slug }, json, model)
        }
        return newest.values.map { it.second }.sortedBy { it.label.lowercase() }
    }

    suspend fun list(): List<Word> = cached ?: withContext(Dispatchers.IO) { parse(get(MANIFEST).decodeToString()) }.also { cached = it }

    fun file(context: Context, slug: String, ext: String) = File(context.filesDir, "wakeword/$slug.$ext")

    /** Downloads [word] unless it's already here. Both files are checked before either replaces anything. */
    suspend fun download(context: Context, word: Word) = withContext(Dispatchers.IO) {
        val json = file(context, word.slug, "json")
        val model = file(context, word.slug, "tflite")
        if (json.exists() && model.exists()) return@withContext
        val jsonBytes = get(word.jsonUrl)
        val modelBytes = get(word.modelUrl)
        WakeModel.parse(word.slug, jsonBytes.decodeToString())
        if (!isTflite(modelBytes)) throw IOException("not a wake word model")
        json.parentFile!!.mkdirs()
        // model first: json existing is what marks the download complete
        write(model, modelBytes)
        write(json, jsonBytes)
    }

    /** A TFLite flatbuffer carries "TFL3" at bytes 4..7. */
    fun isTflite(b: ByteArray) = b.size > 10_000 && String(b, 4, 4, Charsets.US_ASCII) == "TFL3"

    private fun write(f: File, bytes: ByteArray) {
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) throw IOException("couldn't save ${f.name}")
    }

    private fun get(url: String): ByteArray = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
        r.body!!.bytes()
    }
}
