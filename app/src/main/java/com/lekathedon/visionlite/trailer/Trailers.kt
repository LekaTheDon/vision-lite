// Vision's IMDb trailer source (data/trailer/ImdbTrailerApi + ImdbTrailerData + ImdbStreamSelector), ported to
// Lite: org.json instead of Gson, no title-page WebView fallback, no DI.
package com.lekathedon.visionlite.trailer

import android.os.SystemClock
import com.lekathedon.visionlite.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** A title's main trailer straight from IMDb's data service: one small request, then a direct video link. */
object Trailers {
    private val ENDPOINT get() = com.lekathedon.visionlite.data.Our.imdb
    private const val CLIENT_NAME = "imdb-web-next-localized"
    private const val BUDGET_MS = 4_000L
    // Up to 20 of the title's videos with their kind and owner: IMDb's first one is often a clip, an interview or a
    // featurette about someone else (Ray 2004 → "The Rise of Jamie Foxx"), so only a Trailer of this very title counts.
    private const val QUERY = "query TitleTrailer(\$id: ID!) { title(id: \$id) { primaryVideos(first: 20) " +
        "{ edges { node { id name { value } contentType { id } primaryTitle { id } playbackURLs { url videoMimeType videoDefinition } } } } } }"

    private val client = Net.http.newBuilder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS).build()

    /** Per title + cap: the link, or "" for a title with none (asked once a session). */
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 200
    }
    private val backoff = Backoff { SystemClock.elapsedRealtime() }

    data class Encoding(val url: String, val height: Int, val mp4: Boolean, val hls: Boolean)

    /** The trailer link for IMDb id [imdbId] at most [maxHeight] tall, or null (none, not an IMDb id, or IMDb not answering). */
    suspend fun find(imdbId: String, maxHeight: Int): String? {
        val id = imdbId.substringBefore(':')
        if (!Regex("tt\\d+").matches(id)) return null
        val key = "$id@$maxHeight"
        synchronized(cache) { cache[key] }?.let { return it.ifEmpty { null } }
        if (!backoff.allowed()) return null
        val request = Request.Builder().url(ENDPOINT).header("x-imdb-client-name", CLIENT_NAME)
            .post(requestBody(id).toRequestBody("application/json".toMediaType())).build()
        val body = withTimeoutOrNull(BUDGET_MS) { post(request) }
        if (body == null) { backoff.failed(); return null }
        if (body.isEmpty()) { backoff.refused(); return null }
        val encodings = parse(body, id)
        if (encodings == null) {
            if (hasErrors(body)) backoff.refused()
            return null
        }
        backoff.succeeded()
        val url = select(encodings, maxHeight)
        synchronized(cache) { cache[key] = url.orEmpty() }
        return url
    }

    /**
     * IMDb's answer as encodings of the title's own trailer; empty list = it has none; null = no usable answer.
     * Picks a video IMDb files as a Trailer whose title is [imdbId] itself; among those an "official"/"Trailer #1" one,
     * never a teaser, clip, featurette or interview, nor anything about another title.
     */
    private fun parse(json: String, imdbId: String): List<Encoding>? = runCatching {
        val edges = JSONObject(json).optJSONObject("data")?.optJSONObject("title")
            ?.optJSONObject("primaryVideos")?.optJSONArray("edges") ?: return null
        val trailers = (0 until edges.length()).mapNotNull { edges.optJSONObject(it)?.optJSONObject("node") }.filter { n ->
            n.optJSONObject("contentType")?.optString("id")?.endsWith(".trailer") == true &&
                n.optJSONObject("primaryTitle")?.optString("id") == imdbId
        }
        if (trailers.isEmpty()) return emptyList()
        fun name(n: JSONObject) = n.optJSONObject("name")?.optString("value").orEmpty().lowercase()
        val bad = Regex("teaser|featurette|interview|behind the scenes|clip|recap|review|anniversary|characters|spot|promo|first look|sneak")
        val node = trailers.firstOrNull { name(it).let { n -> ("official" in n || "trailer #1" in n || "trailer 1" in n) && !bad.containsMatchIn(n) } }
            ?: trailers.firstOrNull { !bad.containsMatchIn(name(it)) } ?: trailers.first()
        val id = node.optString("id")
        if (!Regex("vi\\d{6,}").matches(id)) return null
        val urls = node.optJSONArray("playbackURLs") ?: JSONArray()
        (0 until urls.length()).mapNotNull { i ->
            val e = urls.optJSONObject(i) ?: return@mapNotNull null
            val url = e.optString("url").replace("&amp;", "&").takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            val mime = e.optString("videoMimeType")
            val hls = mime.contains("m3u8", true) || url.contains(".m3u8", true)
            Encoding(url, height(e.optString("videoDefinition")), !hls && (mime.contains("mp4", true) || url.contains(".mp4", true)), hls)
        }
    }.getOrNull()

    /** The tallest mp4 of at least 720p and at most [cap]; below the cap, IMDb's HLS master if it goes higher. */
    private suspend fun select(encodings: List<Encoding>, cap: Int): String? {
        val mp4s = encodings.filter { it.mp4 && it.height >= 720 }
        val mp4 = mp4s.filter { it.height <= cap }.maxByOrNull { it.height } ?: mp4s.minByOrNull { it.height }
        if (mp4 != null && mp4.height >= cap) return mp4.url
        var best = mp4?.url
        var height = mp4?.height ?: 0
        withTimeoutOrNull(2_000L) {
            for (e in encodings.filter { it.hls }.distinctBy { it.url }.take(2)) {
                val manifest = fetchSmall(e.url) ?: continue
                val h = highestManifestResolution(manifest)
                if (h >= 720 && h > height) { best = e.url; height = h }
            }
        }
        return best ?: encodings.filter { it.mp4 }.maxByOrNull { it.height }?.url
    }

    /** "4K", "FHD", "DEF_720p", "1080P" -> lines of picture; 0 when the label names none. */
    fun height(label: String): Int {
        val l = label.uppercase().replace("DEF_", "").replace(" ", "")
        NAMED[l]?.let { return it }
        return DIGITS.find(l)?.value?.toIntOrNull() ?: 0
    }
    private val NAMED = mapOf("4K" to 2160, "UHD" to 2160, "2K" to 1440, "QHD" to 1440, "FHD" to 1080, "FULLHD" to 1080, "HD" to 720, "SD" to 480)
    private val DIGITS = Regex("\\d{3,4}")
    private val STREAM_HEIGHT = Regex("RESOLUTION=\\d+x(\\d+)")

    /** Tallest variant an HLS master playlist lists (0 = not a master playlist). */
    fun highestManifestResolution(manifest: String): Int {
        if (!manifest.trimStart().startsWith("#EXTM3U")) return 0
        var best = 0
        var pending = 0
        for (raw in manifest.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> pending = STREAM_HEIGHT.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                line.isNotEmpty() && !line.startsWith('#') -> { if (pending > best) best = pending; pending = 0 }
            }
        }
        return best
    }

    private fun requestBody(imdbId: String): String =
        JSONObject().put("query", QUERY).put("variables", JSONObject().put("id", imdbId)).toString()

    private fun hasErrors(body: String): Boolean = runCatching { JSONObject(body).optJSONArray("errors") != null }.getOrDefault(false)

    /** The body, "" when IMDb refused, null on a network failure. */
    private suspend fun post(request: Request): String? = suspendCancellableCoroutine { c ->
        val call = client.newCall(request)
        c.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (c.isActive) c.resume(null) }
            override fun onResponse(call: Call, response: Response) {
                val out = response.use { if (!it.isSuccessful) "" else runCatching { it.body?.string() }.getOrNull() }
                if (c.isActive) c.resume(out)
            }
        })
    }

    /** A master playlist (small); never reads past 256 KB. */
    private suspend fun fetchSmall(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val stream = r.body?.byteStream() ?: return@use null
                val buffer = ByteArray(8 * 1024)
                val out = java.io.ByteArrayOutputStream()
                while (out.size() <= 256 * 1024) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
                if (out.size() > 256 * 1024) null else out.toString("UTF-8")
            }
        }.getOrNull()
    }

    /** Half an hour after a refusal, five minutes after two failures in a row (Vision's ImdbApiBackoff). */
    private class Backoff(private val now: () -> Long) {
        private var failures = 0
        private var pausedUntil = Long.MIN_VALUE
        @Synchronized fun allowed() = now() >= pausedUntil
        @Synchronized fun succeeded() { failures = 0 }
        @Synchronized fun refused() { failures = 0; pausedUntil = now() + 30 * 60_000L }
        @Synchronized fun failed() { if (++failures < 2) return; failures = 0; pausedUntil = now() + 5 * 60_000L }
    }
}
