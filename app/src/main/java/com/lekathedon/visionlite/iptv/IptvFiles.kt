package com.lekathedon.visionlite.iptv

import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.InputStream
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One channel line of a playlist, as written (numbering and keys come later, in [Iptv]). */
data class PlaylistItem(
    val name: String, val tvgId: String?, val tvgName: String?, val logo: String?, val group: String?, val chno: Int?,
    val url: String, val headers: Map<String, String>, val catchup: CatchupInfo?,
)

class PlaylistFile(val guides: List<String>, val items: List<PlaylistItem>)

/**
 * Extended M3U, read line by line (big "m3u_plus" lists run to 100,000+ lines). Understands what panels write:
 * tvg-* / group-title / catchup-* attributes (playlist-wide ones on #EXTM3U as defaults), #EXTGRP, request headers
 * from #EXTVLCOPT, #EXTHTTP {json}, #KODIPROP *_headers and "url|Header=value&...".
 */
object Playlists {
    private val CATCHUP_KEYS = listOf("catchup", "catchup-type", "catchup-source", "catchup-days", "catchup-correction", "tvg-rec", "timeshift")

    fun read(reader: BufferedReader): PlaylistFile {
        val guides = LinkedHashSet<String>()
        val items = ArrayList<PlaylistItem>(4096)
        val defaults = HashMap<String, String>()
        var info: String? = null
        var group: String? = null
        val headers = LinkedHashMap<String, String>()
        for (raw in reader.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty()) continue
            if (line[0] != '#') {
                val inf = info
                if (inf != null) items += item(inf, group, line, headers, defaults)
                info = null; group = null; headers.clear()
                continue
            }
            val tag = line.substringBefore(':').uppercase()
            val value = line.substringAfter(':', "")
            when {
                tag.startsWith("#EXTM3U") -> {
                    val a = attrs(line)
                    CATCHUP_KEYS.forEach { k -> a[k]?.takeIf { it.isNotBlank() }?.let { defaults[k] = it } }
                    for (k in listOf("url-tvg", "x-tvg-url", "tvg-url")) a[k]?.split(',')?.map { it.trim() }?.filter { it.startsWith("http") }?.let(guides::addAll)
                }
                tag == "#EXTINF" -> { info = line; group = null }        // header lines may come before #EXTINF: kept
                tag == "#EXTGRP" -> group = value.trim().ifEmpty { null }
                tag == "#EXTVLCOPT" -> when (value.substringBefore('=').trim().lowercase()) {
                    "http-user-agent" -> headers["User-Agent"] = value.substringAfter('=').trim()
                    "http-referrer", "http-referer" -> headers["Referer"] = value.substringAfter('=').trim()
                    "http-origin" -> headers["Origin"] = value.substringAfter('=').trim()
                }
                tag == "#EXTHTTP" -> runCatching { val o = JSONObject(value); o.keys().forEach { headers[header(it)] = o.optString(it) } }
                tag == "#KODIPROP" -> {
                    val k = value.substringBefore('=').trim().lowercase().substringAfterLast('.')
                    if (k == "stream_headers" || k == "manifest_headers" || k == "common_headers") pairs(value.substringAfter('='), headers)
                }
            }
        }
        return PlaylistFile(guides.toList(), items)
    }

    private fun item(info: String, extGroup: String?, line: String, extra: Map<String, String>, defaults: Map<String, String>): PlaylistItem {
        val a = HashMap(defaults).apply { putAll(attrs(info)) }
        val h = LinkedHashMap(extra)
        val url = line.substringBefore('|').trim()
        if ('|' in line) pairs(line.substringAfter('|'), h)
        (a["http-user-agent"] ?: a["user-agent"])?.let { h["User-Agent"] = it }
        a["http-referrer"]?.let { h["Referer"] = it }
        val type = a["catchup"] ?: a["catchup-type"]
        val days = (a["catchup-days"] ?: a["tvg-rec"] ?: a["timeshift"])?.trim()?.toIntOrNull()
        val catchup = if (type != null || (days ?: 0) > 0)
            CatchupInfo(type?.lowercase() ?: "default", a["catchup-source"], days ?: 1, a["catchup-correction"]?.replace(',', '.')?.toDoubleOrNull() ?: 0.0)
        else null
        fun v(vararg k: String) = k.firstNotNullOfOrNull { a[it]?.trim()?.ifEmpty { null } }
        return PlaylistItem(
            name = title(info).ifBlank { v("tvg-name") ?: "Channel" },
            tvgId = v("tvg-id"), tvgName = v("tvg-name"), logo = v("tvg-logo", "logo"),
            group = (v("group-title") ?: extGroup)?.substringBefore(';')?.trim(),
            chno = v("tvg-chno", "channel-number", "tvg-num")?.toIntOrNull(),
            url = url, headers = h, catchup = catchup,
        )
    }

    /** "A=1&B=2" (values URL-encoded) into [out]. */
    private fun pairs(s: String, out: MutableMap<String, String>) {
        for (p in s.split('&')) {
            val k = p.substringBefore('=').trim()
            if (k.isEmpty()) continue
            val v = p.substringAfter('=', "")
            out[header(k)] = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
        }
    }

    private fun header(k: String) = when (k.lowercase()) {
        "user-agent" -> "User-Agent"; "referer", "referrer" -> "Referer"; "origin" -> "Origin"; "cookie" -> "Cookie"; else -> k
    }

    /** The text after the first comma outside quotes. */
    private fun title(info: String): String {
        var quoted = false
        info.forEachIndexed { i, c -> if (c == '"') quoted = !quoted else if (c == ',' && !quoted) return info.substring(i + 1).trim() }
        return ""
    }

    /** key="value" / key=value pairs, keys lower-case; a quoted value beats a bare one, the first bare one counts. */
    internal fun attrs(line: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        val n = line.length
        while (i < n) {
            if (!line[i].isLetterOrDigit()) { i++; continue }
            val k0 = i
            while (i < n && (line[i].isLetterOrDigit() || line[i] == '-' || line[i] == '_')) i++
            val key = line.substring(k0, i).lowercase()
            var j = i
            while (j < n && line[j] == ' ') j++
            if (j >= n || line[j] != '=') continue
            j++
            while (j < n && line[j] == ' ') j++
            if (j < n && line[j] == '"') {
                val end = line.indexOf('"', j + 1)
                if (end < 0) { i = j + 1; continue }
                out[key] = line.substring(j + 1, end)
                i = end + 1
            } else {
                var e = j
                while (e < n && !line[e].isWhitespace() && line[e] != '"' && line[e] != ',') e++
                if (e > j && key != "extinf") out.putIfAbsent(key, line.substring(j, e))
                i = maxOf(e, j)
            }
        }
        return out
    }
}

