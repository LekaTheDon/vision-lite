package com.lekathedon.visionlite.youtube

import com.lekathedon.visionlite.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

data class YtItem(val videoId: String?, val playlistId: String?, val browseId: String?, val title: String, val sub: String, val thumb: String?) {
    val key get() = videoId ?: playlistId ?: browseId ?: title
    val image get() = thumb ?: videoId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
}
data class YtShelf(val title: String, val items: List<YtItem>)
data class YtStreams(val video: String?, val audio: String?, val hls: String?, val title: String, val author: String, val height: Int)

/**
 * What's left of Lite's YouTube client after YouTube and Music left the app (2026-11-26, Debrid + IPTV only): just
 * enough for trailers and the services' intro clips. No sign-in, no feeds.
 *  - search = the TV web API (InnerTube), signed out, read with one generic walker (trailer search fallback);
 *  - playback = the visionOS / VR players' answer, whose links play without deciphering.
 * YouTube changes these without notice, so this can break.
 */
object Yt {
    private const val TAG = "Yt"
    private const val TV_UA = "Mozilla/5.0 (Linux armeabi-v7a; Android 12) Cobalt/25.lts.30.1034943-gold (unlike Gecko) v8/8.8.278.17-jit gles Starboard/15, Ugoos_AM9/1.0 (Ugoos, AM9, Wired)"
    private const val WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    const val VR_UA = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
    private val JSON = "application/json".toMediaType()

