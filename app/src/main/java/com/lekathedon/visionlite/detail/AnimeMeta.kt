package com.lekathedon.visionlite.detail

import com.lekathedon.visionlite.addons.Meta
import com.lekathedon.visionlite.addons.Video
import com.lekathedon.visionlite.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Details for anime cards that have no IMDb id (AniList / MAL / Kitsu ids) and no anime meta add-on: ani.zip
 * (ids, episodes with titles/art/air dates, TVDB art) + AniList (plot, genres, score, banner). Keyless, 2 requests,
 * only when such a page opens. Episodes are named "kitsu:K:E" (what anime stream add-ons and Vision TB understand),
 * MAL / AniList ids when Kitsu has none.
 */
object AnimeMeta {
    fun isAnimeId(id: String) = id.startsWith("anilist:") || id.startsWith("mal:") || id.startsWith("kitsu:")

    private val cache = HashMap<String, Meta?>()

    suspend fun meta(id: String, type: String): Meta? = withContext(Dispatchers.IO) {
        val base = id.split(':').take(2).joinToString(":")
        synchronized(cache) { if (cache.containsKey(base)) return@withContext cache[base] }
        val m = runCatching { load(base, type) }.getOrNull()
        if (m != null) synchronized(cache) { cache[base] = m }
        m
    }

    private val zips = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()

    /** ani.zip's mapping for an anime id (ids, titles, episodes), kept for the run. */
    private suspend fun zip(id: String): JSONObject? {
        zips[id]?.let { return it }
        val num = id.substringAfter(':')
        val q = when {
            id.startsWith("mal:") -> "mal_id=$num"
            id.startsWith("kitsu:") -> "kitsu_id=$num"
            else -> "anilist_id=$num"
        }
        return runCatching { JSONObject(Net.get("https://api.ani.zip/mappings?$q", 10)) }.getOrNull()?.also { zips[id] = it }
    }

    /**
     * IMDb-style grouping (user 2026-11-26): an anime season listed on its own (MAL / AniList / Kitsu each list every
     * season as a show) belongs to its IMDb show. Returns that show's id and the season this entry is (TVDB's number,
     * as ani.zip gives it), or null for films and entries IMDb doesn't know. Same one ani.zip request as the page.
     */
    suspend fun parentOf(id: String, cardName: String? = null): Pair<String, Int?>? =
        // Runs on its own: a page that stops waiting (10 s) leaves the lookup to finish and be kept for the next open.
        lookups.getOrPut(id) { bg.async { runCatching { parentOfNow(id, cardName) }.getOrNull() } }.await().also { lookups.remove(id) }
    private val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val lookups = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Pair<String, Int?>?>>()

    private suspend fun parentOfNow(id: String, cardName: String?): Pair<String, Int?>? = withContext(Dispatchers.IO) {
        val base = id.split(':').take(2).joinToString(":")
        val z = zip(base) ?: return@withContext null
        val maps = z.optJSONObject("mappings") ?: return@withContext null
        if (maps.optString("type").equals("MOVIE", true)) return@withContext null
        val eps = z.optJSONObject("episodes")
        val season = eps?.optJSONObject("1")?.optInt("seasonNumber", 0)?.takeIf { it > 0 }
        val imdb = imdbFor(base, z, maps, cardName) ?: return@withContext null
        imdb to (season ?: z.optJSONObject("titles")?.optString("en")?.let { Regex("(?i)season\\s*(\\d+)|(\\d+)(st|nd|rd|th)\\s*season").find(it) }
            ?.let { (it.groupValues[1].ifEmpty { it.groupValues[2] }).toIntOrNull() })
    }

    /** Found show per anime id, kept across runs (prefs "anime_parent2"; v1 dropped 2026-11-26, it held prequel-chain guesses): one lookup per anime, ever. */
    private fun saved(): MutableMap<String, String> = runCatching {
        JSONObject(com.lekathedon.visionlite.App.instance.prefs.sp.getString("anime_parent2", "{}")!!).let { o -> o.keys().asSequence().associateWith { o.getString(it) }.toMutableMap() }
    }.getOrDefault(HashMap())
    private fun remember(id: String, imdb: String) {
        val m = saved(); m[id] = imdb
        com.lekathedon.visionlite.App.instance.prefs.sp.edit().putString("anime_parent2", JSONObject(m as Map<*, *>).toString()).apply()
    }

