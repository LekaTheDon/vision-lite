// Vision's Echo look (Nuvio Echo, github.com/Laking22/Nuvio-Echo, GPL-3.0, as ported to Vision), drawn the
// Lite way: no motion, focus and scroll in one frame, posters open to landscape at once, images at drawn size.
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.lekathedon.visionlite.home

import androidx.compose.animation.animateContentSize
import com.lekathedon.visionlite.ui.remembersFocus
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentSize
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
import com.lekathedon.visionlite.ui.Text
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
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.focusGroup
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
import com.lekathedon.visionlite.library.Saved
import com.lekathedon.visionlite.library.Saves
import com.lekathedon.visionlite.recs.Recs
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import com.lekathedon.visionlite.ui.Focusable
import com.lekathedon.visionlite.ui.Img
import com.lekathedon.visionlite.ui.spoiler
import androidx.compose.runtime.collectAsState
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.Screen
import com.lekathedon.visionlite.ui.snapSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

// ---- Echo's sizes (NewLookDimens, Medium, scaled by the poster size setting) ----

private data class EchoDims(
    val cwWidth: Dp, val cwHeight: Dp,
    val posterWidth: Dp, val posterHeight: Dp, val posterOpenWidth: Dp,
    val gap: Dp = 10.dp, val rowHead: Dp = 33.dp, val posterMeta: Dp = 24.dp, val rowSpace: Dp = 26.dp,
    val radius: Dp = 12.dp,
    /** Vision Echo's highlight card (ranked rows). */
    val highlightWidth: Dp = 396.dp, val highlightHeight: Dp = 234.dp
) {
    /** The big number's room left of a ranked card, and its size (NewLookDimens.rankPad / rankFontSize). */
    val rankPad: Dp get() = highlightHeight * 0.62f
    val rankFontSize: Float get() = highlightHeight.value * 1.18f
    companion object {
        fun of(size: String): EchoDims {
            val s = when (size) { "s" -> 0.85f; "l" -> 1.2f; else -> 1f }
            val ph = 198.dp * s
            return EchoDims(317.dp * s, 178.dp * s, 132.dp * s, ph, ph * (16f / 9f), highlightWidth = 396.dp * s, highlightHeight = 234.dp * s)
        }
    }
}

private val Start = 24.dp

/**
 * Where focus was on Home, kept while another page is open: the row's key and, per row, the card last focused.
 * Back to Home puts focus on that card again (the row was snapped to it, so it's the row's first visible card).
 */
internal object HomeFocus {
    var row: String? = null
    val index = HashMap<String, Int>()
    /** Set when Home leaves the screen with a card focused; the row clears it once it took focus back. */
    var restore = false
}

/** A card's place in its row, for [EchoCard] to register its focus requester ([RowSlots]). */
private class RowSlots(val key: String, val home: Boolean = true) {
    val reqs = HashMap<Int, FocusRequester>()
    /** Focus just came into this row from outside (Up/Down, back from a page): the next focused card is an entry. */
    var entered = false
    var state: androidx.compose.foundation.lazy.LazyListState? = null
    var scope: kotlinx.coroutines.CoroutineScope? = null
    /** Where a row is entered (user 2026-11-24): the card you moved to with Left/Right, else always the first. */
    val target: Int get() = RowMoves.index[key] ?: 0

    /** Focus to card [t], scrolling the row to it first when it isn't laid out. */
    fun goTo(t: Int) {
        reqs[t]?.let { if (runCatching { it.requestFocus() }.isSuccess) return }
        scope?.launch {
            runCatching { state?.scrollToItem(t) }
            repeat(10) { withFrameNanos { }; reqs[t]?.let { if (runCatching { it.requestFocus() }.isSuccess) return@launch } }
        }
    }
    /** A page row's card last focused (Home keeps its own in [HomeFocus]). */
    var last: Int
        get() = PageFocus.index[key] ?: 0
        set(v) { PageFocus.index[key] = v }
}

/** The card last focused in each row of the other pages (by page + row), so going back up lands where you were. */
internal object PageFocus { val index = HashMap<String, Int>() }
/**
 * The card you moved to with Left/Right in each row (by row key), for every row (Home and pages). A row you only passed
 * through with Up/Down has none, so it is entered on its first card, never the 2nd or 3rd (user 2026-11-24).
 */
internal object RowMoves { val index = HashMap<String, Int>() }

/** Home's poster rows (key, title) for Settings' billboard choice, and their titles for the screensaver. */
object BillboardRows {
    val rows = kotlinx.coroutines.flow.MutableStateFlow<List<Pair<String, String>>>(emptyList())
    fun publish(list: List<Pair<String, String>>) { if (list.isNotEmpty()) rows.value = (rows.value + list).distinctBy { it.first } }
}
/**
 * The Home row whose loading card has focus (user 2026-11-22: holding Down sometimes jumped back up). The card used to
 * vanish the moment the row's titles came in, taking focus with it, so the next Down started again from the top. Now
 * the card stays until it has handed focus to the row's first title (or the next row, when the row came back empty).
 */
internal object LoadingHold { var key by androidx.compose.runtime.mutableStateOf<String?>(null) }

/** Home's Up/Down move under way: the row it is going to, and the job that takes focus there. */
private class RowMover {
    var target: String? = null
    /** Place in the row list of the last move's row (for a row that disappears while focus heads for it). */
    var lastAt = 0
    var job: kotlinx.coroutines.Job? = null
}

/** Each Home row's slots by row key, so focus can go back to a row after one of its cards was removed. */
private val RowRegistry = HashMap<String, RowSlots>()

/**
 * After "Not for me" / "Drop": the card is gone, so focus goes to the card now in its place (or the one before it, at the
 * row's end) in the same row, instead of falling back to the top of Home. Waits (up to ~2 s) for the row to drop it.
 */
private suspend fun refocusRow(key: String, index: Int, old: FocusRequester?, tries: Int = 120): Boolean {
    old?.let { runCatching { it.requestFocus() } }
    repeat(tries) {
        withFrameNanos { }
        val reqs = RowRegistry[key]?.reqs ?: return@repeat
        val target = reqs[index]?.takeIf { it !== old } ?: reqs[index - 1]?.takeIf { it !== old && reqs[index] == null }
        if (target != null && runCatching { target.requestFocus() }.isSuccess) {
            HomeFocus.row = key; HomeFocus.index[key] = if (reqs[index] === target) index else index - 1
            return true
        }
    }
    return false
}

/**
 * A row still loading: a card the remote can land on (an empty, unfocusable row used to stop Down dead: "Home won't
 * scroll"). When the titles arrive, focus moves to the row's first card; if the row turns out empty and goes away,
 * focus goes to the next row below that has cards.
 */
