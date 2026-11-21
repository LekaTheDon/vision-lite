package com.lekathedon.visionlite.home

import android.util.Log
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.BuildConfig
import com.lekathedon.visionlite.addons.MetaPreview
import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.integrations.Integrations
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Vision's own Home rows, in Vision's order under Continue Watching (Moods left out on purpose, for now). */
object VisionRows {
    const val COMING_UP = "v/comingup"
    const val PICKS = "v/picks"
    const val IMDB = "v/imdb"
    const val AI = "v/ai"
    const val SERVICES = "v/services"
    const val PEOPLE = "v/people"

    /** key to title, in Vision's order. */
    val all: List<Pair<String, String>> = listOf(
        COMING_UP to "Coming up", PICKS to "Vision Picks", IMDB to "IMDb Picks", AI to "AI Picks",
        SERVICES to "Streaming services", PEOPLE to "Favourite people"
    ) + Charts.Chart.entries.map { "c/${it.id}" to it.title }

    /**
     * The rows a hub's Home starts from. Films & TV leaves the anime charts out; Anime puts them right under the picks
     * and drops the anime suffix (every row there is anime). Each row then shows only its hub's titles.
     */
    fun forHub(hub: com.lekathedon.visionlite.anime.Hub): List<Pair<String, String>> {
        val animeCharts = Charts.Chart.entries.filter { it.src in Charts.ANIME_SRCS }.map { "c/${it.id}" }.toSet()
        if (hub == com.lekathedon.visionlite.anime.Hub.FILMS) return all.filter { it.first !in animeCharts }
        // Anime: its own Top 20 Today (AniList) in place of TMDB's, whose lists are hardly ever anime.
        val tmdbToday = setOf("c/${Charts.Chart.TOP_MOVIES_TODAY.id}", "c/${Charts.Chart.TOP_SHOWS_TODAY.id}")
        // IMDb's general charts (Top 250 Movies / TV, Most Popular) would only repeat the Top 250 Anime rows there.
        val imdbCharts = Charts.Chart.entries.filter { it.src == Charts.Src.IMDB }.map { "c/${it.id}" }.toSet()
        val (charts, rest) = all.filter { it.first !in tmdbToday && it.first !in imdbCharts }.partition { it.first in animeCharts }
        val head = rest.takeWhile { it.first != SERVICES }
        // Named as Films & TV's (Top 250 Movies / Top 250 TV Shows): one set per hub, never a second.
        return head + charts.map { it.first to it.second.removeSuffix(" · Anime").replace("Anime ", "").replace("Top 250 Series", "Top 250 TV Shows") } + rest.drop(head.size)
    }

    fun isPicks(key: String) = key == PICKS || key == IMDB || key == AI
    /** Vision's ranked rows (BuiltInRow ranked = true): Top 20 Movies / Shows Today. */
    val ranked = setOf("c/${Charts.Chart.TOP_MOVIES_TODAY.id}", "c/${Charts.Chart.TOP_SHOWS_TODAY.id}",
        "c/${Charts.Chart.ANIME_TODAY_SERIES.id}", "c/${Charts.Chart.ANIME_TODAY_MOVIES.id}")
}

// ------------------------------------------------------------------ TMDB (only with your key)

object Tmdb {
    fun key(): String? = if (!Integrations.active("tmdb")) null else Integrations.values(Integrations.byId("tmdb")).firstOrNull()?.takeIf { it.isNotBlank() }

    /** GET /3/[path] with your key (an API key or a read token); null on any failure or without a key. */
    fun get(path: String): JSONObject? {
        val k = key() ?: return null
        val token = k.length > 50
        val url = "https://api.themoviedb.org/3/$path" + if (token) "" else (if ('?' in path) "&" else "?") + "api_key=$k"
        return runCatching {
            Net.http.newCall(Request.Builder().url(url).apply { if (token) header("Authorization", "Bearer $k") }.build()).execute().use { r ->
                if (r.isSuccessful) JSONObject(r.body!!.string()) else null
            }
        }.getOrNull()
    }

    fun img(path: String?, size: String) = path?.takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/$size$it" }

    /** TMDB results as cards; ids "tmdb:movie:1" / "tmdb:tv:2" are turned into IMDb ids when opened. */
    fun items(o: JSONObject?, tv: Boolean?): List<MetaPreview> = o?.optJSONArray("results").objects().mapNotNull { r ->
        val isTv = tv ?: (r.optString("media_type") == "tv")
        val id = r.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null
        MetaPreview("tmdb:${if (isTv) "tv" else "movie"}:$id", if (isTv) "series" else "movie", r.optString(if (isTv) "name" else "title").ifBlank { return@mapNotNull null },
            img(r.optString("poster_path"), "w342"), img(r.optString("backdrop_path"), "w1280"),
            description = r.optString("overview").ifBlank { null },
            releaseInfo = r.optString(if (isTv) "first_air_date" else "release_date").take(4).ifBlank { null },
            imdbRating = r.optDouble("vote_average").takeIf { !it.isNaN() && it > 0 }?.let { "%.1f".format(it) })
    }

    fun imdbOf(tmdbId: String): String? {
        val (kind, id) = tmdbId.removePrefix("tmdb:").split(':').takeIf { it.size == 2 } ?: return null
        return get("$kind/$id/external_ids")?.optString("imdb_id")?.takeIf { it.startsWith("tt") }
    }
}

internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** Opens a card: IMDb ids at once; TMDB and AniList ids are first turned into IMDb ids (one small lookup). */
suspend fun openTitle(nav: Nav, m: MetaPreview) {
    val id = imdbIdOf(m) ?: return
    nav.push(Screen.Detail(m.type, id, m.name))
}

/** The IMDb id behind a card (TMDB / AniList ids looked up once); the card's own id otherwise. */
suspend fun imdbIdOf(m: MetaPreview): String? {
    return when {
        m.id.startsWith("tmdb:") -> withContext(Dispatchers.IO) { Tmdb.imdbOf(m.id) }
        m.id.startsWith("anilist:") || m.id.startsWith("mal:") || m.id.startsWith("kitsu:") -> withContext(Dispatchers.IO) {
            val q = when {
                m.id.startsWith("mal:") -> "mal_id=${m.id.removePrefix("mal:")}"
                m.id.startsWith("kitsu:") -> "kitsu_id=${m.id.removePrefix("kitsu:")}"
                else -> "anilist_id=${m.id.removePrefix("anilist:")}"
            }
            runCatching { JSONObject(Net.get("https://api.ani.zip/mappings?$q", 10))
                .optJSONObject("mappings")?.optString("imdb_id")?.takeIf { it.startsWith("tt") } }.getOrNull()
                ?.also { com.lekathedon.visionlite.anime.AnimeIds.learn(it) }
        } ?: m.id
        else -> m.id
    }
}

// ------------------------------------------------------------------ Charts (keyless) + Top 20 Today (TMDB)

/**
 * Vision's chart rows: IMDb (Most Popular / Top 250, movies and TV; Top 250 anime), AniList (Trending, This
 * Season), and TMDB's Top 20 Movies / Shows Today (with your TMDB key). Cost: 3 requests every 12 h (IMDb ~250 KB,
 * AniList ~150 KB, IMDb anime ~290 KB) + 2 TMDB a day; kept on disk, so Home shows them at once.
 */
object Charts {
    enum class Chart(val id: String, val title: String, val type: String, val src: Src) {
        IMDB_POPULAR_MOVIES("imdb.popular.movie", "Most Popular Movies", "movie", Src.IMDB),
        TOP_MOVIES_TODAY("tmdb.today.movie", "Top 20 Movies Today", "movie", Src.TMDB),
        IMDB_TOP_MOVIES("imdb.top250.movie", "Top 250 Movies", "movie", Src.IMDB),
        IMDB_POPULAR_TV("imdb.popular.series", "Most Popular TV Shows", "series", Src.IMDB),
        TOP_SHOWS_TODAY("tmdb.today.tv", "Top 20 Shows Today", "series", Src.TMDB),
        IMDB_TOP_TV("imdb.top250.series", "Top 250 TV Shows", "series", Src.IMDB),
        ANILIST_TRENDING("anilist.trending", "Trending Now · Anime", "series", Src.ANILIST),
        ANILIST_SEASON("anilist.season", "Popular This Season · Anime", "series", Src.ANILIST),
        /** The Anime hub's Top 20 Today (its highlight rows): AniList's trending, asked in the same request. */
        ANIME_TODAY_SERIES("anilist.today.series", "Top 20 Anime Shows Today", "series", Src.ANILIST),
        ANIME_TODAY_MOVIES("anilist.today.movie", "Top 20 Anime Movies Today", "movie", Src.ANILIST),
        IMDB_ANIME_SERIES("imdb.anime250.series", "Top 250 Anime Series", "series", Src.IMDB_ANIME),
        IMDB_ANIME_MOVIES("imdb.anime250.movie", "Top 250 Anime Movies", "movie", Src.IMDB_ANIME),
        // Anime trackers (user 2026-11-21: they follow anime releases better than IMDb/TMDB). Anime hub only.
        /** LiveChart.me's feed of episodes aired in the last 24 h, one card per show, newest first. */
        LIVECHART_AIRED("livechart.aired", "Just Aired · LiveChart", "series", Src.LIVECHART),
        /** AniChart = AniList's seasonal chart: what started this season, and next season's most awaited. */
        ANICHART_NEW("anichart.new", "New This Season · AniChart", "series", Src.ANILIST),
        ANICHART_NEXT("anichart.next", "Next Season · AniChart", "series", Src.ANILIST),
        /** MyAnimeList (Lite's MAL app id; no sign-in): the season by members, top airing, most anticipated. */
        MAL_SEASON("mal.season", "Popular This Season · MAL", "series", Src.MAL),
        MAL_AIRING("mal.airing", "Top Airing · MAL", "series", Src.MAL),
        MAL_UPCOMING("mal.upcoming", "Most Anticipated · MAL", "series", Src.MAL),
        KITSU_TRENDING("kitsu.trending", "Trending · Kitsu", "series", Src.KITSU)
    }
    enum class Src(val freshMs: Long) { IMDB(12 * 3600_000L), ANILIST(12 * 3600_000L), IMDB_ANIME(12 * 3600_000L), TMDB(20 * 3600_000L),
        LIVECHART(3600_000L), MAL(12 * 3600_000L), KITSU(12 * 3600_000L) }

