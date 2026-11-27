package com.lekathedon.visionlite.sport

import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.data.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Every game Sports knows, one list for the Sports page, the clock's Live now and the host (reminders, pins, alerts).
 * Nothing is asked until something [watch]es; saved boards show at once (≤ 150 ms) and live data replaces them in place.
 * Refresh: 30 s for leagues with a live game, 5 min for the rest, stopped when nobody watches.
 */
object Scores {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs get() = App.instance.prefs
    private val dir by lazy { File(App.instance.filesDir, "sport").apply { mkdirs() } }
    private val gate = Semaphore(6)

    val settings: StateFlow<Settings> = prefs.sportsSettings.flow.map { Settings.of(it.ifBlank { null }) }.stateIn(scope, SharingStarted.Eagerly, Settings.of(prefs.sportsSettings.value.ifBlank { null }))
    val follows: StateFlow<Follows> = prefs.sportsFollows.flow.map { Follows.of(it.ifBlank { null }) }.stateIn(scope, SharingStarted.Eagerly, Follows.of(prefs.sportsFollows.value.ifBlank { null }))
    fun save(f: Follows) { prefs.sportsFollows.value = f.toJson() }
    fun save(s: Settings) { prefs.sportsSettings.value = s.toJson() }

    /** league|easternDate → (fetched at, games). */
    private val boards = HashMap<String, Pair<Long, List<Game>>>()
    private val _games = MutableStateFlow<List<Game>>(emptyList())
    val games: StateFlow<List<Game>> = _games
    /** Bumped after every pass (Refresh's "Updated 14:05"). */
    val updatedAt = MutableStateFlow(0L)

    /** The leagues shown now: your region's order, without the hidden ones; followed / top-bar ones always. */
    fun shownLeagues(s: Settings = settings.value, f: Follows = follows.value): List<League> {
        val must = f.bar().toSet() + f.leagues + f.teams.map { it.league }
        return Leagues.forRegion(region(s)).filter { it.key !in s.hiddenLeagues || it.key in must }
    }

    fun region(s: Settings = settings.value): String = s.region.ifBlank { java.util.Locale.getDefault().country }

    // ---- who's watching ----

    private var watchers = 0
    private var loop: Job? = null

    /** Sports page / game page / clock panel on screen: call on show, and the returned function on hide. */
    @Synchronized fun watch(): () -> Unit {
        watchers++
        if (loop?.isActive != true) loop = scope.launch { run() }
        var done = false
        return { synchronized(this) { if (!done) { done = true; watchers--; if (watchers <= 0) { watchers = 0; loop?.cancel() } } } }
    }

