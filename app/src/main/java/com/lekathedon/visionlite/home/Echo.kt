// Vision's Echo look (Nuvio Echo, github.com/Laking22/Nuvio-Echo, GPL-3.0, as ported to Nuvio Vision), drawn the
// Lite way: no motion, focus and scroll in one frame, posters open to landscape at once, images at drawn size.
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

private val Start = 48.dp
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
    var focused by remember { mutableStateOf<MetaPreview?>(null) }
    var shown by remember { mutableStateOf<MetaPreview?>(null) }
    LaunchedEffect(Unit) { snapshotFlow { focused }.collectLatest { delay(120); shown = it } }

    val cw by vm.continueWatching.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshContinue(force = com.lekathedon.visionlite.player.PlayerReturn.consume()) }
    val hidden = remember(hiddenText) { hiddenText.split('\n').filter { it.isNotEmpty() }.toSet() }
    val visible = rows.filter { it.key !in hidden && (it.items.isNotEmpty() || !it.done) }
    // Vision's start screen: the Legacy billboard (titles of the first row), focus on Watch Now.
    val heroItems = remember(visible.firstOrNull { it.items.isNotEmpty() }?.items) {
        visible.firstOrNull { it.items.isNotEmpty() }?.items.orEmpty().take(6)
    }
    var heroIndex by remember { mutableStateOf(0) }
    var heroFocused by remember { mutableStateOf(true) }
    val heroItem = heroItems.getOrNull(heroIndex.coerceAtMost((heroItems.size - 1).coerceAtLeast(0)))
    val watchNow = remember { FocusRequester() }
    LaunchedEffect(heroItems.isNotEmpty()) { if (heroItems.isNotEmpty() && focused == null) { withFrameNanos { }; runCatching { watchNow.requestFocus() } } }
    val density = androidx.compose.ui.platform.LocalDensity.current
    // The billboard sits at the page's top; a focused row lands under the compact details (Echo's place for it).
    val pageSpec = remember(density) {
        val lead = with(density) { (RowsTop).toPx() }
        val heroPx = with(density) { 300.dp.toPx() }
        object : androidx.compose.foundation.gestures.BringIntoViewSpec {
            @Suppress("OVERRIDE_DEPRECATION")
            override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> = androidx.compose.animation.core.snap()
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                if (size > heroPx) offset else offset - lead
        }
    }
    val art = if (heroFocused) heroItem else shown

    Box(Modifier.fillMaxSize().background(look.page)) {
        if (!look.oled) AmbientPageWash(art?.wide(), Modifier.fillMaxSize())
        if (heroFocused) EchoBackdrop(heroItem, 540.dp) else { EchoBackdrop(shown, 400.dp); EchoHeroCopy(shown) }
        CompositionLocalProvider(LocalBringIntoViewSpec provides pageSpec) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 300.dp)) {
                item(key = "hero") {
                    Billboard(
                        heroItems, heroItem, heroIndex, watchNow,
                        onFocus = { if (it) heroFocused = true },
                        onStep = { d -> if (heroItems.isNotEmpty()) heroIndex = (heroIndex + d + heroItems.size) % heroItems.size },
                        onWatch = { m ->
                            if (m.type == "movie") nav.push(Screen.Streams(m.type, m.id, m.name, m.id)) else nav.push(Screen.Detail(m.type, m.id, m.name))
                        },
                        onInfo = { m -> nav.push(Screen.Detail(m.type, m.id, m.name)) }
                    )
                }
                if (cw.isNotEmpty()) item(key = "cw") {
                    EchoRow("Continue Watching", dims, dims.cwHeight, shown) {
                        itemsIndexed(cw, key = { _, r -> r.streamId }) { _, r ->
                            val m = remember(r) { MetaPreview(r.imdb, r.type, r.label, posterOf(r.imdb), backdropOf(r.imdb)) }
                            ContinueWatchingCard(
                                m, r.season?.let { "S$it E${r.episode}" }, r.progress / 100f, dims, Modifier,
                                onFocus = { focused = m; heroFocused = false },
                                onClick = { nav.push(Screen.Streams(r.type, r.streamId, r.label, r.imdb)) }
                            )
                        }
                    }
                }
                items(visible, key = { it.key }) { row ->
                    LaunchedEffect(row.key) { vm.need(row.key) }
                    EchoRow(row.title, dims, dims.posterHeight, shown) {
                        if (row.items.isEmpty()) item { Box(Modifier.size(dims.posterWidth, dims.posterHeight).clip(RoundedCornerShape(dims.radius)).background(CardBackground)) }
                        itemsIndexed(row.items, key = { _, m -> m.id }) { i, m ->
                            PosterCard(
                                m, dims, Modifier,
                                onFocus = {
                                    focused = m
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
        // The page scrolls under the top band: pill in the middle, clock on the right (Vision's Echo Home).
        Box(Modifier.fillMaxWidth().height(TopBand).background(
            Brush.verticalGradient(0f to Color(0x99000000), 1f to Color.Transparent)
        ))
        TopPill(nav)
        if (App.instance.prefs.clock.flow.collectAsStateWithLifecycle().value) ClockPill(Modifier.align(Alignment.TopEnd))
        if (askExit) ExitAsk(onLeave = { activity?.finish() }, onStay = { askExit = false; LastFocus?.let { runCatching { it.requestFocus() } } })
    }
}

// ---- Legacy billboard (NetflixLegacyBillboard): title, details, plot, Watch Now / More Info, page dots ----

@Composable
private fun Billboard(
    items: List<MetaPreview>, item: MetaPreview?, index: Int, watchNow: FocusRequester,
    onFocus: (Boolean) -> Unit, onStep: (Int) -> Unit, onWatch: (MetaPreview) -> Unit, onInfo: (MetaPreview) -> Unit
) {
    Box(Modifier.fillMaxWidth().height(470.dp).onFocusChanged { onFocus(it.hasFocus) }) {
        if (item == null) return@Box
        Column(Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 22.dp).width(420.dp)) {
            var failed by remember(item.id) { mutableStateOf(false) }
            val logo = item.logoUrl()
            if (!logo.isNullOrBlank() && !failed) {
                val context = LocalContext.current
                val req = remember(logo) { ImageRequest.Builder(context).data(logo).build() }
                coil3.compose.AsyncImage(req, item.name, Modifier.widthIn(max = 300.dp).heightIn(max = 90.dp),
                    contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, onError = { failed = true })
            } else {
                Text(item.name.uppercase(), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 10f)))
            }
            Box(Modifier.padding(top = 10.dp)) { DetailsLine(item, color = Color(0xFFE6E6E6)) }
            item.description?.let {
                Text(it, color = Color(0xCCFFFFFF), fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp))
            }
            Row(
                Modifier.padding(top = 14.dp).onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) false
                    else when (e.key) {
                        // Left on Watch Now / Right on More Info step the billboard's titles.
                        Key.DirectionLeft -> if (leftEdge) { onStep(-1); true } else false
                        Key.DirectionRight -> if (rightEdge) { onStep(1); true } else false
                        else -> false
                    }
                },
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HeroButton("▶  Watch Now", Modifier.focusRequester(watchNow).onFocusChanged { leftEdge = it.isFocused }) { onWatch(item) }
                HeroButton("More Info", Modifier.onFocusChanged { rightEdge = it.isFocused }) { onInfo(item) }
            }
        }
        if (items.size > 1) Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 32.dp, bottom = 34.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            items.indices.forEach { i ->
                Box(Modifier.size(if (i == index) 14.dp else 5.dp, 5.dp).clip(RoundedCornerShape(50))
                    .background(if (i == index) Color(0xCCFFFFFF) else Color(0x55FFFFFF)))
            }
        }
    }
}

