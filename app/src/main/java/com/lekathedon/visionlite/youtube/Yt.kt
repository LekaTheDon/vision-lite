package com.lekathedon.visionlite.youtube

import android.util.Log
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class YtItem(val videoId: String?, val playlistId: String?, val browseId: String?, val title: String, val sub: String, val thumb: String?) {
    val key get() = videoId ?: playlistId ?: browseId ?: title
    val image get() = thumb ?: videoId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
}
data class YtShelf(val title: String, val items: List<YtItem>)
data class YtStreams(val video: String?, val audio: String?, val hls: String?, val title: String, val author: String, val height: Int)

/**
 * A small YouTube / YouTube Music client of our own (Vision runs SmartTube's engine; this is the light way):
 *  - sign-in = YouTube's TV device code (approve at youtube.com/activate), the same way TVs sign in;
 *  - feeds and search = the TV and Music web APIs (InnerTube), read with one generic walker;
 *  - playback = the VR app's player answer, whose links play without deciphering.
 * Nothing is called until YouTube or Music opens. YouTube changes these without notice, so this can break.
 */
object Yt {
    private const val TAG = "Yt"
    private const val TV_UA = "Mozilla/5.0 (Linux armeabi-v7a; Android 12) Cobalt/25.lts.30.1034943-gold (unlike Gecko) v8/8.8.278.17-jit gles Starboard/15, Ugoos_AM9/1.0 (Ugoos, AM9, Wired)"
    private const val WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    const val VR_UA = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
    private val JSON = "application/json".toMediaType()

    private enum class Client(val cname: String, val version: String, val id: String, val ua: String, val host: String) {
        TV("TVHTML5", "7.20250929.13.00", "7", TV_UA, "https://www.youtube.com"),
        MUSIC("WEB_REMIX", "1.20250929.01.00", "67", WEB_UA, "https://music.youtube.com"),
        VR("ANDROID_VR", "1.65.10", "28", VR_UA, "https://www.youtube.com")
    }

    // ------------------------------------------------------------ sign-in

    data class Code(val device: String, val user: String, val url: String, val interval: Int, val expires: Int)
    private class Tokens(val access: String, val refresh: String, val expiresAt: Long)

    @Volatile private var tokens: Tokens? = load()
    val signedIn get() = tokens != null
    private val signedFlow = kotlinx.coroutines.flow.MutableStateFlow(tokens != null)
    val signed: kotlinx.coroutines.flow.StateFlow<Boolean> get() = signedFlow

    private fun load(): Tokens? = runCatching {
        val o = JSONObject(App.instance.prefs.ytTokens.value.ifEmpty { return null })
        Tokens(o.getString("a"), o.getString("r"), o.getLong("e"))
    }.getOrNull()

    private fun store(t: Tokens?) {
        tokens = t; signedFlow.value = t != null
        App.instance.prefs.ytTokens.value = t?.let { JSONObject().put("a", it.access).put("r", it.refresh).put("e", it.expiresAt).toString() } ?: ""
    }

    fun signOut() = store(null)

    @Volatile private var creds: Pair<String, String>? = null
    /** The TV app's own client id and secret, read from youtube.com/tv's script (as SmartTube does); known values if that fails. */
    private fun creds(): Pair<String, String> = creds ?: runCatching {
        val page = text(Request.Builder().url("https://www.youtube.com/tv").header("User-Agent", TV_UA).build())
        val js = Regex("<script id=\"base-js\" src=\"([^\"]+)\"").find(page)!!.groupValues[1].let { if (it.startsWith("http")) it else "https://www.youtube.com$it" }
        val m = Regex("clientId:\"([-\\w]+\\.apps\\.googleusercontent\\.com)\",\\n?[$\\w]+:\"([-\\w]+)\"").find(text(Request.Builder().url(js).header("User-Agent", TV_UA).build()))!!
        m.groupValues[1] to m.groupValues[2]
    }.getOrElse { Log.w(TAG, "creds: ${it.message}"); "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com" to "SboVhoG9s0rNafixCSGGKXAT" }.also { creds = it }