    val ANIME_SRCS = setOf(Src.ANILIST, Src.IMDB_ANIME, Src.LIVECHART, Src.MAL, Src.KITSU)
    @Serializable private data class Disk(val at: Long, val rows: Map<String, List<MetaPreview>>)
    private val loaded = HashMap<Src, Disk>()
    private val locks = Src.entries.associateWith { Mutex() }
    private fun file(s: Src) = File(App.instance.filesDir, "charts/${s.name.lowercase()}.json").apply { parentFile?.mkdirs() }

    fun byKey(key: String) = Chart.entries.firstOrNull { "c/${it.id}" == key }

    suspend fun items(c: Chart): List<MetaPreview> = withContext(Dispatchers.IO) {
        locks.getValue(c.src).withLock {
            val now = System.currentTimeMillis()
            var have = loaded[c.src] ?: runCatching { Net.json.decodeFromString(Disk.serializer(), file(c.src).readText()) }.getOrNull()?.also { loaded[c.src] = it }
            // A chart added since the last fetch (the Anime hub's Top 20 Today) is asked at once, not after 12 h.
            if (have == null || now - have.at > c.src.freshMs || c.id !in have.rows) {
                val fresh = runCatching { if (c.src == Src.LIVECHART) liveChart() else fetch(c.src) }.onFailure { Log.w("Charts", "${c.src}: ${it.message}") }.getOrNull()
                if (fresh != null && fresh.values.any { it.isNotEmpty() }) {
                    have = Disk(now, fresh); loaded[c.src] = have
                    runCatching { file(c.src).writeText(Net.json.encodeToString(Disk.serializer(), have)) }
                }
            }
            have?.rows?.get(c.id).orEmpty()
        }
    }

    private fun fetch(s: Src): Map<String, List<MetaPreview>>? = when (s) {
        Src.TMDB -> {
            if (Tmdb.key() == null) null
            else mapOf(Chart.TOP_MOVIES_TODAY.id to Tmdb.items(Tmdb.get("trending/movie/day"), false).take(20),
                Chart.TOP_SHOWS_TODAY.id to Tmdb.items(Tmdb.get("trending/tv/day"), true).take(20))
        }
        Src.IMDB -> post(IMDB_URL, JSONObject().put("query", IMDB_QUERY))?.let { d ->
            mapOf(Chart.IMDB_POPULAR_MOVIES.id to imdb(d.optJSONObject("popMovies"), "movie"), Chart.IMDB_TOP_MOVIES.id to imdb(d.optJSONObject("topMovies"), "movie"),
                Chart.IMDB_POPULAR_TV.id to imdb(d.optJSONObject("popTv"), "series"), Chart.IMDB_TOP_TV.id to imdb(d.optJSONObject("topTv"), "series"))
        }
        Src.IMDB_ANIME -> post(IMDB_URL, JSONObject().put("query", IMDB_ANIME_QUERY))?.let { d ->
            val listed = imdb(d.optJSONObject("animeMovies")?.optJSONObject("titleListItemSearch"), "movie")
            val found = listOf("animeTagged", "animeJapan").flatMap { a -> d.optJSONObject(a)?.optJSONArray("edges").objects().mapNotNull { it.optJSONObject("node")?.optJSONObject("title") } }
                .distinctBy { it.optString("id") }
                .sortedWith(compareByDescending<JSONObject> { it.optJSONObject("ratingsSummary")?.optDouble("aggregateRating")?.takeIf { r -> !r.isNaN() } ?: 0.0 }
                    .thenByDescending { it.optJSONObject("ratingsSummary")?.optLong("voteCount") ?: 0L })
            val asChart = JSONObject().put("edges", JSONArray().apply { found.forEach { put(JSONObject().put("node", it)) } })
            mapOf(Chart.IMDB_ANIME_SERIES.id to imdb(d.optJSONObject("animeSeries")?.optJSONObject("titleListItemSearch"), "series"),
                Chart.IMDB_ANIME_MOVIES.id to (listed + imdb(asChart, "movie")).distinctBy { it.id }.take(250))
        }
        Src.ANILIST -> {
            val (season, year) = season(LocalDate.now())
            val (nextSeason, nextYear) = season(LocalDate.now().plusMonths(3))
            post("https://graphql.anilist.co", JSONObject().put("query", ANILIST_QUERY).put("variables", JSONObject().put("season", season).put("year", year)
                .put("nextSeason", nextSeason).put("nextYear", nextYear)))?.let { d ->
                mapOf(Chart.ANILIST_TRENDING.id to ani(d, "trending"), Chart.ANILIST_SEASON.id to ani(d, "season"),
                    Chart.ANIME_TODAY_SERIES.id to ani(d, "todayTv").take(20), Chart.ANIME_TODAY_MOVIES.id to ani(d, "todayMovies").take(20),
                    Chart.ANICHART_NEW.id to ani(d, "fresh"), Chart.ANICHART_NEXT.id to ani(d, "next"))
            }
        }
        Src.MAL -> if (BuildConfig.MAL_CLIENT_ID.isBlank()) null else {
            val (season, year) = season(LocalDate.now())
            val f = "fields=main_picture,alternative_titles,mean,media_type,start_season,genres&nsfw=false"
            mapOf(Chart.MAL_SEASON.id to mal("anime/season/$year/${season.lowercase()}?sort=anime_num_list_users&limit=50&$f"),
                Chart.MAL_AIRING.id to mal("anime/ranking?ranking_type=airing&limit=50&$f"),
                Chart.MAL_UPCOMING.id to mal("anime/ranking?ranking_type=upcoming&limit=50&$f"))
        }
        Src.KITSU -> {
            val d = Net.http.newCall(Request.Builder().url("https://kitsu.io/api/edge/trending/anime?limit=20").build()).execute()
                .use { r -> if (!r.isSuccessful) throw java.io.IOException("Kitsu HTTP ${r.code}"); JSONObject(r.body?.string().orEmpty()) }
            mapOf(Chart.KITSU_TRENDING.id to d.optJSONArray("data").objects().mapNotNull { o ->
                val a = o.optJSONObject("attributes") ?: return@mapNotNull null
                val name = a.optJSONObject("titles")?.optString("en")?.takeIf { it.isNotBlank() && it != "null" } ?: a.optString("canonicalTitle").ifBlank { null } ?: return@mapNotNull null
                MetaPreview("kitsu:${o.optString("id")}", if (a.optString("subtype") == "movie") "movie" else "series", name,
                    a.optJSONObject("posterImage")?.optString("large")?.takeIf { it.startsWith("http") },
                    a.optJSONObject("coverImage")?.optString("large")?.takeIf { it.startsWith("http") },
                    description = a.optString("synopsis").takeIf { it.isNotBlank() && it != "null" },
                    releaseInfo = a.optString("startDate").take(4).takeIf { it.length == 4 },
                    imdbRating = a.optString("averageRating").toDoubleOrNull()?.let { "%.1f".format(it / 10) })
            })
        }
        Src.LIVECHART -> null
    }

