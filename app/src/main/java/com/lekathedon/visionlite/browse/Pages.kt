// Vision's Nuvio pages, the lite way: Discover (your add-ons' catalogs), Genres and Decades (Cinemeta's genre and
// year catalogs, no key), Scout (the recommendation engine) and Calendar (Trakt). One poster card, one rail, one grid.
package com.lekathedon.visionlite.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.addons.Addon
import com.lekathedon.visionlite.addons.CatalogDef
import com.lekathedon.visionlite.addons.Manifest
import com.lekathedon.visionlite.addons.MetaPreview
import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.library.posterOf
import com.lekathedon.visionlite.recs.Recs
import com.lekathedon.visionlite.ui.Button
import com.lekathedon.visionlite.ui.Focusable
import com.lekathedon.visionlite.ui.Img
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

// ---------------------------------------------------------------- shared parts

/** A poster in Echo's look: OK opens it, hold OK (when given) does [onHold]. [expand] = opens out when focused (rows). */
@Composable
fun Poster(m: MetaPreview, nav: Nav, modifier: Modifier = Modifier, sub: String? = null, onHold: (() -> Unit)? = null, expand: Boolean = true) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // AniList / TMDB cards open through their IMDb id.
    com.lekathedon.visionlite.home.EchoPoster(m, modifier, expand, sub, onHold) {
        if (m.id.startsWith("tt") || m.id.startsWith("kitsu:")) nav.push(Screen.Detail(m.type, m.id, m.name))
        else scope.launch { com.lekathedon.visionlite.home.openTitle(nav, m) }
    }
}

/** The titles of [metas] that belong to the hub you're in (Films & TV or Anime); people and services stay. */
@Composable
fun hubOnly(metas: List<MetaPreview>): List<MetaPreview> {
    val hub by com.lekathedon.visionlite.anime.Hubs.content.collectAsState()
    com.lekathedon.visionlite.anime.AnimeIds.changed.collectAsState().value   // read, so a move redraws the rail
    return metas.filter { com.lekathedon.visionlite.anime.Hubs.keeps(hub, it) }
}

@Composable
fun Rail(title: String, metas: List<MetaPreview>, nav: Nav, subOf: (MetaPreview) -> String? = { null }, onHold: ((MetaPreview) -> Unit)? = null,
         firstFocus: FocusRequester? = null, onEnd: (() -> Unit)? = null) {
    val metas = hubOnly(metas)
    if (metas.isEmpty()) return
    com.lekathedon.visionlite.home.EchoRowTitle(title)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(metas, key = { _, m -> m.id }) { i, m ->
            if (onEnd != null && i >= metas.size - 6) LaunchedEffect(metas.size) { onEnd() }
            Poster(m, nav, if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier, subOf(m), onHold?.let { h -> { h(m) } })
        }
    }
}

