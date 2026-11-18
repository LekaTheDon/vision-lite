// Vision's skip sources (data/repository/SkipIntroRepository): IntroDB first, AniSkip for anime, merged per
// segment kind by priority. Lite maps anime ids with ani.zip instead of Simkl (no key needed).
package com.lekathedon.visionlite.player

import com.lekathedon.visionlite.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** One skippable part, in ms. [kind]: intro, recap, outro, credits. */
data class Segment(val startMs: Long, val endMs: Long, val kind: String) {
    val label get() = when (kind) { "intro" -> "Skip intro"; "recap" -> "Skip recap"; "outro" -> "Skip outro"; else -> "Skip credits" }
}

object Skips {
    private val client = Net.http.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()
    private val cache = ConcurrentHashMap<String, List<Segment>>()
    /** Show id → MAL id and its episode numbering ("" = not anime / unknown), asked once a session. */
    private val anime = ConcurrentHashMap<String, Pair<String, JSONObject?>>()

    /** Segments for [id] ("tt…" movie, "tt…:s:e", "kitsu:…:e", "mal:…:e"); empty when none is known. */
    suspend fun find(type: String, id: String): List<Segment> {
        cache[id]?.let { return it }
        val parts = id.split(':')
        val out = runCatching {
            when {
                parts[0].startsWith("tt") && parts.size == 1 -> introDb(parts[0], null, null, movie = true)
                parts[0].startsWith("tt") && parts.size >= 3 -> {
                    val s = parts[1].toIntOrNull() ?: 0; val e = parts[2].toIntOrNull() ?: 0
                    val db = introDb(parts[0], s, e, movie = false)
                    if (db.any { it.kind == "intro" } && db.any { it.kind == "outro" }) db
                    else merge(db, aniSkipForImdb(parts[0], s, e))
                }
                parts[0] == "kitsu" && parts.size >= 3 -> malFor("kitsu_id", parts[1])?.let { aniSkip(it, parts[2].toIntOrNull() ?: 1) } ?: emptyList()
                parts[0] == "mal" && parts.size >= 3 -> aniSkip(parts[1], parts[2].toIntOrNull() ?: 1)
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
        if (type.isNotEmpty()) cache[id] = out
        return out
    }

    /** First provider wins each kind (IntroDB, then AniSkip). */
    private fun merge(vararg lists: List<Segment>) = lists.flatMap { it }.distinctBy { it.kind }

    private suspend fun introDb(imdb: String, season: Int?, episode: Int?, movie: Boolean): List<Segment> {
        val url = "https://api.introdb.app/segments?imdb_id=$imdb" + (if (season != null) "&season=$season&episode=$episode" else "") + (if (movie) "&is_movie=true" else "")
        val j = get(url)?.let { JSONObject(it) } ?: return emptyList()
        fun seg(name: String, kind: String): Segment? {
            val o = j.optJSONObject(name) ?: return null
            val start = if (o.has("start_sec")) (o.optDouble("start_sec") * 1000).toLong() else o.optLong("start_ms", -1)
            val end = if (o.has("end_sec")) (o.optDouble("end_sec") * 1000).toLong() else o.optLong("end_ms", -1)
            return if (start >= 0 && end > start) Segment(start, end, kind) else null
        }
        if (!movie) return listOfNotNull(seg("intro", "intro"), seg("recap", "recap"), seg("outro", "outro"))
        // Skipping a film's credits must not also skip its post-credits scene.
        val credits = seg("outro", "credits") ?: return emptyList()
        val scene = seg("post_credits", "scene")
        return listOf(if (scene != null && scene.startMs < credits.endMs && scene.endMs > credits.startMs) credits.copy(endMs = scene.startMs) else credits)
            .filter { it.endMs > it.startMs }
    }

    /** Anime with an IMDb id: ani.zip gives its MAL id and which anime episode this season/episode is. */
    private suspend fun aniSkipForImdb(imdb: String, season: Int, episode: Int): List<Segment> {
        val (mal, eps) = anime.getOrPut(imdb) {
            val j = get("https://api.ani.zip/mappings?imdb_id=$imdb")?.let { runCatching { JSONObject(it) }.getOrNull() }
            (j?.optJSONObject("mappings")?.opt("mal_id")?.toString()?.takeIf { it != "null" } ?: "") to j?.optJSONObject("episodes")
        }
        if (mal.isEmpty()) return emptyList()
        val local = eps?.let { e -> e.keys().asSequence().firstOrNull { k ->
            val o = e.optJSONObject(k); o != null && o.optInt("seasonNumber") == season && o.optInt("episodeNumber") == episode
        }?.toIntOrNull() } ?: if (season == 1) episode else return emptyList()
        return aniSkip(mal, local)
    }

    private suspend fun malFor(key: String, value: String): String? {
        val (mal, _) = anime.getOrPut("$key:$value") {
            val j = get("https://api.ani.zip/mappings?$key=$value")?.let { runCatching { JSONObject(it) }.getOrNull() }
            (j?.optJSONObject("mappings")?.opt("mal_id")?.toString()?.takeIf { it != "null" } ?: "") to null
        }
        return mal.ifEmpty { null }
    }

    private suspend fun aniSkip(mal: String, episode: Int): List<Segment> {
        val j = get("https://api.aniskip.com/v2/skip-times/$mal/$episode?types[]=op&types[]=ed&types[]=recap&types[]=mixed-op&types[]=mixed-ed&episodeLength=0")
            ?.let { JSONObject(it) } ?: return emptyList()
        if (!j.optBoolean("found")) return emptyList()
        val r = j.optJSONArray("results") ?: return emptyList()
        return (0 until r.length()).mapNotNull { i ->
            val o = r.getJSONObject(i); val iv = o.getJSONObject("interval")
            val kind = when (o.optString("skipType")) { "op", "mixed-op" -> "intro"; "ed", "mixed-ed" -> "outro"; "recap" -> "recap"; else -> return@mapNotNull null }
            Segment((iv.optDouble("startTime") * 1000).toLong(), (iv.optDouble("endTime") * 1000).toLong(), kind).takeIf { it.endMs > it.startMs }
        }.distinctBy { it.kind }
    }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        runCatching { client.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.string() else null } }.getOrNull()
    }
}
