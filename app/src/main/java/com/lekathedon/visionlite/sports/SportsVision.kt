// What Vision's Sports screens (copied as is) ask of their view model and repository, answered by Lite's own Sports
// engine: same names, so the screens stay Vision's. Nothing here runs until the Sports page is open.
package com.lekathedon.visionlite.sports

import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.iptv.EpgProgram
import com.lekathedon.visionlite.iptv.Iptv
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

/** Vision's pinned scores, on Lite's [SportsPins] (always on). */
object SportsPinnedScores {
    const val MAX = SportsPins.MAX
    val ids: StateFlow<List<String>> get() = SportsPins.list
    val enabled: StateFlow<Boolean> = MutableStateFlow(true)
    fun isPinned(id: String) = SportsPins.isPinned(id)
    fun toggle(id: String): Boolean = SportsPins.toggle(id)
    fun drop(id: String) = SportsPins.drop(id)
}

/** Vision's SportsViewModel: the page's flows and the video window, over [Sports]. */
object SportsViewModel {
    val repo get() = SportsRepository
    val gemini get() = SportsGemini
    /** Set by the Sports page (a few of Vision's pop-ups open a Lite page instead). */
    @Volatile var nav: Nav? = null

    val settings: StateFlow<SportsSettings> get() = Sports.settingsFlow
    val follows: StateFlow<SportsFollows> get() = Sports.followsFlow
    val events: StateFlow<List<SportsEvent>> get() = Sports.events
    val known: StateFlow<List<SportsEvent>> get() = Sports.known
    val matches: StateFlow<Map<String, List<ChannelMatch>>> get() = Sports.matches
    val loading: StateFlow<Boolean> get() = Sports.loading
    val matching: StateFlow<Boolean> get() = Sports.matching
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> get() = _problem
    val use24h: StateFlow<Boolean> = MutableStateFlow(true)
    val backdrop: StateFlow<String> = MutableStateFlow("auto")
    private val _hasChannels = MutableStateFlow(true)
    val hasChannels: StateFlow<Boolean> get() = _hasChannels
    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> get() = _now
    /** Vision's record early / late (Lite's recorder starts 5 minutes early itself). */
    val recordPadding: StateFlow<Pair<Int, Int>> = MutableStateFlow(0 to 5)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ticking = false
    internal fun startTicking() {
        if (ticking) return
        ticking = true
        scope.launch {
            while (SportsRepository.viewers > 0) {
                _now.value = System.currentTimeMillis()
                _hasChannels.value = runCatching { Iptv.hasChannels() }.getOrDefault(true)
                _problem.value = Sports.status.value.takeIf { it.startsWith("Couldn't") }
                delay(15_000)
            }
            ticking = false
        }
    }

    // ---- the video window: Lite's small player, one channel at a time

    private val _playing = MutableStateFlow<ChannelMatch?>(null)
    /** The channel playing in the window. */
    val playingChannel: StateFlow<ChannelMatch?> get() = _playing
    private val _playingKey = MutableStateFlow<String?>(null)
    val playingKey: StateFlow<String?> get() = _playingKey
    val playingNow: StateFlow<Boolean> get() = _playingNowFlow
    private val _playingNowFlow = MutableStateFlow(false)

    fun stopWindow() { _playing.value = null; _playingKey.value = null; _playingNowFlow.value = false }

    private fun asMatch(ch: LiveChannel) = ChannelMatch(ch, 150, "League network", null, null, false)

    fun playHere(m: ChannelMatch) {
        if (_playingKey.value == m.channel.key) return
        armFailover(m)
        _playing.value = m; _playingKey.value = m.channel.key; _playingNowFlow.value = true
        Iptv.channelsByKey(listOf(m.channel.key)).firstOrNull()?.let { Iptv.played(it) }
    }

    fun playChannelHere(ch: LiveChannel) = playHere(asMatch(ch))

    /** The game this channel was picked for and its other channels, for SportsFailover. */
    private fun armFailover(m: ChannelMatch) {
        val all = Sports.matches.value
        val evs = Sports.events.value.filter { ev -> all[ev.id].orEmpty().any { it.channel.key == m.channel.key } }
        val ev = evs.firstOrNull { it.state == GameState.LIVE } ?: evs.firstOrNull() ?: return
        SportsFailover.arm(ev.title, all[ev.id].orEmpty(), m.channel)
    }

