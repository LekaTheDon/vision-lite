package com.lekathedon.visionlite.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.detail.Imdb
import kotlinx.coroutines.delay

/**
 * Netflix's maturity rating (user 2026-11-25): top left once the picture starts, a slim accent bar, the certificate
 * (IMDb's: TV-MA, R, 16…) and the warnings IMDb's parents guide rates Moderate or Severe ("violence, language…").
 * It stays 30 s, so it is still there after the TV's own Dolby Vision / HDR logo has gone, then rolls up and leaves.
 * Cost: the details page's IMDb request, usually already cached; nothing when the title has no certificate.
 */
object Maturity {
    data class Info(val rating: String, val warnings: List<String>)

    private val WORDS = listOf(
        "sex" to "sex & nudity", "violence" to "violence", "profanity" to "language",
        "alcohol" to "substances", "frightening" to "frightening scenes")

    fun of(t: Imdb.Title?): Info? {
        val cert = t?.certificate?.takeIf { it.isNotBlank() && !it.equals("Not Rated", true) && !it.equals("Unrated", true) } ?: return null
        val rank = mapOf("severe" to 0, "moderate" to 1)
        val warn = t.guide.mapNotNull { g ->
            val r = rank[g.severity.lowercase()] ?: return@mapNotNull null
            val w = WORDS.firstOrNull { g.category.lowercase().contains(it.first) }?.second ?: return@mapNotNull null
            r to w
        }.sortedBy { it.first }.map { it.second }.distinct()
        return Info(cert, warn)
    }
}

/** The rating for [id] (an IMDb id, episodes "tt…:s:e"), shown for 30 s from [started]. */
@Composable
fun MaturityBadge(id: String, started: Boolean, modifier: Modifier = Modifier) {
    val imdb = id.substringBefore(':')
    val info by produceState<Maturity.Info?>(null, imdb) {
        if (!Imdb.isId(imdb) || !com.lekathedon.visionlite.App.instance.prefs.ageRating.value) return@produceState
        value = Maturity.of(Imdb.cachedTitle(imdb) ?: runCatching { Imdb.title(imdb) }.getOrNull())
    }
    var show by remember(imdb) { mutableStateOf(false) }
    var done by remember(imdb) { mutableStateOf(false) }
    LaunchedEffect(started, info) {
        if (!started || info == null || done) return@LaunchedEffect
        delay(1_200)   // after the first picture settles, as Netflix
        show = true
        delay(30_000)
        show = false; done = true
    }
    val i = info ?: return
    AnimatedVisibility(show, modifier,
        enter = expandHorizontally(tween(450), expandFrom = Alignment.Start) + fadeIn(tween(450)),
        exit = shrinkVertically(tween(550), shrinkTowards = Alignment.Top) + fadeOut(tween(450))) {
        val shade = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 8f))
        Box(Modifier.background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent)))
            .padding(start = 0.dp, end = 60.dp, top = 6.dp, bottom = 6.dp)) {
            Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).fillMaxHeight().background(com.lekathedon.visionlite.ui.Kit.accent))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(i.rating, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, style = shade, maxLines = 1)
                    if (i.warnings.isNotEmpty()) Text(i.warnings.joinToString(", "), color = Color.White.copy(alpha = 0.88f), fontSize = 15.sp,
                        style = shade, maxLines = 2, modifier = Modifier.widthIn(max = 520.dp).padding(top = 2.dp))
                }
            }
        }
    }
}