/** A guide channel: its id and the names it goes by. */
class GuideChannel(val id: String, val names: List<String>)

/** One show as the guide store keeps it. */
class GuideShow(val startMs: Long, val stopMs: Long, val title: String, val description: String?, val category: String?)

/**
 * XMLTV, streamed: guides are often 50-200 MB, so nothing is held but one channel's run of shows. Shows outside
 * [fromMs, toMs] are skipped; which channels to keep is asked once, at the first show ([pick], from the channel
 * list; null = no list, then [keepUnlisted] per id). A broken file keeps what was read before the break.
 */
object Guides {
    fun read(
        input: InputStream, fromMs: Long, toMs: Long,
        keepUnlisted: (String) -> Boolean,
        onShows: (String, List<GuideShow>) -> Unit,
        pick: (Map<String, GuideChannel>) -> Set<String>?,
    ): String? {
        val x = Xml.newPullParser()
        x.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        runCatching { x.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        x.setInput(input, null)
        val channels = LinkedHashMap<String, GuideChannel>()
        var wanted: Set<String>? = null
        var picked = false
        val asked = HashMap<String, Boolean>()
        var runId: String? = null
        var run = ArrayList<GuideShow>()
        fun flush() { val id = runId ?: return; if (run.isNotEmpty()) onShows(id, run.sortedBy { it.startMs }); run = ArrayList(); runId = null }
        var error: String? = null
        try {
            var ev = x.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) when (x.name) {
                    "channel" -> channel(x)?.let { channels[it.id] = it }
                    "programme" -> {
                        if (!picked) { wanted = pick(channels); picked = true }
                        val id = x.getAttributeValue(null, "channel")
                        val keep = id != null && (wanted?.contains(id) ?: asked.getOrPut(id) { keepUnlisted(id) })
                        val show = if (keep) show(x, fromMs, toMs) else { skip(x); null }
                        if (show != null) { if (runId != id) { flush(); runId = id }; run += show }
                    }
                }
                ev = x.next()
            }
        } catch (e: Exception) {
            error = "line ${runCatching { x.lineNumber }.getOrDefault(-1)}: ${e.message?.take(140) ?: e.javaClass.simpleName}"
        }
        runCatching { flush() }
        return error
    }

    private fun channel(x: XmlPullParser): GuideChannel? {
        val id = x.getAttributeValue(null, "id") ?: run { skip(x); return null }
        val names = ArrayList<String>(2)
        children(x) { if (x.name == "display-name") text(x).trim().takeIf { it.isNotEmpty() }?.let(names::add) else skip(x) }
        return GuideChannel(id, names)
    }

    private fun show(x: XmlPullParser, fromMs: Long, toMs: Long): GuideShow? {
        val start = time(x.getAttributeValue(null, "start"))
        val stop = time(x.getAttributeValue(null, "stop"))
        if (start == null || stop == null || stop <= fromMs || start >= toMs) { skip(x); return null }
        var title: String? = null; var sub: String? = null; var desc: String? = null; var cat: String? = null
        children(x) {
            when (x.name) {
                "title" -> text(x).let { if (title == null) title = it }
                "sub-title" -> text(x).let { if (sub == null) sub = it }
                "desc" -> text(x).let { if (desc == null) desc = it }
                "category" -> text(x).let { if (cat == null) cat = it }
                else -> skip(x)
            }
        }
        val about = listOfNotNull(sub?.trim()?.ifEmpty { null }, desc?.trim()?.ifEmpty { null }).joinToString("\n").ifEmpty { null }
        return GuideShow(start, stop, title?.trim()?.ifEmpty { null } ?: "No information",
            about?.let { if (it.length > 800) it.take(800).trimEnd() + "…" else it }, cat?.trim()?.ifEmpty { null })
    }

    /** Runs [each] at every direct child start tag of the current element; returns at its end tag. */
    private inline fun children(x: XmlPullParser, each: () -> Unit) {
        val depth = x.depth
        while (true) {
            val ev = x.next()
            if (ev == XmlPullParser.END_DOCUMENT || ev == XmlPullParser.END_TAG && x.depth == depth) return
            if (ev == XmlPullParser.START_TAG) each()
        }
    }

    /** The element's own text; nested tags are skipped. */
    private fun text(x: XmlPullParser): String {
        val sb = StringBuilder()
        val depth = x.depth
        while (true) {
            val ev = x.next()
            if (ev == XmlPullParser.END_DOCUMENT || ev == XmlPullParser.END_TAG && x.depth == depth) return sb.toString()
            if (ev == XmlPullParser.TEXT) sb.append(x.text) else if (ev == XmlPullParser.START_TAG) skip(x)
        }
    }

    private fun skip(x: XmlPullParser) {
        if (x.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth > 0) when (x.next()) {
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.END_DOCUMENT -> return
        }
    }

    /** "20261128193000 +0300" (offset optional, UTC then; seconds optional) -> epoch ms. */
    internal fun time(s: String?): Long? {
        val t = s?.trim() ?: return null
        var d = 0
        while (d < t.length && t[d].isDigit()) d++
        if (d < 12) return null
        fun n(a: Int, b: Int) = t.substring(a, b).toInt()
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.clear()
        c.set(n(0, 4), n(4, 6) - 1, n(6, 8), n(8, 10), n(10, 12), if (d >= 14) n(12, 14) else 0)
        var ms = c.timeInMillis
        val z = t.substring(d).trim()
        if (z.length >= 5 && (z[0] == '+' || z[0] == '-') && z.substring(1, 5).all { it.isDigit() }) {
            val off = (z.substring(1, 3).toInt() * 60 + z.substring(3, 5).toInt()) * 60_000L
            ms -= if (z[0] == '+') off else -off
        }
        return ms
    }
}

