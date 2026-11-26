// Discover, Vision's way: three drop-downs (Type · Catalog · Genre) and the titles below in Home's rows. Built-in
// sources need no add-on: Films & TV = TMDB (your key), IMDb and Cinemeta (both keyless, asked directly); Anime =
// AniList, MyAnimeList and Kitsu. Your add-ons' catalogs join the Catalog list when you have any (optional).
// Each hub shows only its own titles: no anime in Films & TV, nothing but anime in Anime.
package com.lekathedon.visionlite.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.BuildConfig
import com.lekathedon.visionlite.addons.MetaPreview
import com.lekathedon.visionlite.anime.Hub
import com.lekathedon.visionlite.anime.Hubs
import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.home.Charts
import com.lekathedon.visionlite.home.Tmdb
import com.lekathedon.visionlite.ui.Focusable
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** One Discover catalog: its name, the genres it can filter by, and how to load (genre or null, cards already had). */
class DiscoverCat(val key: String, val name: String, val genres: List<String>, val load: suspend (genre: String?, skip: Int) -> List<MetaPreview>)

object DiscoverSources {
    private val TMDB_MOVIE = linkedMapOf("Action" to 28, "Adventure" to 12, "Animation" to 16, "Comedy" to 35, "Crime" to 80, "Documentary" to 99,
        "Drama" to 18, "Family" to 10751, "Fantasy" to 14, "History" to 36, "Horror" to 27, "Music" to 10402, "Mystery" to 9648,
        "Romance" to 10749, "Science Fiction" to 878, "Thriller" to 53, "War" to 10752, "Western" to 37)
    private val TMDB_TV = linkedMapOf("Action & Adventure" to 10759, "Animation" to 16, "Comedy" to 35, "Crime" to 80, "Documentary" to 99,
        "Drama" to 18, "Family" to 10751, "Kids" to 10762, "Mystery" to 9648, "Reality" to 10764, "Sci-Fi & Fantasy" to 10765,
        "War & Politics" to 10768, "Western" to 37)
    /** IMDb's / Cinemeta's genre names (one list for both). */
    private val IMDB_GENRES = listOf("Action", "Adventure", "Animation", "Biography", "Comedy", "Crime", "Documentary", "Drama", "Family",
        "Fantasy", "History", "Horror", "Music", "Mystery", "Romance", "Sci-Fi", "Sport", "Thriller", "War", "Western")
    private val ANI_GENRES = listOf("Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Horror", "Mahou Shoujo", "Mecha", "Music",
        "Mystery", "Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller")
    /** Kitsu's category slugs where they differ from the name. */
    private val KITSU_SLUG = mapOf("Sci-Fi" to "science-fiction", "Mahou Shoujo" to "magical-girl", "Sports" to "sports")

    private fun aniGenres() = if (com.lekathedon.visionlite.recs.Adult.on()) ANI_GENRES - "Ecchi" else ANI_GENRES

