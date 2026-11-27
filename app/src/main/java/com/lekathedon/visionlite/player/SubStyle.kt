package com.lekathedon.visionlite.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.lekathedon.visionlite.App
import java.util.Locale

/*
 * Old Vision's subtitle look (PlayerScreen.applySubtitleStyleIfNeeded, NuvioMpvSurfaceView.applySubtitleStyle,
 * SubtitleEdgePresentation), copied (user 2026-11-25: Vision's subtitles looked better; it wasn't the link). Default =
 * Vision's: white text, a soft drop shadow (black at 70 %), 24 sp, the file's own styling (italics...) kept but not its
 * sizes, 5 % up from the bottom. Every part can be changed in Settings › Player › Subtitles.
 */
object SubStyle {
    /** Vision's SubtitleTextColors. */
    val TextColors = listOf("FFFFFFFF" to "White", "FFD9D9D9" to "Light grey", "FFFFD700" to "Gold", "FF00E5FF" to "Cyan",
        "FFFF5C5C" to "Red", "FF00FF88" to "Green", "FFFFAD42" to "Warm orange", "FFFFF59D" to "Soft yellow",
        "FFB3E5FC" to "Pale blue", "FFE1BEE7" to "Lavender")
    /** Vision's SubtitleOutlineColors. */
    val OutlineColors = listOf("FF000000" to "Black", "FFFFFFFF" to "White", "FF00E5FF" to "Cyan", "FFFF5C5C" to "Red",
        "FF212121" to "Charcoal", "FF8E44AD" to "Purple")
    /** Vision's subtitleBackgroundColors. */
    val Backgrounds = listOf("00000000" to "None", "80000000" to "Black, see-through", "FF000000" to "Black",
        "FF1A1A1A" to "Dark grey", "FF2D2D2D" to "Grey")
    val Edges = listOf("shadow" to "Shadow", "outline" to "Outline", "none" to "None")
    val Positions = listOf("-10" to "Lower", "5" to "Normal", "15" to "Higher", "25" to "Much higher")
    val Sizes = listOf("s" to "Small", "m" to "Medium", "l" to "Large", "xl" to "Extra large")
    /** HDR/DV subtitle brightness steps (setting 0-100), named by the % of full white they end at. */
    val HdrLevels = listOf("0", "10", "20", "30", "40", "50", "58", "70", "85", "100").map { it to hdrLabel(it) }
    fun hdrLabel(v: String): String {
        val pct = ((0.7f * (v.toIntOrNull() ?: 58).coerceIn(0, 100) + 30f)).toInt()
        return "$pct %" + when (v) { "30" -> " (default)"; "58" -> " (Vision's number)"; "100" -> " (off)"; else -> "" }
    }

    const val SHADOW_COLOR: Int = 0xB3000000.toInt()

    private fun argb(hex: String, fallback: Int) = hex.toLongOrNull(16)?.toInt() ?: fallback
    private val p get() = App.instance.prefs
    val textColor get() = argb(p.subColor.value, -1)
    val outlineColor get() = argb(p.subOutlineColor.value, 0xFF000000.toInt())
    val backColor get() = argb(p.subBack.value, 0)
    val edge get() = p.subEdge.value
    val sizePct get() = when (p.subSize.value) { "s" -> 80; "l" -> 125; "xl" -> 150; else -> 100 }
    val offset get() = (p.subPos.value.toIntOrNull() ?: 5).coerceIn(-20, 50)

    /** ExoPlayer (Vision's applySubtitleStyleIfNeeded, non-ASS). [hdr]: the colour dimmed as Vision's HDR style. */
    @OptIn(UnstableApi::class)
    fun apply(v: SubtitleView, hdr: Boolean) {
        v.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 24f * sizePct / 100f)
        v.setApplyEmbeddedFontSizes(false)
        val edgeType = when (edge) { "outline" -> CaptionStyleCompat.EDGE_TYPE_OUTLINE; "none" -> CaptionStyleCompat.EDGE_TYPE_NONE; else -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW }
        val face = if (p.subBold.value) android.graphics.Typeface.DEFAULT_BOLD else null
        // HDR: text, background and outline dimmed together, as Vision's forVideo().
        fun d(c: Int) = if (hdr) hdrSubColor(c) else c
        v.setStyle(CaptionStyleCompat(d(textColor), d(backColor), android.graphics.Color.TRANSPARENT,
            edgeType, if (edge == "shadow") SHADOW_COLOR else d(outlineColor), face))
        v.setApplyEmbeddedStyles(true)
        val bottom = (0.06f + offset / 250f).coerceIn(0f, 0.4f)
        v.setBottomPaddingFraction(bottom)
        v.post {
            val extra = (v.height * (offset / 400f)).toInt().coerceAtLeast(0)
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, extra)
        }
    }

    private fun mpvColor(c: Int) = String.format(Locale.US, "#%08X", c)

    /** MPV (Vision's NuvioMpvSurfaceView.applySubtitleStyle + mpvSubtitleEdges). */
    fun mpv(set: (String, String) -> Unit, hdr: Boolean) {
        val norm = (offset + 20).toDouble() / 70.0
        val subPos = 103.4 - norm * (103.4 - 72.4)
        val marginY = (norm * 60).toInt()
        val background = (backColor ushr 24) > 0
        val (outline, shadow, border, back) = when {
            edge == "shadow" -> listOf("0", "1.5", "outline-and-shadow", mpvColor(SHADOW_COLOR))
            else -> listOf(if (edge == "outline") "1" else "0", if (background) "5" else "0",
                if (background) "background-box" else "outline-and-shadow", mpvColor(if (hdr) hdrSubColor(backColor) else backColor))
        }
        set("sub-scale", String.format(Locale.US, "%.2f", sizePct / 100.0))
        set("sub-bold", if (p.subBold.value) "yes" else "no")
        set("sub-outline-size", outline)
        set("sub-shadow-offset", shadow)
        set("sub-border-style", border)
        set("sub-back-color", back)
        set("sub-outline-color", mpvColor(if (hdr) hdrSubColor(outlineColor) else outlineColor))
        set("sub-color", mpvColor(if (hdr) hdrSubColor(textColor) else textColor))
        set("sub-pos", String.format(Locale.US, "%.1f", subPos))
        set("sub-margin-y", marginY.toString())
    }
}
