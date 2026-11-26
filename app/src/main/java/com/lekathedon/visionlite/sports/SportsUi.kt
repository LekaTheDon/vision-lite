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
import androidx.compose.foundation.layout.aspectRatio
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

private val Muted get() = Palette.TextDim
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

/** Opens a channel full screen; Up / Down in the player then steps through this game's channels; failover armed. */
private fun clockWatch(nav: Nav, ev: SportsEvent, m: ChannelMatch, all: List<ChannelMatch>) = watchGame(nav, ev, m, all)

private fun leagueShort(ev: SportsEvent) = SportsCatalog.league(ev.leagueId)?.short ?: ""

private fun stateText(ev: SportsEvent, now: Long): String = when (ev.state) {
    GameState.LIVE -> ev.status?.takeIf { it.isNotBlank() } ?: "Live"
    GameState.POST -> ev.status?.takeIf { it.isNotBlank() } ?: "Final"
    else -> if (ev.hasTime) hm.format(Date(ev.startMs)) + if (ev.startMs - now in 1..3_600_000) " · in ${(ev.startMs - now) / 60_000} min" else "" else "This week"
}

/** A game as one line: league and state, then teams with logos and scores (or the event's title). */
@Composable
private fun ClockGameLine(ev: SportsEvent, now: Long, channels: Int, f: Boolean, compact: Boolean = false) {
    val hide = Sports.settings.hideScores && ev.state != GameState.PRE
    val text = if (f) com.lekathedon.visionlite.ui.kitFocus.second else Palette.Text
    val dim = if (f) text.copy(alpha = 0.7f) else Palette.TextDim
    Column(Modifier.fillMaxWidth().background(if (f) com.lekathedon.visionlite.ui.kitFocus.first else com.lekathedon.visionlite.ui.Kit.rowFill).padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ev.state == GameState.LIVE) Box(Modifier.padding(end = 6.dp).size(7.dp).clip(RoundedCornerShape(50)).background(LiveRed))
            Text(stateText(ev, now), color = if (ev.state == GameState.LIVE) LiveRed else dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("  ${leagueShort(ev)}", color = dim, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
            if (channels > 0) Text("$channels channel" + if (channels == 1) "" else "s", color = if (f) dim else com.lekathedon.visionlite.ui.Kit.accent, fontSize = 12.sp)
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
private fun ClockChannelLine(m: ChannelMatch, playing: Boolean, f: Boolean) {
    val ink = if (f) com.lekathedon.visionlite.ui.kitFocus.second else Palette.Text
    Row(Modifier.fillMaxWidth().background(if (f) com.lekathedon.visionlite.ui.kitFocus.first else if (playing) com.lekathedon.visionlite.ui.Kit.accent.copy(alpha = 0.2f) else com.lekathedon.visionlite.ui.Kit.rowFill).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp, 28.dp), contentAlignment = Alignment.Center) { Img(m.channel.logo, 48.dp, 28.dp, Modifier.size(48.dp, 28.dp), ContentScale.Fit) }
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(m.channel.name, color = ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(m.quality?.takeIf { it.isNotBlank() }, m.program?.title?.takeIf { it.isNotBlank() }, if (m.sure) null else "may be another game")
                .joinToString(" · ")
            val hint = if (playing) "Playing · OK again: full screen" else sub
            if (hint.isNotBlank()) Text(hint, color = if (f) ink.copy(alpha = 0.7f) else Palette.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The small window with the game and channel under it (Vision's Sports / Live now video window). */
@Composable
private fun ClockWindow(ev: SportsEvent, playing: ChannelMatch?, modifier: Modifier = Modifier) {
    Column(modifier.widthIn(max = 480.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(Color.Black)
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            MiniPlayer(playing?.channel?.url, playing?.channel?.headers ?: emptyMap(), Modifier.fillMaxSize())
            if (playing == null) Text("Pick a channel to try it here", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(ev.title, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        Text(playing?.let { "On ${it.channel.name} · OK on it again to watch full screen" } ?: "OK on a channel plays it here",
            color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}


// ---------------------------------------------------------------- the clock's panel

/** Vision's clock tabs (LegacyClock PanelTab), same order (YouTube and Music removed 2026-11-26: Debrid + IPTV only). */
private enum class PanelTab(val title: String, val chip: String) {
    SAVED("Saved", "Saved"), ACTIVITY("Activity", "Activity"), LIVE("Live now", "Live"),
    NOTICES("Notices", "Notices"), CALENDAR("Calendar", "Calendar")
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
    val tabs = PanelTab.entries
    var tab by rememberSaveable { mutableStateOf(PanelTab.SAVED) }
    LaunchedEffect(tabs) { if (tab !in tabs) tab = PanelTab.SAVED }
    val events by Sports.events.collectAsState()
    val matches by Sports.matches.collectAsState()
    val status by Sports.status.collectAsState()
    val refresh by Sports.liveRefresh.collectAsState()
    val searching by Sports.searching.collectAsState()
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
    // Opening a game swaps the list under the focused row: for a moment focus lands on the first tab ("Saved"), which
    // used to switch tab and close the game at once (user 2026-11-24: the window showed for a blink, then dropped).
    // While the list changes, tabs gaining focus don't switch; focus goes to the new list (or back to the Live tab).
    var holdTabs by remember { mutableStateOf(0L) }
    LaunchedEffect(game) {
        if (game == null && returnTo == null) return@LaunchedEffect
        delay(60)
        val ok = runCatching { (returnTo?.let { rowFocus[it] } ?: first).requestFocus() }.isSuccess
        if (!ok) runCatching { tabFocus.requestFocus() }
        returnTo = null
        delay(200); holdTabs = 0L
    }
    // A game opened: its channels are looked for again now (user 2026-11-24), the list fills in as they're found.
    LaunchedEffect(game?.id) { game?.let { g -> runCatching { Sports.matchOne(g) } } }
    // Refresh finished: the live games are shown again with focus on the first (the chip when there are none).
    var refreshAsked by remember { mutableStateOf(false) }
    LaunchedEffect(refresh.busy) {
        if (refresh.busy || !refreshAsked) return@LaunchedEffect
        refreshAsked = false
        delay(80)
        if (game == null && tab == PanelTab.LIVE) runCatching { first.requestFocus() }
    }

    // The list tabs, each read when opened.
    val cw by produceState(emptyList<com.lekathedon.visionlite.trakt.Resume>(), tab) {
        if (tab == PanelTab.SAVED) value = runCatching { app.trakt.continueWatching() }.getOrDefault(emptyList())
    }
    val history by produceState(emptyList<com.lekathedon.visionlite.trakt.HistoryItem>(), tab) {
        if (tab == PanelTab.ACTIVITY) value = runCatching { app.trakt.history() }.getOrDefault(emptyList())
    }
    val comingAll by com.lekathedon.visionlite.home.ComingUp.items.collectAsState()
    // Saved, Activity, Notices and Calendar keep to the hub Home shows (user 2026-11-24: no Films & TV in Anime's and
    // the other way round); Live now is shared.
    val panelHub by com.lekathedon.visionlite.anime.Hubs.content.collectAsState()
    com.lekathedon.visionlite.anime.AnimeIds.changed.collectAsState().value   // a title moved hub: lists redraw
    fun mine(id: String) = com.lekathedon.visionlite.anime.Hubs.keepsId(panelHub, id)
    val coming = comingAll.filter { mine(it.imdb) }
    val cwMine = cw.filter { mine(it.imdb) }
    val savesMine = com.lekathedon.visionlite.library.Saves.all().filter { mine(it.id) }
    val historyMine = history.filter { h -> (h.movie?.ids?.imdb ?: h.show?.ids?.imdb)?.let { mine(it) } == true }
    fun detail(type: String, id: String, name: String) { onDismiss(); nav.push(Screen.Detail(type, id, name)) }
    fun epText(s: Int?, e: Int?) = if (s != null && e != null) "S$s E$e" else null

    val lines: List<PanelLine> = when (tab) {
        PanelTab.SAVED -> {
            val saves = savesMine
            cwMine.mapIndexed { i, r -> PanelLine("cw:${r.streamId}", r.name, listOfNotNull(epText(r.season, r.episode), if (r.progress > 0f) "${r.progress.toInt()}% watched" else "Up next").joinToString(" · "),
                com.lekathedon.visionlite.library.posterOf(r.imdb), if (i == 0) "CONTINUE WATCHING" else null) {
                onDismiss(); nav.push(Screen.Streams(r.type, r.streamId, r.label, r.imdb)) } } +
            saves.mapIndexed { i, sv -> PanelLine("sv:${sv.id}", sv.name, if (sv.type == "series") "Series" else "Movie",
                sv.poster ?: com.lekathedon.visionlite.library.posterOf(sv.id.substringBefore(':')), if (i == 0) "SAVED" else null) { detail(sv.type, sv.id, sv.name) } }
        }
        PanelTab.ACTIVITY -> historyMine.mapNotNull { h ->
            val at = isoMs(h.watchedAt)
            when {
                h.movie != null -> h.movie.ids.imdb?.let { id -> PanelLine("act:${h.id}", h.movie.title, "${ago(at, now)} · Movie", com.lekathedon.visionlite.library.posterOf(id)) { detail("movie", id, h.movie.title) } }
                h.show != null && h.episode != null -> h.show.ids.imdb?.let { id -> PanelLine("act:${h.id}", h.show.title,
                    listOfNotNull(ago(at, now), epText(h.episode.season, h.episode.number), h.episode.title).joinToString(" · "), com.lekathedon.visionlite.library.posterOf(id)) { detail("series", id, h.show.title) } }
                else -> null
            }
        }
        PanelTab.NOTICES -> coming.filter { it.isNew }.sortedByDescending { it.at }.map { c ->
            PanelLine("n:${c.key}", c.name, listOfNotNull(if (c.type == "movie") "Now available to stream" else "New episode", epText(c.season, c.episode), c.epTitle, ago(c.at, now)).joinToString(" · "),
                c.image ?: com.lekathedon.visionlite.library.posterOf(c.imdb)) { detail(c.type, c.imdb, c.name) } }
        PanelTab.CALENDAR -> coming.filter { it.at in (now - 86_400_000L)..(now + 14 * 86_400_000L) }.sortedBy { it.at }.map { c ->
            PanelLine("c:${c.key}", c.name, listOfNotNull(dayFmt.format(Date(c.at)), epText(c.season, c.episode), c.epTitle).joinToString(" · "),
                c.image ?: com.lekathedon.visionlite.library.posterOf(c.imdb)) { detail(c.type, c.imdb, c.name) } }
        PanelTab.LIVE -> emptyList()
    }
    val kicker = when (tab) {
        PanelTab.SAVED -> "${panelHub.label.uppercase()} · ${(cwMine.map { it.imdb } + savesMine.map { it.id }).toSet().size} TITLES · ${cwMine.size} TO RESUME"
        PanelTab.ACTIVITY -> "${panelHub.label.uppercase()} · ${historyMine.size} RECENTLY WATCHED"
        PanelTab.LIVE -> "${live.size} LIVE NOW"
        PanelTab.NOTICES -> "${panelHub.label.uppercase()} · ${lines.size} NEW"
        PanelTab.CALENDAR -> "${panelHub.label.uppercase()} · NEXT 14 DAYS"
    }
    val empty = when (tab) {
        PanelTab.SAVED -> "Nothing saved or to resume in ${panelHub.label} yet."
        PanelTab.ACTIVITY -> if (app.trakt.connected.value == null) "Sign in to Trakt to see what you watched." else "Nothing watched yet."
        PanelTab.NOTICES -> "No new episodes in the last 10 days."
        PanelTab.CALENDAR -> "Nothing airing in the next 14 days."
        PanelTab.LIVE -> ""
    }

    val back: () -> Unit = {
        val g = game
        when {
            g != null -> { holdTabs = System.currentTimeMillis(); game = null; playing = null; returnTo = g.id }
            else -> onDismiss()
        }
    }
    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (com.lekathedon.visionlite.ui.Theme.skin == com.lekathedon.visionlite.ui.Skin.OLED) 0.7f else 0.45f))) {
            // The small window lives in the space left of the panel (412 dp), centred there, never under it (user 2026-11-24).
            Box(Modifier.fillMaxHeight().fillMaxWidth().padding(start = 24.dp, end = 436.dp), contentAlignment = Alignment.Center) {
                game?.let { ClockWindow(it, playing) }
            }
            // The panel wears the look and skin (the clock on Home keeps its own shape).
            val style = com.lekathedon.visionlite.ui.Kit.style
            Column(Modifier.align(Alignment.CenterEnd).padding(12.dp).width(400.dp).fillMaxHeight().clip(RoundedCornerShape(com.lekathedon.visionlite.ui.Kit.cardR))
                .background(com.lekathedon.visionlite.ui.Kit.panel.copy(alpha = 0.97f)).border(1.dp, com.lekathedon.visionlite.ui.Kit.line, RoundedCornerShape(com.lekathedon.visionlite.ui.Kit.cardR)).padding(top = 20.dp)) {
                com.lekathedon.visionlite.ui.Eyebrow(kicker, Modifier.padding(horizontal = 24.dp))
                com.lekathedon.visionlite.ui.DisplayText(if (tab == PanelTab.LIVE) game?.title ?: tab.title else tab.title, 24.sp,
                    Modifier.padding(horizontal = 24.dp, vertical = 6.dp), maxLines = 1)
                // Tabs: focus moving over a tab opens it (as Vision); Down goes into its list.
                androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(tabs, key = { it.name }) { t ->
                        Focusable({ }, if (t == tab) Modifier.focusRequester(tabFocus) else Modifier,
                            RoundedCornerShape(if (style == com.lekathedon.visionlite.ui.Style.TONAL) 10.dp else if (style == com.lekathedon.visionlite.ui.Style.MARQUEE) 4.dp else 50.dp), onFocus = {
                                if (holdTabs > 0 && System.currentTimeMillis() - holdTabs < 1500) { if (t != tab) runCatching { tabFocus.requestFocus() } }
                                else if (tab != t) tab = t
                            }) { f ->
                            val sel = t == tab
                            if (style == com.lekathedon.visionlite.ui.Style.MARQUEE) Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                                Text(t.chip.uppercase(), color = if (sel) Palette.Text else Palette.TextDim, fontFamily = com.lekathedon.visionlite.ui.Kit.label, fontSize = 13.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
                                Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(2.dp).background(if (sel) com.lekathedon.visionlite.ui.Kit.accent else Color.Transparent))
                            }
                            else Text(t.chip, color = if (sel) com.lekathedon.visionlite.ui.Kit.onAccent else Palette.TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.background(if (sel) com.lekathedon.visionlite.ui.Kit.accent else com.lekathedon.visionlite.ui.Kit.card2).padding(horizontal = 11.dp, vertical = 6.dp))
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
                                Focusable(l.open, if (l == lines.first()) Modifier.focusRequester(first) else Modifier, RoundedCornerShape(com.lekathedon.visionlite.ui.Kit.rowR)) { f ->
                                    val halo = f && style == com.lekathedon.visionlite.ui.Style.HALO
                                    Row(Modifier.fillMaxWidth().background(when { halo -> com.lekathedon.visionlite.ui.Kit.accent; f && style == com.lekathedon.visionlite.ui.Style.TONAL -> com.lekathedon.visionlite.ui.Kit.lerp(com.lekathedon.visionlite.ui.Kit.card2, com.lekathedon.visionlite.ui.Kit.accent, 0.25f); f -> com.lekathedon.visionlite.ui.Kit.card2; else -> com.lekathedon.visionlite.ui.Kit.rowFill }).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        val wide = false
                                        Box(Modifier.size(if (wide) 64.dp else 32.dp, 48.dp).clip(RoundedCornerShape(if (style == com.lekathedon.visionlite.ui.Style.TONAL) 10.dp else 4.dp)).background(com.lekathedon.visionlite.ui.Kit.card2)) { Img(l.poster, if (wide) 64.dp else 32.dp, 48.dp) }
                                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                            Text(l.title, color = if (halo) com.lekathedon.visionlite.ui.Kit.onAccent else Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(l.sub, color = if (halo) com.lekathedon.visionlite.ui.Kit.onAccent.copy(alpha = 0.7f) else Palette.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    } else if (g == null) {
                        // Refresh: the TV guide, the scores and every game's channels again now, each step shown.
                        item(key = "refresh") { Row(verticalAlignment = Alignment.CenterVertically) {
                            Focusable({ refreshAsked = true; Sports.refreshLive() }, if (live.isEmpty()) Modifier.focusRequester(first) else Modifier, RoundedCornerShape(50)) { f ->
                                Row(Modifier.background(if (f) com.lekathedon.visionlite.ui.Kit.accent else com.lekathedon.visionlite.ui.Kit.card2).padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.Icon(com.lekathedon.visionlite.ui.Icons.Sync, null, Modifier.size(14.dp),
                                        tint = if (f) com.lekathedon.visionlite.ui.Kit.onAccent else Palette.Text)
                                    Text(if (refresh.busy) "Refreshing" else "Refresh", color = if (f) com.lekathedon.visionlite.ui.Kit.onAccent else Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(start = 6.dp))
                                }
                            }
                            val note = when {
                                refresh.busy -> refresh.step
                                refresh.failed != null -> refresh.failed
                                refresh.at > 0 -> "Updated ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(refresh.at))} · ${if (refresh.live == 0) "nothing live" else "${refresh.live} live"}"
                                else -> status.ifEmpty { null }
                            }
                            if (note != null) Text(note, color = if (refresh.busy) com.lekathedon.visionlite.ui.Kit.accent else Muted, fontSize = 11.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp))
                        } }
                        if (live.isEmpty()) item {
                            Text(when { refresh.busy -> "Looking for live games…"; status.isNotEmpty() -> status; events.isEmpty() -> "Getting the scores…"; else -> "Nothing live right now." },
                                color = Muted, fontSize = 13.sp, modifier = Modifier.padding(10.dp))
                        }
                        items(live, key = { it.id }) { ev ->
                            val fr = rowFocus.getOrPut(ev.id) { FocusRequester() }
                            Focusable({ holdTabs = System.currentTimeMillis(); game = ev; playing = null }, Modifier.focusRequester(fr).then(if (ev == live.first()) Modifier.focusRequester(first) else Modifier)) { f ->
                                Column {
                                    ClockGameLine(ev, now, 0, f, compact = true)
                                    LogoChips(matches[ev.id].orEmpty())
                                }
                            }
                        }
                    } else {
                        val chans = matches[g.id].orEmpty()
                        val looking = searching == g.id
                        if (chans.isEmpty()) item {
                            Text(when { !Iptv.hasChannels() -> "Add a Live TV playlist to see the channels."; looking -> "Searching your channels for this game…"; else -> "No channel found for this game." },
                                color = Muted, fontSize = 13.sp, modifier = Modifier.padding(10.dp))
                        } else if (looking) item(key = "looking") {
                            Text("Searching for more channels…", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
                        }
                        items(chans, key = { it.channel.key }) { m ->
                            Focusable({ if (playing?.channel?.key == m.channel.key) { onDismiss(); clockWatch(nav, g, m, chans) } else playing = m },
                                if (m == chans.first()) Modifier.focusRequester(first) else Modifier) { f ->
                                ClockChannelLine(m, playing?.channel?.key == m.channel.key, f)
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
    Row(Modifier.fillMaxWidth().background(com.lekathedon.visionlite.ui.Kit.rowFill).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        shown.take(3).forEach { m ->
            Row(Modifier.padding(end = 8.dp).clip(RoundedCornerShape(4.dp)).background(com.lekathedon.visionlite.ui.Kit.card2).padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (!m.channel.logo.isNullOrBlank()) Img(m.channel.logo, 18.dp, 18.dp, Modifier.size(18.dp), ContentScale.Fit)
                Spacer(Modifier.width(4.dp))
                Text(m.channel.name, color = Palette.Text, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 90.dp))
            }
        }
        if (shown.size > 3) Text("+${shown.size - 3}", color = Muted, fontSize = 10.sp)
    }
}