    /** The catalogs for [hub] and [type] ("movie" / "series"): built-in first, then your add-ons' (this hub's titles only). */
    fun catalogs(hub: Hub, type: String): List<DiscoverCat> {
        val tv = type == "series"
        val out = ArrayList<DiscoverCat>()
        if (hub == Hub.FILMS) {
            if (Tmdb.key() != null) {
                val g = (if (tv) TMDB_TV else TMDB_MOVIE).keys.toList()
                out += DiscoverCat("tmdb.popular", "Popular · TMDB", g) { genre, skip -> tmdb(tv, genre, skip, "popularity.desc", 100) }
                out += DiscoverCat("tmdb.top", "Top Rated · TMDB", g) { genre, skip -> tmdb(tv, genre, skip, "vote_average.desc", if (tv) 300 else 1000) }
                out += DiscoverCat("tmdb.new", "New Releases · TMDB", g) { genre, skip -> tmdb(tv, genre, skip, if (tv) "first_air_date.desc" else "primary_release_date.desc", 20) }
                out += DiscoverCat("tmdb.trending", "Trending This Week · TMDB", emptyList()) { _, skip ->
                    withContext(Dispatchers.IO) { Tmdb.items(Tmdb.get("trending/${if (tv) "tv" else "movie"}/week?page=${skip / 20 + 1}"), tv) }
                }
            }
            out += DiscoverCat("imdb.popular", "Most Popular · IMDb", IMDB_GENRES) { genre, skip -> imdb(tv, genre, skip, "POPULARITY", "ASC", 2000) }
            out += DiscoverCat("imdb.top", "Top Rated · IMDb", IMDB_GENRES) { genre, skip -> imdb(tv, genre, skip, "USER_RATING", "DESC", if (tv) 10000 else 25000) }
            out += DiscoverCat("cinemeta.top", "Popular · Cinemeta", IMDB_GENRES) { genre, skip -> cinemeta(type, "top", genre, skip) }
            out += DiscoverCat("cinemeta.rating", "Featured · Cinemeta", IMDB_GENRES) { genre, skip -> cinemeta(type, "imdbRating", genre, skip) }
        } else {
            val g = aniGenres()
            out += DiscoverCat("anilist.trending", "Trending · AniList", g) { genre, skip -> ani(type, "TRENDING_DESC", genre, skip) }
            out += DiscoverCat("anilist.popular", "Most Popular · AniList", g) { genre, skip -> ani(type, "POPULARITY_DESC", genre, skip) }
            out += DiscoverCat("anilist.top", "Top Rated · AniList", g) { genre, skip -> ani(type, "SCORE_DESC", genre, skip) }
            out += DiscoverCat("anilist.new", "Newest · AniList", g) { genre, skip -> ani(type, "START_DATE_DESC", genre, skip) }
            if (BuildConfig.MAL_CLIENT_ID.isNotBlank()) {
                out += DiscoverCat("mal.top", "Top Ranked · MAL", g) { genre, skip -> mal(if (tv) "tv" else "movie", genre, skip) }
                out += DiscoverCat("mal.popular", "Most Popular · MAL", g) { genre, skip -> mal("bypopularity", genre, skip, if (tv) "series" else "movie") }
                if (tv) out += DiscoverCat("mal.airing", "Top Airing · MAL", g) { genre, skip -> mal("airing", genre, skip) }
                out += DiscoverCat("mal.upcoming", "Most Anticipated · MAL", g) { genre, skip -> mal("upcoming", genre, skip, if (tv) "series" else "movie") }
            }
            out += DiscoverCat("kitsu.top", "Top Rated · Kitsu", g) { genre, skip -> kitsu(tv, "-averageRating", genre, skip) }
            out += DiscoverCat("kitsu.popular", "Most Popular · Kitsu", g) { genre, skip -> kitsu(tv, "popularityRank", genre, skip) }
        }
        // Your add-ons (optional): every catalog of this type that needs no search term.
        App.instance.addons.addons.value.filter { com.lekathedon.visionlite.addons.AddonRoles.allows(it.base, "catalog") }.forEach { a ->
            a.manifest.catalogs.filter { c -> c.type == type && !c.needsSearch && c.extraRequired.all { it == "genre" } && c.extra.none { it.isRequired && it.name != "genre" } }
                .forEach { c ->
                    val genres = c.extra.firstOrNull { it.name == "genre" }?.options.orEmpty()
                    val required = c.extra.any { it.name == "genre" && it.isRequired } || "genre" in c.extraRequired
                    out += DiscoverCat("addon|${a.base}|${c.type}|${c.id}", "${c.name ?: c.id} · ${a.manifest.name}", genres) { genre, skip ->
                        if (skip > 0 && !c.supportsSkip) emptyList()
                        else App.instance.client.catalog(a, c, skip = skip, genre = genre ?: if (required) genres.firstOrNull() else null)
                    }
                }
        }
        return out
    }

    private suspend fun tmdb(tv: Boolean, genre: String?, skip: Int, sort: String, votes: Int): List<MetaPreview> = withContext(Dispatchers.IO) {
        val today = LocalDate.now().toString()
        val dateKey = if (tv) "first_air_date" else "primary_release_date"
        val gid = genre?.let { (if (tv) TMDB_TV else TMDB_MOVIE)[it] }
        Tmdb.items(Tmdb.get("discover/${if (tv) "tv" else "movie"}?include_adult=false&sort_by=$sort&vote_count.gte=$votes&$dateKey.lte=$today&page=${skip / 20 + 1}" +
            (gid?.let { "&with_genres=$it" } ?: "") + com.lekathedon.visionlite.recs.Adult.tmdbWithout()), tv)
    }

