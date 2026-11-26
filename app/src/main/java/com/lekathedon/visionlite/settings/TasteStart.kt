package com.lekathedon.visionlite.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.lekathedon.visionlite.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.lekathedon.visionlite.anime.AnimeIds
import com.lekathedon.visionlite.anime.Hub
import com.lekathedon.visionlite.anime.Hubs
import com.lekathedon.visionlite.anime.MalGenres
import com.lekathedon.visionlite.home.People
import com.lekathedon.visionlite.recs.Interests
import com.lekathedon.visionlite.recs.Recs
import com.lekathedon.visionlite.search.Suggest
import com.lekathedon.visionlite.ui.Button
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Palette

/**
 * First start (user 2026-11-25): a new person with no Trakt history gets a few questions so Home is theirs from day
 * one: titles they love, genres, people, anime titles, anime genres. Any number of each (none is fine); Skip this /
 * Skip all everywhere. Answers go where the app's own buttons put them: titles = Love it, genres = Your interests,
 * people = Follow (Films & TV). Anime's two steps are left out when Settings › Anime is Off.
 * Cost: one small IMDb suggestion request per pause in typing; one Picks build per hub at the end (when anything was picked).
 */
object TasteStartGate {
    /** Shown once, to someone the app knows nothing about yet; anyone with Likes, follows or interests is past it. */
    fun wanted(): Boolean {
        val p = App.instance.prefs
        if (p.tasteStartDone.value) return false
        val knows = Recs.feedback().isNotEmpty() || People.list.value.isNotEmpty() || Interests.liked().isNotEmpty()
        if (knows) p.tasteStartDone.value = true
        return !knows
    }
}

private enum class Step(val title: String, val about: String, val anime: Boolean) {
    TITLES("Films and series you love", "Type a title and pick it. Pick different kinds: Dune and Dune: Part Two teach the app the same thing, so one of them is enough. As many as you like; about 10 is plenty.", false),
    GENRES("Genres you like", "Pick any. Each one you like gets its own \"Because you like …\" row.", false),
    PEOPLE("Actors and directors", "Type a name and pick the person. As many as you like.", false),
    ANIME_TITLES("Anime you love", "Type an anime and pick it. Different kinds teach more than one series' seasons.", true),
    ANIME_GENRES("Anime genres", "Pick any (MyAnimeList's genres, themes and audiences).", true)
}

@Composable
fun TasteStart(onDone: () -> Unit) {
    val animeOff = remember { Hubs.off() }
    val steps = remember { Step.entries.filter { !it.anime || !animeOff } }
    var at by remember { mutableStateOf(0) }
    val picked = remember { mutableStateListOf<String>() }   // hubs that got anything, for the Picks builds at the end
    fun finish() {
        App.instance.prefs.tasteStartDone.value = true
        picked.distinct().forEach { Recs.rebuildNow(if (it == Hub.ANIME.key) Hub.ANIME else Hub.FILMS) }
        onDone()
    }
    fun next() { if (at >= steps.size - 1) finish() else at++ }
    val step = steps[at]
    key(step) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; runCatching { first.requestFocus() } }
        LazyColumn(Modifier.fillMaxSize().background(LocalLook.current.page), contentPadding = PaddingValues(48.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Step ${at + 1} of ${steps.size}", color = Palette.TextDim, fontSize = 14.sp)
                Text(step.title, color = Palette.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(step.about, color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
            }
            when (step) {
                Step.TITLES, Step.ANIME_TITLES -> item { TitleStep(step == Step.ANIME_TITLES, first) { picked += if (step.anime) Hub.ANIME.key else Hub.FILMS.key } }
                Step.PEOPLE -> item { PeopleStep(first) { picked += Hub.FILMS.key } }
                Step.GENRES -> {
                    val groups = Interests.GROUPS.map { (g, r) -> g to Interests.inGroup(r) }.filter { it.second.isNotEmpty() }
                    groups.forEachIndexed { gi, (g, ids) -> item(key = "g:$g") { GenreRow(g, ids, if (gi == 0) first else null) { picked += Hub.FILMS.key } } }
                }
                Step.ANIME_GENRES -> MalGenres.GROUPS.forEachIndexed { gi, (g, ids) -> item(key = "m:$g") { GenreRow(g, ids, if (gi == 0) first else null) { picked += Hub.ANIME.key } } }
            }
            item {
                Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(if (at >= steps.size - 1) "Done" else "Next", { next() })
                    Button("Skip this", { next() })
                    Button("Skip all", { finish() })
                }
            }
        }
    }
}

