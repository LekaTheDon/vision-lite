package com.lekathedon.visionlite.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.lekathedon.visionlite.App
import org.json.JSONObject

/**
 * App language (user 2026-11-26), Haven-style: English is the source; assets/lang/<tag>.json maps each English
 * sentence to its translation, missing ones stay English. Numbers inside a sentence are matched as {0}, {1}… so
 * "12 min left" finds "{0} min left". Only the chosen language's file is read (once), so a language costs memory
 * only while it is in use; every file is ~30-40 KB in the APK.
 * Every screen's text goes through [Text] below (same signature as material3's), so nothing else needs changing.
 */
object Lang {
    /** Bundled languages: tag to its own name (the picker shows those). */
    val bundled: List<Pair<String, String>> by lazy {
        val files = runCatching { App.instance.assets.list("lang")?.toList() }.getOrNull().orEmpty().filter { it.endsWith(".json") }
        files.mapNotNull { f ->
            val tag = f.removeSuffix(".json")
            runCatching { App.instance.assets.open("lang/$f").bufferedReader().use { r -> JSONObject(r.readText()).optString("@name", tag) } }.getOrNull()?.let { tag to it }
        }.sortedBy { it.second }
    }

    /** "" = the TV's language when bundled, else English. */
    fun chosen(): String {
        val p = App.instance.prefs.appLang.value
        if (p.isNotBlank()) return p
        val sys = java.util.Locale.getDefault().language
        return if (bundled.any { it.first == sys }) sys else "en"
    }

    val rtl get() = chosen() in setOf("ar", "he", "fa", "ur")

    @Volatile private var loaded: String? = null
    @Volatile private var map: Map<String, String> = emptyMap()
    private val num = Regex("\\d+(?:[.,:]\\d+)*")

    private fun table(): Map<String, String> {
        val tag = chosen()
        if (tag == loaded) return map
        map = if (tag == "en") emptyMap() else runCatching {
            val o = App.instance.assets.open("lang/$tag.json").bufferedReader().use { JSONObject(it.readText()) }
            o.keys().asSequence().filter { !it.startsWith("@") }.associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
        loaded = tag
        return map
    }

    /** [s] in the app's language (English when there is no translation). */
    fun t(s: String): String {
        if (s.isEmpty()) return s
        val m = table()
        if (m.isEmpty()) return s
        m[s]?.let { return it }
        // Sentences with numbers: "{0} min left".
        val nums = num.findAll(s).map { it.value }.toList()
        if (nums.isEmpty() || nums.size > 4) return s
        var i = 0
        val key = num.replace(s) { "{${i++}}" }
        val tr = m[key] ?: return s
        var out = tr
        nums.forEachIndexed { k, v -> out = out.replace("{$k}", v) }
        return out
    }
}

/** material3's Text with the app language applied (every screen imports this one). */
@Composable
fun Text(
    text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null, fontWeight: FontWeight? = null, fontFamily: FontFamily? = null, letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null, textAlign: TextAlign? = null, lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip, softWrap: Boolean = true, maxLines: Int = Int.MAX_VALUE, minLines: Int = 1,
    onTextLayout: (TextLayoutResult) -> Unit = {}, style: TextStyle = androidx.compose.material3.LocalTextStyle.current,
) = androidx.compose.material3.Text(text = Lang.t(text), modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle,
    fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign,
    lineHeight = lineHeight, overflow = overflow, softWrap = softWrap, maxLines = maxLines, minLines = minLines, onTextLayout = onTextLayout, style = style)

@Composable
fun Text(
    text: AnnotatedString, modifier: Modifier = Modifier, color: Color = Color.Unspecified, fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null, fontWeight: FontWeight? = null, fontFamily: FontFamily? = null, letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null, textAlign: TextAlign? = null, lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip, softWrap: Boolean = true, maxLines: Int = Int.MAX_VALUE, minLines: Int = 1,
    inlineContent: Map<String, androidx.compose.foundation.text.InlineTextContent> = mapOf(),
    onTextLayout: (TextLayoutResult) -> Unit = {}, style: TextStyle = androidx.compose.material3.LocalTextStyle.current,
) = androidx.compose.material3.Text(text = text, modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle,
    fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign,
    lineHeight = lineHeight, overflow = overflow, softWrap = softWrap, maxLines = maxLines, minLines = minLines, inlineContent = inlineContent,
    onTextLayout = onTextLayout, style = style)
