package com.lekathedon.visionlite.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import com.lekathedon.visionlite.ui.Button
import com.lekathedon.visionlite.ui.Focusable
import com.lekathedon.visionlite.ui.Img
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.Screen
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * My Teams as rows (Vision's MyTeamsRows): the next big game on a wide card, then Live & Upcoming, a row per team you
 * follow (its last results, then what's next), a row per league on your bar or followed whole, a row per name you
 * follow. Each row opens on the game that's on or next. OK opens the game's page.
 * Cost: each followed team's season (~30-200 KB) every 6 hours, only while this page is open.
 */
@Composable
fun SportsMyTeamsPage(nav: Nav) {
    SportsViewer()
    val events by Sports.events.collectAsState()
    val schedules by Sports.schedules.collectAsState()
    val matches by Sports.matches.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { Sports.loadSchedules() }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val page = remember(events, schedules, now / 60_000) { Sports.myTeams(now) }
    val follows = remember(events) { Sports.follows }
    val first = remember { FocusRequester() }
    LaunchedEffect(page.rows.isNotEmpty() || page.heroes.isNotEmpty()) { delay(60); runCatching { first.requestFocus() } }

    Column(Modifier.fillMaxSize().background(LocalLook.current.page)) {
        Row(Modifier.padding(start = 36.dp, end = 36.dp, top = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("My Teams", color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button("Pick teams", { nav.push(Screen.SportsTeams) })
            Button("All games", { nav.push(Screen.Sports) })
        }
        if (follows.isEmpty) {
            Text("Follow a team or a league (Pick teams) and its games line up here.", color = Palette.TextDim, fontSize = 16.sp, modifier = Modifier.padding(36.dp))
            return
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 14.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (page.heroes.isNotEmpty()) item("hero") {
                LazyRow(contentPadding = PaddingValues(horizontal = 36.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(page.heroes, key = { _, e -> "h" + e.id }) { i, ev ->
                        Focusable({ nav.push(Screen.SportsGame(ev.id)) }, if (i == 0) Modifier.focusRequester(first) else Modifier, RoundedCornerShape(16.dp)) { f ->
                            Hero(ev, matches[ev.id]?.size ?: 0, f)
                        }
                    }
                }
            }
            page.rows.forEachIndexed { ri, row ->
                item(row.key) {
                    Column {
                        Row(Modifier.padding(start = 36.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            (row.team?.logo ?: row.league?.let { null })?.let { Img(it, 22.dp, 22.dp, Modifier.size(22.dp).padding(end = 6.dp), ContentScale.Fit) }
                            Text(row.title, color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text("  ${row.events.size}", color = Palette.TextDim, fontSize = 13.sp)
                        }
                        val state = rememberLazyListState(row.startIndex.coerceAtLeast(0))
                        LazyRow(state = state, contentPadding = PaddingValues(horizontal = 36.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            itemsIndexed(row.events, key = { _, e -> row.key + e.id }) { i, ev ->
                                val m = if (page.heroes.isEmpty() && ri == 0 && i == row.startIndex) Modifier.focusRequester(first) else Modifier
                                Focusable({ nav.push(Screen.SportsGame(ev.id)) }, m, RoundedCornerShape(12.dp)) { f -> RowCard(ev, matches[ev.id]?.size ?: 0, f, now) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val dayFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())
private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())

/** "Today", "Tomorrow", "Yesterday", or "Sat 24 Oct". */
private fun dayLabel(ms: Long, now: Long): String {
    fun d(t: Long) = Calendar.getInstance().apply { timeInMillis = t }.let { it.get(Calendar.YEAR) * 400 + it.get(Calendar.DAY_OF_YEAR) }
    return when (d(ms) - d(now)) { 0 -> "Today"; 1 -> "Tomorrow"; -1 -> "Yesterday"; else -> dayFmt.format(Date(ms)) }
}

private fun when_(ev: SportsEvent, now: Long): String = when (ev.state) {
    GameState.LIVE -> ev.status ?: ev.liveLabel
    GameState.POST -> (ev.status ?: ev.finishedLabel) + " · " + dayLabel(ev.startMs, now)
    else -> dayLabel(ev.startMs, now) + if (ev.hasTime) " · " + hm.format(Date(ev.startMs)) else ""
}

@Composable
private fun Hero(ev: SportsEvent, channels: Int, f: Boolean) {
    val sport = SportsCatalog.league(ev.leagueId)?.sport
    val sides = sport?.let { ev.ordered(it) } ?: ev.sides
    val c1 = Color(sides.firstOrNull()?.team?.color ?: 0xFF2A2F3A); val c2 = Color(sides.getOrNull(1)?.team?.color ?: 0xFF1B1B1F)
    val now = System.currentTimeMillis()
    Box(Modifier.width(620.dp).height(200.dp).clip(RoundedCornerShape(16.dp)).background(Brush.horizontalGradient(listOf(c1.copy(alpha = 0.7f), c2.copy(alpha = 0.7f))))
        .padding(20.dp)) {
        Column {
            Text(when_(ev, now) + "  ·  " + (SportsCatalog.league(ev.leagueId)?.name ?: ""), color = if (ev.state == GameState.LIVE) Color(0xFFFF6B6B) else Color(0xCCFFFFFF),
                fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (sides.size == 2) sides.forEach { s ->
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Img(s.team.logo, 40.dp, 40.dp, Modifier.size(40.dp), ContentScale.Fit)
                    Text(s.team.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp).weight(1f), maxLines = 1)
                    if (ev.state != GameState.PRE && !Sports.settings.hideScores) Text(s.score.orEmpty(), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
            } else Text(ev.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp), maxLines = 2)
        }
        Text(if (channels > 0) "$channels channel" + (if (channels == 1) "" else "s") else "", color = Color(0xFFD9DC8E), fontSize = 13.sp, modifier = Modifier.align(Alignment.BottomEnd))
        if (f) Box(Modifier.fillMaxSize().background(Color(0x22FFFFFF)))
    }
}

@Composable
private fun RowCard(ev: SportsEvent, channels: Int, f: Boolean, now: Long) {
    val sport = SportsCatalog.league(ev.leagueId)?.sport
    val sides = sport?.let { ev.ordered(it) } ?: ev.sides
    val hide = Sports.settings.hideScores
    Column(Modifier.width(260.dp).height(116.dp).background(if (f) Color.White else Color(0x14FFFFFF)).padding(12.dp)) {
        Text(when_(ev, now), color = if (ev.state == GameState.LIVE) Color(0xFFE5484D) else if (f) Color.DarkGray else Color(0x99FFFFFF), fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (sides.size == 2) sides.forEach { s ->
            Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Img(s.team.logo, 20.dp, 20.dp, Modifier.size(20.dp), ContentScale.Fit)
                Text(s.team.name, color = if (f) Color.Black else Color.White, fontSize = 14.sp, fontWeight = if (s.winner) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier.padding(start = 8.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (ev.state != GameState.PRE && !hide) Text(s.score.orEmpty(), color = if (f) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        } else Text(ev.title, color = if (f) Color.Black else Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
        if (channels > 0) Text("$channels ch", color = if (f) Color.DarkGray else Color(0xFFD9DC8E), fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
    }
}
