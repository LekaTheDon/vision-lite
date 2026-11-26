package com.lekathedon.visionlite.player

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.lekathedon.visionlite.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.detail.Imdb
import kotlinx.coroutines.delay
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Old Vision's two start-of-play overlays, copied as they were (ParentalGuideOverlay, DisplayModeOverlay and their
 * StaggeredOverlayTimeline): top left the content warnings, top right the refresh rate and resolution, each a thin
 * line that grows, rows that fade in one by one, then out in reverse. Vision held them 5 s; Lite holds 15 s (user
 * 2026-11-25), so they outlast the TV's own Dolby Vision / HDR logo. Cost: the IMDb guide is the details page's
 * (usually cached); the display info is one read of the display mode.
 */

private val ROW_HEIGHT = 18.dp
/** The start overlays' blood-dark red (user 2026-11-26: dark red, not brown, not the old bright red); values stay white. */
internal val OverlayRed = Color(0xFF8A0303)
private val ROW_GAP = 2.dp
internal const val STAGGERED_OVERLAY_HOLD_MS = 15_000L

internal data class StaggeredOverlayFrame(val containerAlpha: Float, val lineFraction: Float, val itemAlphas: List<Float>) {
    companion object { fun hidden(count: Int) = StaggeredOverlayFrame(0f, 0f, List(count) { 0f }) }
}

internal class StaggeredOverlayTimeline(val count: Int, fadeScale: Float) {
    private val scale = fadeScale.coerceIn(0f, 1f)
    val containerInMs = fade(300)
    val lineInMs = fade(400)
    val itemInMs = fade(200)
    val itemOutMs = fade(150)
    val lineOutMs = fade(300)
    val containerOutMs = fade(200)
    val lineInStart: Long = containerInMs
    val itemInStarts: List<Long>
    val shownAt: Long
    val holdEnd: Long
    val itemOutStarts: List<Long>
    val lineOutStart: Long
    val containerOutStart: Long
    val totalMs: Long

    init {
        var cursor = lineInStart + lineInMs
        itemInStarts = List(count) { val start = cursor + 80; cursor = start + itemInMs; start }
        shownAt = cursor
        cursor += STAGGERED_OVERLAY_HOLD_MS
        holdEnd = cursor
        val outStarts = LongArray(count)
        for (i in (count - 1) downTo 0) { outStarts[i] = cursor + 60; cursor = outStarts[i] + itemOutMs }
        itemOutStarts = outStarts.toList()
        lineOutStart = cursor + 100
        cursor = lineOutStart + lineOutMs
        containerOutStart = cursor + 200
        totalMs = containerOutStart + containerOutMs
    }

    fun frameAt(elapsedMs: Long): StaggeredOverlayFrame {
        val container = if (elapsedMs < containerOutStart) eased(elapsedMs, 0L, containerInMs) else 1f - eased(elapsedMs, containerOutStart, containerOutMs)
        val line = if (elapsedMs < lineOutStart) eased(elapsedMs, lineInStart, lineInMs) else 1f - eased(elapsedMs, lineOutStart, lineOutMs)
        val items = List(count) { i ->
            if (elapsedMs < itemOutStarts[i]) eased(elapsedMs, itemInStarts[i], itemInMs) else 1f - eased(elapsedMs, itemOutStarts[i], itemOutMs)
        }
        return StaggeredOverlayFrame(container, line, items)
    }

    private fun fade(baseMs: Int): Long = (baseMs * scale).toLong()
    private fun eased(elapsedMs: Long, startMs: Long, durationMs: Long): Float = when {
        elapsedMs < startMs -> 0f
        durationMs <= 0L -> 1f
        else -> FastOutSlowInEasing.transform(((elapsedMs - startMs).toFloat() / durationMs).coerceIn(0f, 1f))
    }
}

internal suspend fun runStaggeredOverlay(count: Int, alreadyShown: Boolean = false, onFrame: (StaggeredOverlayFrame) -> Unit) {
    val fadeScale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    val timeline = StaggeredOverlayTimeline(count, fadeScale)
    val offsetMs = if (alreadyShown) timeline.shownAt else 0L
    val startedAtMs = withFrameMillis { it }
    while (true) {
        val elapsedMs = withFrameMillis { it } - startedAtMs + offsetMs
        if (elapsedMs >= timeline.totalMs) { onFrame(StaggeredOverlayFrame.hidden(count)); return }
        onFrame(timeline.frameAt(elapsedMs))
        if (elapsedMs >= timeline.shownAt && elapsedMs < timeline.holdEnd) delay(timeline.holdEnd - elapsedMs)
    }
}

data class ParentalWarning(val label: String, val severity: String)

