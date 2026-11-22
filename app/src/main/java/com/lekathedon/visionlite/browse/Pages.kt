// Vision's Nuvio pages, the lite way: Discover (your add-ons' catalogs), Genres and Decades (WuPlay's, from tiles on
// Home), Scout (the recommendation engine) and Calendar (Trakt). One poster card, one rail, one grid.
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
import androidx.compose.runtime.withFrameNanos
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
         firstFocus: FocusRequester? = null, onEnd: (() -> Unit)? = null, key: String = title, loading: Boolean = false) {
    val metas = hubOnly(metas)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Home's row: the opened card rests on the left, Up / Down come back to the card you left.
    com.lekathedon.visionlite.home.PageRow(key, title, metas, loading, subOf, firstFocus, onHold, onEnd) { m ->
        if (m.id.startsWith("tt") || m.id.startsWith("kitsu:")) nav.push(Screen.Detail(m.type, m.id, m.name))
        else scope.launch { com.lekathedon.visionlite.home.openTitle(nav, m) }
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

// Discover: see Discover.kt (built-in sources; add-ons optional).

// ---------------------------------------------------------------- Genres and Decades (WuPlay, see Wuplay.kt)

/** A loading rail's titles, kept by its page (not by the row), so a row scrolled away and back keeps what it had. */
class RailData {
    val items = mutableStateListOf<MetaPreview>()
    var busy by mutableStateOf(false)
    var end by mutableStateOf(false)
    /** Cards the source has given so far (before dropping repeats): the next page starts there. */
    var fetched = 0
    var page = 0
}

/** The page's rails' data: one per rail key, alive while the page is. */
@Composable
fun rememberRails(vararg keys: Any?): MutableMap<String, RailData> = remember(*keys) { HashMap() }

/** A rail that loads itself the first time it's drawn, and pages as you reach its end. [load] gets (skip, page). */
@Composable
fun LoadingRail(title: String, nav: Nav, store: MutableMap<String, RailData>, key: String = title, load: suspend (skip: Int, page: Int) -> List<MetaPreview>) {
    val d = store.getOrPut(key) { RailData() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun more() {
        if (d.busy || d.end) return
        d.busy = true
        scope.launch {
            try {
                for (tries in 0 until 3) {
                    val got = load(d.fetched, d.page + 1)
                    d.page++; d.fetched += got.size
                    val p = got.filter { m -> d.items.none { it.id == m.id } }
                    d.items.addAll(p); d.end = got.isEmpty()
                    if (d.end || p.any { com.lekathedon.visionlite.anime.Hubs.keeps(com.lekathedon.visionlite.anime.Hubs.now(), it) }) break
                }
            } finally { d.busy = false }
        }
    }
    LaunchedEffect(key) { if (d.items.isEmpty()) more() }
    // A rail that ended empty (a subgenre with nothing in that decade) leaves no trace; one still loading keeps a card.
    Rail(title, d.items, nav, subOf = { m -> listOfNotNull(m.releaseInfo, m.imdbRating?.let { "★ $it" }).joinToString(" · ").ifEmpty { null } },
        onEnd = { more() }, key = key, loading = !d.end && d.items.isEmpty())
}

private val media = listOf("all" to "All", "movie" to "Movies", "series" to "Shows")
private fun mediaOf(k: String) = when (k) { "movie" -> Wuplay.Media.MOVIES; "series" -> Wuplay.Media.SHOWS; else -> Wuplay.Media.ALL }

/**
 * A mood tile's page: first what the one engine has for it (Vision Picks + Scout's finds + every "because you watched" +
 * the AIs, nothing you watched, saved or turned down), then its films and its shows from TMDB, page by page.
 */
@Composable
fun MoodPage(nav: Nav, key: String) {
    val mood = remember(key) { com.lekathedon.visionlite.recs.Moods.byKey(key) } ?: return
    val hub = remember { com.lekathedon.visionlite.anime.Hubs.now() }
    val changed by com.lekathedon.visionlite.recs.Recs.changed.collectAsState()
    val pool by com.lekathedon.visionlite.recs.Recs.pool(hub).collectAsState()
    val adult by com.lekathedon.visionlite.recs.Adult.revision.collectAsState()
    val own = remember(changed, pool, adult) { com.lekathedon.visionlite.recs.Adult.visible(com.lekathedon.visionlite.recs.Moods.engineMetas(mood, hub)) }
    val store = rememberRails(key, changed)
    val tmdb = com.lekathedon.visionlite.home.Tmdb.key() != null
    Page(mood.label, mood.why + if (!tmdb) "  ·  Add your TMDB key (Settings › Integrations) for more." else "") {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = 60.dp)) {
            if (own.isNotEmpty()) item(key = "own") {
                Rail("Picked for you", own, nav, subOf = { m -> listOfNotNull(m.releaseInfo, m.imdbRating?.let { "★ $it" }).joinToString(" · ").ifEmpty { null } }, key = "own")
            }
            if (tmdb) {
                if (!mood.seriesOnly) item(key = "films") {
                    LoadingRail("Films", nav, store, key = "films") { _, page -> com.lekathedon.visionlite.recs.Moods.page(mood, false, page, hub) }
                }
                item(key = "shows") {
                    LoadingRail("Shows", nav, store, key = "shows") { _, page -> com.lekathedon.visionlite.recs.Moods.page(mood, true, page, hub) }
                }
            }
        }
    }
}

/** A genre tile's page (WuPlay): Popular, Latest, Most Rated, then its subgenres, each a row; Anime: one subgenre's rows. */
@Composable
fun GenrePage(nav: Nav, slug: String) {
    if (slug.startsWith("anime:")) { AnimeGenrePage(nav, slug.removePrefix("anime:")); return }
    val g = remember(slug) { Wuplay.genre(slug) } ?: return
    var kind by rememberSaveable { mutableStateOf("all") }
    val store = rememberRails(slug, kind)
    val first = remember { FocusRequester() }
    Page(g.name, if (com.lekathedon.visionlite.home.Tmdb.key() == null) "Add your TMDB key (Settings › Integrations) to fill these rows." else "${g.rails.size} rows") {
        Chips(media, kind, Modifier.padding(top = 12.dp)) { kind = it }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 60.dp)) {
            items(g.rails, key = { "$kind|${it.key}" }) { r ->
                LoadingRail(r.title, nav, store, key = r.key) { _, page -> Wuplay.page(r, mediaOf(kind), page) }
            }
        }
    }
}

