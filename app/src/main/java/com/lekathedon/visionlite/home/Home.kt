package com.lekathedon.visionlite.home

import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lekathedon.visionlite.addons.MetaPreview
import com.lekathedon.visionlite.ui.ArtTint
import com.lekathedon.visionlite.ui.Button
import com.lekathedon.visionlite.ui.Focusable
import com.lekathedon.visionlite.ui.Img
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.Screen
import com.lekathedon.visionlite.ui.snapSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

private val Start = 48.dp
private val HeroH = 230.dp
/** Poster size (S / M / L) and ratings badge from Settings. */
private data class PosterLook(val w: androidx.compose.ui.unit.Dp, val h: androidx.compose.ui.unit.Dp, val ratings: Boolean)
private val LocalPoster = androidx.compose.runtime.staticCompositionLocalOf { PosterLook(120.dp, 180.dp, false) }
/** The poster that had focus last, so focus goes back to it after the exit question. */
internal var LastFocus: FocusRequester? = null

/**
 * Home: focused title's details on top, rows below. No motion: focus and scroll move in one frame, the focused row
 * snaps under the details. The details/tint follow focus after 120 ms, so holding a direction never loads art.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Home(nav: Nav) {
    val hub by com.lekathedon.visionlite.anime.Hubs.content.collectAsStateWithLifecycle()
    androidx.compose.runtime.key(hub) { HomeOf(nav, hub) }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HomeOf(nav: Nav, hub: com.lekathedon.visionlite.anime.Hub) {
    val vm = homeModel(hub)
    val homeScope = androidx.compose.runtime.rememberCoroutineScope()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val look = LocalLook.current
    val prefs = com.lekathedon.visionlite.App.instance.prefs
    val size by prefs.posterSize.flow.collectAsStateWithLifecycle()
    val ratings by prefs.ratings.flow.collectAsStateWithLifecycle()
    val pl = remember(size, ratings) { when (size) { "s" -> PosterLook(100.dp, 150.dp, ratings); "l" -> PosterLook(150.dp, 225.dp, ratings); else -> PosterLook(120.dp, 180.dp, ratings) } }
    val PosterW = pl.w; val PosterH = pl.h
    val hiddenText by com.lekathedon.visionlite.anime.Hubs.rowsHidden(hub).flow.collectAsStateWithLifecycle()
    val exitConfirm by prefs.exitConfirm.flow.collectAsStateWithLifecycle()
    var askExit by remember { mutableStateOf(false) }
    var cwHold by remember { mutableStateOf<com.lekathedon.visionlite.trakt.Resume?>(null) }
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    androidx.activity.compose.BackHandler(enabled = exitConfirm && !askExit) { askExit = true }
    var focused by remember { mutableStateOf<MetaPreview?>(null) }
    var shown by remember { mutableStateOf<MetaPreview?>(null) }
    LaunchedEffect(Unit) { snapshotFlow { focused }.collectLatest { delay(120); shown = it } }

    val cw by vm.continueWatching.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshContinue(force = com.lekathedon.visionlite.player.PlayerReturn.consume()) }
    val hidden = remember(hiddenText) { hiddenText.split('\n').filter { it.isNotEmpty() }.toSet() }
    val leaveOut by prefs.leaveOut.flow.collectAsStateWithLifecycle()
    val visible = rows.filter { it.key !in hidden && (it.items.isNotEmpty() || !it.done) &&
        !(leaveOut.isNotEmpty() && com.lekathedon.visionlite.recs.LeaveOut.rowHidden(it.title, it.key)) }
    val firstFocus = remember { FocusRequester() }
    val firstReady = cw.isNotEmpty() || visible.firstOrNull()?.items?.isNotEmpty() == true
    LaunchedEffect(firstReady) { if (firstReady && focused == null) runCatching { firstFocus.requestFocus() } }

    CompositionLocalProvider(LocalPoster provides pl) { Box(Modifier.fillMaxSize().background(look.page)) {
        if (!look.oled) ArtTint(shown?.background ?: shown?.poster)
        Hero(shown)
        Column(Modifier.fillMaxSize()) {
            TopBar(nav)
            Spacer(Modifier.height(HeroH - 52.dp))
            CompositionLocalProvider(LocalBringIntoViewSpec provides remember { snapSpec(0f) }) {
                LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 280.dp)) {
                    if (cw.isNotEmpty()) item(key = "cw") {
                        Column(Modifier.padding(bottom = 18.dp)) {
                            Text("Continue Watching", color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = Start, bottom = 8.dp))
                            CompositionLocalProvider(LocalBringIntoViewSpec provides remember { snapSpec(0f) }) {
                                LazyRow(contentPadding = PaddingValues(horizontal = Start), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    itemsIndexed(cw, key = { i, r -> "$i/${r.streamId}" }) { i, r ->
                                        val m = remember(r) { MetaPreview(r.imdb, r.type, r.label, com.lekathedon.visionlite.library.posterOf(r.imdb), com.lekathedon.visionlite.library.backdropOf(r.imdb)) }
                                        Box {
                                            Poster(m, if (i == 0) Modifier.focusRequester(firstFocus) else Modifier, onFocus = { focused = m },
                                                onClick = { nav.push(Screen.Streams(r.type, r.streamId, r.label, r.imdb)) }, onHold = { cwHold = r })
                                            if (r.progress > 0f) Box(Modifier.align(Alignment.BottomStart).padding(6.dp).width(PosterW - 12.dp).height(4.dp).background(Color(0x66FFFFFF))) {
                                                Box(Modifier.fillMaxHeight().width((PosterW - 12.dp) * (r.progress / 100f)).background(Palette.Accent))
                                            }
                                            if (r.season != null) Text("S${r.season}E${r.episode}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                                modifier = Modifier.align(Alignment.TopStart).background(Color(0xAA000000)).padding(horizontal = 6.dp, vertical = 2.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    items(visible.distinctBy { it.key }, key = { it.key }) { row ->
                        LaunchedEffect(row.key) { vm.need(row.key) }
                        Column(Modifier.padding(bottom = 18.dp)) {
                            Text(row.title, color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = Start, bottom = 8.dp))
                            if (row.items.isEmpty()) {
                                Spacer(Modifier.height(PosterH))
                            } else {
                                CompositionLocalProvider(LocalBringIntoViewSpec provides remember { snapSpec(0f) }) {
                                    LazyRow(contentPadding = PaddingValues(horizontal = Start), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        itemsIndexed(row.items, key = { i, m -> "$i/${m.id}" }) { i, m ->
                                            Poster(
                                                m,
                                                modifier = if (cw.isEmpty() && row.key == visible.first().key && i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                                                onFocus = {
                                                    focused = m
                                                    if (i >= row.items.size - 6) vm.more(row.key)
                                                },
                                                onClick = { if (m.id.startsWith("tt")) nav.push(Screen.Detail(m.type, m.id, m.name)) else homeScope.launch { com.lekathedon.visionlite.home.openTitle(nav, m) } }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        cwHold?.let { r -> CwMenu(r, nav, onDrop = { vm.dropContinue(r) }) { cwHold = null; LastFocus?.let { runCatching { it.requestFocus() } } } }
        if (askExit) ExitAsk(onLeave = { leaveApp(activity) }, onStay = { askExit = false; LastFocus?.let { runCatching { it.requestFocus() } } })
    } }
}

@Composable
private fun Poster(m: MetaPreview, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit, onHold: (() -> Unit)? = null) {
    val me = remember { FocusRequester() }
    val pl = LocalPoster.current
    val PosterW = pl.w; val PosterH = pl.h; val Ratings = pl.ratings
    Focusable(onClick, (if (onHold != null) modifier.onHoldOk(onHold) else modifier).focusRequester(me).size(PosterW, PosterH), onFocus = { LastFocus = me; onFocus() }) {
        Box(Modifier.fillMaxSize().background(Palette.Card)) {
            val art = com.lekathedon.visionlite.poster.Posters.poster(m.id, m.type, m.poster)
            if (art.isNullOrBlank()) {
                Text(m.name, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            } else {
                Img(art, PosterW, PosterH, Modifier.fillMaxSize(), fallback = m.poster)
            }
            if (Ratings && !m.imdbRating.isNullOrBlank()) Text("★ ${m.imdbRating}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).background(Color(0xAA000000)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}

@Composable
private fun TopBar(nav: Nav) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(start = Start, end = Start, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { com.lekathedon.visionlite.ui.VisionWordmark(28.dp) }
        if (com.lekathedon.visionlite.App.instance.prefs.clock.flow.collectAsStateWithLifecycle().value) Clock()
        Button("Search", { nav.push(Screen.Search) })
        Button("Library", { nav.push(Screen.Library) })
        Button("Live TV", { nav.push(Screen.LiveTv) })
        Button("Sports", { nav.push(Screen.Sports) })
        Button("Settings", { nav.push(Screen.Settings) })
    }
}

/** Focused title: backdrop on the right melting into the page, logo or name, facts, 3 lines of plot. */
@Composable
private fun Hero(m: MetaPreview?) {
    Box(Modifier.fillMaxWidth().height(HeroH + 40.dp)) {
        m ?: return@Box
        val art = m.background ?: m.poster
        Box(
            Modifier.align(Alignment.TopEnd).fillMaxWidth(0.66f).fillMaxHeight()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.45f to Color.Black), blendMode = BlendMode.DstIn)
                    drawRect(Brush.verticalGradient(0.5f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                }
        ) { Img(art, 640.dp, HeroH + 40.dp, Modifier.fillMaxSize()) }
        Column(Modifier.padding(start = Start, top = 64.dp).width(440.dp)) {
            if (!m.logo.isNullOrBlank()) Img(m.logo, 240.dp, 64.dp, Modifier.size(240.dp, 64.dp), ContentScale.Fit)
            else Text(m.name, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val facts = listOfNotNull(m.releaseInfo, m.runtime, m.imdbRating?.let { "IMDb $it" },
                m.genres.take(2).joinToString(" · ").ifBlank { null }).joinToString("  ·  ")
            if (facts.isNotBlank()) Text(facts, color = Color(0xCCFFFFFF), fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(top = 10.dp))
            m.description?.let {
                Text(it, color = Palette.TextDim, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** hh:mm, redrawn once a minute on the minute. */
@Composable
internal fun Clock() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val fmt = remember { android.text.format.DateFormat.getTimeFormat(context) }
    var now by remember { mutableStateOf(fmt.format(java.util.Date())) }
    LaunchedEffect(Unit) {
        while (true) { delay(60_000 - System.currentTimeMillis() % 60_000); now = fmt.format(java.util.Date()) }
    }
    Text(now, color = Palette.Text, fontSize = 16.sp, modifier = Modifier.padding(end = 6.dp))
}

/**
 * Leaving from Home. Speed and network › Close fully (Arc's "Full exit on close"): the app's process ends too, so its
 * memory goes back to the box at once; not while a recording runs or music plays (they'd stop).
 */
internal fun leaveApp(activity: android.app.Activity?) {
    activity ?: return
    val busy = com.lekathedon.visionlite.iptv.Recorder.active.value.isNotEmpty() || com.lekathedon.visionlite.music.MusicPlayer.now.value?.playing == true
    if (!com.lekathedon.visionlite.App.instance.prefs.fullExit.value || busy) { activity.finish(); return }
    activity.finishAndRemoveTask()
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 400)
}

@Composable
internal fun ExitAsk(onLeave: () -> Unit, onStay: () -> Unit) {
    val stay = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { stay.requestFocus() } }
    androidx.activity.compose.BackHandler { onStay() }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.background(Palette.Card).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Leave Nuvio Vision?", color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button("Stay", onStay, Modifier.focusRequester(stay))
                Button("Leave", onLeave)
            }
        }
    }
}