    private fun mal(path: String): List<MetaPreview> = Net.http.newCall(Request.Builder().url("https://api.myanimelist.net/v2/$path")
        .header("X-MAL-CLIENT-ID", BuildConfig.MAL_CLIENT_ID).build()).execute().use { r ->
        if (!r.isSuccessful) throw java.io.IOException("MAL HTTP ${r.code}")
        JSONObject(r.body?.string().orEmpty()).optJSONArray("data").objects().mapNotNull { e ->
            val n = e.optJSONObject("node") ?: return@mapNotNull null
            val id = n.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
            val name = n.optJSONObject("alternative_titles")?.optString("en")?.takeIf { it.isNotBlank() } ?: n.optString("title").ifBlank { null } ?: return@mapNotNull null
            MetaPreview("mal:$id", if (n.optString("media_type") == "movie") "movie" else "series", name,
                n.optJSONObject("main_picture")?.let { p -> p.optString("large").takeIf { it.startsWith("http") } ?: p.optString("medium").takeIf { it.startsWith("http") } },
                releaseInfo = n.optJSONObject("start_season")?.optInt("year")?.takeIf { it > 0 }?.toString(),
                imdbRating = n.optDouble("mean").takeIf { !it.isNaN() && it > 0 }?.let { "%.1f".format(it) },
                genres = n.optJSONArray("genres").objects().mapNotNull { it.optString("name").ifBlank { null } })
        }
    }

