// What Vision TB searches with, as Vision's engine fills it (TorboxDiscoveryEngine: withTargetMetadata, withAnime,
// TvdbEpisodeHints): every title of the film or show (Cinemeta, plus TMDB's original and alternative titles with a
// TMDB key), this episode's title and the show's other episode titles (TVDB's other orders with a TVDB key), and for
// anime the Kitsu / AniList / AniDB ids (ani.zip, no key), Kitsu's own titles and SeaDex's best releases.
// Cost: runs only when a title's links are asked for. Per show, kept for the app run (at most 60 shows, a few KB):
// ani.zip 1 request, TMDB 2, Kitsu 1, SeaDex 1, TVDB 1 search + 2 lists a day. Each step has its own short timeout.
package com.lekathedon.visionlite.tb

import android.util.Log
import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.home.Tmdb
import com.lekathedon.visionlite.integrations.Integrations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object TbMeta {
    private const val TAG = "VisionTB"
    private const val MAX = 60
    private const val DAY_MS = 24 * 60 * 60_000L

    private val client: OkHttpClient by lazy {
        Net.http.newBuilder().connectTimeout(4, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).build()
    }

    /** ani.zip's answer for one id: the anime ids, its titles, and anime episode → IMDb-style season/episode. */
    class AniZip(val imdb: String?, val kitsu: Int?, val anilist: Int?, val anidb: Int?, val titles: List<String>,
                 val episodes: Map<Int, Pair<Int, Int>>, val movie: Boolean)

    private val aniZip = ConcurrentHashMap<String, AniZip>()
    private val titles = ConcurrentHashMap<String, Pair<List<String>, Boolean>>()
    private val kitsuTitles = ConcurrentHashMap<Int, List<String>>()
    private val seaDex = ConcurrentHashMap<Int, Set<String>>()

    private fun <K, V> ConcurrentHashMap<K, V>.keep(k: K, v: V): V { if (size > MAX) keys.firstOrNull()?.let(::remove); put(k, v); return v }

    private fun get(url: String, accept: String? = null): String? = runCatching {
        val b = Request.Builder().url(url).header("User-Agent", "VisionLite")
        accept?.let { b.header("Accept", it) }
        client.newCall(b.build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull()

    /**
     * An anime id (kitsu:ID:EP, mal:ID:EP, anilist:ID:EP) as IMDb id + season/episode for the IMDb-id sources, and its
     * anime refs. Null when ani.zip doesn't know it.
     */
    suspend fun fromAnimeId(id: String): Pair<AniZip, Pair<Int?, Int?>>? = withContext(Dispatchers.IO) {
        val p = id.split(':')
        val key = when (p[0].lowercase()) { "kitsu" -> "kitsu_id"; "mal" -> "mal_id"; "anilist" -> "anilist_id"; else -> return@withContext null }
        val a = aniZipFor(key, p.getOrNull(1) ?: return@withContext null) ?: return@withContext null
        val ep = p.getOrNull(2)?.toIntOrNull()
        val at: Pair<Int, Int>? = ep?.let { a.episodes[it] ?: (1 to it) }
        a to Pair<Int?, Int?>(at?.first, at?.second)
    }

    /** ani.zip by IMDb id: non-null = the title is anime. */
    suspend fun animeOfImdb(imdb: String): AniZip? = withContext(Dispatchers.IO) { aniZipFor("imdb_id", imdb) }

    private fun aniZipFor(key: String, value: String): AniZip? {
        aniZip["$key:$value"]?.let { return it.takeIf { a -> a.kitsu != null || a.anilist != null || a.anidb != null } }
        val j = get("https://api.ani.zip/mappings?$key=$value")?.let { runCatching { JSONObject(it) }.getOrNull() }
        val m = j?.optJSONObject("mappings")
        fun int(k: String) = m?.opt(k)?.toString()?.toIntOrNull()?.takeIf { it > 0 }
        val t = j?.optJSONObject("titles")
        val eps = HashMap<Int, Pair<Int, Int>>()
        j?.optJSONObject("episodes")?.let { e ->
            e.keys().forEach { k -> val o = e.optJSONObject(k); val n = k.toIntOrNull()
                if (o != null && n != null && o.optInt("seasonNumber", 0) > 0 && o.optInt("episodeNumber", 0) > 0) eps[n] = o.optInt("seasonNumber") to o.optInt("episodeNumber") }
        }
        val a = AniZip(m?.optString("imdb_id")?.takeIf { it.startsWith("tt") }, int("kitsu_id"), int("anilist_id"), int("anidb_id"),
            listOfNotNull(t?.optString("en"), t?.optString("x-jat"), t?.optString("ja")).filter { it.isNotBlank() && it != "null" }.distinct(),
            eps, m?.optString("type").equals("MOVIE", true))
        aniZip.keep("$key:$value", a)
        return a.takeIf { it.kitsu != null || it.anilist != null || it.anidb != null }
    }

    /** TMDB's original and alternative titles, and whether TMDB calls it Japanese animation. Empty without a TMDB key. */
    suspend fun tmdbTitles(imdb: String, series: Boolean): Pair<List<String>, Boolean> = withContext(Dispatchers.IO) {
        titles[imdb]?.let { return@withContext it }
        if (Tmdb.key() == null) return@withContext emptyList<String>() to false
        val found = Tmdb.get("find/$imdb?external_source=imdb_id")
        val r = found?.optJSONArray(if (series) "tv_results" else "movie_results")?.optJSONObject(0)
            ?: return@withContext emptyList<String>() to false
        val tmdbId = r.optInt("id")
        val original = r.optString(if (series) "original_name" else "original_title").takeIf { it.isNotBlank() }
        val genres = r.optJSONArray("genre_ids")
        val animation = genres != null && (0 until genres.length()).any { genres.optInt(it) == 16 }
        val anime = animation && r.optString("original_language") == "ja"
        val alt = Tmdb.get("${if (series) "tv" else "movie"}/$tmdbId/alternative_titles")
        val list = alt?.optJSONArray(if (series) "results" else "titles")
        // Latin-script alternatives first (release names are mostly Latin); at most 8.
        val alts = (0 until (list?.length() ?: 0)).mapNotNull { list?.optJSONObject(it)?.optString("title")?.takeIf { t -> t.isNotBlank() } }
            .sortedByDescending { t -> t.count { it.code < 0x250 } * 2 >= t.length }.distinct().take(8)
        // Plus up to 2 Arabic titles (ArabP2P names releases in Arabic), even past the 8.
        val arabic = (0 until (list?.length() ?: 0)).mapNotNull { list?.optJSONObject(it)?.optString("title") }
            .filter { t -> t.any { it in '\u0621'..'\u064A' } }.distinct().take(2)
        titles.keep(imdb, (listOfNotNull(original) + alts + arabic).distinct() to anime)
    }

    /** Titles already read for [imdb] (TMDB's original + alternatives, the anime lists'); no request. */
    fun knownTitles(imdb: String): List<String> =
        (titles[imdb]?.first.orEmpty() + aniZip["imdb_id:$imdb"]?.titles.orEmpty()).distinct()

    /** Kitsu's titles for its entry (Comet's anime rows must carry one of them). */
    suspend fun kitsuTitles(kitsuId: Int): List<String> = withContext(Dispatchers.IO) {
        kitsuTitles[kitsuId]?.let { return@withContext it }
        val body = get(AnimeSources.kitsuTitlesUrl(kitsuId), "application/vnd.api+json") ?: return@withContext emptyList()
        AnimeSources.parseKitsuTitles(body).also { if (it.isNotEmpty()) kitsuTitles.keep(kitsuId, it) }
    }

    /** SeaDex's best releases for the show, remembered for the badge and Best link (an empty answer is kept too). */
    suspend fun seaDex(anilistId: Int) = withContext(Dispatchers.IO) {
        val best = seaDex[anilistId] ?: (get(AnimeSources.seaDexUrl(anilistId))?.let { AnimeSources.parseSeaDexBest(it) }
            ?.also { seaDex.keep(anilistId, it) } ?: emptySet())
        SeaDexBest.remember(best)
    }

    /**
     * This episode's title and the show's other episode titles from the details add-on's episode list (Cinemeta's
     * numbering, the one the add-ons use); with a TVDB key, also this episode's numbers in TVDB's DVD order.
     */
    suspend fun episodeHint(imdb: String, season: Int, episode: Int, names: Map<Pair<Int, Int>, String>): EpisodeOrderHint? {
        val own = names[season to episode] ?: return null
        val tvdb = withTimeoutOrNull(4_000) { Tvdb.hint(imdb, season, episode, own) }
        if (tvdb != null) return tvdb
        return EpisodeOrderHint(own, names.filterKeys { it != season to episode }.values.distinct().take(400), emptyList())
    }

    /** Vision's TvdbEpisodeHints with Lite's TVDB integration key; nothing runs without one. The key is never logged. */
    private object Tvdb {
        private const val API = "https://api4.thetvdb.com/v4"
        private data class Ep(val id: Long, val season: Int, val number: Int, val name: String)
        @Volatile private var token: Pair<String, String>? = null
        private val seriesIds = ConcurrentHashMap<String, Long>()
        private val shows = ConcurrentHashMap<Long, Pair<Map<String, List<Ep>>, Long>>()

        private fun key(): String? = com.lekathedon.visionlite.data.Our.keyOr(runCatching { Integrations.values(Integrations.byId("tvdb")).firstOrNull() }.getOrNull())

        suspend fun hint(imdb: String, season: Int, episode: Int, name: String): EpisodeOrderHint? = withContext(Dispatchers.IO) {
            val key = key() ?: return@withContext null
            runCatching {
                val wanted = ReleaseMatcher.normalizeTitle(name).takeIf { it.isNotEmpty() } ?: return@runCatching null
                val sid = seriesIds[imdb] ?: findSeries(key, imdb)?.also { seriesIds.keep(imdb, it) } ?: return@runCatching null
                val orders = shows[sid]?.takeIf { System.currentTimeMillis() - it.second < DAY_MS }?.first
                    ?: listOf("default", "dvd").associateWith { episodes(key, sid, it) }.also { shows.keep(sid, it to System.currentTimeMillis()) }
                val all = orders.values.flatten()
                val match = all.filter { ReleaseMatcher.normalizeTitle(it.name) == wanted }
                    .sortedByDescending { it.season == season && it.number == episode }.firstOrNull() ?: return@runCatching null
                val alternates = all.filter { it.id == match.id && it.season > 0 && !(it.season == season && it.number == episode) }
                    .map { it.season to it.number }.distinct()
                val others = orders["default"].orEmpty().filter { it.id != match.id && it.name.isNotBlank() }.map { it.name }.distinct().take(400)
                EpisodeOrderHint(match.name, others, alternates)
            }.onFailure { Log.w(TAG, "tvdb hint failed: ${it::class.simpleName}") }.getOrNull()
        }

        private fun findSeries(key: String, imdb: String): Long? {
            val data = tvdb(key, "search/remoteid/$imdb")?.optJSONArray("data") ?: return null
            return (0 until data.length()).firstNotNullOfOrNull { data.optJSONObject(it)?.optJSONObject("series")?.optLong("id")?.takeIf { id -> id > 0 } }
        }

        private fun episodes(key: String, sid: Long, order: String): List<Ep> {
            val out = ArrayList<Ep>()
            for (page in 0 until 6) {
                val json = tvdb(key, "series/$sid/episodes/$order?page=$page") ?: break
                val eps = json.optJSONObject("data")?.optJSONArray("episodes") ?: break
                for (i in 0 until eps.length()) {
                    val e = eps.optJSONObject(i) ?: continue
                    val n = e.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: continue
                    out += Ep(e.optLong("id"), e.optInt("seasonNumber", -1), e.optInt("number", -1), n)
                }
                val next = json.optJSONObject("links")?.opt("next")
                if (next == null || next == JSONObject.NULL || eps.length() == 0) break
            }
            return out
        }

        private fun tvdb(key: String, path: String): JSONObject? {
            repeat(2) { attempt ->
                val bearer = token?.takeIf { attempt == 0 && it.first == key }?.second ?: signIn(key)?.also { token = key to it } ?: return null
                client.newCall(Request.Builder().url("$API/$path").header("Authorization", "Bearer $bearer").build()).execute().use { r ->
                    if (r.code == 401) { token = null; return@repeat }
                    if (!r.isSuccessful) return null
                    return JSONObject(r.body?.string().orEmpty())
                }
            }
            return null
        }

        private fun signIn(key: String): String? {
            val body = JSONObject().put("apikey", key).toString().toRequestBody("application/json".toMediaType())
            return client.newCall(Request.Builder().url("$API/login").post(body).build()).execute().use { r ->
                if (!r.isSuccessful) null else JSONObject(r.body?.string().orEmpty()).optJSONObject("data")?.optString("token")?.takeIf { it.isNotBlank() }
            }
        }
    }

    /** The details add-on's episode names for a show, by (season, episode). */
    fun namesOf(meta: com.lekathedon.visionlite.addons.Meta?): Map<Pair<Int, Int>, String> =
        meta?.videos.orEmpty().mapNotNull { v ->
            val s = v.season ?: return@mapNotNull null; val e = v.episode ?: return@mapNotNull null
            val n = (v.name ?: v.title)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            (s to e) to n
        }.toMap()
}
