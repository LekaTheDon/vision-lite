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
import androidx.compose.runtime.produceState
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

/** Vision's clock tabs (LegacyClock PanelTab), same order; YouTube and Music only when YouTube is signed in. */
private enum class PanelTab(val title: String, val chip: String) {
    SAVED("Saved", "Saved"), ACTIVITY("Activity", "Activity"), LIVE("Live now", "Live"), YOUTUBE("YouTube", "YouTube"),
    MUSIC("Music", "Music"), NOTICES("Notices", "Notices"), CALENDAR("Calendar", "Calendar")
}

/** One row of a list tab: poster, title, one line under it; OK opens it. */
private class PanelLine(val key: String, val title: String, val sub: String, val poster: String?, val header: String? = null, val open: () -> Unit)

private fun ago(ms: Long, now: Long): String {
    val m = (now - ms) / 60_000
    return when { m < 1 -> "Just now"; m < 60 -> "${m} min ago"; m < 1440 -> "${m / 60} h ago"; m < 2880 -> "Yesterday"; else -> "${m / 1440} days ago" }
}
private fun isoMs(s: String): Long = runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrDefault(0L)
private val dayFmt = SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault())

/**
 * The clock's panel (Vision's Legacy clock panel): tabs Saved (continue watching + your saves), Activity (what you
 * watched last, Trakt), Live now (games under way; OK lists channels, first OK on a channel plays it in the window,
 * OK again full screen), YouTube (your recommendations), Music (a random batch from your YouTube subscriptions),
 * Notices (new episodes of the shows you follow), Calendar (the next 14 days). Each tab reads only when it's opened
 * (YouTube / Music after a short stay, as Vision). Back: the games again (Live), then closes.
 */
