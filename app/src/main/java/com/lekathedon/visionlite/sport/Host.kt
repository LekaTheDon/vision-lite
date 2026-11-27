package com.lekathedon.visionlite.sport

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.iptv.Channel
import com.lekathedon.visionlite.iptv.Iptv
import com.lekathedon.visionlite.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Sports wherever you are: reminders, scoring alerts, pinned scores, failover. Dormant until Live TV or Sports was
 * opened this session, and then only while a reminder, pin or alert needs the scores.
 */
object Host {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val opened = MutableStateFlow(false)

    // ---- playing a game: its other channels for Up/Down and failover ----

    private data class Playing(val game: Game, val channels: List<Channel>, val tried: MutableSet<String>)
    @Volatile private var playing: Playing? = null

    /** Play [c] for [g] full screen; the game's other channels become the player's list. */
    fun play(nav: Nav, g: Game, c: Channel, all: List<Channel>) {
        playing = Playing(g, all, mutableSetOf(c.key))
        Iptv.zapList = all
        Iptv.played(c)
        nav.push(Screen.Player(c.url, c.name, c.headers, type = "live"))
    }

    /** Best channel now (Hold OK / Watch / reminders); false when none was found. */
    suspend fun watch(nav: Nav, g: Game): Boolean {
        val hits = Matcher.channels(g); val first = hits.firstOrNull()?.channel ?: return false
        kotlinx.coroutines.withContext(Dispatchers.Main) { play(nav, g, first, hits.map { it.channel }) }
        return true
    }

    /** Player: the channel [key] failed; the next one for the same game, once each (only for games started here). */
    fun next(key: String): Pair<String, Channel>? {
        val p = playing ?: return null
        if (p.channels.none { it.key == key }) return null
        p.tried += key
        val n = p.channels.firstOrNull { it.key !in p.tried } ?: return null
        p.tried += n.key
        return title(p.game) to n
    }

    // ---- pins: up to 3, this session ----

    val pins = MutableStateFlow<List<String>>(emptyList())
    fun pinned(g: Game) = g.key in pins.value
    fun pin(g: Game) { pins.value = if (pinned(g)) pins.value - g.key else (pins.value + g.key).takeLast(3); kick() }

    // ---- reminders ----

    fun reminded(g: Game) = Scores.follows.value.reminders.any { it.game == g.key || it.game == g.id }
    fun remind(g: Game) {
        val f = Scores.follows.value
        Scores.save(f.copy(reminders = if (reminded(g)) f.reminders.filter { it.game != g.key && it.game != g.id }
            else f.reminders + Reminder(g.key, title(g), g.startMs)))
        kick()
    }

    data class Card(val game: Game, val text: String, val countdown: Boolean)
    val card = MutableStateFlow<Card?>(null)
    val alert = MutableStateFlow<String?>(null)
    private val shown = HashSet<String>()
    private val lastScore = HashMap<String, String>()

    // ---- the loop ----

    private var loop: kotlinx.coroutines.Job? = null

    /** Something changed (pin, reminder, setting, Sports/Live TV opened): start the loop when it's needed. */
    fun kick() {
        if (loop?.isActive == true || !needed()) return
        loop = scope.launch {
            val stop = Scores.watch()
            try { while (needed()) { tick(); delay(15_000) } } finally { stop() }
        }
    }

    private fun needed(): Boolean {
        if (!opened.value && !Iptv.openedThisSession.value) return false
        val s = Scores.settings.value; val f = Scores.follows.value; val now = System.currentTimeMillis()
        return pins.value.isNotEmpty() || f.reminders.any { it.startMs > now - 3_600_000 && it.startMs < now + 86_400_000 } ||
            (s.remindMyTeams && f.teams.isNotEmpty()) || (s.scoringAlerts && !f.empty)
    }

