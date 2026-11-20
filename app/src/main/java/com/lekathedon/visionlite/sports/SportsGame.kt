package com.lekathedon.visionlite.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.iptv.Iptv
import com.lekathedon.visionlite.iptv.Recorder
import com.lekathedon.visionlite.ui.Focusable
import com.lekathedon.visionlite.ui.Img
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.MiniPlayer
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.Screen
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Dim = Color(0x99FFFFFF)
private val Red = Color(0xFFE5484D)
private val Gold = Color(0xFFD9DC8E)
private val CardBg = Color(0x14FFFFFF)

/**
 * A game's page (Vision's GamePage, drawn the Lite way): the two teams on bands in their colours with the score, then
 * Watch / Remind me / Record / Pin score / Follow / Table, the channels showing it (tried in the window), and what
 * ESPN has about it: scoring, win chance, form, earlier meetings, match info, the table, team stats, leaders and
 * line-ups. A panel shows only when ESPN has something for it. The summary is asked when the page opens and again
 * every 25 s while the game is on (one request for all of it, ~20-150 KB).
 */
@Composable
fun SportsGamePage(nav: Nav, id: String) {
    SportsViewer()
    val events by Sports.events.collectAsState()
    val matches by Sports.matches.collectAsState()
    val pins by SportsPins.list.collectAsState()
    val ev = remember(events, id) { Sports.event(id) } ?: run {
        Box(Modifier.fillMaxSize().background(LocalLook.current.page), contentAlignment = Alignment.Center) {
            Text("Getting the game…", color = Palette.TextDim, fontSize = 16.sp)
        }
        return
    }
    val league = SportsCatalog.league(ev.leagueId)
    val chans = matches[ev.id].orEmpty()
    var extras by remember(id) { mutableStateOf<GameExtras?>(null) }
    var plays by remember(id) { mutableStateOf<List<ScoringPlay>>(emptyList()) }
    LaunchedEffect(id, ev.state) {
        while (true) {
            Sports.event(id)?.let { cur -> Sports.extras(cur)?.let { (e, p) -> extras = e; plays = p } }
            if (Sports.event(id)?.state != GameState.LIVE) break
            delay(25_000)
        }
    }
    var playing by remember(id) { mutableStateOf<ChannelMatch?>(null) }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(note) { if (note.isNotEmpty()) { delay(3_000); note = "" } }
    var follows by remember { mutableStateOf(Sports.follows) }
    var recordAsk by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }

    Box(Modifier.fillMaxSize().background(LocalLook.current.page)) {
        Row(Modifier.fillMaxSize().padding(start = 36.dp, end = 36.dp, top = 24.dp)) {
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                item("head") { Header(ev, league) }
                item("buttons") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val best = chans.firstOrNull()
                        if (best != null) item { Chip("▶ Watch · ${best.channel.name}", Modifier.focusRequester(first)) { watchGame(nav, ev, best, chans) } }
                        if (ev.state == GameState.PRE) item {
                            Chip(if (Sports.hasReminder(ev)) "Reminder set ✓" else "Remind me", if (best == null) Modifier.focusRequester(first) else Modifier) {
                                Sports.toggleReminder(ev); follows = Sports.follows
                                note = if (Sports.hasReminder(ev)) "We'll tell you ${Sports.settings.remindMinutes} min before it starts" else "Reminder removed"
                            }
                        }
                        if (best != null && ev.state != GameState.POST) item {
                            val on = Recorder.isRecording(best.channel.url, ev.startMs - 5 * 60_000L)
                            Chip(if (on) "Recording set ✓" else "● Record") { if (!on) recordAsk = true else note = "Already set: see Recordings" }
                        }
                        if (ev.state != GameState.POST && ev.sides.size == 2) item {
                            Chip(if (ev.id in pins) "Pinned ✓" else "Pin score") {
                                if (!SportsPins.toggle(ev.id)) note = "3 games are pinned already: unpin one first"
                                else if (ev.id in SportsPins.list.value) note = "Its score stays top left while you watch"
                            }
                        }
                        ev.sides.forEach { s ->
                            item {
                                val on = follows.followsTeam(ev.leagueId, s.team.id)
                                Chip(if (on) "Following ${s.team.name} ✓" else "Follow ${s.team.name}") { SportsMine.toggleTeam(s.team); follows = Sports.follows }
                            }
                        }
                        if (league?.espnPath != null && league.kind == LeagueKind.TEAMS) item { Chip("Table") { nav.push(Screen.Standings(league.id)) } }
                    }
                }
                if (plays.isNotEmpty()) item("plays") { Panel("Scoring") {
                    plays.takeLast(12).reversed().forEach { p ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text(p.clock, color = Dim, fontSize = 13.sp, modifier = Modifier.width(70.dp))
                            Text(listOfNotNull(p.teamAbbr, p.text).joinToString(" · "), color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 2)
                            if (p.awayScore != null && p.homeScore != null) Text("${p.awayScore}-${p.homeScore}", color = Gold, fontSize = 13.sp)
                        }
                    }
                } }
                extras?.let { x -> extrasItems(x, ev, league) }
            }
            // The window and the channels showing the game (OK tries one in the window, OK again plays it full screen).
            Column(Modifier.padding(start = 24.dp).width(460.dp).fillMaxHeight()) {
                Box(Modifier.fillMaxWidth().height(259.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black)
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    MiniPlayer(playing?.channel?.url, playing?.channel?.headers ?: emptyMap(), Modifier.fillMaxSize())
                    if (playing == null) Text(if (chans.isEmpty()) "No channel found yet" else "OK on a channel tries it here", color = Color.White, fontSize = 14.sp)
                }
                Text(if (!Iptv.hasChannels()) "Add a Live TV playlist to see the channels showing games." else if (chans.isEmpty()) "No channel found for this one yet." else "Channels",
                    color = Dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(chans, key = { it.channel.key }) { m ->
                        val on = playing?.channel?.key == m.channel.key
                        Focusable({ if (on) watchGame(nav, ev, m, chans) else playing = m }) { f ->
                            Row(Modifier.fillMaxWidth().background(if (f) Color.White else if (on) Color(0x33D9DC8E) else CardBg).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(48.dp, 28.dp), contentAlignment = Alignment.Center) { Img(m.channel.logo, 48.dp, 28.dp, Modifier.size(48.dp, 28.dp), ContentScale.Fit) }
                                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                    Text(m.channel.name, color = if (f) Color.Black else Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val sub = if (on) "Playing · OK again: full screen" else listOfNotNull(m.quality, m.program?.title, if (m.sure) null else "may be another game").joinToString(" · ")
                                    if (sub.isNotBlank()) Text(sub, color = if (f) Color.DarkGray else Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (note.isNotEmpty()) Text(note, color = Color.White, fontSize = 15.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp)
            .background(Color(0xE6101114), RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 8.dp))
        if (recordAsk) chans.firstOrNull()?.let { best ->
            RecordDialog(ev, league, best, onDone = { msg -> recordAsk = false; msg?.let { note = it } })
        }
    }
}

/** How long to record: the usual length plus extra time, from 5 minutes before the start (Vision's SportsRecord). */
@Composable
private fun RecordDialog(ev: SportsEvent, league: League?, m: ChannelMatch, onDone: (String?) -> Unit) {
    val usual = league?.minutes ?: 180
    val options = listOf(usual to "Usual length (${usual / 60} h ${usual % 60} min)", usual + 30 to "+30 min (extra time)", usual + 60 to "+1 hour", usual + 120 to "+2 hours")
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
    androidx.activity.compose.BackHandler { onDone(null) }
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(460.dp).background(Color(0xFF16171A), RoundedCornerShape(16.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Record ${gameName(ev)}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("On ${m.channel.name}, from 5 minutes before the start", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            options.forEachIndexed { i, (mins, label) ->
                Focusable({
                    val c = Iptv.channelsByKey(listOf(m.channel.key)).firstOrNull()
                    if (c == null) onDone("That channel isn't in your list any more")
                    else {
                        val start = (if (ev.hasTime) ev.startMs else System.currentTimeMillis()) - 5 * 60_000L
                        Recorder.scheduleAt(c, gameName(ev), start, maxOf(start, System.currentTimeMillis()) + (mins + 5) * 60_000L)
                        onDone("Recording set · Recordings has it")
                    }
                }, if (i == 0) Modifier.focusRequester(first) else Modifier) { f ->
                    Text(label, color = if (f) Color.Black else Color.White, fontSize = 15.sp,
                        modifier = Modifier.fillMaxWidth().background(if (f) Color.White else CardBg).padding(12.dp))
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Focusable(onClick, modifier, RoundedCornerShape(50)) { f ->
        Text(text, color = if (f) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            modifier = Modifier.background(if (f) Color.White else Color(0x22FFFFFF)).padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

/** The teams on bands in their own colours, the score, the state; for events without two sides, the title. */
@Composable
private fun Header(ev: SportsEvent, league: League?) {
    val hide = Sports.settings.hideScores && ev.state != GameState.PRE
    val hm = remember { SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault()) }
    val state = when (ev.state) {
        GameState.LIVE -> ev.status ?: ev.liveLabel
        GameState.POST -> ev.status ?: ev.finishedLabel
        else -> if (ev.hasTime) hm.format(Date(ev.startMs)) else "This week"
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ev.state == GameState.LIVE) Box(Modifier.padding(end = 8.dp).size(9.dp).clip(RoundedCornerShape(50)).background(Red))
            Text(state, color = if (ev.state == GameState.LIVE) Red else Dim, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("  ·  ${league?.name ?: ""}" + (ev.note?.let { "  ·  $it" } ?: ""), color = Dim, fontSize = 14.sp, maxLines = 1)
        }
        val sides = league?.sport?.let { ev.ordered(it) } ?: ev.sides
        if (sides.size == 2) Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            sides.forEach { s ->
                val c = Color(s.team.color ?: 0xFF2A2F3A)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brush.horizontalGradient(listOf(c.copy(alpha = 0.55f), Color(0x10FFFFFF))))
                    .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Img(s.team.logo, 44.dp, 44.dp, Modifier.size(44.dp), ContentScale.Fit)
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(s.team.fullName.ifBlank { s.team.name }, color = Color.White, fontSize = 20.sp, fontWeight = if (s.winner) FontWeight.ExtraBold else FontWeight.SemiBold, maxLines = 1)
                        (s.record ?: s.team.record)?.let { Text(it, color = Dim, fontSize = 12.sp) }
                    }
                    if (!hide && ev.state != GameState.PRE) Text(s.score.orEmpty(), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else Text(ev.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        ev.situation?.let { Text(it, color = Gold, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
        (ev.about ?: ev.subtitle)?.takeIf { it.isNotBlank() && ev.sides.isEmpty() }?.let { Text(it, color = Dim, fontSize = 13.sp, maxLines = 3, modifier = Modifier.padding(top = 6.dp)) }
        if (ev.standings.isNotEmpty()) Column(Modifier.padding(top = 8.dp)) {
            ev.standings.take(10).forEach { st -> Text("${st.pos}.  ${st.name}" + (st.detail?.let { "  ·  $it" } ?: ""), color = Color.White, fontSize = 14.sp) }
        }
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Focusable({}) { f ->
        Column(Modifier.fillMaxWidth().background(if (f) Color(0x26FFFFFF) else CardBg).padding(16.dp)) {
            Text(title, color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            content()
        }
    }
}

/** ESPN's extras as panels, in Vision's order: before the game form/meetings/info/table; once on stats/leaders/line-ups. */
private fun androidx.compose.foundation.lazy.LazyListScope.extrasItems(x: GameExtras, ev: SportsEvent, league: League?) {
    if (x.hasOdds) item("odds") { Panel("Chance of winning") {
        val home = ev.home?.team?.name ?: "Home"; val away = ev.away?.team?.name ?: "Away"
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
            val h = (x.homeWin ?: 0.0).toFloat().coerceAtLeast(0.01f); val d = (x.draw ?: 0.0).toFloat(); val a = (x.awayWin ?: 0.0).toFloat().coerceAtLeast(0.01f)
            Box(Modifier.weight(h).fillMaxHeight().background(Color(ev.home?.team?.color ?: 0xFF4F7CFF)))
            if (d > 0f) Box(Modifier.weight(d).fillMaxHeight().background(Color(0xFF666666)))
            Box(Modifier.weight(a).fillMaxHeight().background(Color(ev.away?.team?.color ?: 0xFFFF8A4F)))
        }
        Text("$home ${"%.0f".format(x.homeWin)}%" + (x.draw?.let { " · Draw ${"%.0f".format(it)}%" } ?: "") + " · $away ${"%.0f".format(x.awayWin)}%",
            color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
    } }
    if (x.form.isNotEmpty()) item("form") { Panel("Form (last games)") {
        x.form.forEach { r ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.team, color = Color.White, fontSize = 14.sp, modifier = Modifier.width(200.dp), maxLines = 1)
                r.results.takeLast(5).forEach { w ->
                    val c = when (w.uppercase()) { "W" -> Color(0xFF32C25A); "L" -> Red; else -> Color(0xFF8A8A8A) }
                    Box(Modifier.padding(end = 4.dp).size(22.dp).background(c, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                        Text(w.uppercase(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    } }
    if (x.meetings.isNotEmpty()) item("meet") { Panel("Earlier meetings") {
        val df = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        x.meetings.take(6).forEach { m -> Text((m.dateMs?.let { df.format(Date(it)) + "  ·  " } ?: "") + m.text, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(vertical = 2.dp)) }
    } }
    if (x.hasMatchInfo) item("info") { Panel("Match info") {
        listOfNotNull(
            x.venue?.let { "Venue: $it" + (x.city?.let { c -> ", $c" } ?: "") },
            x.tempF?.let { "Weather: ${((it - 32) * 5 / 9)}°C" + (x.conditions?.let { c -> " · $c" } ?: "") },
            x.referee?.let { "Referee: $it" },
            x.attendance?.let { "Attendance: %,d".format(it) }
        ).forEach { Text(it, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(vertical = 2.dp)) }
    } }
    if (x.table.isNotEmpty()) item("table") { Panel(x.tableTitle ?: "Table") {
        Row { Text("", modifier = Modifier.width(34.dp)); Text("", modifier = Modifier.weight(1f)); x.tableHead.take(3).forEach { Text(it, color = Dim, fontSize = 12.sp, modifier = Modifier.width(56.dp)) } }
        x.table.forEach { r ->
            Row(Modifier.background(if (r.mine) Color(0x2232C25A) else Color.Transparent).padding(vertical = 3.dp)) {
                Text(r.pos, color = Dim, fontSize = 13.sp, modifier = Modifier.width(34.dp))
                Text(r.team, color = Color.White, fontSize = 14.sp, fontWeight = if (r.mine) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f), maxLines = 1)
                listOf(r.a, r.b, r.c).forEach { Text(it, color = Color.White, fontSize = 13.sp, modifier = Modifier.width(56.dp)) }
            }
        }
    } }
    if (x.stats.isNotEmpty()) item("stats") { Panel("Team stats") {
        x.stats.forEach { s ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Row { Text(s.away, color = Color.White, fontSize = 14.sp, modifier = Modifier.width(80.dp))
                    Text(s.label, color = Dim, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(s.home, color = Color.White, fontSize = 14.sp, modifier = Modifier.width(80.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End) }
                val a = s.awayValue; val h = s.homeValue
                if (a != null && h != null && a + h > 0) Row(Modifier.fillMaxWidth().padding(top = 3.dp).height(4.dp).clip(RoundedCornerShape(2.dp))) {
                    Box(Modifier.weight(a.toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(Color(ev.away?.team?.color ?: 0xFFFF8A4F)))
                    Box(Modifier.weight(h.toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(Color(ev.home?.team?.color ?: 0xFF4F7CFF)))
                }
            }
        }
    } }
    if (x.performers.isNotEmpty()) item("leaders") { Panel("Leaders") {
        x.performers.take(8).forEach { p -> Text("${p.category} · ${p.name} · ${p.line}", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(vertical = 2.dp), maxLines = 2) }
    } }
    if (x.lineups.isNotEmpty()) item("lineups") { Panel("Line-ups") {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            x.lineups.forEach { l ->
                Column(Modifier.weight(1f)) {
                    Text(l.team + (l.formation?.let { " · $it" } ?: ""), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    l.players.forEach { Text(it, color = Color(0xCCFFFFFF), fontSize = 13.sp) }
                }
            }
        }
    } }
}
