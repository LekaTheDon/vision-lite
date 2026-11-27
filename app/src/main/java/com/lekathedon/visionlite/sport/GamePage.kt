package com.lekathedon.visionlite.sport

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.home.onHoldOk
import com.lekathedon.visionlite.iptv.Channel
import com.lekathedon.visionlite.iptv.EpgProgram
import com.lekathedon.visionlite.iptv.Iptv
import com.lekathedon.visionlite.iptv.Recorder
import com.lekathedon.visionlite.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun quality(name: String): String? = Regex("\\b(4K|UHD|FHD|HD|SD)\\b", RegexOption.IGNORE_CASE).find(name)?.value?.uppercase()?.let { if (it == "UHD") "4K" else it }

/** A game's own page: teams and score, actions, its channels, and while live the plays, stats and leaders. */
@Composable
fun GamePage(nav: Nav, key: String) {
    DisposableEffect(Unit) { Host.opened.value = true; Host.kick(); val stop = Scores.watch(); onDispose { stop() } }
    val games by Scores.games.collectAsState()
    val settings by Scores.settings.collectAsState()
    val follows by Scores.follows.collectAsState()
    val looked by Scores.looked.collectAsState()
    val g = games.firstOrNull { it.key == key || it.id == key } ?: looked[key]
    if (g == null) {
        LaunchedEffect(key) { Scores.ask(Scores.today()); Scores.ask(Scores.today().plusDays(1)); Scores.ask(Scores.today().minusDays(1)) }
        Inside(true) { Box(Modifier.fillMaxSize().skinPage(), contentAlignment = Alignment.Center) { Text("Finding this game…", color = Palette.TextDim) } }
        return
    }
    val scope = rememberCoroutineScope()
    val matched by Matcher.changed.collectAsState()
    var hits by remember(g.key) { mutableStateOf(Matcher.cached(g).orEmpty()) }
    LaunchedEffect(g.key, matched) { val h = Matcher.channels(g); if (h.isNotEmpty() || hits.isEmpty()) hits = h }
    var sum by remember(g.key) { mutableStateOf<Espn.Summary?>(null) }
    LaunchedEffect(g.key, g.state, g.home?.score, g.away?.score) {
        while (true) {
            if (!g.league.startsWith("tsdb/")) runCatching { Espn.summary(g.league, g.id) }.getOrNull()?.let { sum = it }
            if (g.state != State.LIVE) break; delay(30_000)
        }
    }
    var place by remember(g.key) { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(g.key) {
        val l = Leagues.byKey(g.league) ?: return@LaunchedEffect
        if (l.table == Table.NONE) return@LaunchedEffect
        val rows = Scores.table(l).flatMap { gr -> gr.rows.mapIndexed { i, r -> r.team.id to "${ordinal(i + 1)} in ${gr.name.ifBlank { l.name }}" } }
        place = rows.toMap()
    }
    var win by remember(g.key) { mutableStateOf<Channel?>(null) }
    var menu by remember { mutableStateOf<Channel?>(null) }
    var fix by remember { mutableStateOf(false) }
    var record by remember { mutableStateOf(false) }
    var team by remember { mutableStateOf<Side?>(null) }
    val hide = settings.hideScores && g.state == State.PRE  // opened: scores show
    val channels = hits.map { it.channel }
    val title = Host.title(g)
    val league = Leagues.byKey(g.league)

    Inside(true) {
        LazyColumn(Modifier.fillMaxSize().skinPage(), contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 24.dp, bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(key = "top") {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Logo(league?.logo, 26.dp); Eyebrow("  ${league?.name.orEmpty()} · ${stateText(g)}") }
                        if (g.versus) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            ordered(g).forEachIndexed { i, s ->
                                if (i == 1) DisplayText(if (g.state == State.PRE || hide) "v" else Host.scoreLine(g), 40.sp)
                                Focusable({ team = s }, shape = RoundedCornerShape(Kit.cardR)) { f ->
                                    Column(Modifier.background(if (f) kitFocus.first else Color.Transparent).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Logo(s.logo, 72.dp)
                                        Text(s.name, color = if (f) kitFocus.second else Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                        Text(listOfNotNull(s.record, place[s.id], sum?.form?.get(s.id)?.ifBlank { null }, sum?.winChance?.get(s.id)?.let { "${it.toInt()}% to win" }).joinToString(" · "),
                                            color = if (f) kitFocus.second else Palette.TextDim, fontSize = 12.sp)
                                    }
                                }
                            }
                        } else DisplayText(g.title, 34.sp)
                        g.venue?.let { Text(it, color = Palette.TextDim, fontSize = 13.sp) }
                    }
                    if (win != null) Box(Modifier.width(420.dp).aspectRatio(16 / 9f).clip(RoundedCornerShape(Kit.cardR)).background(Color.Black)) {
                        MiniPlayer(win!!.url, win!!.headers, Modifier.fillMaxSize())
                    }
                }
            }
            item(key = "actions") {
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { KitButton("Watch", Icons.Play, { channels.firstOrNull()?.let { Host.play(nav, g, it, channels) } }, Modifier.focusRequester(first), primary = true) }
                    startOver(g, channels.firstOrNull())?.let { url -> item { KitButton("From the start", Icons.Restart, {
                        Host.play(nav, g, channels.first(), channels); nav.replace(Screen.Player(url, channels.first().name, channels.first().headers, type = "live")) }) } }
                    if (g.versus) item {
                        val all = g.sides.all { s -> follows.teams.any { it.league == g.league && it.id == s.id } }
                        KitButton(if (all) "Following" else "Follow", Icons.Star, { followSides(g, !all) }, on = all)
                    }
                    if (g.state == State.PRE) item { KitButton(if (Host.reminded(g)) "Reminder set" else "Remind me", Icons.Bell, { Host.remind(g) }, on = Host.reminded(g)) }
                    if (g.state != State.DONE && channels.isNotEmpty()) item { KitButton("Record", Icons.Record, { record = true }) }
                    if (g.state != State.DONE) item { KitButton(if (Host.pinned(g)) "Pinned" else "Pin score", Icons.Pin, { Host.pin(g) }, on = Host.pinned(g)) }
                    item { RefreshButton("${channels.size} channels") }
                }
            }
            item(key = "chh") { Row(verticalAlignment = Alignment.CenterVertically) {
                KitHeader("Channels", by = if (channels.isEmpty()) "none found yet" else "${channels.size}")
                Spacer(Modifier.width(16.dp)); KitButton("Fix channel", Icons.Search, { fix = true })
            } }
            items(hits, key = { "c" + it.channel.key }) { h ->
                val c = h.channel
                Focusable({ if (win?.key == c.key) Host.play(nav, g, c, channels) else win = c },
                    Modifier.fillMaxWidth().onHoldOk { menu = c }, RoundedCornerShape(Kit.rowR)) { f ->
                    val ink = if (f) kitFocus.second else Palette.Text; val dim = if (f) kitFocus.second.copy(alpha = 0.7f) else Palette.TextDim
                    Row(Modifier.fillMaxWidth().background(if (f) kitFocus.first else Kit.rowFill).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Logo(c.logo, 34.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(c.name + if (win?.key == c.key) "  · playing" else "", color = ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            val now by produceState<String?>(null, c.key) { value = withContext(Dispatchers.IO) { runCatching { Iptv.nowNext(c).firstOrNull()?.title }.getOrNull() } }
                            Text(listOfNotNull(now, h.why.label, c.group).joinToString(" · "), color = dim, fontSize = 12.sp, maxLines = 1)
                        }
                        quality(c.name)?.let { KitChip(it) }
                    }
                }
            }
            if (g.field.isNotEmpty() && g.state != State.PRE && !hide) item(key = "field") { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                KitHeader(if (Leagues.byKey(g.league)?.sport == Sport.GOLF) "Leaderboard" else "Running order")
                g.field.forEachIndexed { i, p -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.width(34.dp))
                    Text(p.name, color = Palette.Text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(p.score.orEmpty(), color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                } }
            } }
            val s = sum
            if (s != null && g.state != State.PRE) {
                if (s.lines.isNotEmpty() && !hide) item(key = "lines") { Column {
                    KitHeader("By period")
                    s.lines.forEach { (t, l) -> Text("$t   " + l.joinToString("   "), color = Palette.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp)) }
                } }
                if (s.plays.isNotEmpty() && !hide) item(key = "plays") { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    KitHeader("Scoring")
                    s.plays.takeLast(12).reversed().forEach { p -> Text(listOfNotNull(p.clock, p.score, p.text).joinToString(" · "), color = Palette.Text, fontSize = 13.sp, maxLines = 2) }
                } }
                if (s.stats.isNotEmpty()) item(key = "stats") { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    KitHeader("Stats", by = "${g.home?.abbr} · ${g.away?.abbr}")
                    s.stats.forEach { st -> StatBar(st) }
                } }
                if (s.leaders.isNotEmpty()) item(key = "leaders") { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    KitHeader("Leaders")
                    s.leaders.forEach { l -> Text("${l.label}: ${l.name} ${l.value}", color = Palette.Text, fontSize = 13.sp) }
                } }
            }
        }
        menu?.let { c -> ChannelMenu(g, c) { menu = null; scope.launch { Matcher.changed.value++ } } }
        if (fix) FixChannel(g) { fix = false; Matcher.changed.value++ }
        if (record) RecordPopup(g, channels) { record = false }
        team?.let { s -> TeamCard(nav, Team(g.league, s.id, s.name, s.abbr, s.color, s.logo), place[s.id]) { team = null } }
    }
}

