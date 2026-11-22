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
import java.util.Locale

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
    private val teamDir by lazy { java.io.File(App.instance.filesDir, "sports").apply { mkdirs() } }

    /** A league's teams (Vision's): kept a week on the TV; countries are every country of any competition of that kind. */
    suspend fun teams(l: League): List<TeamInfo> = withContext(Dispatchers.IO) {
        if (l.national) {
            val all = LinkedHashMap<String, TeamInfo>()
            for (n in SportsCatalog.leagues.filter { it.national && it.nationalGroup == l.nationalGroup })
                leagueTeams(n).forEach { t -> all.putIfAbsent(t.id, if (n.id == l.id) t else t.copy(leagueId = l.id)) }
            return@withContext all.values.sortedBy { it.fullName.lowercase(Locale.ROOT) }
        }
        leagueTeams(l)
    }

    /** The teams already known for [l] (memory or the saved list), without asking. */
    fun cachedTeams(l: League): List<TeamInfo> = synchronized(teamCache) { teamCache[l.id] }
        ?: runCatching { java.io.File(teamDir, "teams_${safeId(l.id)}.json").takeIf { it.exists() }?.readText() }.getOrNull()
            ?.let { raw -> runCatching { SportsFeeds.parseTeams(l, raw) }.getOrDefault(emptyList()) }.orEmpty()

    private fun safeId(id: String) = id.replace(Regex("[^a-zA-Z0-9]+"), "_")

    private fun leagueTeams(l: League): List<TeamInfo> {
        synchronized(teamCache) { teamCache[l.id] }?.let { return it }
        if (l.espnPath == null) return emptyList()
        val file = java.io.File(teamDir, "teams_${safeId(l.id)}.json")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < 7 * 24 * 3600_000L
        val raw = (if (fresh) runCatching { file.readText() }.getOrNull() else null)
            ?: runCatching { SportsFeeds().teamsRaw(l).also { runCatching { file.writeText(it) } } }.getOrNull()
            ?: runCatching { file.readText() }.getOrNull() ?: return emptyList()
        val list = runCatching { SportsFeeds.parseTeams(l, raw) }.getOrDefault(emptyList()).sortedBy { (it.location ?: it.fullName).lowercase(Locale.ROOT) }
        if (list.isNotEmpty()) synchronized(teamCache) { teamCache[l.id] = list }
        return list
    }
}
