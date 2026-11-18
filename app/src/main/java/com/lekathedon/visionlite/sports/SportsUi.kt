package com.lekathedon.visionlite.sports

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lekathedon.visionlite.iptv.Iptv
import com.lekathedon.visionlite.ui.Button
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

private val Muted = Color(0x99FFFFFF)
private val LiveRed = Color(0xFFE5484D)
private val Gold = Color(0xFFD9DC8E)
private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())

/** Keeps Sports fetching while the caller is on screen (scores, states and channels stay fresh). */
@Composable
fun SportsViewer() {
    DisposableEffect(Unit) { Sports.viewing(); onDispose { Sports.stopViewing() } }
}

/** A minute tick, so live states and "starts in" stay current without a fetch. */
@Composable
private fun minuteTick(): Long {
    var t by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); t = System.currentTimeMillis() } }
    return t
}

/** Opens a channel full screen; Up / Down in the player then steps through this game's channels. */
private fun watch(nav: Nav, m: ChannelMatch, all: List<ChannelMatch>) {
    val list = Iptv.channelsByKey(all.map { it.channel.key }.distinct())
    Iptv.zapList = list
    list.firstOrNull { it.key == m.channel.key }?.let { Iptv.played(it) }
    nav.push(Screen.Player(m.channel.url, m.channel.name, m.channel.headers, type = "live"))
}

private fun leagueShort(ev: SportsEvent) = SportsCatalog.league(ev.leagueId)?.short ?: ""

private fun stateText(ev: SportsEvent, now: Long): String = when (ev.state) {
    GameState.LIVE -> ev.status?.takeIf { it.isNotBlank() } ?: "Live"
    GameState.POST -> ev.status?.takeIf { it.isNotBlank() } ?: "Final"
    else -> if (ev.hasTime) hm.format(Date(ev.startMs)) + if (ev.startMs - now in 1..3_600_000) " · in ${(ev.startMs - now) / 60_000} min" else "" else "This week"
}

