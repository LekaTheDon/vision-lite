@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lekathedon.visionlite.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.data.Net
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloaded subtitles made right before they show (DebridStream's "strip SDH" and "auto-sync to the embedded track",
 * written for Lite):
 *  - Clean-up (Settings › Audio and subtitles › Remove hearing-impaired tags): [door creaks], (sighs), "JOHN:" and lone ♪
 *    leave the add-on's SRT / VTT as it loads; a line left empty goes. Text in a legacy code page (Arabic Windows-1256 …)
 *    is turned into UTF-8 so it never shows as garbage.
 *  - Auto-sync (… › Auto-sync downloaded subtitles): while the file downloads, the times of its own subtitle lines are
 *    noted (any language, nothing is shown). The add-on subtitle you picked is matched against them section by
 *    section (the last ~5 minutes around you) and moved by the offset most lines agree on, so a copy made for another
 *    cut, frame rate or with ads cut out lines up again. Needs a file with text subtitles of its own; ExoPlayer only.
 *    Your own Sync delay stays on top of it.
 * Cost: the subtitle (≈50-100 KB) is held in memory once; a list of times (8 bytes a line); one comparison every 15 s
 * while an add-on subtitle is on. No extra network.
 */
object SubFix {
    private const val TAG = "SubFix"

    /** Add-on subtitle URL → its lines' start times (ms), filled when it loads. */
    private val external = ConcurrentHashMap<String, LongArray>()
    /** Embedded text track id → its lines' start times (ms) seen so far. */
    private val embedded = ConcurrentHashMap<Int, java.util.concurrent.ConcurrentSkipListSet<Long>>()
    @Volatile private var urls: Set<String> = emptySet()

    /** A new file: forget the last one's times; [subs] = the add-on subtitle URLs this file may load. */
    fun begin(subs: Collection<String>) { external.clear(); embedded.clear(); urls = subs.toSet(); autoMs = 0L; Sync.autoSubMs = 0L }

    // ---------------------------------------------------------------- clean-up while loading

    fun factory(inner: DataSource.Factory): DataSource.Factory = DataSource.Factory { Source(inner.createDataSource()) }

