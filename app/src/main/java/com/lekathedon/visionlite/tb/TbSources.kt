package com.lekathedon.visionlite.tb

import android.util.Log
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.addons.AddonStore
import com.lekathedon.visionlite.addons.Manifest
import com.lekathedon.visionlite.data.Net
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Vision TB's arsenal (user 2026-11-23): the built-in sites plus your own add-ons, which feed only Vision TB (no
 * Home rows, no link group of their own). Every search already made is scored per source, with no extra calls:
 * reach (cached links only it found, links you played that only it had), speed (how long autoplay waited on it) and
 * health. A source that stops answering, or keeps answering with nothing while the others find links, is paused
 * (not asked, so it neither wastes calls nor stalls autoplay), tried again every 6 h, and you are told once.
 */
object TbSources {
    private const val TAG = "TbSources"
    private const val WINDOW = 40
    /** Searches before a verdict is given. */
    const val LEARN = 10
    private const val DEAD_FAILS = 3
    private const val DEAD_EMPTIES = 5
    private const val RETRY_MS = 6 * 3600_000L
    private const val SLOW_MS = 4_000L

    data class UserAddon(val base: String, val name: String)

    enum class Outcome { OK, EMPTY, FAIL, TIMEOUT, SKIPPED }

    /** One search, one source. [unique]: cached links shown that no other source returned; [delay]: ms autoplay waited on it alone. */
    class Run(val at: Long, val outcome: Outcome, val ms: Long, val shown: Int, val unique: Int, val othersFound: Boolean, val delay: Long)

    private class Stats(val runs: ArrayDeque<Run> = ArrayDeque(), var picks: Int = 0, var solePicks: Int = 0)

    private val prefs get() = App.instance.prefs.sp
    private val file by lazy { File(App.instance.filesDir, "tb_sources.json") }
    private val stats = HashMap<String, Stats>()
    private var loaded = false

    /** The newest problem to tell you about (shown once, then cleared). */
    private val alertFlow = MutableStateFlow<String?>(null)
    val alert: StateFlow<String?> get() = alertFlow
    fun alertSeen() { alertFlow.value = null }

    /** One of your own add-ons is broken or adds nothing but wait: you choose Pause / Remove / Keep using. */
    class Ask(val key: String, val label: String, val why: String)
    private val askFlow = MutableStateFlow<Ask?>(null)
    val ask: StateFlow<Ask?> get() = askFlow

    /** Built-in sources are the owner's job: their notices only in the owner build; shared builds pause them silently. */
    private fun mine(key: String) = key.startsWith("addon:")
    private fun tell(key: String, msg: String) { if (mine(key) || com.lekathedon.visionlite.BuildConfig.DEV) alertFlow.value = msg }

    // "Keep using": asked every search and waited for, whatever it costs (your choice, your wait).
    private fun keptSet() = prefs.getStringSet("tb_keep", emptySet()).orEmpty()
    fun kept(key: String) = key in keptSet()
    private fun askedSet() = prefs.getStringSet("tb_asked", emptySet()).orEmpty()

    fun answer(key: String, choice: String) {
        askFlow.value = null
        when (choice) {
            "remove" -> remove(key)
            "keep" -> { prefs.edit().putStringSet("tb_keep", keptSet() + key).apply(); unpause(key) }
            "unkeep" -> prefs.edit().putStringSet("tb_keep", keptSet() - key).putStringSet("tb_asked", askedSet() - key).apply()
            else -> if (pausedMap()[key] == null) savePaused(pausedMap() + (key to System.currentTimeMillis()))
        }
        tickFlow.value++
    }

    /** Bumped when stats change, so the Sources page redraws. */
    private val tickFlow = MutableStateFlow(0)
    val tick: StateFlow<Int> get() = tickFlow

    // ---- your add-ons ----

    fun addons(): List<UserAddon> = runCatching {
        val a = JSONArray(prefs.getString("tb_addons", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> UserAddon(o.getString("base"), o.getString("name")) } }
    }.getOrDefault(emptyList())

    private fun saveAddons(list: List<UserAddon>) {
        prefs.edit().putString("tb_addons", JSONArray(list.map { JSONObject().put("base", it.base).put("name", it.name) }).toString()).apply()
        tickFlow.value++
    }

