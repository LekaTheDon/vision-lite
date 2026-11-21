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

    /** TMDB's best YouTube trailer: official Trailer first, then any Trailer, then a Teaser. Needs your TMDB key. */
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
        val pick = all.firstOrNull { it.optString("type") == "Trailer" && it.optBoolean("official") }
            ?: all.firstOrNull { it.optString("type") == "Trailer" } ?: all.firstOrNull { it.optString("type") == "Teaser" }
        return pick?.optString("key")
    }

    /** YouTube's first video for "<title> <year> official trailer" (anime: "… anime trailer"). */
    private suspend fun search(m: MetaPreview): String? {
        if (m.name.isBlank()) return null
        val anime = !m.id.startsWith("tt") && !m.id.startsWith("tmdb:")
        val q = listOfNotNull(m.name, m.releaseInfo?.take(4), if (anime) "anime trailer" else "official trailer").joinToString(" ")
        return Yt.search(q).flatMap { it.items }.firstOrNull { it.videoId?.length == 11 }?.videoId
    }
}