/** A row of choices (Vision's pills): the picked one is filled. */
@Composable
fun Chips(options: List<Pair<String, String>>, selected: String?, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options, key = { it.first }) { (key, label) ->
            Focusable({ onPick(key) }, shape = RoundedCornerShape(50)) { f ->
                Text(label, color = if (f) Color.Black else Color.White, fontSize = 14.sp, fontWeight = if (key == selected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.background(if (f) Color.White else if (key == selected) Color(0x40FFFFFF) else Color(0x14FFFFFF)).padding(horizontal = 14.dp, vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun Page(title: String, note: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(LocalLook.current.page).padding(start = 40.dp, end = 24.dp, top = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            if (note != null) Text(note, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(start = 14.dp))
        }
        content()
    }
}

// ---------------------------------------------------------------- Discover

/** Discover: a type, one of your add-ons' catalogs for it, and a genre when the catalog has them; a grid that pages. */
@Composable
fun Discover(nav: Nav) {
    val hub by com.lekathedon.visionlite.anime.Hubs.content.collectAsState()
    androidx.compose.runtime.key(hub) { DiscoverOf(nav, hub) }
}

@Composable
private fun DiscoverOf(nav: Nav, hub: com.lekathedon.visionlite.anime.Hub) {
    val addons by App.instance.addons.addons.collectAsState()
    val all = remember(addons) {
        addons.flatMap { a -> a.manifest.catalogs.filter { c -> !c.needsSearch && c.extraRequired.all { it == "genre" } && c.extra.none { it.isRequired && it.name != "genre" } }.map { a to it } }
    }
    val types = remember(all) { all.map { it.second.type }.distinct().sortedBy { listOf("movie", "series").indexOf(it).let { i -> if (i < 0) 9 else i } } }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    val t = type ?: types.firstOrNull()
    val cats = all.filter { it.second.type == t }
    var catKey by rememberSaveable(t) { mutableStateOf<String?>(null) }
    val cat = cats.firstOrNull { key(it) == catKey } ?: cats.firstOrNull()
    val genres = cat?.second?.extra?.firstOrNull { it.name == "genre" }?.options.orEmpty()
    val genreRequired = cat?.second?.let { c -> c.extra.any { it.name == "genre" && it.isRequired } || "genre" in c.extraRequired } == true
    var genre by rememberSaveable(catKey, t) { mutableStateOf<String?>(null) }
    val g = genre ?: if (genreRequired) genres.firstOrNull() else null
    val items = remember(cat?.let(::key), g) { mutableStateListOf<MetaPreview>() }
    var more by remember(cat?.let(::key), g) { mutableStateOf(true) }
    var asked by remember(cat?.let(::key), g) { mutableStateOf(0) }
    var loading by remember(cat?.let(::key), g) { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun load() {
        val c = cat ?: return
        if (loading || !more) return
        loading = true
        scope.launch {
            // Only this hub's titles; a page with none of them asks the next (up to 4 in a row).
            for (tries in 0 until 4) {
                val page = runCatching { App.instance.client.catalog(c.first, c.second, skip = asked, genre = g) }.getOrDefault(emptyList())
                asked += page.size
                val fresh = page.filter { m -> items.none { it.id == m.id } }
                val kept = fresh.filter { com.lekathedon.visionlite.anime.Hubs.keeps(hub, it) }
                items.addAll(kept)
                more = fresh.isNotEmpty() && c.second.supportsSkip
                if (!more || kept.isNotEmpty()) break
            }
            loading = false
        }
    }
    LaunchedEffect(cat?.let(::key), g) { load() }
    Page("Discover", if (all.isEmpty()) "Your add-ons have no catalogs to browse." else null) {
        Chips(types.map { it to typeName(it) }, t, Modifier.padding(top = 12.dp)) { type = it }
        Chips(cats.map { key(it) to ((it.second.name ?: it.second.id) + " · " + it.first.manifest.name) }, cat?.let(::key), Modifier.padding(top = 8.dp)) { catKey = it }
        if (genres.isNotEmpty()) Chips((if (genreRequired) emptyList() else listOf("" to "All")) + genres.map { it to it }, g ?: "", Modifier.padding(top = 8.dp)) { genre = it.ifEmpty { null } }
        LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize().padding(top = 14.dp), contentPadding = PaddingValues(bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(items, key = { _, m -> m.id }) { i, m ->
                if (i >= items.size - 12) LaunchedEffect(items.size) { load() }
                Poster(m, nav, sub = listOfNotNull(m.releaseInfo, m.imdbRating?.let { "★ $it" }).joinToString(" · ").ifEmpty { null }, expand = false)
            }
            if (items.isEmpty() && !loading && cat != null) item(span = { GridItemSpan(maxLineSpan) }) { Text("Nothing here.", color = Palette.TextDim, fontSize = 15.sp) }
        }
    }
}

private fun key(p: Pair<Addon, CatalogDef>) = p.first.base + "|" + p.second.type + "|" + p.second.id
private fun typeName(t: String) = when (t) { "movie" -> "Movies"; "series" -> "Shows"; "tv" -> "TV"; "channel" -> "Channels"; else -> t.replaceFirstChar { it.uppercase() } }

// ---------------------------------------------------------------- Genres and Decades (Cinemeta, keyless)

private object Cinemeta {
    const val BASE = "https://v3-cinemeta.strem.io"
    @Volatile private var manifest: Manifest? = null
    suspend fun manifest(): Manifest? = manifest ?: runCatching { Net.getJson<Manifest>("$BASE/manifest.json", 12) }.getOrNull()?.also { manifest = it }
    suspend fun page(type: String, catalog: String, genre: String, skip: Int = 0): List<MetaPreview> {
        val m = manifest() ?: return emptyList()
        val def = m.catalogs.firstOrNull { it.type == type && it.id == catalog } ?: CatalogDef(type, catalog)
        return runCatching { App.instance.client.catalog(Addon(BASE, m), def, skip, genre = genre) }.getOrDefault(emptyList())
    }
    suspend fun genres(): List<String> = manifest()?.catalogs?.filter { it.id == "top" }?.flatMap { c -> c.extra.firstOrNull { it.name == "genre" }?.options.orEmpty() }
        ?.distinct().orEmpty()
}

private val media = listOf("movie" to "Movies", "series" to "Shows", "all" to "All")

/** A rail that loads itself the first time it's drawn, and pages as you reach its end. */
@Composable
private fun LoadingRail(title: String, nav: Nav, load: suspend (skip: Int) -> List<MetaPreview>) {
    val items = remember(title) { mutableStateListOf<MetaPreview>() }
    var busy by remember(title) { mutableStateOf(false) }
    var end by remember(title) { mutableStateOf(false) }
    /** Cards the source has given so far (before dropping repeats): the next page starts there. */
    var fetched by remember(title) { mutableStateOf(0) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun more() {
        if (busy || end) return
        busy = true
        scope.launch {
            for (tries in 0 until 3) {
                val got = load(fetched)
                fetched += got.size
                val p = got.filter { m -> items.none { it.id == m.id } }
                items.addAll(p); end = got.isEmpty()
                if (end || p.any { com.lekathedon.visionlite.anime.Hubs.keeps(com.lekathedon.visionlite.anime.Hubs.now(), it) }) break
            }
            busy = false
        }
    }
    LaunchedEffect(title) { more() }
    if (hubOnly(items).isEmpty()) { Text(title, color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 16.dp)); Box(Modifier.height(if (end) 0.dp else 195.dp)) }
    else Rail(title, items, nav, subOf = { m -> listOfNotNull(m.releaseInfo, m.imdbRating?.let { "★ $it" }).joinToString(" · ").ifEmpty { null } }, onEnd = { more() })
}

/** Genres: a genre on the left; its Popular and Top rated rails on the right, for movies, shows or both. */
@Composable
fun Genres(nav: Nav) {
    val hub by com.lekathedon.visionlite.anime.Hubs.content.collectAsState()
    val anime = hub == com.lekathedon.visionlite.anime.Hub.ANIME
    // Anime: AniList's genres and lists (all anime); Films & TV: Cinemeta's, without the anime in them.
    val genres by produceState(emptyList<String>(), anime) { value = if (anime) com.lekathedon.visionlite.anime.AniBrowse.genres else Cinemeta.genres().filter { it != "Anime" } }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    var kind by rememberSaveable { mutableStateOf("all") }
    val g = genre ?: genres.firstOrNull()
    Page("Genres", if (genres.isEmpty()) "Loading…" else null) {
        Chips(media, kind, Modifier.padding(top = 12.dp)) { kind = it }
        Row(Modifier.fillMaxSize().padding(top = 12.dp)) {
            LazyColumn(Modifier.width(200.dp).fillMaxHeight(), contentPadding = PaddingValues(bottom = 30.dp)) {
                items(genres) { x -> SideItem(x, x == g) { genre = x } }
            }
            if (g != null) LazyColumn(Modifier.weight(1f).padding(start = 18.dp), contentPadding = PaddingValues(bottom = 60.dp)) {
                val types = if (kind == "all") listOf("movie", "series") else listOf(kind)
                types.forEach { t ->
                    if (anime) {
                        item(key = "a|$g|$t|top") { LoadingRail("Popular ${typeName(t).lowercase()} · $g", nav) { skip -> com.lekathedon.visionlite.anime.AniBrowse.page(t, "POPULARITY_DESC", genre = g, skip = skip) } }
                        item(key = "a|$g|$t|rated") { LoadingRail("Top rated ${typeName(t).lowercase()} · $g", nav) { skip -> com.lekathedon.visionlite.anime.AniBrowse.page(t, "SCORE_DESC", genre = g, skip = skip) } }
                    } else {
                        item(key = "$g|$t|top") { LoadingRail("Popular ${typeName(t).lowercase()} · $g", nav) { skip -> Cinemeta.page(t, "top", g, skip) } }
                        item(key = "$g|$t|rated") { LoadingRail("Top rated ${typeName(t).lowercase()} · $g", nav) { skip -> Cinemeta.page(t, "imdbRating", g, skip) } }
                    }
                }
            }
        }
    }
}

/** Decades: a decade on the left; a rail per year of it (newest first), movies, shows or both. */
@Composable
fun Decades(nav: Nav) {
    val hub by com.lekathedon.visionlite.anime.Hubs.content.collectAsState()
    val anime = hub == com.lekathedon.visionlite.anime.Hub.ANIME
    val year = Calendar.getInstance().get(Calendar.YEAR)
    val decades = remember(anime) { (year / 10 * 10 downTo if (anime) 1960 else 1920 step 10).toList() }
    var decade by rememberSaveable { mutableStateOf(decades.first()) }
    var kind by rememberSaveable { mutableStateOf("movie") }
    Page("Decades") {
        Chips(media, kind, Modifier.padding(top = 12.dp)) { kind = it }
        Row(Modifier.fillMaxSize().padding(top = 12.dp)) {
            LazyColumn(Modifier.width(200.dp).fillMaxHeight()) { items(decades) { d -> SideItem("${d}s", d == decade) { decade = d } } }
            LazyColumn(Modifier.weight(1f).padding(start = 18.dp), contentPadding = PaddingValues(bottom = 60.dp)) {
                val types = if (kind == "all") listOf("movie", "series") else listOf(kind)
                (minOf(decade + 9, year) downTo decade).forEach { y ->
                    types.forEach { t -> item(key = "$anime|$y|$t") { LoadingRail("$y · ${typeName(t)}", nav) { skip ->
                        if (anime) com.lekathedon.visionlite.anime.AniBrowse.page(t, "POPULARITY_DESC", year = y, skip = skip) else Cinemeta.page(t, "year", y.toString(), skip) } } }
                }
            }
        }
    }
}

@Composable
private fun SideItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Focusable(onClick, Modifier.padding(bottom = 4.dp).fillMaxWidth()) { f ->
        Text(label, color = if (f) Color.Black else Palette.Text, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.fillMaxWidth().background(if (f) Color.White else if (selected) Color(0x33FFFFFF) else Color.Transparent).padding(10.dp))
    }
}

// ---------------------------------------------------------------- Scout

/** Scout: the engine's picks for you and its "because you watched" rows. Hold OK: Not for me. */
@Composable
fun Scout(nav: Nav) {
    val hub by com.lekathedon.visionlite.anime.Hubs.content.collectAsState()
    androidx.compose.runtime.key(hub) { ScoutOf(nav, hub) }
}

/** Scout for one hub: that hub's picks, moods, AI picks and "because you watched" rows. */
@Composable
private fun ScoutOf(nav: Nav, hub: com.lekathedon.visionlite.anime.Hub) {
    val pool by Recs.pool(hub).collectAsState()
    val building by Recs.building(hub).collectAsState()
    val changed by Recs.changed.collectAsState()
    LaunchedEffect(Unit) { runCatching { Recs.refreshIfDue(hub) } }
    val forYou = remember(pool, changed) { Recs.forYou(40, hub = hub) }
    val because = remember(pool, changed) { Recs.because(hub) }
    val first = remember { FocusRequester() }
    LaunchedEffect(forYou.isNotEmpty()) { if (forYou.isNotEmpty()) runCatching { first.requestFocus() } }
    fun meta(p: com.lekathedon.visionlite.recs.Pick) = MetaPreview(p.imdb, p.type, p.name, p.poster ?: posterOf(p.imdb), releaseInfo = p.year?.toString(),
        imdbRating = p.rating?.toString(), genres = p.genres, description = p.why)
    val note = when { building -> "Thinking…"; pool == null -> "Learns from Trakt and what you like."; else -> "Hold OK on a poster: not for me" }
    Page("Scout", note) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 60.dp)) {
            item {
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button("Refresh picks", { Recs.rebuildNow(hub) }) }
                if (forYou.isEmpty() && !building) Text("No picks yet. Connect Trakt (Settings › Integrations) or like a few titles, then Refresh.",
                    color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 16.dp))
            }
            item(key = "foryou") {
                Rail("For you", forYou.map(::meta), nav, subOf = { it.description }, firstFocus = first,
                    onHold = { m -> Recs.notForMe(m.id, m.type == "series", m.name) })
            }
            item(key = "moods") { MoodsRow(nav, pool, changed, hub) }
            item(key = "ai") { AiRail(nav, hub) }
            items(because, key = { it.seed }) { b ->
                Rail("Because you watched ${b.seed}", b.picks.map(::meta), nav, subOf = { it.description },
                    onHold = { m -> Recs.notForMe(m.id, m.type == "series", m.name) })
            }
        }
    }
}

