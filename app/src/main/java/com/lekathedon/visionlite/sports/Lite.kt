// The few pieces of Vision's Live TV that its Sports code (copied as is) leans on, built over Lite's own IPTV store.
package com.lekathedon.visionlite.sports

import com.lekathedon.visionlite.iptv.CatchupInfo
import com.lekathedon.visionlite.iptv.DrmInfo
import com.lekathedon.visionlite.iptv.EpgProgram
import com.lekathedon.visionlite.iptv.Iptv

/** Vision's channel model (the matcher reads it). [key] is Lite's "playlist|url". */
data class LiveChannel(
    val key: String, val sourceId: String, val sourceName: String, val name: String, val tvgId: String?, val tvgName: String?,
    val logo: String?, val groupId: String, val group: String, val number: Int, val url: String,
    val headers: Map<String, String> = emptyMap(), val catchup: CatchupInfo? = null, val drm: DrmInfo? = null
)

/** Vision's manual guide assignment; Lite has none, the matcher only reads it for replays. */
data class EpgAssignment(val sourceId: String, val xmltvId: String) {
    val isNone: Boolean get() = sourceId == NONE
    companion object { const val NONE = "__none__" }
}

private val TWENTY_FOUR_SEVEN = Regex("""(?i)\b24\s*[/\\|\-x]?\s*7\b""")
internal fun is247(c: LiveChannel): Boolean = TWENTY_FOUR_SEVEN.containsMatchIn(c.name) || TWENTY_FOUR_SEVEN.containsMatchIn(c.group) ||
    (c.tvgName?.let { TWENTY_FOUR_SEVEN.containsMatchIn(it) } ?: false)

/** A followed team plays, a league you follow, a name you follow, or a reminder you set (Vision's SportsUi). */
internal fun isMine(ev: SportsEvent, f: SportsFollows): Boolean =
    f.leagues.any { SportsTaxonomy.includesLeague(it, ev.leagueId) } || ev.word != null ||
        f.pins.any { SportsCatalog.league(it)?.kind == LeagueKind.EVENT && SportsTaxonomy.includesLeague(it, ev.leagueId) } ||
        ev.sides.any { f.followsTeam(ev.leagueId, it.team.id) } ||
        f.reminders.any { it.eventId == ev.id }

internal fun endEstimate(ev: SportsEvent): Long {
    val league = SportsCatalog.league(ev.leagueId)
    return ev.endMs?.takeIf { ev.hasTime } ?: (ev.startMs + (league?.minutes ?: 120) * 60_000L)
}

/**
 * Vision's EpgDatabase calls, answered from Lite's guide (iptv.db). Lite keeps shows by playlist + guide id; these
 * queries join them to every channel carrying that id, so each show comes back under the channel's key, as in Vision.
 */
internal class EpgDatabase {
    private val db get() = Iptv.guideDb()
    private val join = "SELECT c.pl||'|'||c.url, p.start, p.stop, p.title, p.descr, p.cat FROM prog p JOIN ch c ON c.pl=p.pl AND c.tvg=p.tvg"

    fun fingerprint(): String? = Iptv.guideRevision()
    fun contentRevision(): String? = Iptv.guideRevision()

    private fun read(sql: String, args: Array<String>, each: (String, EpgProgram) -> Unit) {
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) each(c.getString(0), EpgProgram(c.getLong(1), c.getLong(2), c.getString(3),
                if (c.isNull(4)) null else c.getString(4), if (c.isNull(5)) null else c.getString(5)))
        }
    }

    fun range(fromMs: Long, toMs: Long, onlyChannel: String? = null, @Suppress("UNUSED_PARAMETER") light: Boolean = false,
              channels: Collection<String>? = null): Map<String, List<EpgProgram>> {
        val out = HashMap<String, ArrayList<EpgProgram>>()
        val time = arrayOf((fromMs - 12 * 3600_000L).toString(), toMs.toString(), fromMs.toString())
        val keys = onlyChannel?.let { listOf(it) } ?: channels?.distinct()
        val where = "$join WHERE p.start >= ? AND p.start < ? AND p.stop > ?"
        if (keys == null) read(where, time) { k, p -> out.getOrPut(k) { ArrayList() } += p }
        else keys.chunked(400).forEach { part ->
            read("$where AND c.pl||'|'||c.url IN (${part.joinToString(",") { "?" }})", time + part) { k, p -> out.getOrPut(k) { ArrayList() } += p }
        }
        out.values.forEach { l -> l.sortBy { it.startMs } }
        return out
    }

    fun visitPrograms(fromMs: Long, toMs: Long, visit: (String, EpgProgram) -> Unit) {
        var start = fromMs
        while (start < toMs) {
            val end = minOf(toMs, start + 15 * 60_000L)
            read("$join WHERE p.start >= ? AND p.start < ?", arrayOf(start.toString(), end.toString()), visit)
            start = end
        }
    }

    fun findByWords(fromMs: Long, toMs: Long, minStopMs: Long, words: Collection<String>, inDescription: Boolean = true,
                    limit: Int = 4_000): List<Pair<String, EpgProgram>> {
        val clean = words.map { it.trim() }.filter { it.length >= 3 }.distinct()
        if (clean.isEmpty()) return emptyList()
        val out = ArrayList<Pair<String, EpgProgram>>()
        val seen = HashSet<String>()
        fun like(w: String) = "%" + w.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        clean.chunked(if (inDescription) 180 else 360).forEach { part ->
            if (out.size >= limit) return@forEach
            val conds = part.flatMap { if (inDescription) listOf("p.title LIKE ? ESCAPE '\\'", "p.descr LIKE ? ESCAPE '\\'") else listOf("p.title LIKE ? ESCAPE '\\'") }
                .joinToString(" OR ")
            val args = arrayOf(fromMs.toString(), toMs.toString(), minStopMs.toString()) +
                part.flatMap { w -> if (inDescription) listOf(like(w), like(w)) else listOf(like(w)) }
            read("$join WHERE p.start >= ? AND p.start < ? AND p.stop > ? AND ($conds) LIMIT ${limit - out.size}", args) { k, p ->
                if (seen.add("$k|${p.startMs}")) out += k to p
            }
        }
        return out
    }
}
