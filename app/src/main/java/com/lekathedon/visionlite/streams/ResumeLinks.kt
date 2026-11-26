package com.lekathedon.visionlite.streams

import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.ui.Screen
import org.json.JSONObject

/**
 * Resume on the exact link (DebridStream's resume_links idea, written for Lite): the link you played last is kept per
 * episode / film, and Resume plays that same file again, with no new search. A torrent link is resolved again from its
 * hash and file (debrid links expire); Vision TB's again from TorBox; an add-on's own link is used as it was.
 * Dropped when you finish (80 %+), when the link was marked bad (LinkMemory), or after 60 days.
 * Cost: one small JSON pref (max 200 titles); on Resume one debrid/TorBox call instead of every add-on.
 */
object ResumeLinks {
    private const val DAYS60 = 60L * 24 * 3600_000L
    private const val MAX = 200
    private val prefs get() = App.instance.prefs

    @Synchronized private fun all(): JSONObject = runCatching { JSONObject(prefs.resumeLinks.value.ifBlank { "{}" }) }.getOrDefault(JSONObject())
    @Synchronized private fun save(o: JSONObject) {
        val now = System.currentTimeMillis()
        o.keys().asSequence().toList().forEach { k -> if (now - (o.optJSONObject(k)?.optLong("t") ?: 0L) > DAYS60) o.remove(k) }
        if (o.length() > MAX) o.keys().asSequence().toList().sortedBy { o.optJSONObject(it)?.optLong("t") ?: 0L }.take(o.length() - MAX).forEach { o.remove(it) }
        prefs.resumeLinks.value = o.toString()
    }

    /** Called when a link starts playing for [videoId] (Streams.play). */
    fun remember(videoId: String, l: Link) {
        // VOD is separate from normal Play (user 2026-11-26): a VOD copy is never normal Play's resume link.
        if (videoId.isBlank() || l.vod) return
        val st = l.stream
        val j = JSONObject().put("src", l.source).put("key", LinkMemory.keyOf(l)).put("t", System.currentTimeMillis()).put("pct", 0.0)
            .put("name", st.name).put("details", st.details).put("bytes", l.bytes)
        st.behaviorHints?.filename?.let { j.put("fn", it) }
        st.behaviorHints?.proxyHeaders?.request?.takeIf { it.isNotEmpty() }?.let { j.put("hdr", JSONObject(it)) }
        when {
            l.tb != null -> j.put("tb", l.tb.hash).put("tbf", l.tb.fileName ?: "")
            st.url.isNullOrBlank() && !st.infoHash.isNullOrBlank() -> { j.put("hash", st.infoHash); st.fileIdx?.let { j.put("idx", it) } }
            !st.url.isNullOrBlank() -> j.put("url", st.url)
            else -> return
        }
        val o = all(); o.put(videoId, j); save(o)
    }

    /** The player's position when it closes: finished (80 %+) drops the link, else its % is kept. */
    fun progress(videoId: String, pct: Float) {
        val o = all(); val j = o.optJSONObject(videoId) ?: return
        if (pct >= 80f) o.remove(videoId) else j.put("pct", pct.toDouble()).put("t", System.currentTimeMillis())
        save(o)
    }

    /** Play with VOD keeps its own % under "vod|id" (no link): normal Play and VOD never share a resume point. */
    fun vodProgress(videoId: String, pct: Float) {
        val o = all(); val k = "vod|$videoId"
        if (pct >= 80f) o.remove(k) else o.put(k, JSONObject().put("pct", pct.toDouble()).put("t", System.currentTimeMillis()))
        save(o)
    }

    /** The % saved with the link (Resume without Trakt), or -1. */
    fun pct(videoId: String): Float = all().optJSONObject(videoId)?.optDouble("pct", -1.0)?.toFloat() ?: -1f

    /** True when [videoId] has a link to resume on (stopped part-way, not marked bad). */
    fun has(videoId: String): Boolean = entry(videoId) != null

    private fun entry(videoId: String): JSONObject? {
        val j = all().optJSONObject(videoId) ?: return null
        if (j.optDouble("pct", 0.0) !in 2.0..92.0) return null
        if (LinkMemory.bad(j.optString("key")) != null) return null
        // A VOD copy saved before VOD was separate (0.41.0) is never resumed by normal Play.
        if (j.optString("src") == VodLinks.ADDON || j.optString("name").contains("\nVOD") || j.optString("details").contains(" VOD")) return null
        return j
    }

    /** The saved link's player screen, its link fetched again where needed; null = nothing saved or it no longer plays. */
    suspend fun screen(s: Screen.Streams): Screen.Player? {
        val j = entry(s.id) ?: return null
        val fn = j.optString("fn").ifBlank { null }
        val url = when {
            j.has("tb") -> com.lekathedon.visionlite.tb.VisionTb.resolve(com.lekathedon.visionlite.tb.VisionTb.Pick(j.getString("tb"), j.optString("tbf").ifBlank { null })).getOrNull()
            j.has("hash") -> if (Debrid.any()) Debrid.resolve(j.getString("hash"), if (j.has("idx")) j.optInt("idx") else null, fn, EpisodeCheck.targetOf(s.id)).getOrNull() else null
            else -> j.optString("url").ifBlank { null }
        } ?: return null
        val hdr = j.optJSONObject("hdr")?.let { h -> h.keys().asSequence().associateWith { h.optString(it) } }.orEmpty()
        val text = listOfNotNull(fn, j.optString("name").ifBlank { null }, j.optString("details").ifBlank { null }).joinToString("\n")
        return Screen.Player(url, s.title, hdr, s.type, s.id, text, j.optString("src"), j.optLong("bytes"))
    }
}
