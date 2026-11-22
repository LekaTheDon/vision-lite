package com.lekathedon.visionlite.trailer

import android.util.Log
import com.lekathedon.visionlite.addons.MetaPreview
import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.home.Tmdb
import com.lekathedon.visionlite.youtube.Yt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** A playable trailer: one link, or YouTube's separate video + audio. */
data class Trailer(val url: String, val audio: String? = null)

/**
 * Vision's trailer order for any card on Home, whatever row it sits in:
 *  1. IMDb (the title's IMDb id; anime ids are turned into one first),
 *  2. TMDB's trailer video (with your TMDB key) — for anime also AniList's own trailer,
 *  3. YouTube search ("<title> <year> official trailer") as the backup.
 * TMDB / AniList / search give a YouTube video, played through Lite's own YouTube client. One answer per title a
 * session (none found is remembered too). Only asked once focus has rested on a card, never ahead.
 */
object TrailerChain {
    private const val TAG = "TrailerChain"
    private val cache = object : LinkedHashMap<String, Trailer?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Trailer?>?) = size > 200
    }

    suspend fun find(m: MetaPreview, maxHeight: Int): Trailer? {
        val key = "${m.id}@$maxHeight"
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val t = withTimeoutOrNull(12_000) { look(m, maxHeight) }
        synchronized(cache) { cache[key] = t }
        return t
    }

    private suspend fun look(m: MetaPreview, maxHeight: Int): Trailer? {
        val imdb = runCatching { if (m.id.startsWith("tt")) m.id.substringBefore(':') else com.lekathedon.visionlite.home.imdbIdOf(m) }
            .getOrNull()?.takeIf { it.startsWith("tt") }
        // 0. A show whose newest season started in the last 6 months (or starts within 2 months): that season's trailer
        // (user 2026-11-22: Dexter: Resurrection S2, Monster S4 played the first season's). Anime with IMDb ids too.
        val seasonYt = if (m.type == "series" && imdb != null) withContext(Dispatchers.IO) { runCatching { latestSeasonTrailer(imdb, m) }.getOrNull() } else null
        if (seasonYt != null) withContext(Dispatchers.IO) {
            runCatching { Yt.streams(seasonYt, maxHeight) }.getOrNull()?.let { s -> s.video?.let { Trailer(it, s.audio) } ?: s.hls?.let { Trailer(it) } }
        }?.let { return it }
        // 1. IMDb
        if (imdb != null) Trailers.find(imdb, maxHeight)?.let { return Trailer(it) }
        // 2. TMDB (and AniList for anime)
        val yt = withContext(Dispatchers.IO) {
            runCatching { anilistTrailer(m) }.getOrNull()
                ?: runCatching { tmdbTrailer(imdb, m) }.getOrNull()
        }
            // 3. YouTube search
            ?: runCatching { search(m) }.onFailure { Log.w(TAG, "search ${m.name}: ${it.message}") }.getOrNull()
            ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { Yt.streams(yt, maxHeight) }.getOrNull()?.let { s ->
                s.video?.let { Trailer(it, s.audio) } ?: s.hls?.let { Trailer(it) }
            }
        }
    }

    private fun dayMs(d: String?): Long? = d?.takeIf { it.length >= 10 }?.let { runCatching { java.time.LocalDate.parse(it.take(10)).toEpochDay() * 86_400_000L }.getOrNull() }

    /** The newest season if it is season 2+ and started within 180 days (or starts within 60); else null (older shows keep their trailer). */
    private fun recentSeason(starts: Map<Int, Long>): Int? {
        val now = System.currentTimeMillis(); val day = 86_400_000L
        val (n, at) = starts.filterKeys { it > 0 }.maxByOrNull { it.key } ?: return null
        return n.takeIf { it > 1 && now - at < 180 * day && at - now < 60 * day }
    }

    /**
     * The trailer of a show's season that just came out: TMDB's own season videos (your TMDB key; official Trailer,
     * then Teaser), else a YouTube search that must name that season. Without a TMDB key the seasons' start dates come
     * from Cinemeta. Cost: 2 TMDB calls (or 1 Cinemeta) per show whose trailer is asked, + 1 for a recent season.
     */
    private suspend fun latestSeasonTrailer(imdb: String, m: MetaPreview): String? {
        if (Tmdb.key() != null) {
            val tvId = Tmdb.get("find/$imdb?external_source=imdb_id")?.optJSONArray("tv_results")?.optJSONObject(0)?.optLong("id")?.takeIf { it > 0 }
            if (tvId != null) {
                val seasons = Tmdb.get("tv/$tvId")?.optJSONArray("seasons") ?: return null
                val starts = (0 until seasons.length()).mapNotNull { seasons.optJSONObject(it) }
                    .mapNotNull { o -> dayMs(o.optString("air_date"))?.let { o.optInt("season_number") to it } }.toMap()
                val n = recentSeason(starts) ?: return null
                val v = Tmdb.get("tv/$tvId/season/$n/videos?language=en-US")?.optJSONArray("results")
                val all = if (v == null) emptyList() else (0 until v.length()).mapNotNull { v.optJSONObject(it) }
                    .filter { it.optString("site") == "YouTube" && it.optString("key").length == 11 }
                val bad = Regex("(?i)review|reaction|explained|breakdown|recap|featurette|interview|behind|clip|spot")
                val pick = all.filter { it.optString("type") == "Trailer" && !bad.containsMatchIn(it.optString("name")) }
                    .sortedByDescending { it.optBoolean("official") }.firstOrNull()
                    ?: all.firstOrNull { it.optString("type") == "Teaser" && !bad.containsMatchIn(it.optString("name")) }
                return pick?.optString("key") ?: search(m, n)
            }
        }
        val meta = runCatching { Net.getJson<com.lekathedon.visionlite.addons.MetaResponse>("https://v3-cinemeta.strem.io/meta/series/$imdb.json", 10).meta }.getOrNull() ?: return null
        val starts = meta.videos.orEmpty().filter { (it.season ?: 0) > 0 }.groupBy { it.season!! }
            .mapNotNull { (n, eps) -> eps.mapNotNull { dayMs(it.released) }.minOrNull()?.let { n to it } }.toMap()
        val n = recentSeason(starts) ?: return null
        return search(m, n)
    }

    /** AniList's own trailer (YouTube) for anilist: ids. */
    private fun anilistTrailer(m: MetaPreview): String? {
        val id = m.id.removePrefix("anilist:").substringBefore(':').toLongOrNull()?.takeIf { m.id.startsWith("anilist:") } ?: return null
        val q = "{ Media(id: $id) { trailer { id site } } }"
        val text = Net.http.newCall(Request.Builder().url("https://graphql.anilist.co").header("Accept", "application/json")
            .post(JSONObject().put("query", q).toString().toRequestBody("application/json".toMediaType())).build())
            .execute().use { it.body?.string().orEmpty() }
        val t = JSONObject(text).optJSONObject("data")?.optJSONObject("Media")?.optJSONObject("trailer") ?: return null
        return t.optString("id").takeIf { t.optString("site") == "youtube" && it.length == 11 }
    }

    /**
     * TMDB's best YouTube trailer, from the title's own videos: an official Trailer in English first (the main one, not a
     * "final"/character/review one), then any official Trailer, then an official Teaser. Unofficial uploads are left out.
     */
    private fun tmdbTrailer(imdb: String?, m: MetaPreview): String? {
        if (Tmdb.key() == null) return null
        val (kind, id) = when {
            m.id.startsWith("tmdb:") -> m.id.removePrefix("tmdb:").split(':').let { it.getOrNull(0) to it.getOrNull(1) }
            imdb != null -> Tmdb.get("find/$imdb?external_source=imdb_id")?.let { f ->
                f.optJSONArray("movie_results")?.optJSONObject(0)?.optLong("id")?.takeIf { it > 0 }?.let { "movie" to it.toString() }
                    ?: f.optJSONArray("tv_results")?.optJSONObject(0)?.optLong("id")?.takeIf { it > 0 }?.let { "tv" to it.toString() }
            } ?: return null
            else -> return null
        }
        if (kind == null || id == null) return null
        val v = Tmdb.get("$kind/$id/videos?language=en-US")?.optJSONArray("results") ?: return null
        val all = (0 until v.length()).mapNotNull { v.optJSONObject(it) }.filter { it.optString("site") == "YouTube" && it.optString("key").length == 11 }
        val off = all.filter { it.optBoolean("official") }
        val bad = Regex("(?i)review|reaction|explained|breakdown|recap|featurette|interview|behind|clip|spot")
        val trailers = off.filter { it.optString("type") == "Trailer" && !bad.containsMatchIn(it.optString("name")) }
        val pick = trailers.sortedBy { it.optString("published_at") }.firstOrNull { Regex("(?i)official|trailer ?#?1\\b|main").containsMatchIn(it.optString("name")) }
            ?: trailers.minByOrNull { it.optString("published_at").ifBlank { "9" } }
            ?: off.firstOrNull { it.optString("type") == "Teaser" && !bad.containsMatchIn(it.optString("name")) }
        return pick?.optString("key")
    }

    /**
     * YouTube search for "<title> <year> official trailer" (anime: "… anime trailer"), as the last resort, and only a
     * video that is plainly this title's trailer: its name holds the whole title and the word trailer (or teaser /
     * PV), and none of review, reaction, explained, recap, breakdown, ending, interview, fan-made, concept… A search
     * that finds none gives no trailer: better the still than someone else's video (the review of "Prawo bestii").
     */
    private suspend fun search(m: MetaPreview, season: Int? = null): String? {
        if (m.name.isBlank()) return null
        val anime = !m.id.startsWith("tt") && !m.id.startsWith("tmdb:")
        // A season's trailer: "<name> season N official trailer", and the video must name that season (its year is the
        // season's, so the title's first year doesn't count against it).
        val year = if (season != null) null else m.releaseInfo?.take(4)
        val seasonWord = season?.let { Regex("\\b(season|s)\\s*0?$it\\b") }
        val q = listOfNotNull(m.name, year, season?.let { "season $it" }, if (anime) "anime trailer" else "official trailer").joinToString(" ")
        val want = norm(m.name)
        if (want.length < 2) return null
        return Yt.search(q).flatMap { it.items }.filter { it.videoId?.length == 11 }.take(8).firstOrNull { v ->
            val t = norm(v.title)
            val padded = " $t "
            // The title's own words don't count against it (a film called "The Review").
            val rest = padded.replace(" $want ", " ")
            " $want " in padded && TRAILER_WORD.containsMatchIn(rest) && !NOT_TRAILER.containsMatchIn(rest) &&
                // Another year in the name is another film of that name (a remake, the original).
                (year == null || YEAR.findAll(rest).none { it.value != year }) &&
                (seasonWord == null || seasonWord.containsMatchIn(rest))
        }?.videoId
    }

    private fun norm(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .lowercase().replace('&', ' ').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    private val TRAILER_WORD = Regex("\\b(trailer|teaser|pv|zwiastun|trailer oficial|bande annonce|tráiler|fragman|tráiler oficial)\\b")
    private val NOT_TRAILER = Regex("\\b(review|reviews|reaction|reacts|explained|explain|recap|breakdown|ending|interview|fan made|fanmade|concept|parody|analysis|theory|theories|easter eggs|things you missed|comparison|honest|pitch meeting|clip|behind the scenes|featurette|commentary|podcast|unboxing|ai generated)\\b")
    private val YEAR = Regex("\\b(19|20)\\d{2}\\b")
}
