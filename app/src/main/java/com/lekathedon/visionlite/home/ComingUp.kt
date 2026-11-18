package com.lekathedon.visionlite.home

import android.util.Log
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.library.Saves
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Coming up: every show you have EVER watched (Trakt, no time limit), your Trakt watchlist and your Library, checked
 * on TVmaze for a new episode. A show quiet for years that suddenly gets a new season shows up the day TVmaze lists
 * it. Plus films on your Trakt calendar.
 *
 * Cost: the first time, one TVmaze lookup per show (paced, ~2 per second, kept forever). After that, twice a day: one
 * call to TVmaze's "what changed today" list, then only the shows that changed or whose episode aired. No Trakt = your
 * Library's shows only.
 */
object ComingUp {
    private const val TAG = "ComingUp"
    private const val TVMAZE = "https://api.tvmaze.com"
    private const val EVERY_MS = 12 * 3600_000L
    private const val NEW_DAYS = 10

    @Serializable data class Item(
        val imdb: String, val type: String, val name: String, val season: Int? = null, val episode: Int? = null,
        val epTitle: String? = null, val at: Long, val isNew: Boolean = false, val image: String? = null
    ) {
        val key get() = "$imdb:${season ?: 0}:${episode ?: 0}"
    }

    /** A show's state on TVmaze, as last read. */
    @Serializable private data class Show(
        val tvmaze: Int, val name: String = "", val image: String? = null, val status: String = "",
        val nextS: Int? = null, val nextE: Int? = null, val nextTitle: String? = null, val nextAt: Long = 0,
        val prevS: Int? = null, val prevE: Int? = null, val prevTitle: String? = null, val prevAt: Long = 0, val readAt: Long = 0
    )

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> get() = _items
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> get() = _status

    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir get() = File(App.instance.filesDir, "comingup").apply { mkdirs() }
    private val idSer = MapSerializer(String.serializer(), Int.serializer())
    private val showSer = MapSerializer(String.serializer(), Show.serializer())
    private val itemSer = ListSerializer(Item.serializer())
    private var loadedDisk = false

    /** Home shows: the saved row at once, and a fresh check when the last one is half a day old. */
    fun onHome() {
        if (!loadedDisk) { loadedDisk = true; _items.value = read("items.json", itemSer).orEmpty() }
        val last = App.instance.prefs.sp.getLong("comingup_at", 0)
        if (System.currentTimeMillis() - last > EVERY_MS) refresh(false)
    }