    private class Source(private val inner: DataSource) : DataSource {
        private var bytes: ByteArray? = null
        private var pos = 0
        private var uri: Uri? = null
        override fun addTransferListener(transferListener: TransferListener) = inner.addTransferListener(transferListener)
        override fun open(dataSpec: DataSpec): Long {
            val u = dataSpec.uri.toString()
            if (u !in urls) { bytes = null; return inner.open(dataSpec) }
            uri = dataSpec.uri
            val raw = Net.http.newCall(Request.Builder().url(u).build()).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("subtitle HTTP ${r.code}")
                r.body?.bytes() ?: ByteArray(0)
            }
            val out = runCatching { fix(u, raw) }.onFailure { Log.w(TAG, "left as it was: ${it.message}") }.getOrDefault(raw)
            bytes = out; pos = dataSpec.position.toInt().coerceIn(0, out.size)
            return (out.size - pos).toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val b = bytes ?: return inner.read(buffer, offset, length)
            if (pos >= b.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, b.size - pos); System.arraycopy(b, pos, buffer, offset, n); pos += n; return n
        }
        override fun getUri(): Uri? = if (bytes != null) uri else inner.uri
        override fun getResponseHeaders(): Map<String, List<String>> = if (bytes != null) emptyMap() else inner.responseHeaders
        override fun close() { if (bytes != null) { bytes = null; uri = null } else inner.close() }
    }

    private val TIME = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->""")
    private val TIME_SHORT = Regex("""^(\d{2}):(\d{2})[.](\d{3})\s*-->""")

    /** UTF-8 text, hearing-impaired tags out (when on), and the lines' start times noted for auto-sync. */
    internal fun fix(url: String, raw: ByteArray): ByteArray {
        val text = decode(raw)
        val lower = url.substringBefore('?').lowercase()
        if (lower.endsWith(".ass") || lower.endsWith(".ssa") || text.trimStart().startsWith("[Script Info]")) return text.toByteArray()
        val strip = App.instance.prefs.subsStripSdh.value
        val blocks = text.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n\\s*\n"))
        val times = ArrayList<Long>()
        val out = StringBuilder()
        for (b in blocks) {
            val lines = b.split('\n')
            val ti = lines.indexOfFirst { "-->" in it }
            if (ti < 0) { if (b.isNotBlank()) out.append(b.trim('\n')).append("\n\n"); continue }   // WEBVTT header, notes, styles
            val body = lines.drop(ti + 1).let { if (strip) sdh(it) else it }.filter { it.isNotBlank() }
            if (body.isEmpty()) continue
            start(lines[ti])?.let(times::add)
            lines.take(ti + 1).forEach { out.append(it).append('\n') }
            body.forEach { out.append(it).append('\n') }
            out.append('\n')
        }
        if (times.size >= 10) external[url] = times.sorted().toLongArray()
        return out.toString().toByteArray()
    }

    private fun start(line: String): Long? {
        TIME.find(line)?.destructured?.let { (h, m, s, f) -> return ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + f.padEnd(3, '0').toLong() }
        TIME_SHORT.find(line.trim())?.destructured?.let { (m, s, f) -> return (m.toLong() * 60 + s.toLong()) * 1000 + f.toLong() }
        return null
    }

    private val BRACKETS = Regex("""\[[^\]\n]{1,60}]|\([^)\n]{1,60}\)""")
    private val SPEAKER = Regex("""^(\s*(?:<[^>]+>)*\s*-?\s*)[A-Z][A-Z0-9 .'&\-]{1,28}:\s+""")
    private val ONLY_MUSIC = Regex("""^[\s♪♫#*\-]*$""")

    private fun sdh(lines: List<String>): List<String> = lines.map { l ->
        var t = l.replace(BRACKETS) { m -> if (m.value.any { it.isLetter() }) "" else m.value }
        t = SPEAKER.replace(t) { it.groupValues[1] }
        t.replace(Regex("""\s{2,}"""), " ").replace(Regex("""^((?:\s*<[^>]+>)*)\s+"""), "$1").trimEnd()
    }.filterNot { ONLY_MUSIC.matches(it.replace(Regex("<[^>]+>"), "")) }

    private fun decode(raw: ByteArray): String {
        val utf8 = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        runCatching { return utf8.decode(java.nio.ByteBuffer.wrap(raw)).toString().removePrefix("﻿") }
        // Not UTF-8: an old Arabic file (windows-1256: most high bytes are letters 0xC1-0xEA) or Western (windows-1252).
        val high = raw.count { (it.toInt() and 0xFF) >= 0x80 }
        val arabic = raw.count { (it.toInt() and 0xFF) in 0xC1..0xEA }
        val name = if (high > 20 && arabic * 10 > high * 7) "windows-1256" else "windows-1252"
        return String(raw, runCatching { charset(name) }.getOrDefault(Charsets.ISO_8859_1))
    }

    // ---------------------------------------------------------------- the file's own lines, as they download

    /** Wraps every extractor: text tracks' line times are noted on their way to the player (all tracks, shown or not). */
    fun capture(base: ExtractorsFactory): ExtractorsFactory = ExtractorsFactory {
        base.createExtractors().also { list -> list.forEachIndexed { i, e -> list[i] = Watch(e) } }
    }

    private class Watch(private val inner: Extractor) : Extractor by inner {
        override fun init(output: ExtractorOutput) = inner.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                val t = output.track(id, type)
                if (type != C.TRACK_TYPE_TEXT) return t
                return object : TrackOutput by t {
                    @Volatile var isText = true
                    override fun format(format: Format) { isText = format.sampleMimeType != null; t.format(format) }
                    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                        if (isText && timeUs >= 0) embedded.getOrPut(id) { java.util.concurrent.ConcurrentSkipListSet() }.add(timeUs / 1000)
                        t.sampleMetadata(timeUs, flags, size, offset, cryptoData)
                    }
                }
            }
            override fun endTracks() = output.endTracks()
            override fun seekMap(seekMap: SeekMap) = output.seekMap(seekMap)
        })
        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = inner.read(input, seekPosition)
    }

    // ---------------------------------------------------------------- the comparison

    @Volatile private var autoMs = 0L

    /**
     * Every ~15 s while playing (Player): the offset that lines the picked add-on subtitle up with the file's own lines
     * around [posMs]; 0 when an add-on subtitle isn't on, or nothing agrees clearly enough.
     */
    fun tick(player: MediaPlayer, posMs: Long) {
        if (!App.instance.prefs.subsAutoSync.value) { set(0L); return }
        val url = selectedExternal(player) ?: run { set(0L); return }
        val ext = external[url] ?: return
        val best = embedded.values.mapNotNull { offset(it, ext, posMs) }.maxByOrNull { it.second } ?: return
        if (kotlin.math.abs(best.first - autoMs) >= 120) set(best.first)
    }

    private fun set(ms: Long) {
        if (ms == autoMs) return
        autoMs = ms; Sync.autoSubMs = ms
        if (ms != 0L) Log.i(TAG, "auto-sync: subtitle moved ${if (ms > 0) "+" else ""}$ms ms")
    }

    private fun selectedExternal(player: MediaPlayer): String? {
        for (g in player.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_TEXT || !g.isSelected) continue
            // The add-on subtitle's id is "ext:<n>|<url>" (withSubs); the player may put a prefix before it.
            for (i in 0 until g.length) if (g.isTrackSelected(i)) g.getTrackFormat(i).id?.let { id -> id.indexOf("ext:").takeIf { it >= 0 }?.let { return id.substring(it).substringAfter('|') } }
        }
        return null
    }

    /**
     * (delay to apply in ms, how many lines agreed): every pairing of the file's lines (5 minutes before, 3 after [pos])
     * with add-on lines within 20 s, binned by 100 ms; the busiest bin wins when at least 8 lines and 30 % agree.
     */
    private fun offset(own: java.util.concurrent.ConcurrentSkipListSet<Long>, ext: LongArray, pos: Long): Pair<Long, Int>? {
        val win = own.subSet(pos - 300_000, pos + 180_000).toList()
        if (win.size < 8) return null
        val bins = HashMap<Long, MutableList<Long>>()
        for (e in win) {
            var i = java.util.Arrays.binarySearch(ext, e - 20_000).let { if (it < 0) -it - 1 else it }
            while (i < ext.size && ext[i] <= e + 20_000) { val d = ext[i] - e; bins.getOrPut(Math.round(d / 100.0)) { ArrayList() }.add(d); i++ }
        }
        val top = bins.keys.maxByOrNull { k -> (bins[k]?.size ?: 0) + (bins[k - 1]?.size ?: 0) + (bins[k + 1]?.size ?: 0) } ?: return null
        val agree = listOfNotNull(bins[top - 1], bins[top], bins[top + 1]).flatten().sorted()
        if (agree.size < 8 || agree.size < win.size * 0.3) return null
        val d = agree[agree.size / 2]
        // The add-on line comes d ms after the file's own: it has to show d ms sooner.
        return (-d).coerceIn(-20_000, 20_000) to agree.size
    }
}