/**
 * Catch-up links: replays of past shows, the way the playlist says (catchup type + source template), else the
 * panel's own forms: Xtream /timeshift/ links, Flussonic archive names, or ?utc= shift.
 */
object Replay {
    fun available(c: CatchupInfo?, startMs: Long, nowMs: Long): Boolean =
        c != null && startMs < nowMs && nowMs - startMs <= c.days.coerceAtLeast(1) * 86_400_000L

    fun link(live: String, c: CatchupInfo, startMs: Long, stopMs: Long, nowMs: Long = System.currentTimeMillis()): String? {
        val fix = (c.correctionHours * 3_600_000).toLong()
        val t = Times((startMs + fix) / 1000, (stopMs + fix) / 1000, nowMs / 1000)
        val src = c.source?.takeIf { it.isNotBlank() }
        return when (c.type) {
            "append" -> src?.let { live + t.fill(it) }
            "default" -> when {
                src == null -> xtream(live, t) ?: shift(live, t)
                src.startsWith("http", true) -> t.fill(src)
                else -> live + t.fill(src)
            }
            "shift", "timeshift" -> if (src == null) xtream(live, t) ?: shift(live, t) else shift(live, t)
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> flussonic(live, t)
            "xc", "xtream" -> xtream(live, t)
            else -> src?.let(t::fill)
        }
    }

