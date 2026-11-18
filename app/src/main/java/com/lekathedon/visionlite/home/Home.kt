package com.lekathedon.visionlite.home

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
private val PosterW = 120.dp
private val PosterH = 180.dp

/**
 * Home: focused title's details on top, rows below. No motion: focus and scroll move in one frame, the focused row
 * snaps under the details. The details/tint follow focus after 120 ms, so holding a direction never loads art.
 */
@Composable
fun Home(nav: Nav) {
    val vm: HomeModel = viewModel()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val look = LocalLook.current
    var focused by remember { mutableStateOf<MetaPreview?>(null) }
    var shown by remember { mutableStateOf<MetaPreview?>(null) }
    LaunchedEffect(Unit) { snapshotFlow { focused }.collectLatest { delay(120); shown = it } }

    val visible = rows.filter { it.items.isNotEmpty() || !it.done }
    val firstFocus = remember { FocusRequester() }
    val firstReady = visible.firstOrNull()?.items?.isNotEmpty() == true
    LaunchedEffect(firstReady) { if (firstReady && focused == null) runCatching { firstFocus.requestFocus() } }

    Box(Modifier.fillMaxSize().background(look.page)) {
        if (!look.oled) ArtTint(shown?.background ?: shown?.poster)
        Hero(shown)
        Column(Modifier.fillMaxSize()) {
            TopBar(nav)
            Spacer(Modifier.height(HeroH - 52.dp))
            CompositionLocalProvider(LocalBringIntoViewSpec provides remember { snapSpec(0f) }) {
                LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 280.dp)) {
                    items(visible, key = { it.key }) { row ->
                        LaunchedEffect(row.key) { vm.need(row.key) }
                        Column(Modifier.padding(bottom = 18.dp)) {
                            Text(row.title, color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = Start, bottom = 8.dp))
                            if (row.items.isEmpty()) {
                                Spacer(Modifier.height(PosterH))
                            } else {
                                CompositionLocalProvider(LocalBringIntoViewSpec provides remember { snapSpec(0f) }) {
                                    LazyRow(contentPadding = PaddingValues(horizontal = Start), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        itemsIndexed(row.items, key = { _, m -> m.id }) { i, m ->
                                            Poster(
                                                m,
                                                modifier = if (row.key == visible.first().key && i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                                                onFocus = {
                                                    focused = m
                                                    if (i >= row.items.size - 6) vm.more(row.key)
                                                },
                                                onClick = { nav.push(Screen.Detail(m.type, m.id, m.name)) }
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
    }
}

@Composable
private fun Poster(m: MetaPreview, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    Focusable(onClick, modifier.size(PosterW, PosterH), onFocus = onFocus) {
        Box(Modifier.fillMaxSize().background(Palette.Card)) {
            if (m.poster.isNullOrBlank()) {
                Text(m.name, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            } else {
                Img(m.poster, PosterW, PosterH, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun TopBar(nav: Nav) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(start = Start, end = Start, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Vision Lite", color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Button("Search", { nav.push(Screen.Search) })
        Button("Library", { nav.push(Screen.Library) })
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
