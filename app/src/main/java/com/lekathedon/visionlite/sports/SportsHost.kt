package com.lekathedon.visionlite.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.iptv.Iptv
import com.lekathedon.visionlite.ui.Focusable
import com.lekathedon.visionlite.ui.Img
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A team you follow just scored (shown for a few seconds wherever you are). Vision's SportsAlert. */
data class SportsAlert(val event: SportsEvent, val team: TeamInfo, val league: League, val id: Long = System.nanoTime())

/** Vision's noticeScoreIncreases, as is. */
internal fun noticeScoreIncreases(league: League, before: List<SportsEvent>, after: List<SportsEvent>,
    follows: SportsFollows, notify: (SportsAlert) -> Unit) {
    val old = before.associateBy { it.id }
    for (event in after) {
        if (event.state != GameState.LIVE || !event.hasScore) continue
        val previous = old[event.id]?.takeIf { it.hasScore } ?: continue
        for (side in event.sides) {
            if (!follows.followsTeam(league.id, side.team.id) && league.id !in follows.leagues) continue
            val was = previous.sides.firstOrNull { it.team.id == side.team.id }?.score?.toDoubleOrNull() ?: continue
            val score = side.score?.toDoubleOrNull() ?: continue
            if (score > was) notify(SportsAlert(event, side.team, league))
        }
    }
}

/**
 * Vision's pinned scores: up to 3 live games (pinned from a game's page) whose scores stay top left over every screen,
 * the player included. For this session only; a game is let go 10 minutes after it ends. Costs no requests of its
 * own: a pin keeps Sports' own loop running (see [Sports.needsBackground]).
 */
object SportsPins {
    const val MAX = 3
    private val _list = MutableStateFlow<List<String>>(emptyList())
    val list: StateFlow<List<String>> get() = _list
    fun isPinned(id: String) = id in _list.value
    /** False when 3 are already pinned. */
    fun toggle(id: String): Boolean {
        val now = _list.value
        if (id in now) { _list.value = now - id; return true }
        if (now.size >= MAX) return false
        _list.value = now + id; return true
    }
    fun drop(id: String) { _list.value = _list.value - id }
}

/**
 * Plays a game's channel full screen. The game's other channels become the Up/Down list, and Sports' failover is
 * armed: if this channel won't play, the next channel showing the same game takes over (Vision, idea from CouchSide).
 */
fun watchGame(nav: Nav, ev: SportsEvent, m: ChannelMatch, all: List<ChannelMatch>, replace: Boolean = false) {
    val list = Iptv.channelsByKey(all.map { it.channel.key }.distinct())
    Iptv.zapList = list
    list.firstOrNull { it.key == m.channel.key }?.let { Iptv.played(it) }
    SportsFailover.arm(ev.title, all, m.channel)
    val screen = Screen.Player(m.channel.url, m.channel.name, m.channel.headers, type = "live")
    if (replace) nav.replace(screen) else nav.push(screen)
}

/** A short name for a game ("Arsenal v Chelsea", or its own title). */
internal fun gameName(ev: SportsEvent): String {
    val sport = SportsCatalog.league(ev.leagueId)?.sport ?: return ev.title
    val sides = ev.ordered(sport)
    return if (sides.size == 2) sides.joinToString(if (sport.homeFirst) " v " else " at ") { it.team.name } else ev.title
}

/**
 * Sports wherever you are (Vision's SportsHost): "starting soon" reminders for the games you picked and (when on) your
 * teams' games, with Watch now and, when Switch by itself is on, a 10 s countdown a minute before the start; scoring
 * cards for your teams; pinned scores top left. Runs Sports' loop in the background only while something needs it
 * (a reminder, a pin, your teams' alerts) and only after Live TV or Sports was opened this session (IPTV on demand).
 */
@Composable
fun SportsHost(nav: Nav) {
    val pins by SportsPins.list.collectAsState()
    val opened by Iptv.openedThisSession.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); tick++ } }
    val need = remember(pins, opened, tick) { opened && Sports.needsBackground() }
    if (need) DisposableEffect(Unit) { Sports.viewing(); onDispose { Sports.stopViewing() } }
    if (!opened) return

    Box(Modifier.fillMaxSize()) {
        PinnedCorner(Modifier.align(Alignment.TopStart))
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End) {
            Reminders(nav)
            ScoringCard()
        }
    }
}