    /** IMDb advanced search (keyless), 50 a page; deeper pages ask the first [skip] + 50 and keep the new ones (max 500). */
    private suspend fun imdb(tv: Boolean, genre: String?, skip: Int, sort: String, order: String, votes: Int): List<MetaPreview> = withContext(Dispatchers.IO) {
        if (skip >= 500) return@withContext emptyList()
        val types = if (tv) "[\"tvSeries\", \"tvMiniSeries\"]" else "[\"movie\"]"
        val c = StringBuilder("titleTypeConstraint: {anyTitleTypeIds: $types}, userRatingsConstraint: {ratingsCountRange: {min: $votes}}, ")
        c.append("releaseDateConstraint: {releaseDateRange: {end: \"${LocalDate.now()}\"}}")
        genre?.let { c.append(", genreConstraint: {allGenreIds: [${JSONObject.quote(it)}]}") }
        val q = "{ s: advancedTitleSearch(first: ${skip + 50}, constraints: {$c}, sort: {sortBy: $sort, sortOrder: $order}) { edges { node { title { " +
            "id titleText { text } releaseYear { year } ratingsSummary { aggregateRating } primaryImage { url } titleGenres { genres { genre { text } } } } } } } }"
        val d = runCatching { Charts.post(Charts.IMDB_URL, JSONObject().put("query", q)) }.getOrNull() ?: return@withContext emptyList()
        val edges = d.optJSONObject("s")?.optJSONArray("edges") ?: return@withContext emptyList()
        val flat = JSONArray().apply { (0 until edges.length()).forEach { n -> edges.optJSONObject(n)?.optJSONObject("node")?.optJSONObject("title")?.let { put(JSONObject().put("node", it)) } } }
        Charts.imdb(JSONObject().put("edges", flat), if (tv) "series" else "movie").drop(skip)
    }