    private suspend fun run() {
        loadSaved(today())
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            pass(today(), force = false)
            val live = _games.value.any { it.state == State.LIVE }
            delay(if (live) 30_000 else 300_000)
        }
    }

    // ---- days ----

    fun today(): LocalDate = LocalDate.now()

    /** The two Eastern dates one local day touches. */
    private fun easternDates(day: LocalDate): List<LocalDate> {
        val zone = ZoneId.systemDefault()
        val a = day.atStartOfDay(zone).withZoneSameInstant(Espn.eastern).toLocalDate()
        val b = day.plusDays(1).atStartOfDay(zone).minusSeconds(1).withZoneSameInstant(Espn.eastern).toLocalDate()
        return listOf(a, b).distinct()
    }

    fun gamesOn(day: LocalDate, all: List<Game> = _games.value): List<Game> {
        val zone = ZoneId.systemDefault()
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli(); val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return all.filter { it.startMs in from until to || it.state == State.LIVE && day == today() }
    }

    /** Ask a day (when picked on the days bar): saved first, then fresh. */
    fun ask(day: LocalDate) { scope.launch { loadSaved(day); pass(day, force = false) } }

    /**
     * One pass: each shown league's boards for [day]. Leagues with a live game are always asked again; the rest when
     * older than 5 min ([force]: everything, Refresh). [only]: just these leagues (a game page's Refresh).
     */
    suspend fun pass(day: LocalDate, force: Boolean, only: Set<String>? = null) {
        val now = System.currentTimeMillis()
        val leagues = shownLeagues().map { it.key }.filter { only == null || it in only }
        val due = leagues.flatMap { l -> easternDates(day).map { "$l|$it" } }.filter { k ->
            val b = synchronized(boards) { boards[k] }
            force || b == null || b.second.any { it.state == State.LIVE } || now - b.first > 300_000 ||
                b.second.any { it.state == State.PRE && it.startMs in (b.first)..now }
        }
        coroutineScopeAll(due) { k ->
            val (league, date) = k.split('|').let { it[0] to LocalDate.parse(it[1]) }
            val raw = runCatching { Net.get("https://site.api.espn.com/apis/site/v2/sports/$league/scoreboard?dates=${date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)}&limit=300", 15) }.getOrNull()
                ?: return@coroutineScopeAll
            val list = runCatching { Espn.parseBoard(league, JSONObject(raw)) }.getOrNull() ?: return@coroutineScopeAll
            synchronized(boards) { boards[k] = System.currentTimeMillis() to list }
            runCatching { File(dir, "b_${k.replace('/', '_').replace('|', '_')}.json").writeText(raw) }
        }
        publish()
        updatedAt.value = System.currentTimeMillis()
    }

    private suspend fun coroutineScopeAll(keys: List<String>, f: suspend (String) -> Unit) = withContext(Dispatchers.IO) {
        keys.map { k -> async { gate.withPermit { f(k) } } }.awaitAll()
    }

    private suspend fun loadSaved(day: LocalDate) = withContext(Dispatchers.IO) {
        var any = false
        shownLeagues().forEach { l -> easternDates(day).forEach { d ->
            val k = "${l.key}|$d"
            if (synchronized(boards) { k in boards }) return@forEach
            val f = File(dir, "b_${k.replace('/', '_').replace('|', '_')}.json")
            if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 7 * 86_400_000L) return@forEach
            runCatching { Espn.parseBoard(l.key, JSONObject(f.readText())) }.getOrNull()?.let { synchronized(boards) { boards[k] = f.lastModified() to it }; any = true }
        } }
        if (any) publish()
    }

    private fun publish() {
        val all = synchronized(boards) { boards.values.flatMap { it.second } }.distinctBy { it.key }.sortedBy { it.startMs }
        _games.value = all
    }

    /** Drop saved boards older than a week (on the first watch of a day). */
    fun tidy() = scope.launch { dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 7 * 86_400_000L }?.forEach { it.delete() } }

    /** Games seen in a team's season (not on a day board): the game page finds them here. */
    val looked = MutableStateFlow<Map<String, Game>>(emptyMap())
    fun game(key: String): Game? = _games.value.firstOrNull { it.key == key || it.id == key } ?: looked.value[key]

    // ---- lists the screens show ----

    /** Live now: your teams first, then games on one of your channels, then the rest. */
    fun liveNow(all: List<Game> = _games.value, onMyChannels: (Game) -> Boolean = { false }): List<Game> {
        val f = follows.value
        return all.filter { it.state == State.LIVE }.sortedWith(compareBy<Game>({ if (f.follows(it)) 0 else if (onMyChannels(it)) 1 else 2 }, { it.startMs }))
    }

    /** A team's season, kept an hour (at most 6 teams). */
    private val seasons = object : LinkedHashMap<String, Pair<Long, List<Game>>>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<Long, List<Game>>>?) = size > 6
    }
    suspend fun season(t: Team): List<Game> {
        synchronized(seasons) { seasons[t.key] }?.takeIf { System.currentTimeMillis() - it.first < 3_600_000 }?.let { return it.second }
        val list = runCatching { Espn.schedule(t.league, t.id) }.getOrDefault(emptyList())
        synchronized(seasons) { seasons[t.key] = System.currentTimeMillis() to list }
        looked.value = (looked.value + list.associateBy { it.key }).let { m -> if (m.size > 400) list.associateBy { it.key } else m }
        return list
    }

    /** A league's teams (Pick Your Teams), kept a week on disk. */
    suspend fun teams(league: String): List<Team> = withContext(Dispatchers.IO) {
        val f = File(dir, "t_${league.replace('/', '_')}.json")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 7 * 86_400_000L)
            runCatching { org.json.JSONArray(f.readText()).objs().map { Team(league, it.optString("id"), it.optString("n"), it.optString("a"),
                if (it.has("c")) it.optLong("c") else null, it.optString("logo").ifBlank { null }) } }.getOrNull()?.let { return@withContext it }
        val list = runCatching { Espn.teams(league) }.getOrDefault(emptyList())
        if (list.isNotEmpty()) runCatching { f.writeText(org.json.JSONArray(list.map { JSONObject().put("id", it.id).put("n", it.name).put("a", it.abbr).put("c", it.color).put("logo", it.logo) }).toString()) }
        list
    }

    /** Tables, kept an hour. */
    private val tables = HashMap<String, Pair<Long, List<Espn.Group>>>()
    suspend fun table(l: League): List<Espn.Group> {
        synchronized(tables) { tables[l.key] }?.takeIf { System.currentTimeMillis() - it.first < 3_600_000 }?.let { return it.second }
        val g = runCatching { Espn.standings(l) }.getOrDefault(emptyList())
        if (g.isNotEmpty()) synchronized(tables) { tables[l.key] = System.currentTimeMillis() to g }
        return g
    }
}