    /**
     * LiveChart.me's "aired in the last 24 h" RSS (~20 KB): one card per show, newest first. Its ids become AniList /
     * MAL / Kitsu ids through the anime-ids list; one AniList request then gives English titles, banners and scores.
     */
    private suspend fun liveChart(): Map<String, List<MetaPreview>> {
        val xml = Net.http.newCall(Request.Builder().url("https://www.livechart.me/feeds/episodes").header("User-Agent", "Mozilla/5.0").build())
            .execute().use { r -> if (!r.isSuccessful) throw java.io.IOException("LiveChart HTTP ${r.code}"); r.body?.string().orEmpty() }
        data class Ep(val lc: Int, val title: String, val ep: String?, val poster: String?)
        val seen = HashSet<Int>()
        val eps = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val b = m.groupValues[1]
            val lc = Regex("livechart\\.me/anime/(\\d+)").find(b)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val t = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1)?.let(::unXml) ?: return@mapNotNull null
            val ep = Regex("\\s#(\\d+)$").find(t)?.groupValues?.get(1)
            Ep(lc, if (ep != null) t.substringBeforeLast(" #") else t, ep, Regex("media:thumbnail url=\"([^\"]+)\"").find(b)?.groupValues?.get(1)?.replace("/small.jpg", "/large.jpg"))
        }.filter { seen.add(it.lc) }.toList()   // the feed is newest first: keep each show's latest episode
        val ids = com.lekathedon.visionlite.anime.AnimeIds.fromLivechart(eps.map { it.lc })
        val aniIds = ids.values.filter { it.startsWith("anilist:") }.mapNotNull { it.removePrefix("anilist:").toLongOrNull() }
        val ani = if (aniIds.isEmpty()) emptyMap() else runCatching {
            post("https://graphql.anilist.co", JSONObject().put("query", "query (\$ids: [Int]) { p: Page(perPage: 50) { ${AF.format("POPULARITY_DESC", ", id_in: \$ids")} } }")
                .put("variables", JSONObject().put("ids", JSONArray(aniIds))))?.let { d -> ani(d, "p").associateBy { it.id } }
        }.getOrNull().orEmpty()
        return mapOf(Chart.LIVECHART_AIRED.id to eps.mapNotNull { e ->
            val id = ids[e.lc] ?: return@mapNotNull null
            val epInfo = e.ep?.let { "Episode $it" }
            ani[id]?.copy(releaseInfo = epInfo ?: ani[id]?.releaseInfo) ?: MetaPreview(id, "series", e.title, e.poster, releaseInfo = epInfo)
        })
    }

    private fun unXml(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").trim()

    internal fun post(url: String, body: JSONObject): JSONObject? = Net.http.newCall(Request.Builder().url(url)
        .post(body.toString().toRequestBody("application/json".toMediaType())).header("Accept", "application/json")
        .apply { if (url == IMDB_URL) header("x-imdb-client-name", "imdb-web-next-localized") }.build()).execute().use { r ->
        val t = r.body?.string().orEmpty()
        if (!r.isSuccessful || !t.trimStart().startsWith("{")) throw java.io.IOException("HTTP ${r.code}")
        JSONObject(t).optJSONObject("data")
    }

    internal fun imdb(chart: JSONObject?, type: String): List<MetaPreview> = chart?.optJSONArray("edges").objects().mapNotNull { e ->
        val n = e.optJSONObject("node") ?: return@mapNotNull null
        val id = n.optString("id").takeIf { it.startsWith("tt") } ?: return@mapNotNull null
        MetaPreview(id, type, n.optJSONObject("titleText")?.optString("text")?.ifBlank { null } ?: return@mapNotNull null,
            n.optJSONObject("primaryImage")?.optString("url")?.takeIf { it.startsWith("http") }?.let { if ("._V1_" in it) it.substringBefore("._V1_") + "._V1_QL75_UX342_.jpg" else it }
                ?: "https://images.metahub.space/poster/medium/$id/img",
            "https://images.metahub.space/background/medium/$id/img",
            releaseInfo = n.optJSONObject("releaseYear")?.optInt("year")?.takeIf { it > 0 }?.toString(),
            imdbRating = n.optJSONObject("ratingsSummary")?.optDouble("aggregateRating")?.takeIf { !it.isNaN() && it > 0 }?.toString(),
            genres = n.optJSONObject("titleGenres")?.optJSONArray("genres").objects().mapNotNull { it.optJSONObject("genre")?.optString("text") })
    }

    internal fun ani(d: JSONObject, alias: String): List<MetaPreview> = d.optJSONObject(alias)?.optJSONArray("media").objects().mapNotNull { m ->
        val id = m.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
        val t = m.optJSONObject("title")
        val name = t?.optString("english")?.takeIf { it.isNotBlank() && it != "null" } ?: t?.optString("userPreferred")?.ifBlank { null } ?: return@mapNotNull null
        MetaPreview("anilist:$id", if (m.optString("format") == "MOVIE") "movie" else "series", name,
            m.optJSONObject("coverImage")?.let { c -> c.optString("extraLarge").takeIf { it.startsWith("http") } ?: c.optString("large").takeIf { it.startsWith("http") } },
            m.optString("bannerImage").takeIf { it.startsWith("http") },
            description = m.optString("description").takeIf { it.isNotBlank() && it != "null" }?.replace(Regex("<[^>]+>"), ""),
            releaseInfo = m.optInt("seasonYear").takeIf { it > 0 }?.toString(),
            imdbRating = m.optInt("averageScore").takeIf { it > 0 }?.let { "%.1f".format(it / 10f) },
            genres = m.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.optString(it) } }.orEmpty())
    }

    private fun season(d: LocalDate) = when (d.monthValue) { 12 -> "WINTER" to d.year + 1; 1, 2 -> "WINTER" to d.year; 3, 4, 5 -> "SPRING" to d.year; 6, 7, 8 -> "SUMMER" to d.year; else -> "FALL" to d.year }

    internal const val IMDB_URL = "https://api.graphql.imdb.com/"
    private const val F = "edges { node { id titleText { text } releaseYear { year } ratingsSummary { aggregateRating } primaryImage { url } titleGenres { genres { genre { text } } } } }"
    private const val IMDB_QUERY = "{ popMovies: chartTitles(first: 100, chart: {chartType: MOST_POPULAR_MOVIES}) { $F } " +
        "topMovies: chartTitles(first: 250, chart: {chartType: TOP_RATED_MOVIES}) { $F } " +
        "popTv: chartTitles(first: 100, chart: {chartType: MOST_POPULAR_TV_SHOWS}) { $F } " +
        "topTv: chartTitles(first: 250, chart: {chartType: TOP_RATED_TV_SHOWS}) { $F } }"
    private const val LF = "edges { node: title { id titleText { text } releaseYear { year } ratingsSummary { aggregateRating } primaryImage { url } titleGenres { genres { genre { text } } } } }"
    private const val SF = "edges { node { title { id titleText { text } releaseYear { year } ratingsSummary { aggregateRating voteCount } primaryImage { url } titleGenres { genres { genre { text } } } } } }"
    private const val AB = "genreConstraint: {allGenreIds: [\"Animation\"]}, titleTypeConstraint: {anyTitleTypeIds: [\"movie\"]}, userRatingsConstraint: {ratingsCountRange: {min: 2000}}"
    private const val IMDB_ANIME_QUERY = "{ animeSeries: list(id: \"ls088526366\") { titleListItemSearch(first: 250) { $LF } } " +
        "animeMovies: list(id: \"ls093468141\") { titleListItemSearch(first: 250) { $LF } } " +
        "animeTagged: advancedTitleSearch(first: 250, constraints: {$AB, keywordConstraint: {anyKeywords: [\"anime\"]}}, sort: {sortBy: USER_RATING, sortOrder: DESC}) { $SF } " +
        "animeJapan: advancedTitleSearch(first: 250, constraints: {$AB, originCountryConstraint: {anyPrimaryCountries: [\"JP\"], excludeCountries: [\"US\"]}}, sort: {sortBy: USER_RATING, sortOrder: DESC}) { $SF } }"
    private const val AF = "media(type: ANIME, sort: %s, isAdult: false%s) { id idMal format seasonYear title { english userPreferred } coverImage { extraLarge large } bannerImage genres averageScore description(asHtml: false) }"
    private val ANILIST_QUERY = "query (\$season: MediaSeason, \$year: Int, \$nextSeason: MediaSeason, \$nextYear: Int) { trending: Page(perPage: 50) { ${AF.format("TRENDING_DESC", "")} } " +
        "fresh: Page(perPage: 50) { ${AF.format("START_DATE_DESC", ", season: \$season, seasonYear: \$year, status: RELEASING, format_in: [TV, ONA], popularity_greater: 3000")} } " +
        "next: Page(perPage: 50) { ${AF.format("POPULARITY_DESC", ", season: \$nextSeason, seasonYear: \$nextYear")} } " +
        "season: Page(perPage: 50) { ${AF.format("POPULARITY_DESC", ", season: \$season, seasonYear: \$year")} } " +
        "todayTv: Page(perPage: 20) { ${AF.format("TRENDING_DESC", ", format_in: [TV, TV_SHORT, ONA]")} } " +
        "todayMovies: Page(perPage: 20) { ${AF.format("TRENDING_DESC", ", format: MOVIE")} } }"
}