@Composable
private fun LoadingCard(rowKey: String, width: Dp, height: Dp, radius: Dp, below: () -> List<String>, scope: kotlinx.coroutines.CoroutineScope,
                        ready: Boolean = false) {
    val me = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val slots = LocalRowSlots.current
    val shape = RoundedCornerShape(radius)
    androidx.compose.runtime.DisposableEffect(slots) {
        slots?.reqs?.set(0, me)
        onDispose {
            if (slots?.reqs?.get(0) === me) slots.reqs.remove(0)
            // A page row (not Home): its first card takes focus once the titles are in.
            if (focused && slots != null && !slots.home) scope.launch {
                repeat(30) { withFrameNanos { }; slots.reqs[0]?.let { if (runCatching { it.requestFocus() }.isSuccess) return@launch } }
            }
            else if (focused && LoadingHold.key != rowKey) scope.launch {
                if (refocusRow(rowKey, 0, null, tries = 30)) return@launch
                for (k in below()) if (refocusRow(k, HomeFocus.index[k] ?: 0, null, tries = 5) || refocusRow(k, 0, null, tries = 5)) return@launch
            }
        }
    }
    LaunchedEffect(focused) { if (focused) { LastFocus = me; slots?.entered = false; slots?.let { if (it.home) { HomeFocus.row = it.key; HomeFocus.index[it.key] = 0 } } } }
    // Home: hold the card while it has focus; once the titles are in, give focus to the first one, then let go.
    LaunchedEffect(focused) {
        if (slots?.home != true) return@LaunchedEffect
        if (focused) LoadingHold.key = rowKey else if (LoadingHold.key == rowKey) LoadingHold.key = null
    }
    LaunchedEffect(ready, focused) {
        if (!ready || !focused || slots?.home != true) return@LaunchedEffect
        repeat(30) {
            withFrameNanos { }
            val first = slots.reqs[0]?.takeIf { it !== me }
            if (first != null && runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
        }
        for (k in below()) if (refocusRow(k, HomeFocus.index[k] ?: 0, null, tries = 5) || refocusRow(k, 0, null, tries = 5)) return@LaunchedEffect
        if (LoadingHold.key == rowKey) LoadingHold.key = null
    }
    Box(Modifier.focusRequester(me).onFocusChanged { focused = it.isFocused }.focusable().size(width, height).clip(shape)
        .background(com.lekathedon.visionlite.ui.Kit.card).then(if (focused) Modifier.border(2.dp, com.lekathedon.visionlite.ui.Kit.ringColor, shape) else Modifier))
}
private val LocalRowSlots = androidx.compose.runtime.compositionLocalOf<RowSlots?> { null }
private val LocalSlotIndex = androidx.compose.runtime.compositionLocalOf { -1 }

@Composable
private fun Slot(i: Int, content: @Composable () -> Unit) = CompositionLocalProvider(LocalSlotIndex provides i, content = content)
private val TopBand = 52.dp
/** Where the rows start: the hero's copy sits above, the wall-to-wall backdrop behind both. */
private val RowsTop = 262.dp

/** Metahub art for IMDb ids when the catalog gave none. */
private fun MetaPreview.wide(): String? = background ?: if (id.startsWith("tt")) backdropOf(id) else poster
private fun MetaPreview.logoUrl(): String? = logo ?: if (id.startsWith("tt")) com.lekathedon.visionlite.data.Our.logo(id, "medium") else null

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun EchoHome(nav: Nav) {
    val hub by com.lekathedon.visionlite.anime.Hubs.content.collectAsStateWithLifecycle()
    // Films & TV and Anime: each its own Home (rows, Continue watching, picks); the other stays as it was left.
    androidx.compose.runtime.key(hub) { EchoHomeOf(nav, hub) }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EchoHomeOf(nav: Nav, hub: com.lekathedon.visionlite.anime.Hub) {
    val vm = homeModel(hub)
    val rows by vm.rows.collectAsStateWithLifecycle()
    val look = LocalLook.current
    val prefs = App.instance.prefs
    val size by prefs.posterSize.flow.collectAsStateWithLifecycle()
    val dims = remember(size) { EchoDims.of(size) }
    val hiddenText by com.lekathedon.visionlite.anime.Hubs.rowsHidden(hub).flow.collectAsStateWithLifecycle()
    val exitConfirm by prefs.exitConfirm.flow.collectAsStateWithLifecycle()
    var askExit by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? android.app.Activity

    // The focused card; the hero, the wash and the details line follow it after 120 ms (holding a key loads no art).
    var focused by remember { mutableStateOf<HeroEntry?>(null) }
    var shown by remember { mutableStateOf<HeroEntry?>(null) }
    LaunchedEffect(Unit) { snapshotFlow { focused }.collectLatest { delay(120); shown = it } }

    val cw by vm.continueWatching.collectAsStateWithLifecycle()
    val watchedMode by prefs.watchedOnHome.flow.collectAsStateWithLifecycle()
    val watched by Watched.ids.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        val back = com.lekathedon.visionlite.player.PlayerReturn.consume()
        vm.refreshContinue(force = back)
        launch { runCatching { Watched.refresh(force = back) } }
    }
    LaunchedEffect(watchedMode) { runCatching { Watched.refresh() } }
    val hidden = remember(hiddenText) { hiddenText.split('\n').filter { it.isNotEmpty() }.toSet() }
    val traktOn = App.instance.trakt.connected.collectAsStateWithLifecycle().value != null
    // Leave out: a row about something left out ("Korean Thrillers", "Because you like Horror") isn't shown at all.
    val leaveOut by prefs.leaveOut.flow.collectAsStateWithLifecycle()
    val visible = rows.filter { r ->
        if (leaveOut.isNotEmpty() && com.lekathedon.visionlite.recs.LeaveOut.rowHidden(r.title, r.key)) return@filter false
        // A loaded row left empty by "Watched titles: Hide" has no card to land on: it's left out like an empty row.
        val shows = r.items.isNotEmpty() && !(watchedMode == "hide" && watched.isNotEmpty() && r.kind == RowKind.POSTER && r.items.all { it.id in watched })
        r.key !in hidden && (shows || !r.done || r.key == LoadingHold.key ||
            (VisionRows.isPicks(r.key) && traktOn))
    }
    // The phone companion's copy of Home (only while it is switched on; built off the main thread).
    val linkOn by prefs.phoneLink.flow.collectAsStateWithLifecycle()
    LaunchedEffect(linkOn, cw, rows) {
        if (!linkOn) return@LaunchedEffect
        delay(2_000)
        val list = visible.map { it.title to it.items }
        withContext(Dispatchers.Default) { com.lekathedon.visionlite.link.LinkState.publishRows(hub, cw, list) }
    }
    val recsBuilding by Recs.building(hub).collectAsStateWithLifecycle()
    var hold by remember { mutableStateOf<Pair<Row, MetaPreview>?>(null) }
    var cwHold by remember { mutableStateOf<com.lekathedon.visionlite.trakt.Resume?>(null) }
    val scope = rememberCoroutineScope()
    fun open(m: MetaPreview) {
        when (m.type) {
            "service" -> nav.push(Screen.Service(m.id.removePrefix("svc:")))
            "genre" -> nav.push(Screen.Genre(m.id.removePrefix("genre:")))
            "decade" -> nav.push(Screen.Decade(m.id.removePrefix("decade:")))
            "mood" -> nav.push(Screen.Mood(m.id.removePrefix("mood:")))
            "scout" -> nav.push(Screen.Scout(m.id.removePrefix("scout:")))
            "person" -> nav.push(Screen.Person(m.id, m.name))
            else -> if (m.id.startsWith("tt")) nav.push(Screen.Detail(m.type, m.id, m.name)) else scope.launch { openTitle(nav, m) }
        }
    }
    // Vision's start screen: the Legacy billboard over the whole page, focus on View title. It shows the hero's
    // titles (Continue Watching first, else the first row) and, once a card has focus, that card's title.
    // Billboard source (Settings › Home and look, user 2026-11-22): default = Continue watching then the first row;
    // a row key = that row; "random" = a different filled row on each visit, to keep Home fresh.
    val billSrc by prefs.billboardSource.flow.collectAsStateWithLifecycle()
    LaunchedEffect(visible.size, visible.sumOf { it.items.size }) { com.lekathedon.visionlite.ui.Screensaver.feed(visible.filter { it.kind == RowKind.POSTER }.flatMap { it.items.take(20) }) }
    LaunchedEffect(visible.size) { BillboardRows.publish(visible.filter { it.kind == RowKind.POSTER }.map { it.key to it.title }) }
    val pool = visible.filter { it.items.isNotEmpty() && it.kind == RowKind.POSTER }
    val randomKey = remember(pool.size > 0) { pool.randomOrNull()?.key }
    val chosen = when (billSrc) { "" -> null; "random" -> pool.firstOrNull { it.key == randomKey } ?: pool.firstOrNull(); else -> pool.firstOrNull { it.key == billSrc } }
    val firstItems = (chosen ?: visible.firstOrNull { it.items.isNotEmpty() })?.items
    val heroEntries = remember(cw, firstItems, chosen?.key) {
        if (cw.isNotEmpty() && chosen == null) cw.take(8).map { HeroEntry(MetaPreview(it.imdb, it.type, it.name, posterOf(it.imdb), backdropOf(it.imdb)), it.season, it.episode) }
        else firstItems.orEmpty().let { if (billSrc == "random") it.shuffled() else it }.take(8).map { HeroEntry(it, null, null) }
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
    var clockPanel by remember { mutableStateOf(false) }
    var pillFocused by remember { mutableStateOf(false) }
    /** Bumped by Left on the clock: the pill opens on its last item (the profile). */
    var pillEnd by remember { mutableStateOf(0) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { HomeFocus.restore = HomeFocus.row != null } }
    LaunchedEffect(heroEntries.isNotEmpty()) {
        if (heroEntries.isEmpty() || focused != null) return@LaunchedEffect
        // Back from a page opened on a card: that card's row takes focus back; View title only if it can't.
        if (HomeFocus.restore) { repeat(20) { withFrameNanos { } }; if (!HomeFocus.restore) return@LaunchedEffect; HomeFocus.restore = false }
        withFrameNanos { }; runCatching { viewTitle.requestFocus() }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Vision's page scroll: just enough for the focused card to rest a margin above the screen's bottom.
    val pageSpec = remember(density) {
        val margin = with(density) { 84.dp.toPx() } // the focused card's text (rating, reason, 2 lines of plot) stays on screen
        object : androidx.compose.foundation.gestures.BringIntoViewSpec {
            @Suppress("OVERRIDE_DEPRECATION")
            override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> = androidx.compose.animation.core.snap()
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                offset + size - (containerSize - margin)
        }
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Up/Down between rows, done by Home itself (user 2026-11-24: holding Down stopped on a row "as if it's the last",
    // then needed several presses). Compose's own search only finds rows already laid out, and while a held Down
    // outruns the layout it finds nothing and focus stays. Here every press names the next row by its place in the
    // list; a row not built yet is scrolled in and focused a frame later. Presses that come while a move is still
    // under way count on from where that move is going, so a long press keeps walking down row by row.
    val rowOrder by androidx.compose.runtime.rememberUpdatedState((if (cw.isNotEmpty()) listOf("cw") else emptyList()) + visible.map { it.key })
    val mover = remember { RowMover() }
    val moveRow: (Boolean) -> Boolean = moveRow@{ down ->
        val order = rowOrder
        val from = mover.target ?: HomeFocus.row ?: return@moveRow false
        var at = order.indexOf(from)
        // The row it was on or going to left the list (it loaded empty, or was hidden): carry on from where it stood.
        if (at < 0) at = mover.lastAt.coerceIn(0, (order.size - 1).coerceAtLeast(0)) - (if (down) 1 else 0)
        if (order.isEmpty()) return@moveRow false
        val to = at + if (down) 1 else -1
        if (to >= order.size) return@moveRow true          // the last row: stay
        if (to < 0) {                                       // above the first row: the billboard's View title
            mover.target = null; mover.job?.cancel()
            scope.launch { runCatching { listState.scrollToItem(0) }; repeat(3) { withFrameNanos { } }; runCatching { viewTitle.requestFocus() } }
            return@moveRow true
        }
        mover.target = order[to]; mover.lastAt = to
        if (mover.job?.isActive == true) return@moveRow true
        mover.job = scope.launch {
            var frames = 0
            while (frames < 90) {
                val key = mover.target ?: break
                // Gone while on the way: on to the next row that is still there.
                if (key !in rowOrder) {
                    val o = rowOrder
                    mover.target = o.getOrNull(mover.lastAt.coerceAtMost(o.size - 1)) ?: break
                    continue
                }
                val slots = RowRegistry[key]
                val req = slots?.let { it.reqs[it.target] ?: it.reqs[0] }
                if (slots != null && req != null) {
                    slots.entered = true
                    if (runCatching { req.requestFocus() }.isSuccess) {
                        HomeFocus.row = key
                        if (mover.target == key) { mover.target = null; break } else { frames = 0; continue }
                    }
                } else if (slots != null && slots.reqs.isNotEmpty()) {
                    // The row's entry card is scrolled out of the row: the row brings it in and focuses it.
                    slots.entered = true; slots.goTo(slots.target); HomeFocus.row = key
                    if (mover.target == key) { mover.target = null; break } else { frames = 0; continue }
                } else if (listState.layoutInfo.visibleItemsInfo.none { it.key == key }) {
                    // Not built yet: bring the row on screen (item 0 is the billboard).
                    val idx = 1 + rowOrder.indexOf(key)
                    if (idx > 0) runCatching { listState.scrollToItem(idx) }
                }
                withFrameNanos { }
                frames++
            }
            if (frames >= 90) mover.target = null
        }
        true
    }
    // Rows load ahead of you: the next 3 rows under the screen are asked before you reach them, so going Down lands on
    // titles, not on a row still loading.
    val visibleKeys by androidx.compose.runtime.rememberUpdatedState(visible.map { it.key })
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.key }.collect { last ->
            val keys = visibleKeys
            val at = keys.indexOf(last)
            if (at >= 0) keys.drop(at + 1).take(5).forEach { vm.prefetch(it) }
        }
    }
    // Open season (user 2026-11-27): the loading screen stays until every row is in AND every poster you can see
    // without moving Left/Right is loaded, so a full Up/Down pass never meets an empty card. Every row is asked at once
    // (Picks too), then each row's first screen of pictures is fetched at the size the cards draw. Fits the device:
    // pictures go into memory while they fit the image cache (Tier: 10% of RAM on Light boxes, 25% on strong ones);
    // the rest are fetched to disk only, so a 1-2 GB box doesn't evict what it just loaded. A dead network can't hold
    // the screen for ever: 30 s at most. Once per hub per app start.
    var ready by remember { mutableStateOf(hub in HomeReady.done) }
    val context = LocalContext.current
    val screenW = with(density) { androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    LaunchedEffect(Unit) {
        if (ready) return@LaunchedEffect
        val started = System.currentTimeMillis()
        val cap = 30_000L
        val rowAt = HashMap<String, Long>() // when each visible row was ready (timing log, see below)
        while (System.currentTimeMillis() - started < cap) {
            val keys = visibleKeys
            keys.forEach { vm.prefetch(it) }
            val byKey = vm.rows.value.associateBy { it.key }
            keys.forEach { k -> if (k !in rowAt && byKey[k]?.let { it.done || it.items.isNotEmpty() } == true) rowAt[k] = System.currentTimeMillis() - started }
            if (keys.isNotEmpty() && keys.all { k -> byKey[k]?.let { it.done || it.items.isNotEmpty() } != false }) break
            delay(100)
        }
        val rowsMs = System.currentTimeMillis() - started
        val px = with(density) { dims.posterWidth.roundToPx() to dims.posterHeight.roundToPx() }
        val wide = with(density) { dims.cwWidth.roundToPx() to dims.cwHeight.roundToPx() }
        val hi = with(density) { dims.highlightWidth.roundToPx() to dims.highlightHeight.roundToPx() }
        // How many cards of each kind one screen shows (plus the one peeking in at the right edge).
        fun across(w: Int) = screenW / w.coerceAtLeast(1) + 2
        val urls = cw.take(across(wide.first)).mapNotNull { r -> backdropOf(r.imdb)?.let { it to wide } } +
            vm.rows.value.filter { it.key in visibleKeys }.flatMap { r ->
                val isWide = r.kind == RowKind.COMING || r.kind == RowKind.SERVICES || r.kind == RowKind.TILES
                val size = if (r.kind == RowKind.RANKED) hi else if (isWide) wide else px
                r.items.take(across(size.first)).mapNotNull { m ->
                    (if (isWide) com.lekathedon.visionlite.poster.Posters.wideNow(m.id, m.type, m.wide()) else com.lekathedon.visionlite.poster.Posters.posterNow(m.id, m.type, m.poster))
                        ?.takeIf { it.isNotBlank() }?.let { it to size }
                }
            }
        val left = (cap - (System.currentTimeMillis() - started)).coerceAtLeast(3_000L)
        withTimeoutOrNull(left) {
            withContext(Dispatchers.IO) {
                val loader = context.imageLoader
                var budget = ((loader.memoryCache?.maxSize ?: 0L) * 0.8).toLong()
                urls.distinctBy { it.first }.map { (u, size) ->
                    val k = minOf(1f, 1920f / size.first.coerceAtLeast(1), 1080f / size.second.coerceAtLeast(1))
                    val w = (size.first * k).toInt().coerceAtLeast(1); val h = (size.second * k).toInt().coerceAtLeast(1)
                    budget -= w.toLong() * h * 4
                    ImageRequest.Builder(context).data(u).size(w, h)
                        .apply { if (budget < 0) memoryCachePolicy(coil3.request.CachePolicy.DISABLED) }.build()
                }.chunked(com.lekathedon.visionlite.device.Tier.imageDecoders * 3).forEach { batch ->
                    batch.map { r -> async { runCatching { loader.execute(r) } } }.awaitAll()
                }
            }
        }
        // Hub open timing (2026-11-27, "hubs open slower"): rows wait, then pictures, slowest rows first. logcat -s VisionHub
        android.util.Log.i("VisionHub", "${hub.key}: rows ${rowsMs} ms, pictures ${System.currentTimeMillis() - started - rowsMs} ms (${urls.size}), " +
            "slowest " + rowAt.entries.sortedByDescending { it.value }.take(5).joinToString { "${it.key}=${it.value}" })
        HomeReady.done += hub; ready = true
    }
    val heroPx = with(density) { 470.dp.toPx() }
    val art = entry?.let { enriched[it.m.id]?.background ?: it.m.wide() }
    val clockOn by App.instance.prefs.clock.flow.collectAsStateWithLifecycle()
    // Trailers (Vision's IMDb source): the billboard's after the delay on View title; a poster's in its opened card.
    val trailersOn by prefs.trailers.flow.collectAsStateWithLifecycle()
    val delayText by prefs.trailerDelay.flow.collectAsStateWithLifecycle()
    val sound by prefs.trailerSound.flow.collectAsStateWithLifecycle()
    // The clock's panel open: every trailer and clip on Home stops fully (user 2026-11-24), so its small window plays alone.
    val trailerLook = remember(trailersOn, delayText, sound, clockPanel) {
        TrailerLook(trailersOn && !clockPanel, ((delayText.toDoubleOrNull() ?: 5.0) * 1000).toLong(), !sound, paused = clockPanel)
    }
    var heroTrailer by remember { mutableStateOf<Pair<String, com.lekathedon.visionlite.trailer.Trailer>?>(null) }
    LaunchedEffect(heroFocused, heroEntry?.m?.id, trailerLook) {
        heroTrailer = null
        val hm = heroEntry?.m ?: return@LaunchedEffect
        val id = hm.id
        if (!heroFocused || !trailerLook.on) return@LaunchedEffect
        heroTrailer = trailerAfterRest(hm, trailerLook.delayMs, com.lekathedon.visionlite.device.Tier.trailerHeight(2160))?.let { id to it }
    }

    // Back on Home: first to the top (the pill), then out of the app (asking first when "Leave Vision?" is on).
    androidx.activity.compose.BackHandler(enabled = !askExit) {
        if (!pillFocused) scope.launch { runCatching { listState.scrollToItem(0) }; repeat(3) { withFrameNanos { } }; runCatching { pillHome.requestFocus() } }
        else if (exitConfirm) askExit = true else leaveApp(activity)
    }
    Box(Modifier.fillMaxSize().background(look.page)) {
        // Below the billboard nothing of the backdrop can be seen (user 2026-11-22): it and the wash are gone from the
        // page entirely there (no picture loaded per focused card, nothing drawn), and come back at the top.
        val atTop by remember { androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < heroPx } }
        if (atTop && !look.oled) AmbientPageWash(art, Modifier.fillMaxSize())
        // The billboard's art, wall to wall, fading as the page scrolls past it (read while drawing only).
        if (atTop) EchoBackdrop(art, heroTrailer?.takeIf { heroFocused && it.first == entry?.m?.id }?.second, trailerLook.muted,
            onEnded = { heroTrailer = null }, modifier = Modifier.graphicsLayer {
            alpha = if (listState.firstVisibleItemIndex > 0) 0f
            else (1f - listState.firstVisibleItemScrollOffset / heroPx).coerceIn(0f, 1f)
        })
        CompositionLocalProvider(LocalBringIntoViewSpec provides pageSpec, LocalTrailer provides trailerLook) {
            LazyColumn(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                // Only from a row's card (the billboard, pill and clock keep their own Up/Down).
                if (e.type != KeyEventType.KeyDown || (HomeFocus.row == null && mover.target == null)) false
                else when (e.key) {
                    Key.DirectionDown -> moveRow(true)
                    Key.DirectionUp -> moveRow(false)
                    else -> false
                }
            }, state = listState, contentPadding = PaddingValues(bottom = 300.dp)) {
                item(key = "hero") {
                    Billboard(
                        entry, enriched[entry?.m?.id], heroEntries.size, heroIndex, viewTitle, if (clockOn) clockFocus else pillHome,
                        onFocus = { if (it) { heroFocused = true; HomeFocus.row = null } },
                        onStep = { d -> if (heroEntries.isNotEmpty()) heroIndex = (heroIndex + d + heroEntries.size) % heroEntries.size },
                        onOpen = { m -> nav.push(Screen.Detail(m.type, m.id, m.name)) }
                    )
                }
                if (cw.isNotEmpty()) item(key = "cw") {
                    val cwBlur by prefs.blurUnwatched.flow.collectAsStateWithLifecycle()
                    EchoRow("cw", "Continue watching", dims, dims.cwHeight, shown?.m, plot = shown?.m?.let { enriched[it.id]?.description }) {
                        itemsIndexed(cw, key = { _, r -> r.streamId }) { i, r -> Slot(i) {
                            val e = remember(r) { HeroEntry(MetaPreview(r.imdb, r.type, r.name, posterOf(r.imdb), backdropOf(r.imdb)), r.season, r.episode) }
                            ContinueWatchingCard(
                                e.m, r.season?.let { "S$it E${r.episode}" }, r.progress / 100f, dims, Modifier, cwStill(r), cwBlur,
                                onFocus = { focused = e; heroFocused = false },
                                onClick = { nav.push(Screen.Streams(r.type, r.streamId, r.label, r.imdb)) },
                                onHold = { cwHold = r }
                            )
                        } }
                    }
                }
                items(visible, key = { it.key }) { row ->
                    LaunchedEffect(row.key) { vm.need(row.key) }
                    val wide = row.kind == RowKind.COMING || row.kind == RowKind.SERVICES || row.kind == RowKind.TILES
                    val ranked = row.kind == RowKind.RANKED
                    val h = if (ranked) dims.highlightHeight else if (wide) dims.cwHeight else dims.posterHeight
                    val title = when {
                        row.key == VisionRows.PICKS && recsBuilding -> "${row.title} · thinking…"
                        else -> row.title
                    }
                    val items = if (watchedMode == "hide" && watched.isNotEmpty() && row.kind == RowKind.POSTER) row.items.filter { it.id !in watched } else row.items
                    // Only a title of this row (a services / genres / people row never shows the last title's text),
                    // and the reason only where the row name doesn't already say it (Vision Picks).
                    val sm = shown?.m?.takeIf { (row.kind == RowKind.POSTER || row.kind == RowKind.RANKED || row.kind == RowKind.COMING) &&
                        it.type !in NotTitles && row.items.any { x -> x.id == it.id } }
                    val picks = VisionRows.isPicks(row.key)
                    val reason = if (sm != null && picks) sm.description else null
                    val plot = sm?.let { enriched[it.id]?.description ?: if (picks) null else it.description }?.takeIf { it.isNotBlank() }
                    EchoRow(row.key, title, dims, h, sm, lead = if (ranked) dims.rankPad else 0.dp, reason = reason, plot = plot) {
                        if ((row.items.isEmpty() && !row.done) || LoadingHold.key == row.key) item(key = "loading") { Slot(0) {
                            LoadingCard(row.key, if (ranked) dims.highlightWidth else if (wide) dims.cwWidth else dims.posterWidth, h, dims.radius,
                                below = { visible.map { it.key }.let { k -> k.drop(k.indexOf(row.key) + 1) } }, scope = scope,
                                ready = row.items.isNotEmpty() || row.done)
                        } }
                        itemsIndexed(items, key = { i, m -> "$i:${m.id}" }) { i, m -> Slot(i) {
                            val onFocus = {
                                if (m.type !in NotTitles) { focused = HeroEntry(m, null, null); heroFocused = false }
                                if (i >= items.size - 6) vm.more(row.key)
                            }
                            val onHold: (() -> Unit)? = if (m.type in NotTitles) null else ({ hold = row to m })
                            when (row.kind) {
                                RowKind.COMING -> ComingCard(m, dims, onFocus, onHold) { open(m) }
                                RowKind.SERVICES -> ServiceCard(m, dims, onFocus) { open(m) }
                                RowKind.PEOPLE -> PersonCard(m, dims, onFocus) { open(m) }
                                RowKind.TILES -> BrowseTile(m.id, m.name, m.background, dims, Modifier, onFocus) { open(m) }
                                RowKind.RANKED -> RankedCard(i + 1, m, dims,
                                    if (watchedMode == "dim" && m.id in watched) Modifier.graphicsLayer { alpha = 0.4f } else Modifier,
                                    onFocus = onFocus, onClick = { open(m) }, onHold = onHold)
                                RowKind.POSTER -> PosterCard(m, dims,
                                    if (watchedMode == "dim" && m.id in watched) Modifier.graphicsLayer { alpha = 0.4f } else Modifier,
                                    onFocus = onFocus, onClick = { open(m) }, onHold = onHold)
                            }
                        } }
                        if (VisionRows.isPicks(row.key)) item(key = "refresh") {
                            Slot(items.size + if (row.items.isEmpty() && !row.done) 1 else 0) {
                                RefreshCard(dims, onFocus = { focused = null }) { vm.refreshPicks(row.key) }
                            }
                        }
                    }
                }
            }
        }
        // The menu stays hidden: Up from View title reaches the clock, Up again opens the pill (Vision's Home).
        // Down to the billboard always works: the list is brought back to the top first (the billboard may have
        // scrolled out, and then its button isn't there to take focus — the old "stuck on the clock").
        val toHero: () -> Unit = { scope.launch { runCatching { listState.scrollToItem(0) }; repeat(3) { withFrameNanos { } }; runCatching { viewTitle.requestFocus() } } }
        TopPill(nav, pillFocused, pillHome, onDown = { if (clockOn) runCatching { clockFocus.requestFocus() } else toHero() },
            onRight = if (clockOn) ({ runCatching { clockFocus.requestFocus() } }) else null, onFocus = { pillFocused = it; if (it) HomeFocus.row = null },
            enterAtEnd = pillEnd)
        if (clockOn) ClockPill(
            Modifier.align(Alignment.TopEnd), clockFocus, pillHome, toHero,
            onFocus = { clockFocused = it; if (it) HomeFocus.row = null }, onClick = { clockPanel = true },
            onLeft = { pillEnd++ }, ready = ready
        )
        // Inside the clock the look and skin apply (only the clock itself keeps Home's shape).
        if (clockPanel) com.lekathedon.visionlite.ui.Inside(true) { com.lekathedon.visionlite.sport.ClockPanel(nav) { clockPanel = false; runCatching { clockFocus.requestFocus() } } }
        hold?.let { (row, m) -> HoldMenu(row, m, scope) { removed ->
            hold = null
            val old = LastFocus
            if (removed) { val i = HomeFocus.index[row.key] ?: 0; scope.launch { refocusRow(row.key, i, old) } }
            else old?.let { runCatching { it.requestFocus() } }
        } }
        cwHold?.let { r -> CwMenu(r, nav, onDrop = { vm.dropContinue(r) }) { cwHold = null; LastFocus?.let { runCatching { it.requestFocus() } } } }
        if (!ready) HomeLoading(hub)
        if (askExit) ExitAsk(onLeave = { leaveApp(activity) }, onStay = { askExit = false; LastFocus?.let { runCatching { it.requestFocus() } } })
    }
}

/** Cards that aren't a title (no billboard, details line or hold menu). */
private val NotTitles = setOf("service", "person", "genre", "decade", "mood")

/** Hubs whose Home has shown its first screens this app start (the loading cover shows only before that). */
internal object HomeReady { val done = HashSet<com.lekathedon.visionlite.anime.Hub>() }

/** The cover while Home's first rows load (user 2026-11-26): the hub's own loading screen, like opening a title. */
@Composable
private fun HomeLoading(hub: com.lekathedon.visionlite.anime.Hub) {
    com.lekathedon.visionlite.ui.HubLoading(hub.key, "Loading ${hub.label}")
}

/** Trailer settings for the page, read once by Home. */
private data class TrailerLook(val on: Boolean, val delayMs: Long, val muted: Boolean, val paused: Boolean = false)
private val LocalTrailer = androidx.compose.runtime.staticCompositionLocalOf { TrailerLook(false, 5_000L, false) }

/** Waits out the trailer delay (the lookup starts a second early, so the trailer is ready on time); null for none. */
/** [maxHeight]: the big billboard may reach 4K, opened posters stop at 1080p. */
private suspend fun trailerAfterRest(m: MetaPreview, delayMs: Long, maxHeight: Int): com.lekathedon.visionlite.trailer.Trailer? {
    val lead = minOf(1_000L, delayMs)
    delay(delayMs - lead)
    val started = System.currentTimeMillis()
    // Vision's order for every card, whatever its row: IMDb, then TMDB (AniList for anime), then YouTube.
    val url = com.lekathedon.visionlite.trailer.TrailerChain.find(m, maxHeight) ?: return null
    delay((lead - (System.currentTimeMillis() - started)).coerceAtLeast(0L))
    return url
}

/** A billboard title: Continue Watching entries carry their episode. */
private data class HeroEntry(val m: MetaPreview, val season: Int?, val episode: Int?)


/** Home's buttons (View title): the look's shape and fills at Home's size. */
private fun Modifier.homeButton(f: Boolean): Modifier {
    val shape = com.lekathedon.visionlite.ui.Kit.buttonShape
    return clip(shape).then(if (f) Modifier.background(com.lekathedon.visionlite.ui.kitFocus.first)
        else Modifier.background(Color(0x8C000000)).border(1.dp, com.lekathedon.visionlite.ui.Kit.line, shape))
}

/** The pill's and clock's corners: the look's. */
private fun homeShape(): androidx.compose.ui.graphics.Shape = com.lekathedon.visionlite.ui.Kit.buttonShape

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
                    overflow = TextOverflow.Ellipsis, style = androidx.compose.material3.LocalTextStyle.current.copy(fontFamily = com.lekathedon.visionlite.ui.Kit.display, shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 10f)))
            }
            val dim = com.lekathedon.visionlite.ui.Palette.TextDim
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
            // A Continue watching episode is not watched yet: its plot is a spoiler when the blur is on.
            val blurOn by com.lekathedon.visionlite.App.instance.prefs.hidePlots.flow.collectAsState()
            (video?.overview ?: meta?.description ?: m.description)?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = com.lekathedon.visionlite.ui.Palette.Text.copy(alpha = 0.95f), fontSize = 14.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp).spoiler(blurOn && video?.overview != null))
            }
            val source = remember { MutableInteractionSource() }
            val f by source.collectIsFocusedAsState()
            Text(
                "View title", color = if (f) com.lekathedon.visionlite.ui.kitFocus.second else com.lekathedon.visionlite.ui.Palette.Text, fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
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
                    .homeButton(f)
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
                    .background(if (i == index) com.lekathedon.visionlite.ui.Kit.accent else com.lekathedon.visionlite.ui.Palette.Text.copy(alpha = 0.4f)))
            }
            Text("›", color = Color(0x99FFFFFF), fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

// ---- Top band: PillNavigationBar (glass pill, top centre, hidden until reached) and LegacyClockPill (top right) ----

/**
 * Vision's PillNavigationBar: Home and the hub entry fixed on the left (on a dark capsule in the clock's colour, the
 * hub's name in white: where you are), the hub's pages three at a time in a window that slides a page along when focus
 * steps past its last (or first) slot, the gear and your account fixed on the right. The pill never grows: OK on the
 * hub entry shows the hubs in that same window. On Home it hides until reached; on a hub's pages it stays shown with
 * that page marked. From outside only the marked item takes focus. Up stays, Down = [onDown], Right past the end =
 * [onRight]. Back folds the hubs away, then returns to Nuvio.
 */
@Composable
fun BoxScope.TopPill(
    nav: Nav, open: Boolean, home: FocusRequester, onDown: () -> Unit, onRight: (() -> Unit)?, onFocus: (Boolean) -> Unit,
    /** The hub page on screen (its pill label); null = Home. */
    current: String? = null,
    /** Bumped from outside (Left on the clock): focus the pill's last item. */
    enterAtEnd: Int = 0
) {
    val shape = homeShape()
    val prefs = App.instance.prefs
    val section by prefs.appSection.flow.collectAsStateWithLifecycle()
    // A hub switched off in Settings › Home hubs: Home moves to the first one still on.
    LaunchedEffect(section) { if (Sections.all.none { it.first == section }) Sections.all.firstOrNull()?.let { com.lekathedon.visionlite.anime.Hubs.setSection(it.first) } }
    var choosing by remember { mutableStateOf(false) }
    LaunchedEffect(open) { if (!open) choosing = false }
    fun midOf(ch: Boolean) = if (ch) Sections.all.map { "s:" + it.first to it.second } else Sections.pages(section).map { "p:" + it.first to it.first }
    val middle = midOf(choosing)
    val req = remember { HashMap<String, FocusRequester>() }
    fun r(k: String) = if (k == "home") home else req.getOrPut(k) { FocusRequester() }
    val entryKey = current?.let { "p:$it" } ?: "home"
    var focusedKey by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(0) }
    val pages = ((middle.size + 2) / 3).coerceAtLeast(1)
    LaunchedEffect(choosing, section, open) {
        if (open) return@LaunchedEffect
        page = if (choosing) Sections.all.indexOfFirst { it.first == section }.coerceAtLeast(0) / 3
            else (middle.indexOfFirst { it.first == entryKey }.coerceAtLeast(0)) / 3
    }
    LaunchedEffect(choosing) { if (choosing) page = Sections.all.indexOfFirst { it.first == section }.coerceAtLeast(0) / 3 }
    var pending by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pending) {
        val k = pending ?: return@LaunchedEffect
        repeat(20) { withFrameNanos { }; if (focusedKey == k) { pending = null; return@LaunchedEffect }; runCatching { r(k).requestFocus() } }
        pending = null
    }
    fun go(k: String) { val mi = middle.indexOfFirst { it.first == k }; if (mi >= 0) page = mi / 3; pending = k }
    // Coming in from the right: the pill's items are focusable before it has focus, until one takes it.
    var entering by remember { mutableStateOf(false) }
    LaunchedEffect(open) { if (open) entering = false }
    // The Lite profile in use (data/Profiles.kt): its name and picture (GIFs move), as Vision's ProfileAvatarCircle.
    com.lekathedon.visionlite.data.Profiles.version.collectAsStateWithLifecycle().value
    val me = com.lekathedon.visionlite.data.Profiles.current()
    val account = me.name
    val avatar = me.pic to me.color
    val ends = listOf("settings", "account")
    // OK on the profile: Vision's "Who's watching" picker over the page.
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(enterAtEnd) { if (enterAtEnd > 0) { entering = true; pending = ends.last() } }
    fun step(forward: Boolean): Boolean {
        val k = focusedKey ?: return false
        val mi = middle.indexOfFirst { it.first == k }
        val ei = ends.indexOf(k)
        when {
            k == "home" -> if (forward) go("section")
            k == "section" -> if (forward) middle.getOrNull(page * 3)?.let { go(it.first) } ?: go(ends.first()) else go("home")
            ei >= 0 -> if (forward) { ends.getOrNull(ei + 1)?.let(::go) ?: onRight?.invoke() }
                else ends.getOrNull(ei - 1)?.let(::go) ?: (middle.getOrNull(minOf(page * 3 + 2, middle.lastIndex))?.let { go(it.first) } ?: go("section"))
            mi >= 0 -> { val n = middle.getOrNull(if (forward) mi + 1 else mi - 1); if (n != null) go(n.first) else go(if (forward) ends.first() else "section") }
            else -> return false
        }
        return true
    }
    // Back on Home from IPTV: to the content hub Home shows (Films & TV or Anime).
    val contentHub by com.lekathedon.visionlite.anime.Hubs.content.collectAsStateWithLifecycle()
    androidx.activity.compose.BackHandler(enabled = open && (choosing || (current == null && section != contentHub.key))) {
        if (choosing) choosing = false else com.lekathedon.visionlite.anime.Hubs.setSection(contentHub.key)
        pending = "section"
    }
    val shown = open || current != null
    val pillAlpha by androidx.compose.animation.core.animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 320 else 240), label = "pill")
    Row(
        Modifier.align(Alignment.TopCenter).padding(top = 11.dp).height(36.dp)
            .graphicsLayer { alpha = pillAlpha; translationY = (1f - pillAlpha) * -40f }
            // Focus gone from the pill: it no longer "is" on any item. A stale key made Left on the clock do nothing
            // (it thought it was already on your account: the clock → account step broke).
            .onFocusChanged { onFocus(it.hasFocus); if (!it.hasFocus) focusedKey = null }
            .onPreviewKeyEvent { e ->
                if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    androidx.compose.ui.input.key.Key.DirectionUp -> true
                    androidx.compose.ui.input.key.Key.DirectionDown -> { onDown(); true }
                    androidx.compose.ui.input.key.Key.DirectionRight -> step(true)
                    androidx.compose.ui.input.key.Key.DirectionLeft -> step(false)
                    else -> false
                }
            }
            .clip(shape).background(Color(0x2EFFFFFF)).border(1.dp, Color(0x59FFFFFF), shape).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        @Composable fun item(k: String, label: String?, selected: Boolean = false, white: Boolean = false, face: Pair<String, String>? = null, onClick: () -> Unit) =
            PillItem(label, k == entryKey || open || entering, selected, Modifier.focusRequester(r(k)).onFocusChanged { if (it.isFocused) focusedKey = k }, white, face, onClick)
        // Where you are: Home and the hub's name on the clock's dark capsule, up to the line.
        Row(Modifier.fillMaxHeight().clip(shape).background(com.lekathedon.visionlite.ui.Theme.skin.capsule.copy(alpha = 0.86f)).padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            item("home", "Home", selected = current == null) { if (current != null) nav.popToHome() }
            item("section", Sections.label(section), white = true) { if (!choosing) { choosing = true; pending = "s:$section" } }
        }
        PillDivider()
        androidx.compose.animation.AnimatedContent(page to choosing, transitionSpec = {
            val dir = if (targetState.first >= initialState.first) 1 else -1
            (androidx.compose.animation.slideInHorizontally(tween(320)) { it * dir } + androidx.compose.animation.fadeIn(tween(200))) togetherWith
                (androidx.compose.animation.slideOutHorizontally(tween(320)) { -it * dir } + androidx.compose.animation.fadeOut(tween(160))) using
                androidx.compose.animation.SizeTransform(clip = true) { _, _ -> tween(320) }
        }, label = "pillPage") { (pg, ch) ->
            Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                midOf(ch).drop(pg * 3).take(3).forEach { (k, label) ->
                    item(k, label, selected = if (ch) k == "s:$section" else k == entryKey) {
                        if (ch) {
                            val key = k.removePrefix("s:")
                            com.lekathedon.visionlite.anime.Hubs.setSection(key); choosing = false
                            Sections.opensAt(key)?.let { if (current != null) nav.replace(it) else nav.push(it) } ?: run {
                                if (current != null) nav.popToHome(); pending = Sections.pages(key).firstOrNull()?.let { "p:" + it.first } ?: "section" }
                        } else if (k != entryKey) Sections.pages(section).firstOrNull { it.first == label }?.let {
                            if (current != null) nav.replace(it.second) else nav.push(it.second)
                        }
                    }
                }
            }
        }
        if (pages > 1) Text("${page + 1}/$pages", color = com.lekathedon.visionlite.ui.Palette.TextDim, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 4.dp))
        PillDivider()
        item("settings", null) { nav.push(Screen.Settings) }
        item("account", account, face = avatar) { picking = true }
    }
    // OK on the profile: Who's watching? over the page; another profile restarts the app in it.
    if (picking) com.lekathedon.visionlite.profiles.ProfileGate(atStart = false, onManage = { picking = false; nav.push(Screen.Profiles) }) {
        picking = false; entering = true; pending = "account"
    }
}

