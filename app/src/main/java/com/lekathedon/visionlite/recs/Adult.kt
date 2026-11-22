// Hide adult titles (Settings › Recommendations, off by default): explicit films such as 9 Songs, softcore and porn are
// left out of every row, page, search and recommendation. R-rated films stay; only the explicit kind goes.
//
// A title is adult when IMDb lists it as Adult, TMDB flags it adult, its certificate is X / NC-17 / R18, or its IMDb /
// TMDB keywords say explicit sex (unsimulated sex, softcore, erotic film, sexploitation…).
// Cost: nothing while off. On: each title is checked once and remembered on the TV (adult.json): IMDb titles 25 per
// keyless request, TMDB titles one small request each (TMDB pages ask TMDB to leave those keywords out, so their titles
// need no check). A title shows until its check comes back, then goes if it's adult.
package com.lekathedon.visionlite.recs

import android.util.Log
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.addons.MetaPreview
import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.home.Tmdb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object Adult {
    private const val TAG = "Adult"
    private const val KEEP = 30_000

    /** Keywords that only explicit films carry (IMDb and TMDB spell them alike). */
    private val WORDS = setOf(
        "unsimulated sex", "real sex", "explicit sex", "hardcore sex", "graphic sex", "softcore", "softcore porn",
        "soft core", "erotic film", "erotic movie", "erotica", "sexploitation", "porn film", "pornographic film",
        "pornographic video", "hardcore pornography", "pink film", "pinku eiga", "xxx", "sex film", "adult film"
    )
    private val GENRES = setOf("adult", "erotica", "erotic", "hentai")
    private val CERTS = setOf("x", "xxx", "nc-17", "r18", "r18+", "x18+", "r-18")

    fun on(): Boolean = App.instance.prefs.hideAdult.value

    private val known = ConcurrentHashMap<String, Boolean>()
    private val _revision = MutableStateFlow(0)
    /** Bumps when a title turns out adult, so lists filter again. */
    val revision: StateFlow<Int> get() = _revision
    private val file get() = File(App.instance.filesDir, "adult.json")
    @Volatile private var loaded = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var queued = false
    private val gate = Semaphore(3)

    private fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching { JSONObject(file.readText()).let { o -> o.keys().forEach { k -> known[k] = o.getInt(k) == 1 } } }
            loaded = true
        }
    }

    private fun save() = runCatching {
        val o = JSONObject()
        known.entries.toList().takeLast(KEEP).forEach { (k, v) -> o.put(k, if (v) 1 else 0) }
        File(file.path + ".tmp").apply { writeText(o.toString()) }.renameTo(file)
    }

    /** Whether the words a source gives already decide it (no request). */
    fun judge(genres: Collection<String>, keywords: Collection<String>, cert: String?, flagged: Boolean = false): Boolean =
        flagged || genres.any { it.lowercase() in GENRES } || (cert?.lowercase()?.trim() in CERTS) ||
            keywords.any { it.lowercase().trim() in WORDS }

    /** A source (TMDB's adult flag, a check already made) says what [id] is. */
    fun mark(id: String, adult: Boolean) {
        if (!on()) return
        load()
        val was = known.put(id, adult)
        if (adult && was != true) _revision.value++
    }

    /** Titles TMDB returned with the adult keywords left out: known clean, no check needed. */
    fun clean(ids: Collection<String>) { if (!on()) return; load(); ids.forEach { known.putIfAbsent(it, false) } }

    /** True when [m] is known adult. Unknown titles are queued for a check (and show until it says otherwise). */
    fun hides(m: MetaPreview): Boolean {
        if (!on()) return false
        if (m.type != "movie" && m.type != "series") return false
        load()
        if (m.genres.any { it.lowercase() in GENRES }) { mark(m.id, true); return true }
        val v = known[m.id]
        if (v != null) return v
        if (m.id.startsWith("tt") || m.id.startsWith("tmdb:")) ask(m.id)
        return false
    }

    fun visible(list: List<MetaPreview>): List<MetaPreview> = if (!on()) list else list.filterNot { hides(it) }
    fun hidesId(id: String): Boolean = on() && run { load(); known[id] == true }

    /** The words TMDB's discover leaves out (their TMDB ids, looked up once and kept), as "&without_keywords=…". */
    fun tmdbWithout(): String {
        if (!on()) return ""
        val ids = tmdbKeywordIds() ?: return ""
        return if (ids.isEmpty()) "" else "&without_keywords=" + ids.joinToString("%7C")
    }

    @Volatile private var kwIds: List<Int>? = null
    private fun tmdbKeywordIds(): List<Int>? {
        kwIds?.let { return it }
        val sp = App.instance.prefs.sp
        sp.getString("adult_tmdb_kw", null)?.let { s -> return s.split(',').mapNotNull { it.toIntOrNull() }.also { kwIds = it } }
        if (Tmdb.key() == null) return null
        scope.launch {
            val ids = WORDS.mapNotNull { w ->
                val r = Tmdb.get("search/keyword?query=" + java.net.URLEncoder.encode(w, "UTF-8")) ?: return@mapNotNull null
                r.optJSONArray("results")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }
                    .firstOrNull { it.optString("name").equals(w, true) }?.optInt("id")?.takeIf { it > 0 } }
            }
            if (ids.isNotEmpty()) { sp.edit().putString("adult_tmdb_kw", ids.joinToString(",")).apply(); kwIds = ids }
        }
        return null
    }

    private fun ask(id: String) {
        if (!pending.add(id)) return
        synchronized(this) { if (queued) return; queued = true }
        scope.launch {
            try {
                while (pending.isNotEmpty()) {
                    delay(400)
                    val batch = pending.toList().take(200).also { pending.removeAll(it.toSet()) }
                    runCatching { check(batch) }.onFailure { Log.w(TAG, "check: ${it.message}") }
                    save()
                }
            } finally { synchronized(this@Adult) { queued = false } }
        }
    }

    private suspend fun check(ids: List<String>) = kotlinx.coroutines.coroutineScope {
        val (imdb, tmdb) = ids.filter { known[it] == null }.partition { it.startsWith("tt") }
        val jobs = imdb.chunked(25).map { c -> async { gate.withPermit { imdbCheck(c) } } } +
            tmdb.map { id -> async { gate.withPermit { tmdbCheck(id) } } }
        jobs.awaitAll()
    }

    private fun imdbCheck(ids: List<String>) {
        val q = ids.mapIndexed { i, id -> "t$i: title(id: \"$id\") { titleGenres { genres { genre { text } } } certificate { rating } " +
            "keywords(first: 30) { edges { node { keyword { text { text } } } } } }" }
        val body = JSONObject().put("query", "query { ${q.joinToString(" ")} }").toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("https://api.graphql.imdb.com/").post(body)
            .header("x-imdb-client-name", "imdb-web-next").header("Accept", "application/json").build()
        val data = Net.http.newCall(req).execute().use { r -> if (r.isSuccessful) JSONObject(r.body!!.string()).optJSONObject("data") else null } ?: return
        ids.forEachIndexed { i, id ->
            val t = data.optJSONObject("t$i") ?: return@forEachIndexed
            val genres = t.optJSONObject("titleGenres")?.optJSONArray("genres").objs().mapNotNull { it.optJSONObject("genre")?.optString("text") }
            val kws = t.optJSONObject("keywords")?.optJSONArray("edges").objs()
                .mapNotNull { it.optJSONObject("node")?.optJSONObject("keyword")?.optJSONObject("text")?.optString("text") }
            put(id, judge(genres, kws, t.optJSONObject("certificate")?.optString("rating")))
        }
    }

    private fun tmdbCheck(id: String) {
        val (kind, n) = id.removePrefix("tmdb:").split(':').takeIf { it.size == 2 } ?: return
        val o = Tmdb.get("$kind/$n?append_to_response=keywords" + if (kind == "movie") ",release_dates" else "") ?: return
        val kw = o.optJSONObject("keywords")?.let { k -> (k.optJSONArray("keywords") ?: k.optJSONArray("results")).objs().map { it.optString("name") } }.orEmpty()
        val genres = o.optJSONArray("genres").objs().map { it.optString("name") }
        val cert = o.optJSONObject("release_dates")?.optJSONArray("results").objs().firstOrNull { it.optString("iso_3166_1") == "US" }
            ?.optJSONArray("release_dates").objs().map { it.optString("certification") }.firstOrNull { it.isNotBlank() }
        put(id, judge(genres, kw, cert, o.optBoolean("adult")))
    }

    private fun put(id: String, adult: Boolean) {
        val was = known.put(id, adult)
        if (adult && was != true) _revision.value++
    }

    private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