    /** Cinemeta asked directly (no add-on needed). */
    private suspend fun cinemeta(type: String, id: String, genre: String?, skip: Int): List<MetaPreview> = withContext(Dispatchers.IO) {
        val extras = listOfNotNull(genre?.let { "genre=" + java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }, skip.takeIf { it > 0 }?.let { "skip=$it" })
        val url = "https://v3-cinemeta.strem.io/catalog/$type/$id" + (if (extras.isEmpty()) "" else "/" + extras.joinToString("&")) + ".json"
        runCatching {
            JSONObject(Net.get(url, 15)).optJSONArray("metas").let { a ->
                (0 until (a?.length() ?: 0)).mapNotNull { i ->
                    val m = a!!.optJSONObject(i) ?: return@mapNotNull null
                    val mid = m.optString("imdb_id").ifBlank { m.optString("id") }.takeIf { it.startsWith("tt") } ?: return@mapNotNull null
                    MetaPreview(mid, type, m.optString("name").ifBlank { return@mapNotNull null },
                        m.optString("poster").takeIf { it.startsWith("http") }, m.optString("background").takeIf { it.startsWith("http") },
                        description = m.optString("description").ifBlank { null },
                        releaseInfo = m.optString("releaseInfo").ifBlank { m.optString("year") }.ifBlank { null },
                        imdbRating = m.optString("imdbRating").ifBlank { null },
                        genres = m.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.optString(it) } }.orEmpty())
                }
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun ani(type: String, sort: String, genre: String?, skip: Int): List<MetaPreview> =
        com.lekathedon.visionlite.anime.AniBrowse.page(type, sort, genre?.let { com.lekathedon.visionlite.anime.AniBrowse.Sub(it, genres = listOf(it)) }, skip = skip)

    /** MAL rankings (Lite's MAL app id, no sign-in), 50 a page; a genre is matched on MAL's own genre names. */
    private suspend fun mal(ranking: String, genre: String?, skip: Int, onlyType: String? = null): List<MetaPreview> = withContext(Dispatchers.IO) {
        if (skip >= 500) return@withContext emptyList()
        runCatching { Charts.mal("anime/ranking?ranking_type=$ranking&limit=50&offset=$skip&fields=main_picture,alternative_titles,mean,media_type,start_season,genres&nsfw=false") }
            .getOrDefault(emptyList())
            .let { raw ->
                val kept = raw.filter { (onlyType == null || it.type == onlyType) && (genre == null || it.genres.any { g -> g.equals(genre, true) }) }
                // Filtered-out cards still count as asked (placeholders Discover drops), so the next page starts at the right place.
                kept + List(raw.size - kept.size) { MetaPreview("skip:$ranking:$skip:$it", "skip") }
            }
    }

    /** Kitsu (keyless JSON:API), 20 a page; genres are Kitsu categories. */
    private suspend fun kitsu(tv: Boolean, sort: String, genre: String?, skip: Int): List<MetaPreview> = withContext(Dispatchers.IO) {
        val cat = genre?.let { KITSU_SLUG[it] ?: it.lowercase().replace(' ', '-') }
        val url = "https://kitsu.io/api/edge/anime?sort=$sort&filter%5Bsubtype%5D=${if (tv) "TV" else "movie"}&page%5Blimit%5D=20&page%5Boffset%5D=$skip" +
            (cat?.let { "&filter%5Bcategories%5D=$it" } ?: "") + if (com.lekathedon.visionlite.recs.Adult.on()) "&filter%5BageRating%5D=G,PG,R" else ""
        runCatching {
            val d = Net.http.newCall(Request.Builder().url(url).header("Accept", "application/vnd.api+json").build()).execute().use { JSONObject(it.body?.string().orEmpty()) }
            d.optJSONArray("data").let { arr ->
                (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                    val o = arr!!.optJSONObject(i) ?: return@mapNotNull null
                    val a = o.optJSONObject("attributes") ?: return@mapNotNull null
                    val name = a.optJSONObject("titles")?.optString("en")?.takeIf { it.isNotBlank() && it != "null" } ?: a.optString("canonicalTitle").ifBlank { null } ?: return@mapNotNull null
                    MetaPreview("kitsu:${o.optString("id")}", if (a.optString("subtype") == "movie") "movie" else "series", name,
                        a.optJSONObject("posterImage")?.optString("large")?.takeIf { it.startsWith("http") },
                        a.optJSONObject("coverImage")?.optString("large")?.takeIf { it.startsWith("http") },
                        description = a.optString("synopsis").takeIf { it.isNotBlank() && it != "null" },
                        releaseInfo = a.optString("startDate").take(4).takeIf { it.length == 4 },
                        imdbRating = a.optString("averageRating").toDoubleOrNull()?.let { "%.1f".format(it / 10) })
                }
            }
        }.getOrDefault(emptyList())
    }
}

/** Discover: Type · Catalog · Genre drop-downs, then the titles in Home's rows (the focused card opens out on the left). */
@Composable
fun Discover(nav: Nav) {
    val hub by Hubs.content.collectAsState()
    androidx.compose.runtime.key(hub) { DiscoverOf(nav, hub) }
}

private const val PER_ROW = 20

@Composable
private fun DiscoverOf(nav: Nav, hub: Hub) {
    val addons by App.instance.addons.addons.collectAsState()
    val adultOn by App.instance.prefs.hideAdult.flow.collectAsState()
    var type by rememberSaveable { mutableStateOf("movie") }
    val cats = remember(hub, type, addons, adultOn) { DiscoverSources.catalogs(hub, type) }
    var catKey by rememberSaveable(type) { mutableStateOf<String?>(null) }
    val cat = cats.firstOrNull { it.key == catKey } ?: cats.firstOrNull()
    var genre by rememberSaveable(cat?.key) { mutableStateOf<String?>(null) }
    val g = genre?.takeIf { cat != null && it in cat.genres }
    val listKey = "${hub.key}|$type|${cat?.key}|$g"
    val items = remember(listKey) { mutableStateListOf<MetaPreview>() }
    var asked by remember(listKey) { mutableStateOf(0) }
    var more by remember(listKey) { mutableStateOf(true) }
    var loading by remember(listKey) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun load() {
        val c = cat ?: return
        if (loading || !more) return
        loading = true
        scope.launch {
            try {
                // Only this hub's titles; a page with none of them asks the next (up to 4 in a row).
                for (tries in 0 until 4) {
                    val page = runCatching { c.load(g, asked) }.getOrDefault(emptyList())
                    asked += page.size
                    val fresh = page.filter { m -> m.type != "skip" && items.none { it.id == m.id } }
                    val kept = fresh.filter { Hubs.shows(hub, it) }
                    items.addAll(kept)
                    more = page.isNotEmpty()
                    if (!more || kept.isNotEmpty()) break
                }
            } finally { loading = false }
        }
    }
    LaunchedEffect(listKey) { load() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; runCatching { first.requestFocus() } }
    val rows = items.chunked(PER_ROW)
    Column(Modifier.fillMaxSize().background(LocalLook.current.page).padding(start = 40.dp, end = 24.dp, top = 24.dp)) {
        Text("Discover", color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Drop("Type", listOf("movie" to "Movies", "series" to "Shows"), type, Modifier.focusRequester(first)) { type = it }
            Drop("Catalog", cats.map { it.key to it.name }, cat?.key ?: "") { catKey = it }
            if (cat != null && cat.genres.isNotEmpty()) Drop("Genre", listOf("" to "All genres") + cat.genres.filterNot { com.lekathedon.visionlite.recs.LeaveOut.rowHidden(it) }.map { it to it }, g ?: "") { genre = it.ifEmpty { null } }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 6.dp, bottom = 60.dp)) {
            items(rows.size, key = { "$listKey|$it" }) { i ->
                val last = i == rows.size - 1
                com.lekathedon.visionlite.home.PageRow("disc|$listKey|$i", "", rows[i],
                    sub = { m -> listOfNotNull(m.releaseInfo, m.imdbRating?.let { "★ $it" }).joinToString(" · ").ifEmpty { null } },
                    onEnd = if (last) ({ load() }) else null) { m ->
                    if (m.id.startsWith("tt") || m.id.startsWith("kitsu:")) nav.push(Screen.Detail(m.type, m.id, m.name))
                    else scope.launch { com.lekathedon.visionlite.home.openTitle(nav, m) }
                }
                Spacer(Modifier.height(14.dp))
            }
            if (items.isEmpty()) item(key = "note") {
                Text(when { cat == null -> "No catalogs here."; loading || more -> "Loading…"; else -> "Nothing here." },
                    color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 20.dp))
            }
        }
    }
}

