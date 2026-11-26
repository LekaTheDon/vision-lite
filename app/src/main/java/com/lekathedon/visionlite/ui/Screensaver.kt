package com.lekathedon.visionlite.ui

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.addons.MetaPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Vision's Ambient Mode (user 2026-11-22): after Settings › Home and look › Screensaver minutes with no key pressed,
 * outside the players and while no video plays in the app (clock window, trailers), full-screen backdrops of titles from your Home rows (random, never the same twice in a row)
 * with a slow Ken Burns move, the logo and genres bottom-left. Any key closes it (that key does nothing else).
 * Cost: one check every 15 s while the app is open; images only while it shows.
 */
object Screensaver {
    val showing = MutableStateFlow(false)
    private val pool = LinkedHashMap<String, MetaPreview>()
    private var swallowUp = -1

    /** Home's filled rows hand their titles in (kept: the last 400). */
    fun feed(items: List<MetaPreview>) = synchronized(pool) {
        items.filter { it.id.startsWith("tt") }.forEach { pool[it.id] = it }
        while (pool.size > 400) pool.remove(pool.keys.first())
    }
    fun titles(): List<MetaPreview> = synchronized(pool) { pool.values.toList() }

    /** Videos playing inside the app (the clock's small window, trailers): watching is using the app. */
    private val playingNow = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Any, Boolean>())
    val videoPlaying get() = playingNow.isNotEmpty()

    /** Counts [p] as use of the app while it plays; call [untrack] when it's released. */
    fun track(p: androidx.media3.common.Player) {
        p.addListener(object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) playingNow += p else playingNow -= p; DpadPace.touch() }
        })
    }
    fun untrack(p: androidx.media3.common.Player) { playingNow -= p; DpadPace.touch() }

    /**
     * The slide's picture: TMDB's backdrop for that IMDb id first (curated per title), metahub's only without a TMDB
     * key or backdrop (user 2026-11-26: metahub's picture for Das Boot (1985) is an unrelated AI image of a woman).
     * Kept per title for the run; one TMDB call per new slide.
     */
    private val arts = java.util.concurrent.ConcurrentHashMap<String, String>()
    suspend fun backdrop(m: MetaPreview): String = arts[m.id] ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val o = com.lekathedon.visionlite.home.Tmdb.get("find/${m.id}?external_source=imdb_id")
        val r = o?.optJSONArray(if (m.type == "movie") "movie_results" else "tv_results")?.optJSONObject(0)
            ?: o?.optJSONArray("movie_results")?.optJSONObject(0) ?: o?.optJSONArray("tv_results")?.optJSONObject(0)
        com.lekathedon.visionlite.home.Tmdb.img(r?.optString("backdrop_path"), "original")
            ?: "https://images.metahub.space/background/large/${m.id}/img"
    }.also { arts[m.id] = it }

    fun dismiss(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_UP && e.keyCode == swallowUp) { swallowUp = -1; return true }
        if (!showing.value) return false
        if (e.action == KeyEvent.ACTION_DOWN) { showing.value = false; swallowUp = e.keyCode; DpadPace.touch() }
        return true
    }
}

private data class Motion(val s0: Float, val s1: Float, val x0: Float = 0f, val x1: Float = 0f, val y0: Float = 0f, val y1: Float = 0f)
private val MOTIONS = listOf(
    Motion(1.0f, 1.06f), Motion(1.07f, 1.01f), Motion(1.06f, 1.06f, -0.025f, 0.025f), Motion(1.06f, 1.06f, 0.025f, -0.025f),
    Motion(1.06f, 1.06f, 0f, 0f, 0.02f, -0.02f), Motion(1.02f, 1.08f, 0f, -0.025f, 0f, -0.02f), Motion(1.02f, 1.08f, 0f, 0.025f, 0f, 0.02f))

@Composable
fun ScreensaverHost(nav: Nav) {
    val minText by App.instance.prefs.screensaverMin.flow.collectAsState()
    val minutes = minText.toIntOrNull() ?: 0
    val showing by Screensaver.showing.collectAsState()
    val top = nav.current
    LaunchedEffect(minutes, top) {
        if (minutes <= 0 || top is Screen.Player) { Screensaver.showing.value = false; return@LaunchedEffect }
        DpadPace.touch()
        while (true) {
            delay(15_000)
            // A video playing in the app (clock window, trailer) is use: the idle minutes count from when it stops.
            if (Screensaver.videoPlaying) DpadPace.touch()
            if (!Screensaver.showing.value && com.lekathedon.visionlite.link.LinkState.front &&
                SystemClock.uptimeMillis() - DpadPace.lastInput >= minutes * 60_000L && Screensaver.titles().size >= 3) Screensaver.showing.value = true
        }
    }
    if (!showing) return
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    val ctx = LocalContext.current
    var slide by remember { mutableStateOf<MetaPreview?>(null) }
    var artUrl by remember { mutableStateOf<String?>(null) }
    var motion by remember { mutableStateOf(MOTIONS[0]) }
    val alpha = remember { Animatable(0f) }
    val titleA = remember { Animatable(0f) }
    val genreA = remember { Animatable(0f) }
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        var last: String? = null
        var lastMotion = -1
        while (true) {
            val all = Screensaver.titles()
            val next = all.filter { it.id != last }.randomOrNull() ?: break
            // Load it (screen size) before it shows, so the fade never starts on an empty frame.
            val art = Screensaver.backdrop(next)
            runCatching {
                coil3.SingletonImageLoader.get(ctx).execute(coil3.request.ImageRequest.Builder(ctx).data(art).build())
            }
            artUrl = art
            last = next.id
            val mi = MOTIONS.indices.filter { it != lastMotion }.random(); lastMotion = mi
            motion = MOTIONS[mi]; slide = next
            t.snapTo(0f); titleA.snapTo(0f); genreA.snapTo(0f)
            launch { t.animateTo(1f, tween(11_500, easing = LinearEasing)) }
            alpha.animateTo(1f, tween(900))
            delay(250); launch { titleA.animateTo(1f, tween(500)) }
            delay(350); launch { genreA.animateTo(1f, tween(450)) }
            delay(10_000)
            launch { genreA.animateTo(0f, tween(250)) }
            titleA.animateTo(0f, tween(350))
            alpha.animateTo(0f, tween(700))
            delay(300)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val m = slide ?: return@Box
        val p = t.value
        Box(Modifier.fillMaxSize().graphicsLayer {
            this.alpha = alpha.value
            val sc = motion.s0 + (motion.s1 - motion.s0) * p
            scaleX = sc; scaleY = sc
            translationX = (motion.x0 + (motion.x1 - motion.x0) * p) * size.width
            translationY = (motion.y0 + (motion.y1 - motion.y0) * p) * size.height
        }) {
            coil3.compose.AsyncImage(artUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 72.dp, bottom = 64.dp).widthIn(max = 560.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            var logoFailed by remember(m.id) { mutableStateOf(false) }
            Box(Modifier.graphicsLayer { this.alpha = titleA.value }) {
                if (!logoFailed) coil3.compose.AsyncImage(m.logo ?: "https://images.metahub.space/logo/medium/${m.id}/img", m.name,
                    Modifier.sizeIn(maxWidth = 460.dp, maxHeight = 150.dp), contentScale = ContentScale.Fit, onError = { logoFailed = true })
                else Text(m.name, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            }
            if (m.genres.isNotEmpty()) Text(m.genres.take(3).joinToString("  •  "), color = Color(0xE6FFFFFF), fontSize = 16.sp,
                modifier = Modifier.padding(top = 14.dp).graphicsLayer { this.alpha = genreA.value })
        }
    }
}
