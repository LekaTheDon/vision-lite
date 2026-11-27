package com.lekathedon.visionlite.sport

import com.lekathedon.visionlite.data.Net
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * ESPN's public site API (no key). Every call runs on IO (Net.get) and is parsed with org.json, field by field, so a
 * change on their side costs a missing value, never a crash.
 */
object Espn {
    private const val SITE = "https://site.api.espn.com/apis/site/v2/sports"
    private const val WEB = "https://site.web.api.espn.com/apis/v2/sports"
    private val ymd = DateTimeFormatter.BASIC_ISO_DATE
    /** ESPN's days are US Eastern dates. */
    val eastern: ZoneId = ZoneId.of("America/New_York")

    suspend fun scoreboard(league: String, day: LocalDate): List<Game> =
        parseBoard(league, JSONObject(Net.get("$SITE/$league/scoreboard?dates=${day.format(ymd)}&limit=300", 15)))

    /** A team's season (results and games to come). */
    suspend fun schedule(league: String, team: String): List<Game> =
        parseBoard(league, JSONObject(Net.get("$SITE/$league/teams/$team/schedule", 15)))

    suspend fun teams(league: String): List<Team> {
        val o = JSONObject(Net.get("$SITE/$league/teams?limit=500", 15))
        return o.optJSONArray("sports").objs().flatMap { it.optJSONArray("leagues").objs() }.flatMap { it.optJSONArray("teams").objs() }
            .mapNotNull { it.optJSONObject("team") }.map { t ->
                Team(league, t.optString("id"), t.optString("displayName"), t.optString("abbreviation"), color(t.optString("color")),
                    t.optJSONArray("logos").objs().firstOrNull()?.optString("href")?.ifBlank { null })
            }.filter { it.id.isNotEmpty() }.sortedBy { it.name }
    }

    fun parseBoard(league: String, o: JSONObject): List<Game> =
        o.optJSONArray("events").objs().mapNotNull { e -> runCatching { game(league, e) }.getOrNull() }

    private fun game(league: String, e: JSONObject): Game? {
        val id = e.optString("id").ifBlank { return null }
        val c = e.optJSONArray("competitions").objs().firstOrNull()
        val st = (c?.optJSONObject("status") ?: e.optJSONObject("status"))
        val type = st?.optJSONObject("type")
        val state = when (type?.optString("state")) { "in" -> State.LIVE; "post" -> State.DONE; else -> State.PRE }
        val sides = c?.optJSONArray("competitors").objs().orEmpty().mapNotNull { side(it) }
        val nets = c?.optJSONArray("broadcasts").objs().orEmpty().flatMap { it.optJSONArray("names").strs() } +
            c?.optJSONArray("geoBroadcasts").objs().orEmpty().mapNotNull { it.optJSONObject("media")?.optString("shortName")?.ifBlank { null } }
        return Game(
            id, league, time(e.optString("date")), state,
            type?.optString("shortDetail")?.ifBlank { null } ?: type?.optString("detail").orEmpty(),
            if (sides.size == 2) sides else emptyList(),
            e.optString("name").ifBlank { e.optString("shortName") },
            nets.distinct(), c?.optJSONObject("venue")?.optString("fullName")?.ifBlank { null },
            st?.optInt("period") ?: 0, st?.optString("displayClock")?.ifBlank { null },
        )
    }

    private fun side(o: JSONObject): Side? {
        val t = o.optJSONObject("team") ?: o.optJSONObject("athlete") ?: return null
        val score = o.opt("score")?.let { if (it is JSONObject) it.optString("displayValue") else it.toString() }?.ifBlank { null }
        return Side(t.optString("id"), t.optString("displayName"), t.optString("shortDisplayName").ifBlank { t.optString("displayName") },
            t.optString("abbreviation"), t.optString("logo").ifBlank { null } ?: t.optJSONArray("logos").objs().firstOrNull()?.optString("href"),
            color(t.optString("color")), score, o.optJSONArray("records").objs().firstOrNull()?.optString("summary")?.ifBlank { null },
            o.optString("homeAway") == "home")
    }

    private fun color(hex: String?): Long? = hex?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { 0xFF000000 or it }
    private fun time(iso: String): Long = runCatching { Instant.parse(iso.let { if (it.length == 17) it.dropLast(1) + ":00Z" else it }).toEpochMilli() }.getOrDefault(0L)

    // ---- game page ----

    data class Play(val text: String, val clock: String?, val team: String?, val score: String?)
    data class Stat(val label: String, val home: String, val away: String)
    data class Leader(val label: String, val name: String, val value: String, val team: String?)
    data class Summary(val plays: List<Play>, val stats: List<Stat>, val leaders: List<Leader>, val lines: List<Pair<String, List<String>>>,
                       val form: Map<String, String>, val winChance: Map<String, Double>)

