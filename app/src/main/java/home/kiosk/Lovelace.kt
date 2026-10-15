package home.kiosk

import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class DashboardInfo(val urlPath: String, val title: String)

/**
 * The selected HA dashboard's config, kept as HA's raw JSON so a save from the tablet
 * round-trips every option this app doesn't understand.
 */
class Lovelace(private val ha: HaClient, private val scope: CoroutineScope, private val prefs: SharedPreferences) {
    private val _urlPath = MutableStateFlow(prefs.getString(PREF, DEFAULT)!!)
    val urlPath: StateFlow<String> = _urlPath
    private val _config = MutableStateFlow<JSONObject?>(null)
    val config: StateFlow<JSONObject?> = _config
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    val editable get() = urlPath.value.startsWith("wall-")

    init {
        scope.launch { ha.status.collect { if (it == Status.CONNECTED) load() } }
        scope.launch { ha.dashboardSaved.collect { if (it == urlPath.value) load() } }
    }

    fun select(path: String) {
        prefs.edit().putString(PREF, path).apply()
        _urlPath.value = path
        _config.value = null
        scope.launch { load() }
    }

    suspend fun load() {
        runCatching { fetch() }
            .onSuccess { _config.value = it; _error.value = null }
            .onFailure { _error.value = "Couldn't load dashboard “${urlPath.value}”: ${it.message}" }
    }

    private suspend fun fetch() = ha.request("lovelace/config") { put("url_path", urlPath.value) } as JSONObject

    suspend fun dashboards(): List<DashboardInfo> {
        val arr = ha.request("lovelace/dashboards/list") as JSONArray
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("mode") == "storage" }
            .map { DashboardInfo(it.getString("url_path"), it.optString("title", it.getString("url_path"))) }
    }

    /** Saves [draft] unless the dashboard changed since [original] was loaded. False means conflict. */
    suspend fun save(draft: JSONObject, original: JSONObject): Boolean {
        if (fetch().toString() != original.toString()) return false
        ha.request("lovelace/config/save") { put("url_path", urlPath.value); put("config", draft) }
        _config.value = draft
        return true
    }

    /** Copies dashboard [from] into a new `wall-…` dashboard and switches to it. */
    suspend fun createWallCopy(from: DashboardInfo) {
        val path = "wall-" + from.urlPath.removePrefix("dashboard-")
        val source = ha.request("lovelace/config") { put("url_path", from.urlPath) } as JSONObject
        ha.request("lovelace/dashboards/create") {
            put("url_path", path); put("title", "Wall – ${from.title}"); put("icon", "mdi:tablet-dashboard")
            put("mode", "storage"); put("show_in_sidebar", true); put("require_admin", false)
        }
        ha.request("lovelace/config/save") { put("url_path", path); put("config", source) }
        select(path)
    }

    private companion object {
        const val PREF = "dashboard"
        const val DEFAULT = "wall-home"
    }
}

// ---- pure helpers over the raw config (unit-tested) ----

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

fun viewsOf(config: JSONObject): List<JSONObject> = config.optJSONArray("views")?.objects().orEmpty()

/** Stable id for a view: its URL path, else its position. Used for show/hide and "last view". */
fun viewKey(view: JSONObject, index: Int) = view.optString("path").ifEmpty { index.toString() }

fun viewTitle(view: JSONObject, index: Int) = view.optString("title").ifEmpty { view.optString("path").ifEmpty { "View ${index + 1}" } }

/** Which view a `navigate` path points at: "/wall-home/living-room", "living-room" or "1". */
fun viewIndexFor(config: JSONObject, path: String): Int? {
    val last = path.trimEnd('/').substringAfterLast('/')
    val views = viewsOf(config)
    return views.indices.firstOrNull { viewKey(views[it], it) == last }
}

