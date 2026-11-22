// Genres and Decades the WuPlay way (Vision's BrowseHubs + BrowseRepository, the lite way): WuPlay 0.9.4's 19 genres
// (TMDB genre + keyword ids, anime left out) and 9 decades from assets/browse_hubs.json; each genre's page = Popular,
// Latest, Most Rated, then its subgenres; each decade's = Popular, Most Rated, then the genres within it. Movies and
// shows come mixed in each rail (or one kind, by the pills). Needs your TMDB key, like the services' pages.
package com.lekathedon.visionlite.browse

import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.addons.MetaPreview
import com.lekathedon.visionlite.home.Tmdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

object Wuplay {
    enum class Sort { POPULAR, LATEST, MOST_RATED }
    enum class Media { ALL, MOVIES, SHOWS }

    /** One rail: what it asks TMDB for. */
    data class Rail(val key: String, val title: String, val sort: Sort = Sort.POPULAR, val genre: Int? = null,
                    val keywords: List<Int> = emptyList(), val from: Int? = null, val to: Int? = null, val only: Media? = null)
    data class Genre(val slug: String, val name: String, val genre: Int, val rails: List<Rail>)
    data class Decade(val slug: String, val name: String, val from: Int?, val to: Int, val rails: List<Rail>)

    private class Data(val genres: List<Genre>, val decades: List<Decade>)
    @Volatile private var loaded: Data? = null
    private fun data(): Data = loaded ?: synchronized(this) { loaded ?: parse(App.instance.assets.open("browse_hubs.json").bufferedReader().use { it.readText() }).also { loaded = it } }

    val genres: List<Genre> get() = data().genres
    val decades: List<Decade> get() = data().decades
    fun genre(slug: String) = genres.firstOrNull { it.slug == slug }
    fun decade(slug: String) = decades.firstOrNull { it.slug == slug }

    private fun parse(text: String): Data {
        val root = JSONObject(text)
        val names = HashMap<String, Pair<String, Int>>()
        val genres = root.getJSONArray("genres").objs().map { g ->
            val slug = g.getString("slug"); val name = g.getString("name"); val id = g.getInt("genre")
            names[slug] = name to id
            val base = listOf(Sort.POPULAR to "Popular", Sort.LATEST to "Latest", Sort.MOST_RATED to "Most Rated").map { (s, t) -> Rail("$slug:${s.name}", t, s, id) }
            val subs = g.getJSONArray("rails").objs().map { r ->
                Rail("$slug:${r.getString("name")}", r.getString("name"), Sort.POPULAR, id,
                    r.optJSONArray("keywords")?.let { k -> (0 until k.length()).map { k.getInt(it) } }.orEmpty(),
                    only = when (r.optString("media")) { "movie" -> Media.MOVIES; "series" -> Media.SHOWS; else -> null })
            }
            Genre(slug, name, id, base + subs)
        }
        val decades = root.getJSONArray("decades").objs().map { d ->
            val slug = d.getString("slug"); val name = d.getString("name")
            val from = if (d.isNull("from")) null else d.getInt("from"); val to = d.getInt("to")
            val base = listOf(Sort.POPULAR to "Popular", Sort.MOST_RATED to "Most Rated").map { (s, t) -> Rail("$slug:${s.name}", t, s, from = from, to = to) }
            val byGenre = d.getJSONArray("genres").let { a -> (0 until a.length()).map { a.getString(it) } }.mapNotNull { gs ->
                val (gn, gid) = names[gs] ?: return@mapNotNull null
                Rail("$slug:$gs", gn, Sort.POPULAR, gid, from = from, to = to)
            }
            Decade(slug, if (from == null) "Before 1950" else name, from, to, base + byGenre)
        }
        return Data(genres, decades)
    }

    // ---- pages (TMDB discover; the last 120 pages kept) ----

