// Vision's Echo look (Nuvio Echo, github.com/Laking22/Nuvio-Echo, GPL-3.0, as ported to Nuvio Vision), drawn the
// Lite way: no motion, focus and scroll in one frame, posters open to landscape at once, images at drawn size.
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lekathedon.visionlite.home

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.BitmapImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.addons.MetaPreview
import com.lekathedon.visionlite.library.backdropOf
import com.lekathedon.visionlite.library.posterOf
import com.lekathedon.visionlite.ui.Focusable
import com.lekathedon.visionlite.ui.Img
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.Screen
import com.lekathedon.visionlite.ui.snapSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

// ---- Echo's sizes (NewLookDimens, Medium, scaled by the poster size setting) ----

private data class EchoDims(
    val cwWidth: Dp, val cwHeight: Dp,
    val posterWidth: Dp, val posterHeight: Dp, val posterOpenWidth: Dp,
    val gap: Dp = 10.dp, val rowHead: Dp = 33.dp, val posterMeta: Dp = 24.dp, val rowSpace: Dp = 26.dp,
    val radius: Dp = 12.dp
) {
    companion object {
        fun of(size: String): EchoDims {
            val s = when (size) { "s" -> 0.85f; "l" -> 1.2f; else -> 1f }
            val ph = 198.dp * s
            return EchoDims(317.dp * s, 178.dp * s, 132.dp * s, ph, ph * (16f / 9f))
        }
    }
}

private val Start = 24.dp
private val TopBand = 52.dp
/** Where the rows start: the hero's copy sits above, the wall-to-wall backdrop behind both. */
private val RowsTop = 262.dp
private val ImdbYellow = Color(0xFFF5C518)
private val CardBackground = Color(0xFF222222)

