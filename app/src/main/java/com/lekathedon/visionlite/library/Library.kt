package com.lekathedon.visionlite.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.trakt.ListItem
import com.lekathedon.visionlite.ui.Focusable
import com.lekathedon.visionlite.ui.Img
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.Screen

fun posterOf(imdb: String) = "https://images.metahub.space/poster/medium/$imdb/img"
fun backdropOf(imdb: String) = "https://images.metahub.space/background/medium/$imdb/img"

/** Library = local saves, then the Trakt watchlist (newest first): movies and shows, each a row like Home's. */
@Composable
fun Library(nav: Nav) {
    val trakt = App.instance.trakt
    var items by remember { mutableStateOf<List<ListItem>?>(null) }
    // Only the hub you're in (Films & TV or Anime).
    val hub = remember { com.lekathedon.visionlite.anime.Hubs.now() }
    val saved = remember { Saves.all().filter { com.lekathedon.visionlite.anime.Hubs.keepsId(hub, it.id) } }
    LaunchedEffect(Unit) { items = if (trakt.connected.value != null) trakt.watchlist() else emptyList() }
    val first = remember { FocusRequester() }
    val list = remember(items) {
        items.orEmpty().mapNotNull { li ->
            when {
                li.movie != null -> li.movie.ids.imdb?.let { com.lekathedon.visionlite.addons.MetaPreview(it, "movie", li.movie.title, posterOf(it), backdropOf(it)) }
                li.show != null -> li.show.ids.imdb?.let { com.lekathedon.visionlite.addons.MetaPreview(it, "series", li.show.title, posterOf(it), backdropOf(it)) }
                else -> null
            }
        }.filter { com.lekathedon.visionlite.anime.Hubs.keepsId(hub, it.id) }
    }
    val savedMetas = remember(saved) { saved.map { sv -> com.lekathedon.visionlite.addons.MetaPreview(sv.id, sv.type, sv.name,
        sv.poster ?: if (sv.id.startsWith("tt")) posterOf(sv.id) else null, if (sv.id.startsWith("tt")) backdropOf(sv.id) else null) } }
    var focused by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(items) { if (!focused && (saved.isNotEmpty() || list.isNotEmpty())) { androidx.compose.runtime.withFrameNanos { }; if (runCatching { first.requestFocus() }.isSuccess) focused = true } }
    val open: (com.lekathedon.visionlite.addons.MetaPreview) -> Unit = { m -> nav.push(Screen.Detail(m.type, m.id, m.name)) }
    val movies = list.filter { it.type == "movie" }
    val shows = list.filter { it.type != "movie" }
    LazyColumn(Modifier.fillMaxSize().background(LocalLook.current.page), contentPadding = PaddingValues(start = 40.dp, end = 24.dp, top = 24.dp, bottom = 60.dp)) {
        item(key = "head") {
            Text(when {
                trakt.connected.value == null -> "Library · connect Trakt in Settings for your watchlist"
                items == null -> "Library"
                list.isEmpty() && saved.isEmpty() -> "Library is empty"
                else -> "Library"
            }, color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        if (saved.isNotEmpty()) item(key = "saved") {
            com.lekathedon.visionlite.home.PageRow("lib:saved", "Saved · ${saved.size}", savedMetas, firstFocus = first, onClick = open)
        }
        item(key = "movies") {
            com.lekathedon.visionlite.home.PageRow("lib:movies", "Watchlist · Movies · ${movies.size}", movies, loading = items == null && trakt.connected.value != null,
                firstFocus = if (saved.isEmpty()) first else null, onClick = open)
        }
        item(key = "shows") {
            com.lekathedon.visionlite.home.PageRow("lib:shows", "Watchlist · Shows · ${shows.size}", shows,
                firstFocus = if (saved.isEmpty() && movies.isEmpty()) first else null, onClick = open)
        }
    }
}
