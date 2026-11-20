package com.lekathedon.visionlite.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.addons.MetaPreview
import com.lekathedon.visionlite.ui.Focusable
import com.lekathedon.visionlite.ui.Img
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.Screen
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Suggestions while typing (IMDb feed); every add-on catalog that takes a search term is asked when Search is pressed. */
@Composable
fun Search(nav: Nav) {
    val app = App.instance
    var q by remember { mutableStateOf("") }
    val results = remember { mutableStateListOf<Pair<String, List<MetaPreview>>>() }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    val prefs = app.prefs
    var recent by remember { mutableStateOf(prefs.recentSearches.value.split('\n').filter { it.isNotEmpty() }) }
    fun run() {
        job?.cancel(); results.clear()
        val term = q.trim().ifEmpty { return }
        recent = (listOf(term) + recent.filterNot { it.equals(term, true) }).take(8)
        prefs.recentSearches.value = recent.joinToString("\n")
        job = scope.launch {
            app.addons.addons.value.flatMap { a -> a.manifest.catalogs.filter { it.supportsSearch }.map { a to it } }
                .map { (a, c) -> async { searchRowName(c.name, c.type, a.manifest.name) to runCatching { app.client.catalog(a, c, search = term) }.getOrDefault(emptyList()) } }
                .forEach { d -> d.await().takeIf { it.second.isNotEmpty() }?.let { results.add(it) } }
        }
    }
    // Suggestions as you type: 0.25 s after typing stops, IMDb's feed (see Suggest). Also sent to the keyboard's
    // suggestion bar; picking one there fills the field with that title, which searches it at once.
    var hints by remember { mutableStateOf<List<MetaPreview>>(emptyList()) }
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(q) {
        if (q.trim().length < 2) { hints = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(250)
        hints = Suggest.of(q)
    }
    LaunchedEffect(hints) {
        val imm = view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        runCatching { imm?.displayCompletions(view, hints.mapIndexed { i, m -> android.view.inputmethod.CompletionInfo(i.toLong(), i, m.name) }.toTypedArray()) }
    }
    LazyColumn(Modifier.fillMaxSize().background(LocalLook.current.page), contentPadding = PaddingValues(48.dp)) {
        item {
            BasicTextField(q, { n ->
                    val picked = n.length - q.length > 1 && hints.any { it.name.equals(n.trim(), true) }
                    q = n; if (picked) run() }, singleLine = true,
                textStyle = TextStyle(color = Palette.Text, fontSize = 18.sp), cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { run() }),
                modifier = Modifier.fillMaxWidth(0.6f).focusRequester(field).background(Palette.Card).padding(14.dp),
                decorationBox = { inner -> if (q.isEmpty()) Text(if (com.lekathedon.visionlite.anime.Hubs.now() == com.lekathedon.visionlite.anime.Hub.ANIME) "Search anime" else "Search movies and series",
                    color = Palette.TextDim, fontSize = 18.sp); inner() })
        }
        if (results.isEmpty() && hints.isNotEmpty() && q.trim().length >= 2) item {
            val hints = com.lekathedon.visionlite.browse.hubOnly(hints)
            Text("Suggestions", color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(hints, key = { i, m -> "$i:${m.id}" }) { _, m ->
                    com.lekathedon.visionlite.home.EchoPoster(m, sub = m.releaseInfo) { nav.push(Screen.Detail(m.type, m.id, m.name)) }
                }
            }
        }
        if (results.isEmpty() && recent.isNotEmpty() && hints.isEmpty()) item {
            Text("Recent", color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recent) { t -> com.lekathedon.visionlite.ui.Button(t, { q = t; run() }) }
                item { com.lekathedon.visionlite.ui.Button("Clear", { recent = emptyList(); prefs.recentSearches.value = "" }) }
            }
        }
        // Index in the key: two add-ons (or one add-on's film and series catalogs) can give the same row name.
        itemsIndexed(results, key = { i, r -> "$i:${r.first}" }) { _, (title, all) ->
            // Only the hub you're in: Anime finds anime, Films & TV everything else.
            val metas = com.lekathedon.visionlite.browse.hubOnly(all)
            if (metas.isEmpty()) return@itemsIndexed
            com.lekathedon.visionlite.home.EchoRowTitle(title)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(metas.distinctBy { it.type + it.id }, key = { i, m -> "$i:${m.type}:${m.id}" }) { _, m ->
                    com.lekathedon.visionlite.home.EchoPoster(m) { nav.push(Screen.Detail(m.type, m.id, m.name)) }
                }
            }
        }
    }
}

/** "Popular films · Cinemeta" / "Popular series · Cinemeta", so a film and a series catalog with one name read apart. */
private fun searchRowName(name: String?, type: String, addon: String): String {
    val kind = when (type) { "movie" -> "films"; "series" -> "series"; else -> type }
    val n = name?.takeIf { it.isNotBlank() } ?: return "${kind.replaceFirstChar { it.uppercase() }} · $addon"
    return if (n.contains(kind, true) || n.contains(type, true)) "$n · $addon" else "$n $kind · $addon"
}
