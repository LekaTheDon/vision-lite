package com.lekathedon.visionlite.sports

import android.util.Log
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.iptv.Iptv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Sports for Lite: Vision's catalogue, ESPN feeds, guide discovery and channel matcher (copied as is), run by a small
 * loop of its own. Dormant until looked at: nothing is fetched unless the Sports page or the clock's Live now is on
 * screen ([viewing]). Scores refresh every 30 s while a game shown is live, otherwise every 5 minutes; the channel
 * match reruns when scores or the guide change.
 */
object Sports {
    private const val TAG = "Sports"
    private const val MINUTE = 60_000L
    private const val DAY = 24 * 60 * MINUTE
    private val prefs get() = App.instance.prefs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val feeds by lazy { SportsFeeds() }
    private val dir by lazy { File(App.instance.profileDir, "sports").apply { mkdirs() } }
    private val matcher by lazy { SportsMatcher(EpgDatabase(), File(dir, "guide").apply { mkdirs() }) }

    val settings: SportsSettings get() = SportsSettings.fromJson(prefs.sportsSettings.value.ifBlank { null })
    val follows: SportsFollows get() = SportsFollows.fromJson(prefs.sportsFollows.value.ifBlank { null })
    /** The same two, as flows (Vision's Sports screens watch them). */
    val settingsFlow: StateFlow<SportsSettings> by lazy {
        prefs.sportsSettings.flow.map { SportsSettings.fromJson(it.ifBlank { null }) }.stateIn(scope, SharingStarted.Eagerly, settings)
    }
    val followsFlow: StateFlow<SportsFollows> by lazy {
        prefs.sportsFollows.flow.map { SportsFollows.fromJson(it.ifBlank { null }) }.stateIn(scope, SharingStarted.Eagerly, follows)
    }

    /** The Sports page's days: a week back to a week ahead (Vision's). */
    const val DAYS_BACK = 7
    const val DAYS_AHEAD = 7

    private class LeagueData(val events: List<SportsEvent>, val at: Long)
    private val data = ConcurrentHashMap<String, LeagueData>()
    private val fixtures = ConcurrentHashMap<String, LeagueData>()
    /** Days other than yesterday..tomorrow, fetched only when the page shows one: by "league|ESPN date". */
    private val dayData = ConcurrentHashMap<String, LeagueData>()
    /** Followed leagues (and leagues on your top bar): a week ahead to five weeks ahead, by month. */
    private val ahead = ConcurrentHashMap<String, LeagueData>()
    /** A team looked up on a table (its card): its season, kept an hour (at most 6 teams). */
    private val _lookedUp = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    @Volatile private var wantedDay: Int? = null
    @Volatile private var wantedAt = 0L
    @Volatile private var guide: List<SportsEvent> = emptyList()

    private val _events = MutableStateFlow<List<SportsEvent>>(emptyList())
    val events: StateFlow<List<SportsEvent>> get() = _events
    private val _matches = MutableStateFlow<Map<String, List<ChannelMatch>>>(emptyMap())
    /** Event id → the channels showing it, best first. */
    val matches: StateFlow<Map<String, List<ChannelMatch>>> get() = _matches
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> get() = _status
    private val _matching = MutableStateFlow(false)
    /** Looking through your channels for the games (the first time takes a few seconds). */
    val matching: StateFlow<Boolean> get() = _matching
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> get() = _loading

    // ---- who is looking ----

    private var viewers = 0
    private var loop: Job? = null

    /** The Sports page or Live now is on screen (pair with [stopViewing]). */
    @Synchronized fun viewing() {
        viewers++
        if (loop?.isActive != true) loop = scope.launch { run() }
    }

    @Synchronized fun stopViewing() {
        viewers = (viewers - 1).coerceAtLeast(0)
        if (viewers == 0) { loop?.cancel(); loop = null }
    }

    private var force = false
    private val kick = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    fun refreshNow() { force = true; kick.trySend(Unit); if (viewers > 0 && loop?.isActive != true) loop = scope.launch { run() } }

    /** The clock's Refresh, step by step: [busy] while it runs, [step] what it's doing, [at] when it last finished. */
    data class LiveRefresh(val busy: Boolean = false, val step: String = "", val at: Long = 0L, val live: Int = 0, val failed: String? = null)
    private val _liveRefresh = MutableStateFlow(LiveRefresh())
    val liveRefresh: StateFlow<LiveRefresh> get() = _liveRefresh
    @Volatile private var liveJob: Job? = null