    private val cache = object : LinkedHashMap<String, List<MetaPreview>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MetaPreview>>?) = size > 120
    }
    private class Scored(val m: MetaPreview, val pop: Double, val date: String, val votes: Int)

    /** Page [page] (1-based) of [rail], for [media]; empty when there's no more (or no TMDB key). */
    suspend fun page(rail: Rail, media: Media, page: Int): List<MetaPreview> {
        val eff = when { rail.only == null -> media; media == Media.ALL || media == rail.only -> rail.only!!; else -> return emptyList() }
        val key = "${rail.key}|${rail.from}-${rail.to}|$eff|$page"
        synchronized(cache) { cache[key] }?.let { return it }
        val list = withContext(Dispatchers.IO) {
            when (eff) {
                Media.MOVIES -> discover(rail, false, page).map { it.m }
                Media.SHOWS -> discover(rail, true, page).map { it.m }
                Media.ALL -> coroutineScope {
                    val m = async { discover(rail, false, page) }; val s = async { discover(rail, true, page) }
                    (m.await() + s.await()).sortedWith(when (rail.sort) {
                        Sort.POPULAR -> compareByDescending { it.pop }
                        Sort.LATEST -> compareByDescending { it.date }
                        Sort.MOST_RATED -> compareByDescending { it.votes }
                    }).map { it.m }
                }
            }
        }
        if (list.isNotEmpty()) synchronized(cache) { cache[key] = list }
        return list
    }

    private fun discover(rail: Rail, tv: Boolean, page: Int): List<Scored> {
        val genre = if (!tv) rail.genre else rail.genre?.let { TV_GENRE[it] ?: it.takeIf { g -> g in TV_GENRES } }
        // A movie-only genre (Horror, Thriller, Romance…) has no TV genre: only its subgenres' keywords say anything there.
        if (tv && rail.genre != null && genre == null && rail.keywords.isEmpty()) return emptyList()
        val today = LocalDate.now().toString()
        val dateKey = if (tv) "first_air_date" else "primary_release_date"
        val q = StringBuilder(if (tv) "discover/tv" else "discover/movie").append("?include_adult=false&language=en-US&page=").append(page)
        q.append("&sort_by=").append(when (rail.sort) { Sort.POPULAR -> "popularity.desc"; Sort.LATEST -> "$dateKey.desc"; Sort.MOST_RATED -> "vote_count.desc" })
        genre?.let { q.append("&with_genres=").append(it) }
        if (rail.keywords.isNotEmpty()) q.append("&with_keywords=").append(rail.keywords.joinToString("%7C"))
        rail.from?.let { q.append("&$dateKey.gte=").append(it).append("-01-01") }
        q.append("&$dateKey.lte=").append(rail.to?.let { minOf("$it-12-31", today) } ?: today)
        // Popular: known titles first (TMDB's popularity alone puts this week's unknown releases on top), as WuPlay's are.
        when (rail.sort) {
            Sort.POPULAR -> q.append("&vote_count.gte=").append(if (tv) 60 else 150)
            Sort.LATEST -> q.append("&vote_count.gte=20")
            Sort.MOST_RATED -> {}
        }
        // Hide adult titles (when on): TMDB leaves the explicit keywords out, so these titles need no check of their own.
        val adult = com.lekathedon.visionlite.recs.Adult.tmdbWithout()
        q.append(adult)
        val o = Tmdb.get(q.toString()) ?: return emptyList()
        val results = o.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val r = results.optJSONObject(i) ?: return@mapNotNull null
            if (r.optString("poster_path").isBlank()) return@mapNotNull null
            val m = Tmdb.items(JSONObject().put("results", JSONArray().put(r)), tv).firstOrNull() ?: return@mapNotNull null
            Scored(m, r.optDouble("popularity", 0.0), r.optString(if (tv) "first_air_date" else "release_date"), r.optInt("vote_count"))
        }.also { l -> if (adult.isNotEmpty()) com.lekathedon.visionlite.recs.Adult.clean(l.map { it.m.id }) }
    }

    // ---- tile pictures: each genre's / decade's highest-rated title (no two tiles the same; kept a week) ----

    private val _art = MutableStateFlow<Map<String, String>>(emptyMap())
    val art: StateFlow<Map<String, String>> get() = _art
    private val artLock = Mutex()
    private val artFile get() = File(App.instance.filesDir, "browse_art.json")
    private const val ART_FRESH_MS = 7 * 24 * 3600_000L
    /** 2 = highest rated (was: most popular). A change of version or of Hide adult titles asks every tile again. */
    private const val ART_V = 3

    /** Highest-rated titles for a tile, best first: (TMDB id, backdrop). Hide adult titles leaves the explicit ones out. */
    private fun topRated(genre: Int?, from: Int?, to: Int?, tv: Boolean, votes: Int): List<Pair<String, String>> {
        val g = if (!tv) genre else genre?.let { TV_GENRE[it] ?: it.takeIf { x -> x in TV_GENRES } ?: return emptyList() }
        val dateKey = if (tv) "first_air_date" else "primary_release_date"
        val q = StringBuilder(if (tv) "discover/tv" else "discover/movie").append("?include_adult=false&language=en-US&sort_by=vote_average.desc&vote_count.gte=").append(votes)
        g?.let { q.append("&with_genres=").append(it) }
        from?.let { q.append("&$dateKey.gte=").append(it).append("-01-01") }
        to?.let { q.append("&$dateKey.lte=").append(minOf("$it-12-31", LocalDate.now().toString())) }
        q.append(com.lekathedon.visionlite.recs.Adult.tmdbWithout())
        val results = Tmdb.get(q.toString())?.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val r = results.optJSONObject(i) ?: return@mapNotNull null
            if (r.optBoolean("adult")) return@mapNotNull null
            // Strictly Films & TV: nothing anime behind any tile (the anime list, or Japanese / Chinese / Korean animation).
            if (isAnimeResult(r, tv)) return@mapNotNull null
            val b = r.optString("backdrop_path").takeIf { it.startsWith("/") } ?: return@mapNotNull null
            (if (tv) "tv:" else "movie:") + r.optInt("id") to Tmdb.img(b, "w1280")!!
        }
    }

    /** True for a TMDB result the anime list knows, or animation (genre 16) made in Japanese, Chinese or Korean. */
    internal fun isAnimeResult(r: JSONObject, tv: Boolean): Boolean {
        if (com.lekathedon.visionlite.anime.AnimeIds.isAnime("tmdb:${if (tv) "tv" else "movie"}:${r.optInt("id")}")) return true
        val g = r.optJSONArray("genre_ids")
        val animated = g != null && (0 until g.length()).any { g.optInt(it) == 16 }
        return animated && r.optString("original_language") in setOf("ja", "zh", "ko", "cn")
    }

    /** Loads the tiles' pictures (disk first; asked again weekly, or when the rule or Hide adult titles changed). Needs the TMDB key. */
    suspend fun loadArt() = withContext(Dispatchers.IO) {
        com.lekathedon.visionlite.anime.AnimeIds.warm()
        artLock.withLock {
            val disk = runCatching { JSONObject(artFile.readText()) }.getOrNull()
            val at = disk?.optLong("at") ?: 0L
            val adult = com.lekathedon.visionlite.recs.Adult.on()
            val same = disk != null && disk.optInt("v") == ART_V && disk.optBoolean("adult") == adult
            val have = disk?.optJSONObject("art")?.let { a -> a.keys().asSequence().associateWith { a.getString(it) } }.orEmpty()
            // Old pictures (another rule, or adult titles shown then) are never shown again.
            if (have.isNotEmpty() && same) _art.value = have
            if (Tmdb.key() == null || (same && have.size >= genres.size + decades.size && System.currentTimeMillis() - at < ART_FRESH_MS)) return@withLock
            // Each tile's list asked at once (28 small calls), then handed out in order: a title already behind a tile
            // goes to the next in line (Action and Adventure never share one).
            val asks = coroutineScope {
                (genres.map { g -> "genre:${g.slug}" to async {
                    runCatching { topRated(g.genre, null, null, false, 3000).ifEmpty { topRated(g.genre, null, null, true, 500) } }.getOrDefault(emptyList())
                } } + decades.map { d -> "decade:${d.slug}" to async {
                    val votes = if ((d.from ?: 0) < 1970) 800 else 2000
                    runCatching { topRated(null, d.from, d.to, false, votes).ifEmpty { topRated(null, d.from, d.to, true, 200) } }.getOrDefault(emptyList())
                } }).map { (id, job) -> id to job.await() }
            }
            val used = HashSet<String>()
            val fresh = HashMap<String, String>()
            asks.forEach { (id, list) -> list.firstOrNull { used.add(it.first) }?.let { fresh[id] = it.second } ?: (if (same) have[id] else null)?.let { fresh[id] = it } }
            _art.value = fresh
            runCatching { artFile.writeText(JSONObject().put("at", System.currentTimeMillis()).put("v", ART_V).put("adult", adult).put("art", JSONObject(fresh as Map<*, *>)).toString()) }
        }
    }

    /** Movie genres that are called something else on TV. */
    private val TV_GENRE = mapOf(28 to 10759, 12 to 10759, 878 to 10765, 14 to 10765, 10752 to 10768)
    /** Genres TV shares with movies (same id), plus TV's own Reality. */
    private val TV_GENRES = setOf(16, 35, 80, 99, 18, 10751, 9648, 37, 10764)

    private fun JSONArray.objs(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
