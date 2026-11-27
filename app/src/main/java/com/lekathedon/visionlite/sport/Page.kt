package com.lekathedon.visionlite.sport

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.home.onHoldOk
import com.lekathedon.visionlite.iptv.Channel
import com.lekathedon.visionlite.iptv.Recorder
import com.lekathedon.visionlite.ui.*
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

// ---- shared bits ----

fun stateText(g: Game): String {
    val t = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(g.startMs))
    return when (g.state) {
        State.LIVE -> "LIVE · ${g.detail}"
        State.DONE -> g.detail.ifBlank { "Final" }
        State.PRE -> {
            val d = Instant.ofEpochMilli(g.startMs).atZone(ZoneId.systemDefault()).toLocalDate(); val today = LocalDate.now()
            when (d) { today -> t; today.plusDays(1) -> "Tomorrow $t"
                else -> "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${d.dayOfMonth} · $t" }
        }
    }
}

/** Sides in the order the league says them: away first for US leagues ("at"), home first elsewhere. */
fun ordered(g: Game): List<Side> = if (Leagues.byKey(g.league)?.usStyle == true) listOfNotNull(g.away, g.home) else listOfNotNull(g.home, g.away)

@Composable
fun Logo(url: String?, size: androidx.compose.ui.unit.Dp) =
    Box(Modifier.size(size), contentAlignment = Alignment.Center) { Img(url, size, size, scale = ContentScale.Fit) }

/** The Refresh channels button: one shared job; its note says the step, then "Updated 14:05 · n live". */
@Composable
fun RefreshButton(note: String, modifier: Modifier = Modifier) {
    val busy by Matcher.refreshing.collectAsState()
    val at by Scores.updatedAt.collectAsState()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        KitButton(if (busy) "Refreshing…" else "Refresh channels", Icons.Sync, { if (!busy) Matcher.refresh() })
        Text(if (busy) "Guide, scores, then channels…" else if (at > 0) "Updated ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))} · $note" else "",
            color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp))
    }
}

