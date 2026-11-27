package com.lekathedon.visionlite.sport

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.iptv.Channel
import com.lekathedon.visionlite.ui.*

/** The clock's tabs: your lists first, then games under way, then what's new and what's coming. */
private enum class ClockTab(val label: String) { SAVED("Saved"), ACTIVITY("Activity"), LIVE("Live now"), NOTICES("Notices"), CALENDAR("Calendar") }

/** One title line of a list tab: poster, name, one line under it; OK opens it. */
private class TabLine(val key: String, val name: String, val sub: String, val poster: String?, val open: () -> Unit)

private val dayTime = java.text.SimpleDateFormat("EEE d MMM · HH:mm", java.util.Locale.getDefault())
private fun ago(at: Long, now: Long): String = ((now - at) / 60_000).let { m ->
    when { m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 1440 -> "${m / 60} h ago"; m < 2880 -> "yesterday"; else -> "${m / 1440} days ago" } }
private fun ep(s: Int?, e: Int?) = if (s != null && e != null) "S$s E$e" else null

/**
 * The clock's panel (user 2026-11-27: it had shrunk to Sports only). Tabs: Saved (continue watching + your saves),
 * Activity (what you watched, Trakt), Live now (games; OK shows channels, OK on one plays it in the window, OK again
 * full screen), Notices (new episodes / films out), Calendar (next 14 days). A tab reads only when it's opened;
 * the lists keep to the hub Home shows. Focus on a tab opens it; Down goes into its list.
 */