private var leftEdge = false
private var rightEdge = false

@Composable
private fun HeroButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val f by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(50)
    Text(
        text, color = if (f) Color.Black else Color(0xCCFFFFFF), fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = modifier.clip(shape).background(if (f) Color.White else Color(0x661C1C1E))
            .border(1.dp, if (f) Color.White else Color(0x33FFFFFF), shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp)
    )
}

// ---- Top band: PillNavigationBar (glass pill, top centre) and LegacyClockPill (top right) ----

@Composable
private fun BoxScope.TopPill(nav: Nav) {
    Row(
        Modifier.align(Alignment.TopCenter).padding(top = 12.dp).height(44.dp)
            .clip(RoundedCornerShape(50)).background(Color(0xE61C1C1E))
            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(50)).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PillItem(null, Icon.Search) { nav.push(Screen.Search) }
        PillItem("Home", null, selected = true) {}
        PillItem("Library", null) { nav.push(Screen.Library) }
        PillItem(null, Icon.Gear) { nav.push(Screen.Settings) }
    }
}

private enum class Icon { Search, Gear }

@Composable
private fun PillItem(label: String?, icon: Icon?, selected: Boolean = false, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val f by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(50)
    val tint = if (f) Color.Black else if (selected) Color.White else Color.White.copy(alpha = 0.72f)
    Box(
        Modifier.fillMaxHeight().clip(shape)
            .background(when { f -> Color.White; selected -> Color(0x33FFFFFF); else -> Color.Transparent })
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        if (label != null) Text(label, color = tint, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
        else androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
            val w = size.width
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.1f)
            if (icon == Icon.Search) {
                drawCircle(tint, radius = w * 0.3f, center = androidx.compose.ui.geometry.Offset(w * 0.42f, w * 0.42f), style = stroke)
                drawLine(tint, androidx.compose.ui.geometry.Offset(w * 0.64f, w * 0.64f), androidx.compose.ui.geometry.Offset(w * 0.9f, w * 0.9f), strokeWidth = w * 0.1f)
            } else {
                val c = androidx.compose.ui.geometry.Offset(w / 2, w / 2)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    drawLine(tint, c, androidx.compose.ui.geometry.Offset(c.x + (w * 0.46f * Math.cos(a)).toFloat(), c.y + (w * 0.46f * Math.sin(a)).toFloat()), strokeWidth = w * 0.16f)
                }
                drawCircle(tint, radius = w * 0.32f, center = c)
                drawCircle(if (f) Color.White else Color(0xFF1C1C1E), radius = w * 0.13f, center = c)
            }
        }
    }
}

