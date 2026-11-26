package com.lekathedon.visionlite.tb

import android.util.Log
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * ArabP2P (user 2026-11-23): the Arabic private tracker behind Aradeb, searched with YOUR account, so Arabic films and
 * series reach Vision TB with no add-on and no 3-month key. Login and search follow Jackett's open arabp2p.yml
 * (form login uid/pwd, index.php?page=torrents&search=). Each row's hash comes from its magnet, or from its .torrent
 * (info dict SHA-1). Only hashes TorBox already has cached are ever shown: nothing is added to TorBox, so the account
 * never downloads or uploads (no ratio). Logging in on each app run keeps the account active (3-month rule).
 */
object ArabP2P {
    private const val TAG = "ArabP2P"
    private const val BASE = "https://www.arabp2p.net"
    const val KEY = "ARABP2P"
    private val prefs get() = App.instance.prefs.sp

    fun user(): String = prefs.getString("arabp2p_user", "").orEmpty()
    private fun pass(): String = prefs.getString("arabp2p_pass", "").orEmpty()
    fun configured() = user().isNotBlank() && pass().isNotBlank()
    fun save(u: String, p: String) {
        prefs.edit().putString("arabp2p_user", u.trim()).putString("arabp2p_pass", p).apply()
        loggedIn = false; retryAt = 0L; jar.clear(); TbSources.unpause(KEY)
    }
    fun forget() { prefs.edit().remove("arabp2p_user").remove("arabp2p_pass").apply(); loggedIn = false; jar.clear() }

    /**
     * The site's session, kept on disk (user 2026-11-23: "sign one time"). The site locks you out for 30 min after a
     * few logins, so the app signs in once and reuses the session across runs; it logs in again only when the site
     * no longer accepts it (and never twice inside 31 min).
     */
    private val jar = object : CookieJar {
        private val store = HashMap<String, Cookie>()
        private var loaded = false
        private fun load() {
            if (loaded) return; loaded = true
            runCatching {
                val a = org.json.JSONArray(prefs.getString("arabp2p_session", "[]"))
                val u = "$BASE/".toHttpUrl()
                for (i in 0 until a.length()) Cookie.parse(u, a.getString(i))?.let { store[it.name] = it }
            }
        }
        private fun save() = prefs.edit().putString("arabp2p_session", org.json.JSONArray(store.values.map { c ->
            // A session cookie (no expiry) is kept 30 days on disk; the site says when it's no longer good.
            if (c.persistent) c.toString() else c.toString() + "; Max-Age=2592000"
        }).toString()).apply()
        fun clear() = synchronized(store) { store.clear(); loaded = true; prefs.edit().remove("arabp2p_session").apply() }
        fun has() = synchronized(store) { load(); store.isNotEmpty() }
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = synchronized(store) { load(); cookies.forEach { store[it.name] = it }; save() }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(store) {
            load(); store.values.removeAll { it.expiresAt < System.currentTimeMillis() }; store.values.filter { it.matches(url) }
        }
    }
    private val http by lazy {
        Net.http.newBuilder().cookieJar(jar).followRedirects(true).connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    }
    @Volatile private var loggedIn = false
    @Volatile private var retryAt = 0L
    private val lock = Mutex()
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Safari/537.36"

    private fun get(url: String): String = http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use {
        if (!it.isSuccessful) throw java.io.IOException("HTTP ${it.code}"); it.body?.string().orEmpty()
    }