/** Metahub art for IMDb ids when the catalog gave none. */
private fun MetaPreview.wide(): String? = background ?: if (id.startsWith("tt")) backdropOf(id) else poster
private fun MetaPreview.logoUrl(): String? = logo ?: if (id.startsWith("tt")) "https://images.metahub.space/logo/medium/$id/img" else null

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun EchoHome(nav: Nav) {
    val vm: HomeModel = viewModel()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val look = LocalLook.current
    val prefs = App.instance.prefs
    val size by prefs.posterSize.flow.collectAsStateWithLifecycle()
    val dims = remember(size) { EchoDims.of(size) }
    val hiddenText by prefs.rowsHidden.flow.collectAsStateWithLifecycle()
    val exitConfirm by prefs.exitConfirm.flow.collectAsStateWithLifecycle()
    var askExit by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? android.app.Activity
    androidx.activity.compose.BackHandler(enabled = exitConfirm && !askExit) { askExit = true }

    // The focused card; the hero, the wash and the details line follow it after 120 ms (holding a key loads no art).
    var focused by remember { mutableStateOf<HeroEntry?>(null) }
    var shown by remember { mutableStateOf<HeroEntry?>(null) }
    LaunchedEffect(Unit) { snapshotFlow { focused }.collectLatest { delay(120); shown = it } }

    val cw by vm.continueWatching.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshContinue(force = com.lekathedon.visionlite.player.PlayerReturn.consume()) }
    val hidden = remember(hiddenText) { hiddenText.split('\n').filter { it.isNotEmpty() }.toSet() }
    val visible = rows.filter { it.key !in hidden && (it.items.isNotEmpty() || !it.done) }
    // Vision's start screen: the Legacy billboard over the whole page, focus on View title. It shows the hero's
    // titles (Continue Watching first, else the first row) and, once a card has focus, that card's title.
    val firstItems = visible.firstOrNull { it.items.isNotEmpty() }?.items
    val heroEntries = remember(cw, firstItems) {
        if (cw.isNotEmpty()) cw.take(8).map { HeroEntry(MetaPreview(it.imdb, it.type, it.name, posterOf(it.imdb), backdropOf(it.imdb)), it.season, it.episode) }
        else firstItems.orEmpty().take(8).map { HeroEntry(it, null, null) }
    }
    var heroIndex by remember { mutableStateOf(0) }
    var heroFocused by remember { mutableStateOf(true) }
    val heroEntry = heroEntries.getOrNull(heroIndex.coerceAtMost((heroEntries.size - 1).coerceAtLeast(0)))
    val entry = if (heroFocused) heroEntry else shown ?: heroEntry
    // Vision's focus enrichment: the title's meta (logo, plot, episode name), one cached call per title shown.
    val enriched = remember { androidx.compose.runtime.mutableStateMapOf<String, com.lekathedon.visionlite.addons.Meta>() }
    LaunchedEffect(entry?.m?.id) {
        val m = entry?.m ?: return@LaunchedEffect
        if (m.id !in enriched) runCatching { App.instance.client.meta(m.type, m.id) }.getOrNull()?.let { enriched[m.id] = it }
    }
    val viewTitle = remember { FocusRequester() }
    val clockFocus = remember { FocusRequester() }
    val pillHome = remember { FocusRequester() }
    var clockFocused by remember { mutableStateOf(false) }
    var pillFocused by remember { mutableStateOf(false) }
    LaunchedEffect(heroEntries.isNotEmpty()) { if (heroEntries.isNotEmpty() && focused == null) { withFrameNanos { }; runCatching { viewTitle.requestFocus() } } }
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Vision's page scroll: just enough for the focused card to rest a margin above the screen's bottom.
    val pageSpec = remember(density) {
        val margin = with(density) { 30.dp.toPx() }
        object : androidx.compose.foundation.gestures.BringIntoViewSpec {
            @Suppress("OVERRIDE_DEPRECATION")
            override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> = androidx.compose.animation.core.snap()
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                offset + size - (containerSize - margin)
        }
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val heroPx = with(density) { 470.dp.toPx() }
    val art = entry?.let { enriched[it.m.id]?.background ?: it.m.wide() }
    val clockOn by App.instance.prefs.clock.flow.collectAsStateWithLifecycle()
    // Trailers (Vision's IMDb source): the billboard's after the delay on View title; a poster's in its opened card.
    val trailersOn by prefs.trailers.flow.collectAsStateWithLifecycle()
    val delayText by prefs.trailerDelay.flow.collectAsStateWithLifecycle()
    val sound by prefs.trailerSound.flow.collectAsStateWithLifecycle()
    val trailerLook = remember(trailersOn, delayText, sound) {
        TrailerLook(trailersOn, ((delayText.toDoubleOrNull() ?: 5.0) * 1000).toLong(), !sound)
    }
    var heroTrailer by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(heroFocused, heroEntry?.m?.id, trailerLook) {
        heroTrailer = null
        val id = heroEntry?.m?.id ?: return@LaunchedEffect
        if (!heroFocused || !trailerLook.on) return@LaunchedEffect
        heroTrailer = trailerAfterRest(id, trailerLook.delayMs, 2160)?.let { id to it }
    }

    Box(Modifier.fillMaxSize().background(look.page)) {
        if (!look.oled) AmbientPageWash(art, Modifier.fillMaxSize())
        // The billboard's art, wall to wall, fading as the page scrolls past it (read while drawing only).
        EchoBackdrop(art, heroTrailer?.takeIf { heroFocused && it.first == entry?.m?.id }?.second, trailerLook.muted,
            onEnded = { heroTrailer = null }, modifier = Modifier.graphicsLayer {
            alpha = if (listState.firstVisibleItemIndex > 0) 0f
            else (1f - listState.firstVisibleItemScrollOffset / heroPx).coerceIn(0f, 1f)
        })
        CompositionLocalProvider(LocalBringIntoViewSpec provides pageSpec, LocalTrailer provides trailerLook) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 300.dp)) {
                item(key = "hero") {
                    Billboard(
                        entry, enriched[entry?.m?.id], heroEntries.size, heroIndex, viewTitle, if (clockOn) clockFocus else pillHome,
                        onFocus = { if (it) heroFocused = true },
                        onStep = { d -> if (heroEntries.isNotEmpty()) heroIndex = (heroIndex + d + heroEntries.size) % heroEntries.size },
                        onOpen = { m -> nav.push(Screen.Detail(m.type, m.id, m.name)) }
                    )
                }
                if (cw.isNotEmpty()) item(key = "cw") {
                    EchoRow("Continue watching", dims, dims.cwHeight, shown?.m) {
                        itemsIndexed(cw, key = { _, r -> r.streamId }) { _, r ->
                            val e = remember(r) { HeroEntry(MetaPreview(r.imdb, r.type, r.name, posterOf(r.imdb), backdropOf(r.imdb)), r.season, r.episode) }
                            ContinueWatchingCard(
                                e.m, r.season?.let { "S$it E${r.episode}" }, r.progress / 100f, dims, Modifier,
                                onFocus = { focused = e; heroFocused = false },
                                onClick = { nav.push(Screen.Streams(r.type, r.streamId, r.label, r.imdb)) }
                            )
                        }
                    }
                }
                items(visible, key = { it.key }) { row ->
                    LaunchedEffect(row.key) { vm.need(row.key) }
                    EchoRow(row.title, dims, dims.posterHeight, shown?.m) {
                        if (row.items.isEmpty()) item { Box(Modifier.size(dims.posterWidth, dims.posterHeight).clip(RoundedCornerShape(dims.radius)).background(CardBackground)) }
                        itemsIndexed(row.items, key = { _, m -> m.id }) { i, m ->
                            PosterCard(
                                m, dims, Modifier,
                                onFocus = {
                                    focused = HeroEntry(m, null, null)
                                    heroFocused = false
                                    if (i >= row.items.size - 6) vm.more(row.key)
                                },
                                onClick = { nav.push(Screen.Detail(m.type, m.id, m.name)) }
                            )
                        }
                    }
                }
            }
        }
        // The menu stays hidden: Up from View title reaches the clock, Up again opens the pill (Vision's Home).
        TopPill(nav, pillFocused, pillHome, if (clockOn) clockFocus else viewTitle) { pillFocused = it }
        if (clockOn) ClockPill(
            Modifier.align(Alignment.TopEnd), clockFocus, pillHome, viewTitle,
            onFocus = { clockFocused = it }, onClick = { nav.push(Screen.Library) }
        )
        if (askExit) ExitAsk(onLeave = { activity?.finish() }, onStay = { askExit = false; LastFocus?.let { runCatching { it.requestFocus() } } })
    }
}