/** A decade tile's page (WuPlay): Popular, Most Rated, then each genre within the decade, each a row. */
@Composable
fun DecadePage(nav: Nav, slug: String) {
    if (slug.startsWith("anime:")) { AnimeDecadePage(nav, slug.removePrefix("anime:").toInt()); return }
    val d = remember(slug) { Wuplay.decade(slug) } ?: return
    var kind by rememberSaveable { mutableStateOf("all") }
    val store = rememberRails(slug, kind)
    Page(d.name, if (com.lekathedon.visionlite.home.Tmdb.key() == null) "Add your TMDB key (Settings › Integrations) to fill these rows." else null) {
        Chips(media, kind, Modifier.padding(top = 12.dp)) { kind = it }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 60.dp)) {
            items(d.rails, key = { "$kind|${it.key}" }) { r ->
                LoadingRail(r.title, nav, store, key = r.key) { _, page -> Wuplay.page(r, mediaOf(kind), page) }
            }
        }
    }
}

/** Anime hub: one of WuPlay's 52 anime subgenres (AniList): popular, top rated and newest, shows and movies. */
@Composable
private fun AnimeGenrePage(nav: Nav, name: String) {
    val sub = remember(name) { com.lekathedon.visionlite.anime.AniBrowse.subgenres.firstOrNull { it.name == name } } ?: return
    val store = rememberRails(name)
    val ab = com.lekathedon.visionlite.anime.AniBrowse
    fun r(t: String, f: suspend (Int) -> List<MetaPreview>) = t to f
    val rails = if (sub.format != null) listOf(
        r("Popular") { s -> ab.page("series", "POPULARITY_DESC", sub = sub, skip = s) },
        r("Top rated") { s -> ab.page("series", "SCORE_DESC", sub = sub, skip = s) },
        r("Latest") { s -> ab.page("series", "START_DATE_DESC", sub = sub, skip = s) })
    else listOf(
        r("Popular shows") { s -> ab.page("series", "POPULARITY_DESC", sub = sub, skip = s) },
        r("Popular movies") { s -> ab.page("movie", "POPULARITY_DESC", sub = sub, skip = s) },
        r("Top rated shows") { s -> ab.page("series", "SCORE_DESC", sub = sub, skip = s) },
        r("Top rated movies") { s -> ab.page("movie", "SCORE_DESC", sub = sub, skip = s) },
        r("Latest shows") { s -> ab.page("series", "START_DATE_DESC", sub = sub, skip = s) })
    Page(name) {
        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp), contentPadding = PaddingValues(bottom = 60.dp)) {
            items(rails, key = { it.first }) { (t, load) -> LoadingRail(t, nav, store, key = "$name|$t") { skip, _ -> load(skip) } }
        }
    }
}