    private enum class Client(val cname: String, val version: String, val id: String, val ua: String, val host: String) {
        TV("TVHTML5", "7.20250929.13.00", "7", TV_UA, "https://www.youtube.com"),
        WEB("WEB", "2.20251101.01.00", "1", WEB_UA, "https://www.youtube.com"),
        VR("ANDROID_VR", "1.65.10", "28", VR_UA, "https://www.youtube.com"),
        // Vision's first player client (InAppYouTubeExtractor): plays without a bot check when sent with a visitor id.
        VISIONOS("VISIONOS", "1.02", "101", VISION_UA, "https://www.youtube.com")
    }
    private const val VISION_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
    @Volatile private var visitor: Pair<Long, String>? = null
    /** YouTube's visitor id from a watch page, kept 6 h (one page load). */
    private fun visitorId(videoId: String): String? {
        visitor?.let { (at, v) -> if (System.currentTimeMillis() - at < 6 * 3600_000L) return v }
        val html = runCatching { text(Request.Builder().url("https://www.youtube.com/watch?v=$videoId&hl=en").header("User-Agent", WEB_UA).build()) }.getOrNull() ?: return null
        val v = Regex("\"VISITOR_DATA\":\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: return null
        visitor = System.currentTimeMillis() to v
        return v
    }

    // ------------------------------------------------------------ InnerTube

    private fun context(c: Client) = JSONObject().put("client", JSONObject().put("clientName", c.cname).put("clientVersion", c.version).put("hl", "en").put("gl", "US").apply {
        if (c == Client.VISIONOS) { put("deviceMake", "Apple"); put("deviceModel", "RealityDevice17,1"); put("osName", "visionOS"); put("osVersion", "26.5.23O471") }
        if (c == Client.VR) { put("deviceMake", "Oculus"); put("deviceModel", "Quest 3"); put("androidSdkVersion", 32); put("osName", "Android"); put("osVersion", "12L") }
    })

    private fun call(c: Client, endpoint: String, body: JSONObject, auth: Boolean, visitorId: String? = null): JSONObject {
        body.put("context", context(c))
        @Suppress("UNUSED_PARAMETER") fun req(withAuth: Boolean) = Request.Builder().url("${c.host}/youtubei/v1/$endpoint?prettyPrint=false")
            .header("User-Agent", c.ua).header("X-Youtube-Client-Name", c.id).header("X-Youtube-Client-Version", c.version)
            .header("Origin", c.host).header("Referer", c.host + "/")
            .apply { if (visitorId != null) header("X-Goog-Visitor-Id", visitorId) }
            .post(body.toString().toRequestBody(JSON)).build()
        return JSONObject(text(req(auth)))
    }

    suspend fun search(q: String): List<YtShelf> = withContext(Dispatchers.IO) { shelves(call(Client.TV, "search", JSONObject().put("query", q), true)) }

    private val streamCache = ConcurrentHashMap<String, Pair<Long, YtStreams>>()

    /** Playable links for a video: best video (to 2160p) + best audio, or HLS for live. Cached 3 hours. */
    fun streams(videoId: String, maxHeight: Int = 2160): YtStreams {
        val ck = "$videoId@$maxHeight"
        streamCache[ck]?.let { (at, s) -> if (System.currentTimeMillis() - at < 3 * 3600_000L) return s }
        fun ask(c: Client, v: String?) = call(c, "player", JSONObject().put("videoId", videoId).put("contentCheckOk", true).put("racyCheckOk", true), false, v)
        // As Vision: visionOS with a visitor id first (the VR client now gets "confirm you're not a bot"), VR as the fallback.
        val o = visitorId(videoId)?.let { v -> runCatching { ask(Client.VISIONOS, v) }.getOrNull()?.takeIf { it.optJSONObject("playabilityStatus")?.optString("status") == "OK" } }
            ?: ask(Client.VR, null)
        val ps = o.optJSONObject("playabilityStatus")
        if (ps?.optString("status") != "OK") throw IOException(ps?.optString("reason")?.ifBlank { null } ?: "Not playable")
        val sd = o.optJSONObject("streamingData") ?: throw IOException("No streams")
        val fmts = sd.optJSONArray("adaptiveFormats") ?: JSONArray()
        val list = (0 until fmts.length()).map { fmts.getJSONObject(it) }.filter { it.has("url") }
        fun codecRank(m: String) = when { "vp9" in m || "vp09" in m -> 0; "avc1" in m -> 1; "av01" in m -> 2; else -> 3 }
        val video = list.filter { it.optString("mimeType").startsWith("video/") && it.optInt("height") <= maxHeight }
            .sortedWith(compareByDescending<JSONObject> { it.optInt("height") }.thenBy { codecRank(it.optString("mimeType")) }.thenByDescending { it.optInt("fps") }).firstOrNull()
        val audio = list.filter { it.optString("mimeType").startsWith("audio/") }.maxByOrNull { it.optInt("bitrate") }
        val d = o.optJSONObject("videoDetails")
        val s = YtStreams(video?.getString("url"), audio?.getString("url"), sd.optString("hlsManifestUrl").ifBlank { null },
            d?.optString("title").orEmpty(), d?.optString("author").orEmpty(), video?.optInt("height") ?: 0)
        streamCache[ck] = System.currentTimeMillis() to s
        return s
    }

    // ------------------------------------------------------------ the walker

    private val SHELVES = setOf("shelfRenderer", "richShelfRenderer", "reelShelfRenderer", "musicCarouselShelfRenderer", "musicShelfRenderer",
        "musicPlaylistShelfRenderer", "playlistPanelRenderer", "gridRenderer", "horizontalListRenderer")
    private val ITEMS = setOf("tileRenderer", "videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "lockupViewModel", "musicTwoRowItemRenderer",
        "musicResponsiveListItemRenderer", "playlistPanelVideoRenderer", "playlistVideoRenderer", "musicCardShelfRenderer")
    private val SKIP = setOf("content", "contents", "items", "tiles", "playlistItemData")

    /** Finds every shelf (and its items) in an answer, whatever the layout; loose items go to a last "More" shelf. */
    fun shelves(root: JSONObject): List<YtShelf> {
        val out = ArrayList<Pair<String, MutableList<YtItem>>>()
        val loose = ArrayList<YtItem>()
        fun walk(v: Any?, into: MutableList<YtItem>) {
            when (v) {
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i), into)
                is JSONObject -> for (k in v.keys()) {
                    val child = v.opt(k)
                    when {
                        k in ITEMS && child is JSONObject -> item(k, child)?.let(into::add)
                        k in SHELVES && child is JSONObject -> {
                            val list = ArrayList<YtItem>(); out += titleOf(child) to list
                            walk(child, list)
                        }
                        k == "frameworkUpdates" || k == "responseContext" -> {}
                        else -> walk(child, into)
                    }
                }
            }
        }
        walk(root, loose)
        return (out.map { (t, l) -> YtShelf(t, l.distinctBy { it.key }) } + YtShelf("More", loose.distinctBy { it.key }))
            .filter { it.items.isNotEmpty() }
            .fold(ArrayList<YtShelf>()) { acc, s ->  // a shelf whose items are shelves of their own shows once
                if (acc.none { a -> a.items.map { it.key } == s.items.map { it.key } }) acc += s; acc
            }
    }

    private fun titleOf(shelf: JSONObject): String {
        fun find(v: Any?, depth: Int): String? = when {
            depth > 6 -> null
            v is JSONObject -> v.keys().asSequence().filter { it !in SKIP }.firstNotNullOfOrNull { k ->
                if (k == "title" || k == "headerText") text(v.opt(k))?.takeIf { it.isNotBlank() } else find(v.opt(k), depth + 1)
            }
            else -> null
        }
        return find(shelf, 0) ?: ""
    }

    private fun item(kind: String, o: JSONObject): YtItem? {
        val vid = first(o, "videoId")?.takeIf { it.length == 11 }
        val pl = first(o, "playlistId")
        val br = first(o, "browseId")
        if (vid == null && pl == null && br == null) return null
        val title: String
        val sub: String
        when (kind) {
            "tileRenderer" -> {
                val md = o.optJSONObject("metadata")?.optJSONObject("tileMetadataRenderer")
                title = text(md?.opt("title")) ?: ""
                sub = md?.optJSONArray("lines")?.let { ls -> (0 until ls.length()).flatMap { i ->
                    ls.optJSONObject(i)?.optJSONObject("lineRenderer")?.optJSONArray("items")?.let { its -> (0 until its.length()).mapNotNull { j ->
                        text(its.optJSONObject(j)?.optJSONObject("lineItemRenderer")?.opt("text"))
                    } }.orEmpty() }.filter { it.isNotBlank() && it != "•" }.joinToString(" · ") }.orEmpty()
            }
            "lockupViewModel" -> {
                val md = o.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
                title = text(md?.opt("title")) ?: ""
                sub = ""
            }
            "musicResponsiveListItemRenderer" -> {
                val cols = o.optJSONArray("flexColumns") ?: JSONArray()
                fun col(i: Int) = text(cols.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.opt("text"))
                title = col(0) ?: ""
                sub = listOfNotNull(col(1), col(2)).joinToString(" · ")
            }
            else -> {
                title = text(o.opt("title")) ?: text(o.opt("headline")) ?: ""
                sub = text(o.opt("subtitle")) ?: text(o.opt("longBylineText")) ?: text(o.opt("shortBylineText")) ?: text(o.opt("ownerText")) ?: ""
            }
        }
        if (title.isBlank()) return null
        return YtItem(vid, pl, if (vid == null) br else null, title, sub, thumb(o))
    }

    private fun first(v: Any?, key: String, depth: Int = 0): String? = if (depth > 14) null else when (v) {
        is JSONObject -> (v.opt(key) as? String) ?: v.keys().asSequence().firstNotNullOfOrNull { first(v.opt(it), key, depth + 1) }
        is JSONArray -> (0 until v.length()).firstNotNullOfOrNull { first(v.opt(it), key, depth + 1) }
        else -> null
    }

    private fun thumb(o: Any?, depth: Int = 0): String? = if (depth > 10) null else when (o) {
        is JSONObject -> (o.opt("thumbnails") as? JSONArray ?: o.opt("sources") as? JSONArray)?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.maxByOrNull { it.optInt("width") }?.optString("url")?.let { if (it.startsWith("//")) "https:$it" else it }
        } ?: o.keys().asSequence().firstNotNullOfOrNull { thumb(o.opt(it), depth + 1) }
        is JSONArray -> (0 until o.length()).firstNotNullOfOrNull { thumb(o.opt(it), depth + 1) }
        else -> null
    }

    fun text(v: Any?): String? = when (v) {
        is String -> v
        is JSONObject -> v.optString("simpleText").ifBlank { null } ?: v.optString("content").ifBlank { null }
            ?: v.optJSONArray("runs")?.let { r -> (0 until r.length()).joinToString("") { r.optJSONObject(it)?.optString("text").orEmpty() } }?.ifBlank { null }
        else -> null
    }

    // ------------------------------------------------------------ http

    private fun text(r: Request): String = Net.http.newCall(r).execute().use { res ->
        val b = res.body?.string() ?: ""
        if (!res.isSuccessful) throw IOException("HTTP ${res.code}: ${b.take(160)}")
        b
    }
}
