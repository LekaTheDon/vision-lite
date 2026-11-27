package com.lekathedon.visionlite.detail

import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.data.Our
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * The app's own score (agreed 2026-11-27): one number out of 10, the average of the relevant sources, shown with our
 * own mark instead of a row of other sites' logos.
 *  - Films & TV: IMDb, TMDB, Trakt, plus Metacritic, Rotten Tomatoes (critics + audience) and Letterboxd when known.
 *  - Anime: AniList, MyAnimeList and Kitsu.
 * Our server works it out (and keeps it a day); the sources the page already has (your own MDBList / Trakt keys) join
 * in, each source counted once. Without the server, anime asks the three keyless sources itself.
 * Cost: one small GET per details page (memory cache, 60 titles); nothing else.
 */
object OwnScore {
    data class Part(val src: String, val value: Double)
    data class Score(val value: Double, val parts: List<Part>) {
        val label get() = "%.1f".format(value)
        /** "IMDb · TMDB · Trakt": names only, no logos. */
        val from get() = parts.joinToString(" · ") { NAMES[it.src] ?: it.src }
    }

    private val NAMES = mapOf("imdb" to "IMDb", "tmdb" to "TMDB", "trakt" to "Trakt", "metacritic" to "Metacritic",
        "rottentomatoes" to "Rotten Tomatoes", "audience" to "RT audience", "letterboxd" to "Letterboxd",
        "anilist" to "AniList", "mal" to "MyAnimeList", "kitsu" to "Kitsu")

    private val cache = object : LinkedHashMap<String, Score?>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Score?>?) = size > 60
    }

    private fun of(parts: Collection<Part>): Score? {
        val ok = parts.filter { it.value > 0 && it.value <= 10 }.distinctBy { it.src }
        if (ok.isEmpty()) return null
        return Score(Math.round(ok.sumOf { it.value } / ok.size * 10) / 10.0, ok)
    }

    private fun server(type: String, id: String): List<Part> {
        val url = Our.score(type, id) ?: return emptyList()
        return runCatching {
            Net.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return emptyList()
                val a = JSONObject(r.body?.string().orEmpty()).optJSONArray("parts") ?: return emptyList()
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Part(it.optString("src"), it.optDouble("value")) }
            }
        }.getOrDefault(emptyList())
    }

    /** Films & TV: the server's parts, then what this page already knows (filled in only where the server had none). */
    suspend fun film(imdb: String, series: Boolean, t: Imdb.Title?, trakt: Double?, more: MoreRatings.R): Score? = withContext(Dispatchers.IO) {
        val key = "f:$imdb:${more.hashCode()}:$trakt"
        synchronized(cache) { if (cache.containsKey(key)) return@withContext cache[key] }
        val local = buildList {
            t?.rating?.let { add(Part("imdb", it)) }
            more.tmdb?.toDoubleOrNull()?.let { add(Part("tmdb", it / 10)) }
            trakt?.let { add(Part("trakt", it)) }
            t?.metascore?.let { add(Part("metacritic", it / 10.0)) }
            more.tomatoes?.let { add(Part("rottentomatoes", it / 10.0)) }
            more.audience?.let { add(Part("audience", it / 10.0)) }
            more.letterboxd?.let { add(Part("letterboxd", it * 2)) }
        }
        of(server(if (series) "series" else "movie", imdb) + local).also { synchronized(cache) { cache[key] = it } }
    }

    /** Anime: AniList, MyAnimeList, Kitsu ([anime] = any anime entry id: "anilist:N", "mal:N", "kitsu:N"). */
    suspend fun anime(anime: String): Score? = withContext(Dispatchers.IO) {
        val key = "a:$anime"
        synchronized(cache) { if (cache.containsKey(key)) return@withContext cache[key] }
        val al = AnimeMeta.anilistOf(anime)
        val parts = when {
            al == null -> emptyList()
            Our.on -> server("anime", "$al")
            else -> direct(al)
        }
        of(parts).also { synchronized(cache) { cache[key] = it } }
    }

    /** No server yet: the same three keyless sources, asked from here. */
    private suspend fun direct(al: Int): List<Part> = coroutineScope {
        val q = JSONObject().put("query", "query(\$i:Int){Media(id:\$i){averageScore idMal}}").put("variables", JSONObject().put("i", al))
        val m = runCatching {
            Net.http.newCall(Request.Builder().url("https://graphql.anilist.co")
                .post(q.toString().toRequestBody("application/json".toMediaType())).build()).execute()
                .use { JSONObject(it.body?.string().orEmpty()).optJSONObject("data")?.optJSONObject("Media") }
        }.getOrNull() ?: return@coroutineScope emptyList()
        val out = mutableListOf<Part>()
        m.optInt("averageScore").takeIf { it > 0 }?.let { out += Part("anilist", it / 10.0) }
        val mal = m.optInt("idMal").takeIf { it > 0 } ?: return@coroutineScope out
        val j = async { runCatching { JSONObject(Net.get("https://api.jikan.moe/v4/anime/$mal", 6)).optJSONObject("data")?.optDouble("score") }.getOrNull() }
        val k = async { runCatching {
            JSONObject(Net.get("https://kitsu.io/api/edge/mappings?filter[externalSite]=myanimelist/anime&filter[externalId]=$mal&include=item", 6))
                .optJSONArray("included")?.optJSONObject(0)?.optJSONObject("attributes")?.optString("averageRating")?.toDoubleOrNull()
        }.getOrNull() }
        j.await()?.takeIf { it > 0 }?.let { out += Part("mal", it) }
        k.await()?.takeIf { it > 0 }?.let { out += Part("kitsu", it / 10) }
        out
    }
}