    /**
     * The IMDb show of ANY anime entry, from any list (AniList, MAL, Kitsu, Simkl, charts...), never "a new show"
     * (user 2026-11-26: every season / list entry opens the one IMDb-style show with all its seasons). In order:
     * 1) this entry's ids: ani.zip's IMDb id, then its TVDB / TMDB id (your Trakt shows, Trakt's id lookup, TMDB);
     * 2) the same for each earlier season (AniList PREQUEL chain up to season 1);
     * 3) IMDb's own search for season 1's name (season words stripped), a series of that year (±1).
     * Each answer is kept for good, so a title is looked up once.
     */
    private suspend fun imdbFor(base: String, z: JSONObject, maps: JSONObject, cardName: String? = null): String? {
        saved()[base]?.let { return it }
        val watched = Mine()
        idsToImdb(maps, watched)?.let { remember(base, it); return it }
        // IMDb first (user 2026-11-26): a spin-off / new series IMDb lists as its own show (Dragon Ball Super: Beerus)
        // stays its own show; only a season IMDb doesn't list on its own goes on to its earlier seasons.
        val al0 = maps.optInt("anilist_id").takeIf { it > 0 }
        val own = al0?.let { alInfo(it) }
        val ownNames = listOfNotNull(cardName?.takeIf { it.isNotBlank() }, own?.first?.first, own?.first?.second)
        imdbSearch(z, watched, ownNames, own?.second)?.let { remember(base, it); return it }
        // Earlier seasons: their mappings often carry the ids a new season doesn't have yet.
        var root = z
        var al = maps.optInt("anilist_id").takeIf { it > 0 }
        for (hop in 0 until 8) {
            val prev = al?.let { prequel(it) } ?: break
            val pz = zip("anilist:$prev") ?: break
            root = pz; al = prev
            pz.optJSONObject("mappings")?.let { m -> idsToImdb(m, watched)?.let { remember(base, it); return it } }
        }
        return imdbSearch(root, watched)?.also { remember(base, it) }
    }

    private suspend fun idsToImdb(maps: JSONObject, watched: Mine): String? {
        maps.optString("imdb_id").takeIf { it.startsWith("tt") }?.let { return it }
        val tvdb = maps.optInt("thetvdb_id").takeIf { it > 0 }
        val tmdb = maps.optString("themoviedb_id").toIntOrNull()?.takeIf { it > 0 }
        if (tvdb == null && tmdb == null) return null
        val trakt = com.lekathedon.visionlite.App.instance.trakt
        return (watched.get().firstOrNull { s -> (tvdb != null && s.ids.tvdb == tvdb.toLong()) || (tmdb != null && s.ids.tmdb == tmdb.toLong()) }?.ids?.imdb
            ?: tvdb?.let { trakt.imdbByExternal("tvdb", "$it") }
            ?: tmdb?.let { trakt.imdbByExternal("tmdb", "$it") }
            ?: tmdb?.let { com.lekathedon.visionlite.home.Tmdb.imdbOf("tmdb:tv:$it") })?.takeIf { it.startsWith("tt") }
    }

    /** Your Trakt watched shows, read once per lookup and only if needed. */
    private class Mine {
        private var v: List<com.lekathedon.visionlite.trakt.TShow>? = null
        suspend fun get() = v ?: runCatching { com.lekathedon.visionlite.App.instance.trakt.watchedShows() }.getOrNull().orEmpty().map { it.show }.also { v = it }
    }