/** Trailer settings for the page, read once by Home. */
private data class TrailerLook(val on: Boolean, val delayMs: Long, val muted: Boolean)
private val LocalTrailer = androidx.compose.runtime.staticCompositionLocalOf { TrailerLook(false, 5_000L, false) }

/** Waits out the trailer delay (the lookup starts a second early, so the trailer is ready on time); null for none. */
/** [maxHeight]: the big billboard may reach 4K, opened posters stop at 1080p. */
private suspend fun trailerAfterRest(id: String, delayMs: Long, maxHeight: Int): String? {
    val lead = minOf(1_000L, delayMs)
    delay(delayMs - lead)
    val started = System.currentTimeMillis()
    val url = com.lekathedon.visionlite.trailer.Trailers.find(id, maxHeight) ?: return null
    delay((lead - (System.currentTimeMillis() - started)).coerceAtLeast(0L))
    return url
}

/** A billboard title: Continue Watching entries carry their episode. */
private data class HeroEntry(val m: MetaPreview, val season: Int?, val episode: Int?)

private val ViewTitleYellow = Color(0xFFD9DC8E)

// ---- Legacy billboard (NetflixLegacyBillboard): logo, details, IMDb, plot, View title, page dots ----

@Composable
private fun Billboard(
    entry: HeroEntry?, meta: com.lekathedon.visionlite.addons.Meta?, count: Int, index: Int,
    viewTitle: FocusRequester, clock: FocusRequester,
    onFocus: (Boolean) -> Unit, onStep: (Int) -> Unit, onOpen: (MetaPreview) -> Unit
) {
    Box(Modifier.fillMaxWidth().height(470.dp).onFocusChanged { onFocus(it.hasFocus) }) {
        val m = entry?.m ?: return@Box
        val video = if (entry.season != null) meta?.videos?.firstOrNull { it.season == entry.season && it.episode == entry.episode } else null
        Column(Modifier.align(Alignment.BottomStart).padding(start = 52.dp, bottom = 3.dp).width(430.dp)) {
            var failed by remember(m.id) { mutableStateOf(false) }
            val logo = meta?.logo ?: m.logoUrl()
            if (!logo.isNullOrBlank() && !failed) {
                val context = LocalContext.current
                val req = remember(logo) { ImageRequest.Builder(context).data(logo).build() }
                coil3.compose.AsyncImage(req, m.name, Modifier.widthIn(max = 230.dp).heightIn(max = 60.dp),
                    contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, onError = { failed = true })
            } else {
                Text((meta?.name ?: m.name).uppercase(), color = Color.White, fontSize = 30.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 10f)))
            }
            val dim = Color(0xFFBDBDBD)
            val parts = listOfNotNull(
                when (m.type) { "movie" -> "Movie"; "series" -> "TV Show"; else -> null },
                (meta?.genres ?: m.genres).firstOrNull(),
                (meta?.releaseInfo ?: m.releaseInfo)?.take(4)
            )
            Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                if (entry.season != null) {
                    Text("S${entry.season} E${entry.episode}", color = dim, fontSize = 14.sp, maxLines = 1)
                    Text("·", color = dim, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 8.dp))
                    Text(video?.title ?: video?.name ?: "Episode ${entry.episode}", color = dim, fontSize = 14.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                    if (parts.isNotEmpty()) Dot(dim)
                }
                parts.forEachIndexed { i, p -> if (i > 0) Dot(dim); Text(p, color = dim, fontSize = 14.sp, maxLines = 1) }
            }
            (meta?.imdbRating ?: m.imdbRating)?.takeIf { it.isNotBlank() }?.let {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RatingBadge(); Text("  $it", color = dim, fontSize = 13.sp)
                }
            }
            (video?.overview ?: meta?.description ?: m.description)?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Color(0xF2FFFFFF), fontSize = 14.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp))
            }
            val source = remember { MutableInteractionSource() }
            val f by source.collectIsFocusedAsState()
            Text(
                "View title", color = if (f) Color(0xFF1A1A1A) else Color(0xE6FFFFFF), fontSize = 15.sp,
                modifier = Modifier.padding(top = 16.dp).focusRequester(viewTitle)
                    .focusProperties { up = clock }
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) false
                        else when (e.key) {
                            Key.DirectionLeft -> { onStep(-1); true }
                            Key.DirectionRight -> { onStep(1); true }
                            else -> false
                        }
                    }
                    .clip(RoundedCornerShape(50)).background(if (f) ViewTitleYellow else Color(0x99383838))
                    .clickable(interactionSource = source, indication = null) { onOpen(meta?.let { MetaPreview(m.id, m.type, it.name) } ?: m) }
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }
        if (count > 1) Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 56.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            Text("‹", color = Color(0x99FFFFFF), fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp))
            repeat(count) { i ->
                Box(Modifier.size(if (i == index) 13.dp else 4.dp, 4.dp).clip(RoundedCornerShape(50))
                    .background(if (i == index) ViewTitleYellow else Color(0x66FFFFFF)))
            }
            Text("›", color = Color(0x99FFFFFF), fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

// ---- Top band: PillNavigationBar (glass pill, top centre, hidden until reached) and LegacyClockPill (top right) ----

@Composable
private fun BoxScope.TopPill(nav: Nav, open: Boolean, home: FocusRequester, down: FocusRequester, onFocus: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.align(Alignment.TopCenter).padding(top = 11.dp).height(34.dp)
            .graphicsLayer { alpha = if (open) 1f else 0f }
            .onFocusChanged { onFocus(it.hasFocus) }
            .clip(shape).background(Color(0x2EFFFFFF)).border(1.dp, Color(0x59FFFFFF), shape).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PillItem("Home", open, down, selected = true, modifier = Modifier.focusRequester(home)) {}
        PillItem("Search", open, down) { nav.push(Screen.Search) }
        PillItem("Library", open, down) { nav.push(Screen.Library) }
        PillItem("Live TV", open, down) { nav.push(Screen.LiveTv) }
        Box(Modifier.padding(horizontal = 4.dp).size(1.dp, 16.dp).background(Color(0x55FFFFFF)))
        PillItem(null, open, down) { nav.push(Screen.Settings) }
    }
}