/** The hub (section key) a menu screen belongs to, or null. Films & TV and Anime share their pages: the one you're in keeps them. */
fun hubOf(s: Screen): String? {
    val now = App.instance.prefs.appSection.value
    if (Sections.pages(now).any { it.second == s }) return now
    val content = com.lekathedon.visionlite.anime.Hubs.now().key
    if (Sections.pages(content).any { it.second == s }) return content
    return Sections.all.firstOrNull { (key, _) -> Sections.pages(key).any { it.second == s } }?.first
}

/** The hub page each menu screen is (for the pill on that page): its label, or null when it isn't one. */
fun hubPageOf(s: Screen): String? = hubOf(s)?.let { key -> Sections.pages(key).firstOrNull { it.second == s }?.first }

@Composable
private fun PillDivider() = Box(Modifier.padding(horizontal = 4.dp).size(1.dp, 16.dp).background(com.lekathedon.visionlite.ui.Palette.Text.copy(alpha = 0.33f)))

@Composable
private fun PillItem(label: String?, canFocus: Boolean, selected: Boolean = false, modifier: Modifier = Modifier, white: Boolean = false,
    /** A profile: (picture link, colour) drawn as Vision's avatar circle instead of the label. */
    face: Pair<String, String>? = null, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val f by source.collectIsFocusedAsState()
    val shape = homeShape()
    val tint = if (f) com.lekathedon.visionlite.ui.kitFocus.second else com.lekathedon.visionlite.ui.Palette.Text.copy(alpha = if (selected || white) 1f else 0.85f)
    Box(
        modifier.fillMaxHeight().focusProperties { this.canFocus = canFocus }.clip(shape)
            .then(if (f) Modifier.background(com.lekathedon.visionlite.ui.kitFocus.first)
                else if (selected) Modifier.background(com.lekathedon.visionlite.ui.Palette.Text.copy(alpha = 0.15f)).border(1.dp, com.lekathedon.visionlite.ui.Kit.line, shape) else Modifier)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = if (face != null) 2.dp else 11.dp),
        contentAlignment = Alignment.Center
    ) {
        if (face != null) ProfileFace(label.orEmpty(), face.first, face.second, 26.dp)
        else if (label != null) Text(label, color = tint, fontSize = 15.sp, fontWeight = if (white || f) FontWeight.SemiBold else FontWeight.Normal)
        else androidx.compose.foundation.Canvas(Modifier.size(17.dp)) {
            val w = size.width
            val c = androidx.compose.ui.geometry.Offset(w / 2, w / 2)
            for (k in 0 until 8) {
                val a = Math.toRadians(k * 45.0)
                drawLine(tint, c, androidx.compose.ui.geometry.Offset(c.x + (w * 0.47f * Math.cos(a)).toFloat(), c.y + (w * 0.47f * Math.sin(a)).toFloat()), strokeWidth = w * 0.18f)
            }
            drawCircle(tint, radius = w * 0.33f, center = c)
            drawCircle(if (f) com.lekathedon.visionlite.ui.kitFocus.first else Color(0xFF4A4A4E), radius = w * 0.14f, center = c)
        }
    }
}