    private class Times(val start: Long, val end: Long, val now: Long) {
        val duration = (end - start).coerceAtLeast(60)

        /** {utc} {start} {end} {utcend} {now} {lutc} {timestamp} {duration[:div]} {offset[:div]}, {name:Y-m-d H:M:S}, bare {Y}..{S} (local). */
        fun fill(template: String): String = TOKEN.replace(template) { m ->
            val key = m.groupValues[1]; val arg = m.groupValues[2].ifEmpty { null }
            val at = when (key) { "utcend", "end" -> end; "lutc", "now", "timestamp" -> now; else -> start }
            when (key) {
                "duration" -> (duration / (arg?.toLongOrNull()?.coerceAtLeast(1) ?: 1)).toString()
                "offset" -> ((now - start) / (arg?.toLongOrNull()?.coerceAtLeast(1) ?: 1)).toString()
                "utc", "start", "utcend", "end", "lutc", "now", "timestamp" -> if (arg == null) at.toString() else stamp(arg, at, TimeZone.getTimeZone("UTC"))
                "Y", "m", "d", "H", "M", "S" -> stamp(key, start, TimeZone.getDefault())
                else -> m.value
            }
        }

        private fun stamp(pattern: String, sec: Long, zone: TimeZone): String {
            val java = pattern.map { c -> when (c) { 'Y' -> "yyyy"; 'm' -> "MM"; 'd' -> "dd"; 'H' -> "HH"; 'M' -> "mm"; 'S' -> "ss"; else -> if (c.isLetter()) "'$c'" else "$c" } }.joinToString("")
            return runCatching { SimpleDateFormat(java, Locale.US).apply { timeZone = zone }.format(Date(sec * 1000)) }.getOrDefault(sec.toString())
        }
    }

    private val TOKEN = Regex("""\$?\{(utc|start|utcend|end|lutc|now|timestamp|duration|offset|[YmdHMS])(?::([^}]*))?\}""")
    private val XTREAM = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(\.[a-z0-9]+)?(\?.*)?$""", RegexOption.IGNORE_CASE)
    private val FLUSSONIC = Regex("""^(https?://[^/]+)/(.*)/([^/?]*)(\?.*)?$""")

    private fun shift(url: String, t: Times) = url + (if ('?' in url) '&' else '?') + "utc=${t.start}&lutc=${t.now}"

    /** host/user/pass/id(.ext) -> host/timeshift/user/pass/minutes/yyyy-MM-dd:HH-mm/id.ext (panel clock = device clock). */
    private fun xtream(url: String, t: Times): String? {
        val m = XTREAM.find(url) ?: return null
        val (host, user, pass, id) = m.destructured
        val when_ = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).format(Date(t.start * 1000))
        return "$host/timeshift/$user/$pass/${(t.duration / 60).coerceAtLeast(1)}/$when_/$id${m.groupValues[5].ifEmpty { ".ts" }}${m.groupValues[6]}"
    }

    private fun flussonic(url: String, t: Times): String? {
        val m = FLUSSONIC.find(url) ?: return null
        val (host, stream, file) = m.destructured
        val q = m.groupValues[4]
        return if (file.isEmpty() || file.endsWith(".m3u8")) "$host/$stream/${file.removeSuffix(".m3u8").ifEmpty { "index" }}-${t.start}-${t.duration}.m3u8$q"
        else "$host/$stream/timeshift_abs-${t.start}.ts$q"
    }
}