/** Anime hub: a decade's most popular, then WuPlay's anime subgenres within it, each a row. */
@Composable
private fun AnimeDecadePage(nav: Nav, decade: Int) {
    val year = Calendar.getInstance().get(Calendar.YEAR)
    val to = minOf(decade + 9, year)
    val store = rememberRails(decade)
    val ab = com.lekathedon.visionlite.anime.AniBrowse
    Page("${decade}s") {
        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp), contentPadding = PaddingValues(bottom = 60.dp)) {
            listOf("series" to "Popular shows", "movie" to "Popular movies").forEach { (t, title) ->
                item(key = "pop|$t") { LoadingRail(title, nav, store, key = "pop|$t") { skip, _ -> ab.page(t, "POPULARITY_DESC", from = decade, to = to, skip = skip) } }
            }
            items(ab.subgenres, key = { it.name }) { sub ->
                LoadingRail(sub.name, nav, store, key = "sub|${sub.name}") { skip, _ -> ab.page("series", "POPULARITY_DESC", sub = sub, from = decade, to = to, skip = skip) }
            }
        }
    }
}

/** Home's Genres / Decades tiles for a hub: (id, name). Films & TV: WuPlay's 19 and 9; Anime: its 52 subgenres and decades. */
object BrowseTiles {
    fun genres(anime: Boolean): List<Pair<String, String>> =
        if (anime) com.lekathedon.visionlite.anime.AniBrowse.subgenres.map { "genre:anime:${it.name}" to it.name }
        else Wuplay.genres.map { "genre:${it.slug}" to it.name }
    fun decades(anime: Boolean): List<Pair<String, String>> {
        if (!anime) return Wuplay.decades.map { "decade:${it.slug}" to it.name }
        val year = Calendar.getInstance().get(Calendar.YEAR)
        return (year / 10 * 10 downTo 1960 step 10).map { "decade:anime:$it" to "${it}s" }
    }
}