/** One game: league logo, state, both sides (or the event's title), marks. */
@Composable
fun GameLine(g: Game, hide: Boolean, channels: Int?, onClick: () -> Unit, onHold: () -> Unit, modifier: Modifier = Modifier) {
    val league = Leagues.byKey(g.league)
    Focusable(onClick, modifier.fillMaxWidth().onHoldOk(onHold),
        RoundedCornerShape(Kit.rowR)) { f ->
        val ink = if (f) kitFocus.second else Palette.Text; val dim = if (f) kitFocus.second.copy(alpha = 0.7f) else Palette.TextDim
        Row(Modifier.fillMaxWidth().background(if (f) kitFocus.first else Kit.rowFill).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Logo(league?.logo, 28.dp)
            Column(Modifier.width(112.dp).padding(start = 10.dp)) {
                Text(stateText(g), color = if (g.state == State.LIVE && !f) Kit.accent else dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text(league?.short ?: "", color = dim, fontSize = 11.sp, maxLines = 1)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (g.versus) ordered(g).forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Logo(s.logo, 22.dp)
                        Text(s.name, color = ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        if (g.state != State.PRE) Text(if (hide) "–" else s.score ?: "", color = ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                } else Text(g.title, color = ink, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.padding(start = 10.dp), horizontalAlignment = Alignment.End) {
                val marks = listOfNotNull("● REC".takeIf { channels != null && Recorder.list.value.any { r -> r.title == Host.title(g) && r.state == "scheduled" } },
                    "🔔".takeIf { Host.reminded(g) }, "📌".takeIf { Host.pinned(g) })
                if (marks.isNotEmpty()) Text(marks.joinToString(" "), color = ink, fontSize = 11.sp)
                if (channels != null && channels > 0) Text("on $channels ch", color = dim, fontSize = 11.sp)
            }
        }
    }
}

// ---- the Sports page ----

private sealed interface View { val label: String }
private data object LiveView : View { override val label = "Live now" }
private data object AllView : View { override val label = "All events" }
private data object MineView : View { override val label = "My Teams" }
private data class SportView(val sport: Sport) : View { override val label = sport.label }
private data class LeagueView(val l: League) : View { override val label = l.short }
private data object TablesView : View { override val label = "Tables" }
private data object NetsView : View { override val label = "League networks" }

@Composable
fun SportPage(nav: Nav) {
    DisposableEffect(Unit) { Host.opened.value = true; Host.kick(); Matcher.ensure(); Scores.tidy(); val stop = Scores.watch(); onDispose { stop() } }
    val settings by Scores.settings.collectAsState()
    val follows by Scores.follows.collectAsState()
    val all by Scores.games.collectAsState()
    val matched by Matcher.changed.collectAsState()
    LaunchedEffect(Unit) { if (follows.empty && !follows.setUp) { Scores.save(follows.copy(setUp = true)); nav.push(Screen.SportsTeams) } }

    val sports = remember(follows) { (follows.teams.map { it.league } + follows.leagues).mapNotNull { Leagues.byKey(it)?.sport }.distinct() }
    val views: List<View> = remember(follows, sports) {
        listOf(LiveView, AllView, MineView) + sports.map(::SportView) + follows.bar().mapNotNull(Leagues::byKey).map(::LeagueView) + listOf(TablesView, NetsView)
    }
    var viewKey by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("Live now") }
    val view = views.firstOrNull { it.label == viewKey } ?: LiveView
    var day by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(day) { if (day != LocalDate.now()) Scores.ask(day) }
    var choose by remember { mutableStateOf(false) }

    // The video window (setting): a game and channel playing on the right.
    var winGame by remember { mutableStateOf<Game?>(null) }
    var winCh by remember { mutableStateOf<Channel?>(null) }
    var winList by remember { mutableStateOf<List<Channel>>(emptyList()) }
    val scope = rememberCoroutineScope()
    fun watch(g: Game) = scope.launch {
        val hits = Matcher.channels(g).map { it.channel }
        if (hits.isEmpty()) return@launch
        if (settings.videoWindow && winCh?.key != hits.first().key) { winGame = g; winCh = hits.first(); winList = hits }
        else Host.play(nav, g, winCh ?: hits.first(), hits)
    }

    val list: List<Game> = remember(view, day, all, follows, settings, matched) {
        val dayGames = Scores.gamesOn(day, all)
        val shown = Scores.shownLeagues(settings, follows).map { it.key }
        val base = when (view) {
            LiveView -> Scores.liveNow(all) { (Matcher.cached(it)?.size ?: 0) > 0 }
            AllView -> dayGames.filter { it.league in shown }.sortedWith(compareBy({ shown.indexOf(it.league) }, { it.startMs }))
            MineView -> all.filter { follows.follows(it) || it.league in follows.bar() }
            is SportView -> dayGames.filter { Leagues.byKey(it.league)?.sport == view.sport }
            is LeagueView -> dayGames.filter { it.league == view.l.key }
            else -> emptyList()
        }
        if (settings.onlyOnMyChannels) base.filter { follows.follows(it) || (Matcher.cached(it)?.size ?: 1) > 0 } else base
    }
    LaunchedEffect(list) { Matcher.warm(list) }
    var guide by remember { mutableStateOf<List<Game>>(emptyList()) }
    LaunchedEffect(follows.words, matched) { guide = Matcher.guideGames(follows.words) }

    Inside(true) {
        Column(Modifier.fillMaxSize().skinPage().padding(start = 40.dp, end = 32.dp, top = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KitHeader("Sports", by = "${all.count { it.state == State.LIVE }} live")
                Spacer(Modifier.weight(1f))
                RefreshButton("${all.count { it.state == State.LIVE }} live")
            }
            LazyRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(views, key = { it.label }) { v -> KitButton(v.label, null, { viewKey = v.label }, on = v.label == view.label) }
            }
            if (view == AllView || view is SportView || view is LeagueView) DaysBar(day) { day = it }
            when (view) {
                TablesView -> Tables(nav, null)
                NetsView -> Networks()
                else -> Row(Modifier.fillMaxSize().padding(top = 12.dp)) {
                    LazyColumn(Modifier.weight(if (settings.videoWindow) 0.56f else 1f), verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 40.dp)) {
                        if (view == AllView) item(key = "choose") {
                            KitButton("Choose leagues · ${Scores.shownLeagues(settings, follows).size} on", null, { choose = true })
                        }
                        if (list.isEmpty() && !(view == MineView && guide.isNotEmpty())) item(key = "none") {
                            Text(when (view) { LiveView -> "Nothing live right now."; MineView -> "Nothing coming up for what you follow."; else -> "No games this day." },
                                color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.padding(vertical = 20.dp))
                        }
                        if (view == MineView) mine(nav, follows, settings, list, guide) { watch(it) }
                        else if (settings.videoWindow) items(list, key = { it.key }) { g ->
                            GameLine(g, settings.hideScores, Matcher.cached(g)?.size, { nav.push(Screen.SportsGame(g.key)) }, { watch(g) })
                        }
                        // Video window off: two cards a row across the page.
                        else items(list.chunked(2), key = { it.first().key }) { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { pair.forEach { g ->
                                GameLine(g, settings.hideScores, Matcher.cached(g)?.size, { nav.push(Screen.SportsGame(g.key)) }, { watch(g) }, Modifier.weight(1f))
                            }; if (pair.size == 1) Spacer(Modifier.weight(1f)) }
                        }
                    }
                    if (settings.videoWindow) Window(winGame?.let { w -> all.firstOrNull { it.key == w.key } ?: w }, winCh, settings.hideScores,
                        Modifier.weight(0.44f).padding(start = 20.dp))
                }
            }
        }
        if (choose) LeagueChooser { choose = false }
    }
}

