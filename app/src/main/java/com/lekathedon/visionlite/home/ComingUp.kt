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
 * Coming up: every show you have EVER watched or saved (Trakt, Simkl, the Nuvio account, your Library) and every show
 * you Follow, checked on TVmaze for a new episode, with TMDB as the second source when TVmaze has no date (or doesn't
 * know the show). Films: every film on your Trakt watchlist, Simkl plan-to-watch, Nuvio library and Library, with
 * TMDB's cinema and digital dates (as Nuvio's Calendar), plus films on your Trakt calendar.
 *
 * Cost: the first time, one TVmaze lookup per show and one TMDB lookup per film / undated show (paced, kept). After
 * that, twice a day: TVmaze's "what changed" list, then only shows that changed, aired, are followed, or have no date
 * (TMDB, once a day each); films once every few days (daily near their date), never again once out on digital.
 */
object ComingUp {
    private const val TAG = "ComingUp"
    private const val TVMAZE = "https://api.tvmaze.com"
    private const val EVERY_MS = 12 * 3600_000L
    private const val NEW_DAYS = 10
    /** A season that started this recently and you haven't watched since stays in Coming up as "Back" (user 2026-11-25:
     *  shows that already returned, often dropped all at once, must show, not only ones still to come). */
    private const val BACK_DAYS = 60
    private const val DAY = 24 * 3600_000L

    @Serializable data class Item(
        val imdb: String, val type: String, val name: String, val season: Int? = null, val episode: Int? = null,
        val epTitle: String? = null, val at: Long, val isNew: Boolean = false, val image: String? = null,
        /** The source gives a day, not a time (TMDB): no "21:00" on the card. */
        val dateOnly: Boolean = false,
        /** The season already started (in the last 60 days) and you haven't watched since: "Back". */
        val back: Boolean = false
    ) {
        val key get() = "$imdb:${season ?: 0}:${episode ?: 0}" + if (type == "movie") ":${epTitle.orEmpty()}" else ""
    }

    /** A show's state on TVmaze (or TMDB, tvmaze = 0), as last read. */
    @Serializable private data class Show(
        val tvmaze: Int, val name: String = "", val image: String? = null, val status: String = "",
        val nextS: Int? = null, val nextE: Int? = null, val nextTitle: String? = null, val nextAt: Long = 0,
        val prevS: Int? = null, val prevE: Int? = null, val prevTitle: String? = null, val prevAt: Long = 0, val readAt: Long = 0,
        /** TVmaze calls it anime (genre "Anime", or Japanese animation); null = read before this was kept. */
        val anime: Boolean? = null,
        /** When the latest aired season started (its episode 1); 0 = not known / long ago. */
        val premAt: Long = 0
    )

    /** A film's dates on TMDB (0 = none known). */
    @Serializable private data class Film(val tmdb: Int, val name: String = "", val cinema: Long = 0, val digital: Long = 0,
                                          val poster: String? = null, val readAt: Long = 0)

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> get() = _items
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> get() = _status

    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir get() = File(App.instance.profileDir, "comingup").apply { mkdirs() }
    private val idSer = MapSerializer(String.serializer(), Int.serializer())
    private val strSer = MapSerializer(String.serializer(), String.serializer())
    private val showSer = MapSerializer(String.serializer(), Show.serializer())
    private val filmSer = MapSerializer(String.serializer(), Film.serializer())
    private val itemSer = ListSerializer(Item.serializer())
    private var loadedDisk = false

    /** Home shows: the saved row at once, and a fresh check when the last one is half a day old. */
    fun onHome() {
        if (!loadedDisk) { loadedDisk = true; _items.value = read("items.json", itemSer).orEmpty() }
        // Once after the update that reads your whole library: a full check now, not in 12 h.
        val sp = App.instance.prefs.sp
        if (!sp.getBoolean("comingup_v4", false)) { sp.edit().putBoolean("comingup_v4", true).apply(); refresh(true); return }
        val last = App.instance.prefs.sp.getLong("comingup_at", 0)
        if (System.currentTimeMillis() - last > EVERY_MS) refresh(false)
    }