private fun ordinal(n: Int) = "$n" + if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }

private fun followSides(g: Game, on: Boolean) {
    val f = Scores.follows.value
    val teams = g.sides.map { Team(g.league, it.id, it.name, it.abbr, it.color, it.logo) }
    Scores.save(f.copy(teams = if (on) f.teams + teams.filter { t -> f.teams.none { it.key == t.key } } else f.teams.filter { t -> teams.none { it.key == t.key } }))
}

private fun program(g: Game) = EpgProgram(g.startMs, g.startMs + (Leagues.byKey(g.league)?.minutes ?: 120) * 60_000L, Host.title(g))
private fun startOver(g: Game, c: Channel?): String? = if (c == null || g.state != State.LIVE) null else runCatching { Iptv.startOverUrl(c, program(g)) }.getOrNull()

@Composable
private fun StatBar(s: Espn.Stat) {
    val h = s.home.filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: 0f; val a = s.away.filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: 0f
    val part = if (h + a <= 0f) 0.5f else h / (h + a)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(s.home, color = Palette.Text, fontSize = 13.sp, modifier = Modifier.width(60.dp))
        Column(Modifier.weight(1f)) {
            Text(s.label, color = Palette.TextDim, fontSize = 11.sp)
            Row(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Kit.line)) {
                if (part > 0f) Box(Modifier.weight(part).fillMaxHeight().background(Kit.accent))
                if (part < 1f) Box(Modifier.weight(1f - part).fillMaxHeight())
            }
        }
        Text(s.away, color = Palette.Text, fontSize = 13.sp, modifier = Modifier.width(60.dp).padding(start = 10.dp))
    }
}

