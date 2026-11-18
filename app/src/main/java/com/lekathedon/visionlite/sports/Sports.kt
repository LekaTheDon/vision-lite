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
    private val dir by lazy { File(App.instance.filesDir, "sports").apply { mkdirs() } }
    private val matcher by lazy { SportsMatcher(EpgDatabase(), File(dir, "guide").apply { mkdirs() }) }

    val settings: SportsSettings get() = SportsSettings.fromJson(prefs.sportsSettings.value.ifBlank { null })
    val follows: SportsFollows get() = SportsFollows.fromJson(prefs.sportsFollows.value.ifBlank { null })

    private class LeagueData(val events: List<SportsEvent>, val at: Long)
    private val data = ConcurrentHashMap<String, LeagueData>()
    private val fixtures = ConcurrentHashMap<String, LeagueData>()
    @Volatile private var guide: List<SportsEvent> = emptyList()

    private val _events = MutableStateFlow<List<SportsEvent>>(emptyList())
    val events: StateFlow<List<SportsEvent>> get() = _events
    private val _matches = MutableStateFlow<Map<String, List<ChannelMatch>>>(emptyMap())
    /** Event id → the channels showing it, best first. */
    val matches: StateFlow<Map<String, List<ChannelMatch>>> get() = _matches
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> get() = _status

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
    fun refreshNow() { force = true; if (viewers > 0 && loop?.isActive != true) loop = scope.launch { run() } }

    private suspend fun run() {
        if (data.isEmpty()) loadCache()
        // Live TV's channels and guide (only now that sports is open; loads at most daily).
        scope.launch { runCatching { Iptv.ensureLoaded() } }
        var matchedAt = 0L; var matchedGuide: String? = null; var matchedEvents = 0
        while (currentCoroutineContextActive()) {
            val now = System.currentTimeMillis()
            val changed = runCatching { update(now) }.onFailure { Log.w(TAG, "update: ${it.message}") }.getOrDefault(false)
            val rev = runCatching { Iptv.guideRevision() }.getOrNull()
            if (rev != matchedGuide) { guide = runCatching { guideEvents(now) }.getOrDefault(guide); publish() }
            val evs = _events.value
            if (changed || rev != matchedGuide || evs.size != matchedEvents || now - matchedAt > 2 * MINUTE) {
                runCatching { match(now) }.onFailure { Log.w(TAG, "match: ${it.message}") }
                matchedAt = now; matchedGuide = rev; matchedEvents = evs.size
            }
            delay(15_000)
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
    private suspend fun update(now: Long): Boolean = coroutineScope {
        val s = settings
        val forced = force.also { force = false }
        val leagues = activeLeagues(s)
        val due = leagues.filter { l ->
            if (l.espnPath == null) return@filter false
            val d = data[l.id]
            val age = now - (d?.at ?: 0L)
            val live = d?.events?.any { it.state == GameState.LIVE || (it.state == GameState.PRE && it.startMs in (now - 15 * MINUTE)..(now + 10 * MINUTE)) } == true
            when { forced -> age > 15_000; live -> age > 30_000; else -> age > 5 * MINUTE }
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
                    val all = old == null || now - old.at > 5 * MINUTE
                    val raws = espnDays(now, all).mapNotNull { d -> runCatching { feeds.scoreboardDayRaw(l, d) }.getOrNull() }
                    if (raws.isEmpty()) { failed++; return@withPermit }
                    val parsed = raws.flatMap { raw -> runCatching { SportsFeeds.parseScoreboard(l, raw) }.getOrDefault(emptyList()) }
                    val merged = if (all) parsed.distinctBy { it.id }
                        else (parsed + old!!.events).distinctBy { it.id }
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
        val espn = data.values.flatMap { it.events }
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
        val channels = Iptv.liveChannels()
        val list = _events.value.filter { it.startMs < dayStart(2) && (endEstimate(it) > dayStart(-1)) }
        val result = matcher.match(SportsMatcher.Input(list, channels, emptyMap(), 0L, settings, now))
        _matches.value = result.matches
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

    private fun safe(id: String) = id.replace(Regex("[^a-zA-Z0-9]+"), "_")
    private const val SEP = "\n\u001E\n"
}
