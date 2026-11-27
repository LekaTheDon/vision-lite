// Hide what isn't out yet (user 2026-11-27: "Digger" sat in rows as if it could be watched, though it was only in
// cinemas). A film shows once it is out at home: its first digital / disc date from IMDb (PVOD, VOD, internet,
// streaming, DVD, Blu-ray, 4K UHD) has passed, or, when IMDb lists none, 120 days after its first release (older films
// rarely carry home dates). A series shows once its first episode has aired. Rows made for what's coming ("Coming
// soon", "Most anticipated", "Upcoming", Coming up) keep them. Search still finds everything.
// Cost: nothing for titles older than last year (the card's year says so); otherwise one keyless IMDb request per 10
// new titles, kept on disk (out titles are never asked again; not-out-yet ones at most once a day). Unknown titles are
// held back until their answer (a second or so), like Leave out; a failed request shows them rather than hide them.
package com.lekathedon.visionlite.recs

import android.util.Log
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.addons.MetaPreview
import com.lekathedon.visionlite.data.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

object Released {
    private const val TAG = "Released"
    private const val NEVER = Long.MAX_VALUE
    private const val KEEP = 20_000

    /** id -> (epoch day it is out at home, epoch day it was checked). */
    private class F(val out: Long, val checked: Long)
    private val known = ConcurrentHashMap<String, F>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val _revision = MutableStateFlow(0)
    /** Bumped when answers land, so rows filter again. */
    val revision: StateFlow<Int> get() = _revision
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2)
    @Volatile private var loaded = false
    @Volatile private var queued = false
    private val file get() = File(App.instance.filesDir, "released.json")

    fun on(): Boolean = App.instance.prefs.hideUnreleased.value

    private fun today() = LocalDate.now().toEpochDay()

    /** A row made for what's coming keeps its not-out-yet titles. */
    private val UPCOMING = Regex("""(?i)\b(coming|upcoming|anticipated|soon|unreleased|in production|announced|trailers?)\b""")
    fun upcomingRow(key: String, title: String?): Boolean =
        key.contains("coming", true) || key.contains("upcoming", true) || key.contains("anticipated", true) ||
            (title != null && UPCOMING.containsMatchIn(title))

    /** True when [m] isn't out at home yet (or is being checked). */
    fun hides(m: MetaPreview): Boolean {
        if (!on()) return false
        if (m.type != "movie" && m.type != "series") return false
        val now = LocalDate.now().year
        val year = m.releaseInfo?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }
        if (year != null && year < now - 1) return false
        if (year != null && year > now) return true
        if (!m.id.startsWith("tt")) return false
        load()
        val f = known[m.id]
        if (f != null && (f.out <= today() || f.checked >= today())) return f.out > today()
        if (m.id in failed) return f?.let { it.out > today() } ?: false
        ask(m.id)
        return true
    }

    fun visible(list: List<MetaPreview>): List<MetaPreview> = if (!on()) list else list.filterNot { hides(it) }

    // ---------------------------------------------------------------- disk

    private fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                if (file.exists()) {
                    val o = JSONObject(file.readText())
                    o.keys().forEach { k -> val p = o.getString(k).split('|'); known[k] = F(p[0].toLong(), p[1].toLong()) }
                }
            }
            loaded = true
        }
    }

    private fun save() = runCatching {
        val o = JSONObject()
        known.entries.toList().takeLast(KEEP).forEach { (k, f) -> o.put(k, "${f.out}|${f.checked}") }
        file.writeText(o.toString())
    }

    // ---------------------------------------------------------------- checks

    private fun ask(id: String) {
        if (!pending.add(id)) return
        synchronized(this) { if (queued) return; queued = true }
        scope.launch {
            try {
                while (pending.isNotEmpty()) {
                    delay(250)
                    val batch = pending.toList().take(100).also { pending.removeAll(it.toSet()) }
                    val jobs = batch.chunked(10).map { c -> c to async { gate.withPermit { runCatching { check(c) }.onFailure { Log.w(TAG, "check: ${it.message}") }.getOrDefault(false) } } }
                    jobs.forEach { (c, job) -> val ok = job.await(); c.filter { !ok || it !in known }.forEach { failed += it } }
                    _revision.value++
                    save()
                }
            } finally { synchronized(this@Released) { queued = false } }
        }
    }

    private val HOME = Regex("""(?i)\b(pvod|vod|svod|tvod|avod|internet|digital|streaming|dvd|blu-?ray|4k uhd|uhd|video|download)\b""")

    /** One IMDb request for up to 10 titles; false when the request itself failed. */
    private fun check(ids: List<String>): Boolean {
        val q = ids.mapIndexed { i, id -> "t$i: title(id: \"$id\") { titleType { canHaveEpisodes } releaseDate { day month year } " +
            "releaseDates(first: 250) { edges { node { day month year attributes { text } } } } }" }
        val body = JSONObject().put("query", "query { ${q.joinToString(" ")} }").toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("https://api.graphql.imdb.com/").post(body)
            .header("x-imdb-client-name", "imdb-web-next").header("Accept", "application/json").build()
        val data = Net.http.newCall(req).execute().use { r -> if (r.isSuccessful) JSONObject(r.body!!.string()).optJSONObject("data") else null } ?: return false
        val today = today()
        ids.forEachIndexed { i, id ->
            val t = data.optJSONObject("t$i") ?: return@forEachIndexed
            val first = day(t.optJSONObject("releaseDate"))
            val series = t.optJSONObject("titleType")?.optBoolean("canHaveEpisodes") == true
            val out = if (series) first ?: NEVER else {
                val edges = t.optJSONObject("releaseDates")?.optJSONArray("edges")
                val home = (0 until (edges?.length() ?: 0)).mapNotNull { k ->
                    val n = edges!!.optJSONObject(k)?.optJSONObject("node") ?: return@mapNotNull null
                    val attrs = n.optJSONArray("attributes")
                    val words = (0 until (attrs?.length() ?: 0)).joinToString(" ") { attrs!!.optJSONObject(it)?.optString("text").orEmpty() }
                    if (HOME.containsMatchIn(words)) day(n) else null
                }.minOrNull()
                home ?: first?.plus(120) ?: NEVER
            }
            known[id] = F(out, today)
        }
        return true
    }

    private fun day(o: JSONObject?): Long? {
        o ?: return null
        val y = o.optInt("year", 0).takeIf { it > 0 } ?: return null
        val m = o.optInt("month", 0).takeIf { it in 1..12 } ?: return LocalDate.of(y, 12, 31).toEpochDay()
        val d = o.optInt("day", 0).takeIf { it in 1..31 } ?: return LocalDate.of(y, m, 1).plusMonths(1).minusDays(1).toEpochDay()
        return runCatching { LocalDate.of(y, m, d).toEpochDay() }.getOrNull()
    }
}