@Composable
private fun DaysBar(day: LocalDate, pick: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val days = remember(today) { (-7..7).map { today.plusDays(it.toLong()) } }
    val state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = 4)
    LazyRow(Modifier.padding(top = 10.dp), state = state, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(days, key = { it.toEpochDay() }) { d ->
            val label = when (d) { today -> "Today"; today.minusDays(1) -> "Yesterday"; today.plusDays(1) -> "Tomorrow"
                else -> d.format(DateTimeFormatter.ofPattern("EEE d")) }
            KitButton(label, null, { pick(d) }, on = d == day)
        }
    }
}

/** The video window: the game in it, its latest score and channel; a hint when nothing plays. */
@Composable
private fun Window(g: Game?, c: Channel?, hide: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val shape = RoundedCornerShape(Kit.cardR)
        Box(Modifier.fillMaxWidth().aspectRatio(16 / 9f).clip(shape).background(Color.Black).border(1.dp, Kit.line, shape), contentAlignment = Alignment.Center) {
            if (c != null) MiniPlayer(c.url, c.headers, Modifier.fillMaxSize())
            else Text("Hold OK on a game to watch it here", color = Palette.TextDim, fontSize = 14.sp)
        }
        if (g != null) {
            Text(Host.title(g), color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(listOf(if (hide) "" else Host.scoreLine(g), stateText(g)).filter { it.isNotBlank() }.joinToString(" · "), color = Palette.TextDim, fontSize = 14.sp)
            c?.let { Text("On ${it.name} · hold OK again for full screen", color = Palette.TextDim, fontSize = 12.sp) }
        }
    }
}

/**
 * My Teams: on now and next across everything you follow, then a section per team (its season: last two, next three),
 * per top-bar league and followed league (the day's games), per followed name (the guide's shows).
 */
private fun androidx.compose.foundation.lazy.LazyListScope.mine(nav: Nav, f: Follows, s: Settings, all: List<Game>, guide: List<Game>, watch: (Game) -> Unit) {
    val now = System.currentTimeMillis()
    fun line(g: Game, k: String) = item(key = k) { GameLine(g, s.hideScores, Matcher.cached(g)?.size, { nav.push(Screen.SportsGame(g.key)) }, { watch(g) }) }
    fun head(t: String, k: String) = item(key = k) { KitHeader(t, Modifier.padding(top = 12.dp)) }
    val soon = (all.filter { f.follows(it) } + guide).filter { it.state == State.LIVE || it.state == State.PRE && it.startMs - now < 86_400_000 }.sortedBy { it.startMs }
    if (soon.isNotEmpty()) { head("On now and next", "h_soon"); soon.forEach { line(it, "soon" + it.key) } }
    f.teams.forEach { t ->
        item(key = "team" + t.key) {
            var season by remember(t.key) { mutableStateOf<List<Game>?>(null) }
            LaunchedEffect(t.key) { season = Scores.season(t) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) { Logo(t.logo, 24.dp); KitHeader("  " + t.name, by = Leagues.byKey(t.league)?.name) }
                val l = season
                if (l == null) Text("Loading…", color = Palette.TextDim, fontSize = 12.sp)
                else (l.filter { it.state == State.DONE }.takeLast(2) + l.filter { it.state != State.DONE }.take(3)).forEach { g ->
                    GameLine(g, s.hideScores, Matcher.cached(g)?.size, { nav.push(Screen.SportsGame(g.key)) }, { watch(g) }) }
            }
        }
    }
    (f.bar() + f.leagues).distinct().mapNotNull(Leagues::byKey).forEach { l ->
        val games = Scores.gamesOn(LocalDate.now(), all).filter { it.league == l.key }
        if (games.isNotEmpty()) { head(l.name, "hl" + l.key); games.forEach { line(it, "l" + l.key + it.key) } }
    }
    f.words.forEach { w ->
        val games = guide.filter { it.title.contains(w, ignoreCase = true) }
        head(w, "hw$w")
        if (games.isEmpty()) item(key = "nw$w") { Text("Nothing in your guide today or tomorrow.", color = Palette.TextDim, fontSize = 12.sp) }
        games.forEach { line(it, "w$w" + it.key) }
    }
}