    suspend fun startLogin(): Code = withContext(Dispatchers.IO) {
        val (id, _) = creds()
        val o = JSONObject(text(Request.Builder().url("https://www.youtube.com/o/oauth2/device/code").header("User-Agent", TV_UA)
            .post(FormBody.Builder().add("client_id", id).add("scope", "http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content")
                .add("device_id", UUID.randomUUID().toString()).add("device_model", "ytlr::").build()).build()))
        Code(o.getString("device_code"), o.getString("user_code"), o.optString("verification_url", "https://www.youtube.com/activate"), o.optInt("interval", 5), o.optInt("expires_in", 1800))
    }

    /** Polls until the code is approved (true), refused or expired (false). */
    suspend fun waitForLogin(c: Code): Boolean = withContext(Dispatchers.IO) {
        val (id, secret) = creds()
        val until = System.currentTimeMillis() + c.expires * 1000L
        var every = c.interval * 1000L
        while (System.currentTimeMillis() < until) {
            delay(every)
            val o = runCatching { JSONObject(raw(Request.Builder().url("https://www.youtube.com/o/oauth2/token").header("User-Agent", TV_UA)
                .post(FormBody.Builder().add("client_id", id).add("client_secret", secret).add("code", c.device)
                    .add("grant_type", "http://oauth.net/grant_type/device/1.0").build()).build())) }.getOrNull() ?: continue
            when (o.optString("error")) {
                "" -> { store(Tokens(o.getString("access_token"), o.getString("refresh_token"), System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000)); return@withContext true }
                "authorization_pending" -> {}
                "slow_down" -> every += 2000
                else -> return@withContext false
            }
        }
        false
    }

    private fun bearer(): String? {
        val t = tokens ?: return null
        if (System.currentTimeMillis() < t.expiresAt - 60_000) return t.access
        val (id, secret) = creds()
        return runCatching {
            val o = JSONObject(text(Request.Builder().url("https://www.youtube.com/o/oauth2/token").header("User-Agent", TV_UA)
                .post(FormBody.Builder().add("client_id", id).add("client_secret", secret).add("refresh_token", t.refresh).add("grant_type", "refresh_token").build()).build()))
            Tokens(o.getString("access_token"), t.refresh, System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000).also(::store).access
        }.getOrElse { e -> if (e.message?.contains("invalid_grant") == true) store(null); null }
    }

    // ------------------------------------------------------------ InnerTube

    private fun context(c: Client) = JSONObject().put("client", JSONObject().put("clientName", c.cname).put("clientVersion", c.version).put("hl", "en").put("gl", "US").apply {
        if (c == Client.VR) { put("deviceMake", "Oculus"); put("deviceModel", "Quest 3"); put("androidSdkVersion", 32); put("osName", "Android"); put("osVersion", "12L") }
    })

    private fun call(c: Client, endpoint: String, body: JSONObject, auth: Boolean): JSONObject {
        body.put("context", context(c))
        fun req(withAuth: Boolean) = Request.Builder().url("${c.host}/youtubei/v1/$endpoint?prettyPrint=false")
            .header("User-Agent", c.ua).header("X-Youtube-Client-Name", c.id).header("X-Youtube-Client-Version", c.version)
            .header("Origin", c.host).header("Referer", c.host + "/")
            .apply { if (withAuth) bearer()?.let { header("Authorization", "Bearer $it") } }
            .post(body.toString().toRequestBody(JSON)).build()
        // Music doesn't always take the TV sign-in; then it answers signed out.
        return runCatching { JSONObject(text(req(auth))) }.getOrElse { if (auth && tokens != null) JSONObject(text(req(false))) else throw it }
    }

