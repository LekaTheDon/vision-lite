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
        loggedIn = false; jar.clear(); TbSources.unpause(KEY)
    }
    fun forget() { prefs.edit().remove("arabp2p_user").remove("arabp2p_pass").apply(); loggedIn = false; jar.clear() }

    private val jar = object : CookieJar {
        private val store = HashMap<String, Cookie>()
        fun clear() = synchronized(store) { store.clear() }
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = synchronized(store) { cookies.forEach { store[it.name] = it } }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(store) { store.values.filter { it.matches(url) } }
    }
    private val http by lazy {
        Net.http.newBuilder().cookieJar(jar).followRedirects(true).connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    }
    @Volatile private var loggedIn = false
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
            runCatching {
                get("$BASE/index.php") // session cookie
                val body = FormBody.Builder().add("uid", user()).add("pwd", pass()).build()
                http.newCall(Request.Builder().url("$BASE/index.php?page=login").header("User-Agent", UA).post(body).build()).execute().close()
                loggedIn = get("$BASE/index.php").contains("logout.php?t=")
            }.onFailure { Log.w(TAG, "login: ${it::class.simpleName}") }
            loggedIn
        }
    }

    /** Does this account work? (Settings check after saving.) */
    suspend fun check(): Boolean = login(force = true)

    /** One search page → candidates (hash + normalized name). Null = failed (not signed in, site down). */
    suspend fun search(queries: List<String>): List<DiscoveryCandidate>? = withContext(Dispatchers.IO) {
        if (!login()) return@withContext null
        val out = LinkedHashMap<String, DiscoveryCandidate>()
        var anyOk = false
        for (q in queries.distinct().take(3)) {
            val html = runCatching { page(q) }.getOrNull() ?: continue
            val final = if (!html.contains("logout.php?t=") && login(force = true)) runCatching { page(q) }.getOrNull() ?: continue else html
            anyOk = true
            rows(final).forEach { if (it.hash !in out) out[it.hash] = it }
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
    private val SEEDS = Regex("""<span[^>]*title="Seeders"[^>]*>\s*(\d+)""", RegexOption.IGNORE_CASE)
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
        val fetchable = found.filter { it.second.first == null && it.second.second != null }.take(12).map { it.second.second }.toSet()
        found.map { (name, hd, ss) ->
            val (hash, dl) = hd
            async {
                val h = hash ?: if (dl != null && dl in fetchable) gate.withPermit { runCatching { hashOfTorrent("$BASE/$dl") }.getOrNull() } else null
                h?.let { DiscoveryCandidate(it, name, ss.first, ss.second, DiscoverySource.ARABP2P) }
            }
        }.awaitAll().filterNotNull()
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