    /** Adds a Stremio add-on that returns streams (torrent hashes, or debrid links with the hash in them). */
    suspend fun add(url: String): UserAddon {
        val base = AddonStore.normalize(url)
        val m = Net.getJson<Manifest>("$base/manifest.json", timeoutSec = 15)
        val a = com.lekathedon.visionlite.addons.Addon(base, m)
        require(a.serves("stream", "movie", "tt0111161") || a.serves("stream", "series", "tt0903747:1:1")) { "${m.name} gives no streams" }
        val ua = UserAddon(base, m.name.ifBlank { base.removePrefix("https://").substringBefore('/') })
        saveAddons(addons().filterNot { it.base == base } + ua)
        unpause(keyOf(ua))
        return ua
    }

    fun remove(key: String) {
        saveAddons(addons().filterNot { keyOf(it) == key })
        load(); synchronized(stats) { stats.remove(key) }; unpause(key); setOn(key, true); save()
        prefs.edit().putStringSet("tb_keep", keptSet() - key).putStringSet("tb_asked", askedSet() - key).apply()
    }

    fun keyOf(a: UserAddon) = "addon:" + a.base
    fun keyOf(s: DiscoverySource) = s.name

    // ---- on / off / paused ----

    private fun offSet() = prefs.getStringSet("tb_off", emptySet()).orEmpty()
    fun isOn(key: String) = key !in offSet()
    fun setOn(key: String, on: Boolean) {
        prefs.edit().putStringSet("tb_off", if (on) offSet() - key else offSet() + key).apply(); tickFlow.value++
    }

    private fun pausedMap(): Map<String, Long> = runCatching {
        val o = JSONObject(prefs.getString("tb_paused", "{}") ?: "{}"); o.keys().asSequence().associateWith { o.getLong(it) }
    }.getOrDefault(emptyMap())
    private fun savePaused(m: Map<String, Long>) { prefs.edit().putString("tb_paused", JSONObject(m).toString()).apply(); tickFlow.value++ }
    fun pausedSince(key: String): Long? = pausedMap()[key]
    fun unpause(key: String) { if (key in pausedMap()) savePaused(pausedMap() - key) }

    /** Asked in this search: on, and not paused (a paused source is tried again once every 6 h). */
    fun shouldAsk(key: String): Boolean {
        if (!isOn(key)) return false
        if (kept(key)) return true
        val p = pausedMap()[key] ?: return true
        if (System.currentTimeMillis() - p < RETRY_MS) return false
        savePaused(pausedMap() + (key to System.currentTimeMillis())) // one retry per 6 h
        return true
    }

    // ---- one search ----

    /** Collects one search's facts; [finish] scores them. Thread-safe. */
    class Search(val videoId: String) {
        private val t0 = android.os.SystemClock.elapsedRealtime()
        private val asked = HashSet<String>()
        private val done = HashMap<String, Pair<Outcome, Long>>()
        private val returned = HashMap<String, MutableSet<String>>()
        private val shownBy = HashMap<String, MutableSet<String>>()
        private val shown = HashSet<String>()
        @Volatile var has4k = false; private set
        private val lock = Any()

        fun elapsed() = android.os.SystemClock.elapsedRealtime() - t0
        fun started(key: String) = synchronized(lock) { asked += key }
        /** A request ended: rows null = failed. A key with several requests is OK when any of them answered. */
        fun ended(key: String, rows: List<DiscoveryCandidate>?, timedOut: Boolean) = synchronized(lock) {
            val o = when { rows == null -> if (timedOut) Outcome.TIMEOUT else Outcome.FAIL; rows.isEmpty() -> Outcome.EMPTY; else -> Outcome.OK }
            val prev = done[key]?.first
            val best = listOfNotNull(prev, o).minByOrNull { it.ordinal }!!
            done[key] = best to elapsed()
            rows?.forEach { returned.getOrPut(it.hash.lowercase()) { HashSet() } += key }
        }
        fun skipped(key: String) = synchronized(lock) { if (key !in done) done[key] = Outcome.SKIPPED to elapsed() }
        fun shown(key: String, hashes: List<String>, any4k: Boolean) = synchronized(lock) {
            hashes.forEach { h -> shown += h.lowercase(); shownBy.getOrPut(h.lowercase()) { HashSet() } += key }
            if (any4k) has4k = true
        }
        fun pending(): Set<String> = synchronized(lock) { asked - done.keys }
        fun anyShown() = synchronized(lock) { shown.isNotEmpty() }