    /** AniList (english, romaji) title and start year of [id]: names IMDb can be asked for when ani.zip has none. */
    private fun alInfo(id: Int): Pair<Pair<String?, String?>, Int?>? {
        val query = "{ Media(id: $id, type: ANIME) { title { english romaji } startDate { year } } }"
        val req = Request.Builder().url("https://graphql.anilist.co").header("Accept", "application/json")
            .post(JSONObject().put("query", query).toString().toRequestBody("application/json".toMediaType())).build()
        return runCatching {
            Net.http.newCall(req).execute().use { r ->
                val m = JSONObject(r.body!!.string()).optJSONObject("data")?.optJSONObject("Media") ?: return null
                val t = m.optJSONObject("title")
                fun s(k: String) = t?.optString(k)?.takeIf { it.isNotBlank() && it != "null" }
                (s("english") to s("romaji")) to m.optJSONObject("startDate")?.optInt("year")?.takeIf { it > 0 }
            }
        }.getOrNull()
    }

    /** AniList id of the season before [id] (TV / TV_SHORT / ONA only), or null. */
    private fun prequel(id: Int): Int? {
        val query = "{ Media(id: $id, type: ANIME) { relations { edges { relationType node { id type format } } } } }"
        val req = Request.Builder().url("https://graphql.anilist.co").header("Accept", "application/json")
            .post(JSONObject().put("query", query).toString().toRequestBody("application/json".toMediaType())).build()
        return runCatching {
            Net.http.newCall(req).execute().use { r ->
                val edges = JSONObject(r.body!!.string()).optJSONObject("data")?.optJSONObject("Media")?.optJSONObject("relations")?.optJSONArray("edges") ?: return null
                (0 until edges.length()).mapNotNull { edges.optJSONObject(it) }.firstOrNull { e ->
                    val n = e.optJSONObject("node")
                    e.optString("relationType") == "PREQUEL" && n?.optString("type") == "ANIME" && n.optString("format") in setOf("TV", "TV_SHORT", "ONA")
                }?.optJSONObject("node")?.optInt("id")?.takeIf { it > 0 }
            }
        }.getOrNull()
    }

    private val seasonWords = Regex("(?i)\\s*[:(\\-]?\\s*(season\\s*\\d+|\\d+(st|nd|rd|th)\\s*season|part\\s*\\d+|cour\\s*\\d+|final season)\\)?.*$")

