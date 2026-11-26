package com.lekathedon.visionlite.tb

import android.util.Base64
import android.util.Log
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.BuildConfig
import com.lekathedon.visionlite.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Hand fixes per title, outside the app's code (DebridStream's remote title-overrides, written for Lite): when a title
 * keeps getting the wrong show (Monster 2004 vs the Netflix Monster), a line in a small JSON fixes it for every box
 * without a new build.
 *
 *   { "v": 1, "titles": { "tt0434706": { "kind": "anime", "year": 2004, "end": 2005, "runtime": 24,
 *       "aka": ["Naoki Urasawa's Monster"], "reject": ["(?i)\\bdahmer\\b"] } } }
 *
 *  - kind / year / end / runtime replace what IMDb says (TitleFacts), so the year, type and length checks use them;
 *  - aka = extra names a release may carry; reject = patterns that are never this title (any source, autoplay too).
 *
 * The copy inside the app (assets/title_fixes.json) always works. A newer copy comes from FIXES_URL (build setting;
 * empty = off, no call) only with a valid signature (FIXES_URL + ".sig", ECDSA P-256 over the file, key below), so a
 * changed file on the host can't steer playback. Fetched at most every 12 h, when a link list opens, never at start.
 * Cost: a few KB in memory; with FIXES_URL set, ≤2 small requests per 12 h.
 */
object TitleFixes {
    private const val TAG = "TitleFixes"
    private const val PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE886C6S3lNj+VszsDgAxAskVaE0HRgU0PUWK8behYcFZe1mDGlX5RE0xLi35Z8ZqWBGB6Me184UpLn7VSDN01jQ=="
    private const val EVERY = 12 * 3600_000L

    class Fix(val kind: TitleFacts.Kind?, val year: Int?, val end: Int?, val runtime: Int?, val aka: List<String>, val reject: List<Regex>)

    @Volatile private var fixes: Map<String, Fix>? = null
    @Volatile private var askedAt = 0L

    private val cache get() = File(App.instance.filesDir, "title_fixes.json")

    private fun all(): Map<String, Fix> = fixes ?: synchronized(this) {
        fixes ?: run {
            val remote = runCatching { cache.takeIf { it.isFile }?.readText() }.getOrNull()?.let(::parse)
            val bundled = runCatching { App.instance.assets.open("title_fixes.json").bufferedReader().use { it.readText() } }.getOrNull()?.let(::parse)
            // The newer of the two wins as a whole ("v" is the file's version).
            (listOfNotNull(remote, bundled).maxByOrNull { it.first }?.second ?: emptyMap()).also { fixes = it }
        }
    }

    private fun parse(text: String): Pair<Int, Map<String, Fix>>? = runCatching {
        val o = JSONObject(text)
        val t = o.optJSONObject("titles") ?: JSONObject()
        o.optInt("v") to t.keys().asSequence().associateWith { id ->
            val j = t.getJSONObject(id)
            fun ints(k: String) = if (j.has(k)) j.optInt(k).takeIf { it > 0 } else null
            fun strs(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
            Fix(when (j.optString("kind")) { "anime" -> TitleFacts.Kind.ANIME; "show" -> TitleFacts.Kind.SHOW; "movie" -> TitleFacts.Kind.MOVIE; else -> null },
                ints("year"), ints("end"), ints("runtime"), strs("aka"),
                strs("reject").mapNotNull { p -> runCatching { Regex(p) }.onFailure { Log.w(TAG, "bad pattern for $id: $p") }.getOrNull() })
        }
    }.onFailure { Log.w(TAG, "unreadable: ${it.message}") }.getOrNull()

    fun of(imdb: String?): Fix? = imdb?.substringBefore(':')?.let { all()[it] }

    /** The facts with the hand fix laid over them. */
    fun apply(imdb: String, f: TitleFacts.Facts): TitleFacts.Facts {
        val x = of(imdb) ?: return f
        return f.copy(kind = x.kind ?: f.kind, year = x.year ?: f.year, endYear = x.end ?: f.endYear, runtimeMin = x.runtime ?: f.runtimeMin)
    }

    /** True when the release [name] is never [imdb] by the hand fix. */
    fun rejects(imdb: String?, name: String): Boolean = of(imdb)?.reject?.any { it.containsMatchIn(name) } == true

    fun aka(imdb: String?): List<String> = of(imdb)?.aka.orEmpty()

    /** A newer signed copy from FIXES_URL, at most every 12 h; nothing when the URL is empty. */
    suspend fun refresh() {
        val url = BuildConfig.FIXES_URL.ifBlank { return }
        val now = System.currentTimeMillis()
        if (now - askedAt < EVERY || (cache.isFile && now - cache.lastModified() < EVERY)) return
        askedAt = now
        withContext(Dispatchers.IO) {
            runCatching {
                fun get(u: String) = Net.http.newCall(Request.Builder().url(u).build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
                val body = get(url) ?: return@runCatching
                val sig = get("$url.sig") ?: return@runCatching
                if (!verified(body, String(sig).trim())) { Log.w(TAG, "signature doesn't match; kept the old fixes"); return@runCatching }
                val parsed = parse(String(body)) ?: return@runCatching
                if (parsed.first <= (fixesVersion() ?: 0)) { cache.setLastModified(now); return@runCatching }
                cache.writeBytes(body)
                synchronized(this@TitleFixes) { fixes = parsed.second }
                TitleFacts.forget()
                Log.i(TAG, "fixes v${parsed.first}: ${parsed.second.size} titles")
            }.onFailure { Log.w(TAG, "refresh: ${it.message}") }
        }
    }

    private fun fixesVersion(): Int? = listOfNotNull(
        runCatching { cache.takeIf { it.isFile }?.readText() }.getOrNull()?.let(::parse)?.first,
        runCatching { App.instance.assets.open("title_fixes.json").bufferedReader().use { it.readText() } }.getOrNull()?.let(::parse)?.first).maxOrNull()

    private fun verified(body: ByteArray, sigB64: String): Boolean = runCatching {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY, Base64.DEFAULT)))
        Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(body); verify(Base64.decode(sigB64, Base64.DEFAULT)) }
    }.getOrDefault(false)
}