/** Vision's ProfileAvatarCircle: the profile's colour with its initial, the picture over it once loaded. */
@Composable
fun ProfileFace(name: String, url: String, colorHex: String, size: Dp) {
    val bg = remember(colorHex) { runCatching { Color(android.graphics.Color.parseColor(colorHex)) }.getOrDefault(Color(0xFF1E88E5)) }
    var loaded by remember(url) { mutableStateOf(false) }
    Box(Modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape).background(bg), contentAlignment = Alignment.Center) {
        if (!loaded) Text(name.firstOrNull()?.uppercaseChar()?.toString() ?: "?", color = Color.White,
            fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Bold)
        if (url.isNotBlank()) {
            val context = LocalContext.current
            val d = androidx.compose.ui.platform.LocalDensity.current
            val req = remember(url) { ImageRequest.Builder(context).data(url).size(with(d) { size.roundToPx() } * 2).build() }
            coil3.compose.AsyncImage(req, name, Modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape),
                contentScale = ContentScale.Crop, onSuccess = { loaded = true })
        }
    }
}

/** Legacy's clock island: the time on a dark capsule, top right; focused, a white ring. OK opens its panel (Live now). */
@Composable
private fun ClockPill(modifier: Modifier, me: FocusRequester, up: FocusRequester, onDown: () -> Unit, onFocus: (Boolean) -> Unit, onClick: () -> Unit, onLeft: () -> Unit, ready: Boolean = true) {
    val context = LocalContext.current
    val fmt = remember { android.text.format.DateFormat.getTimeFormat(context) }
    var now by remember { mutableStateOf(fmt.format(java.util.Date())) }
    LaunchedEffect(Unit) {
        while (true) { delay(60_000 - System.currentTimeMillis() % 60_000); now = fmt.format(java.util.Date()) }
    }
    // Greeting (user 2026-11-27): once per app start the clock opens out to the left with "Welcome back, <profile>"
    // for 5 s, then rolls back into the time.
    // Starts only once Home is on screen: Home is built under its loading cover, where 0.41.9's greeting ran out unseen.
    var greet by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        val g = Greeting.take() ?: return@LaunchedEffect
        delay(700); greet = g; delay(5_000); greet = null
    }
    val source = remember { MutableInteractionSource() }
    val f by source.collectIsFocusedAsState()
    LaunchedEffect(f) { onFocus(f) }
    val shape = homeShape()
    Box(
        modifier.padding(top = 12.dp, end = 25.dp).height(25.dp)
            .animateContentSize(androidx.compose.animation.core.tween(450))
            .focusRequester(me)
            // Between the menu and the billboard (Vision's LegacyClockFocus): Up = the menu's Home, Left = the menu's
            // last item (the profile: the clock sits right of it), Down = the billboard.
            .onPreviewKeyEvent { e ->
                if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    androidx.compose.ui.input.key.Key.DirectionUp -> { runCatching { up.requestFocus() }; true }
                    androidx.compose.ui.input.key.Key.DirectionLeft -> { onLeft(); true }
                    androidx.compose.ui.input.key.Key.DirectionDown -> { onDown(); true }
                    androidx.compose.ui.input.key.Key.DirectionRight -> true
                    else -> false
                }
            }
            .clip(shape).background(if (f) com.lekathedon.visionlite.ui.kitFocus.first else com.lekathedon.visionlite.ui.Theme.skin.capsule.copy(alpha = 0.86f))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        val ink = if (f) com.lekathedon.visionlite.ui.kitFocus.second else com.lekathedon.visionlite.ui.Palette.Text
        androidx.compose.animation.Crossfade(greet, animationSpec = androidx.compose.animation.core.tween(350), label = "greet") { g ->
            Text(if (g != null) "Welcome back, $g" else now, color = ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/** The clock's welcome: the profile's name once per app start (the process restarts on a profile switch). */
internal object Greeting {
    @Volatile private var shown = false
    fun take(): String? {
        if (shown) return null
        shown = true
        return com.lekathedon.visionlite.data.Profiles.current().name.trim().takeIf { it.isNotEmpty() }
    }
}

/**
 * A row: title, cards snapped to the row's start, and the focused card's details line under it (Echo).
 * Coming into the row from above or below lands on the card it last had (the one it's snapped to), or the first card
 * for a row not visited yet. Right on the last card stays put (the clock is reached from the top only).
 */
@Composable
private fun EchoRow(
    key: String,
    title: String,
    dims: EchoDims,
    cardHeight: Dp,
    shown: MetaPreview?,
    /** Room kept left of the focused card when the row snaps (a ranked card's number). */
    lead: Dp = 0.dp,
    /** Old Vision's text for the focused card: why it's here ("Because you watched X") and its plot (2 lines). */
    reason: String? = null,
    plot: String? = null,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    var hasFocus by remember { mutableStateOf(false) }
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    val slots = remember(key) { RowSlots(key) }
    val rowScope = rememberCoroutineScope()
    slots.state = state; slots.scope = rowScope
    androidx.compose.runtime.DisposableEffect(slots) { RowRegistry[key] = slots; onDispose { if (RowRegistry[key] === slots) RowRegistry.remove(key) } }
    LaunchedEffect(key) {
        if (!HomeFocus.restore || HomeFocus.row != key) return@LaunchedEffect
        repeat(10) {
            withFrameNanos { }
            val i = HomeFocus.index[key] ?: state.firstVisibleItemIndex
            val r = slots.reqs[i] ?: slots.reqs[state.firstVisibleItemIndex]
            if (r != null && runCatching { r.requestFocus() }.isSuccess) { HomeFocus.restore = false; return@LaunchedEffect }
        }
    }
    Column(Modifier.fillMaxWidth().padding(bottom = dims.rowSpace - dims.posterMeta + 6.dp).onFocusChanged {
        if (it.hasFocus && !hasFocus) slots.entered = true
        hasFocus = it.hasFocus
    }) {
        com.lekathedon.visionlite.ui.HomeHeading(title, Modifier.padding(start = Start).height(dims.rowHead))
        val leadPx = with(androidx.compose.ui.platform.LocalDensity.current) { lead.toPx() }
        CompositionLocalProvider(LocalBringIntoViewSpec provides remember(leadPx) { snapSpec(leadPx) }, LocalRowSlots provides slots) {
            LazyRow(
                Modifier.fillMaxWidth().height(cardHeight)
                    .focusRestorer { slots.reqs[slots.target] ?: FocusRequester.Default }
                    .focusGroup()
                    .onPreviewKeyEvent { e ->
                        // The last card: nothing further right in this row, so Right stays (never jumps to the clock).
                        e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight &&
                            (HomeFocus.index[key] ?: -1) >= state.layoutInfo.totalItemsCount - 1
                    },
                state = state,
                contentPadding = PaddingValues(start = Start, end = 600.dp),
                horizontalArrangement = Arrangement.spacedBy(dims.gap),
                content = content
            )
        }
        // The focused row alone has the text (rating line, reason, 2 lines of plot); the others keep the short gap.
        Column(Modifier.then(if (hasFocus && shown != null) Modifier.heightIn(min = dims.posterMeta) else Modifier.height(dims.posterMeta))
            .padding(start = Start, top = 6.dp, end = 48.dp).widthIn(max = 900.dp)) {
            if (hasFocus && shown != null) {
                DetailsLine(shown)
                reason?.let { Text(it, color = com.lekathedon.visionlite.ui.Kit.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
                plot?.let { Text(it, color = com.lekathedon.visionlite.ui.Palette.TextDim, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp, bottom = 4.dp)) }
            }
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
    /** Hold OK. */
    onHold: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit
) {
    val me = remember { FocusRequester() }
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val slots = LocalRowSlots.current
    val slot = LocalSlotIndex.current
    if (slots != null && slot >= 0) androidx.compose.runtime.DisposableEffect(slots, slot) {
        slots.reqs[slot] = me
        onDispose { if (slots.reqs[slot] === me) slots.reqs.remove(slot) }
    }
    LaunchedEffect(focused) {
        if (focused) {
            LastFocus = me
            if (slots != null && slot >= 0) {
                if (slots.entered) {
                    // Came in from another row: land on the first card (or the one you moved to), whatever focus search picked.
                    slots.entered = false
                    val t = slots.target
                    if (slot != t && (slots.reqs[t] != null || t < slot)) { slots.goTo(t); return@LaunchedEffect }
                } else RowMoves.index[slots.key] = slot   // moved within the row with Left/Right
                if (slots.home) { HomeFocus.row = slots.key; HomeFocus.index[slots.key] = slot } else slots.last = slot
            }
            onFocus()
        }
    }
    val shape = RoundedCornerShape(radius)
    Box(
        modifier.focusRequester(me)
            .then(if (onHold == null) Modifier else Modifier.onPreviewKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter
                when {
                    ok && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0 -> { com.lekathedon.visionlite.ui.HoldGuard.held(); onHold(); true }
                    else -> false
                }
            })
            .size(if (focused && openWidth != null) openWidth else width, height).clip(shape).background(com.lekathedon.visionlite.ui.Kit.card)
            .then(if (focused) Modifier.border(2.dp, com.lekathedon.visionlite.ui.Kit.ringColor, shape) else Modifier)
            .remembersFocus { focused }
            .clickable(interactionSource = source, indication = null, onClick = onClick)
    ) { content(focused) }
}

/** A poster that opens out to the title's backdrop with its logo while focused: at once, no animation. */
@Composable
private fun PosterCard(m: MetaPreview, dims: EchoDims, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit, onHold: (() -> Unit)? = null) {
    EchoCard(dims.posterWidth, dims.posterHeight, dims.radius, modifier, onFocus = onFocus, onClick = onClick,
        openWidth = dims.posterOpenWidth, onHold = onHold) { f -> Box(Modifier.fillMaxSize()) {
        if (!f) {
            val art = com.lekathedon.visionlite.poster.Posters.poster(m.id, m.type, m.poster)
            if (art.isNullOrBlank()) Fallback(m.name) else Img(art, dims.posterWidth, dims.posterHeight, Modifier.fillMaxSize(), fallback = m.poster)
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Img(com.lekathedon.visionlite.poster.Posters.wide(m.id, m.type, m.wide()), dims.posterOpenWidth, dims.posterHeight, Modifier.fillMaxSize(), fallback = m.wide())
                // The opened card plays the title's trailer once focus has rested (Vision's expanded-card target).
                val look = LocalTrailer.current
                var trailer by remember { mutableStateOf<com.lekathedon.visionlite.trailer.Trailer?>(null) }
                LaunchedEffect(m.id, look) { trailer = if (look.on) trailerAfterRest(m, look.delayMs, com.lekathedon.visionlite.device.Tier.trailerHeight(1080)) else null }
                trailer?.let { com.lekathedon.visionlite.trailer.TrailerVideo(it.url, look.muted, Modifier.fillMaxSize(), it.audio) { trailer = null } }
                // Custom wide art has the name on it: the logo comes only once the trailer covers the art (user 2026-11-22).
                if (trailer != null || !com.lekathedon.visionlite.poster.Posters.wideHasTitle(m.id, m.type)) {
                    Box(Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))
                    ))
                    CardLogo(m.logoUrl(), m.name, 0.55f, 0.4f, dims.posterOpenWidth, dims.posterHeight, 14.dp, 20f)
                }
            }
        }
        WatchedTick(m.id, Modifier.align(Alignment.TopEnd))
    } }
}

