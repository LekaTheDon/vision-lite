package com.lekathedon.visionlite.detail

import com.lekathedon.visionlite.integrations.Integrations
import com.lekathedon.visionlite.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Vision's ratings row (MDBListRatingsRow): besides IMDb, Metacritic and Trakt, TMDB (your TMDB key) and, with your
 * MDBList key, Rotten Tomatoes, audience score, Letterboxd and MyAnimeList. One call per source per title opened
 * (memory cache, 60 titles); nothing without a key.
 */
object MoreRatings {
    /** source -> shown value ("8.1", "93%", "71"). */
    data class R(val tmdb: String? = null, val tomatoes: Int? = null, val audience: Int? = null, val letterboxd: Double? = null, val mal: Double? = null)

    private val cache = object : LinkedHashMap<String, R>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, R>?) = size > 60
    }

    suspend fun of(imdb: String, series: Boolean): R = withContext(Dispatchers.IO) {
        synchronized(cache) { cache[imdb] }?.let { return@withContext it }
        var r = R()
        runCatching {
            val j = com.lekathedon.visionlite.home.Tmdb.get("find/$imdb?external_source=imdb_id")
            val hit = (j?.optJSONArray(if (series) "tv_results" else "movie_results"))?.optJSONObject(0)
            hit?.optDouble("vote_average")?.takeIf { it > 0 && hit.optInt("vote_count") >= 10 }?.let { r = r.copy(tmdb = "${(it * 10).toInt()}") }
        }
        val key = com.lekathedon.visionlite.data.Our.keyOr(if (Integrations.active("mdblist")) Integrations.values(Integrations.byId("mdblist")).firstOrNull() else null)
        if (key != null) runCatching {
            val body = Net.http.newCall(Request.Builder()
                .url("https://api.mdblist.com/imdb/${if (series) "show" else "movie"}/$imdb?apikey=$key").build()).execute().use { it.body?.string() }
            val arr = JSONObject(body ?: "{}").optJSONArray("ratings")
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val v = o.opt("value").let { (it as? Number)?.toDouble() ?: it?.toString()?.toDoubleOrNull() } ?: continue
                if (v <= 0) continue
                r = when (o.optString("source")) {
                    "tomatoes" -> r.copy(tomatoes = v.toInt())
                    "popcorn", "tomatoesaudience" -> r.copy(audience = v.toInt())
                    "letterboxd" -> r.copy(letterboxd = v)
                    "myanimelist" -> r.copy(mal = v)
                    "tmdb" -> if (r.tmdb == null) r.copy(tmdb = "${v.toInt()}") else r
                    else -> r
                }
            }
        }
        synchronized(cache) { cache[imdb] = r }
        r
    }
}