@Composable
private fun PillItem(label: String?, open: Boolean, down: FocusRequester, selected: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val f by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(50)
    val tint = Color.White.copy(alpha = if (f || selected) 1f else 0.85f)
    Box(
        modifier.fillMaxHeight().focusProperties { this.down = down }.clip(shape)
            .then(if (f) Modifier.background(Color(0x40FFFFFF)).border(1.5.dp, Color(0xCCFFFFFF), shape)
                else if (selected) Modifier.background(Color(0x26FFFFFF)).border(1.dp, Color(0x40FFFFFF), shape) else Modifier)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        if (label != null) Text(label, color = tint, fontSize = 15.sp)
        else androidx.compose.foundation.Canvas(Modifier.size(17.dp)) {
            val w = size.width
            val c = androidx.compose.ui.geometry.Offset(w / 2, w / 2)
            for (k in 0 until 8) {
                val a = Math.toRadians(k * 45.0)
                drawLine(tint, c, androidx.compose.ui.geometry.Offset(c.x + (w * 0.47f * Math.cos(a)).toFloat(), c.y + (w * 0.47f * Math.sin(a)).toFloat()), strokeWidth = w * 0.18f)
            }
            drawCircle(tint, radius = w * 0.33f, center = c)
            drawCircle(Color(0xFF4A4A4E), radius = w * 0.14f, center = c)
        }
    }
}