        fun finish() {
            val now = System.currentTimeMillis()
            val runs: Map<String, Run>
            val byHash: Map<String, Set<String>>
            synchronized(lock) {
                // Still running when the 15 s cap hit: timed out.
                (asked - done.keys).forEach { done[it] = Outcome.TIMEOUT to elapsed() }
                // Shown hashes: every source that returned them (returned rows, plus the one that showed it).
                byHash = shown.associateWith { h -> (returned[h].orEmpty() + shownBy[h].orEmpty()) }
                val finishOk = done.filterValues { it.first != Outcome.SKIPPED }
                runs = done.mapValues { (k, v) ->
                    val mine = byHash.count { k in it.value }
                    val unique = byHash.count { it.value == setOf(k) }
                    val othersFound = done.any { (o, ov) -> o != k && ov.first == Outcome.OK }
                    val othersEnd = finishOk.filterKeys { it != k }.values.maxOfOrNull { it.second } ?: 0L
                    val delay = if (v.first == Outcome.SKIPPED) 0L else (v.second - othersEnd).coerceAtLeast(0)
                    Run(now, v.first, v.second, mine, unique, othersFound, delay)
                }
            }
            synchronized(recent) { recent[videoId] = byHash; while (recent.size > 6) recent.remove(recent.keys.first()) }
            record(runs)
        }
    }

    private val recent = LinkedHashMap<String, Map<String, Set<String>>>()

    /** A Vision TB link was played (by you or autoplay): the sources that had it get the credit. */
    fun played(videoId: String, hash: String) {
        val keys = synchronized(recent) { recent[videoId]?.get(hash.lowercase()) } ?: return
        load()
        synchronized(stats) {
            keys.forEach { k -> stats.getOrPut(k) { Stats() }.apply { picks++; if (keys.size == 1) solePicks++ } }
        }
        save(); tickFlow.value++
    }

    private fun record(runs: Map<String, Run>) {
        load()
        val newlyDead = ArrayList<String>()
        synchronized(stats) {
            runs.forEach { (k, r) ->
                val s = stats.getOrPut(k) { Stats() }
                s.runs.addLast(r); while (s.runs.size > WINDOW) s.runs.removeFirst()
            }
        }
        var asking: Ask? = null
        runs.keys.forEach { k ->
            if (kept(k)) return@forEach
            val h = health(k)
            // Your add-on that answers but finds nothing the others don't, and makes autoplay wait: same choice as a broken one.
            val idle = if (h == null && mine(k) && skippable(k)) "found nothing the other sources didn't, and makes autoplay wait" else null
            if ((h ?: idle) != null && pausedMap()[k] == null) {
                savePaused(pausedMap() + (k to System.currentTimeMillis())); newlyDead += k
                if (mine(k) && k !in askedSet() && asking == null) asking = Ask(k, label(k), (h ?: idle)!!)
            }
            else if (h == null && idle == null && runs[k]?.outcome == Outcome.OK && pausedMap()[k] != null) {
                unpause(k); tell(k, "Vision TB: ${label(k)} answers again, back in use")
            }
        }
        asking?.let { a -> prefs.edit().putStringSet("tb_asked", askedSet() + a.key).apply(); askFlow.value = a }
            ?: newlyDead.firstOrNull { !mine(it) }?.let { k -> tell(k, "Vision TB: ${label(k)} ${health(k)}. Paused so autoplay doesn't wait on it") }
        save(); tickFlow.value++
    }

    // ---- verdicts ----

    /** Non-null = broken: why. */
    fun health(key: String): String? {
        val runs = synchronized(stats) { stats[key]?.runs?.filter { it.outcome != Outcome.SKIPPED }.orEmpty() }
        val fails = runs.takeLast(DEAD_FAILS)
        if (fails.size == DEAD_FAILS && fails.all { it.outcome == Outcome.FAIL || it.outcome == Outcome.TIMEOUT })
            return if (fails.all { it.outcome == Outcome.TIMEOUT }) "stopped answering (timed out $DEAD_FAILS times in a row)" else "stopped answering (failed $DEAD_FAILS times in a row)"
        val empties = runs.takeLast(DEAD_EMPTIES)
        if (empties.size == DEAD_EMPTIES && empties.all { it.outcome == Outcome.EMPTY && it.othersFound })
            return "returns nothing ($DEAD_EMPTIES searches in a row while the others found links)"
        return null
    }

    class Report(val key: String, val label: String, val searches: Int, val medianMs: Long, val avgDelayMs: Long,
                 val reachPct: Int, val solePicks: Int, val picks: Int, val verdict: String, val tone: Tone)
    enum class Tone { GOOD, NEUTRAL, WARN, BAD, LEARNING }

