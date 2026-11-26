package com.lekathedon.visionlite.detail

import com.lekathedon.visionlite.addons.Meta
import com.lekathedon.visionlite.addons.Video
import com.lekathedon.visionlite.data.Net
import kotlinx.coroutines.Dispatchers
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
    private fun zip(id: String): JSONObject? {
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
    suspend fun parentOf(id: String): Pair<String, Int?>? = withContext(Dispatchers.IO) {
        val base = id.split(':').take(2).joinToString(":")
        val z = zip(base) ?: return@withContext null
        val maps = z.optJSONObject("mappings") ?: return@withContext null
        if (maps.optString("type").equals("MOVIE", true)) return@withContext null
        val imdb = maps.optString("imdb_id").takeIf { it.startsWith("tt") } ?: return@withContext null
        val eps = z.optJSONObject("episodes")
        val season = eps?.optJSONObject("1")?.optInt("seasonNumber", 0)?.takeIf { it > 0 }
        imdb to season
    }

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