/** Vision's drop-down: a pill showing the choice; OK opens the list (focus on the current one), OK picks. */
@Composable
private fun Drop(label: String, options: List<Pair<String, String>>, current: String, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shown = options.firstOrNull { it.first == current }?.second ?: options.firstOrNull()?.second ?: "—"
    Focusable({ if (options.isNotEmpty()) open = true }, modifier, shape = RoundedCornerShape(50)) { f ->
        Text("$shown  ▾", color = if (f) com.lekathedon.visionlite.ui.KitInk.ink else com.lekathedon.visionlite.ui.KitInk.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.background(if (f) com.lekathedon.visionlite.ui.KitInk.bg else com.lekathedon.visionlite.ui.Kit.rowFill).padding(horizontal = 18.dp, vertical = 8.dp))
    }
    if (open) Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val reqs = remember(options) { List(options.size) { FocusRequester() } }
        val at = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
        LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; runCatching { reqs[at].requestFocus() } }
        Column(Modifier.width(460.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Card).padding(20.dp)) {
            Text(label, color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            val state = androidx.compose.foundation.lazy.rememberLazyListState(at)
            LazyColumn(Modifier.padding(top = 10.dp).heightIn(max = 420.dp), state = state) {
                items(options.size) { i ->
                    val (id, name) = options[i]
                    Focusable({ onPick(id); open = false }, Modifier.focusRequester(reqs[i]).padding(top = 4.dp).fillMaxWidth(), shape = RoundedCornerShape(10.dp)) { f ->
                        Row(Modifier.fillMaxWidth().background(if (f) com.lekathedon.visionlite.ui.KitInk.bg else Color.Transparent).padding(horizontal = 16.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(name, color = if (f) com.lekathedon.visionlite.ui.KitInk.ink else Palette.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            if (id == current) Text("✓", color = if (f) com.lekathedon.visionlite.ui.KitInk.ink else Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
