package com.lekathedon.visionlite.sport

import com.lekathedon.visionlite.data.Net
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

/**
 * TheSportsDB (free tier, no user key) for leagues ESPN doesn't carry: Qatar, UAE, Egypt, Kuwait, Saudi cups… League
 * keys are "tsdb/<id>". Same Game / Team / table shapes as [Espn], so the screens can't tell. Its free tier has no
 * minute-by-minute live data: a game shows as on during its usual length and the score lands when it ends.
 */
object Tsdb {
    private const val API = "https://www.thesportsdb.com/api/v1/json/123"
    fun id(league: String) = league.removePrefix("tsdb/")

    fun dayUrl(league: String, day: LocalDate) = "$API/eventsday.php?d=$day&l=${id(league)}"

    fun parseBoard(league: String, o: JSONObject): List<Game> =
        o.optJSONArray("events").objs().mapNotNull { runCatching { game(league, it) }.getOrNull() }

    private fun game(league: String, e: JSONObject): Game? {
        val id = e.optString("idEvent").ifBlank { return null }
        val start = runCatching { Instant.parse(e.optString("strTimestamp").take(19) + "Z").toEpochMilli() }.getOrDefault(0L)
        val status = e.optString("strStatus").uppercase()
        val hs = e.optString("intHomeScore").takeIf { it.isNotBlank() && it != "null" }
        val aw = e.optString("intAwayScore").takeIf { it.isNotBlank() && it != "null" }
        val mins = Leagues.byKey(league)?.minutes ?: 120
        val now = System.currentTimeMillis()
        val state = when {
            status in setOf("FT", "AET", "PEN", "AP", "MATCH FINISHED", "AWD", "WO") -> State.DONE
            status in setOf("1H", "HT", "2H", "ET", "BT", "P", "LIVE", "INT") -> State.LIVE
            status in setOf("PST", "CANC", "ABD", "SUSP") -> State.PRE
            start > 0 && now in start until start + mins * 60_000L -> State.LIVE
            start > 0 && now >= start + mins * 60_000L && hs != null -> State.DONE
            else -> State.PRE
        }
        fun side(home: Boolean) = Side(
            e.optString(if (home) "idHomeTeam" else "idAwayTeam"), e.optString(if (home) "strHomeTeam" else "strAwayTeam"),
            e.optString(if (home) "strHomeTeam" else "strAwayTeam"), "", e.optString(if (home) "strHomeTeamBadge" else "strAwayTeamBadge").ifBlank { null },
            null, if (state == State.PRE) null else if (home) hs else aw, null, home)
        val detail = when (state) { State.DONE -> "FT"; State.LIVE -> if (status.length in 1..3 && status != "NS") status else "Live"; else -> if (status in setOf("PST", "CANC")) status else "" }
        return Game(id, league, start, state, detail, listOf(side(true), side(false)), e.optString("strEvent"),
            listOfNotNull(e.optString("strTVStation").takeIf { it.isNotBlank() && it != "null" }), e.optString("strVenue").ifBlank { null })
    }

    suspend fun teams(league: String): List<Team> =
        JSONObject(Net.get("$API/lookup_all_teams.php?id=${id(league)}", 15)).optJSONArray("teams").objs().map { t ->
            Team(league, t.optString("idTeam"), t.optString("strTeam"), t.optString("strTeamShort").ifBlank { t.optString("strTeam").take(3).uppercase() },
                t.optString("strColour1").removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { 0xFF000000 or it }, t.optString("strBadge").ifBlank { null })
        }.filter { it.id.isNotEmpty() }.sortedBy { it.name }

    /** A team's last and next games. */
    suspend fun schedule(league: String, team: String): List<Game> {
        val last = runCatching { JSONObject(Net.get("$API/eventslast.php?id=$team", 15)).optJSONArray("results").objs() }.getOrDefault(emptyList())
        val next = runCatching { JSONObject(Net.get("$API/eventsnext.php?id=$team", 15)).optJSONArray("events").objs() }.getOrDefault(emptyList())
        return (last + next).mapNotNull { runCatching { game(league, it) }.getOrNull() }.distinctBy { it.id }.sortedBy { it.startMs }
    }

    suspend fun standings(l: League): List<Espn.Group> {
        val season = JSONObject(Net.get("$API/lookupleague.php?id=${id(l.key)}", 15)).optJSONArray("leagues").objs().firstOrNull()
            ?.optString("strCurrentSeason")?.ifBlank { null } ?: return emptyList()
        val rows = JSONObject(Net.get("$API/lookuptable.php?l=${id(l.key)}&s=$season", 15)).optJSONArray("table").objs()
        return rows.groupBy { it.optString("strGroup") }.map { (name, list) ->
            Espn.Group(name, listOf("P", "W", "D", "L", "GD", "PTS"), list.sortedBy { it.optInt("intRank") }.map { r ->
                Espn.Row(Team(l.key, r.optString("idTeam"), r.optString("strTeam"), "", null, r.optString("strBadge").ifBlank { null }),
                    listOf("intPlayed", "intWin", "intDraw", "intLoss", "intGoalDifference", "intPoints").map { r.optString(it) },
                    r.optString("strDescription").ifBlank { null }, null, null)
            })
        }
    }
}