/** Legacy's clock island: the time on a dark capsule, top right; focused, a white ring. OK opens Library. */
@Composable
private fun ClockPill(modifier: Modifier, me: FocusRequester, up: FocusRequester, down: FocusRequester, onFocus: (Boolean) -> Unit, onClick: () -> Unit) {
    val context = LocalContext.current
    val fmt = remember { android.text.format.DateFormat.getTimeFormat(context) }
    var now by remember { mutableStateOf(fmt.format(java.util.Date())) }
    LaunchedEffect(Unit) {
        while (true) { delay(60_000 - System.currentTimeMillis() % 60_000); now = fmt.format(java.util.Date()) }
    }
    val source = remember { MutableInteractionSource() }
    val f by source.collectIsFocusedAsState()
    LaunchedEffect(f) { onFocus(f) }
    val shape = RoundedCornerShape(50)
    Box(
        modifier.padding(top = 12.dp, end = 25.dp).height(25.dp)
            .then(if (f) Modifier.border(1.5.dp, Color.White, shape) else Modifier)
            .focusRequester(me).focusProperties { this.up = up; this.down = down }
            .clip(shape).background(Color(0xFF121316).copy(alpha = 0.86f))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center
    ) { Text(now, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

/** A row: title, cards snapped to the row's start, and the focused card's details line under it (Echo). */
@Composable
private fun EchoRow(
    title: String,
    dims: EchoDims,
    cardHeight: Dp,
    shown: MetaPreview?,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    var hasFocus by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = dims.rowSpace - dims.posterMeta + 6.dp).onFocusChanged { hasFocus = it.hasFocus }) {
        Text(
            title, color = Color(0xF2FFFFFF), fontSize = 18.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = Start).height(dims.rowHead)
        )
        CompositionLocalProvider(LocalBringIntoViewSpec provides remember { snapSpec(0f) }) {
            LazyRow(
                Modifier.fillMaxWidth().height(cardHeight),
                contentPadding = PaddingValues(start = Start, end = 600.dp),
                horizontalArrangement = Arrangement.spacedBy(dims.gap),
                content = content
            )
        }
        Box(Modifier.height(dims.posterMeta).padding(start = Start, top = 6.dp)) {
            if (hasFocus && shown != null) DetailsLine(shown)
        }
    }
}