// Scout is no longer a page (0.27): its engine fills Home's Vision Picks, "Because you watched" and mood rows.

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
    val store = rememberRails(id)
    val first = remember { FocusRequester() }
    var focusedOnce by rememberSaveable(id) { mutableStateOf(false) }
    Page(s.title, when (s.region) { "GB" -> "UK"; "FR" -> "France"; "JP" -> "Japan"; else -> "US" }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 60.dp)) {
            itemsIndexed(rows, key = { _, r -> r.first }) { i, (title, load) ->
                val d = store.getOrPut(title) { RailData() }
                // First visit only: focus the first card once it's in (never again on the way back up).
                if (i == 0 && !focusedOnce) LaunchedEffect(d.items.isNotEmpty()) { if (d.items.isNotEmpty()) { withFrameNanos { }; if (runCatching { first.requestFocus() }.isSuccess) focusedOnce = true } }
                ServiceRail(title, nav, d, if (i == 0) first else null) { p -> load(p) }
            }
        }
    }
}

/** A service row: up to 5 pages, the ones without a card of this hub skipped; kept by the page across scrolling. */
@Composable
private fun ServiceRail(title: String, nav: Nav, d: RailData, firstFocus: FocusRequester?, load: suspend (Int) -> List<MetaPreview>) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun next() {
        if (d.busy || d.end) return
        d.busy = true
        scope.launch {
            try {
                for (tries in 0 until 3) {
                    if (d.page >= 5) { d.end = true; break }
                    val more = load(d.page + 1); d.page++
                    if (more.isEmpty()) { d.end = true; break }
                    val fresh = more.filter { m -> d.items.none { it.id == m.id } }
                    d.items.addAll(fresh)
                    if (fresh.any { com.lekathedon.visionlite.anime.Hubs.keeps(com.lekathedon.visionlite.anime.Hubs.now(), it) }) break
                }
            } finally { d.busy = false }
        }
    }
    LaunchedEffect(title) { if (d.items.isEmpty()) next() }
    Rail(title, d.items, nav, subOf = { m -> listOfNotNull(m.releaseInfo, m.imdbRating?.let { "★ $it" }).joinToString(" · ").ifEmpty { null } },
        firstFocus = firstFocus, onEnd = { next() }, key = title, loading = !d.end && d.items.isEmpty())
}

/**
 * A person (Vision's look, user 2026-11-22): Known for first so you recognise them, then a row for every role they had
 * (Actor, Director, Creator, Writer, Producer; the biggest first), newest first. Follow makes them a favourite: they
 * steer the engine and get "Because you like" rows on Home. One IMDb request per page opened.
 */
@Composable
fun PersonPage(nav: Nav, id: String, name: String) {
    val people = com.lekathedon.visionlite.home.People
    var data by remember(id) { mutableStateOf<com.lekathedon.visionlite.home.People.Roles?>(null) }
    LaunchedEffect(id) { data = people.roles(id) }
    val list by people.list.collectAsState()
    // Follow makes them a favourite in this hub only (Anime: voice actors, directors you follow there).
    val hubKey = com.lekathedon.visionlite.anime.Hubs.content.collectAsState().value.key
    val following = list.any { it.id == id && it.hub == hubKey }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val d = data
    val shownName = d?.person?.name?.ifBlank { null } ?: name
    val sub = when {
        d == null -> "Loading…"
        d.rows.isEmpty() -> "Nothing found"
        else -> d.rows.joinToString(" · ") { it.first }
    }
    Page(shownName, sub) {
        Row(Modifier.padding(top = 10.dp)) {
            Button(if (following) "Following ✓" else "Follow", {
                people.toggle(d?.person?.copy(name = shownName, hub = hubKey) ?: com.lekathedon.visionlite.home.People.Person(id, name, hub = hubKey))
            }, Modifier.focusRequester(first))
        }
        val subOf: (MetaPreview) -> String = { listOfNotNull(it.releaseInfo, it.imdbRating?.let { r -> "★ $r" }).joinToString(" · ") }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 60.dp)) {
            if (d != null && d.knownFor.isNotEmpty()) item(key = "known") { Rail("Known for", d.knownFor, nav, subOf = subOf, key = "known") }
            d?.rows?.forEach { (role, items) -> item(key = role) { Rail(role, items, nav, subOf = subOf, key = role) } }
        }
    }
}
