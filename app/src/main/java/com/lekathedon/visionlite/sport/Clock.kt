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

/**
 * The clock's Live now: the same list and counts as the Sports page. OK on a game shows its channels under it; OK on a
 * channel plays it in the panel's window, OK again full screen. Refresh channels with its note.
 */
@Composable
fun ClockPanel(nav: Nav, onClose: () -> Unit) {
    BackHandler { onClose() }
    DisposableEffect(Unit) { Host.opened.value = true; Host.kick(); Matcher.ensure(); val stop = Scores.watch(); onDispose { stop() } }
    val all by Scores.games.collectAsState()
    val settings by Scores.settings.collectAsState()
    val matched by Matcher.changed.collectAsState()
    val live = remember(all, matched) { Scores.liveNow(all) { (Matcher.cached(it)?.size ?: 0) > 0 } }
    LaunchedEffect(live) { Matcher.warm(live) }
    var open by remember { mutableStateOf<String?>(null) }
    var win by remember { mutableStateOf<Channel?>(null) }
    var winGame by remember { mutableStateOf<Game?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(live.isNotEmpty()) { runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.CenterEnd) {
        val shape = RoundedCornerShape(Kit.cardR)
        Column(Modifier.fillMaxHeight().width(620.dp).padding(18.dp).clip(shape).background(Kit.panel.copy(alpha = 0.98f)).border(1.dp, Kit.line, shape)
            .padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KitHeader("Live now", by = "${live.size} live"); Spacer(Modifier.weight(1f))
                KitButton("Sports", null, { onClose(); nav.push(Screen.Sports) })
            }
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
    }
}