// ---- Cards (NewLookCards: NewLookCard, PosterCard, ContinueWatchingCard, CardLogo, bottomShade) ----

/** A card with Echo's hairline on focus and its rounded corners; OK = [onClick]. */
@Composable
private fun EchoCard(
    width: Dp, height: Dp, radius: Dp, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit,
    /** Focused, the card opens out to this width (posters), in the same frame. */
    openWidth: Dp? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit
) {
    val me = remember { FocusRequester() }
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) { LastFocus = me; onFocus() } }
    val shape = RoundedCornerShape(radius)
    Box(
        modifier.focusRequester(me).size(if (focused && openWidth != null) openWidth else width, height).clip(shape).background(CardBackground)
            .then(if (focused) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
    ) { content(focused) }
}

/** A poster that opens out to the title's backdrop with its logo while focused: at once, no animation. */
@Composable
private fun PosterCard(m: MetaPreview, dims: EchoDims, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    EchoCard(dims.posterWidth, dims.posterHeight, dims.radius, modifier, onFocus = onFocus, onClick = onClick,
        openWidth = dims.posterOpenWidth) { f ->
        if (!f) {
            if (m.poster.isNullOrBlank()) Fallback(m.name) else Img(m.poster, dims.posterWidth, dims.posterHeight, Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Img(m.wide(), dims.posterOpenWidth, dims.posterHeight, Modifier.fillMaxSize())
                // The opened card plays the title's trailer once focus has rested (Vision's expanded-card target).
                val look = LocalTrailer.current
                var trailer by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(m.id, look) { trailer = if (look.on) trailerAfterRest(m.id, look.delayMs, 1080) else null }
                trailer?.let { com.lekathedon.visionlite.trailer.TrailerVideo(it, look.muted, Modifier.fillMaxSize()) { trailer = null } }
                Box(Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))
                ))
                CardLogo(m.logoUrl(), m.name, 0.55f, 0.4f, dims.posterOpenWidth, dims.posterHeight, 14.dp, 20f)
            }
        }
    }
}

/** Continue Watching: wide, title and episode bottom-left, progress along the bottom edge. */
@Composable
private fun ContinueWatchingCard(
    m: MetaPreview, episode: String?, progress: Float, dims: EchoDims, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit
) {
    val hasProgress = progress > 0f
    EchoCard(dims.cwWidth, dims.cwHeight, dims.radius, modifier, onFocus, onClick) {
        Img(m.background, dims.cwWidth, dims.cwHeight, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().bottomShade())
        val shadow = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 8f))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 14.dp, bottom = if (hasProgress) 14.dp else 12.dp)) {
            Text(m.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = shadow)
            if (!episode.isNullOrBlank()) Text(episode, color = Color.White.copy(alpha = 0.78f), fontSize = 12.sp, maxLines = 1, style = shadow)
        }
        if (hasProgress) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.28f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Color.White))
            }
        }
    }
}

@Composable
private fun BoxScope.Fallback(title: String) {
    Text(title, color = Color(0xFFB3B3B3), fontSize = 13.sp, maxLines = 3, modifier = Modifier.align(Alignment.Center).padding(10.dp))
}

