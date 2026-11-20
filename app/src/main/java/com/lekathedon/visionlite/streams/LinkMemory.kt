package com.lekathedon.visionlite.streams

import com.lekathedon.visionlite.App
import org.json.JSONObject

/**
 * Haven's ideas, written for Lite (no Haven code):
 *  - Link memory, 7 days: a link that didn't play or kept buffering is marked, shown with a warning and moved down;
 *    autoplay skips it. A link that later plays well is cleared.
 *  - Your pick per show: the release you played (its release group and quality) is remembered per show, and the next
 *    episodes put the same release first ("same release all season"). Films keep it per film.
 *  - Speed check: a link that needs more than ~80 % of your measured internet speed (This TV's speed test) says so.
 * Cost: one small JSON pref (a few KB, pruned to 7 days / 300 shows); no network.
 */
object LinkMemory {
    private const val WEEK = 7 * 24 * 3600_000L
    private val prefs get() = App.instance.prefs

    enum class Bad(val text: String) { FAILED("Didn't play last time"), BUFFERED("Kept buffering last time") }

    /** One key per link: the add-on (or Vision TB) and the link's own text, the same on every visit. */
    fun keyOf(source: String, text: String): String = (source + "|" + text.trim()).hashCode().toUInt().toString(36)
    fun keyOf(l: Link): String = keyOf(l.source, textOf(l))
    fun textOf(l: Link): String = listOfNotNull(l.stream.behaviorHints?.filename, l.stream.name, l.stream.details).joinToString("\n")

    @Synchronized private fun marks(): JSONObject = runCatching { JSONObject(prefs.linkMarks.value.ifBlank { "{}" }) }.getOrDefault(JSONObject())

    @Synchronized private fun saveMarks(o: JSONObject) {
        val now = System.currentTimeMillis()
        o.keys().asSequence().toList().forEach { k -> if (now - o.optJSONObject(k)?.optLong("t").let { it ?: 0L } > WEEK) o.remove(k) }
        prefs.linkMarks.value = o.toString()
    }

    fun mark(key: String, bad: Bad) {
        val o = marks()
        o.put(key, JSONObject().put("k", bad.name).put("t", System.currentTimeMillis()))
        saveMarks(o)
    }

    fun clear(key: String) { val o = marks(); if (o.has(key)) { o.remove(key); saveMarks(o) } }

    fun bad(key: String): Bad? {
        val m = marks().optJSONObject(key) ?: return null
        if (System.currentTimeMillis() - m.optLong("t") > WEEK) return null
        return runCatching { Bad.valueOf(m.optString("k")) }.getOrNull()
    }
    fun bad(l: Link): Bad? = bad(keyOf(l))

    // ---- your pick per show (or film) ----

    /** The release group: the word after the last dash of a release name ("…x265-NTb.mkv" → "NTb"). */
    fun groupOf(text: String): String? {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.contains('.') || it.contains('-') } ?: return null
        val name = line.substringAfterLast('/').replace(Regex("""\.(mkv|mp4|avi|ts|m2ts|webm)$""", RegexOption.IGNORE_CASE), "")
        val g = Regex("""-([A-Za-z0-9]{2,20})(?:\[[^\]]*\])?$""").find(name)?.groupValues?.get(1) ?: return null
        return g.takeUnless { it.lowercase() in setOf("dl", "rip", "web", "hd", "x264", "x265", "h264", "h265", "hevc", "dts", "ddp", "aac") }
    }

    private fun heightOf(text: String): Int {
        val t = text.lowercase()
        return when { "2160" in t || "4k" in t || "uhd" in t -> 2160; "1080" in t -> 1080; "720" in t -> 720; else -> 0 }
    }

    /** Saves what you played for [videoId]'s show (or film): its group and height. Called once a link plays well. */
    fun rememberPick(videoId: String, source: String, text: String) {
        val show = videoId.substringBefore(':').ifBlank { return }
        val o = runCatching { JSONObject(prefs.linkPicks.value.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        o.put(show, JSONObject().put("g", groupOf(text) ?: "").put("h", heightOf(text)).put("s", source).put("t", System.currentTimeMillis()))
        if (o.length() > 300) o.keys().asSequence().toList().sortedBy { o.optJSONObject(it)?.optLong("t") ?: 0L }.take(o.length() - 300).forEach { o.remove(it) }
        prefs.linkPicks.value = o.toString()
    }

    /** 2 = same release group (and quality), 1 = same add-on and quality, 0 = nothing in common / no pick saved. */
    fun pickMatch(videoId: String, l: Link): Int {
        val show = videoId.substringBefore(':').ifBlank { return 0 }
        val p = runCatching { JSONObject(prefs.linkPicks.value.ifBlank { "{}" }).optJSONObject(show) }.getOrNull() ?: return 0
        val text = textOf(l)
        val g = p.optString("g")
        val h = p.optInt("h")
        if (g.isNotEmpty() && groupOf(text).equals(g, ignoreCase = true) && (h == 0 || heightOf(text) == h || l.height == h)) return 2
        if (p.optString("s") == l.source && h > 0 && (heightOf(text) == h || l.height == h)) return 1
        return 0
    }

    // ---- speed ----

    /** Measured speed (Mb/s) from This TV's check; null = never tested. */
    fun measuredMbps(): Double? = com.lekathedon.visionlite.device.DeviceCheck.saved()?.mbps

    /** The title's length in minutes from the details already fetched (no network); else 45 for an episode, 120 for a film. */
    fun minutesOf(type: String, metaId: String): Int {
        val r = App.instance.client.cachedMeta(type, metaId)?.runtime
        val n = r?.let { Regex("""(\d+)\s*h(?:\s*(\d+))?""").find(it)?.let { m -> m.groupValues[1].toInt() * 60 + (m.groupValues[2].toIntOrNull() ?: 0) }
            ?: Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        return n?.takeIf { it in 5..400 } ?: if (type == "series") 45 else 120
    }

    /** True when the link's average bitrate needs more than 80 % of your measured speed ([minutes] = the title's length). */
    fun tooFast(l: Link, minutes: Int): Boolean {
        val mbps = measuredMbps() ?: return false
        if (l.bytes <= 0 || minutes <= 0) return false
        val need = l.bytes * 8.0 / (minutes * 60.0) / 1_000_000.0
        return need > mbps * 0.8
    }

    /** The one warning a row shows (the worst wins), or null. */
    fun warning(l: Link, minutes: Int): String? = bad(l)?.text ?: if (tooFast(l, minutes)) "Needs a faster connection than yours" else null
}