// ------------------------------------------------------------------ Streaming services (TMDB)

/**
 * Vision's Streaming services row: one wide tile per service (its logo over a backdrop from its Originals, new each
 * day); OK opens its page of WuPlay's hub rows, all TMDB for the US (UK for ITVX/NOW). Needs your TMDB key; without
 * it the row stays away and nothing is asked.
 */
object Services {
    /** [anime]: an anime-only service, shown only in the Anime hub (the others only in Films & TV). [network] 0 = TMDB
     *  has no network for it: no Originals row; [logo] = its TMDB provider logo, backdrops from its catalogue. */
    enum class S(val id: String, val title: String, val network: Int, val providers: String, val region: String = "US",
                 val movies: Boolean = true, val series: Boolean = true, val originals: Boolean = true,
                 val anime: Boolean = false, val logo: String? = null) {
        NETFLIX("netflix", "Netflix", 213, "8|1796"), PRIME("prime", "Prime Video", 1024, "9|119"), DISNEY("disney", "Disney+", 2739, "337"),
        MAX("max", "HBO Max", 3186, "1899"), APPLE("apple", "Apple TV+", 2552, "350"), HULU("hulu", "Hulu", 453, "15"),
        PARAMOUNT("paramount", "Paramount+", 4330, "2303|2616|582|1853|531"), PEACOCK("peacock", "Peacock", 3353, "386|387"),
        SHUDDER("shudder", "Shudder", 2949, "99|2049", series = false),
        CRITERION("criterion", "The Criterion Channel", 6165, "258", series = false, originals = false), STARZ("starz", "Starz", 318, "43|1855", series = false),
        AMC("amcplus", "AMC+", 4661, "526|1854"), MGM("mgmplus", "MGM+", 6219, "34", series = false), BRITBOX("britbox", "BritBox / Acorn TV", 4025, "151|1852|87|2034", movies = false),
        DISCOVERY("discovery", "discovery+", 4353, "520"), ITVX("itvx", "ITVX", 5871, "41|2300", region = "GB"), NOW("now", "NOW", 1063, "39", region = "GB", movies = false),
        // Anime hub only (TMDB providers checked 2026-11-20: CR ~1,960 shows US, HIDIVE ~230, RetroCrush ~145, d Anime Store ~3,000 JP).
        // Rule (user 2026-11-21): every genuine anime service with a TMDB catalogue is in, intro clip or not (no clip =
        // still tile). Added: U-NEXT JP (~2,560 anime), ADN FR (455 shows/99 films), Anime Times JP (1,953/588),
        // Toei Animation JP (322/1,561), Anime Generation IT (195/94). Left out: Animebox ES (67), Animax IN (27).
        // Logos: TMDB company wordmarks; Anime Times / Anime Generation only have Prime Channels squares, so their name.
        CRUNCHYROLL("crunchyroll", "Crunchyroll", 1112, "283|1968", anime = true),
        HIDIVE("hidive", "HIDIVE", 0, "430|2390", originals = false, anime = true, logo = "/q62gD079BwFpLR7QiXEgjmLXkwP.png"),
        RETROCRUSH("retrocrush", "RetroCrush", 0, "446|295", originals = false, anime = true, logo = "/pqXjcTVXpOVHF3IfWo9siRZkLXo.png"),
        DANIME("danime", "d Anime Store", 0, "2494", region = "JP", originals = false, anime = true, logo = "/wR7YDBvNzdJRkmxOTgJobOpUG8X.png"),
        UNEXT("unext", "U-NEXT", 0, "84", region = "JP", originals = false, anime = true, logo = "/xhbd4zDn0iJ1vwj9TcSvtAFEc6w.png"),
        ADN("adn", "ADN", 0, "415|2173", region = "FR", originals = false, anime = true, logo = "/v2P6Z98qYTgkUtMecP5OaPmv1Pa.png"),
        ANIMETIMES("animetimes", "Anime Times", 0, "2182", region = "JP", originals = false, anime = true),
        TOEI("toei", "Toei Animation", 0, "2519|2520", region = "JP", originals = false, anime = true, logo = "/ayE4LIqoAWotavo7xdvYngwqGML.png"),
        ANIMEGEN("animegen", "Anime Generation", 0, "1895", region = "IT", originals = false, anime = true)
    }
    @Serializable data class Art(val id: String, val logo: String? = null, val backdrop: String? = null)
    @Serializable private data class Saved(val day: String = "", val logosAt: Long = 0, val art: List<Art> = emptyList())