    /** Full screen: Lite's player, with this game's channels as the Up / Down list. */
    fun watch(m: ChannelMatch) {
        val n = nav ?: return
        val all = Sports.matches.value
        val ev = Sports.events.value.firstOrNull { e -> all[e.id].orEmpty().any { it.channel.key == m.channel.key } }
        stopWindow()
        if (ev != null) watchGame(n, ev, m, all[ev.id].orEmpty())
        else {
            Iptv.channelsByKey(listOf(m.channel.key)).firstOrNull()?.let { Iptv.zapList = listOf(it); Iptv.played(it) }
            n.push(Screen.Player(m.channel.url, m.channel.name, m.channel.headers, type = "live"))
        }
    }

    fun watchChannel(ch: LiveChannel) = watch(asMatch(ch))

    /** From the start (catch-up), when the channel can replay it; false otherwise. */
    fun watchFromStart(ev: SportsEvent, m: ChannelMatch): Boolean {
        val n = nav ?: return false
        if (!m.catchup) return false
        val c = Iptv.channelsByKey(listOf(m.channel.key)).firstOrNull() ?: return false
        val league = SportsCatalog.league(ev.leagueId)
        val start = minOf(m.program?.takeIf { it.startMs <= ev.startMs + 30 * 60_000L }?.startMs ?: ev.startMs, ev.startMs - 60_000L)
        val stop = ev.endMs ?: (ev.startMs + (league?.minutes ?: 180) * 60_000L)
        val url = Iptv.catchupUrl(c, EpgProgram(start, stop, ev.title, null, null)) ?: return false
        stopWindow()
        n.push(Screen.Player(url, ev.title, c.headers, type = "live"))
        return true
    }

    /** Pick Your Teams opens by itself the first time (nothing followed, never set up). */
    suspend fun isFirstVisit(): Boolean = Sports.follows.let { !it.setUp && it.isEmpty }

    /** Records [ev] on [m]'s channel from 5 minutes before the start for [minutes] (plus the late padding). */
    fun record(ev: SportsEvent, m: ChannelMatch, minutes: Int): String {
        val c = Iptv.channelsByKey(listOf(m.channel.key)).firstOrNull() ?: return "That channel isn't in your list any more"
        val start = (if (ev.hasTime) ev.startMs else System.currentTimeMillis()) - SportsRecordings.EARLY_MS
        val stop = maxOf(start, System.currentTimeMillis()) + (minutes + recordPadding.value.second) * 60_000L + SportsRecordings.EARLY_MS
        com.lekathedon.visionlite.iptv.Recorder.scheduleAt(c, gameName(ev), start, stop)
        return "Recording set · Recordings has it"
    }

    fun cancelRecording(ev: SportsEvent) {
        SportsRecordings.forEvent(ev)?.let { com.lekathedon.visionlite.iptv.Recorder.cancel(it.id) }
    }

    suspend fun teams(league: League): List<TeamInfo> = SportsMine.teams(league)
    suspend fun plays(ev: SportsEvent): List<ScoringPlay> = Sports.extras(ev)?.second.orEmpty()
    suspend fun extras(ev: SportsEvent): GameExtras? = Sports.extras(ev)?.first
}

/** Vision's SportsRepository calls, over [Sports] and [SportsMine]. */
object SportsRepository {
    const val DAYS_BACK = Sports.DAYS_BACK
    const val DAYS_AHEAD = Sports.DAYS_AHEAD
    @Volatile internal var viewers = 0
        private set

    val known: StateFlow<List<SportsEvent>> get() = Sports.known
    val standings get() = Sports.standings
    val searchingEvent: StateFlow<String?> get() = Sports.searching

    fun setViewing(on: Boolean) {
        synchronized(this) { viewers = (viewers + if (on) 1 else -1).coerceAtLeast(0) }
        if (on) { Sports.viewing(); SportsViewModel.startTicking(); refreshNetworks(); queueGeminiMatching() } else Sports.stopViewing()
    }

    fun dayStart(offset: Int): Long = Sports.dayStart(offset)
    fun activeLeagues(s: SportsSettings = Sports.settings, f: SportsFollows = Sports.follows): List<League> = Sports.activeLeagues(s, f)