    /** Shows you dropped from Coming up (hold OK on its card): never followed again, until brought back in Settings. */
    fun dropped(): Set<String> = App.instance.prefs.sp.getStringSet("comingup_dropped", emptySet()).orEmpty()
    fun drop(imdb: String) {
        val id = imdb.substringBefore(':')
        App.instance.prefs.sp.edit().putStringSet("comingup_dropped", dropped() + id).apply()
        if (isFollowed(id)) follow(id, "", false)
        _items.value = _items.value.filter { it.imdb != id }
        write("items.json", itemSer, _items.value)
    }
    fun undropAll() { App.instance.prefs.sp.edit().remove("comingup_dropped").apply(); refresh(false) }

    // ---- Follow: a show you follow is checked every time (TVmaze and TMDB), so its next season shows the day it's dated ----

    private val _followRev = MutableStateFlow(0)
    val followRev: StateFlow<Int> get() = _followRev
    /** Followed shows: IMDb id → name. */
    fun followed(): Map<String, String> = App.instance.prefs.sp.getStringSet("comingup_follow", emptySet()).orEmpty()
        .associate { it.substringBefore('|') to it.substringAfter('|', "") }
    fun isFollowed(imdb: String) = imdb.substringBefore(':') in followed()

    // Follow only for shows still running (user 2026-11-27): finished or cancelled shows get no Follow (unfollow stays).
    @Volatile private var statusCache: Pair<Long, Map<String, String>>? = null
    /** Finished or cancelled: an end year (IMDb "2019-2022") or TVmaze / TMDB status Ended / Canceled (files on disk). */
    fun ended(imdb: String, releaseInfo: String? = null, endYear: Int? = null): Boolean {
        if (endYear != null) return true
        if (releaseInfo != null && Regex("""\d{4}\s*[-–]\s*\d{4}""").containsMatchIn(releaseInfo)) return true
        val now = System.currentTimeMillis()
        val m = statusCache?.takeIf { now - it.first < 600_000 }?.second ?: runCatching {
            (read("tmdb_shows.json", showSer) ?: emptyMap()).mapValues { it.value.status } +
                (read("shows.json", showSer) ?: emptyMap()).mapValues { it.value.status }
        }.getOrDefault(emptyMap()).also { statusCache = now to it }
        val st = m[imdb.substringBefore(':')] ?: return false
        return st.equals("Ended", true) || st.contains("cancel", true)
    }
    fun follow(imdb: String, name: String, on: Boolean) {
        val id = imdb.substringBefore(':')
        val now = followed().toMutableMap()
        if (on) now[id] = name else now.remove(id)
        val sp = App.instance.prefs.sp.edit().putStringSet("comingup_follow", now.map { "${it.key}|${it.value}" }.toSet())
        if (on) sp.putStringSet("comingup_dropped", dropped() - id)
        sp.apply()
        _followRev.value++
        if (on) refresh(false)
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
        val films = HashMap<String, String>()
        val tvdb = HashMap<String, Long>()
        fun name(map: HashMap<String, String>, id: String, t: String?) { if (map[id].isNullOrBlank()) map[id] = t.orEmpty() }
        watched?.forEach { w -> w.show.ids.imdb?.let { lastWatched[it] = ms(w.lastWatchedAt); name(names, it, w.show.title); w.show.ids.tvdb?.let { t -> tvdb[it] = t } } }
        // Trakt's own calendar of your shows (next 30 days): checked first, so they show even mid first run.
        val calendar = runCatching { trakt.calendar(30).first }.getOrDefault(emptyList())
        val soon = LinkedHashSet<String>()
        calendar.forEach { c -> c.show.ids.imdb?.let { soon += it; name(names, it, c.show.title); c.show.ids.tvdb?.let { t -> tvdb.putIfAbsent(it, t) } } }
        runCatching { trakt.watchlist() }.getOrDefault(emptyList()).forEach { l ->
            l.show?.let { s -> s.ids.imdb?.let { name(names, it, s.title) } }
            l.movie?.let { m -> m.ids.imdb?.let { name(films, it, m.title) } }
        }
        Saves.all().filter { it.id.startsWith("tt") }.forEach {
            if (it.type == "series") name(names, it.id.substringBefore(':'), it.name) else if (it.type == "movie") name(films, it.id.substringBefore(':'), it.name)
        }
        // The rest of your Trakt library (collection, ratings, your own lists) and Simkl (not dropped), as Vision.
        runCatching { trakt.libraryShows() }.getOrDefault(emptyList()).forEach { sh ->
            sh.ids.imdb?.let { name(names, it, sh.title); sh.ids.tvdb?.let { t -> tvdb.putIfAbsent(it, t) } }
        }
        // Simkl (a Nuvio / Stremio library came in once at Join, into Trakt / Simkl and Saves). Titles they know only by a
        // TMDB id get their IMDb id from TMDB (kept, one call each).
        val tmdbImdb = (read("tmdb_imdb.json", strSer) ?: emptyMap()).toMutableMap()
        val others = runCatching { com.lekathedon.visionlite.integrations.SignIns.simklFollowed() }.getOrDefault(emptyList())
        for (f in others) {
            val film = f.type == "movie"
            val imdb = if (f.id.startsWith("tt")) f.id else {
                val k = (if (film) "movie:" else "tv:") + f.id.removePrefix("tmdb:")
                tmdbImdb[k] ?: (externalImdb(f.id.removePrefix("tmdb:"), film) ?: continue).also { tmdbImdb[k] = it; delay(120) }
            }
            if (!imdb.startsWith("tt")) continue
            name(if (film) films else names, imdb, f.title)
        }
        write("tmdb_imdb.json", strSer, tmdbImdb)
        // Shows you follow: always in, always checked.
        val follows = followed()
        follows.forEach { (id, n) -> name(names, id, n) }
        Log.i(TAG, "following ${names.size} shows (${follows.size} followed), ${films.size} films")
        if (names.isEmpty() && films.isEmpty()) { _items.value = emptyList(); return }

        val ids = (read("tvmaze_ids.json", idSer) ?: emptyMap()).toMutableMap()
        val shows = (read("shows.json", showSer) ?: emptyMap()).toMutableMap()
        val tmdbIds = (read("tmdb_ids.json", idSer) ?: emptyMap()).toMutableMap()
        val tmdbShows = (read("tmdb_shows.json", showSer) ?: emptyMap()).toMutableMap()
        val filmDates = (read("films.json", filmSer) ?: emptyMap()).toMutableMap()
        // Shows that weren't found before but are on Trakt's calendar (or followed) now: look again.
        (soon + follows.keys).forEach { if (ids[it] == -1) ids.remove(it) }
        // Refresh, or once after this fix (older builds kept failed lookups as "not on TVmaze"): ask all misses again.
        val sp = App.instance.prefs.sp
        if (force || !sp.getBoolean("comingup_v2", false)) { ids.values.removeAll { it < 0 }; sp.edit().putBoolean("comingup_v2", true).apply() }
        // Which shows TVmaze changed since the last check (one call); a first run or a long gap reads them all.
        val since = App.instance.prefs.sp.getLong("comingup_at", 0)
        val changed: Set<Int>? = if (force || since == 0L || now - since > 6 * DAY) null else runCatching {
            val o = JSONObject(get("$TVMAZE/updates/shows?since=${if (now - since > DAY) "week" else "day"}"))
            o.keys().asSequence().mapNotNull { it.toIntOrNull() }.toSet()
        }.getOrNull()

        fun save() {
            write("tvmaze_ids.json", idSer, ids); write("shows.json", showSer, shows)
            write("tmdb_ids.json", idSer, tmdbIds); write("tmdb_shows.json", showSer, tmdbShows); write("films.json", filmSer, filmDates)
        }
        fun show() = publish(names, films, lastWatched, ids, shows, tmdbShows, filmDates, now)

        var done = 0
        // Followed and Trakt's calendar first, then the most recently watched; the rest after.
        val todo = names.keys.sortedWith(compareByDescending<String> { it in follows }.thenByDescending { it in soon }.thenByDescending { lastWatched[it] ?: 0L })
        for (imdb in todo) {
            done++
            if (done % 20 == 0) {
                _status.value = "Checking your shows… $done/${todo.size}"
                show()
                // Kept as it goes: an interrupted first run carries on where it stopped next time.
                save()
            }
            val id = ids[imdb] ?: run {
                // Not on TVmaze by IMDb id: try its TheTVDB id. A failed call (busy, offline) is NOT kept, so the show
                // is asked again next time instead of being dropped for good.
                var found = lookup("imdb", imdb)
                if (found == -1) tvdb[imdb]?.let { t -> delay(450); found = lookup("thetvdb", t.toString()) }
                delay(450)
                found?.also { ids[imdb] = it } ?: return@run -2
            }
            if (id < 0) continue
            val s = shows[imdb]
            if (s?.anime == true) com.lekathedon.visionlite.anime.AnimeIds.learn(imdb)
            val aired = s != null && s.nextAt in 1..now
            // A running show read before the anime check is read once more (a new anime isn't in the id list yet).
            val stale = s == null || now - s.readAt > 7 * DAY || (s.anime == null && s.status != "Ended")
            val followedNow = imdb in follows && now - (s?.readAt ?: 0) > 6 * 3600_000L
            if (s != null && !aired && !stale && !followedNow && changed != null && id !in changed) continue
            readShow(id)?.let { shows[imdb] = it; if (it.anime == true) com.lekathedon.visionlite.anime.AnimeIds.learn(imdb) }
            delay(450)
        }
        save()

        // TMDB, the second source (needs your TMDB key): shows TVmaze doesn't know, running shows TVmaze has no date
        // for, and followed shows (an ended show can come back). Once a day each (followed: every 6 h).
        if (com.lekathedon.visionlite.home.Tmdb.key() != null) {
            val ask = names.keys.filter { imdb ->
                val tv = shows[imdb]
                val undated = tv == null || (tv.status != "Ended" && tv.nextAt <= now)
                val age = now - (tmdbShows[imdb]?.readAt ?: 0)
                (imdb in follows && age > 6 * 3600_000L) || (undated && age > 20 * 3600_000L)
            }.sortedByDescending { it in follows }
            ask.forEachIndexed { i, imdb ->
                if (i % 25 == 0) { _status.value = "Checking dates on TMDB… $i/${ask.size}"; if (i > 0) { show(); save() } }
                val tid = tmdbId(imdb, tv = true, tmdbIds) ?: return@forEachIndexed
                tmdbShow(tid)?.let { tmdbShows[imdb] = it }
                delay(120)
            }
            // Films: until out on digital; daily when a date is near or unknown, else every 4 days.
            val askFilms = films.keys.filter { imdb ->
                val f = filmDates[imdb]
                if (f != null && f.digital in 1..(now - 30 * DAY)) return@filter false
                val near = f == null || f.cinema == 0L && f.digital == 0L || listOf(f.cinema, f.digital).any { it in (now - 2 * DAY)..(now + 30 * DAY) }
                now - (f?.readAt ?: 0) > (if (near) 20 * 3600_000L else 4 * DAY)
            }
            askFilms.forEachIndexed { i, imdb ->
                if (i % 25 == 0) { _status.value = "Checking film dates… $i/${askFilms.size}"; if (i > 0) { show(); save() } }
                val tid = tmdbId(imdb, tv = false, tmdbIds) ?: return@forEachIndexed
                tmdbFilm(tid, films[imdb].orEmpty())?.let { filmDates[imdb] = it }
                delay(120)
            }
            save()
        }
        this.films = filmsAhead()
        show()
        App.instance.prefs.sp.edit().putLong("comingup_at", now).apply()
    }