@Composable
fun Overlay(onClose: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center, content = content)
}

@Composable
private fun ChannelMenu(g: Game, c: Channel, close: () -> Unit) = Overlay(close) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    KitPopup(c.name, Modifier.width(420.dp), eyebrow = Host.title(g)) {
        KitMenuItem("This is the right channel", { Matcher.pick(g, c, "game"); close() }, Modifier.focusRequester(first))
        if (g.networks.isNotEmpty()) KitMenuItem("…for ${g.networks.first()} from now on", { Matcher.pick(g, c, "network"); close() })
        if (g.versus) KitMenuItem("…for these teams from now on", { Matcher.pick(g, c, "team"); close() })
        KitMenuItem("…for ${Leagues.byKey(g.league)?.name ?: "this league"} from now on", { Matcher.pick(g, c, "league"); close() })
        KitMenuItem("Not this channel", { Matcher.block(c); close() })
    }
}

/** Type part of a channel's name and pick it for this game. */
@Composable
private fun FixChannel(g: Game, close: () -> Unit) = Overlay(close) {
    var q by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Channel>>(emptyList()) }
    LaunchedEffect(q) {
        if (q.length < 2) { found = emptyList(); return@LaunchedEffect }
        delay(250)
        found = withContext(Dispatchers.IO) { val n = Matcher.norm(q); Iptv.channels(null).filter { Matcher.norm(it.name).contains(n) }.take(30) }
    }
    val box = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { box.requestFocus() } }
    KitPopup("Fix channel", Modifier.width(520.dp), sub = "Type part of the channel's name", eyebrow = Host.title(g)) {
        BasicTextField(q, { q = it.take(40) }, singleLine = true, textStyle = TextStyle(color = Palette.Text, fontSize = 16.sp),
            modifier = Modifier.fillMaxWidth().focusRequester(box).border(1.dp, Kit.line, RoundedCornerShape(8.dp)).padding(12.dp))
        LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(found, key = { it.key }) { c -> KitMenuItem("${c.name}  ·  ${c.group}", { Matcher.pick(g, c, "game"); close() }) }
        }
    }
}