    fun followedLeagueIds(f: SportsFollows = Sports.follows): Set<String> {
        val out = LinkedHashSet<String>()
        f.teams.forEach { t -> out += t.leagueId; out += SportsCatalog.relatedLeagues(t.leagueId) }
        out += f.leagues; out += f.pins
        return out
    }

    /** Days before yesterday (and after tomorrow) are fetched when shown; see [Sports.wantDay]. */
    fun wantPast() { }
    fun wantDay(offset: Int) = Sports.wantDay(offset)
    fun focusEvent(@Suppress("UNUSED_PARAMETER") id: String?) { }

    // ---- your choices (saved like Lite's other Sports settings)

    fun updateSettings(transform: (SportsSettings) -> SportsSettings) = SportsMine.saveSettings(transform(Sports.settings))
    fun updateFollows(transform: (SportsFollows) -> SportsFollows) = SportsMine.save(transform(Sports.follows))

    fun toggleTeam(team: TeamInfo) = updateFollows { f ->
        val had = f.followedTeam(team)
        if (had != null) f.copy(teams = f.teams - had)
        else f.copy(teams = f.teams + FollowedTeam(team.leagueId, team.id, team.name, team.abbr, team.color, team.logo), setUp = true)
    }

    fun moveRow(key: String, by: Int) = updateFollows { f ->
        val keys = SportsRows.rowKeys(f).toMutableList()
        val i = keys.indexOf(key); val j = i + by
        if (i < 0 || j !in keys.indices) f else { keys.add(j, keys.removeAt(i)); f.copy(order = keys) }
    }

    fun togglePin(leagueId: String) = updateFollows { f ->
        if (leagueId in f.pins) f.copy(pins = f.pins - leagueId, unpinned = f.unpinned + leagueId)
        else f.copy(pins = f.pins + leagueId, unpinned = f.unpinned - leagueId, setUp = true)
    }

    fun toggleLeague(leagueId: String) = updateFollows { f ->
        f.copy(leagues = if (leagueId in f.leagues) f.leagues - leagueId else f.leagues + leagueId, setUp = true)
    }

    fun addWord(word: String) {
        val w = word.trim()
        if (w.length < 3) return
        updateFollows { f -> if (f.words.any { it.equals(w, true) }) f else f.copy(words = f.words + w, setUp = true) }
    }

    fun removeWord(word: String) = updateFollows { f -> f.copy(words = f.words.filterNot { it.equals(word, true) }) }
    fun markSetUp() { if (!Sports.follows.setUp) updateFollows { it.copy(setUp = true) } }
    fun isReminded(ev: SportsEvent): Boolean = Sports.hasReminder(ev)
    fun toggleReminder(ev: SportsEvent) = Sports.toggleReminder(ev)

    fun setAutoSwitch(ev: SportsEvent, on: Boolean) = updateFollows { f ->
        val has = f.reminders.any { it.eventId == ev.id }
        f.copy(reminders = if (has) f.reminders.map { if (it.eventId == ev.id) it.copy(autoSwitch = on) else it }
            else f.reminders + SportsReminder(ev.id, ev.title, ev.startMs, on))
    }

    fun switchesFor(ev: SportsEvent): Boolean = Sports.follows.reminders.firstOrNull { it.eventId == ev.id }?.autoSwitch ?: Sports.settings.autoSwitch

    /** "This is the right channel" for [ev]; with [forNetworks] also for its network's games from now on (or the team's, the league's). */
    fun pickChannel(ev: SportsEvent, channelKey: String, forNetworks: Boolean, forTeam: TeamInfo? = null, forLeague: Boolean = false) {
        updateSettings { s ->
            val keep = s.eventChannels.filterValues { v -> (v.substringAfterLast('|').toLongOrNull() ?: 0L) > System.currentTimeMillis() - 3 * 24 * 3600_000L }
            s.copy(
                eventChannels = keep + (ev.id to "$channelKey|${ev.startMs}"),
                networkChannels = if (forNetworks) s.networkChannels + ev.broadcasts.associate { SportsMatcher.networkKey(it) to channelKey } else s.networkChannels,
                teamChannels = if (forTeam != null) s.teamChannels + (teamChannelKey(ev.leagueId, forTeam.id) to channelKey) else s.teamChannels,
                leagueChannels = if (forLeague) s.leagueChannels + (ev.leagueId to channelKey) else s.leagueChannels,
                blocked = s.blocked - "${ev.id}|$channelKey"
            )
        }
        Iptv.liveChannels().firstOrNull { it.key == channelKey }?.let { ch ->
            Sports.putFirst(ev, ChannelMatch(ch, 200, "Your pick", null, SportsMatcher.nameQuality(ch.name), false))
        }
    }