@Composable
private fun PinnedCorner(modifier: Modifier) {
    val ids by SportsPins.list.collectAsState()
    val events by Sports.events.collectAsState()
    if (ids.isEmpty()) return
    val games = ids.mapNotNull { id -> events.firstOrNull { it.id == id } }
    LaunchedEffect(games) {
        val now = System.currentTimeMillis()
        games.filter { it.state == GameState.POST && (it.endMs ?: endEstimate(it)) < now - 10 * 60_000L }.forEach { SportsPins.drop(it.id) }
    }
    if (games.isEmpty()) return
    val hide = Sports.settings.hideScores
    Column(modifier.padding(start = 24.dp, top = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        games.forEach { ev ->
            Row(Modifier.background(Color(0xD9101114), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (ev.state == GameState.LIVE) Box(Modifier.padding(end = 7.dp).size(7.dp).background(Color(0xFFE5484D), RoundedCornerShape(50)))
                listOfNotNull(ev.home, ev.away).forEachIndexed { i, s ->
                    if (i == 1) Text("–", color = Color(0x99FFFFFF), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 6.dp))
                    Img(s.team.logo, 20.dp, 20.dp, Modifier.size(20.dp), ContentScale.Fit)
                    Text(" ${s.team.abbr.ifBlank { s.team.name }} ${if (hide || ev.state == GameState.PRE) "" else s.score.orEmpty()}",
                        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                if (ev.home == null) Text(ev.title, color = Color.White, fontSize = 14.sp, maxLines = 1)
                Text("  " + (ev.status ?: ""), color = Color(0x99FFFFFF), fontSize = 12.sp)
            }
        }
    }
}

/** Reminders a few minutes ahead (Settings: Sports reminders), each shown once; Watch now plays the best channel. */
@Composable
private fun Reminders(nav: Nav) {
    var due by remember { mutableStateOf<SportsEvent?>(null) }
    var countdown by remember { mutableStateOf<Pair<SportsEvent, ChannelMatch>?>(null) }
    val shown = remember { HashSet<String>() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            if (due != null || countdown != null) continue
            val s = Sports.settings; val f = Sports.follows
            val now = System.currentTimeMillis()
            val lead = s.remindMinutes.coerceAtLeast(0) * 60_000L
            val events = Sports.events.value
            val manual = f.reminders.mapNotNull { r -> events.firstOrNull { it.id == r.eventId } }
            val auto = if (s.remindMyTeams) events.filter { ev -> ev.state == GameState.PRE && ev.hasTime && ev.sides.any { f.followsTeam(ev.leagueId, it.team.id) } } else emptyList()
            val next = (manual + auto).distinctBy { it.id }.filter { it.id !in shown && it.hasTime && it.startMs - now in -10 * 60_000L..lead }
                .minByOrNull { it.startMs } ?: continue
            shown += next.id
            due = next
        }
    }
    // Switch by itself: a minute before the start, 10 s warning, then the game's best channel.
    LaunchedEffect(due) {
        val ev = due ?: return@LaunchedEffect
        if (!Sports.settings.autoSwitch) return@LaunchedEffect
        delay((ev.startMs - 70_000L - System.currentTimeMillis()).coerceAtLeast(0L))
        val best = Sports.channelsFor(ev).firstOrNull() ?: return@LaunchedEffect
        if (due?.id == ev.id) { due = null; countdown = ev to best }
    }
    LaunchedEffect(countdown) {
        val (ev, m) = countdown ?: return@LaunchedEffect
        delay(10_000)
        if (countdown?.first?.id == ev.id) { countdown = null; Sports.dropReminder(ev.id); watchGame(nav, ev, m, Sports.channelsFor(ev)) }
    }
    val c = countdown
    if (c != null) {
        Card("Switching to ${gameName(c.first)}", "On ${c.second.channel.name} in 10 s", listOf(
            "Watch now" to { countdown = null; Sports.dropReminder(c.first.id); watchGame(nav, c.first, c.second, Sports.channelsFor(c.first)) },
            "Cancel" to { countdown = null }))
        return
    }
    val ev = due ?: return
    LaunchedEffect(ev.id) { delay(30_000); if (due?.id == ev.id) due = null }
    val chans = Sports.channelsFor(ev)
    val mins = ((ev.startMs - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
    Card(gameName(ev), if (mins > 0) "Starts in $mins min" + (chans.firstOrNull()?.let { " · ${it.channel.name}" } ?: "") else "Starting now",
        buildList {
            chans.firstOrNull()?.let { m -> add("Watch now" to { due = null; Sports.dropReminder(ev.id); watchGame(nav, ev, m, chans) }) }
            add("Game page" to { due = null; nav.push(Screen.SportsGame(ev.id)) })
            add("Dismiss" to { due = null; Sports.dropReminder(ev.id) })
        })
}

/** A card bottom right with buttons; it takes focus so OK answers it (Back leaves it to time out). */
@Composable
private fun Card(title: String, sub: String, buttons: List<Pair<String, () -> Unit>>) {
    val first = remember { FocusRequester() }
    LaunchedEffect(title) { delay(50); runCatching { first.requestFocus() } }
    Column(Modifier.widthIn(max = 460.dp).background(Color(0xF2101114), RoundedCornerShape(14.dp)).padding(18.dp)) {
        Text("Sports", color = Color(0xFFD9DC8E), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2)
        Text(sub, color = Color(0xB3FFFFFF), fontSize = 14.sp)
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            buttons.forEachIndexed { i, (label, act) ->
                Focusable(act, if (i == 0) Modifier.focusRequester(first) else Modifier, RoundedCornerShape(50)) { f ->
                    Text(label, color = if (f) Color.Black else Color.White, fontSize = 14.sp,
                        modifier = Modifier.background(if (f) Color.White else Color(0x26FFFFFF)).padding(horizontal = 14.dp, vertical = 7.dp))
                }
            }
        }
    }
}

/** "GOAL · Arsenal" with the score, for 8 seconds; not focusable (it never takes the remote). */
@Composable
private fun ScoringCard() {
    val a by Sports.alerts.collectAsState()
    val alert = a ?: return
    LaunchedEffect(alert.id) { delay(8_000); Sports.clearAlert() }
    val ev = Sports.event(alert.event.id) ?: alert.event
    val word = when (alert.league.sport) { Sport.FOOTBALL -> "GOAL"; Sport.HOCKEY -> "GOAL"; Sport.AMERICAN_FOOTBALL -> "SCORE"; else -> "SCORE" }
    Row(Modifier.width(420.dp).background(Color(0xF2101114), RoundedCornerShape(14.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Img(alert.team.logo, 44.dp, 44.dp, Modifier.size(44.dp), ContentScale.Fit)
        Column(Modifier.padding(start = 12.dp)) {
            Text("$word · ${alert.team.name}", color = Color(0xFFD9DC8E), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(listOfNotNull(ev.home, ev.away).joinToString("  –  ") { "${it.team.name} ${it.score.orEmpty()}" }.ifBlank { ev.title },
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            ev.status?.let { Text(it, color = Color(0x99FFFFFF), fontSize = 12.sp) }
        }
    }
}