    /** IMDb's search for the first season's names: a series with the same name, started that year (±1). */
    private suspend fun imdbSearch(z: JSONObject, watched: Mine, extra: List<String> = emptyList(), extraYear: Int? = null): String? = withContext(Dispatchers.IO) {
        val t = z.optJSONObject("titles")
        val names = (listOfNotNull(t?.optString("en"), t?.optString("x-jat")) + extra).map { it.replace(seasonWords, "") }.filter { it.isNotBlank() && it != "null" }.distinct()
        if (names.isEmpty()) return@withContext null
        val year = z.optJSONObject("episodes")?.optJSONObject("1")?.optString("airDateUtc")?.take(4)?.toIntOrNull() ?: extraYear
        for (n in names) {
            val q = java.net.URLEncoder.encode(n.trim().lowercase(), "UTF-8").replace("+", "%20")
            val hit = runCatching { JSONObject(Net.get("https://v3.sg.media-imdb.com/suggestion/x/$q.json", 10)).optJSONArray("d") }.getOrNull() ?: continue
            (0 until hit.length()).mapNotNull { hit.optJSONObject(it) }.firstOrNull { h ->
                h.optString("id").startsWith("tt") && h.optString("qid") in setOf("tvSeries", "tvMiniSeries") && norm(h.optString("l")) == norm(n) &&
                    (year == null || h.optInt("y", 0).let { y -> y == 0 || kotlin.math.abs(y - year) <= 1 })
            }?.let { return@withContext it.optString("id") }
        }
        // Then your own Trakt shows (same name): after IMDb, since reading that list can take seconds.
        watched.get().firstOrNull { w -> names.any { norm(it) == norm(w.title) } && (year == null || w.year == null || kotlin.math.abs(w.year - year) <= 1) }?.ids?.imdb
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private suspend fun load(id: String, type: String): Meta? {
        val num = id.substringAfter(':')
        val z = zip(id)
        val maps = z?.optJSONObject("mappings")
        val anilist = maps?.optInt("anilist_id")?.takeIf { it > 0 } ?: num.toIntOrNull()?.takeIf { id.startsWith("anilist:") }
        val malId = maps?.optInt("mal_id")?.takeIf { it > 0 } ?: num.toIntOrNull()?.takeIf { id.startsWith("mal:") }
        val kitsu = maps?.optInt("kitsu_id")?.takeIf { it > 0 } ?: num.toIntOrNull()?.takeIf { id.startsWith("kitsu:") }
        val a = anilist(anilist, if (anilist == null) malId else null)

        val playBase = kitsu?.let { "kitsu:$it" } ?: malId?.let { "mal:$it" } ?: (a?.optInt("id")?.takeIf { it > 0 } ?: anilist)?.let { "anilist:$it" } ?: id
        val titles = z?.optJSONObject("titles")
        val at = a?.optJSONObject("title")
        val name = listOfNotNull(at?.optString("english"), titles?.optString("en"), at?.optString("romaji"), titles?.optString("x-jat"))
            .firstOrNull { it.isNotBlank() && it != "null" } ?: return null
        fun img(kind: String) = z?.optJSONArray("images")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { it.optString("coverType") == kind }?.optString("url")
        }?.takeIf { it.isNotBlank() && !it.contains("/blank/") }
        val format = a?.optString("format").orEmpty()
        val isMovie = format == "MOVIE" || maps?.optString("type") == "MOVIE" || type == "movie"

        val eps = z?.optJSONObject("episodes")
        val videos = if (isMovie || eps == null) emptyList() else eps.keys().asSequence().mapNotNull { k ->
            val n = k.toIntOrNull() ?: return@mapNotNull null          // "S1" etc. = specials, left out
            val e = eps.optJSONObject(k) ?: return@mapNotNull null
            val t = e.optJSONObject("title")
            Video("$playBase:$n", title = t?.optString("en")?.takeIf { it.isNotBlank() } ?: t?.optString("x-jat")?.takeIf { it.isNotBlank() },
                season = 1, episode = n,
                released = e.optString("airDateUtc").takeIf { it.isNotBlank() }?.let { if (it.endsWith("Z")) it else null },
                thumbnail = e.optString("image").takeIf { it.isNotBlank() },
                overview = e.optString("overview").takeIf { it.isNotBlank() } ?: e.optString("summary").takeIf { it.isNotBlank() })
        }.sortedBy { it.episode }.toList()

        val year = a?.optJSONObject("startDate")?.optInt("year")?.takeIf { it > 0 }
        return Meta(
            id = playBase, type = if (isMovie) "movie" else "series", name = name,
            poster = a?.optJSONObject("coverImage")?.optString("extraLarge")?.takeIf { it.isNotBlank() } ?: img("Poster"),
            background = img("Fanart") ?: a?.optString("bannerImage")?.takeIf { it.isNotBlank() && it != "null" },
            logo = img("Clearlogo"),
            description = a?.optString("description")?.takeIf { it.isNotBlank() && it != "null" }
                ?.replace(Regex("<br\\s*/?>"), "\n")?.replace(Regex("<[^>]+>"), "")?.replace(Regex("\n{3,}"), "\n\n")?.trim(),
            releaseInfo = year?.toString(),
            imdbRating = a?.optInt("averageScore")?.takeIf { it > 0 }?.let { "%.1f".format(it / 10.0) },
            genres = a?.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.optString(it) } }.orEmpty(),
            runtime = a?.optInt("duration")?.takeIf { it > 0 }?.let { "${it}m" },
            videos = videos,
        )
    }

    private fun anilist(id: Int?, malId: Int?): JSONObject? {
        if (id == null && malId == null) return null
        val arg = if (id != null) "id: $id" else "idMal: $malId"
        val query = "{ Media($arg, type: ANIME) { id format title { english romaji } description(asHtml: false) genres averageScore " +
            "duration bannerImage coverImage { extraLarge } startDate { year } } }"
        val req = Request.Builder().url("https://graphql.anilist.co").header("Accept", "application/json")
            .post(JSONObject().put("query", query).toString().toRequestBody("application/json".toMediaType())).build()
        return runCatching {
            Net.http.newCall(req).execute().use { r -> if (r.isSuccessful) JSONObject(r.body!!.string()).optJSONObject("data")?.optJSONObject("Media") else null }
        }.getOrNull()
    }
}