    /** "Not this channel" for [ev] (and it stops being used for its network, team, league). */
    fun blockChannel(ev: SportsEvent, channelKey: String) {
        updateSettings { s ->
            s.copy(
                blocked = (s.blocked + "${ev.id}|$channelKey").toList().takeLast(400).toSet(),
                eventChannels = s.eventChannels.filterNot { (id, v) -> id == ev.id && v.substringBeforeLast('|') == channelKey },
                networkChannels = s.networkChannels.filterNot { (n, key) -> key == channelKey && ev.broadcasts.any { SportsMatcher.networkKey(it) == n } },
                teamChannels = s.teamChannels.filterNot { (t, key) -> key == channelKey && ev.sides.any { teamChannelKey(ev.leagueId, it.team.id) == t } },
                leagueChannels = s.leagueChannels.filterNot { (l, key) -> key == channelKey && l == ev.leagueId }
            )
        }
        Sports.dropChannel(ev, channelKey)
    }

    fun clearFixes() = updateSettings { it.copy(networkChannels = emptyMap(), eventChannels = emptyMap(), blocked = emptySet(), teamChannels = emptyMap(), leagueChannels = emptyMap()) }

    fun pickLeagueNetwork(networkId: String, channelKey: String) = updateSettings { it.copy(leagueNetworkChannels = it.leagueNetworkChannels + (networkId to channelKey)) }
    fun resetLeagueNetwork(networkId: String) = updateSettings { it.copy(leagueNetworkChannels = it.leagueNetworkChannels - networkId) }

    // ---- channels

    suspend fun loadEventChannels(event: SportsEvent) { Sports.matchOne(event); queueGeminiMatching() }
    suspend fun searchNow(ev: SportsEvent): Int = Sports.matchOne(ev)

    /** Every channel (Fix Channel's list). */
    fun channelsForPicker(): List<LiveChannel> = Iptv.liveChannels()

    /** What's on now and next on each channel, from the guide. */
    suspend fun nowAndNext(keys: Collection<String>): Map<String, Pair<EpgProgram?, EpgProgram?>> = withContext(Dispatchers.IO) {
        if (keys.isEmpty()) return@withContext emptyMap()
        val now = System.currentTimeMillis()
        val listings = runCatching { EpgDatabase().range(now, now + 24 * 3600_000L, channels = keys) }.getOrDefault(emptyMap())
        keys.associateWith { k ->
            val list = listings[k].orEmpty().sortedBy { it.startMs }
            list.firstOrNull { it.startMs <= now && it.stopMs > now } to list.firstOrNull { it.startMs > now }
        }
    }

    // ---- "usually on" (Zero TV idea): the channel a team's last game was surely on

    private val usualFile get() = File(App.instance.filesDir, "sports/usual_channels.json")
    @Volatile private var usual: MutableMap<String, String>? = null
    private fun usualMap(): MutableMap<String, String> = usual ?: java.util.concurrent.ConcurrentHashMap<String, String>().also { m ->
        runCatching { org.json.JSONObject(usualFile.readText()).let { o -> o.keys().forEach { k -> m[k] = o.getString(k) } } }
        usual = m
    }

    /** Remembers the sure channel of each team's game (called as matches come in). */
    internal fun rememberUsual(matches: Map<String, List<ChannelMatch>>, events: List<SportsEvent>) {
        val evs = events.associateBy { it.id }
        val m = usualMap()
        var changed = false
        matches.forEach { (id, list) ->
            val best = list.filter { it.sure }.maxByOrNull { it.score } ?: return@forEach
            evs[id]?.sides?.forEach { side ->
                val k = side.team.leagueId + "/" + side.team.id
                if (m[k] != best.channel.key) { m[k] = best.channel.key; changed = true }
            }
        }
        if (changed) runCatching { usualFile.writeText(org.json.JSONObject(m as Map<*, *>).toString()) }
    }