/**
 * A ranked row's card (Vision Echo's Top 20): the big number stands behind the card's left, the card is Echo's highlight
 * (wide art, logo, trailer once focus rests). Static: nothing animates, the number only lightens its outline on focus.
 */
@Composable
private fun RankedCard(rank: Int, m: MetaPreview, dims: EchoDims, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit, onHold: (() -> Unit)?) {
    var focused by remember { mutableStateOf(false) }
    Box(modifier.size(dims.rankPad + dims.highlightWidth, dims.highlightHeight).onFocusChanged { focused = it.hasFocus }) {
        RankNumber(rank, dims, focused, Modifier.align(Alignment.BottomEnd).padding(end = dims.highlightWidth - (dims.rankFontSize * 0.07f).dp))
        EchoCard(dims.highlightWidth, dims.highlightHeight, dims.radius, Modifier.align(Alignment.TopEnd), onFocus = onFocus, onClick = onClick, onHold = onHold) { f ->
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Img(com.lekathedon.visionlite.poster.Posters.wide(m.id, m.type, m.wide()), dims.highlightWidth, dims.highlightHeight, Modifier.fillMaxSize(), fallback = m.wide())
                var trailer by remember(m.id) { mutableStateOf<com.lekathedon.visionlite.trailer.Trailer?>(null) }
                if (f) {
                    val look = LocalTrailer.current
                    LaunchedEffect(m.id, look) { trailer = if (look.on) trailerAfterRest(m, look.delayMs, com.lekathedon.visionlite.device.Tier.trailerHeight(1080)) else null }
                    trailer?.let { com.lekathedon.visionlite.trailer.TrailerVideo(it.url, look.muted, Modifier.fillMaxSize(), it.audio) { trailer = null } }
                }
                LaunchedEffect(f) { if (!f) trailer = null }
                // Custom wide art has the name on it: the logo comes only once the trailer covers the art (user 2026-11-22).
                if ((f && trailer != null) || !com.lekathedon.visionlite.poster.Posters.wideHasTitle(m.id, m.type)) {
                    Box(Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))
                    ))
                    CardLogo(m.logoUrl(), m.name, 0.55f, 0.4f, dims.highlightWidth, dims.highlightHeight, 14.dp, 20f)
                }
                WatchedTick(m.id, Modifier.align(Alignment.TopEnd))
            }
        }
    }
}