    fun report(key: String): Report {
        load()
        val s = synchronized(stats) { stats[key] }
        val runs = s?.runs?.filter { it.outcome != Outcome.SKIPPED }.orEmpty()
        val n = runs.size
        val answered = runs.filter { it.outcome == Outcome.OK || it.outcome == Outcome.EMPTY }
        val median = answered.map { it.ms }.sorted().let { if (it.isEmpty()) 0L else it[it.size / 2] }
        val delay = if (n == 0) 0L else runs.sumOf { it.delay } / n
        val reach = if (n == 0) 0 else runs.count { it.unique > 0 } * 100 / n
        val sole = s?.solePicks ?: 0
        val picks = s?.picks ?: 0
        val slow = median > SLOW_MS || delay > 1_000
        val cost = if (delay >= 500) "adds ~%.1f s to autoplay".format(delay / 1000.0) else if (median > 0) "answers in ~%.1f s".format(median / 1000.0) else ""
        val paused = pausedMap()[key]
        val (verdict, tone) = when {
            !isOn(key) -> "Off: not asked" to Tone.NEUTRAL
            kept(key) -> ("Kept by you: always asked and waited for" + (health(key)?.let { "; $it" } ?: if (cost.isNotEmpty()) "; $cost" else "")) to Tone.NEUTRAL
            health(key) != null || paused != null -> ("Paused: " + (health(key) ?: "not answering") + ". Edit or remove it; tried again every 6 h") to Tone.BAD
            n < LEARN -> "Learning: $n of $LEARN searches" to Tone.LEARNING
            sole > 0 || reach >= 10 -> ("Keep: only source in $reach% of searches" + (if (sole > 0) ", $sole of your plays came only from it" else "") +
                (if (slow) ", but $cost" else "")) to (if (slow) Tone.WARN else Tone.GOOD)
            reach == 0 && n >= 15 -> ("Remove: nothing in $n searches the others didn't have" + (if (cost.isNotEmpty()) "; $cost" else "")) to Tone.BAD
            slow && reach < 5 -> "Costly: rarely adds anything ($reach%) and $cost" to Tone.WARN
            else -> "Useful sometimes: only source in $reach% of searches${if (cost.isNotEmpty()) "; $cost" else ""}" to Tone.NEUTRAL
        }
        return Report(key, label(key), n, median, delay, reach, sole, picks, verdict, tone)
    }

    /** Slow and adding almost nothing: once the others found a 4K cached link, autoplay stops waiting for it. */
    fun skippable(key: String): Boolean {
        if (kept(key)) return false
        val r = report(key)
        return r.searches >= LEARN && r.solePicks == 0 && r.reachPct < 5 && r.medianMs > SLOW_MS
    }

    /** Every source on the page: yours first, then the built-in ones Vision TB has used. */
    fun keys(): List<String> {
        load()
        val builtIn = DiscoverySource.values().filter { it != DiscoverySource.USER_ADDON && it != DiscoverySource.ADDONS }.map { keyOf(it) }
        val used = synchronized(stats) { stats.keys.toSet() }
        return addons().map(::keyOf) + builtIn.filter { it in used || !isOn(it) }
    }

    fun label(key: String): String = when {
        key.startsWith("addon:") -> addons().firstOrNull { keyOf(it) == key }?.name ?: key.removePrefix("addon:").removePrefix("https://").substringBefore('/')
        else -> runCatching { DiscoverySource.valueOf(key).label }.getOrDefault(key)
    }

    fun problems(): Int = keys().count { isOn(it) && pausedMap()[it] != null && (mine(it) || com.lekathedon.visionlite.BuildConfig.DEV) }

    // ---- storage (files/tb_sources.json, a few KB) ----

    private fun load() {
        if (loaded) return
        synchronized(stats) {
            if (loaded) return
            runCatching {
                val o = JSONObject(file.readText())
                o.keys().forEach { k ->
                    val so = o.getJSONObject(k)
                    val st = Stats(picks = so.optInt("p"), solePicks = so.optInt("sp"))
                    val a = so.optJSONArray("r") ?: JSONArray()
                    for (i in 0 until a.length()) {
                        val r = a.getJSONArray(i)
                        st.runs.addLast(Run(r.getLong(0), Outcome.values()[r.getInt(1)], r.getLong(2), r.getInt(3), r.getInt(4), r.getInt(5) == 1, r.getLong(6)))
                    }
                    stats[k] = st
                }
            }
            loaded = true
        }
    }

    private fun save() {
        val o = JSONObject()
        synchronized(stats) {
            stats.forEach { (k, s) ->
                o.put(k, JSONObject().put("p", s.picks).put("sp", s.solePicks).put("r", JSONArray(s.runs.map { r ->
                    JSONArray(listOf(r.at, r.outcome.ordinal, r.ms, r.shown, r.unique, if (r.othersFound) 1 else 0, r.delay))
                })))
            }
        }
        runCatching { file.writeText(o.toString()) }.onFailure { Log.w(TAG, "save: ${it.message}") }
    }
}