    fun usuallyOn(ev: SportsEvent): LiveChannel? {
        val m = usualMap()
        val key = ev.sides.firstNotNullOfOrNull { side -> m[side.team.leagueId + "/" + side.team.id] } ?: return null
        return Iptv.liveChannels().firstOrNull { it.key == key }
    }

    // ---- tables and teams

    fun mayHaveTable(league: League) = Sports.mayHaveTable(league)
    fun tableLeagues(f: SportsFollows = Sports.follows) = Sports.tableLeagues(f)
    suspend fun standings(leagueId: String, force: Boolean = false) = Sports.standings(leagueId, force)
    suspend fun loadTeamTables() = Sports.loadTeamTables()
    fun teamFor(leagueId: String, row: StandingsRow) = Sports.teamFor(leagueId, row)
    suspend fun teamGames(team: TeamInfo) = Sports.teamGames(team)
    suspend fun teams(league: League) = SportsMine.teams(league)

    // ---- TheSportsDB extras (TV coverage, free leagues) aren't in Lite: these ask nothing

    suspend fun loadBroadcasts(@Suppress("UNUSED_PARAMETER") event: SportsEvent) { }
    suspend fun refreshFreeEvent(@Suppress("UNUSED_PARAMETER") event: SportsEvent) { }

    // ---- League Networks: channels named as a league's own (NFL Network, NBA TV…), found by name when the page opens

    private val _leagueNetworks = MutableStateFlow<Map<String, List<LiveChannel>>>(emptyMap())
    val leagueNetworks: StateFlow<Map<String, List<LiveChannel>>> get() = _leagueNetworks
    private var networksFor = -1
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun refreshNetworks() = bg.launch {
        val list = runCatching { Iptv.liveChannels() }.getOrDefault(emptyList())
        val sig = list.size * 31 + list.fold(0) { h, c -> h * 31 + c.name.hashCode() }
        if (sig == networksFor) return@launch
        networksFor = sig
        _leagueNetworks.value = runCatching { LeagueNetworks.find(list) }.getOrDefault(emptyMap()).filterValues { it.isNotEmpty() }
    }

    // ---- Gemini guide matching (off unless switched on in Sports preferences; at most 3 games a round)

    private val geminiGate = Mutex()

    private fun queueGeminiMatching() {
        if (viewers == 0 || !Sports.settings.backgroundGeminiMatching) return
        bg.launch {
            if (!geminiGate.tryLock()) return@launch
            try {
                val client = SportsGemini
                val session = client.session()
                if (session.second == null) return@launch
                val candidates = Sports.matches.value.entries.flatMap { (id, matches) ->
                    val event = Sports.known.value.firstOrNull { it.id == id } ?: return@flatMap emptyList()
                    matches.filter { it.score in 40..79 && it.program != null }.map { event to it }
                }.distinctBy { (event, match) -> GeminiGuideInput.key(GeminiGuideInput.data(event, match.program!!)) }.take(3)
                for ((event, match) in candidates) {
                    if (viewers == 0 || !Sports.settings.backgroundGeminiMatching || client.session() != session) break
                    val programme = match.program ?: continue
                    val key = GeminiGuideInput.key(GeminiGuideInput.data(event, programme))
                    val decision = runCatching { client.verifyProgramme(event, programme, session) }.getOrNull() ?: continue
                    if (decision != GuideMatchDecision.UNCERTAIN) Sports.rescore(event.id) { list ->
                        val s = Sports.settings
                        SportsMatcher.sortMatches(list.map { c ->
                            val same = c.score in 40..79 && c.program?.let { GeminiGuideInput.key(GeminiGuideInput.data(event, it)) == key } == true
                            if (!same) c else when (decision) {
                                GuideMatchDecision.MATCH -> c.copy(score = 78, how = "Gemini suggests this programme; unconfirmed")
                                GuideMatchDecision.DIFFERENT -> c.copy(score = 35, how = "Gemini suggests a different programme")
                                else -> c
                            }
                        }, s.bestChannelFirst, SportsMatcher.preferenceRank(s))
                    }
                    delay(15_000)
                }
            } finally { geminiGate.unlock() }
        }
    }
}