/** Scout's moods: one chip each; the chosen mood shows the engine's finds for it, then more from TMDB. */
@Composable
private fun MoodsRow(nav: Nav, pool: com.lekathedon.visionlite.recs.Pool?, changed: Int, hub: com.lekathedon.visionlite.anime.Hub) {
    var mood by remember { mutableStateOf<com.lekathedon.visionlite.recs.Mood?>(null) }
    val moods = remember(pool, changed) { com.lekathedon.visionlite.recs.Moods.available(hub) }
    if (moods.isEmpty()) return
    com.lekathedon.visionlite.home.EchoRowTitle("Moods")
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
        items(moods) { m -> Button(if (m == mood) "● ${m.label}" else m.label, { mood = if (mood == m) null else m }) }
    }
    val m = mood ?: return
    val engine = remember(m, pool, changed) { com.lekathedon.visionlite.recs.Moods.fromEngine(m, hub) }
    val more by produceState(emptyList<MetaPreview>(), m, engine) {
        value = runCatching { com.lekathedon.visionlite.recs.Moods.discover(m, engine.map { it.name }.toSet(), hub) }.getOrDefault(emptyList())
    }
    Text(m.why, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    Rail("${m.label} · for you", engine.map { p -> MetaPreview(p.imdb, p.type, p.name, p.poster ?: posterOf(p.imdb), releaseInfo = p.year?.toString(),
        imdbRating = p.rating?.toString(), genres = p.genres, description = p.why) }, nav, subOf = { it.description },
        onHold = { x -> com.lekathedon.visionlite.recs.Recs.notForMe(x.id, x.type == "series", x.name) })
    Rail("${m.label} · more to discover", more, nav)
    if (engine.isEmpty() && more.isEmpty()) Text("Nothing for this mood yet. Refresh picks, or add a TMDB key for more.", color = Palette.TextDim, fontSize = 14.sp)
}