    suspend fun home(): List<YtShelf> = withContext(Dispatchers.IO) { shelves(call(Client.TV, "browse", JSONObject().put("browseId", "FEwhat_to_watch"), true)) }
    suspend fun subscriptions(): List<YtShelf> = withContext(Dispatchers.IO) { shelves(call(Client.TV, "browse", JSONObject().put("browseId", "FEsubscriptions"), true)) }
    suspend fun search(q: String): List<YtShelf> = withContext(Dispatchers.IO) { shelves(call(Client.TV, "search", JSONObject().put("query", q), true)) }

    suspend fun musicHome(): List<YtShelf> = withContext(Dispatchers.IO) { shelves(call(Client.MUSIC, "browse", JSONObject().put("browseId", "FEmusic_home"), true)) }
    suspend fun musicBrowse(id: String): List<YtShelf> = withContext(Dispatchers.IO) { shelves(call(Client.MUSIC, "browse", JSONObject().put("browseId", id), true)) }
    suspend fun musicSearch(q: String): List<YtShelf> = withContext(Dispatchers.IO) { shelves(call(Client.MUSIC, "search", JSONObject().put("query", q), true)) }
    /** A queue: the song's radio, or a playlist / album in order. */
    suspend fun queue(videoId: String?, playlistId: String?): List<YtItem> = withContext(Dispatchers.IO) {
        val b = JSONObject().put("isAudioOnly", true)
        videoId?.let { b.put("videoId", it) }
        b.put("playlistId", playlistId ?: "RDAMVM$videoId")
        shelves(call(Client.MUSIC, "next", b, true)).flatMap { it.items }.filter { it.videoId != null }.distinctBy { it.videoId }
    }

    private val streamCache = ConcurrentHashMap<String, Pair<Long, YtStreams>>()

    /** Playable links for a video: best video (to 2160p) + best audio, or HLS for live. Cached 3 hours. */
    fun streams(videoId: String): YtStreams {
        streamCache[videoId]?.let { (at, s) -> if (System.currentTimeMillis() - at < 3 * 3600_000L) return s }
        val o = call(Client.VR, "player", JSONObject().put("videoId", videoId).put("contentCheckOk", true).put("racyCheckOk", true), false)
        val ps = o.optJSONObject("playabilityStatus")
        if (ps?.optString("status") != "OK") throw IOException(ps?.optString("reason")?.ifBlank { null } ?: "Not playable")
        val sd = o.optJSONObject("streamingData") ?: throw IOException("No streams")
        val fmts = sd.optJSONArray("adaptiveFormats") ?: JSONArray()
        val list = (0 until fmts.length()).map { fmts.getJSONObject(it) }.filter { it.has("url") }
        fun codecRank(m: String) = when { "vp9" in m || "vp09" in m -> 0; "avc1" in m -> 1; "av01" in m -> 2; else -> 3 }
        val video = list.filter { it.optString("mimeType").startsWith("video/") && it.optInt("height") <= 2160 }
            .sortedWith(compareByDescending<JSONObject> { it.optInt("height") }.thenBy { codecRank(it.optString("mimeType")) }.thenByDescending { it.optInt("fps") }).firstOrNull()
        val audio = list.filter { it.optString("mimeType").startsWith("audio/") }.maxByOrNull { it.optInt("bitrate") }
        val d = o.optJSONObject("videoDetails")
        val s = YtStreams(video?.getString("url"), audio?.getString("url"), sd.optString("hlsManifestUrl").ifBlank { null },
            d?.optString("title").orEmpty(), d?.optString("author").orEmpty(), video?.optInt("height") ?: 0)
        streamCache[videoId] = System.currentTimeMillis() to s
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

    private fun raw(r: Request): String = Net.http.newCall(r).execute().use { it.body?.string() ?: "" }
    private fun text(r: Request): String = Net.http.newCall(r).execute().use { res ->
        val b = res.body?.string() ?: ""
        if (!res.isSuccessful) throw IOException("HTTP ${res.code}: ${b.take(160)}")
        b
    }
}