/** A game as one line: league and state, then teams with logos and scores (or the event's title). */
@Composable
private fun GameLine(ev: SportsEvent, now: Long, channels: Int, f: Boolean, compact: Boolean = false) {
    val hide = Sports.settings.hideScores && ev.state != GameState.PRE
    val text = if (f) Color.Black else Color.White
    val dim = if (f) Color.DarkGray else Muted
    Column(Modifier.fillMaxWidth().background(if (f) Color.White else Color(0x12FFFFFF)).padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ev.state == GameState.LIVE) Box(Modifier.padding(end = 6.dp).size(7.dp).clip(RoundedCornerShape(50)).background(LiveRed))
            Text(stateText(ev, now), color = if (ev.state == GameState.LIVE) LiveRed else dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("  ${leagueShort(ev)}", color = dim, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
            if (channels > 0) Text("$channels channel" + if (channels == 1) "" else "s", color = if (f) Color.DarkGray else Gold, fontSize = 12.sp)
        }
        val sides = listOfNotNull(ev.away, ev.home)
        if (sides.size == 2) sides.forEach { s ->
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { Img(s.team.logo, 22.dp, 22.dp, Modifier.size(22.dp), ContentScale.Fit) }
                Text(s.team.name, color = text, fontSize = if (compact) 14.sp else 16.sp, fontWeight = if (s.winner) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).weight(1f))
                if (!hide && ev.state != GameState.PRE) Text(s.score.orEmpty(), color = text, fontSize = if (compact) 14.sp else 16.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Text(ev.title, color = text, fontSize = if (compact) 14.sp else 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp))
            (ev.note ?: ev.subtitle)?.takeIf { it.isNotBlank() }?.let { Text(it, color = dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** A channel showing the game: logo, name, what the guide says, quality; the one in the window says so. */
@Composable
private fun ChannelLine(m: ChannelMatch, playing: Boolean, f: Boolean) {
    Row(Modifier.fillMaxWidth().background(if (f) Color.White else if (playing) Color(0x33D9DC8E) else Color(0x12FFFFFF)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp, 28.dp), contentAlignment = Alignment.Center) { Img(m.channel.logo, 48.dp, 28.dp, Modifier.size(48.dp, 28.dp), ContentScale.Fit) }
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(m.channel.name, color = if (f) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(m.quality?.takeIf { it.isNotBlank() }, m.program?.title?.takeIf { it.isNotBlank() }, if (m.sure) null else "may be another game")
                .joinToString(" · ")
            val hint = if (playing) "Playing · OK again: full screen" else sub
            if (hint.isNotBlank()) Text(hint, color = if (f) Color.DarkGray else Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The small window with the game and channel under it (Vision's Sports / Live now video window). */
@Composable
private fun Window(ev: SportsEvent, playing: ChannelMatch?, modifier: Modifier = Modifier) {
    Column(modifier.width(480.dp)) {
        Box(Modifier.fillMaxWidth().height(270.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black)
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            MiniPlayer(playing?.channel?.url, playing?.channel?.headers ?: emptyMap(), Modifier.fillMaxSize())
            if (playing == null) Text("Pick a channel to try it here", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(ev.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        Text(playing?.let { "On ${it.channel.name} · OK on it again to watch full screen" } ?: "OK on a channel plays it here",
            color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------- the Sports page

/** Sports: yesterday / today / tomorrow; a game's channels on the right, tried in the window above them. */
@Composable
fun SportsScreen(nav: Nav) {
    SportsViewer()
    val events by Sports.events.collectAsState()
    val matches by Sports.matches.collectAsState()
    val status by Sports.status.collectAsState()
    val now = minuteTick()
    var day by rememberSaveable { mutableStateOf(0) }
    var mineOnly by rememberSaveable { mutableStateOf(false) }
    val follows = remember(events) { Sports.follows }
    val games = remember(events, matches, day, now / 60_000, mineOnly) { Sports.day(day, now).let { g -> if (mineOnly) g.filter { isMine(it, follows) } else g } }
    var focused by remember { mutableStateOf<SportsEvent?>(null) }
    var open by remember { mutableStateOf<SportsEvent?>(null) }
    var playing by remember { mutableStateOf<ChannelMatch?>(null) }
    val firstGame = remember { FocusRequester() }
    val firstChannel = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(games.isNotEmpty()) { if (games.isNotEmpty() && !focusedOnce) { focusedOnce = true; runCatching { firstGame.requestFocus() } } }
    val shown = open ?: focused
    val chans = shown?.let { matches[it.id].orEmpty() }.orEmpty()
    LaunchedEffect(open) { if (open != null) { delay(60); runCatching { firstChannel.requestFocus() } } }
    BackHandler(enabled = open != null) { open = null; playing = null }

    Column(Modifier.fillMaxSize().background(LocalLook.current.page).padding(start = 36.dp, end = 36.dp, top = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sports", color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp))
            listOf(-1 to "Yesterday", 0 to "Today", 1 to "Tomorrow").forEach { (d, label) ->
                Focusable({ day = d; open = null; playing = null }, shape = RoundedCornerShape(50)) { f ->
                    Text(label, color = if (f) Color.Black else Color.White, fontSize = 14.sp, fontWeight = if (day == d) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.background(if (f) Color.White else if (day == d) Color(0x33FFFFFF) else Color.Transparent).padding(horizontal = 14.dp, vertical = 6.dp))
                }
            }
            val live = games.count { it.state == GameState.LIVE }
            Text(status.ifEmpty { "${games.size} games" + if (live > 0) " · $live live" else "" }, color = Palette.TextDim, fontSize = 13.sp,
                modifier = Modifier.weight(1f).padding(start = 10.dp))
            Focusable({ mineOnly = !mineOnly; open = null; playing = null }, shape = RoundedCornerShape(50)) { f ->
                Text("My Teams", color = if (f) Color.Black else Color.White, fontSize = 14.sp, fontWeight = if (mineOnly) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.background(if (f) Color.White else if (mineOnly) Color(0x33FFFFFF) else Color.Transparent).padding(horizontal = 14.dp, vertical = 6.dp))
            }
            Button("Teams", { nav.push(com.lekathedon.visionlite.ui.Screen.SportsTeams) })
            shown?.leagueId?.takeIf { SportsCatalog.league(it)?.espnPath != null }?.let { id ->
                Button("Table", { nav.push(com.lekathedon.visionlite.ui.Screen.Standings(id)) })
            }
            Button("Refresh", { Sports.refreshNow() })
        }
        Row(Modifier.fillMaxSize().padding(top = 14.dp)) {
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (games.isEmpty()) item {
                    Text(if (status.isNotEmpty() || events.isEmpty()) "Getting the games…" else if (mineOnly) "None of your teams play this day. Teams: pick them." else "No games this day.", color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.padding(12.dp))
                }
                items(games, key = { it.id }) { ev ->
                    Focusable({ open = ev; playing = null }, if (ev == games.first()) Modifier.focusRequester(firstGame) else Modifier,
                        onFocus = { focused = ev; if (open != null && open != ev) { open = null; playing = null } }) { f ->
                        GameLine(ev, now, matches[ev.id]?.size ?: 0, f)
                    }
                }
            }
            Column(Modifier.padding(start = 20.dp).width(480.dp).fillMaxHeight()) {
                if (shown != null) {
                    Window(shown, playing)
                    Text(if (!Iptv.hasChannels()) "Add a Live TV playlist to see the channels showing games." else if (chans.isEmpty()) "No channel found for this one yet."
                        else "Channels", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(chans, key = { it.channel.key }) { m ->
                            Focusable({ if (playing?.channel?.key == m.channel.key) watch(nav, m, chans) else { if (open == null) open = shown; playing = m } },
                                if (m == chans.first()) Modifier.focusRequester(firstChannel) else Modifier) { f ->
                                ChannelLine(m, playing?.channel?.key == m.channel.key, f)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- the clock's panel

/**
 * The clock's panel (Vision's Legacy clock): Live now (games really under way), OK on a game lists its channels and
 * the first OK on a channel plays it in the window on the left, OK again watches it full screen. Back: the games again,
 * then closes.
 */
@Composable
fun ClockPanel(nav: Nav, onDismiss: () -> Unit) {
    SportsViewer()
    val events by Sports.events.collectAsState()
    val matches by Sports.matches.collectAsState()
    val status by Sports.status.collectAsState()
    val now = minuteTick()
    val live = remember(events, matches, now / 60_000) { Sports.liveNow(now) }
    var game by remember { mutableStateOf<SportsEvent?>(null) }
    var playing by remember { mutableStateOf<ChannelMatch?>(null) }
    var returnTo by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    val rowFocus = remember { HashMap<String, FocusRequester>() }
    LaunchedEffect(game, live.isNotEmpty()) {
        delay(60)
        val target = returnTo?.let { rowFocus[it] }
        runCatching { (target ?: first).requestFocus() }
        returnTo = null
    }
    val back: () -> Unit = { val g = game; if (g != null) { game = null; playing = null; returnTo = g.id } else onDismiss() }
    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))) {
            game?.let { Window(it, playing, Modifier.align(Alignment.CenterStart).padding(start = 40.dp)) }
            Column(Modifier.align(Alignment.CenterEnd).padding(12.dp).width(388.dp).fillMaxHeight().clip(RoundedCornerShape(18.dp))
                .background(Color(0xF2161618)).border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp)).padding(top = 20.dp)) {
                Text("${live.size} LIVE NOW", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, modifier = Modifier.padding(horizontal = 24.dp))
                Text(game?.title ?: "Live now", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                val g = game
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (g == null) {
                        if (live.isEmpty()) item {
                            Text(when { status.isNotEmpty() -> status; events.isEmpty() -> "Getting the scores…"; else -> "Nothing live right now." },
                                color = Muted, fontSize = 13.sp, modifier = Modifier.padding(10.dp))
                        }
                        items(live, key = { it.id }) { ev ->
                            val fr = rowFocus.getOrPut(ev.id) { FocusRequester() }
                            Focusable({ game = ev; playing = null }, Modifier.focusRequester(fr).then(if (ev == live.first()) Modifier.focusRequester(first) else Modifier)) { f ->
                                Column {
                                    GameLine(ev, now, 0, f, compact = true)
                                    LogoChips(matches[ev.id].orEmpty())
                                }
                            }
                        }
                    } else {
                        val chans = matches[g.id].orEmpty()
                        if (chans.isEmpty()) item {
                            Text(if (Iptv.hasChannels()) "No channel found for this game yet." else "Add a Live TV playlist to see the channels.",
                                color = Muted, fontSize = 13.sp, modifier = Modifier.padding(10.dp))
                        }
                        items(chans, key = { it.channel.key }) { m ->
                            Focusable({ if (playing?.channel?.key == m.channel.key) { onDismiss(); watch(nav, m, chans) } else playing = m },
                                if (m == chans.first()) Modifier.focusRequester(first) else Modifier) { f ->
                                ChannelLine(m, playing?.channel?.key == m.channel.key, f)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Under a Live now game: the channels it's on, as small logos with names (not focusable; OK on the game lists them). */
@Composable
private fun LogoChips(list: List<ChannelMatch>) {
    if (list.isEmpty()) return
    val shown = list.distinctBy { it.channel.key }
    Row(Modifier.fillMaxWidth().background(Color(0x0AFFFFFF)).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        shown.take(3).forEach { m ->
            Row(Modifier.padding(end = 8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2A2A2E)).padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (!m.channel.logo.isNullOrBlank()) Img(m.channel.logo, 18.dp, 18.dp, Modifier.size(18.dp), ContentScale.Fit)
                Spacer(Modifier.width(4.dp))
                Text(m.channel.name, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 90.dp))
            }
        }
        if (shown.size > 3) Text("+${shown.size - 3}", color = Muted, fontSize = 10.sp)
    }
}