/** Vision's list: IMDb parents guide, Severe first, then Moderate, Mild; "None" left out; at most 5. */
private fun warningsOf(t: Imdb.Title?): List<ParentalWarning> {
    val labels = listOf("nudity" to "Nudity", "sex" to "Nudity", "violence" to "Violence", "profanity" to "Profanity",
        "alcohol" to "Alcohol/Drugs", "frightening" to "Frightening")
    val order = mapOf("severe" to 0, "moderate" to 1, "mild" to 2)
    return t?.guide.orEmpty().mapNotNull { g ->
        val sev = g.severity.lowercase().takeIf { it in order } ?: return@mapNotNull null
        val label = labels.firstOrNull { g.category.lowercase().contains(it.first) }?.second ?: return@mapNotNull null
        Triple(order[sev]!!, label, sev.replaceFirstChar { it.uppercase() })
    }.distinctBy { it.second }.sortedBy { it.first }.map { ParentalWarning(it.second, it.third) }.take(5)
}

/** Vision's ParentalGuideOverlay, once from the first picture: top left. */
@Composable
fun MaturityBadge(id: String, started: Boolean, modifier: Modifier = Modifier) {
    val imdb = id.substringBefore(':')
    val warnings by produceState(emptyList<ParentalWarning>(), imdb) {
        if (!Imdb.isId(imdb) || !com.lekathedon.visionlite.App.instance.prefs.ageRating.value) return@produceState
        value = warningsOf(Imdb.cachedTitle(imdb) ?: runCatching { Imdb.title(imdb) }.getOrNull())
    }
    if (warnings.isEmpty()) return
    val count = warnings.size
    val totalLineHeight = (ROW_HEIGHT.value * count) + (ROW_GAP.value * (count - 1))
    var frame by remember(imdb) { mutableStateOf(StaggeredOverlayFrame.hidden(count)) }
    var shown by remember(imdb) { mutableStateOf(false) }
    LaunchedEffect(started, count) {
        if (!started || shown) return@LaunchedEffect
        shown = true
        runStaggeredOverlay(count) { frame = it }
    }
    if (frame.containerAlpha <= 0f) return
    Row(modifier.alpha(frame.containerAlpha).padding(start = 32.dp, top = 24.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(3.dp).height((totalLineHeight * frame.lineFraction).dp).clip(RoundedCornerShape(1.dp))
            .background(OverlayRed))
        Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
            warnings.forEachIndexed { index, w ->
                Row(Modifier.height(ROW_HEIGHT).alpha(frame.itemAlphas.getOrNull(index) ?: 0f), verticalAlignment = Alignment.CenterVertically) {
                    Text(w.label, fontSize = 11.sp, color = OverlayRed, fontWeight = FontWeight.SemiBold)
                    Text(" · ", fontSize = 11.sp, color = Color.White.copy(alpha = 0.4f))
                    Text(w.severity, fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
                }
            }
        }
    }
}

private data class DisplayModeInfo(val width: Int, val height: Int, val refreshRate: Float)

private fun modeOf(context: Context): DisplayModeInfo? = runCatching {
    val d = (context as? Activity)?.window?.decorView?.display
        ?: (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(android.view.Display.DEFAULT_DISPLAY)
    val m = d.mode
    DisplayModeInfo(m.physicalWidth, m.physicalHeight, m.refreshRate)
}.getOrNull()

/** Vision's DisplayModeOverlay: top right, refresh rate and resolution, from the first picture and again whenever the
 *  TV's mode changes (frame-rate match). */
@Composable
fun DisplayModeBadge(started: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var info by remember { mutableStateOf<DisplayModeInfo?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(started) { if (started) { info = modeOf(context); visible = true } }
    DisposableEffect(started) {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) {}
            override fun onDisplayRemoved(id: Int) {}
            override fun onDisplayChanged(id: Int) {
                if (!started) return
                val now = modeOf(context)
                if (now != null && now != info) { info = now; visible = true }
            }
        }
        dm.registerDisplayListener(l, android.os.Handler(android.os.Looper.getMainLooper()))
        onDispose { dm.unregisterDisplayListener(l) }
    }
    val i = info ?: return
    val items = listOf("Refresh rate" to formatHz(i.refreshRate), "Resolution" to "${i.width}x${i.height}")
    val count = items.size
    val totalLineHeight = (ROW_HEIGHT.value * count) + (ROW_GAP.value * (count - 1))
    var frame by remember { mutableStateOf(StaggeredOverlayFrame.hidden(count)) }
    LaunchedEffect(visible, info) {
        if (!visible) return@LaunchedEffect
        runStaggeredOverlay(count, alreadyShown = frame.containerAlpha > 0f) { frame = it }
        visible = false
    }
    if (frame.containerAlpha <= 0f) return
    Column(modifier.alpha(frame.containerAlpha).padding(end = 32.dp, top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(ROW_GAP), horizontalAlignment = Alignment.End) {
                items.forEachIndexed { index, item ->
                    Row(Modifier.height(ROW_HEIGHT).alpha(frame.itemAlphas.getOrNull(index) ?: 0f), verticalAlignment = Alignment.CenterVertically) {
                        Text(item.first, fontSize = 11.sp, color = OverlayRed, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
                        Text(" - ", fontSize = 11.sp, color = Color.White.copy(alpha = 0.4f))
                        Text(item.second, fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.End)
                    }
                }
            }
            Box(Modifier.width(3.dp).height((totalLineHeight * frame.lineFraction).dp).clip(RoundedCornerShape(1.dp))
                .background(OverlayRed))
        }
    }
}