@Composable
fun ClockPanel(nav: Nav, onDismiss: () -> Unit) {
    val app = com.lekathedon.visionlite.App.instance
    val ytOn by com.lekathedon.visionlite.youtube.Yt.signed.collectAsState()
    val tabs = remember(ytOn) { PanelTab.entries.filter { (it != PanelTab.YOUTUBE && it != PanelTab.MUSIC) || ytOn } }
    var tab by rememberSaveable { mutableStateOf(PanelTab.SAVED) }
    LaunchedEffect(tabs) { if (tab !in tabs) tab = PanelTab.SAVED }
    val events by Sports.events.collectAsState()
    val matches by Sports.matches.collectAsState()
    val status by Sports.status.collectAsState()
    val now = minuteTick()
    val live = remember(events, matches, now / 60_000) { Sports.liveNow(now) }
    var game by remember { mutableStateOf<SportsEvent?>(null) }
    var playing by remember { mutableStateOf<ChannelMatch?>(null) }
    var returnTo by remember { mutableStateOf<String?>(null) }
    val tabFocus = remember { FocusRequester() }
    val first = remember { FocusRequester() }
    val rowFocus = remember { HashMap<String, FocusRequester>() }
    if (tab == PanelTab.LIVE) SportsViewer()
    LaunchedEffect(tab) { if (tab != PanelTab.LIVE) { game = null; playing = null } }
    LaunchedEffect(Unit) { delay(60); runCatching { tabFocus.requestFocus() } }
    LaunchedEffect(game) {
        if (game == null && returnTo == null) return@LaunchedEffect
        delay(60)
        runCatching { (returnTo?.let { rowFocus[it] } ?: first).requestFocus() }
        returnTo = null
    }

    // The list tabs, each read when opened.
    val cw by produceState(emptyList<com.lekathedon.visionlite.trakt.Resume>(), tab) {
        if (tab == PanelTab.SAVED) value = runCatching { app.trakt.continueWatching() }.getOrDefault(emptyList())
    }
    val history by produceState(emptyList<com.lekathedon.visionlite.trakt.HistoryItem>(), tab) {
        if (tab == PanelTab.ACTIVITY) value = runCatching { app.trakt.history() }.getOrDefault(emptyList())
    }
    var ytRound by remember { mutableStateOf(0) }
    val yt by produceState<List<com.lekathedon.visionlite.youtube.YtItem>?>(null, tab, ytRound) {
        if (tab != PanelTab.YOUTUBE) return@produceState
        delay(600)
        value = runCatching { com.lekathedon.visionlite.youtube.Yt.home().flatMap { it.items }.filter { it.videoId != null }.distinctBy { it.videoId }.take(40) }.getOrDefault(emptyList())
    }
    val music by produceState<List<com.lekathedon.visionlite.youtube.YtItem>?>(null, tab, ytRound) {
        if (tab != PanelTab.MUSIC) return@produceState
        delay(600)
        value = runCatching { com.lekathedon.visionlite.youtube.Yt.subscriptions().flatMap { it.items }.filter { it.videoId != null }.distinctBy { it.videoId }.shuffled().take(30) }.getOrDefault(emptyList())
    }
    val coming by com.lekathedon.visionlite.home.ComingUp.items.collectAsState()
    // Vision's windows on the left: the first OK on a video or a song plays it there, OK again goes full screen.
    // Back closes the window (music stops); leaving the tab closes it too.
    var ytShown by remember { mutableStateOf<com.lekathedon.visionlite.youtube.YtItem?>(null) }
    var musicShown by remember { mutableStateOf<String?>(null) }
    var musicFull by remember { mutableStateOf(false) }
    LaunchedEffect(tab) {
        if (tab != PanelTab.YOUTUBE) ytShown = null
        if (tab != PanelTab.MUSIC && musicShown != null) { com.lekathedon.visionlite.music.MusicPlayer.stop(); musicShown = null }
    }
    // The panel closing stops music it started, unless it went full screen.
    DisposableEffect(Unit) { onDispose { if (musicShown != null && !musicFull) com.lekathedon.visionlite.music.MusicPlayer.stop() } }
    fun detail(type: String, id: String, name: String) { onDismiss(); nav.push(Screen.Detail(type, id, name)) }
    fun epText(s: Int?, e: Int?) = if (s != null && e != null) "S$s E$e" else null

    val lines: List<PanelLine> = when (tab) {
        PanelTab.SAVED -> {
            val saves = com.lekathedon.visionlite.library.Saves.all()
            cw.mapIndexed { i, r -> PanelLine("cw:${r.streamId}", r.name, listOfNotNull(epText(r.season, r.episode), if (r.progress > 0f) "${r.progress.toInt()}% watched" else "Up next").joinToString(" · "),
                com.lekathedon.visionlite.library.posterOf(r.imdb), if (i == 0) "CONTINUE WATCHING" else null) {
                onDismiss(); nav.push(Screen.Streams(r.type, r.streamId, r.label, r.imdb)) } } +
            saves.mapIndexed { i, sv -> PanelLine("sv:${sv.id}", sv.name, if (sv.type == "series") "Series" else "Movie",
                sv.poster ?: com.lekathedon.visionlite.library.posterOf(sv.id.substringBefore(':')), if (i == 0) "SAVED" else null) { detail(sv.type, sv.id, sv.name) } }
        }
        PanelTab.ACTIVITY -> history.mapNotNull { h ->
            val at = isoMs(h.watchedAt)
            when {
                h.movie != null -> h.movie.ids.imdb?.let { id -> PanelLine("act:${h.id}", h.movie.title, "${ago(at, now)} · Movie", com.lekathedon.visionlite.library.posterOf(id)) { detail("movie", id, h.movie.title) } }
                h.show != null && h.episode != null -> h.show.ids.imdb?.let { id -> PanelLine("act:${h.id}", h.show.title,
                    listOfNotNull(ago(at, now), epText(h.episode.season, h.episode.number), h.episode.title).joinToString(" · "), com.lekathedon.visionlite.library.posterOf(id)) { detail("series", id, h.show.title) } }
                else -> null
            }
        }
        PanelTab.YOUTUBE -> yt.orEmpty().map { v -> PanelLine("yt:${v.videoId}", v.title,
            if (ytShown?.videoId == v.videoId) "Playing on the left · OK again: full screen" else v.sub, v.image) {
            if (ytShown?.videoId == v.videoId) { onDismiss(); nav.push(Screen.YtPlay(v.videoId!!, v.title)) }
            else { com.lekathedon.visionlite.music.MusicPlayer.pause(); ytShown = v } } }
        PanelTab.MUSIC -> music.orEmpty().let { list -> list.mapIndexed { i, v -> PanelLine("m:${v.videoId}", v.title,
            if (musicShown == v.videoId) "Playing on the left · OK again: full screen" else v.sub, v.image) {
            if (musicShown == v.videoId) { musicFull = true; onDismiss(); nav.push(Screen.Music) }
            else { com.lekathedon.visionlite.music.MusicPlayer.play(list, i); musicShown = v.videoId } } } }
        PanelTab.NOTICES -> coming.filter { it.isNew }.sortedByDescending { it.at }.map { c ->
            PanelLine("n:${c.key}", c.name, listOfNotNull(if (c.type == "movie") "Now available to stream" else "New episode", epText(c.season, c.episode), c.epTitle, ago(c.at, now)).joinToString(" · "),
                c.image ?: com.lekathedon.visionlite.library.posterOf(c.imdb)) { detail(c.type, c.imdb, c.name) } }
        PanelTab.CALENDAR -> coming.filter { it.at in (now - 86_400_000L)..(now + 14 * 86_400_000L) }.sortedBy { it.at }.map { c ->
            PanelLine("c:${c.key}", c.name, listOfNotNull(dayFmt.format(Date(c.at)), epText(c.season, c.episode), c.epTitle).joinToString(" · "),
                c.image ?: com.lekathedon.visionlite.library.posterOf(c.imdb)) { detail(c.type, c.imdb, c.name) } }
        PanelTab.LIVE -> emptyList()
    }
    val kicker = when (tab) {
        PanelTab.SAVED -> "${(cw.map { it.imdb } + com.lekathedon.visionlite.library.Saves.all().map { it.id }).toSet().size} TITLES · ${cw.size} TO RESUME"
        PanelTab.ACTIVITY -> "${history.size} RECENTLY WATCHED"
        PanelTab.LIVE -> "${live.size} LIVE NOW"
        PanelTab.YOUTUBE -> if (yt.isNullOrEmpty()) "RECOMMENDED" else "${yt!!.size} RECOMMENDED"
        PanelTab.MUSIC -> if (music.isNullOrEmpty()) "FROM YOUR SUBSCRIPTIONS" else "${music!!.size} FROM YOUR SUBSCRIPTIONS"
        PanelTab.NOTICES -> "${lines.size} NEW"
        PanelTab.CALENDAR -> "NEXT 14 DAYS"
    }
    val empty = when (tab) {
        PanelTab.SAVED -> "Nothing saved or to resume yet."
        PanelTab.ACTIVITY -> if (app.trakt.connected.value == null) "Sign in to Trakt to see what you watched." else "Nothing watched yet."
        PanelTab.YOUTUBE -> if (yt == null) "Loading YouTube…" else "Nothing from YouTube right now."
        PanelTab.MUSIC -> if (music == null) "Loading your subscriptions…" else "Nothing from your subscriptions right now."
        PanelTab.NOTICES -> "No new episodes in the last 10 days."
        PanelTab.CALENDAR -> "Nothing airing in the next 14 days."
        PanelTab.LIVE -> ""
    }

    val back: () -> Unit = {
        val g = game
        when {
            g != null -> { game = null; playing = null; returnTo = g.id }
            musicShown != null -> { com.lekathedon.visionlite.music.MusicPlayer.stop(); musicShown = null }
            ytShown != null -> ytShown = null
            else -> onDismiss()
        }
    }
    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))) {
            game?.let { Window(it, playing, Modifier.align(Alignment.CenterStart).padding(start = 40.dp)) }
            ytShown?.let { YtWindow(it, Modifier.align(Alignment.CenterStart).padding(start = 40.dp)) }
            if (musicShown != null) MusicWindow(Modifier.align(Alignment.CenterStart).padding(start = 40.dp))
            Column(Modifier.align(Alignment.CenterEnd).padding(12.dp).width(388.dp).fillMaxHeight().clip(RoundedCornerShape(18.dp))
                .background(Color(0xF2161618)).border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp)).padding(top = 20.dp)) {
                Text(kicker, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, modifier = Modifier.padding(horizontal = 24.dp))
                Text(if (tab == PanelTab.LIVE) game?.title ?: tab.title else tab.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                // Tabs: focus moving over a tab opens it (as Vision); Down goes into its list.
                androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(tabs, key = { it.name }) { t ->
                        Focusable({ if (t == PanelTab.YOUTUBE || t == PanelTab.MUSIC) ytRound++ }, if (t == tab) Modifier.focusRequester(tabFocus) else Modifier,
                            RoundedCornerShape(50), onFocus = { if (tab != t) tab = t }) { f ->
                            Text(t.chip, color = if (f) Color.Black else if (t == tab) Color.White else Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.background(if (f) Color.White else if (t == tab) Color(0x26FFFFFF) else Color.Transparent).padding(horizontal = 10.dp, vertical = 5.dp))
                        }
                    }
                }
                val g = game
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (tab != PanelTab.LIVE) {
                        if (lines.isEmpty()) item { Text(empty, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(10.dp)) }
                        items(lines, key = { it.key }) { l ->
                            Column {
                                l.header?.let { Text(it, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 4.dp)) }
                                Focusable(l.open, if (l == lines.first()) Modifier.focusRequester(first) else Modifier) { f ->
                                    Row(Modifier.fillMaxWidth().background(if (f) Color.White else Color(0x12FFFFFF)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        val wide = tab == PanelTab.YOUTUBE || tab == PanelTab.MUSIC
                                        Box(Modifier.size(if (wide) 64.dp else 32.dp, 48.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x22FFFFFF))) { Img(l.poster, if (wide) 64.dp else 32.dp, 48.dp) }
                                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                            Text(l.title, color = if (f) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(l.sub, color = if (f) Color.DarkGray else Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    } else if (g == null) {
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

/** The clock's YouTube window (Vision's YouTubePreviewWindow): the video plays with sound, 16:9, title under it. */
@Composable
private fun YtWindow(v: com.lekathedon.visionlite.youtube.YtItem, modifier: Modifier) {
    val s by androidx.compose.runtime.produceState<com.lekathedon.visionlite.youtube.YtStreams?>(null, v.videoId) {
        value = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.lekathedon.visionlite.youtube.Yt.streams(v.videoId!!, 1080) } }.getOrNull()
    }
    Column(modifier.width(560.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xF2161618))) {
        Box(Modifier.fillMaxWidth().height(315.dp).background(Color.Black)) {
            Img(v.image, 560.dp, 315.dp, Modifier.fillMaxSize())
            val url = s?.video ?: s?.hls
            if (url != null) com.lekathedon.visionlite.trailer.TrailerVideo(url, false, Modifier.fillMaxSize(), s?.audio?.takeIf { s?.video != null }) {}
        }
        Text(v.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp))
        Text(v.sub, color = Muted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 12.dp))
    }
}

/** The clock's Music window (Vision's MusicPreviewWindow): the song's art, title and artist, playing state. Back stops it. */
@Composable
private fun MusicWindow(modifier: Modifier) {
    val now by com.lekathedon.visionlite.music.MusicPlayer.now.collectAsState()
    val n = now
    Column(modifier.width(420.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xF2161618)).padding(16.dp)) {
        Box(Modifier.fillMaxWidth().height(236.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x22FFFFFF))) {
            n?.art?.let { Img(it, 388.dp, 236.dp, Modifier.fillMaxSize()) }
        }
        Text(n?.title ?: "Loading…", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
        Text(n?.let { "${it.artist} · ${if (it.playing) "Playing" else "Paused"}" } ?: "", color = Muted, fontSize = 12.sp, maxLines = 1)
        Text("Back stops the music · OK again: full screen", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
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