    // ---- TMDB ----

    private fun tmdbId(imdb: String, tv: Boolean, cache: MutableMap<String, Int>): Int? {
        val k = (if (tv) "tv:" else "movie:") + imdb
        cache[k]?.let { return it.takeIf { v -> v > 0 } }
        val o = com.lekathedon.visionlite.home.Tmdb.get("find/$imdb?external_source=imdb_id") ?: return null
        val id = o.optJSONArray(if (tv) "tv_results" else "movie_results")?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
        cache[k] = id ?: -1
        return id
    }

    private fun externalImdb(tmdb: String, film: Boolean): String? =
        com.lekathedon.visionlite.home.Tmdb.get("${if (film) "movie" else "tv"}/$tmdb/external_ids")?.optString("imdb_id")?.takeIf { it.startsWith("tt") }

    private fun ymdMs(d: String?): Long = d?.takeIf { it.length >= 10 }?.let {
        runCatching { java.time.LocalDate.parse(it.take(10)).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
    } ?: 0L

    private fun tmdbShow(id: Int): Show? {
        val o = com.lekathedon.visionlite.home.Tmdb.get("tv/$id") ?: return null
        val next = o.optJSONObject("next_episode_to_air")
        val prev = o.optJSONObject("last_episode_to_air")
        fun num(e: JSONObject?, k: String) = e?.optInt(k, -1)?.takeIf { it >= 0 }
        return Show(0, o.optString("name"), o.optString("poster_path").takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w500$it" },
            o.optString("status"), num(next, "season_number"), num(next, "episode_number"), next?.optString("name"), ymdMs(next?.optString("air_date")),
            num(prev, "season_number"), num(prev, "episode_number"), prev?.optString("name"), ymdMs(prev?.optString("air_date")), System.currentTimeMillis(),
            premAt = num(prev, "season_number")?.let { ps -> o.optJSONArray("seasons")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }
                .firstOrNull { it.optInt("season_number", -1) == ps }?.optString("air_date")?.let { ymdMs(it) } } } ?: 0L)
    }

    /** Cinema (type 3, else 2) and digital (type 4) dates: your country's, else the US's, else the earliest anywhere. */
    private fun tmdbFilm(id: Int, name: String): Film? {
        val o = com.lekathedon.visionlite.home.Tmdb.get("movie/$id?append_to_response=release_dates") ?: return null
        val results = o.optJSONObject("release_dates")?.optJSONArray("results")
        val byCountry = HashMap<String, MutableList<Pair<Int, Long>>>()
        for (i in 0 until (results?.length() ?: 0)) {
            val r = results!!.optJSONObject(i) ?: continue
            val list = byCountry.getOrPut(r.optString("iso_3166_1")) { mutableListOf() }
            val d = r.optJSONArray("release_dates") ?: continue
            for (j in 0 until d.length()) d.optJSONObject(j)?.let { x -> ymdMs(x.optString("release_date")).takeIf { it > 0 }?.let { list += x.optInt("type") to it } }
        }
        val region = java.util.Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"
        fun pick(vararg types: Int): Long {
            for (c in listOf(region, "US")) byCountry[c]?.let { l -> types.forEach { t -> l.filter { it.first == t }.minOfOrNull { it.second }?.let { return it } } }
            types.forEach { t -> byCountry.values.flatten().filter { it.first == t }.minOfOrNull { it.second }?.let { return it } }
            return 0L
        }
        return Film(id, o.optString("title").ifBlank { name }, pick(3, 2), pick(4),
            o.optString("poster_path").takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w500$it" }, System.currentTimeMillis())
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

    private fun publish(names: Map<String, String>, filmNames: Map<String, String>, lastWatched: Map<String, Long>, ids: Map<String, Int>,
                        shows: Map<String, Show>, tmdbShows: Map<String, Show>, filmDates: Map<String, Film>, now: Long) {
        val newSince = now - NEW_DAYS * DAY
        val gone = dropped()
        val list = names.keys.filter { it !in gone }.flatMap { imdb ->
            val tv = shows[imdb]
            val tm = tmdbShows[imdb]
            val name = names[imdb]?.ifBlank { null } ?: tv?.name?.ifBlank { null } ?: tm?.name.orEmpty()
            if (name.isBlank()) return@flatMap emptyList()
            buildList {
                // Aired in the last days and after you last watched it: New (TVmaze; TMDB only for shows TVmaze lacks).
                val p = tv?.takeIf { it.prevAt > 0 } ?: tm
                val seen = lastWatched[imdb] ?: 0
                if (p != null && p.prevAt in newSince..now && p.prevAt > seen)
                    add(Item(imdb, "series", name, p.prevS, p.prevE, p.prevTitle, p.prevAt, true, tv?.image ?: tm?.image, dateOnly = p.tvmaze == 0))
                // Already back: the season started in the last BACK_DAYS and you haven't watched it since (a whole
                // season dropped at once, or a show you lost track of). Shown with its latest aired episode.
                else if (p != null && p.prevAt <= now) {
                    val prem = p.premAt.takeIf { it > 0 } ?: tm?.takeIf { it.prevS == p.prevS }?.premAt ?: 0L
                    if (prem in (now - BACK_DAYS * DAY)..now && prem > seen)
                        add(Item(imdb, "series", name, p.prevS, p.prevE, "Season ${p.prevS} is back" + (p.prevE?.let { " · $it episodes out" } ?: ""),
                            p.prevAt, true, tv?.image ?: tm?.image, dateOnly = true, back = true))
                }
                // Next: TVmaze's (with its time); when TVmaze has no date, TMDB's day.
                val n = tv?.takeIf { it.nextAt > now } ?: tm?.takeIf { it.nextAt > now - DAY / 2 }
                if (n != null) add(Item(imdb, "series", name, n.nextS, n.nextE, n.nextTitle, n.nextAt, false, tv?.image ?: tm?.image, dateOnly = n.tvmaze == 0))
            }
        }
        val filmItems = filmNames.keys.filter { it !in gone }.flatMap { imdb ->
            val f = filmDates[imdb] ?: return@flatMap emptyList()
            val name = filmNames[imdb]?.ifBlank { null } ?: f.name
            buildList {
                if (f.cinema >= now - DAY / 2 && (f.digital == 0L || f.cinema < f.digital)) add(Item(imdb, "movie", name, epTitle = "In cinemas", at = f.cinema, image = f.poster, dateOnly = true))
                if (f.digital >= now - DAY / 2) add(Item(imdb, "movie", name, epTitle = "Digital release", at = f.digital, image = f.poster, dateOnly = true))
                else if (f.digital in newSince..now) add(Item(imdb, "movie", name, epTitle = "Out on digital", at = f.digital, isNew = true, image = f.poster, dateOnly = true))
            }
        }
        val dated = filmItems.map { it.imdb }.toSet()
        val traktFilms = this.films.filter { it.at >= now - DAY && it.imdb !in dated && it.imdb !in gone }
        val all = list + filmItems
        val sorted = all.filter { it.isNew }.sortedByDescending { it.at } + (all.filter { !it.isNew } + traktFilms).sortedBy { it.at }
        val out = sorted.distinctBy { it.key }.take(120)
        _items.value = out
        write("items.json", itemSer, out)
    }

    /** TVmaze id; -1 = TVmaze doesn't have it; null = no answer this time. */
    private fun lookup(by: String, id: String): Int? = runCatching {
        Net.http.newCall(Request.Builder().url("$TVMAZE/lookup/shows?$by=$id").build()).execute().use { r ->
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
            num(prev, "season"), num(prev, "number"), prev?.optString("name"), at(prev), System.currentTimeMillis(),
            // Brand-new anime (Steel Ball Run, 2026) aren't in the anime databases' id list for weeks; TVmaze tags them.
            anime = o.optJSONArray("genres")?.let { g -> (0 until g.length()).any { g.optString(it) == "Anime" } } == true ||
                (o.optString("type") == "Animation" && o.optString("language") == "Japanese"),
            premAt = premiere(id, num(prev, "season"), num(prev, "number"), at(prev)))
    }.getOrNull()

    /** The start of the season the last episode belongs to; asked only when that episode aired in the last [BACK_DAYS]. */
    private fun premiere(id: Int, season: Int?, ep: Int?, prevAt: Long): Long {
        if (season == null || prevAt < System.currentTimeMillis() - BACK_DAYS * DAY) return 0L
        if (ep == 1) return prevAt
        return runCatching {
            Thread.sleep(450)
            val a = org.json.JSONArray(get("$TVMAZE/shows/$id/seasons"))
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optInt("number") == season }
                ?.optString("premiereDate")?.takeIf { it.length >= 10 }?.let { ymdMs(it) }
        }.getOrNull() ?: 0L
    }

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
        if (i.back) return "Back"
        if (i.isNew) return "New"
        val c = java.util.Calendar.getInstance()
        fun day(ms: Long) = c.apply { timeInMillis = ms }.let { it.get(java.util.Calendar.YEAR) * 400 + it.get(java.util.Calendar.DAY_OF_YEAR) }
        val d = day(i.at) - day(now)
        val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(i.at))
        val hour = c.apply { timeInMillis = i.at }.get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            i.type == "movie" -> if (d <= 0) "Out today" else "Out " + java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(i.at))
            d <= 0 -> if (i.dateOnly) "Today" else if (hour >= 18) "Tonight $hm" else "Today $hm"
            d == 1 -> "Tomorrow"
            d < 7 -> java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(java.util.Date(i.at))
            else -> java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(i.at))
        }
    }

    fun episodeLine(i: Item): String? = if (i.season == null) i.epTitle?.takeIf { i.type == "movie" } else
        (if (i.episode == 1) "Season ${i.season} premiere · " else "") + "S${i.season} E${i.episode}" + (i.epTitle?.takeIf { it.isNotBlank() && !it.startsWith("Episode") }?.let { " · $it" } ?: "")
}