    private val _art = MutableStateFlow<List<Art>>(emptyList())
    val art: StateFlow<List<Art>> get() = _art
    private val lock = Mutex()
    private val file get() = File(App.instance.filesDir, "services.json")

    fun on() = Tmdb.key() != null

    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (!lock.tryLock()) return@withContext
        try {
            val saved = runCatching { Net.json.decodeFromString(Saved.serializer(), file.readText()) }.getOrDefault(Saved())
            if (_art.value.isEmpty()) _art.value = saved.art
            if (!on()) return@withContext
            val today = LocalDate.now().toString()
            if (saved.day == today && saved.art.size == S.entries.size) return@withContext
            val logosOld = System.currentTimeMillis() - saved.logosAt > 30L * 24 * 3600_000L
            val fresh = coroutineScope {
                S.entries.map { s -> async {
                    val kept = saved.art.firstOrNull { it.id == s.id }
                    val logo = s.logo ?: if (s.network == 0) null else if (!logosOld && kept?.logo != null) kept.logo else Tmdb.get("network/${s.network}")?.optString("logo_path")?.ifBlank { null } ?: kept?.logo
                    val backs = (if (s.network == 0) Tmdb.get("discover/tv?sort_by=popularity.desc&watch_region=${s.region}&with_watch_providers=${s.providers}")
                        else Tmdb.get("discover/tv?sort_by=popularity.desc&with_networks=${s.network}"))?.optJSONArray("results").objects()
                        .mapNotNull { it.optString("backdrop_path").takeIf { p -> p.startsWith("/") } }
                        .ifEmpty { Tmdb.get("discover/movie?sort_by=popularity.desc&watch_region=${s.region}&with_watch_providers=${s.providers}")?.optJSONArray("results").objects()
                            .mapNotNull { it.optString("backdrop_path").takeIf { p -> p.startsWith("/") } } }
                    Art(s.id, logo, backs.randomOrNull() ?: kept?.backdrop)
                } }.awaitAll()
            }
            _art.value = fresh
            runCatching { file.writeText(Net.json.encodeToString(Saved.serializer(), Saved(today, if (logosOld) System.currentTimeMillis() else saved.logosAt, fresh))) }
        } finally { lock.unlock() }
    }

    /** The service page's rows: (title, loader for page n). */
    fun rows(s: S): List<Pair<String, suspend (Int) -> List<MetaPreview>>> {
        val today = LocalDate.now().toString()
        fun disc(kind: String, sort: String, votes: Int? = null, released: Boolean = false): suspend (Int) -> List<MetaPreview> = { page ->
            withContext(Dispatchers.IO) {
                val date = if (!released) "" else if (kind == "tv") "&first_air_date.lte=$today" else "&release_date.lte=$today"
                Tmdb.items(Tmdb.get("discover/$kind?sort_by=$sort&watch_region=${s.region}&with_watch_providers=${s.providers}&page=$page" +
                    (votes?.let { "&vote_count.gte=$it" } ?: "") + date), kind == "tv")
            }
        }
        val origLoad: suspend (Int) -> List<MetaPreview> = { page ->
            withContext(Dispatchers.IO) { Tmdb.items(Tmdb.get("discover/tv?sort_by=popularity.desc&with_networks=${s.network}&page=$page"), true) }
        }
        val originals = (if (s == S.NOW) "Sky Originals" else "${s.title} Originals") to origLoad
        return listOfNotNull(
            originals.takeIf { s.originals },
            ("Latest Movies" to disc("movie", "primary_release_date.desc", 10, true)).takeIf { s.movies },
            ("Latest Series" to disc("tv", "first_air_date.desc", 10, true)).takeIf { s.series },
            ("Popular Movies" to disc("movie", "popularity.desc")).takeIf { s.movies },
            ("Popular Series" to disc("tv", "popularity.desc")).takeIf { s.series },
            ("Top Rated Movies" to disc("movie", "vote_average.desc", 300)).takeIf { s.movies },
            ("Top Rated Series" to disc("tv", "vote_average.desc", 200)).takeIf { s.series },
            ("Most Rated Movies" to disc("movie", "vote_count.desc")).takeIf { s.movies },
            ("Most Rated Series" to disc("tv", "vote_count.desc")).takeIf { s.series }
        )
    }
}