// ---------------------------------------------------------------- Calendar

/** Calendar: the next two weeks of episodes (shows you watch) and films (Trakt), by day. */
@Composable
fun CalendarPage(nav: Nav) {
    val connected by App.instance.trakt.connected.collectAsState()
    val data by produceState<Pair<List<com.lekathedon.visionlite.trakt.CalShow>, List<com.lekathedon.visionlite.trakt.CalMovie>>?>(null, connected) {
        value = withContext(Dispatchers.IO) { App.instance.trakt.calendar(14) }
    }
    data class Line(val day: Long, val at: String, val meta: MetaPreview, val sub: String)
    val lines = remember(data) {
        val (shows, movies) = data ?: return@remember emptyList()
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", java.util.Locale.US)
        val ymd = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val s = shows.mapNotNull { c ->
            val imdb = c.show.ids.imdb ?: return@mapNotNull null
            val t = runCatching { iso.parse(c.firstAired)!!.time }.getOrNull() ?: return@mapNotNull null
            Line(dayOf(t), hm.format(java.util.Date(t)), MetaPreview(imdb, "series", c.show.title), "S${c.episode.season}E${c.episode.number}" + (c.episode.title?.let { " · $it" } ?: ""))
        }
        val m = movies.mapNotNull { c ->
            val imdb = c.movie.ids.imdb ?: return@mapNotNull null
            val t = runCatching { ymd.parse(c.released)!!.time }.getOrNull() ?: return@mapNotNull null
            Line(dayOf(t), "", MetaPreview(imdb, "movie", c.movie.title, releaseInfo = c.movie.year?.toString()), "Film release")
        }
        (s + m).sortedWith(compareBy({ it.day }, { it.at }))
    }.let { all -> hubOnly(all.map { it.meta }).map { it.id }.toSet().let { keep -> all.filter { it.meta.id in keep } } }
    val days = lines.groupBy { it.day }
    val dayFmt = java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.getDefault())
    val today = dayOf(System.currentTimeMillis())
    Page("Calendar", when { connected == null -> "Connect Trakt in Settings › Integrations to see what's coming."; data == null -> "Loading…"; lines.isEmpty() -> "Nothing in the next two weeks."; else -> "Next 14 days" }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 60.dp)) {
            days.forEach { (d, list) ->
                item(key = d) {
                    val label = when (d) { today -> "Today"; today + 1 -> "Tomorrow"; else -> dayFmt.format(java.util.Date(dayMs(d))) }
                    Rail(label, list.map { it.meta.copy(description = (if (it.at.isNotEmpty()) it.at + " · " else "") + it.sub) }, nav, subOf = { it.name + "\n" + it.description })
                }
            }
        }
    }
}