/** Logo bottom-left, or the title as bold text when there's no logo. */
@Composable
private fun BoxScope.CardLogo(
    logo: String?, title: String, maxWidthFraction: Float, maxHeightFraction: Float,
    cardWidth: Dp, cardHeight: Dp, inset: Dp, textSize: Float
) {
    var failed by remember(logo) { mutableStateOf(false) }
    if (!logo.isNullOrBlank() && !failed) {
        val context = LocalContext.current
        val request = remember(logo) { ImageRequest.Builder(context).data(logo).build() }
        coil3.compose.AsyncImage(
            model = request, contentDescription = title, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
            onError = { failed = true },
            modifier = Modifier.align(Alignment.BottomStart).padding(start = inset, bottom = inset)
                .widthIn(max = cardWidth * maxWidthFraction).heightIn(max = cardHeight * maxHeightFraction)
        )
    } else {
        Text(
            title, color = Color.White, fontSize = textSize.sp, fontWeight = FontWeight.ExtraBold, lineHeight = (textSize * 1.1f).sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 10f)),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = inset, bottom = inset - 2.dp).widthIn(max = cardWidth * 0.7f)
        )
    }
}

/** Clear for the top 45%, then down to black at 65% at the bottom; drawn only over the part that isn't clear. */
private fun Modifier.bottomShade(): Modifier = drawWithCache {
    val top = size.height * 0.45f
    val brush = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.65f), startY = top, endY = size.height)
    onDrawBehind { drawRect(brush, topLeft = androidx.compose.ui.geometry.Offset(0f, top), size = androidx.compose.ui.geometry.Size(size.width, size.height - top)) }
}

/** "Show • Drama, Mystery • 2022 • 55m • IMDb 8.7", under a row's focused card. */
@Composable
private fun DetailsLine(m: MetaPreview, color: Color = Color(0xFFCFCFCF), dotColor: Color = Color(0xFF777777)) {
    val parts = listOfNotNull(
        when (m.type) { "movie" -> "Movie"; "series" -> "Show"; else -> null },
        m.genres.take(2).joinToString(", ").takeIf { it.isNotBlank() },
        m.releaseInfo?.takeIf { it.isNotBlank() },
        m.runtime?.takeIf { it.isNotBlank() }
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { i, part ->
            if (i > 0) Dot(dotColor)
            Text(part, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        if (!m.imdbRating.isNullOrBlank()) {
            if (parts.isNotEmpty()) Dot(dotColor)
            RatingBadge()
            Text(" ${m.imdbRating}", color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun Dot(color: Color) = Text("•", color = color, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))

@Composable
private fun RatingBadge() {
    Text(
        "IMDb", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em,
        modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(ImdbYellow).padding(horizontal = 3.dp, vertical = 1.dp)
    )
}

// ---- The billboard's art (NetflixLegacyBackdrop): wall to wall, darkened on the left and at the bottom ----

@Composable
private fun EchoBackdrop(url: String?, trailer: String?, muted: Boolean, onEnded: () -> Unit, modifier: Modifier) {
    url ?: return
    Box(modifier.fillMaxSize()) {
        Img(url, 960.dp, 540.dp, Modifier.fillMaxSize())
        if (trailer != null) com.lekathedon.visionlite.trailer.TrailerVideo(trailer, muted, Modifier.fillMaxSize(), onEnded)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xE6000000), 0.35f to Color(0x80000000), 0.7f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 0.9f to Color(0xE6000000), 1f to Color.Black)))
    }
}

// ---- AmbientWash.kt (Echo's page colour, Vision speed pass: no crossfade) ----

private const val WASH_READ_WIDTH = 64
private const val WASH_READ_HEIGHT = 36
private const val WASH_BLUR_PASSES = 3
private const val WASH_BLUR_RADIUS = 5
private const val WASH_KEEP_WIDTH = 192
private const val WASH_KEEP_HEIGHT = 108
private const val WASH_MEAN_VALUE = 0.40f
private const val WASH_MAX_LIFT = 3f
private const val WASH_TOP_LIGHT = 1.0f
private const val WASH_BOTTOM_LIGHT = 0.62f
private const val WASH_SATURATION = 1.25f

private val washCache = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 12
}