    /**
     * The clock's Refresh (user 2026-11-24: "it does nothing, it doesn't even seem to register"): the TV guide asked
     * again (downloaded only when the provider's copy changed), every league's scores asked now, the guide's own games
     * read again, then every game matched to your channels. Each step shows on the chip. A press while it runs is ignored.
     * Cost: the playlist + a conditional guide request, one ESPN request per league (6 at a time), one full match.
     */
    fun refreshLive() {
        if (liveJob?.isActive == true) return
        _liveRefresh.value = LiveRefresh(busy = true, step = "Starting…", at = _liveRefresh.value.at)
        liveJob = scope.launch {
            fun step(t: String) { _liveRefresh.value = _liveRefresh.value.copy(step = t) }
            var failed: String? = null
            try {
                if (data.isEmpty()) runCatching { loadCache() }
                val pls = Iptv.playlists()
                if (pls.isNotEmpty()) {
                    step("Updating the TV guide…")
                    pls.forEach { pl -> runCatching { Iptv.refresh(pl) }.onFailure { failed = "Guide: ${it.message}" } }
                }
                step("Getting the live scores…")
                val now = System.currentTimeMillis()
                runCatching { update(now, everything = true) }.onFailure { failed = "Scores: ${it.message}" }
                runCatching { updateDay(now) }
                guide = runCatching { guideEvents(now) }.getOrDefault(guide)
                publish()
                if (Iptv.hasChannels()) {
                    step("Finding the channels…")
                    runCatching { match(System.currentTimeMillis()) }.onFailure { failed = "Channels: ${it.message}" }
                }
            } finally {
                val t = System.currentTimeMillis()
                _liveRefresh.value = LiveRefresh(busy = false, at = t, live = runCatching { liveNow(t).size }.getOrDefault(0), failed = failed)
                kick.trySend(Unit)
            }
        }
    }

    private suspend fun run() {
        if (data.isEmpty()) loadCache()
        // Live TV's channels and guide (only now that sports is open; loads at most daily).
        com.lekathedon.visionlite.iptv.IptvSync.kick()
        var matchedAt = 0L; var matchedGuide: String? = null; var matchedEvents = 0
        while (currentCoroutineContextActive()) {
            val now = System.currentTimeMillis()
            _loading.value = _events.value.isEmpty()
            var changed = runCatching { update(now) }.onFailure { Log.w(TAG, "update: ${it.message}") }.getOrDefault(false)
            if (runCatching { updateDay(now) }.onFailure { Log.w(TAG, "day: ${it.message}") }.getOrDefault(false)) changed = true
            if (runCatching { updateAhead(now) }.onFailure { Log.w(TAG, "ahead: ${it.message}") }.getOrDefault(false)) changed = true
            if (changed) publish()
            _loading.value = false
            val rev = runCatching { Iptv.guideRevision() }.getOrNull()
            if (rev != matchedGuide) { guide = runCatching { guideEvents(now) }.getOrDefault(guide); publish() }
            val evs = _events.value
            if (changed || rev != matchedGuide || evs.size != matchedEvents || now - matchedAt > 2 * MINUTE) {
                runCatching { match(now) }.onFailure { Log.w(TAG, "match: ${it.message}") }
                matchedAt = now; matchedGuide = rev; matchedEvents = evs.size
            }
            // Every 15 s, or at once when a change asks (a day picked, a team followed).
            kotlinx.coroutines.withTimeoutOrNull(15_000) { kick.receive() }
        }
    }

    private suspend fun currentCoroutineContextActive() = kotlin.coroutines.coroutineContext.isActive

    // ---- leagues and days ----

    fun activeLeagues(s: SportsSettings = settings, f: SportsFollows = follows): List<League> {
        val mine = HashSet<String>().apply {
            f.teams.forEach { t -> add(t.leagueId); addAll(SportsCatalog.relatedLeagues(t.leagueId)) }
            addAll(f.leagues); addAll(f.pins)
        }
        return SportsCatalog.ordered(s).filter { (SportsCatalog.shown(it, s) || it.id in mine) && SportsTaxonomy.accepts(it, s.excludedSportsNodes) }
    }