private fun dayOf(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }.let { it.get(Calendar.YEAR) * 1000L + it.get(Calendar.DAY_OF_YEAR) }
private fun dayMs(d: Long) = Calendar.getInstance().apply { set(Calendar.YEAR, (d / 1000).toInt()); set(Calendar.DAY_OF_YEAR, (d % 1000).toInt()) }.timeInMillis

// ---------------------------------------------------------------- Streaming service / person pages (Vision rows)

/** A streaming service's page: its Originals, latest, popular, top rated and most rated (TMDB, your key). */
@Composable
fun ServicePage(nav: Nav, id: String) {
    val s = remember(id) { com.lekathedon.visionlite.home.Services.S.entries.firstOrNull { it.id == id } } ?: return
    val rows = remember(s) { com.lekathedon.visionlite.home.Services.rows(s) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val first = remember { FocusRequester() }
    Page(s.title, if (s.region == "GB") "UK" else "US") {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 60.dp)) {
            itemsIndexed(rows, key = { _, r -> r.first }) { i, (title, load) ->
                val items = remember(title) { androidx.compose.runtime.mutableStateListOf<MetaPreview>() }
                var page by remember(title) { mutableStateOf(0) }
                var busy by remember(title) { mutableStateOf(false) }
                fun next() { if (busy || page >= 5) return; busy = true; scope.launch { val more = load(page + 1); page++; items.addAll(more.filter { m -> items.none { it.id == m.id } }); busy = false } }
                LaunchedEffect(title) { next() }
                if (i == 0) LaunchedEffect(items.isNotEmpty()) { if (items.isNotEmpty()) runCatching { first.requestFocus() } }
                TmdbRail(title, items, nav, if (i == 0) first else null) { next() }
            }
        }
    }
}