/**
 * Vision's RankNumber (NewLookCards), as is: outline under an opaque fill, a soft gray gradient on top; taller than the
 * card (1.18 x its height), standing on the card's bottom edge; two digits narrowed.
 */
@Composable
private fun RankNumber(rank: Int, dims: EchoDims, focused: Boolean, modifier: Modifier = Modifier) {
    val fontSize = dims.rankFontSize.sp
    val twoDigits = rank >= 10
    val outline = if (focused) Color(240, 240, 240, 153) else Color(215, 215, 215, 77)
    val base = TextStyle(
        fontSize = fontSize, fontWeight = FontWeight.ExtraBold, fontFamily = com.lekathedon.visionlite.ui.Kit.display,
        letterSpacing = if (twoDigits) (-0.09).em else (-0.06).em,
        lineHeight = fontSize, textAlign = androidx.compose.ui.text.style.TextAlign.End
    )
    var descentPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    Box(
        modifier.offset { IntOffset(0, descentPx) }
            .wrapContentSize(Alignment.BottomEnd, unbounded = true)
            .graphicsLayer { if (twoDigits) { scaleX = 0.72f; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f) } }
    ) {
        Text(rank.toString(), style = base.copy(color = outline, drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = 6.4f)),
            maxLines = 1, softWrap = false, onTextLayout = { descentPx = (it.size.height - it.lastBaseline).toInt() })
        Text(rank.toString(), style = base.copy(color = Color(0xFF171717)), maxLines = 1, softWrap = false)
        Text(rank.toString(), style = base.copy(brush = Brush.verticalGradient(
            0f to Color(92, 92, 92, 191), 0.55f to Color(44, 44, 44, 115), 1f to Color(14, 14, 14, 0))), maxLines = 1, softWrap = false)
    }
}

/**
 * Echo's poster for every other page (Discover, Genres, Decades, Scout, Library, Search…): the same card, size, corner,
 * white focus ring, logo-on-backdrop when opened, trailer by the Home settings. [expand] = opens out to the backdrop
 * (rows); grids keep it closed so cells don't jump.
 */
@Composable
fun EchoPoster(m: MetaPreview, modifier: Modifier = Modifier, expand: Boolean = true, sub: String? = null, onHoldIn: (() -> Unit)? = null, onClick: () -> Unit) {
    val onHold = onHoldIn ?: if (TasteMenu.isTitle(m)) ({ TasteMenu.show(m) }) else null
    val prefs = App.instance.prefs
    val size by prefs.posterSize.flow.collectAsStateWithLifecycle()
    val dims = remember(size) { EchoDims.of(size) }
    val trailersOn by prefs.trailers.flow.collectAsStateWithLifecycle()
    val delayText by prefs.trailerDelay.flow.collectAsStateWithLifecycle()
    val sound by prefs.trailerSound.flow.collectAsStateWithLifecycle()
    val look = remember(trailersOn, delayText, sound) { TrailerLook(trailersOn && expand, ((delayText.toDoubleOrNull() ?: 5.0) * 1000).toLong(), !sound) }
    val mm = remember(m) { if (m.poster.isNullOrBlank() && m.id.startsWith("tt")) m.copy(poster = posterOf(m.id)) else m }
    Column {
        CompositionLocalProvider(LocalTrailer provides look) {
            if (expand) PosterCard(mm, dims, modifier, onFocus = {}, onClick = onClick, onHold = onHold)
            else EchoCard(dims.posterWidth, dims.posterHeight, dims.radius, modifier, onFocus = {}, onClick = onClick, onHold = onHold) {
                val art = com.lekathedon.visionlite.poster.Posters.poster(mm.id, mm.type, mm.poster)
                Box(Modifier.fillMaxSize()) {
                    if (art.isNullOrBlank()) Fallback(mm.name) else Img(art, dims.posterWidth, dims.posterHeight, Modifier.fillMaxSize(), fallback = mm.poster)
                    WatchedTick(mm.id, Modifier.align(Alignment.TopEnd))
                }
            }
        }
        if (sub != null) Text(sub, color = Color(0xB3FFFFFF), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(dims.posterWidth).padding(top = 6.dp))
    }
}

