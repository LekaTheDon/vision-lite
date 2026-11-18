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

/** Search every add-on catalog that takes a search term; asked only when Done is pressed. */
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
                .map { (a, c) -> async { (c.name ?: c.type) + " · " + a.manifest.name to runCatching { app.client.catalog(a, c, search = term) }.getOrDefault(emptyList()) } }
                .forEach { d -> d.await().takeIf { it.second.isNotEmpty() }?.let { results.add(it) } }
        }
    }
    LazyColumn(Modifier.fillMaxSize().background(LocalLook.current.page), contentPadding = PaddingValues(48.dp)) {
        item {
            BasicTextField(q, { q = it }, singleLine = true,
                textStyle = TextStyle(color = Palette.Text, fontSize = 18.sp), cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { run() }),
                modifier = Modifier.fillMaxWidth(0.6f).focusRequester(field).background(Palette.Card).padding(14.dp),
                decorationBox = { inner -> if (q.isEmpty()) Text("Search movies and series", color = Palette.TextDim, fontSize = 18.sp); inner() })
        }
        if (results.isEmpty() && recent.isNotEmpty()) item {
            Text("Recent", color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recent) { t -> com.lekathedon.visionlite.ui.Button(t, { q = t; run() }) }
                item { com.lekathedon.visionlite.ui.Button("Clear", { recent = emptyList(); prefs.recentSearches.value = "" }) }
            }
        }
        items(results, key = { it.first }) { (title, metas) ->
            com.lekathedon.visionlite.home.EchoRowTitle(title)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(metas, key = { it.id }) { m ->
                    com.lekathedon.visionlite.home.EchoPoster(m) { nav.push(Screen.Detail(m.type, m.id, m.name)) }
                }
            }
        }
    }
}