    private fun tick() {
        val s = Scores.settings.value; val f = Scores.follows.value; val now = System.currentTimeMillis()
        val games = Scores.games.value
        // Reminders: picked games, and your teams' games when that's on.
        val due = games.filter { g -> g.state == State.PRE && (f.reminders.any { it.game == g.key || it.game == g.id } || s.remindMyTeams && g.sides.any { sd -> f.teams.any { it.league == g.league && it.id == sd.id } }) }
        due.forEach { g ->
            val left = g.startMs - now
            val auto = f.reminders.firstOrNull { it.game == g.key }?.autoSwitch ?: s.autoSwitch
            when {
                auto && left in 0..75_000 && "a:${g.key}" !in shown -> { shown += "a:${g.key}"; card.value = Card(g, "Switching to ${title(g)}", true) }
                left in 0..s.remindMinutes * 60_000L && "r:${g.key}" !in shown -> { shown += "r:${g.key}"
                    card.value = Card(g, "${title(g)} · starts ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(g.startMs))}", false) }
            }
        }
        // Old reminders go after a day.
        if (f.reminders.any { it.startMs < now - 86_400_000 }) Scores.save(f.copy(reminders = f.reminders.filter { it.startMs >= now - 86_400_000 }))
        // Pins drop 10 min after the end.
        val ended = pins.value.filter { k -> games.firstOrNull { it.key == k }?.let { it.state == State.DONE && now - it.startMs > (Leagues.byKey(it.league)?.minutes ?: 120) * 60_000L + 600_000 } == true }
        if (ended.isNotEmpty()) pins.value = pins.value - ended.toSet()
        // Scoring alerts: your games and pinned ones, held back so they never beat the stream.
        games.filter { it.state != State.PRE && it.versus && (f.follows(it) || it.key in pins.value) }.forEach { g ->
            val sc = "${g.away?.score}-${g.home?.score}"; val was = lastScore.put(g.key, sc)
            if (s.scoringAlerts && was != null && was != sc && !s.hideScores) scope.launch {
                delay(s.alertDelaySec * 1000L)
                val who = g.sides.firstOrNull { sd -> was.split('-').getOrNull(if (sd.home) 1 else 0) != sd.score }
                alert.value = "${word(g)} · ${who?.short ?: ""} ${scoreLine(g)}".trim(); delay(8_000); alert.value = null
            }
        }
    }

    private fun word(g: Game) = when (Leagues.byKey(g.league)?.sport) {
        Sport.SOCCER -> "GOAL"; Sport.FOOTBALL -> "SCORE"; Sport.HOCKEY -> "GOAL"; Sport.RUGBY -> "SCORE"; else -> "SCORE" }

    fun title(g: Game): String = if (!g.versus) g.title else if (Leagues.byKey(g.league)?.usStyle == true)
        "${g.away!!.short} at ${g.home!!.short}" else "${g.home!!.short} v ${g.away!!.short}"

    fun scoreLine(g: Game): String = if (!g.versus) "" else if (Leagues.byKey(g.league)?.usStyle == true)
        "${g.away!!.score ?: 0}–${g.home!!.score ?: 0}" else "${g.home!!.score ?: 0}–${g.away!!.score ?: 0}"
}

/** Drawn over every screen (MainActivity): the reminder card, the scoring alert and pinned scores. */
@Composable
fun SportsHost(nav: Nav) {
    val card by Host.card.collectAsState()
    val alert by Host.alert.collectAsState()
    val pins by Host.pins.collectAsState()
    val games by Scores.games.collectAsState()
    val opened by Iptv.openedThisSession.collectAsState()
    LaunchedEffect(opened) { Host.kick() }
    Box(Modifier.fillMaxSize()) {
        if (pins.isNotEmpty()) Column(Modifier.align(Alignment.TopStart).padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            pins.mapNotNull { k -> games.firstOrNull { it.key == k } }.forEach { g -> Pill("${Host.title(g)}  ${Host.scoreLine(g)}  ${g.detail}") }
        }
        alert?.let { Pill(it, Modifier.align(Alignment.TopCenter).padding(top = 24.dp), strong = true) }
        card?.let { c -> ReminderCard(nav, c, Modifier.align(Alignment.BottomEnd).padding(28.dp)) }
    }
}

@Composable
private fun Pill(t: String, modifier: Modifier = Modifier, strong: Boolean = false) {
    val shape = RoundedCornerShape(Kit.rowR)
    Text(t, color = if (strong) Kit.onAccent else Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = modifier.clip(shape).background(if (strong) Kit.accent else Kit.panel.copy(alpha = 0.92f)).border(1.dp, Kit.line, shape)
            .padding(horizontal = 14.dp, vertical = 8.dp))
}

@Composable
private fun ReminderCard(nav: Nav, c: Host.Card, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var left by remember(c) { mutableIntStateOf(10) }
    fun go() { Host.card.value = null; scope.launch { Host.watch(nav, c.game) } }
    LaunchedEffect(c) {
        runCatching { focus.requestFocus() }
        if (c.countdown) { while (left > 0) { delay(1000); left-- }; go() } else { delay(60_000); if (Host.card.value == c) Host.card.value = null }
    }
    KitPopup(c.text, modifier.width(380.dp), sub = if (c.countdown) "In $left s" else null, eyebrow = "SPORTS") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KitButton("Watch now", null, { go() }, Modifier.focusRequester(focus), primary = true)
            KitButton(if (c.countdown) "Stay here" else "Dismiss", null, { Host.card.value = null })
        }
    }
}
