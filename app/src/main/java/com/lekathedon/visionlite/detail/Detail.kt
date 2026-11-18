package com.lekathedon.visionlite.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.addons.Meta
import com.lekathedon.visionlite.addons.Video
import com.lekathedon.visionlite.ui.ArtTint
import com.lekathedon.visionlite.ui.Button
import com.lekathedon.visionlite.ui.Focusable
import com.lekathedon.visionlite.ui.Img
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.Screen

/** A title: details, Play (movies) or seasons + episodes (series). One meta call, cached. */
@Composable
fun Detail(nav: Nav, s: Screen.Detail) {
    var meta by remember(s.id) { mutableStateOf<Meta?>(null) }
    var failed by remember(s.id) { mutableStateOf(false) }
    LaunchedEffect(s.id) {
        meta = runCatching { App.instance.client.meta(s.type, s.id) }.getOrNull()
        failed = meta == null
    }
    val look = LocalLook.current
    val m = meta
    Box(Modifier.fillMaxSize().background(look.page)) {
        if (!look.oled) ArtTint(m?.background ?: m?.poster)
        if (m == null) {
            Text(if (failed) "No details for ${s.name}" else s.name, color = Palette.Text, fontSize = 24.sp, modifier = Modifier.padding(48.dp))
            return@Box
        }
        val seasons = remember(m) { m.videos.groupBy { it.season ?: 0 }.toSortedMap().let { g -> if (g.size > 1) g.filterKeys { it != 0 } else g } }
        var season by remember(m) { mutableStateOf(seasons.keys.firstOrNull() ?: 0) }
        val first = remember { FocusRequester() }
        LaunchedEffect(m) { runCatching { first.requestFocus() } }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(48.dp)) {
            item {
                Column(Modifier.width(620.dp)) {
                    if (!m.logo.isNullOrBlank()) Img(m.logo, 300.dp, 90.dp, Modifier.size(300.dp, 90.dp), androidx.compose.ui.layout.ContentScale.Fit)
                    else Text(m.name, color = Palette.Text, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    val facts = listOfNotNull(m.releaseInfo, m.runtime, m.imdbRating?.let { "IMDb $it" }, m.genres.take(3).joinToString(" · ").ifBlank { null })
                    Text(facts.joinToString("  ·  "), color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
                    m.description?.let { Text(it, color = Palette.TextDim, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp)) }
                    if (m.cast.isNotEmpty()) Text("Cast: " + m.cast.take(5).joinToString(", "), color = Palette.TextDim, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
                }
            }
            if (m.type != "series" || m.videos.isEmpty()) {
                item {
                    Button("Play", { nav.push(Screen.Streams(m.type, m.id, m.name, m.id)) }, Modifier.padding(top = 20.dp).focusRequester(first))
                }
            } else {
                item {
                    LazyRow(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(seasons.keys.toList()) { n ->
                            val mod = if (n == seasons.keys.first()) Modifier.focusRequester(first) else Modifier
                            Button(if (n == 0) "Specials" else "Season $n" + if (n == season) " •" else "", { season = n }, mod)
                        }
                    }
                }
                items(seasons[season].orEmpty().sortedBy { it.episode ?: 0 }, key = { it.id }) { v ->
                    Episode(v) { nav.push(Screen.Streams("series", v.id, "${m.name} S${v.season}E${v.episode}", m.id)) }
                }
            }
        }
    }
}

@Composable
private fun Episode(v: Video, onClick: () -> Unit) {
    Focusable(onClick, Modifier.padding(top = 10.dp).fillMaxWidth(0.75f)) { f ->
        Row(Modifier.background(if (f) Palette.Card else androidx.compose.ui.graphics.Color.Transparent).padding(8.dp)) {
            Box(Modifier.size(160.dp, 90.dp).background(Palette.Card)) { Img(v.thumbnail, 160.dp, 90.dp, Modifier.fillMaxSize()) }
            Column(Modifier.padding(start = 14.dp)) {
                Text("${v.episode ?: ""}. ${v.label}", color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                (v.overview ?: v.description)?.let { Text(it, color = Palette.TextDim, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
                v.released?.take(10)?.let { Text(it, color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
    }
}