// ------------------------------------------------------------------ Favourite people (IMDb, keyless)

/**
 * Favourite people: actors and directors you follow; their tile opens their films and shows (IMDb, no key). Add one
 * from a title's cast. Your Vision list is taken over once if it was copied to the app's import folder.
 */
object People {
    /** [hub]: the hub they were followed in ("NUVIO" = Films & TV, "ANIME"); each hub's row shows only its own. */
    @Serializable data class Person(val id: String, val name: String, val photo: String? = null, val hub: String = "NUVIO")
    private val ser = ListSerializer(Person.serializer())
    private val _list = MutableStateFlow(read())
    val list: StateFlow<List<Person>> get() = _list

    private fun read(): List<Person> {
        val p = App.instance.prefs.sp
        p.getString("people", null)?.let { return runCatching { Net.json.decodeFromString(ser, it) }.getOrDefault(emptyList()) }
        // Once: Vision's list, if it was copied to Android/data/<app>/files/import/people.json.
        val f = File(App.instance.getExternalFilesDir("import"), "people.json")
        val imported = runCatching { Net.json.decodeFromString(ser, f.readText()) }.getOrNull() ?: return emptyList()
        p.edit().putString("people", Net.json.encodeToString(ser, imported)).apply()
        return imported
    }

    fun has(id: String, hub: String) = _list.value.any { it.id == id && it.hub == hub }
    /** Follow / unfollow [person] in their [Person.hub]; the same person can be followed in both hubs. */
    fun toggle(person: Person) {
        val l = if (has(person.id, person.hub)) _list.value.filter { !(it.id == person.id && it.hub == person.hub) } else _list.value + person
        _list.value = l; App.instance.prefs.sp.edit().putString("people", Net.json.encodeToString(ser, l)).apply()
    }

    /** IMDb's person for a name (best match), for a title's cast. */
    suspend fun find(name: String): Person? = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(name.lowercase(), "UTF-8").replace("+", "%20")
        runCatching {
            JSONObject(Net.get("https://v3.sg.media-imdb.com/suggestion/x/$q.json", 10)).optJSONArray("d").objects()
                .firstOrNull { it.optString("id").startsWith("nm") }?.let { Person(it.optString("id"), it.optString("l"), it.optJSONObject("i")?.optString("imageUrl")?.let(::small)) }
        }.getOrNull()
    }

    /** A person's titles, newest first (IMDb's credits; known-for when credits don't answer). */
    suspend fun titles(id: String): Pair<Person?, List<MetaPreview>> = withContext(Dispatchers.IO) {
        val node = "title { id titleText { text } releaseYear { year } titleType { id } ratingsSummary { aggregateRating } primaryImage { url } }"
        val q = "{ name(id: \"$id\") { nameText { text } primaryImage { url } " +
            "credits(first: 250, filter: {categories: [\"actor\", \"actress\", \"director\"]}) { edges { node { $node } } } " +
            "knownFor(first: 30) { edges { node { $node } } } } }"
        val d = runCatching {
            Net.http.newCall(Request.Builder().url("https://api.graphql.imdb.com/").post(JSONObject().put("query", q).toString().toRequestBody("application/json".toMediaType()))
                .header("x-imdb-client-name", "imdb-web-next-localized").build()).execute().use { JSONObject(it.body!!.string()).optJSONObject("data")?.optJSONObject("name") }
        }.getOrNull() ?: return@withContext null to emptyList()
        val person = Person(id, d.optJSONObject("nameText")?.optString("text").orEmpty(), d.optJSONObject("primaryImage")?.optString("url")?.let(::small))
        fun list(k: String) = d.optJSONObject(k)?.optJSONArray("edges").objects().mapNotNull { e ->
            val t = e.optJSONObject("node")?.optJSONObject("title") ?: return@mapNotNull null
            val tid = t.optString("id").takeIf { it.startsWith("tt") } ?: return@mapNotNull null
            val kind = t.optJSONObject("titleType")?.optString("id").orEmpty()
            if (kind !in setOf("movie", "tvSeries", "tvMiniSeries", "tvMovie")) return@mapNotNull null
            MetaPreview(tid, if (kind.startsWith("tvS") || kind == "tvMiniSeries") "series" else "movie", t.optJSONObject("titleText")?.optString("text") ?: return@mapNotNull null,
                t.optJSONObject("primaryImage")?.optString("url")?.let(::small) ?: "https://images.metahub.space/poster/medium/$tid/img",
                "https://images.metahub.space/background/medium/$tid/img",
                releaseInfo = t.optJSONObject("releaseYear")?.optInt("year")?.takeIf { it > 0 }?.toString(),
                imdbRating = t.optJSONObject("ratingsSummary")?.optDouble("aggregateRating")?.takeIf { !it.isNaN() && it > 0 }?.toString())
        }
        val all = (list("credits").ifEmpty { list("knownFor") }).distinctBy { it.id }.sortedByDescending { it.releaseInfo?.toIntOrNull() ?: 0 }
        person to all
    }

    private fun small(url: String) = if ("._V1_" in url) url.substringBefore("._V1_") + "._V1_QL75_UX342_.jpg" else url
}
