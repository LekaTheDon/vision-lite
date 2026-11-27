package com.lekathedon.visionlite.sport

import org.json.JSONArray
import org.json.JSONObject

// Sports, written fresh 2026-11-27 from spec/SPORTS.md (clean room). Data shapes only here.

/** A game's state as the card shows it. */
enum class State { PRE, LIVE, DONE }

data class Side(
    val id: String, val name: String, val short: String, val abbr: String,
    val logo: String?, val color: Long?, val score: String?, val record: String?, val home: Boolean,
)

/**
 * One game or event. [league] is the league's key in [Leagues] ("soccer/eng.1"); [title] is the event's own name
 * (races, golf, tennis, shows) and is used when there are no two sides.
 */
data class Game(
    val id: String, val league: String, val startMs: Long, val state: State, val detail: String,
    val sides: List<Side>, val title: String, val networks: List<String>, val venue: String? = null,
    val period: Int = 0, val clock: String? = null,
    /** Races, golf: the running order / leaderboard (score = position or to par). */
    val field: List<Side> = emptyList(),
) {
    val key get() = "$league:$id"
    val home get() = sides.firstOrNull { it.home }
    val away get() = sides.firstOrNull { !it.home }
    val versus get() = sides.size == 2
    fun has(teamId: String) = sides.any { it.id == teamId }
}

data class Team(val league: String, val id: String, val name: String, val abbr: String, val color: Long?, val logo: String?) {
    val key get() = "$league:$id"
}

data class Reminder(val game: String, val title: String, val startMs: Long, val autoSwitch: Boolean? = null)

/**
 * What you follow. Stored under the same pref and JSON keys as before the rewrite, so nobody loses follows.
 * [pins] = leagues on the top bar, [order] = their order ("Arrange My Sports").
 */
data class Follows(
    val teams: List<Team> = emptyList(), val leagues: Set<String> = emptySet(), val words: List<String> = emptyList(),
    val reminders: List<Reminder> = emptyList(), val setUp: Boolean = false, val pins: List<String> = emptyList(),
    val unpinned: Set<String> = emptySet(), val order: List<String> = emptyList(),
) {
    val empty get() = teams.isEmpty() && leagues.isEmpty() && words.isEmpty() && pins.isEmpty()
    fun follows(t: Team) = teams.any { it.key == t.key }
    fun follows(g: Game) = g.league in leagues || g.sides.any { s -> teams.any { it.league == g.league && it.id == s.id } } ||
        words.any { w -> g.title.contains(w, ignoreCase = true) }

    /** The top bar's leagues, in your order. */
    fun bar(): List<String> {
        val mine = pins.filter { Leagues.byKey(it) != null && it !in unpinned }.distinct()
        return order.filter { it in mine } + mine.filter { it !in order }
    }

    fun toJson(): String = JSONObject()
        .put("teams", JSONArray(teams.map { JSONObject().put("l", it.league).put("id", it.id).put("n", it.name).put("a", it.abbr).put("c", it.color).put("logo", it.logo) }))
        .put("leagues", JSONArray(leagues.toList())).put("words", JSONArray(words))
        .put("reminders", JSONArray(reminders.map { JSONObject().put("eventId", it.game).put("title", it.title).put("startMs", it.startMs).apply { it.autoSwitch?.let { a -> put("autoSwitch", a) } } }))
        .put("setUp", setUp).put("pins", JSONArray(pins)).put("unpinned", JSONArray(unpinned.toList())).put("order", JSONArray(order))
        .toString()

    companion object {
        fun of(raw: String?): Follows = runCatching {
            val o = JSONObject(raw ?: return Follows())
            Follows(
                teams = o.optJSONArray("teams").objs().map { Team(it.optString("l"), it.optString("id"), it.optString("n"), it.optString("a"),
                    if (it.has("c") && !it.isNull("c")) it.optLong("c") else null, it.optString("logo").ifBlank { null }) },
                leagues = o.optJSONArray("leagues").strs().map(Leagues::migrate).filterNotNull().toSet(),
                words = o.optJSONArray("words").strs(),
                reminders = o.optJSONArray("reminders").objs().map { Reminder(it.optString("eventId"), it.optString("title"), it.optLong("startMs"),
                    if (it.has("autoSwitch")) it.optBoolean("autoSwitch") else null) },
                setUp = o.optBoolean("setUp"),
                // Pins named after the old vocabulary ("taxonomy/…") become our own league keys where one fits.
                pins = o.optJSONArray("pins").strs().mapNotNull(Leagues::migrate).distinct(),
                unpinned = o.optJSONArray("unpinned").strs().mapNotNull(Leagues::migrate).toSet(),
                order = o.optJSONArray("order").strs().mapNotNull(Leagues::migrate).distinct(),
            )
        }.getOrDefault(Follows())
    }
}