/** Record: channel and length (◂ 2:00 ▸, the sport's usual length rounded up to the half hour), 5 min early. */
@Composable
private fun RecordPopup(g: Game, channels: List<Channel>, close: () -> Unit) = Overlay(close) {
    val usual = ((Leagues.byKey(g.league)?.minutes ?: 120) + 29) / 30 * 30
    var mins by remember { mutableIntStateOf(usual) }
    var ch by remember { mutableIntStateOf(0) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    KitPopup("Record", Modifier.width(460.dp), eyebrow = Host.title(g)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KitButton("◂", null, { ch = (ch - 1 + channels.size) % channels.size }); Text(channels[ch].name, color = Palette.Text, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
            KitButton("▸", null, { ch = (ch + 1) % channels.size })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KitButton("◂", null, { mins = (mins - 30).coerceAtLeast(30) }); Text("%d:%02d".format(mins / 60, mins % 60), color = Palette.Text, fontSize = 18.sp)
            KitButton("▸", null, { mins = (mins + 30).coerceAtMost(600) })
        }
        KitButton("Record", Icons.Record, {
            Recorder.scheduleAt(channels[ch], Host.title(g), g.startMs - 5 * 60_000L, g.startMs + mins * 60_000L); close()
        }, Modifier.focusRequester(first), primary = true)
    }
}

/** A team's season, its table place, follow / unfollow. */
@Composable
fun TeamCard(nav: Nav, t: Team, place: String?, close: () -> Unit) = Overlay(close) {
    val follows by Scores.follows.collectAsState()
    var season by remember(t.key) { mutableStateOf<List<Game>?>(null) }
    LaunchedEffect(t.key) { season = Scores.season(t) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val on = follows.follows(t)
    KitPopup(t.name, Modifier.width(620.dp).heightIn(max = 620.dp), sub = place, eyebrow = Leagues.byKey(t.league)?.name) {
        KitButton(if (on) "Following" else "Follow", Icons.Star, {
            Scores.save(follows.copy(teams = if (on) follows.teams.filter { it.key != t.key } else follows.teams + t)) }, Modifier.focusRequester(first), on = on)
        val list = season
        if (list == null) Text("Loading the season…", color = Palette.TextDim, fontSize = 13.sp)
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(list, key = { it.key }) { g -> GameLine(g, false, null, { close(); nav.push(Screen.SportsGame(g.key)) }, {}) }
        }
    }
}