@Composable
fun ClockPanel(nav: Nav, onClose: () -> Unit) {
    val app = com.lekathedon.visionlite.App.instance
    var tab by remember { mutableStateOf(ClockTab.SAVED) }
    BackHandler { onClose() }
    // Sports runs only while its tab is open (no score polling behind the other tabs).
    if (tab == ClockTab.LIVE) DisposableEffect(Unit) { Host.opened.value = true; Host.kick(); Matcher.ensure(); val stop = Scores.watch(); onDispose { stop() } }
    val tabFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { tabFocus.requestFocus() } }
    val hub by com.lekathedon.visionlite.anime.Hubs.content.collectAsState()
    fun mine(id: String) = com.lekathedon.visionlite.anime.Hubs.keepsId(hub, id)
    val now = remember(tab) { System.currentTimeMillis() }
    fun detail(type: String, id: String, name: String) { onClose(); nav.push(Screen.Detail(type, id, name)) }
    val cw by produceState(emptyList<com.lekathedon.visionlite.trakt.Resume>(), tab) {
        if (tab == ClockTab.SAVED) value = runCatching { app.trakt.continueWatching() }.getOrDefault(emptyList())
    }
    val history by produceState(emptyList<com.lekathedon.visionlite.trakt.HistoryItem>(), tab) {
        if (tab == ClockTab.ACTIVITY) value = runCatching { app.trakt.history() }.getOrDefault(emptyList())
    }
    val coming by com.lekathedon.visionlite.home.ComingUp.items.collectAsState()
    val lines: List<TabLine> = when (tab) {
        ClockTab.SAVED -> cw.filter { mine(it.imdb) }.map { r ->
            TabLine("cw:${r.streamId}", r.name, listOfNotNull("Continue", ep(r.season, r.episode), if (r.progress > 0f) "${r.progress.toInt()}%" else "up next").joinToString(" · "),
                com.lekathedon.visionlite.library.posterOf(r.imdb)) { onClose(); nav.push(Screen.Streams(r.type, r.streamId, r.label, r.imdb)) }
        } + com.lekathedon.visionlite.library.Saves.all().filter { mine(it.id) }.map { sv ->
            TabLine("sv:${sv.id}", sv.name, "Saved · " + if (sv.type == "series") "Series" else "Movie",
                sv.poster ?: com.lekathedon.visionlite.library.posterOf(sv.id.substringBefore(':'))) { detail(sv.type, sv.id, sv.name) }
        }
        ClockTab.ACTIVITY -> history.mapNotNull { h ->
            val at = runCatching { java.time.Instant.parse(h.watchedAt).toEpochMilli() }.getOrDefault(now)
            val m = h.movie; val sh = h.show
            when {
                m != null -> m.ids.imdb?.takeIf { mine(it) }?.let { id -> TabLine("a:${h.id}", m.title, "${ago(at, now)} · Movie", com.lekathedon.visionlite.library.posterOf(id)) { detail("movie", id, m.title) } }
                sh != null -> sh.ids.imdb?.takeIf { mine(it) }?.let { id -> TabLine("a:${h.id}", sh.title,
                    listOfNotNull(ago(at, now), ep(h.episode?.season, h.episode?.number), h.episode?.title).joinToString(" · "), com.lekathedon.visionlite.library.posterOf(id)) { detail("series", id, sh.title) } }
                else -> null
            }
        }
        ClockTab.NOTICES -> coming.filter { it.isNew && mine(it.imdb) }.sortedByDescending { it.at }.map { c ->
            TabLine("n:${c.imdb}:${c.season}:${c.episode}", c.name, listOfNotNull(if (c.type == "movie") "Out to stream" else "New episode", ep(c.season, c.episode), c.epTitle, ago(c.at, now)).joinToString(" · "),
                c.image ?: com.lekathedon.visionlite.library.posterOf(c.imdb)) { detail(c.type, c.imdb, c.name) }
        }
        ClockTab.CALENDAR -> coming.filter { mine(it.imdb) && it.at in (now - 86_400_000L)..(now + 14 * 86_400_000L) }.sortedBy { it.at }.map { c ->
            TabLine("c:${c.imdb}:${c.season}:${c.episode}", c.name, listOfNotNull(dayTime.format(java.util.Date(c.at)), ep(c.season, c.episode), c.epTitle).joinToString(" · "),
                c.image ?: com.lekathedon.visionlite.library.posterOf(c.imdb)) { detail(c.type, c.imdb, c.name) }
        }
        ClockTab.LIVE -> emptyList()
    }
    val empty = when (tab) {
        ClockTab.SAVED -> "Nothing to resume or saved in ${hub.label} yet."
        ClockTab.ACTIVITY -> if (app.trakt.connected.value == null) "Sign in to Trakt to see what you watched." else "Nothing watched yet."
        ClockTab.NOTICES -> "Nothing new from the shows you follow."
        ClockTab.CALENDAR -> "Nothing airing in the next 14 days."
        ClockTab.LIVE -> ""
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.CenterEnd) {
        val shape = RoundedCornerShape(Kit.cardR)
        Column(Modifier.fillMaxHeight().width(620.dp).padding(18.dp).clip(shape).background(Kit.panel.copy(alpha = 0.98f)).border(1.dp, Kit.line, shape)
            .padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KitHeader(tab.label, by = if (tab == ClockTab.LIVE) null else hub.label); Spacer(Modifier.weight(1f))
                KitButton("Sports", null, { onClose(); nav.push(Screen.Sports) })
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ClockTab.entries, key = { it.name }) { t ->
                    Focusable({ tab = t }, if (t == tab) Modifier.focusRequester(tabFocus) else Modifier, RoundedCornerShape(50), onFocus = { tab = t }) { f ->
                        Text(t.label, color = if (t == tab || f) Palette.Text else Palette.TextDim, fontSize = 14.sp,
                            modifier = Modifier.background(if (t == tab) Kit.card2 else Color.Transparent).padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
            if (tab == ClockTab.LIVE) LiveTab(nav, onClose)
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (lines.isEmpty()) item { Text(empty, color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.padding(8.dp)) }
                items(lines, key = { it.key }) { l ->
                    Focusable(l.open, Modifier.fillMaxWidth(), RoundedCornerShape(Kit.rowR)) { f ->
                        Row(Modifier.fillMaxWidth().background(if (f) Kit.card2 else Kit.rowFill).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(34.dp, 50.dp).clip(RoundedCornerShape(4.dp)).background(Kit.card2)) { Img(l.poster, 34.dp, 50.dp) }
                            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                Text(l.name, color = Palette.Text, fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(l.sub, color = Palette.TextDim, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The clock's Live now tab: the same list and counts as the Sports page. OK on a game shows its channels under it; OK on a
 * channel plays it in the panel's window, OK again full screen. Refresh channels with its note.
 */
@Composable
private fun LiveTab(nav: Nav, onClose: () -> Unit) {
    val all by Scores.games.collectAsState()
    val settings by Scores.settings.collectAsState()
    val matched by Matcher.changed.collectAsState()
    val live = remember(all, matched) { Scores.liveNow(all) { (Matcher.cached(it)?.size ?: 0) > 0 } }
    LaunchedEffect(live) { Matcher.warm(live) }
    var open by remember { mutableStateOf<String?>(null) }
    var win by remember { mutableStateOf<Channel?>(null) }
    var winGame by remember { mutableStateOf<Game?>(null) }
    val first = remember { FocusRequester() }
    val shape = RoundedCornerShape(Kit.cardR)
    RefreshButton("${live.size} live")
    if (win != null) Box(Modifier.fillMaxWidth().aspectRatio(16 / 9f).clip(shape).background(Color.Black)) { MiniPlayer(win!!.url, win!!.headers, Modifier.fillMaxSize()) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (live.isEmpty()) item { Text("Nothing live right now.", color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.focusRequester(first)) }
        items(live, key = { it.key }) { g ->
            Column {
                GameLine(g, settings.hideScores, Matcher.cached(g)?.size, { open = if (open == g.key) null else g.key },
                    { onClose(); nav.push(Screen.SportsGame(g.key)) }, if (g == live.first()) Modifier.focusRequester(first) else Modifier)
                if (open == g.key) {
                    val hits = Matcher.cached(g).orEmpty()
                    if (hits.isEmpty()) Text("No channel found yet", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                    LazyRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(hits, key = { it.channel.key }) { h ->
                            val c = h.channel
                            KitButton(c.name + if (win?.key == c.key) " ▶" else "", null, {
                                if (win?.key == c.key && winGame?.key == g.key) { onClose(); Host.play(nav, g, c, hits.map { it.channel }) } else { win = c; winGame = g }
                            }, on = win?.key == c.key)
                        }
                    }
                }
            }
        }
    }
}