/**
 * Home's row for every other page (services, genres, decades, people, Scout, Search, Library…): the opened card always
 * rests on the left (the row snaps to it, as on Home), Up / Down come back to the card you left, a row still loading
 * keeps a card you can land on, and Right on the last card stays put. [key] must be unique on the page and stable.
 */
@Composable
fun PageRow(
    key: String, title: String, metasIn: List<MetaPreview>, loading: Boolean = false,
    sub: (MetaPreview) -> String? = { null }, firstFocus: FocusRequester? = null,
    onHoldIn: ((MetaPreview) -> Unit)? = null, onEnd: (() -> Unit)? = null, expand: Boolean = true,
    /** Search: left-out titles stay, marked "Left out · Korean" (everywhere else they're gone). */
    markLeftOut: Boolean = false,
    /** VOD page (user 2026-11-24): the provider's titles as they are; no adult / leave-out checks (no lookups). */
    filter: Boolean = true,
    /** Drawn over the card's own poster instead of the custom poster (VOD: the provider's art + our badge). */
    overlay: (@Composable BoxScope.(MetaPreview) -> Unit)? = null,
    onClick: (MetaPreview) -> Unit
) {
    // Hide adult titles (when on): checked once each, gone as soon as known.
    val adultRev by com.lekathedon.visionlite.recs.Adult.revision.collectAsStateWithLifecycle()
    val adultOn by App.instance.prefs.hideAdult.flow.collectAsStateWithLifecycle()
    // Leave out (Settings › Recommendations): gone from every page; Search marks them instead.
    val loRev by com.lekathedon.visionlite.recs.LeaveOut.revision.collectAsStateWithLifecycle()
    val loText by App.instance.prefs.leaveOut.flow.collectAsStateWithLifecycle()
    // Settings › Anime › Off: anime shows on no page at all, Search included.
    val animeMode by App.instance.prefs.animeMode.flow.collectAsStateWithLifecycle()
    // Not out at home yet: gone from pages too (Search and pages for what's coming keep them).
    val relRev by com.lekathedon.visionlite.recs.Released.revision.collectAsStateWithLifecycle()
    val relOn by App.instance.prefs.hideUnreleased.flow.collectAsStateWithLifecycle()
    val metas = remember(metasIn, adultRev, adultOn, loRev, loText, markLeftOut, filter, animeMode, relRev, relOn) {
        val metasIn = if (animeMode != "off") metasIn else metasIn.filter { (it.type != "movie" && it.type != "series") || !com.lekathedon.visionlite.anime.AnimeIds.isAnime(it.id) }
        if (!filter) metasIn else com.lekathedon.visionlite.recs.Adult.visible(metasIn).let { l ->
            // Search keeps them, but asks what each is, so the mark appears once known.
            if (markLeftOut) l.also { it.forEach { m -> com.lekathedon.visionlite.recs.LeaveOut.hides(m) } } else com.lekathedon.visionlite.recs.LeaveOut.visible(l)
        }.let { l -> if (markLeftOut || com.lekathedon.visionlite.recs.Released.upcomingRow(key, title)) l else com.lekathedon.visionlite.recs.Released.visible(l) }
    }
    val subIn = sub
    @Suppress("NAME_SHADOWING")
    val sub: (MetaPreview) -> String? = if (!markLeftOut || loText.isEmpty()) subIn else { m ->
        com.lekathedon.visionlite.recs.LeaveOut.why(m)?.let { w -> "⊘ Left out · $w" + (subIn(m)?.let { " · $it" } ?: "") } ?: subIn(m)
    }
    // Every title: hold OK = Love it / Like / Not for me (the page's own hold, when it has one, comes first).
    val onHold: ((MetaPreview) -> Unit)? = if (!filter) onHoldIn else onHoldIn ?: { m: MetaPreview -> TasteMenu.show(m) }
    if (metas.isEmpty() && !loading) return
    val prefs = App.instance.prefs
    val size by prefs.posterSize.flow.collectAsStateWithLifecycle()
    val dims = remember(size) { EchoDims.of(size) }
    val trailersOn by prefs.trailers.flow.collectAsStateWithLifecycle()
    val delayText by prefs.trailerDelay.flow.collectAsStateWithLifecycle()
    val sound by prefs.trailerSound.flow.collectAsStateWithLifecycle()
    val look = remember(trailersOn, delayText, sound, expand) { TrailerLook(trailersOn && expand, ((delayText.toDoubleOrNull() ?: 5.0) * 1000).toLong(), !sound) }
    val slots = remember(key) { RowSlots(key, home = false) }
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()
    slots.state = state; slots.scope = scope
    var rowFocus by remember { mutableStateOf(false) }
    val withSub = metas.any { sub(it) != null }
    EchoRowTitle(title)
    CompositionLocalProvider(LocalBringIntoViewSpec provides remember { snapSpec(0f) }, LocalRowSlots provides slots, LocalTrailer provides look) {
        LazyRow(
            Modifier.fillMaxWidth().height(dims.posterHeight + if (withSub) 44.dp else 0.dp)
                .onFocusChanged { if (it.hasFocus && !rowFocus) slots.entered = true; rowFocus = it.hasFocus }
                .focusRestorer { slots.reqs[slots.target] ?: FocusRequester.Default }
                .focusGroup()
                .onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && slots.last >= metas.size - 1 && metas.isNotEmpty() },
            state = state,
            // A full screen of room after the last card (600 dp wasn't): the last card can rest on the left like every
            // other, instead of the row stopping short and the previous card's shrink pulling it half off-screen.
            contentPadding = PaddingValues(end = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp),
            horizontalArrangement = Arrangement.spacedBy(dims.gap)
        ) {
            if (metas.isEmpty()) item(key = "loading") { Slot(0) { LoadingCard(key, dims.posterWidth, dims.posterHeight, dims.radius, { emptyList() }, scope) } }
            itemsIndexed(metas, key = { i, m -> "$i:${m.id}" }) { i, m -> Slot(i) {
                if (onEnd != null && i >= metas.size - 6) LaunchedEffect(metas.size) { onEnd() }
                val mm = remember(m) { if (m.poster.isNullOrBlank() && m.id.startsWith("tt")) m.copy(poster = posterOf(m.id)) else m }
                Column {
                    val mod = if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier
                    if (expand) PosterCard(mm, dims, mod, onFocus = {}, onClick = { onClick(m) }, onHold = onHold?.takeIf { TasteMenu.isTitle(m) }?.let { h -> { h(m) } })
                    else EchoCard(dims.posterWidth, dims.posterHeight, dims.radius, mod, onFocus = {}, onClick = { onClick(m) }, onHold = onHold?.takeIf { TasteMenu.isTitle(m) }?.let { h -> { h(m) } }) {
                        val art = if (overlay != null) mm.poster else com.lekathedon.visionlite.poster.Posters.poster(mm.id, mm.type, mm.poster)
                        if (art.isNullOrBlank()) Fallback(mm.name) else Img(art, dims.posterWidth, dims.posterHeight, Modifier.fillMaxSize(), fallback = mm.poster)
                        overlay?.invoke(this, mm)
                    }
                    sub(m)?.let { Text(it, color = Color(0xB3FFFFFF), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(dims.posterWidth).padding(top = 6.dp)) }
                }
            } }
        }
    }
}

/** A Genres / Decades tile: a picture from its titles (or its own colour), the name big in the middle. */
@Composable
private fun BrowseTile(id: String, name: String, art: String?, dims: EchoDims, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val tint = remember(id) { TileColors[(id.hashCode() and 0x7fffffff) % TileColors.size] }
    EchoCard(dims.cwWidth, dims.cwHeight, dims.radius, modifier, onFocus, onClick) { focused ->
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(tint, Color(0xFF101010)))))
        if (art != null) Img(art, dims.cwWidth, dims.cwHeight, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.Black.copy(alpha = if (focused) 0.35f else 0.55f), Color.Black.copy(alpha = 0.15f)))))
        Text(name, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = androidx.compose.material3.LocalTextStyle.current.copy(shadow = Shadow(Color.Black.copy(alpha = 0.7f), blurRadius = 12f)),
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp))
    }
}

private val TileColors = listOf(Color(0xFF7A1F2B), Color(0xFF1F4E7A), Color(0xFF2E6B3A), Color(0xFF6B4A1F), Color(0xFF4B2A6E),
    Color(0xFF1F6B6B), Color(0xFF7A3D1F), Color(0xFF3A3A6E), Color(0xFF6E2A55), Color(0xFF41611F))

/** Echo's row title, for rows on other pages. */
@Composable
fun EchoRowTitle(title: String) {
    if (title.isNotBlank()) com.lekathedon.visionlite.ui.HomeHeading(title, Modifier.padding(top = 18.dp, bottom = 10.dp))
}

/** The Continue watching episode's still from the show's meta (cached; the billboard loads the same meta). */
@Composable
private fun cwStill(r: com.lekathedon.visionlite.trakt.Resume): String? {
    if (r.season == null) return null
    val still by androidx.compose.runtime.produceState<String?>(null, r.streamId) {
        fun pick(m: com.lekathedon.visionlite.addons.Meta?) = m?.videos?.firstOrNull { it.season == r.season && it.episode == r.episode }
            ?.thumbnail?.takeIf { it.isNotBlank() }
        // Anime: the first add-on answering is often an anime one with its own episode numbers (no match, so the card fell
        // back to the poster). Cinemeta numbers episodes as Trakt does: asked only when the add-ons' answer has no still.
        value = pick(runCatching { App.instance.client.meta("series", r.imdb) }.getOrNull())
            ?: if (!r.imdb.startsWith("tt")) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                pick(runCatching { com.lekathedon.visionlite.data.Net.getJson<com.lekathedon.visionlite.addons.MetaResponse>(
                    "${com.lekathedon.visionlite.data.Our.cinemeta}/meta/series/${r.imdb}.json", 10).meta }.getOrNull())
            }
    }
    return still
}

/** Continue Watching: wide, title and episode bottom-left, progress along the bottom edge. */
@Composable
private fun ContinueWatchingCard(
    m: MetaPreview, episode: String?, progress: Float, dims: EchoDims, modifier: Modifier, still: String?, blur: Boolean,
    onFocus: () -> Unit, onClick: () -> Unit, onHold: (() -> Unit)? = null
) {
    val hasProgress = progress > 0f
    EchoCard(dims.cwWidth, dims.cwHeight, dims.radius, modifier, onFocus, onClick, onHold = onHold) {
        // The episode's own image (Vision's episode thumbnails); not watched yet, so blurred when the spoiler blur is on.
        if (still != null) Img(still, dims.cwWidth, dims.cwHeight, Modifier.fillMaxSize(), blur = blur)
        else Img(com.lekathedon.visionlite.poster.Posters.wide(m.id, m.type, m.background), dims.cwWidth, dims.cwHeight, Modifier.fillMaxSize(), fallback = m.background)
        Box(Modifier.fillMaxSize().bottomShade())
        val shadow = androidx.compose.material3.LocalTextStyle.current.copy(shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 8f))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 14.dp, bottom = if (hasProgress) 14.dp else 12.dp)) {
            Text(m.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = shadow)
            if (!episode.isNullOrBlank()) Text(episode, color = Color.White.copy(alpha = 0.78f), fontSize = 12.sp, maxLines = 1, style = shadow)
        }
        if (hasProgress) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.28f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(com.lekathedon.visionlite.ui.Kit.accent))
            }
        }
    }
}