/** Like [Rail], but cards with TMDB ids are turned into IMDb ids when opened. */
@Composable
private fun TmdbRail(title: String, all: List<MetaPreview>, nav: Nav, firstFocus: FocusRequester?, onEnd: () -> Unit) {
    val metas = hubOnly(all)
    // None of this hub's on the pages so far: ask the next ones.
    if (metas.isEmpty() && all.isNotEmpty()) LaunchedEffect(all.size) { onEnd() }
    if (metas.isEmpty()) return
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    com.lekathedon.visionlite.home.EchoRowTitle(title)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(metas, key = { i, m -> "$i:${m.id}" }) { i, m ->
            if (i >= metas.size - 6) LaunchedEffect(metas.size) { onEnd() }
            com.lekathedon.visionlite.home.EchoPoster(m, if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier,
                sub = listOfNotNull(m.releaseInfo, m.imdbRating?.let { "★ $it" }).joinToString(" · ").ifEmpty { null }) {
                scope.launch { com.lekathedon.visionlite.home.openTitle(nav, m) }
            }
        }
    }
}

/** A person's films and shows, newest first (IMDb); Follow puts them on Home's Favourite people. */
@Composable
fun PersonPage(nav: Nav, id: String, name: String) {
    val people = com.lekathedon.visionlite.home.People
    var data by remember(id) { mutableStateOf<Pair<com.lekathedon.visionlite.home.People.Person?, List<MetaPreview>>?>(null) }
    LaunchedEffect(id) { data = people.titles(id) }
    val list by people.list.collectAsState()
    // Follow puts them on this hub's Favourite people only (Anime: voice actors, directors you follow there).
    val hubKey = com.lekathedon.visionlite.anime.Hubs.content.collectAsState().value.key
    val following = list.any { it.id == id && it.hub == hubKey }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val d = data
    val mine = hubOnly(d?.second.orEmpty())
    Page(d?.first?.name?.ifBlank { null } ?: name, if (d == null) "Loading…" else "${mine.size} titles") {
        Row(Modifier.padding(top = 10.dp)) {
            Button(if (following) "Following ✓" else "Follow", {
                people.toggle(d?.first?.copy(name = d.first!!.name.ifBlank { name }, hub = hubKey) ?: com.lekathedon.visionlite.home.People.Person(id, name, hub = hubKey))
            }, Modifier.focusRequester(first))
        }
        val all = d?.second.orEmpty()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 60.dp)) {
            item { Rail("Movies", all.filter { it.type == "movie" }, nav, subOf = { listOfNotNull(it.releaseInfo, it.imdbRating?.let { r -> "★ $r" }).joinToString(" · ") }) }
            item { Rail("Series", all.filter { it.type == "series" }, nav, subOf = { listOfNotNull(it.releaseInfo, it.imdbRating?.let { r -> "★ $r" }).joinToString(" · ") }) }
        }
    }
}

