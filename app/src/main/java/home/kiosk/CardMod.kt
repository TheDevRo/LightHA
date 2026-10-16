package home.kiosk

import org.json.JSONObject

/**
 * The common `card_mod` CSS on a card's `ha-card`, mapped to native styling. Anything else (other selectors,
 * var(--x), templates, shadow-DOM paths) is ignored and the card keeps its default look.
 */
data class CardMod(
    /** Multiplier on the card's normal text sizes (6em = 6x). */
    val fontScale: Float? = null,
    val bold: Boolean? = null,
    /** "left", "center" or "right". */
    val align: String? = null,
    val color: Long? = null,
    val background: Long? = null,
    val radiusPx: Float? = null,
)

fun cardMod(card: JSONObject): CardMod {
    val css = cardModCss(card)
    return CardMod(
        fontScale = css["font-size"]?.let(::cssScale),
        bold = css["font-weight"]?.let { it == "bold" || it == "bolder" || (it.toIntOrNull() ?: 0) >= 600 },
        align = css["text-align"]?.let { mapOf("left" to "left", "start" to "left", "center" to "center", "right" to "right", "end" to "right")[it] },
        color = css["color"]?.let(::cssColor),
        background = (css["background-color"] ?: css["background"])?.let(::cssColor),
        radiusPx = css["border-radius"]?.removeSuffix("px")?.toFloatOrNull(),
    )
}

/** Declarations for `ha-card` (or bare ones) from card_mod's style, which is a string or a map keyed "." / "ha-card". */
fun cardModCss(card: JSONObject): Map<String, String> {
    val mod = card.optJSONObject("card_mod") ?: return emptyMap()
    val style = mod.optJSONObject("style")?.let { it.optString(".").ifEmpty { it.optString("ha-card") } } ?: mod.optString("style")
    if ("{{" in style || "{%" in style) return emptyMap() // templated styles need HA's renderer
    // Braces escaped: Android's ICU regex rejects a bare } (the JVM in unit tests doesn't).
    val body = Regex("""(?:^|[\s,\}])(?:ha-card|:host)\s*\{([^\}]*)\}""").findAll(style).joinToString(";") { it.groupValues[1] }
        .ifEmpty { if ('{' in style) "" else style } // bare declarations apply to ha-card
    return body.split(';').mapNotNull { decl ->
        val (k, v) = decl.split(':', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        val value = v.replace("!important", "").trim().lowercase()
        if (value.isEmpty() || "var(" in value) null else k.trim().lowercase() to value
    }.toMap()
}

/** CSS font-size as a multiplier of the card's base 16px text. */
fun cssScale(v: String): Float? = when {
    v.endsWith("rem") -> v.removeSuffix("rem").toFloatOrNull()
    v.endsWith("em") -> v.removeSuffix("em").toFloatOrNull()
    v.endsWith("px") -> v.removeSuffix("px").toFloatOrNull()?.div(16)
    v.endsWith("%") -> v.removeSuffix("%").toFloatOrNull()?.div(100)
    else -> null
}?.takeIf { it > 0 }

private val NAMED = mapOf(
    "white" to 0xFFFFFF, "black" to 0x000000, "red" to 0xFF0000, "green" to 0x008000, "lime" to 0x00FF00, "blue" to 0x0000FF,
    "cyan" to 0x00FFFF, "aqua" to 0x00FFFF, "magenta" to 0xFF00FF, "yellow" to 0xFFFF00, "orange" to 0xFFA500,
    "purple" to 0x800080, "pink" to 0xFFC0CB, "gray" to 0x808080, "grey" to 0x808080, "silver" to 0xC0C0C0,
    "teal" to 0x008080, "navy" to 0x000080, "gold" to 0xFFD700,
)

/** CSS color as ARGB: names above, #rgb, #rrggbb, #rrggbbaa, rgb()/rgba(), transparent. */
fun cssColor(v: String): Long? {
    if (v == "transparent" || v == "none") return 0L
    NAMED[v]?.let { return 0xFF000000 or it.toLong() }
    if (v.startsWith("#")) {
        val h = v.drop(1)
        val full = when (h.length) {
            3 -> h.map { "$it$it" }.joinToString("") + "ff"
            6 -> h + "ff"
            8 -> h
            else -> return null
        }
        val rgba = full.toLongOrNull(16) ?: return null
        return ((rgba and 0xFF) shl 24) or (rgba shr 8)
    }
    val m = Regex("""rgba?\(([^)]*)\)""").matchEntire(v) ?: return null
    val p = m.groupValues[1].split(',', ' ', '/').filter { it.isNotBlank() }.map { it.trim() }
    if (p.size < 3) return null
    val (r, g, b) = p.take(3).map { it.toFloatOrNull()?.toInt()?.coerceIn(0, 255) ?: return null }
    val a = p.getOrNull(3)?.let { if (it.endsWith("%")) it.removeSuffix("%").toFloatOrNull()?.div(100) else it.toFloatOrNull() } ?: 1f
    return ((a.coerceIn(0f, 1f) * 255).toLong() shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
}