    /** Signs in once per app run (again when the site asks); false = wrong account or the site changed. */
    suspend fun login(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            if (loggedIn && !force) return@withLock true
            if (!configured()) return@withLock false
            // The site locks the IP for 30 min after a few failed logins: never try again inside that window.
            if (android.os.SystemClock.elapsedRealtime() < retryAt) return@withLock false
            runCatching {
                get("$BASE/index.php") // session cookie
                val body = FormBody.Builder().add("uid", user()).add("pwd", pass()).build()
                http.newCall(Request.Builder().url("$BASE/index.php?page=login").header("User-Agent", UA).post(body).build()).execute().close()
                loggedIn = get("$BASE/index.php").contains("logout.php?t=")
            }.onFailure { Log.w(TAG, "login: ${it::class.simpleName}") }
            if (!loggedIn) retryAt = android.os.SystemClock.elapsedRealtime() + 31 * 60_000L
            loggedIn
        }
    }

    /** Does this account work? (Settings check after saving.) */
    suspend fun check(): Boolean = login(force = true)

    /** One search page → candidates (hash + normalized name). Null = failed (not signed in, site down). */
    suspend fun search(queries: List<String>): List<DiscoveryCandidate>? = withContext(Dispatchers.IO) {
        // A saved session is tried as is (no login call); the app signs in only when the site says signed out.
        if (!loggedIn && !jar.has() && !login()) return@withContext null
        val out = LinkedHashMap<String, DiscoveryCandidate>()
        var anyOk = false
        for (q in queries.distinct().take(3)) {
            var html = runCatching { page(q) }.getOrNull() ?: continue
            if (!html.contains("logout.php?t=")) {
                loggedIn = false; jar.clear()
                if (!login()) return@withContext null
                html = runCatching { page(q) }.getOrNull() ?: continue
            }
            loggedIn = true; anyOk = true
            rows(html).forEach { if (it.hash !in out) out[it.hash] = it }
            if (out.size >= 40) break
        }
        if (!anyOk) null else out.values.toList()
    }

    private fun page(q: String) = get("$BASE/index.php?page=torrents&search=${URLEncoder.encode(q, "UTF-8")}&category=0&active=0&internel=0&order=5&by=2")

    private val ROW = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val TITLE = Regex("""<a[^>]+href="index\.php\?page=torrent-details[^"]*"[^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val MAGNET = Regex("""href="(magnet:\?xt=urn:btih:[^"]+)"""", RegexOption.IGNORE_CASE)
    private val BTIH = Regex("""btih:([0-9a-fA-F]{40}|[A-Z2-7]{32})""")
    private val DOWNLOAD = Regex("""href="(download\.php\?id=[^"]+)"""", RegexOption.IGNORE_CASE)
    private val SIZE = Regex("""<span[^>]*class="size"[^>]*>\s*([\d.,]+)\s*([KMGT]i?B)""", RegexOption.IGNORE_CASE)
    // <span title="Seeders">▲ <a href='index.php?page=peers…'>12</a></span>
    private val SEEDS = Regex("""title="Seeders"[^>]*>[^<\d]*(?:<a[^>]*>)?\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val TAGS = Regex("<[^>]+>")

    private suspend fun rows(html: String): List<DiscoveryCandidate> = coroutineScope {
        val found = ROW.findAll(html).mapNotNull { m ->
            val r = m.groupValues[1]
            val title = TITLE.find(r)?.groupValues?.get(1)?.replace(TAGS, "")?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val magnet = MAGNET.find(r)?.groupValues?.get(1)
            val dl = DOWNLOAD.find(r)?.groupValues?.get(1)?.replace("&amp;", "&")
            if (magnet == null && dl == null) return@mapNotNull null
            val size = SIZE.find(r)?.let { s ->
                val n = s.groupValues[1].replace(",", "").toDoubleOrNull() ?: return@let null
                val mult = when (s.groupValues[2].uppercase().first()) { 'K' -> 1L shl 10; 'M' -> 1L shl 20; 'G' -> 1L shl 30; else -> 1L shl 40 }
                (n * mult).toLong()
            }
            Triple(normalize(title), magnet?.let { BTIH.find(it)?.groupValues?.get(1) }?.let(InfoHashes::normalize) to dl, size to SEEDS.find(r)?.groupValues?.get(1)?.toIntOrNull())
        }.toList().take(30)
        // Rows without a magnet: their .torrent gives the hash (at most 12 a search, 4 at a time).
        val gate = kotlinx.coroutines.sync.Semaphore(4)
        val fetchable = found.filter { it.second.first == null && it.second.second != null && cachedHash(it.second.second!!) == null }
            .take(12).map { it.second.second }.toSet()
        found.map { (name, hd, ss) ->
            val (hash, dl) = hd
            async {
                val h = hash ?: dl?.let(::cachedHash) ?: if (dl != null && dl in fetchable)
                    gate.withPermit { runCatching { hashOfTorrent("$BASE/$dl") }.getOrNull() }?.also { keepHash(dl, it) } else null
                h?.let { DiscoveryCandidate(it, name, ss.first, ss.second, DiscoverySource.ARABP2P) }
            }
        }.awaitAll().filterNotNull()
    }

    // ---------------------------------------------------------------- browse (user 2026-11-26, off by default)
    data class Release(val name: String, val bytes: Long?, val seeds: Int?)

    /** A signed-in page; null when the site won't sign in. */
    private suspend fun signedPage(url: String): String? {
        if (!loggedIn && !jar.has() && !login()) return null
        var html = runCatching { get(url) }.getOrNull() ?: return null
        if (!html.contains("logout.php?t=")) {
            loggedIn = false; jar.clear()
            if (!login()) return null
            html = runCatching { get(url) }.getOrNull() ?: return null
        }
        loggedIn = true
        return html
    }

    private val CAT = Regex("""page=torrents&(?:amp;)?category=(\d+)[^>]*>([^<]{2,60})<""", RegexOption.IGNORE_CASE)

    /** The site's own categories (id, name), read from its torrents page. Empty = not signed in. */
    suspend fun categories(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val html = signedPage("$BASE/index.php?page=torrents") ?: return@withContext emptyList()
        CAT.findAll(html).map { it.groupValues[1] to unescape(it.groupValues[2]).trim() }.filter { it.first != "0" && it.second.isNotBlank() }
            .distinctBy { it.first }.toList()
    }

    /** One page of a category, newest first (no .torrent downloads: names, sizes and seeders only). */
    suspend fun browse(cat: String, page: Int): List<Release>? = withContext(Dispatchers.IO) {
        val html = signedPage("$BASE/index.php?page=torrents&category=$cat&active=0&order=3&by=2&pages=$page") ?: return@withContext null
        ROW.findAll(html).mapNotNull { m ->
            val r = m.groupValues[1]
            val title = TITLE.find(r)?.groupValues?.get(1)?.replace(TAGS, "")?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val size = SIZE.find(r)?.let { s ->
                val n = s.groupValues[1].replace(",", "").toDoubleOrNull() ?: return@let null
                val mult = when (s.groupValues[2].uppercase().first()) { 'K' -> 1L shl 10; 'M' -> 1L shl 20; 'G' -> 1L shl 30; else -> 1L shl 40 }
                (n * mult).toLong()
            }
            Release(title, size, SEEDS.find(r)?.groupValues?.get(1)?.toIntOrNull())
        }.distinctBy { it.name }.toList()
    }

    /**
     * The IMDb title a release is, from its name: the Latin part + year on IMDb's search; else (Arabic-only names) TMDB's
     * search in Arabic (with your TMDB key). Null = not found.
     */
    suspend fun imdbOf(name: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val year = Regex("\\b(19[3-9]\\d|20[0-4]\\d)\\b").find(name)?.value?.toIntOrNull()
        val series = Regex("(?i)\\bS\\d{1,2}(E\\d{1,3})?\\b|season|الموسم|الحلقة|مسلسل").containsMatchIn(name)
        val cut = name.replace(Regex("[\\[(].*?[\\])]"), " ").split(Regex("(?i)\\b(19[3-9]\\d|20[0-4]\\d|S\\d{1,2}|720p|1080p|2160p|4k|web|bluray|hdtv|x26[45]|hevc)\\b")).first()
        val latin = cut.replace(Regex("[^A-Za-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (latin.length >= 2) {
            val q = java.net.URLEncoder.encode(latin.lowercase(), "UTF-8").replace("+", "%20")
            runCatching { org.json.JSONObject(com.lekathedon.visionlite.data.Net.get("https://v3.sg.media-imdb.com/suggestion/x/$q.json", 10)).optJSONArray("d") }.getOrNull()?.let { d ->
                val hits = (0 until d.length()).mapNotNull { d.optJSONObject(it) }.filter { it.optString("id").startsWith("tt") &&
                    it.optString("qid") in setOf("movie", "tvSeries", "tvMiniSeries", "tvMovie") }
                (hits.firstOrNull { h -> year == null || kotlin.math.abs(h.optInt("y") - year) <= 1 } ?: hits.firstOrNull())?.let { h ->
                    return@withContext h.optString("id") to (if (h.optString("qid").startsWith("tv") && h.optString("qid") != "tvMovie") "series" else "movie")
                }
            }
        }
        val arabic = cut.replace(Regex("[^\\u0621-\\u064A ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (arabic.length >= 2) {
            val q = java.net.URLEncoder.encode(arabic, "UTF-8")
            com.lekathedon.visionlite.home.Tmdb.get("search/${if (series) "tv" else "multi"}?query=$q&language=ar")?.optJSONArray("results")?.let { r ->
                for (i in 0 until minOf(r.length(), 3)) {
                    val o = r.optJSONObject(i) ?: continue
                    val kind = if (series) "tv" else o.optString("media_type").takeIf { it == "tv" || it == "movie" } ?: continue
                    com.lekathedon.visionlite.home.Tmdb.imdbOf("tmdb:$kind:${o.optInt("id")}")?.let { return@withContext it to (if (kind == "tv") "series" else "movie") }
                }
            }
        }
        null
    }

    /** Torrent id → hash, kept on disk (a .torrent is ~50 KB; each is fetched once ever). Last 3000. */
    private val hashes: LinkedHashMap<String, String> by lazy {
        val m = object : LinkedHashMap<String, String>(256, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>?) = size > 3000 }
        runCatching { org.json.JSONObject(hashFile.readText()).let { o -> o.keys().forEach { k -> m[k] = o.getString(k) } } }
        m
    }
    private val hashFile get() = java.io.File(App.instance.filesDir, "arabp2p_hashes.json")
    private fun idOf(dl: String) = Regex("id=(\\d+)").find(dl)?.groupValues?.get(1)
    private fun cachedHash(dl: String): String? = idOf(dl)?.let { synchronized(hashes) { hashes[it] } }
    private fun keepHash(dl: String, h: String) {
        val id = idOf(dl) ?: return
        synchronized(hashes) { hashes[id] = h; runCatching { hashFile.writeText(org.json.JSONObject(hashes as Map<*, *>).toString()) } }
    }

    private fun hashOfTorrent(url: String): String? {
        val bytes = http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { if (it.isSuccessful) it.body?.bytes() else null } ?: return null
        return infoHash(bytes)
    }

    /** SHA-1 of the bencoded "info" dictionary. */
    internal fun infoHash(b: ByteArray): String? {
        if (b.isEmpty() || b[0] != 'd'.code.toByte()) return null
        var i = 1
        while (i < b.size && b[i] != 'e'.code.toByte()) {
            val (key, afterKey) = readString(b, i) ?: return null
            val end = skip(b, afterKey) ?: return null
            if (key == "info") return MessageDigest.getInstance("SHA-1").digest(b.copyOfRange(afterKey, end)).joinToString("") { "%02x".format(it) }
            i = end
        }
        return null
    }

    private fun readString(b: ByteArray, at: Int): Pair<String, Int>? {
        var i = at; var n = 0
        while (i < b.size && b[i] in '0'.code.toByte()..'9'.code.toByte()) { n = n * 10 + (b[i] - '0'.code.toByte()); i++ }
        if (i >= b.size || b[i] != ':'.code.toByte() || i + 1 + n > b.size) return null
        return String(b, i + 1, n, Charsets.ISO_8859_1) to (i + 1 + n)
    }

    private fun skip(b: ByteArray, at: Int): Int? {
        if (at >= b.size) return null
        return when (b[at].toInt().toChar()) {
            'i' -> { var i = at + 1; while (i < b.size && b[i] != 'e'.code.toByte()) i++; if (i < b.size) i + 1 else null }
            'l', 'd' -> { var i = at + 1; while (i < b.size && b[i] != 'e'.code.toByte()) i = skip(b, i) ?: return null; if (i < b.size) i + 1 else null }
            in '0'..'9' -> readString(b, at)?.second
            else -> null
        }
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

    /**
     * ArabP2P's Arabic bracket names as release names (Jackett's arabp2p.yml filters): "[5] [2026] [م1]" → "S01E05 [2026]",
     * "[E08] [S01]" → "S01E08", a leading "[12]" → "S01E12"; brackets spaced so the title reads as words.
     */
    internal fun normalize(t: String): String {
        var s = t.replace(Regex("(?<=\\d)\\s+(?=\\d)"), " ")
        s = s.replace(Regex("\\[(\\d+(?:[\\s-]+\\d+)*)\\]\\s*\\[(\\d{4})\\]\\s*\\[م(\\d+)\\]")) { "S${it.groupValues[3]}E${it.groupValues[1]} [${it.groupValues[2]}]" }
        s = s.replace(Regex("\\[(\\d+(?:[\\s-]+\\d+)*)\\]\\s*\\[م(\\d+)\\]")) { "S${it.groupValues[2]}E${it.groupValues[1]}" }
        s = s.replace(Regex("\\[(E\\d+(?:[\\s-]+E\\d+)?)\\]\\s*\\[(S\\d+)\\]")) { it.groupValues[2] + it.groupValues[1] }
        s = s.replace(Regex("\\[(S\\d+)\\]\\s*\\[(E\\d+(?:[\\s-]+E\\d+)?)\\]")) { it.groupValues[1] + it.groupValues[2] }
        s = s.replace(Regex("^\\[(\\d+(?:[\\s-]+\\d+)*)\\](?!\\s*\\[م)")) { "S01E${it.groupValues[1]}" }
        s = s.replace(Regex("E(\\d+)[\\s-]+(\\d+)\\b")) { "E${it.groupValues[1]}-E${it.groupValues[2]}" }
        s = s.replace(Regex("\\[\\s*]"), "").replace(Regex("(?<=\\S)(\\[)"), " [")
        s = s.replace(Regex("S(\\d)(?=E)")) { "S0${it.groupValues[1]}" }.replace(Regex("E(\\d)(?=\\D|$)")) { "E0${it.groupValues[1]}" }
        return s.replace(Regex("\\s{2,}"), " ").trim()
    }

    /** What to search: the Arabic title first (the site matches Arabic best), then the others. */
    fun queries(target: DiscoveryTarget): List<String> {
        val arabic = target.titles.filter { t -> t.any { it in '؀'..'ۿ' } }
        return (arabic + target.titles.filterNot { it in arabic }).map { it.replace(Regex("[:!?،]"), " ").replace(Regex("\\s+"), " ").trim() }
            .filter { it.length >= 2 }.distinct().take(3)
    }
}
