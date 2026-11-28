package com.lekathedon.visionlite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.CircularProgressIndicator
import com.lekathedon.visionlite.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * While a title is being opened (links searched, then the first frame): the title's big backdrop with its logo, never
 * a black page (user 2026-11-22). Lite's own wait (2026-11-26): no link counts, just a calm "comet" that glides along
 * a hairline in the look's accent, one short step word, and Back to cancel. [sub] is kept for callers but not shown.
 * [id] = "tt…" or "tt…:s:e"; other ids show the name on the page colour. Cost: one infinite animation while it shows.
 */
@Composable
fun LoadingHero(id: String, title: String, status: String, sub: String = "", modifier: Modifier = Modifier, extra: @Composable () -> Unit = {}) {
    val imdb = id.substringBefore(':').takeIf { it.startsWith("tt") }
    Box(modifier.fillMaxSize().background(Palette.Base)) {
        if (imdb != null) Img(com.lekathedon.visionlite.data.Our.backdrop(imdb, "large"), 1280.dp, 720.dp, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xF0000000), 0.5f to Color(0x99000000), 0.85f to Color(0x22000000))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xE6000000))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 64.dp, bottom = 56.dp)) {
            Text("NOW OPENING", color = Kit.accent, fontSize = 11.sp, letterSpacing = 3.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            var logoFailed by remember(imdb) { mutableStateOf(imdb == null) }
            val name = title.replace(Regex("""\s+S\d+E\d+$"""), "")
            if (!logoFailed) coil3.compose.AsyncImage(com.lekathedon.visionlite.data.Our.logo(imdb.orEmpty(), "medium"), name,
                Modifier.sizeIn(maxWidth = 380.dp, maxHeight = 110.dp), contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                onError = { logoFailed = true })
            else DisplayText(name.uppercase(), 32.sp, maxLines = 2)
            Regex("""S(\d+)E(\d+)$""").find(title)?.let { m ->
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("S${m.groupValues[1].toInt()} · E${m.groupValues[2].toInt()}", color = Kit.accent, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Text("   Season ${m.groupValues[1].toInt()}, episode ${m.groupValues[2].toInt()}", color = Palette.Text.copy(alpha = 0.75f), fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(26.dp))
            Comet(Modifier.width(300.dp))
            Text(status.removeSuffix("…").removeSuffix("...").uppercase(), color = Palette.Text.copy(alpha = 0.85f), fontSize = 11.sp, letterSpacing = 2.5.sp,
                modifier = Modifier.padding(top = 12.dp))
            extra()
        }
        Text("BACK TO CANCEL", color = Palette.Text.copy(alpha = 0.4f), fontSize = 10.sp, letterSpacing = 2.5.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 64.dp, bottom = 60.dp))
    }
}

/** A soft light gliding back and forth along a hairline (ease in and out), with a glow that breathes. */
@Composable
private fun Comet(modifier: Modifier = Modifier) {
    // Light boxes: a still comet in the middle (no frame every 16 ms while links are searched).
    if (com.lekathedon.visionlite.device.Tier.current == com.lekathedon.visionlite.device.Tier.Class.LIGHT) { CometAt(modifier, 0.5f, 1f); return }
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "comet")
    val x by t.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(1600, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        androidx.compose.animation.core.RepeatMode.Reverse), label = "x")
    val glow by t.animateFloat(0.55f, 1f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(800), androidx.compose.animation.core.RepeatMode.Reverse), label = "glow")
    CometAt(modifier, x, glow)
}

@Composable
private fun CometAt(modifier: Modifier, x: Float, glow: Float) {
    val accent = Kit.accent
    androidx.compose.foundation.Canvas(modifier.height(14.dp)) {
        val y = size.height / 2
        drawLine(Color.White.copy(alpha = 0.12f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 2f)
        val len = size.width * 0.3f
        val start = (size.width - len) * x
        drawLine(Brush.horizontalGradient(listOf(Color.Transparent, accent.copy(alpha = glow), Color.Transparent), start, start + len),
            androidx.compose.ui.geometry.Offset(start, y), androidx.compose.ui.geometry.Offset(start + len, y), strokeWidth = 4f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(accent.copy(alpha = glow * 0.35f), radius = 7f * glow + 3f, center = androidx.compose.ui.geometry.Offset(start + len / 2, y))
        drawCircle(Color.White, radius = 3f, center = androidx.compose.ui.geometry.Offset(start + len / 2, y))
    }
}

/** Random backdrops per hub for [HubLoading] (well-known titles with good metahub backdrops; no network but the one picture). */
private val HUB_BACKDROPS = mapOf(
    "ANIME" to listOf("tt2560140", "tt9335498", "tt12343534", "tt0388629", "tt22248376", "tt13616990", "tt13706018", "tt21209876", "tt0877057", "tt0213338", "tt1355642", "tt0988824"),
    "NUVIO" to listOf("tt15239678", "tt15398776", "tt0903747", "tt3581920", "tt0816692", "tt1856101", "tt11198330", "tt1877830", "tt2788316", "tt1392190", "tt7660850", "tt0944947"),
)

/**
 * A hub's loading screen (user 2026-11-26), the same kind as opening a title: a random backdrop of the hub's kind,
 * "Vision" with the hub's word small beside it (as "Vision" sits by "Nuvio"), the comet and one short word.
 * [hub] = "NUVIO" (Films & TV), "ANIME" or "IPTV" (Live TV). Cost: one picture + one infinite animation while it shows.
 */
@Composable
fun HubLoading(hub: String, status: String = "Loading", modifier: Modifier = Modifier) {
    val pick = remember(hub) { (HUB_BACKDROPS[hub] ?: HUB_BACKDROPS.getValue("NUVIO")).random() }
    val word = when (hub) { "ANIME" -> "Anime"; "IPTV" -> "Live TV"; else -> "Films & TV" }
    Box(modifier.fillMaxSize().background(Palette.Base)) {
        Img(com.lekathedon.visionlite.data.Our.backdrop(pick, "large"), 1280.dp, 720.dp, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xF0000000), 0.5f to Color(0x99000000), 0.85f to Color(0x22000000))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xE6000000))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 64.dp, bottom = 56.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                VisionWordmark(180.dp)
                Text(word, color = Kit.accent, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 10.dp, bottom = 4.dp))
            }
            Spacer(Modifier.height(26.dp))
            Comet(Modifier.width(300.dp))
            Text(status.removeSuffix("…").removeSuffix("...").uppercase(), color = Palette.Text.copy(alpha = 0.85f), fontSize = 11.sp, letterSpacing = 2.5.sp,
                modifier = Modifier.padding(top = 12.dp))
        }
    }
}