/** Legacy's clock island: the time on a dark capsule, 16 dp from the top, 32 dp from the right. */
@Composable
private fun ClockPill(modifier: Modifier) {
    val context = LocalContext.current
    val fmt = remember { android.text.format.DateFormat.getTimeFormat(context) }
    var now by remember { mutableStateOf(fmt.format(java.util.Date())) }
    LaunchedEffect(Unit) {
        while (true) { delay(60_000 - System.currentTimeMillis() % 60_000); now = fmt.format(java.util.Date()) }
    }
    Box(
        modifier.padding(top = 16.dp, end = 32.dp).height(30.dp).clip(RoundedCornerShape(50))
            .background(Color(0xFF121316).copy(alpha = 0.86f)).padding(horizontal = 12.dp),
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
            title, color = Color(0xE6FFFFFF), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
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

// ---- Hero (Vision's Echo keeps the Legacy wall-to-wall backdrop) ----

/** The focused title's backdrop across the top, melting into the page at its left and bottom. */
@Composable
private fun EchoBackdrop(m: MetaPreview?, height: Dp) {
    m ?: return
    Box(
        Modifier.fillMaxWidth().height(height)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(Brush.horizontalGradient(0f to Color(0x33000000), 0.5f to Color.Black), blendMode = BlendMode.DstIn)
                drawRect(Brush.verticalGradient(0.45f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            }
    ) { Img(m.wide(), 960.dp, height, Modifier.fillMaxSize()) }
}

@Composable
private fun EchoHeroCopy(m: MetaPreview?) {
    m ?: return
    Column(Modifier.padding(start = Start, top = TopBand + 18.dp).width(440.dp)) {
        var failed by remember(m.id) { mutableStateOf(false) }
        val logo = m.logoUrl()
        if (!logo.isNullOrBlank() && !failed) {
            val context = LocalContext.current
            val req = remember(logo) { ImageRequest.Builder(context).data(logo).build() }
            coil3.compose.AsyncImage(req, m.name, Modifier.widthIn(max = 280.dp).heightIn(max = 80.dp),
                contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, onError = { failed = true })
        } else {
            Text(m.name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 10f)))
        }
        Box(Modifier.padding(top = 10.dp)) { DetailsLine(m, color = Color(0xFFE6E6E6)) }
        m.description?.let {
            Text(it, color = Color(0xCCFFFFFF), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp))
        }
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
