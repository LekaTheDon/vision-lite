package com.lekathedon.visionlite.tb

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.ui.Focusable
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.Screen
import com.lekathedon.visionlite.ui.Text
import kotlinx.coroutines.launch

/**
 * ArabP2P browse (user 2026-11-26, a try-out, off by default: Settings › Vision TB › ArabP2P browse page). The site's
 * own categories along the top, newest releases below (50 a page, "More" loads the next). OK on a release finds its
 * IMDb title (Latin name + year, else TMDB's Arabic search) and opens its page, where Vision TB finds the ArabP2P link.
 * Cost: one site page per category / "More"; one IMDb (or TMDB) lookup per release pressed. Nothing runs when off.
 */
@Composable
fun ArabBrowseScreen(nav: Nav) {
    val scope = rememberCoroutineScope()
    var cats by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var cat by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableIntStateOf(1) }
    var rows by remember { mutableStateOf<List<ArabP2P.Release>?>(null) }
    var more by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        cats = ArabP2P.categories()
        if (cats.isNullOrEmpty()) note = "ArabP2P didn't answer (signed out, or the site is down)" else cat = cats!!.first().first
    }
    LaunchedEffect(cat, page) {
        val c = cat ?: return@LaunchedEffect
        if (page == 1) rows = null
        val got = ArabP2P.browse(c, page)
        if (got == null) { note = "Couldn't read this category"; more = false; return@LaunchedEffect }
        val fresh = if (page == 1) got else got.filter { r -> rows.orEmpty().none { it.name == r.name } }
        more = fresh.isNotEmpty()
        rows = (if (page == 1) emptyList() else rows.orEmpty()) + fresh
    }
    fun open(r: ArabP2P.Release) = scope.launch {
        note = "Finding \"${r.name.take(50)}\"…"
        val hit = ArabP2P.imdbOf(r.name)
        if (hit == null) note = "No IMDb match for this one" else { note = ""; nav.push(Screen.Detail(hit.second, hit.first, r.name)) }
    }
    fun size(b: Long?) = b?.let { if (it >= 1L shl 30) "%.1f GB".format(it / 1073741824.0) else "%d MB".format(it shr 20) }
    Column(Modifier.fillMaxSize().background(LocalLook.current.page).padding(start = 48.dp, end = 40.dp, top = 28.dp)) {
        Text("ArabP2P", color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Try-out: the site's newest releases by category. OK opens the title; Vision TB plays what TorBox has cached.",
            color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        cats?.takeIf { it.isNotEmpty() }?.let { list ->
            LazyRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.first }) { (id, name) ->
                    Focusable({ if (cat != id) { cat = id; page = 1; more = true } }, shape = RoundedCornerShape(50)) { f ->
                        Text(name, color = if (f) Color.Black else if (id == cat) Palette.Text else Palette.TextDim, fontSize = 15.sp,
                            fontWeight = if (id == cat) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
        }
        if (note.isNotEmpty()) Text(note, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        LazyColumn(Modifier.fillMaxSize().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
            val list = rows
            if (list == null) { if (cat != null) item { Text("Loading…", color = Palette.TextDim, fontSize = 14.sp) } }
            else {
                if (list.isEmpty()) item { Text("Nothing in this category.", color = Palette.TextDim, fontSize = 14.sp) }
                itemsIndexed(list, key = { i, r -> "$i${r.name}" }) { _, r ->
                    Focusable({ open(r) }, Modifier.fillMaxWidth(), RoundedCornerShape(10.dp)) { f ->
                        Column(Modifier.fillMaxWidth().background(if (f) Palette.Text else Palette.Card).padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(r.name, color = if (f) Color.Black else Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = listOfNotNull(size(r.bytes), r.seeds?.let { "$it seeders" }).joinToString(" · ")
                            if (sub.isNotEmpty()) Text(sub, color = if (f) Color.DarkGray else Palette.TextDim, fontSize = 13.sp)
                        }
                    }
                }
                if (more && list.isNotEmpty()) item {
                    Focusable({ page++ }, Modifier.fillMaxWidth(), RoundedCornerShape(10.dp)) { f ->
                        Text("More", color = if (f) Color.Black else Palette.Text, fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth().background(if (f) Palette.Text else Palette.Card).padding(horizontal = 16.dp, vertical = 10.dp))
                    }
                }
            }
        }
    }
}