/** The focused title's art blurred until only its colours are left, darkened toward the rows; one stretched image per frame. */
@Composable
private fun AmbientPageWash(url: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<ImageBitmap?>(url?.let { synchronized(washCache) { washCache[it] } }) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) return@LaunchedEffect
        val next = synchronized(washCache) { washCache[url] } ?: withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(context).data(url).allowHardware(false)
                .size(coil3.size.Size(WASH_READ_WIDTH, WASH_READ_HEIGHT)).build()
            val bitmap = ((context.imageLoader.execute(request) as? SuccessResult)?.image as? BitmapImage)?.bitmap
            bitmap?.let(::washOf)?.also { synchronized(washCache) { washCache[url] = it } }
        } ?: return@LaunchedEffect
        shown = next
    }
    Box(modifier.drawBehind {
        val img = shown ?: return@drawBehind
        drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset.Zero,
            IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low)
    })
}

private fun washOf(source: Bitmap): ImageBitmap? {
    if (source.width <= 0 || source.height <= 0) return null
    val small = if (source.width == WASH_READ_WIDTH && source.height == WASH_READ_HEIGHT) source
    else Bitmap.createScaledBitmap(source, WASH_READ_WIDTH, WASH_READ_HEIGHT, true)
    val w = small.width
    val h = small.height
    val pixels = IntArray(w * h)
    small.getPixels(pixels, 0, w, 0, 0, w, h)
    val r = FloatArray(w * h) { ((pixels[it] shr 16) and 0xFF).toFloat() }
    val g = FloatArray(w * h) { ((pixels[it] shr 8) and 0xFF).toFloat() }
    val b = FloatArray(w * h) { (pixels[it] and 0xFF).toFloat() }
    repeat(WASH_BLUR_PASSES) {
        for (channel in arrayOf(r, g, b)) {
            boxBlur(channel, w, h, WASH_BLUR_RADIUS, horizontal = true)
            boxBlur(channel, w, h, WASH_BLUR_RADIUS, horizontal = false)
        }
    }
    val hsv = FloatArray(3)
    var valueSum = 0f
    for (i in 0 until w * h) {
        android.graphics.Color.RGBToHSV(r[i].toInt(), g[i].toInt(), b[i].toInt(), hsv)
        valueSum += hsv[2]
    }
    val gain = (WASH_MEAN_VALUE / (valueSum / (w * h)).coerceAtLeast(0.01f)).coerceAtMost(WASH_MAX_LIFT)
    for (y in 0 until h) {
        val light = WASH_TOP_LIGHT + (WASH_BOTTOM_LIGHT - WASH_TOP_LIGHT) * (y / (h - 1f))
        for (x in 0 until w) {
            val i = y * w + x
            android.graphics.Color.RGBToHSV(r[i].toInt(), g[i].toInt(), b[i].toInt(), hsv)
            hsv[1] = (hsv[1] * WASH_SATURATION).coerceAtMost(0.9f)
            hsv[2] = (hsv[2] * gain).coerceAtMost(0.75f) * light
            pixels[i] = android.graphics.Color.HSVToColor(hsv)
        }
    }
    val blurred = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    return Bitmap.createScaledBitmap(blurred, WASH_KEEP_WIDTH, WASH_KEEP_HEIGHT, true).asImageBitmap()
}

private fun boxBlur(channel: FloatArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val length = if (horizontal) w else h
    val line = FloatArray(length)
    val span = radius * 2 + 1
    for (l in 0 until lines) {
        fun at(k: Int) = if (horizontal) l * w + k else k * w + l
        for (k in 0 until length) line[k] = channel[at(k)]
        var sum = 0f
        for (k in -radius..radius) sum += line[k.coerceIn(0, length - 1)]
        for (k in 0 until length) {
            channel[at(k)] = sum / span
            sum += line[(k + radius + 1).coerceAtMost(length - 1)] - line[(k - radius).coerceAtLeast(0)]
        }
    }
}
