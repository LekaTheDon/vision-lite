package com.lekathedon.visionlite.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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

/** Library = the Trakt watchlist (newest first). */
@Composable
fun Library(nav: Nav) {
    val trakt = App.instance.trakt
    var items by remember { mutableStateOf<List<ListItem>?>(null) }
    LaunchedEffect(Unit) { items = trakt.watchlist() }
    val first = remember { FocusRequester() }
    LaunchedEffect(items) { if (!items.isNullOrEmpty()) runCatching { first.requestFocus() } }
    LazyVerticalGrid(GridCells.Adaptive(132.dp), Modifier.fillMaxSize().background(LocalLook.current.page), contentPadding = PaddingValues(48.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(when {
                trakt.connected.value == null -> "Library: connect Trakt in Settings"
                items == null -> "Library"
                items!!.isEmpty() -> "Library: your Trakt watchlist is empty"
                else -> "Library · ${items!!.size}"
            }, color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        }
        val list = items.orEmpty().mapNotNull { li ->
            when {
                li.movie != null -> li.movie.ids.imdb?.let { Triple("movie", it, li.movie.title) }
                li.show != null -> li.show.ids.imdb?.let { Triple("series", it, li.show.title) }
                else -> null
            }
        }
        items(list, key = { it.second }) { (type, imdb, title) ->
            val mod = if (imdb == list.first().second) Modifier.focusRequester(first) else Modifier
            Focusable({ nav.push(Screen.Detail(type, imdb, title)) }, mod.size(120.dp, 180.dp)) {
                Box(Modifier.fillMaxSize().background(Palette.Card)) {
                    Text(title, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                    Img(posterOf(imdb), 120.dp, 180.dp, Modifier.fillMaxSize())
                }
            }
        }
    }
}
