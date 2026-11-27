package com.lekathedon.visionlite.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
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

/**
 * The quick start (user 2026-11-26: "don't ask about everything; people don't want to look at so many genres and
 * people"): broad kinds grouped in a few big tiles (Action & Adventure, Crime & Mystery…), then a few favourite titles,
 * both optional. Each tile likes the broad genres it groups, so the engine finds your subgenres by itself from what
 * you watch. "Tell it more" opens the detailed questions (every subgenre, people, anime titles) for those who want them.
 */
private class Kind(val name: String, val about: String, val ids: List<String>)
private fun imdb(name: String, about: String, vararg n: Int) = Kind(name, about, n.map { Interests.id(it) })
private val FilmKinds = listOf(
    imdb("Action & Adventure", "Heists, quests, superheroes", 1, 12), imdb("Comedy", "Sitcoms, rom-coms, satire", 34),
    imdb("Crime & Mystery", "Detectives, gangsters, whodunnits", 52, 139), imdb("Thrillers", "Spies, conspiracies, suspense", 186),
    imdb("Drama", "Characters and stories", 76), imdb("Sci-Fi & Fantasy", "Space, time travel, magic", 162, 98),
    imdb("Horror", "Ghosts, monsters, slashers", 112), imdb("Romance", "Love stories", 152),
    imdb("History & War", "Epics, period pieces", 80, 10), imdb("Family & Animation", "For everyone", 93, 26),
    imdb("Documentaries", "True stories, nature, crime", 60), imdb("Reality & Game shows", "Competitions, real lives", 148, 105),
    imdb("Westerns", "Frontier stories", 191), imdb("Sport", "On and off the field", 174)
)
private val AnimeKinds = listOf(
    Kind("Action & Adventure", "Shounen battles, journeys", listOf("Action", "Adventure")), Kind("Comedy & Slice of life", "Everyday, light", listOf("Comedy", "Slice of Life")),
    Kind("Romance & Drama", "Feelings first", listOf("Romance", "Drama")), Kind("Fantasy & Isekai", "Other worlds", listOf("Fantasy", "Isekai")),
    Kind("Sci-Fi & Mecha", "Robots, space, the future", listOf("Sci-Fi", "Mecha")), Kind("Mystery & Psychological", "Mind games", listOf("Mystery", "Psychological")),
    Kind("Horror & Supernatural", "Spirits, curses", listOf("Horror", "Supernatural")), Kind("Sports", "Teams and rivals", listOf("Sports"))
).map { k -> Kind(k.name, k.about, k.ids.map { MalGenres.id(it) }) }

@Composable
fun TasteStart(onDone: () -> Unit) {
    var more by remember { mutableStateOf(false) }
    if (more) TasteDetailed(onDone) else TasteQuick(onDone) { more = true }
}

