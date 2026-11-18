package com.lekathedon.visionlite.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.ui.Button
import com.lekathedon.visionlite.ui.Focusable
import com.lekathedon.visionlite.ui.Img
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Sports, the Vision way: My Teams (follow teams or whole leagues; "My Teams" on the Sports page shows only their games,
 * and Vision's reminders/matching already use them), league tables (ESPN), and the region (which leagues show).
 * Cost: a league's team list (~50-300 KB) only when you open it in Teams; a table (~30-100 KB) only when you open it.
 */
object SportsMine {
    private val prefs get() = App.instance.prefs

    fun save(f: SportsFollows) { prefs.sportsFollows.value = f.toJson(); Sports.refreshNow() }
    fun saveSettings(s: SportsSettings) { prefs.sportsSettings.value = s.toJson(); Sports.refreshNow() }

    fun toggleTeam(t: TeamInfo) {
        val f = Sports.follows
        val on = f.teams.any { it.leagueId == t.leagueId && it.teamId == t.id }
        save(f.copy(teams = if (on) f.teams.filterNot { it.leagueId == t.leagueId && it.teamId == t.id }
            else f.teams + FollowedTeam(t.leagueId, t.id, t.name, t.abbr, t.color, t.logo), setUp = true))
    }

    fun toggleLeague(id: String) {
        val f = Sports.follows
        save(f.copy(leagues = if (id in f.leagues) f.leagues - id else f.leagues + id, setUp = true))
    }

    /** Next region (blank = the TV's own country). */
    fun nextRegion() {
        val s = Sports.settings
        val codes = listOf("") + SportsCatalog.regions.map { it.first }
        saveSettings(s.copy(region = codes[(codes.indexOf(s.region).coerceAtLeast(0) + 1) % codes.size]))
    }
    fun regionLabel(): String = Sports.settings.region.let { if (it.isBlank()) "This TV (${SportsCatalog.regionOf(Sports.settings)})" else SportsCatalog.regionName(it) }

    private val teamCache = HashMap<String, List<TeamInfo>>()
    suspend fun teams(l: League): List<TeamInfo> = withContext(Dispatchers.IO) {
        teamCache[l.id] ?: runCatching { SportsFeeds.parseTeams(l, SportsFeeds().teamsRaw(l)) }.getOrDefault(emptyList())
            .sortedBy { it.fullName }.also { if (it.isNotEmpty()) teamCache[l.id] = it }
    }

    data class TableRow(val rank: Int, val team: String, val logo: String?, val played: String, val record: String, val points: String?, val mine: Boolean)
    data class Table(val group: String, val rows: List<TableRow>)

    /** A league's table(s) from ESPN: each group (conference, division, league) with rank, team, record and points. */
    suspend fun standings(l: League): List<Table> = withContext(Dispatchers.IO) {
        val path = l.espnPath ?: return@withContext emptyList()
        val f = Sports.follows
        val raw = runCatching { SportsFeeds().standingsRaw("https://site.api.espn.com/apis/v2/sports/$path/standings") }.getOrNull() ?: return@withContext emptyList()
        val out = ArrayList<Table>()
        fun walk(o: JSONObject, name: String) {
            val st = o.optJSONObject("standings")
            val entries = st?.optJSONArray("entries")
            if (entries != null && entries.length() > 0) {
                val rows = (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }.map { e ->
                    val t = e.optJSONObject("team")
                    val stats = e.optJSONArray("stats")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
                    fun stat(vararg n: String) = stats.firstOrNull { it.optString("name") in n || it.optString("type") in n }?.optString("displayValue")?.takeIf { it.isNotBlank() }
                    val w = stat("wins"); val d = stat("ties", "draws"); val lo = stat("losses")
                    TableRow(stat("rank", "playoffSeed")?.toDoubleOrNull()?.toInt() ?: 0, t?.optString("displayName").orEmpty(),
                        t?.optJSONArray("logos")?.optJSONObject(0)?.optString("href"), stat("gamesPlayed") ?: "",
                        stat("overall", "total") ?: listOfNotNull(w, d, lo).joinToString("-"), stat("points"),
                        f.teams.any { it.leagueId == l.id && it.teamId == t?.optString("id") })
                }
                val sorted = if (rows.all { it.rank > 0 }) rows.sortedBy { it.rank } else rows
                out += Table(name.ifBlank { l.name }, sorted.mapIndexed { i, r -> if (r.rank == 0) r.copy(rank = i + 1) else r })
            }
            o.optJSONArray("children")?.let { c -> for (i in 0 until c.length()) c.optJSONObject(i)?.let { walk(it, it.optString("name")) } }
        }
        runCatching { walk(JSONObject(raw), "") }
        out
    }
}

/** My Teams: leagues on the left (your region's first); a league's teams on the right, OK follows. */
@Composable
fun SportsTeams() {
    var follows by remember { mutableStateOf(Sports.follows) }
    val leagues = remember { Sports.activeLeagues().filter { it.espnPath != null && it.kind == LeagueKind.TEAMS } }
    var league by remember { mutableStateOf(leagues.firstOrNull()) }
    var teams by remember { mutableStateOf<List<TeamInfo>?>(null) }
    LaunchedEffect(league) { teams = null; league?.let { teams = SportsMine.teams(it) } }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize().background(LocalLook.current.page).padding(start = 36.dp, end = 36.dp, top = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("My Teams", color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("${follows.teams.size} teams · ${follows.leagues.size} whole leagues", color = Palette.TextDim, fontSize = 13.sp)
            Button("Region: ${SportsMine.regionLabel()}", { SportsMine.nextRegion() })
        }
        Row(Modifier.fillMaxSize().padding(top = 14.dp)) {
            LazyColumn(Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(leagues, key = { _, l -> l.id }) { i, l ->
                    Focusable({ league = l }, if (i == 0) Modifier.focusRequester(first) else Modifier, onFocus = { league = l }) { f ->
                        Text((if (l.id in follows.leagues) "★ " else "") + l.name, color = if (f) Color.Black else Palette.Text, fontSize = 15.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().background(if (f) Color.White else Palette.Card).padding(10.dp))
                    }
                }
            }
            Column(Modifier.padding(start = 20.dp).weight(1f)) {
                val l = league
                if (l != null) Button(if (l.id in follows.leagues) "Following every ${l.short} game ✓" else "Follow every ${l.short} game",
                    { SportsMine.toggleLeague(l.id); follows = Sports.follows })
                val t = teams
                when {
                    l == null -> {}
                    t == null -> Text("Getting the teams…", color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp))
                    t.isEmpty() -> Text("No team list for this one.", color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp))
                    else -> LazyColumn(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(t, key = { it.id }) { team ->
                            val on = follows.followsTeam(team.leagueId, team.id)
                            Focusable({ SportsMine.toggleTeam(team); follows = Sports.follows }) { f ->
                                Row(Modifier.fillMaxWidth().background(if (f) Color(0x33FFFFFF) else Palette.Card).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    team.logo?.let { Img(it, 32.dp, 32.dp, Modifier.size(32.dp)) }
                                    Text(team.fullName, color = Palette.Text, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(start = 10.dp))
                                    Text(if (on) "Following ✓" else "Follow", color = if (on) Color(0xFF7BD88F) else Palette.TextDim, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A league's table(s); your teams stand out. */
@Composable
fun SportsStandings(leagueId: String) {
    val league = remember(leagueId) { SportsCatalog.league(leagueId) }
    var tables by remember(leagueId) { mutableStateOf<List<SportsMine.Table>?>(null) }
    LaunchedEffect(leagueId) { tables = league?.let { SportsMine.standings(it) } ?: emptyList() }
    val first = remember { FocusRequester() }
    LaunchedEffect(tables) { if (!tables.isNullOrEmpty()) runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize().background(LocalLook.current.page).padding(start = 36.dp, end = 36.dp, top = 24.dp)) {
        Text("${league?.name ?: "League"} · Table", color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        val t = tables
        when {
            t == null -> Text("Getting the table…", color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp))
            t.isEmpty() -> Text("No table for this one.", color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp))
            else -> LazyColumn(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                t.forEachIndexed { ti, table ->
                    item(key = "h$ti") { Text(table.group, color = Palette.TextDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                    itemsIndexed(table.rows, key = { i, r -> "$ti:$i:${r.team}" }) { i, r ->
                        Focusable({}, if (ti == 0 && i == 0) Modifier.focusRequester(first) else Modifier) { f ->
                            Row(Modifier.fillMaxWidth(0.8f).background(if (f) Color(0x33FFFFFF) else if (r.mine) Color(0x2232C25A) else Palette.Card).padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("${r.rank}", color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.width(34.dp))
                                Box(Modifier.size(24.dp)) { r.logo?.let { Img(it, 24.dp, 24.dp, Modifier.size(24.dp)) } }
                                Text(r.team, color = Palette.Text, fontSize = 14.sp, fontWeight = if (r.mine) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f).padding(start = 10.dp))
                                Text(r.played, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.width(44.dp))
                                Text(r.record, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.width(110.dp))
                                Text(r.points?.let { "$it pts" } ?: "", color = Palette.Text, fontSize = 13.sp, modifier = Modifier.width(70.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