/** Sports settings, same pref and JSON keys as before the rewrite. */
data class Settings(
    val showInSidebar: Boolean = true, val region: String = "", val hideScores: Boolean = false,
    val scoreOnInfoBar: Boolean = true, val bestChannelFirst: Boolean = true, val remindMyTeams: Boolean = true,
    val remindMinutes: Int = 15, val autoSwitch: Boolean = false, val scoringAlerts: Boolean = false,
    val alertDelaySec: Int = 30, val matchCategories: Boolean = true, val onlyOnMyChannels: Boolean = false,
    val videoWindow: Boolean = true, val backgroundGeminiMatching: Boolean = false,
    val hiddenLeagues: Set<String> = emptySet(),
    /** Your corrections: channel key per game / network / team / league / league network; channels said wrong. */
    val eventChannels: Map<String, String> = emptyMap(), val networkChannels: Map<String, String> = emptyMap(),
    val teamChannels: Map<String, String> = emptyMap(), val leagueChannels: Map<String, String> = emptyMap(),
    val leagueNetworkChannels: Map<String, String> = emptyMap(), val blocked: Set<String> = emptySet(),
    val preferredGroups: Set<String> = emptySet(),
) {
    fun toJson(): String = JSONObject()
        .put("showInSidebar", showInSidebar).put("region", region).put("hideScores", hideScores).put("scoreOnInfoBar", scoreOnInfoBar)
        .put("bestChannelFirst", bestChannelFirst).put("remindMyTeams", remindMyTeams).put("remindMinutes", remindMinutes)
        .put("autoSwitch", autoSwitch).put("scoringAlerts", scoringAlerts).put("alertDelaySec", alertDelaySec)
        .put("matchCategories", matchCategories).put("onlyMyChannels", onlyOnMyChannels).put("videoWindow", videoWindow)
        .put("backgroundGeminiMatching", backgroundGeminiMatching).put("hiddenLeagues", JSONArray(hiddenLeagues.toList()))
        .put("eventChannels", JSONObject(eventChannels)).put("networkChannels", JSONObject(networkChannels))
        .put("teamChannels", JSONObject(teamChannels)).put("leagueChannels", JSONObject(leagueChannels))
        .put("leagueNetworkChannels", JSONObject(leagueNetworkChannels)).put("blocked", JSONArray(blocked.toList()))
        .put("preferredGroups", JSONArray(preferredGroups.toList()))
        .toString()

    companion object {
        fun of(raw: String?): Settings = runCatching {
            val o = JSONObject(raw ?: return Settings()); val d = Settings()
            Settings(
                o.optBoolean("showInSidebar", d.showInSidebar), o.optString("region"), o.optBoolean("hideScores"),
                o.optBoolean("scoreOnInfoBar", d.scoreOnInfoBar), o.optBoolean("bestChannelFirst", d.bestChannelFirst),
                o.optBoolean("remindMyTeams", d.remindMyTeams), o.optInt("remindMinutes", d.remindMinutes),
                o.optBoolean("autoSwitch"), o.optBoolean("scoringAlerts"), o.optInt("alertDelaySec", d.alertDelaySec),
                o.optBoolean("matchCategories", d.matchCategories), o.optBoolean("onlyMyChannels"),
                o.optBoolean("videoWindow", d.videoWindow), o.optBoolean("backgroundGeminiMatching"),
                o.optJSONArray("hiddenLeagues").strs().toSet(),
                o.optJSONObject("eventChannels").strMap(), o.optJSONObject("networkChannels").strMap(),
                o.optJSONObject("teamChannels").strMap(), o.optJSONObject("leagueChannels").strMap(),
                o.optJSONObject("leagueNetworkChannels").strMap(), o.optJSONArray("blocked").strs().toSet(),
                o.optJSONArray("preferredGroups").strs().toSet(),
            )
        }.getOrDefault(Settings())
    }
}

internal fun JSONArray?.strs(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifBlank { null } }
internal fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject?.strMap(): Map<String, String> = if (this == null) emptyMap() else keys().asSequence().associateWith { optString(it) }