/** A view's sections as card lists; a masonry view is one section. */
fun sectionsOf(config: JSONObject, viewIndex: Int = 0): List<List<JSONObject>> {
    val view = config.optJSONArray("views")?.optJSONObject(viewIndex) ?: return emptyList()
    view.optJSONArray("sections")?.let { s -> return s.objects().map { it.optJSONArray("cards")?.objects().orEmpty() } }
    return listOf(view.optJSONArray("cards")?.objects().orEmpty())
}

fun badgesOf(config: JSONObject, viewIndex: Int = 0): List<JSONObject> =
    config.optJSONArray("views")?.optJSONObject(viewIndex)?.optJSONArray("badges")?.objects().orEmpty()

fun isPopup(card: JSONObject) = card.optString("type") == "custom:bubble-card" && card.optString("card_type") == "pop-up"

/** Bubble-card pop-ups by hash ("#pop-up-calendar" → its cards), from every view. */
fun popupsOf(config: JSONObject): Map<String, List<JSONObject>> =
    viewsOf(config).indices.flatMap { sectionsOf(config, it).flatten() }.filter(::isPopup)
        .associate { it.optString("hash") to it.optJSONArray("cards")?.objects().orEmpty() }

fun popupTitle(card: JSONObject) = card.optString("name").ifEmpty {
    card.optString("hash").removePrefix("#").removePrefix("pop-up-").replace('-', ' ').replaceFirstChar { it.uppercase() }
}

/** HA card `visibility` conditions. Screen/user/location conditions count as met. */
fun visible(card: JSONObject, states: Map<String, Entity>): Boolean =
    card.optJSONArray("visibility")?.objects()?.all { met(it, card.optString("entity"), states) } ?: true

private fun met(c: JSONObject, defaultEntity: String, states: Map<String, Entity>): Boolean {
    val state = states[c.optString("entity").ifEmpty { c.optString("entity_id") }.ifEmpty { defaultEntity }]?.state
    fun matches(x: Any?) = if (x is JSONArray) (0 until x.length()).any { x.optString(it) == state } else x?.toString() == state
    val subs = c.optJSONArray("conditions")?.objects().orEmpty()
    return when (c.optString("condition")) {
        "state" -> when {
            c.has("state") -> matches(c.get("state"))
            c.has("state_not") -> !matches(c.get("state_not"))
            else -> true
        }
        "numeric_state" -> {
            val v = state?.toDoubleOrNull() ?: return false
            (!c.has("above") || v > c.getDouble("above")) && (!c.has("below") || v < c.getDouble("below"))
        }
        "and" -> subs.all { met(it, defaultEntity, states) }
        "or" -> subs.any { met(it, defaultEntity, states) }
        "not" -> subs.none { met(it, defaultEntity, states) }
        else -> true
    }
}

sealed interface Act {
    data class Service(val domain: String, val service: String, val ids: List<String>, val data: JSONObject?) : Act
    data class Popup(val hash: String) : Act
    data class View(val path: String) : Act
    data object None : Act
    data object MoreInfo : Act
    data class Confirm(val text: String, val then: Act) : Act
    /** No tap_action (or more-info): the kiosk's own default for the entity kind. */
    data object Default : Act
}

fun tapAct(card: JSONObject, entityId: String?): Act =
    card.optJSONObject("tap_action")?.let { parseAct(it, entityId) } ?: Act.Default

/** Press-and-hold: the card's hold_action, else more-info (HA's own default). */
fun holdAct(card: JSONObject, entityId: String?): Act =
    card.optJSONObject("hold_action")?.let { parseAct(it, entityId) }?.takeUnless { it == Act.Default } ?: Act.MoreInfo

fun doubleTapAct(card: JSONObject, entityId: String?): Act =
    card.optJSONObject("double_tap_action")?.let { parseAct(it, entityId) } ?: Act.None