@Composable
private fun BoxScope.Fallback(title: String) {
    Text(title, color = com.lekathedon.visionlite.ui.Palette.TextDim, fontSize = 13.sp, maxLines = 3, modifier = Modifier.align(Alignment.Center).padding(10.dp))
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
            style = androidx.compose.material3.LocalTextStyle.current.copy(fontFamily = com.lekathedon.visionlite.ui.Kit.display, shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 10f)),
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
private fun DetailsLine(m: MetaPreview, color: Color = com.lekathedon.visionlite.ui.Palette.Text.copy(alpha = 0.82f), dotColor: Color = com.lekathedon.visionlite.ui.Palette.TextDim.copy(alpha = 0.6f)) {
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
        "★", color = com.lekathedon.visionlite.ui.Kit.onAccent, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em,
        modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(com.lekathedon.visionlite.ui.Kit.accent).padding(horizontal = 3.dp, vertical = 1.dp)
    )
}

// ---- The billboard's art (NetflixLegacyBackdrop): wall to wall, darkened on the left and at the bottom ----

@Composable
private fun EchoBackdrop(url: String?, trailer: com.lekathedon.visionlite.trailer.Trailer?, muted: Boolean, onEnded: () -> Unit, modifier: Modifier) {
    url ?: return
    Box(modifier.fillMaxSize()) {
        Img(url, 960.dp, 540.dp, Modifier.fillMaxSize())
        if (trailer != null) com.lekathedon.visionlite.trailer.TrailerVideo(trailer.url, muted, Modifier.fillMaxSize(), trailer.audio, onEnded = onEnded)
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

/** Vision's sections and their pages, as far as Lite has built them (the rest join here as they arrive). */
private object Sections {
    /** Anime has no section of its own when Settings › Anime is Merged or Off. */
    val all get() = listOf("NUVIO" to "Films & TV", "ANIME" to "Anime", "IPTV" to "IPTV")
        .filter { com.lekathedon.visionlite.anime.Hubs.on(it.first) }   // Settings › Home hubs (Anime also needs its own hub)
    fun label(key: String) = all.firstOrNull { it.first == key }?.second ?: "Films & TV"
    fun pages(key: String): List<Pair<String, Screen>> = when (key) {
        "IPTV" -> listOf("Live TV" to Screen.LiveTv) +
            (if (com.lekathedon.visionlite.sport.Scores.settings.value.showInSidebar) listOf("Sports" to Screen.Sports) else emptyList()) +
            listOf("VOD" to Screen.Vod, "Recordings" to Screen.Recordings)
        else -> listOf("Discover" to Screen.Discover, "Search" to Screen.Search,
            "Library" to Screen.Library, "Calendar" to Screen.Calendar) +
            (if (com.lekathedon.visionlite.streams.Cloud.services().isNotEmpty()) listOf("Cloud" to Screen.Cloud) else emptyList()) +
            (if (com.lekathedon.visionlite.App.instance.prefs.arabBrowse.value && com.lekathedon.visionlite.tb.ArabP2P.configured()) listOf("ArabP2P" to Screen.ArabBrowse) else emptyList())
    }
    /** Every section stays on Home with its pages in the pill (YouTube and Music removed 2026-11-26). */
    @Suppress("UNUSED_PARAMETER") fun opensAt(key: String): Screen? = null
}

// ---- Vision's own row cards ----

/** Coming up: wide, the show's art, its name, when ("New", "Tonight 21:00", …) and the episode. */
@Composable
private fun ComingCard(m: MetaPreview, dims: EchoDims, onFocus: () -> Unit, onHold: (() -> Unit)?, onClick: () -> Unit) {
    EchoCard(dims.cwWidth, dims.cwHeight, dims.radius, Modifier, onFocus, onClick, onHold = onHold) {
        Img(com.lekathedon.visionlite.poster.Posters.wide(m.id, m.type, m.background), dims.cwWidth, dims.cwHeight, Modifier.fillMaxSize(), fallback = m.background)
        Box(Modifier.fillMaxSize().bottomShade())
        val shadow = androidx.compose.material3.LocalTextStyle.current.copy(shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 8f))
        val isNew = m.releaseInfo == "New"
        Text(m.releaseInfo.orEmpty(), color = if (isNew) com.lekathedon.visionlite.ui.Kit.onAccent else com.lekathedon.visionlite.ui.Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp).clip(homeShape())
                .background(if (isNew) com.lekathedon.visionlite.ui.Kit.accent else com.lekathedon.visionlite.ui.Theme.skin.capsule.copy(alpha = 0.8f)).padding(horizontal = 10.dp, vertical = 3.dp))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
            Text(m.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, style = shadow)
            m.description?.let { Text(it, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, style = shadow) }
        }
    }
}

/**
 * A streaming service, drawn as Vision's ServiceTileOverlay: backdrop from its shows, its logo in its own colours on a
 * soft round shade (white for the dark-on-clear ones). No intro clips (user 2026-11-27: the services' own animations
 * are their artwork; logos only name the service).
 */
/** Logos that are dark on clear on TMDB: drawn white. */
private val LIGHT_LOGO = setOf("apple", "peacock", "hidive", "danime", "unext", "toei")

@Composable
private fun ServiceCard(m: MetaPreview, dims: EchoDims, onFocus: () -> Unit, onClick: () -> Unit) {
    val sid = m.id.removePrefix("svc:")
    EchoCard(dims.cwWidth, dims.cwHeight, dims.radius, Modifier, onFocus, onClick) { focused ->
        Img(m.background, dims.cwWidth, dims.cwHeight, Modifier.fillMaxSize())
        if (m.logo != null) {
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.15f)))),
                contentAlignment = Alignment.Center) {
                val context = LocalContext.current
                val req = remember(m.logo) { ImageRequest.Builder(context).data(m.logo).build() }
                coil3.compose.AsyncImage(req, m.name, Modifier.fillMaxWidth(0.62f).fillMaxHeight(0.42f), contentScale = ContentScale.Fit,
                    colorFilter = if (sid in LIGHT_LOGO) androidx.compose.ui.graphics.ColorFilter.tint(Color.White) else null)
            }
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            Text(m.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }
    }
}

/** A favourite person: photo, name under it. */
@Composable
private fun PersonCard(m: MetaPreview, dims: EchoDims, onFocus: () -> Unit, onClick: () -> Unit) {
    EchoCard(dims.posterWidth, dims.posterHeight, dims.radius, Modifier, onFocus, onClick) {
        if (m.poster.isNullOrBlank()) Fallback(m.name) else Img(m.poster, dims.posterWidth, dims.posterHeight, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().bottomShade())
        Text(m.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
    }
}

/** The end of a Picks row: new picks now. */
@Composable
private fun RefreshCard(dims: EchoDims, onFocus: () -> Unit, onClick: () -> Unit) {
    EchoCard(dims.posterWidth, dims.posterHeight, dims.radius, Modifier, onFocus, onClick) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("↻", color = com.lekathedon.visionlite.ui.Palette.Text, fontSize = 34.sp)
            Text("Refresh", color = com.lekathedon.visionlite.ui.Palette.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Hold OK on a title: Love it / Like / Not for me (the one engine behind every recommendation), Move, Turn this row off, Library. */
@Composable
private fun HoldMenu(row: Row, m: MetaPreview, scope: kotlinx.coroutines.CoroutineScope, onClose: (removed: Boolean) -> Unit) {
    androidx.activity.compose.BackHandler { onClose(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    val tv = m.type == "series"
    val verdict = remember(m.id) { Recs.verdict(m.id) }
    val saved = remember(m.id) { Saves.has(m.id) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        com.lekathedon.visionlite.ui.KitPopup(m.name, Modifier.width(360.dp), eyebrow = row.title.takeIf { it.isNotBlank() }) {
            var firstUsed = false
            @Composable fun Choice(label: String, removes: Boolean = false, act: () -> Unit) {
                val mod = if (!firstUsed) { firstUsed = true; Modifier.fillMaxWidth().focusRequester(first) } else Modifier.fillMaxWidth()
                com.lekathedon.visionlite.ui.KitMenuItem(label, { act(); onClose(removes) }, mod)
            }
            if (m.type == "movie" || m.type == "series") {
                val loved = verdict == "love" || verdict == "like"
                Choice(if (loved) "Loved ✓ (undo)" else "Love it") { if (loved) Recs.forget(m.id) else Recs.love(m.id, tv, m.name) }
                // Gone from the engine's rows at once and never recommended again; other rows (charts, services) keep it.
                Choice("Not for me", removes = VisionRows.isEngine(row.key)) { Recs.notForMe(m.id, tv, m.name) }
                // A tick on the card, then gone from every recommendation for good; lists (charts, services, Library) keep it.
                Choice(if (verdict == "watched") "Watched ✓ (undo)" else "Watched") { if (verdict == "watched") Recs.forget(m.id) else Ticks.watched(m) }
            }
            if (tv && m.id.startsWith("tt")) {
                val following = remember(m.id) { ComingUp.isFollowed(m.id) }
                if (following || !ComingUp.ended(m.id, m.releaseInfo)) Choice(if (following) "Following ✓ (stop)" else "Follow · new seasons in Coming up") { ComingUp.follow(m.id, m.name, !following) }
            }
            if (VisionRows.base(row.key) == VisionRows.COMING_UP) Choice("Drop from Coming up", removes = true) { ComingUp.drop(m.id) }
            // Wrong hub: the title moves to the other one, everywhere (its IMDb id too, when the card has another id).
            val anime = remember(m.id) { com.lekathedon.visionlite.anime.AnimeIds.isAnime(m.id) }
            Choice(if (anime) "Move to Films & TV" else "Move to Anime", removes = true) {
                com.lekathedon.visionlite.anime.AnimeIds.move(m.id, !anime)
                if (!m.id.startsWith("tt")) scope.launch { imdbIdOf(m)?.takeIf { it.startsWith("tt") }?.let { com.lekathedon.visionlite.anime.AnimeIds.move(it, !anime) } }
            }
            // Repeat rows: one press turns the whole row off in this hub (Settings › Home rows turns it back on).
            Choice("Turn this row off") {
                val h = com.lekathedon.visionlite.anime.Hubs.rowsHidden(com.lekathedon.visionlite.anime.Hubs.now())
                h.value = (h.value.split('\n').filter { it.isNotEmpty() }.toSet() + row.key).joinToString("\n")
            }
            Choice(if (saved) "Remove from Library" else "Add to Library") {
                scope.launch {
                    val id = imdbIdOf(m) ?: return@launch
                    Saves.toggle(Saved(m.type, id, m.name, m.poster))
                }
            }
            Choice("Cancel") {}
        }
    }
}

/** The white tick on a card you said you watched (it shows ~1 s, then the card leaves the recommendation rows). */
@Composable
fun WatchedTick(id: String, modifier: Modifier = Modifier) {
    val ticks by Ticks.ids.collectAsStateWithLifecycle()
    if (id !in ticks) return
    Box(modifier.padding(8.dp).size(30.dp).clip(androidx.compose.foundation.shape.CircleShape).background(com.lekathedon.visionlite.ui.Kit.accent), contentAlignment = Alignment.Center) {
        Text("✓", color = com.lekathedon.visionlite.ui.Kit.onAccent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}