private fun formatHz(rate: Float): String {
    val rounded = (rate * 1000f).roundToInt() / 1000f
    val whole = rounded.roundToInt()
    val display = if (abs(rounded - whole.toFloat()) < 0.01f) whole.toString() else String.format("%.3f", rounded)
    return "$display Hz"
}


/**
 * What this file is, as plain words beside the content warnings (DebridStream's idea, Lite's words): 4K · Dolby Vision ·
 * Atmos · TrueHD 7.1 · Remux. No line; it fades in with the warnings, stays 15 s and fades out. Like the subtitles, it is
 * a little dimmer on HDR (so it doesn't glare) and brighter on SDR. Cost: one read of the playing formats ~1.5 s after the
 * first picture.
 */
@Composable
internal fun MediaBadge(started: Boolean, release: String, stats: () -> StatsSource, modifier: Modifier = Modifier) {
    var words by remember { mutableStateOf<List<String>>(emptyList()) }
    var hdr by remember { mutableStateOf(false) }
    LaunchedEffect(started) {
        if (!started || words.isNotEmpty()) return@LaunchedEffect
        delay(1_500)
        runCatching { val src = stats(); hdr = playing(src)?.hdr?.isNotEmpty() == true; words = mediaFacts(src, release).map { it.second } }
    }
    if (words.isEmpty()) return
    var frame by remember { mutableStateOf(StaggeredOverlayFrame.hidden(1)) }
    LaunchedEffect(words.size) { runStaggeredOverlay(1) { frame = it } }
    if (frame.containerAlpha <= 0f) return
    val ink = Color.White.copy(alpha = if (hdr) 0.62f else 0.9f)
    Text(words.joinToString("  ·  "), fontSize = 12.sp, color = ink, fontWeight = FontWeight.SemiBold, maxLines = 1,
        style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 6f)),
        modifier = modifier.alpha(frame.containerAlpha * (frame.itemAlphas.firstOrNull() ?: 0f)).padding(start = 28.dp, top = 26.dp))
}

internal fun mediaFacts(src: StatsSource, release: String): List<Pair<String, String>> {
    val p = playing(src) ?: return emptyList()
    val r = release.lowercase()
    val out = mutableListOf<Pair<String, String>>()
    when { p.width >= 3200 || p.height >= 2000 -> "4K"; p.width >= 1800 || p.height >= 1000 -> "1080p"; p.width >= 1200 || p.height >= 700 -> "720p"; p.height > 0 -> "${p.height}p"; else -> null }
        ?.let { out += "Video" to it }
    when (p.hdr) { "DV" -> "Dolby Vision"; "HDR10" -> if (Regex("""hdr10\+|hdr10plus""").containsMatchIn(r)) "HDR10+" else "HDR10"; "HLG" -> "HLG"; else -> null }?.let { out += "HDR" to it }
    val atmos = p.audioCodec == "E-AC-3-JOC" || (p.audioCodec in setOf("TRUEHD", "E-AC-3") && "atmos" in r)
    val codec = when (p.audioCodec) { "TRUEHD" -> "TrueHD"; "E-AC-3-JOC", "E-AC-3" -> "Dolby Digital+"; "AC-3" -> "Dolby Digital"; "DTS-HD" -> if ("ma" in r.split('.', ' ', '-')) "DTS-HD MA" else "DTS-HD"; "DTS" -> "DTS"; else -> null }
    val ch = when (p.channels) { 0 -> null; 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${p.channels} ch" }
    listOfNotNull(if (atmos) "Atmos" else null, codec, ch).joinToString(" · ").ifBlank { null }?.let { out += "Audio" to it }
    when {
        "remux" in r -> "Remux"; Regex("""blu-?ray|bdrip|brrip""").containsMatchIn(r) -> "Blu-ray"
        Regex("""web-?dl""").containsMatchIn(r) -> "WEB-DL"; Regex("""webrip""").containsMatchIn(r) -> "WEBRip"; Regex("""hdtv""").containsMatchIn(r) -> "HDTV"; else -> null
    }?.let { out += "Source" to it }
    // No bitrate here (user 2026-11-26); Stats still has it.
    return out
}