/** "dune: part two" / "dune" → "dune": two titles of one series or franchise teach the app the same thing. */
private fun franchise(name: String): String = name.lowercase().substringBefore(':').substringBefore(" - ")
    .replace(Regex("\\b(part|chapter|vol\\.?|volume|season)\\s+\\w+"), "").replace(Regex("\\b(\\d+|ii|iii|iv|v|vi)\\b"), "")
    .replace(Regex("[^a-z0-9 ]"), "").trim().replace(Regex("\\s+"), " ")

@Composable
private fun TitleStep(anime: Boolean, first: FocusRequester, onPick: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<MetaPreview>>(emptyList()) }
    val chosen = remember { mutableStateListOf<MetaPreview>() }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(q) {
        if (q.trim().length < 2) { hits = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(250)
        hits = Suggest.of(q).filter { AnimeIds.isAnime(it.id) == anime }
    }
    Field(q, { q = it }, if (anime) "Type an anime" else "Type a film or series", first)
    if (hits.isNotEmpty()) com.lekathedon.visionlite.home.PageRow("taste:hits:$anime", "", hits,
        sub = { m -> if (chosen.any { it.id == m.id }) "✓ Picked" else m.releaseInfo }, onHoldIn = { }) { m ->
        val had = chosen.firstOrNull { it.id == m.id }
        if (had != null) { chosen.remove(had); Recs.forget(m.id); note = null; return@PageRow }
        val same = chosen.firstOrNull { franchise(it.name).isNotEmpty() && franchise(it.name) == franchise(m.name) }
        note = same?.let { "\"${m.name}\" is like \"${it.name}\": both teach the app the same thing. A different kind of title teaches more." }
        chosen += m; Recs.love(m.id, m.type == "series", m.name); onPick()
    }
    note?.let { Text(it, color = Color(0xFFEAB308), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
    if (chosen.isNotEmpty()) {
        Text("Picked: ${chosen.size} · OK on one removes it", color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(chosen.toList(), key = { it.id }) { m -> Button("✓ ${m.name}" + (m.releaseInfo?.let { " ($it)" } ?: ""), { chosen.remove(m); Recs.forget(m.id) }) }
        }
    }
}

@Composable
private fun PeopleStep(first: FocusRequester, onPick: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Pair<People.Person, String>>>(emptyList()) }
    val list by People.list.collectAsState()
    LaunchedEffect(q) {
        if (q.trim().length < 2) { hits = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(300)
        hits = People.search(q)
    }
    Field(q, { q = it }, "Type an actor's or director's name", first)
    if (hits.isNotEmpty()) LazyRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(hits, key = { it.first.id }) { (p, known) ->
            val on = list.any { it.id == p.id && it.hub == Hub.FILMS.key }
            Button((if (on) "✓ " else "") + p.name + (known.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), { People.toggle(p); if (!on) onPick() })
        }
    }
    val mine = list.filter { it.hub == Hub.FILMS.key }
    if (mine.isNotEmpty()) {
        Text("Following: ${mine.size} · OK on one removes it", color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(mine, key = { it.id }) { p -> Button("✓ ${p.name}", { People.toggle(p) }) }
        }
    }
}

@Composable
private fun GenreRow(group: String, ids: List<String>, first: FocusRequester?, onPick: () -> Unit) {
    val tick by Interests.changed.collectAsState()
    val liked = remember(tick) { Interests.liked() }
    Text(group, color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(ids, key = { it }) { id ->
            val on = id in liked
            Button((if (on) "✓ " else "") + (Interests.name(id) ?: id), { Interests.toggle(id); if (!on) onPick() },
                if (first != null && id == ids.first()) Modifier.focusRequester(first) else Modifier)
        }
    }
}

@Composable
private fun Field(q: String, onChange: (String) -> Unit, hint: String, first: FocusRequester) {
    BasicTextField(q, onChange, singleLine = true,
        textStyle = TextStyle(color = Palette.Text, fontSize = 18.sp), cursorBrush = SolidColor(Color.White),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { }),
        modifier = Modifier.fillMaxWidth(0.6f).focusRequester(first).background(Palette.Card).padding(14.dp),
        decorationBox = { inner -> if (q.isEmpty()) Text(hint, color = Palette.TextDim, fontSize = 18.sp); inner() })
}