/** Scout's AI Picks: the row, and how the AIs decide (Vote with weights, or one Boss). */
@Composable
private fun AiRail(nav: Nav, hub: com.lekathedon.visionlite.anime.Hub) {
    val ai = com.lekathedon.visionlite.recs.AiPicks
    val picks by ai.picks(hub).collectAsState()
    val status by ai.status(hub).collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) { runCatching { ai.refreshIfDue(hub) } }
    var tick by remember { mutableStateOf(0) }
    val on = remember(tick) { ai.configured() }
    val boss = remember(tick) { ai.boss() }
    Text("AI Picks" + (status?.let { " · $it" } ?: ""), color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp))
    if (on.isNotEmpty()) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button("New AI picks", { scope.launch { ai.refreshIfDue(hub, force = true) } })
        Button(if (boss == null) "Mode: Vote" else "Mode: Boss = ${boss.label}", {
            val order = listOf<com.lekathedon.visionlite.recs.AiPicks.Ai?>(null) + on
            ai.setBoss(order[(order.indexOf(boss) + 1) % order.size]); tick++
        })
        if (boss == null) on.forEach { a ->
            val w = ai.weight(a)
            Button("${a.label} ×${if (w % 1.0 == 0.0) w.toInt().toString() else w.toString()}", {
                val ws = ai.WEIGHTS; ai.setWeight(a, ws[(ws.indexOf(w).coerceAtLeast(0) + 1) % ws.size]); tick++
            })
        }
    }
    fun meta(p: com.lekathedon.visionlite.recs.Pick) = MetaPreview(p.imdb, p.type, p.name, p.poster ?: posterOf(p.imdb), releaseInfo = p.year?.toString(),
        imdbRating = p.rating?.toString(), genres = p.genres, description = p.why)
    Rail("", picks.map(::meta), nav, subOf = { it.description }, onHold = { m -> Recs.notForMe(m.id, m.type == "series", m.name); ai.remove(m.id, hub) })
}