    fun dayStart(offset: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, offset)
    }.timeInMillis

    private fun espnDays(now: Long, all: Boolean): List<String> {
        val f = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }
        return (if (all) listOf(now - DAY, now, now + DAY) else listOf(now)).map { f.format(java.util.Date(it)) }.distinct()
    }

    // ---- fetching ----

    /** Fetches the leagues that are due; true when anything new arrived. */
    private suspend fun update(now: Long, everything: Boolean = false): Boolean = coroutineScope {
        val s = settings
        val forced = force.also { force = false }
        val leagues = activeLeagues(s)
        val due = leagues.filter { l ->
            if (l.espnPath == null) return@filter false
            val d = data[l.id]
            val age = now - (d?.at ?: 0L)
            val live = d?.events?.any { it.state == GameState.LIVE || (it.state == GameState.PRE && it.startMs in (now - 15 * MINUTE)..(now + 10 * MINUTE)) } == true
            when { everything -> true; forced -> age > 15_000; live -> age > 30_000; else -> age > 5 * MINUTE }
        }
        val fx = leagues.filter { it.espnPath == null && it.fixtureSlug != null && now - (fixtures[it.id]?.at ?: 0L) > 6 * 60 * MINUTE }
        if (due.isEmpty() && fx.isEmpty()) return@coroutineScope false
        if (_events.value.isEmpty()) _status.value = "Getting today's games…"
        val gate = Semaphore(6)
        var failed = 0
        (due.map { l ->
            async {
                gate.withPermit {
                    val old = data[l.id]
                    // A live league only needs today's page; the three days on the first fetch and every 5 minutes.
                    val all = everything || old == null || now - old.at > 5 * MINUTE
                    val raws = espnDays(now, all).mapNotNull { d -> runCatching { feeds.scoreboardDayRaw(l, d) }.getOrNull() }
                    if (raws.isEmpty()) { failed++; return@withPermit }
                    val parsed = raws.flatMap { raw -> runCatching { SportsFeeds.parseScoreboard(l, raw) }.getOrDefault(emptyList()) }
                    val merged = if (all) parsed.distinctBy { it.id }
                        else (parsed + old!!.events).distinctBy { it.id }
                    old?.let { o -> noticeScoring(l, o.events, merged) }
                    data[l.id] = LeagueData(merged, System.currentTimeMillis())
                    if (all) runCatching { File(dir, "sb_${safe(l.id)}.json").writeText("${System.currentTimeMillis()}\n" + raws.joinToString(SEP)) }
                }
            }
        } + fx.map { l ->
            async {
                gate.withPermit {
                    val slug = l.fixtureSlug!!
                    val file = File(dir, "fx_${safe(slug)}.json")
                    val raw = runCatching { feeds.fixturesRaw(slug) }.onSuccess { runCatching { file.writeText(it) } }.getOrNull()
                        ?: runCatching { file.readText() }.getOrNull() ?: return@withPermit
                    fixtures[l.id] = LeagueData(runCatching { SportsFeeds.parseFixtures(l, raw) }.getOrDefault(emptyList()), System.currentTimeMillis())
                }
            }
        }).awaitAll()
        _status.value = if (failed > 0 && failed >= due.size && data.isEmpty()) "Couldn't reach the scores service." else ""
        publish()
        true
    }

    private fun loadCache() {
        val leagues = activeLeagues()
        for (l in leagues) {
            val f = File(dir, "sb_${safe(l.id)}.json").takeIf { it.exists() } ?: continue
            runCatching {
                val text = f.readText(); val nl = text.indexOf('\n')
                // Kept stale (at = 0) so the loop refreshes it straight away; the page shows it meanwhile.
                data[l.id] = LeagueData(text.substring(nl + 1).split(SEP).flatMap { SportsFeeds.parseScoreboard(l, it) }.distinctBy { it.id }, 0L)
            }
        }
        publish()
    }

    /** Games found in the TV guide only (darts, snooker, wrestling, leagues ESPN doesn't carry): today and tomorrow. */
    private fun guideEvents(now: Long): List<SportsEvent> {
        if (!Iptv.hasChannels()) return emptyList()
        val keys = Iptv.liveChannels().associateBy { it.key }
        val leagues = activeLeagues()
        return (0..1).flatMap { off ->
            SportsGuideEvents.merge(matcher.guideEvents(dayStart(off), dayStart(off + 1), leagues, follows.words, keys, 0L, now), emptySet())
        }
    }

    /** ESPN first; the guide and the fixture lists only for what ESPN doesn't already show (Vision's rule). */
    private fun publish() {
        val espn = (data.values.flatMap { it.events } + dayData.values.flatMap { it.events } + ahead.values.flatMap { it.events })
            .groupBy { it.id }.values.map { v -> v.first() }
        val g = guide
        val byLeague = g.groupBy { it.leagueId }
        val fx = fixtures.values.flatMap { it.events }.filter { f ->
            val same = byLeague[f.leagueId].orEmpty()
            if (f.hasTime) same.none { kotlin.math.abs(it.startMs - f.startMs) <= 3 * 60 * MINUTE } else same.none { dayOf(it.startMs) == dayOf(f.startMs) }
        }
        val index = SportsFixtureIndex(espn)
        fun covered(ev: SportsEvent): Boolean {
            val league = SportsCatalog.league(ev.leagueId) ?: return false
            if (league.espnPath != null) return false
            return index.anyNear(ev) { c -> SportsCatalog.league(c.leagueId)?.let { o -> league.sport == o.sport && SportsEventIdentity.guideCoversFixture(ev, c, o) } == true }
        }
        val s = settings
        _events.value = (espn + (fx + g).filterNot(::covered)).distinctBy { it.id }
            .filter { SportsTaxonomy.acceptsEvent(SportsCatalog.league(it.leagueId), it.title, it.subtitle, s.excludedSportsNodes) }
    }

    private fun dayOf(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }.let { it.get(Calendar.YEAR) * 400 + it.get(Calendar.DAY_OF_YEAR) }

    private fun match(now: Long) {
        if (!Iptv.hasChannels()) { _matches.value = emptyMap(); return }
        _matching.value = _matches.value.isEmpty()
        try {
            val channels = Iptv.liveChannels()
            val list = _events.value.filter { it.startMs < dayStart(2) && (endEstimate(it) > dayStart(-1)) }
            val result = matcher.match(SportsMatcher.Input(list, channels, emptyMap(), 0L, settings, now))
            // Games matched one at a time (a game page further out in the week) stay matched.
            val keep = _matches.value.filterKeys { id -> id !in result.matches && list.none { it.id == id } && _events.value.any { it.id == id } }
            _matches.value = keep + result.matches
            runCatching { SportsRepository.rememberUsual(result.matches, list) }
        } finally { _matching.value = false }
    }

    private val _searching = MutableStateFlow<String?>(null)
    /** The game "Search now" is looking for (its id), or null. */
    val searching: StateFlow<String?> get() = _searching

    /**
     * One game's channels, straight away (its game page, or "Search now"): the same matcher and settings, for this
     * game alone. Channels found before stay when this finds none. Returns how many channels show it.
     */
    suspend fun matchOne(ev: SportsEvent): Int = kotlinx.coroutines.withContext(Dispatchers.Default) {
        if (!Iptv.hasChannels()) return@withContext 0
        if (_searching.value == ev.id) return@withContext _matches.value[ev.id].orEmpty().size
        _searching.value = ev.id
        try {
            val now = System.currentTimeMillis()
            val one = _events.value.firstOrNull { it.id == ev.id } ?: ev
            val found = matcher.match(SportsMatcher.Input(listOf(one), Iptv.liveChannels(), emptyMap(), 0L, settings, now,
                replay = one.startMs < now - 5 * 3600_000L)).matches[ev.id].orEmpty()
            if (found.isNotEmpty()) _matches.value = _matches.value + (ev.id to found)
            (if (found.isNotEmpty()) found else _matches.value[ev.id].orEmpty()).size
        } finally { _searching.value = null }
    }

    /** Shows the channel straight away as this game's pick (the next round keeps it, see SportsSettings.eventChannels). */
    /** Re-orders one game's channels (Gemini's verdicts). */
    fun rescore(id: String, f: (List<ChannelMatch>) -> List<ChannelMatch>) {
        val cur = _matches.value[id] ?: return
        _matches.value = _matches.value + (id to f(cur))
    }

    fun putFirst(ev: SportsEvent, m: ChannelMatch) {
        _matches.value = _matches.value + (ev.id to (listOf(m) + _matches.value[ev.id].orEmpty().filter { it.channel.key != m.channel.key }))
    }

    fun dropChannel(ev: SportsEvent, key: String) {
        _matches.value[ev.id]?.let { l -> _matches.value = _matches.value + (ev.id to l.filter { it.channel.key != key }) }
    }

    // ---- the rest of the week (Vision's week and past days), only for a day the page shows ----

    /** The page shows day [offset] (0 = today): its games are fetched (yesterday..tomorrow always are). */
    fun wantDay(offset: Int) {
        wantedDay = offset; wantedAt = System.currentTimeMillis()
        if (offset !in -1..1) refreshNow()
    }

    private fun eastern(ms: Long): String = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }.format(java.util.Date(ms))

    /** One local day spans two US Eastern dates; each is asked once per league (a few hours for days to come). */
    private suspend fun updateDay(now: Long): Boolean = coroutineScope {
        val off = wantedDay ?: return@coroutineScope false
        if (off in -1..1 || now - wantedAt > 30 * MINUTE) return@coroutineScope false
        val dates = listOf(eastern(dayStart(off) + 60_000L), eastern(dayStart(off + 1) - 60_000L)).distinct()
        val fresh = if (off < 0) 12 * 60 * MINUTE else 3 * 60 * MINUTE
        val due = activeLeagues().filter { it.espnPath != null }.flatMap { l -> dates.map { l to it } }
            .filter { (l, d) -> now - (dayData["${l.id}|$d"]?.at ?: 0L) > fresh }
        if (due.isEmpty()) return@coroutineScope false
        val gate = Semaphore(6)
        due.map { (l, d) -> async { gate.withPermit {
            val raw = runCatching { feeds.scoreboardDayRaw(l, d) }.getOrNull()
            val at = System.currentTimeMillis()
            dayData["${l.id}|$d"] = if (raw == null) LeagueData(dayData["${l.id}|$d"]?.events.orEmpty(), at - fresh + 30 * MINUTE)
                else LeagueData(runCatching { SportsFeeds.parseScoreboard(l, raw) }.getOrDefault(emptyList()), at)
        } } }.awaitAll()
        // Only the last few days looked at are kept.
        if (dayData.size > 400) dayData.entries.sortedBy { it.value.at }.take(dayData.size - 300).forEach { dayData.remove(it.key) }
        true
    }

    /** Followed leagues and top-bar leagues: their months to five weeks ahead (ESPN takes a month, not a range), every 6 hours. */
    private suspend fun updateAhead(now: Long): Boolean {
        val f = follows
        val ids = (f.pins + f.leagues).toSet()
        ahead.keys.filter { it !in ids }.forEach { ahead.remove(it) }
        val due = ids.mapNotNull { SportsCatalog.league(it) }.filter { it.espnPath != null && now - (ahead[it.id]?.at ?: 0L) > 6 * 60 * MINUTE }
        if (due.isEmpty()) return false
        val fmt = SimpleDateFormat("yyyyMM", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }
        val months = listOf(now + (DAYS_AHEAD + 1) * DAY, now + 35 * DAY).map { fmt.format(java.util.Date(it)) }.distinct()
        val from = dayStart(2); val to = now + 36 * DAY
        for (l in due) {
            val raws = months.mapNotNull { m -> runCatching { feeds.scoreboardDayRaw(l, m) }.getOrNull() }
            val at = System.currentTimeMillis()
            ahead[l.id] = if (raws.isEmpty()) LeagueData(ahead[l.id]?.events.orEmpty(), at - 5 * 60 * MINUTE)
                else LeagueData(raws.flatMap { r -> runCatching { SportsFeeds.parseScoreboard(l, r) }.getOrDefault(emptyList()) }
                    .filter { it.startMs in from until to }.distinctBy { it.id }, at)
        }
        return true
    }

    /** Everything known: the page's days, your teams' seasons and teams looked up (each game once, the fresher copy). */
    val known: StateFlow<List<SportsEvent>> by lazy {
        combine(_events, _schedules, _lookedUp) { ev, sch, lu ->
            val seen = HashSet<String>(ev.size * 2)
            ev.forEach { seen += it.espnId?.let { e -> "e:$e" } ?: it.id }
            ev + (sch.values.flatten() + lu.values.flatMap { it.events }).filter { seen.add(it.espnId?.let { e -> "e:$e" } ?: it.id) }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    }

    /** A team's games for its card (results and games to come); another team's season is fetched and kept an hour. */
    suspend fun teamGames(team: TeamInfo): List<SportsEvent> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val fam = SportsCatalog.teamFamily(team.leagueId)
        fun mine(ev: SportsEvent) = ev.sides.any { it.team.id == team.id && SportsCatalog.teamFamily(it.team.leagueId) == fam }
        val now = System.currentTimeMillis()
        val league = SportsCatalog.league(team.leagueId)
        val followed = follows.teams.any { it.leagueId == team.leagueId && it.teamId == team.id }
        val cur = _lookedUp.value[team.key]
        val path = league?.espnPath
        if (!followed && league != null && path != null && (cur == null || now - cur.at > 60 * MINUTE)) {
            val raws = ArrayList<Pair<League, String>>()
            runCatching { feeds.teamScheduleRaw(path, team.id, fixtures = false) }.getOrNull()?.let { raws += league to it }
            if (path.startsWith("soccer/") || league.national) runCatching { feeds.teamScheduleRaw(path, team.id, fixtures = true) }.getOrNull()?.let { raws += league to it }
            if (raws.isNotEmpty()) _lookedUp.value = (_lookedUp.value + (team.key to LeagueData(merge(raws), now)))
                .entries.sortedByDescending { it.value.at }.take(6).associate { it.key to it.value }
        }
        known.value.filter(::mine).distinctBy { it.espnId ?: it.id }.sortedBy { it.startMs }
    }

    // ---- league tables (Vision's), asked only when a table or a game's place is shown; an hour in memory ----

    private val _standings = MutableStateFlow<Map<String, Standings>>(emptyMap())
    val standings: StateFlow<Map<String, Standings>> get() = _standings
    private val noTable = ConcurrentHashMap<String, Long>()
    private val tableLocks = ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    private val RACING_TABLES = setOf("racing/f1", "racing/nascar-premier", "racing/nascar-secondary", "racing/nascar-truck", "racing/irl")
    private val EUROPEAN_TABLES = setOf("soccer/uefa.champions", "soccer/uefa.europa", "soccer/uefa.europa.conf")

    fun mayHaveTable(league: League): Boolean {
        if (noTable.containsKey(league.id) && System.currentTimeMillis() - (noTable[league.id] ?: 0L) < 12 * 60 * MINUTE) return false
        if (league.guideOnly || league.id.startsWith("tsdb/") || league.espnPath == null) return false
        if (league.kind == LeagueKind.EVENT) return league.sport == Sport.RACING && league.id in RACING_TABLES
        return league.sport !in setOf(Sport.GOLF, Sport.TENNIS, Sport.FIGHTING, Sport.WRESTLING, Sport.DARTS, Sport.SNOOKER)
    }

    fun tableLeagues(f: SportsFollows = follows): List<League> {
        val ids = LinkedHashSet<String>()
        for (key in SportsRows.rowKeys(f)) when {
            key.startsWith("t:") -> f.teams.firstOrNull { "t:${it.key}" == key }?.let { t ->
                ids += t.leagueId
                SportsCatalog.relatedLeagues(t.leagueId).filter { it in EUROPEAN_TABLES }.forEach { ids += it }
            }
            key.startsWith("l:") -> ids += key.removePrefix("l:")
        }
        return ids.mapNotNull { SportsCatalog.league(it) }.filter { mayHaveTable(it) }
    }

    suspend fun standings(leagueId: String, force: Boolean = false): Standings? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val league = SportsCatalog.league(leagueId) ?: return@withContext null
        val lock = tableLocks.getOrPut(leagueId) { kotlinx.coroutines.sync.Mutex() }
        lock.lock()
        try {
            val now = System.currentTimeMillis()
            _standings.value[leagueId]?.let { if (!force && now - it.fetchedAt < 60 * MINUTE) return@withContext it }
            if (!mayHaveTable(league)) return@withContext null
            val file = File(dir, "table_${safe(leagueId)}.json")
            val url = Standings.espnUrl(league) ?: return@withContext null
            val raw = runCatching { feeds.standingsRaw(url) }.getOrElse {
                val old = runCatching { file.takeIf { f -> f.exists() }?.readText() }.getOrNull()
                val at = old?.substringBefore('\n')?.toLongOrNull() ?: return@withContext _standings.value[leagueId]
                return@withContext (runCatching { Standings.parseEspn(league, old.substringAfter('\n'), at) }.getOrNull() ?: _standings.value[leagueId])
                    ?.also { st -> _standings.value = _standings.value + (leagueId to st) }
            }
            var table = runCatching { Standings.parseEspn(league, raw, now) }.getOrNull()?.takeIf { st -> st.groups.any { it.rows.isNotEmpty() } }
            if (table == null) { noTable[leagueId] = now; return@withContext null }
            runCatching { file.writeText("$now\n$raw") }
            // Form (the last five): from the last six weeks' results (one request).
            if (table.kind != StandingsKind.RACING && table.source == "ESPN") {
                val recent = known.value.filter { it.leagueId == leagueId }
                val past = runCatching {
                    SportsFeeds.parseScoreboard(league, feeds.scoreboardRaw(league, eastern(now - 42 * DAY), eastern(now)))
                }.getOrDefault(emptyList())
                table = Standings.withForm(table, Standings.formFrom((past + recent).distinctBy { it.espnId ?: it.id }, leagueId))
            }
            if (table.kind != StandingsKind.RACING) {
                val listed = runCatching { SportsMine.teams(league) }.getOrDefault(emptyList()).associateBy { it.id }
                val fam = SportsCatalog.teamFamily(leagueId)
                val seen = HashMap<String, TeamInfo>()
                if (listed.size < table.groups.sumOf { it.rows.size }) known.value.forEach { ev ->
                    ev.sides.forEach { sd -> if (sd.team.color != null && SportsCatalog.teamFamily(sd.team.leagueId) == fam) seen.putIfAbsent(sd.team.id, sd.team) }
                }
                table = table.copy(groups = table.groups.map { g -> g.copy(rows = g.rows.map { r ->
                    val t = listed[r.id]?.takeIf { it.color != null || it.altColor != null } ?: seen[r.id]
                    if (t == null) r else r.copy(color = t.color, altColor = t.altColor, logo = r.logo ?: t.logo)
                }) })
            }
            val mem = File(dir, "table_moves_${safe(leagueId)}.json")
            val (moved, save) = Standings.withChanges(table, runCatching { mem.takeIf { it.exists() }?.readText() }.getOrNull())
            runCatching { mem.writeText(save) }
            _standings.value = _standings.value + (leagueId to moved)
            moved
        } finally { lock.unlock() }
    }

    /** The tables My Sports' row headers need ("2nd in Premier League"): your teams' own leagues. */
    suspend fun loadTeamTables() {
        for (id in follows.teams.map { it.leagueId }.distinct()) {
            val l = SportsCatalog.league(id) ?: continue
            if (mayHaveTable(l)) runCatching { standings(id) }
        }
    }

    /** A table row's team as the app knows it (colours and logo from the league's team list). */
    fun teamFor(leagueId: String, row: StandingsRow): TeamInfo {
        val league = SportsCatalog.league(leagueId)
        val info = league?.let { SportsMine.cachedTeams(it).firstOrNull { t -> t.id == row.id } }
            ?: known.value.asSequence().flatMap { it.sides.asSequence() }.map { it.team }
                .firstOrNull { it.id == row.id && SportsCatalog.teamFamily(it.leagueId) == SportsCatalog.teamFamily(leagueId) }
        return info?.copy(leagueId = leagueId, logo = info.logo ?: row.logo)
            ?: TeamInfo(leagueId, row.id, row.shortName, row.name, row.abbr, null, null, null, row.logo)
    }

    // ---- what the screens show ----

    /** A day's games ([offset] 0 = today), as the Sports page lists them. */
    fun day(offset: Int, now: Long = System.currentTimeMillis()): List<SportsEvent> {
        val s = settings; val f = follows
        val m = _matches.value
        val active = activeLeagues(s, f).map { it.id }.toSet() + SportsMatcher.OTHER_ID
        val filter = s.onlyOnMyChannels && Iptv.hasChannels() && m.isNotEmpty()
        val rank = SportsCatalog.ordered(s).mapIndexed { i, l -> l.id to i }.toMap()
        return SportsLiveNow.dayEvents(_events.value, m, active, filter, f, dayStart(offset), dayStart(offset + 1), now)
            .sortedWith(compareBy<SportsEvent> { if (it.state == GameState.LIVE) 0 else if (it.state == GameState.PRE) 1 else 2 }
                .thenByDescending { isMine(it, f) }.thenByDescending { m[it.id]?.isNotEmpty() == true }
                .thenBy { rank[it.leagueId] ?: 999 }.thenBy { it.startMs })
    }

    /** Live now: really under way, yours first, then on a channel (the clock's list, as in Vision). */
    fun liveNow(now: Long = System.currentTimeMillis()): List<SportsEvent> {
        val f = follows; val m = _matches.value
        val today = day(0, now)
        val week = SportsLiveNow.weekLong(today, m, now).mapTo(HashSet()) { it.id }
        val rank = SportsCatalog.ordered(settings).mapIndexed { i, l -> l.id to i }.toMap()
        return SportsLiveNow.live(today, week, m, f) { rank[it.leagueId] ?: 999 }.filter { SportsLiveNow.reallyLive(it, m, now) }
    }

    fun channelsFor(ev: SportsEvent): List<ChannelMatch> = _matches.value[ev.id].orEmpty()

    /** The freshest copy of a game (scores move on while a page shows it); team seasons included. */
    fun event(id: String): SportsEvent? = _events.value.firstOrNull { it.id == id }
        ?: _schedules.value.values.firstNotNullOfOrNull { l -> l.firstOrNull { it.id == id } }

    // ---- scoring alerts (Vision's noticeScoreIncreases; the card waits so it never shows before the stream does) ----

    private val _alerts = MutableStateFlow<SportsAlert?>(null)
    val alerts: StateFlow<SportsAlert?> get() = _alerts
    fun clearAlert() { _alerts.value = null }

    private fun noticeScoring(league: League, before: List<SportsEvent>, after: List<SportsEvent>) {
        val s = settings
        if (!s.scoringAlerts) return
        val wait = s.alertDelaySec.coerceIn(0, 300) * 1000L
        noticeScoreIncreases(league, before, after, follows) { a -> scope.launch { delay(wait); _alerts.value = a } }
    }

    // ---- a game's page: ESPN's summary (stats, form, meetings, line-ups, table, win chance) ----

    private class Summary(val at: Long, val sig: String, val extras: GameExtras, val plays: List<ScoringPlay>)
    private val summaries = ConcurrentHashMap<String, Summary>()

    /** Asked again after 25 s while the game is on (straight away when the score changes), else after 30 minutes. */
    suspend fun extras(ev: SportsEvent): Pair<GameExtras, List<ScoringPlay>>? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val league = SportsCatalog.league(ev.leagueId) ?: return@withContext null
        val id = ev.espnId ?: return@withContext null
        if (league.espnPath == null) return@withContext null
        val sig = "${ev.away?.score}-${ev.home?.score}-${ev.state}"
        val c = summaries[ev.id]
        if (c != null && c.sig == sig && System.currentTimeMillis() - c.at < (if (ev.state == GameState.LIVE) 25_000 else 30 * MINUTE)) return@withContext c.extras to c.plays
        val root = runCatching { feeds.summaryJson(league, id) }.getOrNull() ?: return@withContext c?.let { it.extras to it.plays }
        val plays = if (ev.state == GameState.PRE) emptyList() else runCatching { SportsFeeds.parseSummary(root) }.getOrDefault(emptyList())
        val ex = runCatching { SportsExtras.parse(root, ev, league.sport) }.getOrDefault(GameExtras())
        summaries[ev.id] = Summary(System.currentTimeMillis(), sig, ex, plays)
        ex to plays
    }

    // ---- your teams' whole seasons (My Teams rows), every 6 hours, only while My Teams is open ----

    private val _schedules = MutableStateFlow<Map<String, List<SportsEvent>>>(emptyMap())
    val schedules: StateFlow<Map<String, List<SportsEvent>>> get() = _schedules
    private val scheduleAt = ConcurrentHashMap<String, Long>()

    suspend fun loadSchedules(force: Boolean = false) = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val f = follows
        val keys = f.teams.map { it.key }.toSet()
        if (_schedules.value.keys.any { it !in keys }) _schedules.value = _schedules.value.filterKeys { it in keys }
        for (t in f.teams) {
            val l = SportsCatalog.league(t.leagueId) ?: continue
            val path = l.espnPath ?: continue
            val file = File(dir, "ts_${safe(t.key)}.json")
            val now = System.currentTimeMillis()
            if (_schedules.value[t.key] == null && file.exists()) runCatching {
                val text = file.readText(); val nl = text.indexOf('\n')
                scheduleAt[t.key] = text.substring(0, nl).toLong()
                _schedules.value = _schedules.value + (t.key to merge(text.substring(nl + 1).split(SEP).mapNotNull { part ->
                    val tab = part.indexOf('\t'); SportsCatalog.league(part.substring(0, tab))?.let { it to part.substring(tab + 1) } }))
            }
            if (now - (scheduleAt[t.key] ?: 0L) < (if (force) 3600_000L else 6 * 3600_000L)) continue
            val raws = ArrayList<Pair<League, String>>()
            runCatching { feeds.teamScheduleRaw(path, t.teamId, fixtures = false) }.getOrNull()?.let { raws += l to it }
            if (path.startsWith("soccer/") || l.national) {
                runCatching { feeds.teamScheduleRaw(path, t.teamId, fixtures = true) }.getOrNull()?.let { raws += l to it }
                for (rid in SportsCatalog.relatedLeagues(l.id)) {
                    val rl = SportsCatalog.league(rid) ?: continue
                    val rp = rl.espnPath ?: continue
                    runCatching { feeds.teamScheduleRaw(rp, t.teamId, fixtures = true) }.getOrNull()?.let { raws += rl to it }
                }
            }
            if (raws.isEmpty()) { scheduleAt[t.key] = now - 5 * 3600_000L; continue }
            scheduleAt[t.key] = now
            _schedules.value = _schedules.value + (t.key to merge(raws))
            runCatching { file.writeText("$now\n" + raws.joinToString(SEP) { (rl, raw) -> rl.id + "\t" + raw }) }
        }
    }

    /** One list from a team's schedule answers: each game once, under the first competition it came with. */
    private fun merge(raws: List<Pair<League, String>>): List<SportsEvent> {
        val byId = LinkedHashMap<String, SportsEvent>()
        for ((rl, raw) in raws) runCatching { SportsFeeds.parseSchedule(rl, raw) }.getOrDefault(emptyList()).forEach { byId.putIfAbsent(it.espnId ?: it.id, it) }
        // Today's scoreboard is fresher than a season list (live scores).
        val live = _events.value.associateBy { it.espnId ?: it.id }
        return byId.values.map { live[it.espnId ?: it.id] ?: it }.sortedBy { it.startMs }
    }

    /** My Teams as rows (Vision's SportsRows): today's games plus your teams' seasons. */
    internal fun myTeams(now: Long = System.currentTimeMillis()): MyTeamsPage {
        val f = follows
        val known = (_events.value + _schedules.value.values.flatten()).distinctBy { it.espnId ?: it.id }
        return SportsRows.build(known, f, now) { isMine(it, f) }
    }

    // ---- reminders (a game you picked, or every game of your teams when "Remind me of my teams' games" is on) ----

    fun hasReminder(ev: SportsEvent) = follows.reminders.any { it.eventId == ev.id }
    fun toggleReminder(ev: SportsEvent) {
        val f = follows
        SportsMine.save(f.copy(reminders = if (hasReminder(ev)) f.reminders.filterNot { it.eventId == ev.id }
            else f.reminders + SportsReminder(ev.id, ev.title, ev.startMs)))
    }
    fun dropReminder(id: String) { val f = follows; if (f.reminders.any { it.eventId == id }) SportsMine.save(f.copy(reminders = f.reminders.filterNot { it.eventId == id })) }

    /** True when something needs Sports running in the background (a reminder, a pinned score, your teams' alerts). */
    fun needsBackground(): Boolean {
        val f = follows; val s = settings
        return f.reminders.any { it.startMs > System.currentTimeMillis() - 3 * 3600_000L } || SportsPins.list.value.isNotEmpty() ||
            (!f.isEmpty && (s.scoringAlerts || s.remindMyTeams))
    }

    private fun safe(id: String) = id.replace(Regex("[^a-zA-Z0-9]+"), "_")
    private const val SEP = "\n\u001E\n"
}