/** Parses one HA action object (tap_action and friends). */
fun parseAct(t: JSONObject, entityId: String?): Act {
    val act = parseBareAct(t, entityId)
    // HA's `confirmation: true` or `confirmation: {text: ...}` asks before running.
    val confirm = t.opt("confirmation")
    if (act is Act.Service && (confirm == true || confirm is JSONObject)) {
        return Act.Confirm((confirm as? JSONObject)?.optString("text").orEmpty().ifEmpty { "Are you sure?" }, act)
    }
    return act
}

private fun parseBareAct(t: JSONObject, entityId: String?): Act {
    return when (t.optString("action")) {
        "toggle" -> entityId?.let { Act.Service("homeassistant", "toggle", listOf(it), null) } ?: Act.None
        "navigate" -> t.optString("navigation_path").let { p ->
            when {
                p.startsWith("#") -> Act.Popup(p)
                p.isNotEmpty() -> Act.View(p)
                else -> Act.None
            }
        }
        "perform-action", "call-service" -> {
            val svc = t.optString("perform_action").ifEmpty { t.optString("service") }
            if ('.' !in svc) return Act.None
            val data = JSONObject((t.optJSONObject("data") ?: t.optJSONObject("service_data") ?: JSONObject()).toString())
            t.optJSONObject("target")?.let { target -> target.keys().forEach { data.put(it, target.get(it)) } }
            Act.Service(svc.substringBefore('.'), svc.substringAfter('.'), emptyList(), data)
        }
        "none" -> Act.None
        "more-info" -> Act.MoreInfo
        else -> Act.Default
    }
}

/** Width in HA's 12-column section grid. */
fun widthOf(card: JSONObject): Int = when (val c = card.optJSONObject("grid_options")?.opt("columns")) {
    is Number -> c.toInt().coerceIn(1, 12)
    "full" -> 12
    else -> if (card.optString("type") in setOf("tile", "button", "entity")) 6 else 12
}

/** Greedy rows of cards whose widths add up to at most 12. */
fun packRows(cards: List<JSONObject>): List<List<JSONObject>> {
    val rows = mutableListOf<MutableList<JSONObject>>()
    var used = 12
    for (c in cards) {
        val w = widthOf(c)
        if (used + w > 12) { rows += mutableListOf<JSONObject>(); used = 0 }
        rows.last() += c
        used += w
    }
    return rows
}

/** Tablet-editor operations. Each returns an edited copy; the input is never mutated. */
object Edits {
    private fun cardsArray(config: JSONObject, view: Int, section: Int): JSONArray {
        val view = config.getJSONArray("views").getJSONObject(view)
        return view.optJSONArray("sections")?.getJSONObject(section)?.getJSONArray("cards") ?: view.getJSONArray("cards")
    }

    private fun edit(config: JSONObject, view: Int, section: Int, change: (MutableList<JSONObject>) -> Unit): JSONObject {
        val copy = JSONObject(config.toString())
        val arr = cardsArray(copy, view, section)
        val list = arr.objects().toMutableList()
        change(list)
        while (arr.length() > 0) arr.remove(0)
        list.forEach { arr.put(it) }
        return copy
    }

    fun move(config: JSONObject, view: Int, section: Int, index: Int, delta: Int) = edit(config, view, section) {
        val to = (index + delta).coerceIn(0, it.lastIndex)
        it.add(to, it.removeAt(index))
    }

    fun remove(config: JSONObject, view: Int, section: Int, index: Int) = edit(config, view, section) { it.removeAt(index) }

    fun add(config: JSONObject, view: Int, section: Int, card: JSONObject) = edit(config, view, section) { it += card }

    fun rename(config: JSONObject, view: Int, section: Int, index: Int, name: String) = edit(config, view, section) {
        val card = it[index]
        val key = if (card.optString("type") == "custom:mushroom-template-card") "primary" else "name"
        if (name.isBlank()) card.remove(key) else card.put(key, name)
    }

    /** A built-in HA card for a newly added entity, so it also looks right in HA's own dashboard. */
    fun cardFor(entityId: String) = JSONObject().put("type", "tile").put("entity", entityId)
}