    fun refresh(force: Boolean) {
        scope.launch {
            if (!lock.tryLock()) return@launch
            try { run(force) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "check failed: ${e.message}")
            } finally { _status.value = ""; lock.unlock() }
        }
    }

    private suspend fun run(force: Boolean) {
        val now = System.currentTimeMillis()
        val trakt = App.instance.trakt
        // Followed: watched ever (with when last watched), watchlist shows, Library shows.
        val watched = trakt.watchedShows()
        val lastWatched = HashMap<String, Long>()
        val names = HashMap<String, String>()
        watched?.forEach { w -> w.show.ids.imdb?.let { lastWatched[it] = ms(w.lastWatchedAt); names[it] = w.show.title } }
        runCatching { trakt.watchlist() }.getOrDefault(emptyList()).forEach { l -> l.show?.let { s -> s.ids.imdb?.let { names.putIfAbsent(it, s.title) } } }
        Saves.all().filter { it.type == "series" && it.id.startsWith("tt") }.forEach { names.putIfAbsent(it.id.substringBefore(':'), it.name) }
        if (names.isEmpty()) { _items.value = emptyList(); return }

        val ids = (read("tvmaze_ids.json", idSer) ?: emptyMap()).toMutableMap()
        val shows = (read("shows.json", showSer) ?: emptyMap()).toMutableMap()
        // Which shows TVmaze changed since the last check (one call); a first run or a long gap reads them all.
        val since = App.instance.prefs.sp.getLong("comingup_at", 0)
        val changed: Set<Int>? = if (force || since == 0L || now - since > 6 * 24 * 3600_000L) null else runCatching {
            val o = JSONObject(get("$TVMAZE/updates/shows?since=${if (now - since > 24 * 3600_000L) "week" else "day"}"))
            o.keys().asSequence().mapNotNull { it.toIntOrNull() }.toSet()
        }.getOrNull()

        var done = 0
        val todo = names.keys.toList()
        for (imdb in todo) {
            done++
            if (done % 20 == 0) { _status.value = "Checking your shows… $done/${todo.size}"; publish(names, lastWatched, ids, shows, now) }
            val id = ids[imdb] ?: run {
                val found = lookup(imdb) ?: -1
                ids[imdb] = found
                delay(450)
                found
            }
            if (id < 0) continue
            val s = shows[imdb]
            val aired = s != null && s.nextAt in 1..now
            val stale = s == null || now - s.readAt > 7 * 24 * 3600_000L
            if (s != null && !aired && !stale && changed != null && id !in changed) continue
            readShow(id)?.let { shows[imdb] = it }
            delay(450)
        }
        write("tvmaze_ids.json", idSer, ids)
        write("shows.json", showSer, shows)
        publish(names, lastWatched, ids, shows, now, films = filmsAhead())
        App.instance.prefs.sp.edit().putLong("comingup_at", now).apply()
    }

    private suspend fun filmsAhead(): List<Item> = runCatching {
        val (_, movies) = App.instance.trakt.calendar(31)
        val ymd = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        movies.mapNotNull { m ->
            val imdb = m.movie.ids.imdb ?: return@mapNotNull null
            val at = runCatching { ymd.parse(m.released)!!.time }.getOrNull() ?: return@mapNotNull null
            Item(imdb, "movie", m.movie.title, at = at)
        }
    }.getOrDefault(emptyList())

    private var films: List<Item> = emptyList()

    private fun publish(names: Map<String, String>, lastWatched: Map<String, Long>, ids: Map<String, Int>, shows: Map<String, Show>, now: Long, films: List<Item>? = null) {
        if (films != null) this.films = films
        val newSince = now - NEW_DAYS * 24 * 3600_000L
        val list = shows.filterKeys { it in names }.flatMap { (imdb, s) ->
            val name = names[imdb] ?: s.name
            buildList {
                // Aired in the last days and after you last watched it: New.
                if (s.prevAt in newSince..now && s.prevAt > (lastWatched[imdb] ?: 0))
                    add(Item(imdb, "series", name, s.prevS, s.prevE, s.prevTitle, s.prevAt, true, s.image))
                if (s.nextAt > now) add(Item(imdb, "series", name, s.nextS, s.nextE, s.nextTitle, s.nextAt, false, s.image))
            }
        }
        val sorted = list.filter { it.isNew }.sortedByDescending { it.at } +
            (list.filter { !it.isNew } + this.films.filter { it.at >= now - 24 * 3600_000L }).sortedBy { it.at }
        val out = sorted.distinctBy { it.key }.take(80)
        _items.value = out
        write("items.json", itemSer, out)
    }

    private fun lookup(imdb: String): Int? = runCatching {
        Net.http.newCall(Request.Builder().url("$TVMAZE/lookup/shows?imdb=$imdb").build()).execute().use { r ->
            if (r.code == 404) return -1
            if (!r.isSuccessful) return null
            JSONObject(r.body!!.string()).optInt("id").takeIf { it > 0 }
        }
    }.getOrNull()

    private fun readShow(id: Int): Show? = runCatching {
        val o = JSONObject(get("$TVMAZE/shows/$id?embed[]=nextepisode&embed[]=previousepisode"))
        val emb = o.optJSONObject("_embedded")
        val next = emb?.optJSONObject("nextepisode")
        val prev = emb?.optJSONObject("previousepisode")
        fun at(e: JSONObject?) = e?.optString("airstamp")?.takeIf { it.isNotBlank() }?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: 0L
        fun num(e: JSONObject?, k: String) = e?.optInt(k, -1)?.takeIf { it >= 0 }
        Show(id, o.optString("name"), o.optJSONObject("image")?.optString("original")?.takeIf { it.startsWith("http") }, o.optString("status"),
            num(next, "season"), num(next, "number"), next?.optString("name"), at(next),
            num(prev, "season"), num(prev, "number"), prev?.optString("name"), at(prev), System.currentTimeMillis())
    }.getOrNull()

    private fun get(url: String): String = Net.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
        if (r.code == 429) throw java.io.IOException("TVmaze busy")
        if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
        r.body!!.string()
    }

    private fun ms(iso: String) = runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)

    private fun <T> read(name: String, ser: kotlinx.serialization.KSerializer<T>): T? =
        runCatching { Net.json.decodeFromString(ser, File(dir, name).readText()) }.getOrNull()
    private fun <T> write(name: String, ser: kotlinx.serialization.KSerializer<T>, v: T) { runCatching { File(dir, name).writeText(Net.json.encodeToString(ser, v)) } }

    /** "New", "Tonight 21:00", "Today 15:00", "Tomorrow", a weekday, then a date. */
    fun label(i: Item, now: Long = System.currentTimeMillis()): String {
        if (i.isNew) return "New"
        val c = java.util.Calendar.getInstance()
        fun day(ms: Long) = c.apply { timeInMillis = ms }.let { it.get(java.util.Calendar.YEAR) * 400 + it.get(java.util.Calendar.DAY_OF_YEAR) }
        val d = day(i.at) - day(now)
        val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(i.at))
        val hour = c.apply { timeInMillis = i.at }.get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            i.type == "movie" -> if (d <= 0) "Out today" else "Out " + java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(i.at))
            d <= 0 -> if (hour >= 18) "Tonight $hm" else "Today $hm"
            d == 1 -> "Tomorrow"
            d < 7 -> java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(java.util.Date(i.at))
            else -> java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(i.at))
        }
    }

    fun episodeLine(i: Item): String? = if (i.season == null) null else
        (if (i.episode == 1) "Season ${i.season} premiere · " else "") + "S${i.season} E${i.episode}" + (i.epTitle?.takeIf { it.isNotBlank() && !it.startsWith("Episode") }?.let { " · $it" } ?: "")
}