    suspend fun summary(league: String, id: String): Summary {
        val o = JSONObject(Net.get("$SITE/$league/summary?event=$id", 15))
        val plays = (o.optJSONArray("scoringPlays").objs().map { p ->
            Play(p.optString("text"), p.optJSONObject("clock")?.optString("displayValue"), p.optJSONObject("team")?.optString("id"),
                listOf(p.optString("awayScore"), p.optString("homeScore")).takeIf { it.all { s -> s.isNotBlank() } }?.joinToString("–"))
        } + o.optJSONArray("keyEvents").objs().filter { it.optBoolean("scoringPlay") || it.optJSONObject("type")?.optString("type")?.contains("goal") == true }.map { p ->
            Play(p.optString("text").ifBlank { p.optJSONObject("type")?.optString("text").orEmpty() }, p.optJSONObject("clock")?.optString("displayValue"),
                p.optJSONObject("team")?.optString("id"), null)
        }).filter { it.text.isNotBlank() }
        val box = o.optJSONObject("boxscore")?.optJSONArray("teams").objs()
        val stats = if (box.size == 2) {
            val a = box[0].optJSONArray("statistics").objs(); val b = box[1].optJSONArray("statistics").objs()
            val home = if (box[1].optString("homeAway") == "home") b else a; val away = if (home === b) a else b
            home.mapNotNull { h -> val n = h.optString("name"); away.firstOrNull { it.optString("name") == n }?.let { w ->
                Stat(h.optString("label").ifBlank { n }, h.optString("displayValue"), w.optString("displayValue")) } }.take(14)
        } else emptyList()
        val leaders = o.optJSONArray("leaders").objs().flatMap { t -> t.optJSONArray("leaders").objs().mapNotNull { cat ->
            cat.optJSONArray("leaders").objs().firstOrNull()?.let { l -> Leader(cat.optString("displayName"), l.optJSONObject("athlete")?.optString("shortName").orEmpty(),
                l.optString("displayValue"), t.optJSONObject("team")?.optString("id")) } } }.take(6)
        val comp = o.optJSONObject("header")?.optJSONArray("competitions").objs().firstOrNull()
        val lines = comp?.optJSONArray("competitors").objs().orEmpty().map { s ->
            s.optJSONObject("team")?.optString("abbreviation").orEmpty() to s.optJSONArray("linescores").objs().map { it.optString("displayValue").ifBlank { it.optString("value") } } }
            .filter { it.second.isNotEmpty() }
        val form = o.optJSONArray("lastFiveGames").objs().associate { t -> t.optJSONObject("team")?.optString("id").orEmpty() to
            t.optJSONArray("events").objs().joinToString("") { it.optString("gameResult").take(1) } }
        val wp = o.optJSONObject("predictor")?.let { p -> listOfNotNull(
            p.optJSONObject("homeTeam")?.let { it.optString("id") to it.optDouble("gameProjection", Double.NaN) },
            p.optJSONObject("awayTeam")?.let { it.optString("id") to it.optDouble("gameProjection", Double.NaN) }).filter { !it.second.isNaN() }.toMap() }.orEmpty()
        return Summary(plays, stats, leaders, lines, form, wp)
    }

    // ---- tables ----

    data class Row(val team: Team, val cols: List<String>, val note: String?, val noteColor: Long?, val clinch: String?)
    data class Group(val name: String, val heads: List<String>, val rows: List<Row>)

    suspend fun standings(l: League): List<Group> {
        val o = JSONObject(Net.get("$WEB/${l.key}/standings", 15))
        val groups = (o.optJSONArray("children").objs().ifEmpty { listOf(o) }).flatMap { g ->
            val inner = g.optJSONArray("children").objs()
            if (inner.isNotEmpty()) inner.map { it.optString("name") to it.optJSONObject("standings") } else listOf(g.optString("name") to g.optJSONObject("standings"))
        }
        val cols = when (l.table) {
            Table.US -> listOf("wins" to "W", "losses" to "L", "winPercent" to "PCT", "gamesBehind" to "GB", "streak" to "STRK")
            Table.HOCKEY -> listOf("gamesPlayed" to "GP", "wins" to "W", "losses" to "L", "otLosses" to "OTL", "points" to "PTS")
            Table.DRIVERS -> listOf("wins" to "W", "points" to "PTS")
            else -> listOf("gamesPlayed" to "P", "wins" to "W", "ties" to "D", "losses" to "L", "pointDifferential" to "GD", "points" to "PTS")
        }
        return groups.mapNotNull { (name, s) ->
            val rows = s?.optJSONArray("entries").objs().orEmpty().map { e ->
                val t = e.optJSONObject("team") ?: e.optJSONObject("athlete") ?: JSONObject()
                val stats = e.optJSONArray("stats").objs().associate { it.optString("name") to it.optString("displayValue") }
                val note = e.optJSONObject("note")
                Row(Team(l.key, t.optString("id"), t.optString("displayName"), t.optString("abbreviation"), null,
                        t.optJSONArray("logos").objs().firstOrNull()?.optString("href")),
                    cols.map { stats[it.first].orEmpty() }, note?.optString("description")?.ifBlank { null }, color(note?.optString("color")?.removePrefix("#")),
                    stats["clincher"]?.ifBlank { null })
            }
            if (rows.isEmpty()) null else Group(name, cols.map { it.second }, rows)
        }
    }

    fun today(zone: ZoneId = ZoneId.systemDefault()): LocalDate = LocalDate.now(zone)
}