@Composable
private fun TasteQuick(onDone: () -> Unit, onMore: () -> Unit) {
    val animeOff = remember { Hubs.off() }
    var at by remember { mutableStateOf(0) }   // 0 = kinds, 1 = favourites, 2 = all set
    val picked = remember { mutableStateListOf<String>() }
    fun build() { picked.distinct().forEach { Recs.rebuildNow(if (it == Hub.ANIME.key) Hub.ANIME else Hub.FILMS) }; picked.clear() }
    fun finish() { App.instance.prefs.tasteStartDone.value = true; build(); onDone() }
    val first = remember(at) { FocusRequester() }
    LaunchedEffect(at) { androidx.compose.runtime.withFrameNanos { }; runCatching { first.requestFocus() } }
    androidx.activity.compose.BackHandler(enabled = at > 0) { at-- }
    LazyColumn(Modifier.fillMaxSize().background(LocalLook.current.page), contentPadding = PaddingValues(horizontal = 56.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "head$at") {
            com.lekathedon.visionlite.ui.Eyebrow(if (at < 2) "Make it yours · ${at + 1} of 2" else "Make it yours")
            com.lekathedon.visionlite.ui.DisplayText(when (at) { 0 -> "What do you like to watch?"; 1 -> "A few favourites?"; else -> "All set" }, 28.sp, Modifier.padding(top = 4.dp))
            Text(when (at) {
                0 -> "Pick a few. The app learns the finer kinds from what you watch."
                1 -> "Optional: 3 to 5 films or series you love. Skip if you like."
                else -> "Home is built around what you picked, and keeps learning as you watch. Change it any time in Settings › Home and look."
            }, color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
        }
        when (at) {
            0 -> {
                item(key = "films") { KindGrid(if (animeOff) null else "Films & series", FilmKinds, first) { picked += Hub.FILMS.key } }
                if (!animeOff) item(key = "anime") { KindGrid("Anime", AnimeKinds, null) { picked += Hub.ANIME.key } }
            }
            1 -> item(key = "titles") { TitleStep(false, first) { picked += Hub.FILMS.key } }
            else -> {}
        }
        item(key = "buttons$at") {
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (at) {
                    0 -> { com.lekathedon.visionlite.ui.KitButton("Next", null, { at = 1 }, primary = true); Button("Skip", { finish() }) }
                    1 -> { com.lekathedon.visionlite.ui.KitButton("Next", null, { at = 2 }, primary = true); Button("Skip", { at = 2 }) }
                    else -> {
                        com.lekathedon.visionlite.ui.KitButton("Go to Home", null, { finish() }, Modifier.focusRequester(first), primary = true)
                        Button("Tell it more (subgenres, actors, anime titles)", { build(); onMore() })
                    }
                }
            }
        }
    }
}

/** Big grouped tiles, five to a line; a tile is on when every genre it groups is liked. */
@Composable
private fun KindGrid(title: String?, kinds: List<Kind>, first: FocusRequester?, onPick: () -> Unit) {
    val tick by Interests.changed.collectAsState()
    val liked = remember(tick) { Interests.liked() }
    val K = com.lekathedon.visionlite.ui.Kit
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        title?.let { com.lekathedon.visionlite.ui.Eyebrow(it, Modifier.padding(top = 8.dp)) }
        kinds.chunked(5).forEachIndexed { r, line ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                line.forEachIndexed { c, k ->
                    val on = k.ids.all { it in liked }
                    val shape = androidx.compose.foundation.shape.RoundedCornerShape(K.rowR)
                    com.lekathedon.visionlite.ui.Focusable({
                        if (on) k.ids.filter { it in liked }.forEach(Interests::toggle) else { k.ids.filter { it !in liked }.forEach(Interests::toggle); onPick() }
                    }, (if (first != null && r == 0 && c == 0) Modifier.focusRequester(first) else Modifier).width(176.dp), shape = shape) { f ->
                        val (bg, ink) = when { f -> com.lekathedon.visionlite.ui.kitFocus; on -> K.lerp(K.card, K.accent, 0.3f) to Palette.Text; else -> K.rowFill to Palette.Text }
                        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().background(bg)
                            .then(if (on && !f) Modifier.border(1.5.dp, K.accent, shape) else Modifier.border(1.dp, K.line, shape))
                            .padding(horizontal = 14.dp, vertical = 11.dp)) {
                            Text((if (on) "✓ " else "") + k.name, color = ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(k.about, color = ink.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** The detailed questions (the first-start set before 0.41.7): every genre and subgenre, titles, people, anime. */
@Composable
private fun TasteDetailed(onDone: () -> Unit) {
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
                com.lekathedon.visionlite.ui.Eyebrow("Tell it more · ${at + 1} of ${steps.size}")
                com.lekathedon.visionlite.ui.DisplayText(step.title, 26.sp, Modifier.padding(top = 4.dp))
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
        modifier = Modifier.fillMaxWidth(0.6f).focusRequester(first).clip(androidx.compose.foundation.shape.RoundedCornerShape(com.lekathedon.visionlite.ui.Kit.rowR))
            .background(com.lekathedon.visionlite.ui.Kit.rowFill).border(1.dp, com.lekathedon.visionlite.ui.Kit.line, androidx.compose.foundation.shape.RoundedCornerShape(com.lekathedon.visionlite.ui.Kit.rowR)).padding(14.dp),
        decorationBox = { inner -> if (q.isEmpty()) Text(hint, color = Palette.TextDim, fontSize = 18.sp); inner() })
}
