package com.lekathedon.visionlite.sport

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.iptv.Iptv
import com.lekathedon.visionlite.ui.*

/** Pick Your Teams: sport on the left, its leagues across the top, the league's teams below. [arrange]: open on Arrange. */
@Composable
fun Picker(nav: Nav, arrange: Boolean) {
    val follows by Scores.follows.collectAsState()
    var sport by remember { mutableStateOf(Sport.SOCCER) }
    val leagues = remember(sport) { Leagues.of(sport) }
    var league by remember(sport) { mutableStateOf(leagues.firstOrNull()) }
    var teams by remember { mutableStateOf<List<Team>?>(null) }
    LaunchedEffect(league) { teams = null; teams = league?.let { Scores.teams(it.key) }.orEmpty() }
    var arranging by remember { mutableStateOf(arrange) }
    var word by remember { mutableStateOf("") }
    fun save(f: Follows) = Scores.save(f.copy(setUp = true))

    Inside(true) {
        Column(Modifier.fillMaxSize().skinPage().padding(start = 40.dp, end = 32.dp, top = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KitHeader("Pick your teams", by = "${follows.teams.size} teams · ${follows.leagues.size} leagues · ${follows.bar().size} on the bar")
                Spacer(Modifier.weight(1f))
                KitButton("Arrange my sports", Icons.Filled.SwapVert, { arranging = true })
                Spacer(Modifier.width(8.dp)); KitButton("Done", null, { nav.pop() }, primary = true)
            }
            // What you follow: OK unfollows.
            if (!follows.empty) LazyRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(follows.teams, key = { "t" + it.key }) { t -> KitButton("✕ ${t.name}", null, { save(follows.copy(teams = follows.teams - t)) }) }
                items(follows.leagues.toList(), key = { "l$it" }) { k -> KitButton("✕ ${Leagues.byKey(k)?.name ?: k}", null, { save(follows.copy(leagues = follows.leagues - k)) }) }
                items(follows.words, key = { "w$it" }) { w -> KitButton("✕ $w", null, { save(follows.copy(words = follows.words - w)) }) }
            }
            Row(Modifier.fillMaxSize().padding(top = 14.dp)) {
                LazyColumn(Modifier.width(200.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(Sport.entries.filter { it != Sport.OTHER }, key = { it.name }) { s -> KitButton(s.label, null, { sport = s }, Modifier.fillMaxWidth(), on = s == sport, wide = true) }
                    item(key = "other") { KitButton("By name (guide)", null, { sport = Sport.OTHER }, Modifier.fillMaxWidth(), on = sport == Sport.OTHER, wide = true) }
                }
                Column(Modifier.weight(1f).padding(start = 20.dp)) {
                    if (sport == Sport.OTHER) {
                        Text("Sports ESPN doesn't carry (darts, snooker, wrestling…): follow a name and the guide finds them.", color = Palette.TextDim, fontSize = 13.sp)
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            BasicTextField(word, { word = it.take(40) }, singleLine = true, textStyle = TextStyle(color = Palette.Text, fontSize = 16.sp),
                                modifier = Modifier.width(360.dp).border(1.dp, Kit.line, RoundedCornerShape(8.dp)).padding(12.dp))
                            Spacer(Modifier.width(10.dp))
                            KitButton("Follow", Icons.Filled.Add, { if (word.isNotBlank()) { save(follows.copy(words = (follows.words + word.trim()).distinct())); word = "" } })
                        }
                        return@Column
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(leagues, key = { it.key }) { l -> KitButton(l.short, null, { league = l }, on = l == league) }
                    }
                    league?.let { l ->
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val all = l.key in follows.leagues; val pinned = l.key in follows.bar()
                            KitButton(if (all) "Following all of ${l.short}" else "Follow all of ${l.short}", Icons.Filled.Star,
                                { save(follows.copy(leagues = if (all) follows.leagues - l.key else follows.leagues + l.key)) }, on = all)
                            KitButton(if (pinned) "On the top bar" else "Put on the top bar", Icons.Filled.PushPin, {
                                save(if (pinned) follows.copy(unpinned = follows.unpinned + l.key) else follows.copy(pins = (follows.pins + l.key).distinct(), unpinned = follows.unpinned - l.key))
                            }, on = pinned)
                        }
                    }
                    val list = teams
                    if (list == null) Text("Loading teams…", color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp))
                    else LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.padding(top = 12.dp), contentPadding = PaddingValues(bottom = 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(list, key = { it.key }) { t ->
                            val on = follows.follows(t)
                            Focusable({ save(follows.copy(teams = if (on) follows.teams.filter { it.key != t.key } else follows.teams + t)) }, shape = RoundedCornerShape(Kit.cardR)) { f ->
                                Column(Modifier.fillMaxWidth().background(if (f) kitFocus.first else if (on) Kit.accent.copy(alpha = 0.25f) else Kit.rowFill).padding(10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally) {
                                    Logo(t.logo, 48.dp)
                                    Text((if (on) "★ " else "") + t.name, color = if (f) kitFocus.second else Palette.Text, fontSize = 13.sp, maxLines = 2, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (arranging) Arrange { arranging = false }
    }
}

/** Arrange My Sports: the top bar's order; OK picks a league up, OK again drops it. */
@Composable
private fun Arrange(close: () -> Unit) = Overlay(close) {
    val follows by Scores.follows.collectAsState()
    val bar = follows.bar()
    var held by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    KitPopup("Arrange my sports", Modifier.width(480.dp).heightIn(max = 640.dp), sub = "OK picks a league up, OK on another place drops it there") {
        if (bar.isEmpty()) Text("Nothing on the top bar yet.", color = Palette.TextDim, fontSize = 13.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(bar, key = { it }) { k ->
                KitMenuItem((if (held == k) "↕ " else "") + (Leagues.byKey(k)?.name ?: k), {
                    val h = held
                    if (h == null) held = k
                    else { val l = bar.toMutableList(); l.remove(h); l.add(bar.indexOf(k).coerceAtMost(l.size), h); Scores.save(follows.copy(order = l)); held = null }
                }, if (k == bar.first()) Modifier.focusRequester(first) else Modifier)
            }
        }
        KitButton("Done", null, close, primary = true)
    }
}

/** All events' league chooser: sports as groups (closed), a search box, OK turns a league on/off. */
@Composable
fun LeagueChooser(close: () -> Unit) = Overlay(close) {
    val settings by Scores.settings.collectAsState()
    var open by remember { mutableStateOf(setOf<Sport>()) }
    var q by remember { mutableStateOf("") }
    val box = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { box.requestFocus() } }
    val order = remember { Leagues.forRegion(Scores.region()) }
    KitPopup("Choose leagues", Modifier.width(560.dp).heightIn(max = 680.dp), sub = "Leagues shown in All events") {
        BasicTextField(q, { q = it.take(30) }, singleLine = true, textStyle = TextStyle(color = Palette.Text, fontSize = 15.sp),
            modifier = Modifier.fillMaxWidth().focusRequester(box).border(1.dp, Kit.line, RoundedCornerShape(8.dp)).padding(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Sport.entries.forEach { s ->
                val ls = order.filter { it.sport == s && (q.isBlank() || it.name.contains(q, true) || it.short.contains(q, true)) }
                if (ls.isEmpty()) return@forEach
                val shown = q.isNotBlank() || s in open
                item(key = "s" + s.name) { KitMenuItem("${if (shown) "▾" else "▸"} ${s.label}  ·  ${ls.count { it.key !in settings.hiddenLeagues }}/${ls.size}",
                    { open = if (s in open) open - s else open + s }) }
                if (shown) items(ls, key = { it.key }) { l ->
                    val on = l.key !in settings.hiddenLeagues
                    KitMenuItem("    ${if (on) "✓" else "  "} ${l.name}", {
                        Scores.save(settings.copy(hiddenLeagues = if (on) settings.hiddenLeagues + l.key else settings.hiddenLeagues - l.key)) })
                }
            }
        }
    }
}

/** Tables: your leagues first; [only] = the one asked for. Kept an hour; fetched when looked at. */
@Composable
fun Tables(nav: Nav, only: String?) {
    val follows by Scores.follows.collectAsState()
    val mine = remember(follows) { (follows.bar() + follows.teams.map { it.league } + follows.leagues).distinct().mapNotNull(Leagues::byKey).filter { it.table != Table.NONE } }
    val choices = mine.ifEmpty { Leagues.all.filter { it.table != Table.NONE }.take(8) }
    var l by remember { mutableStateOf(only?.let(Leagues::byKey) ?: choices.firstOrNull()) }
    var groups by remember { mutableStateOf<List<Espn.Group>?>(null) }
    LaunchedEffect(l) { groups = null; groups = l?.let { Scores.table(it) }.orEmpty() }
    var team by remember { mutableStateOf<Team?>(null) }
    Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(choices, key = { it.key }) { c -> KitButton(c.short, null, { l = c }, on = c == l) }
        }
        val gs = groups
        if (gs == null) Text("Loading the table…", color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
        else LazyColumn(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
            if (gs.isEmpty()) item { Text("No table for this league right now.", color = Palette.TextDim, fontSize = 13.sp) }
            gs.forEach { gr ->
                item(key = "h" + gr.name) { Row(Modifier.padding(top = 10.dp)) {
                    KitHeader(gr.name.ifBlank { l?.name.orEmpty() }, Modifier.weight(1f))
                    gr.heads.forEach { Text(it, color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.width(52.dp)) }
                } }
                items(gr.rows.withIndex().toList(), key = { gr.name + it.value.team.id }) { (i, r) ->
                    val mineTeam = follows.teams.any { it.league == l?.key && it.id == r.team.id }
                    Focusable({ team = r.team }, Modifier.fillMaxWidth(), RoundedCornerShape(Kit.rowR)) { f ->
                        val ink = if (f) kitFocus.second else Palette.Text
                        Row(Modifier.fillMaxWidth().background(if (f) kitFocus.first else if (mineTeam) Kit.accent.copy(alpha = 0.2f) else Kit.rowFill).padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(4.dp).height(22.dp).background(r.noteColor?.let { androidx.compose.ui.graphics.Color(it) } ?: androidx.compose.ui.graphics.Color.Transparent))
                            Text("${i + 1}", color = ink, fontSize = 13.sp, modifier = Modifier.width(34.dp).padding(start = 8.dp))
                            Logo(r.team.logo, 22.dp)
                            Text(r.team.name + (r.clinch?.let { " ($it)" } ?: ""), color = ink, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 8.dp))
                            r.cols.forEach { Text(it, color = ink, fontSize = 13.sp, modifier = Modifier.width(52.dp)) }
                        }
                    }
                }
                val notes = gr.rows.mapNotNull { r -> r.note?.let { it to r.noteColor } }.distinct()
                if (notes.isNotEmpty()) item(key = "n" + gr.name) { Text(notes.joinToString("  ·  ") { it.first }, color = Palette.TextDim, fontSize = 11.sp) }
            }
        }
    }
    team?.let { t -> TeamCard(nav, t, null) { team = null } }
}

/** League networks: each league's own channel, as found or as you chose it. */
@Composable
fun Networks() {
    val settings by Scores.settings.collectAsState()
    val nets = remember { Leagues.all.filter { it.network != null }.distinctBy { it.network } }
    var found by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(settings) {
        found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val all = runCatching { Iptv.channels(null) }.getOrDefault(emptyList())
            nets.associate { l -> val want = Matcher.norm(l.network!!)
                l.key to (settings.leagueChannels[l.key]?.let { k -> all.firstOrNull { it.key == k }?.name }
                    ?: all.firstOrNull { Matcher.norm(it.name).let { n -> n == want || n.startsWith("$want ") || n.contains(" $want") } && it.key !in settings.blocked }?.name ?: "not found") }
        }
    }
    LazyColumn(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(nets, key = { it.key }) { l ->
            Row(Modifier.fillMaxWidth().background(Kit.rowFill, RoundedCornerShape(Kit.rowR)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Logo(l.logo, 26.dp)
                Text(l.network!!, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(220.dp).padding(start = 10.dp))
                Text(found[l.key] ?: "…", color = Palette.TextDim, fontSize = 14.sp)
            }
        }
    }
}
