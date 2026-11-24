package com.lekathedon.visionlite.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TeeDataSource
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.data.Net
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import kotlin.concurrent.thread

/**
 * Live rewind (TiviMate timeshift, Haven / Zero TV "pause live"). It costs nothing extra on the network: while a
 * channel plays, the bytes the player already downloads are also written into a ring of short MPEG-TS pieces on disk
 * (cut at key frames, PAT/PMT repeated at each start) and listed in a local HLS playlist.
 * Pause or rewind → the player switches to that playlist and the app's own download keeps the ring filling on the
 * same single connection (the player's direct one closes first: one-connection accounts keep working). Forward up to
 * the live point → straight back to the direct stream. A new channel empties the ring; leaving the player deletes it.
 * Only for direct MPEG-TS streams (Xtream .ts); HLS channels rewind inside the provider's own window when it has one.
 * Size: the minutes picked in Settings › Live TV, never more than 30 % of free space (stops below 500 MB free).
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object Timeshift {
    private const val TAG = "VLTimeshift"
    private const val PKT = 188
    private const val SEG_MIN_MS = 4_000L
    private const val SEG_MAX_MS = 8_000L

    /** What the UI shows: seconds kept and whether the ring is what's playing. */
    data class State(val keptMs: Long = 0, val ring: Boolean = false, val on: Boolean = false)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    @Volatile private var url: String? = null
    @Volatile private var headers: Map<String, String> = emptyMap()
    @Volatile private var ring: Ring? = null
    @Volatile private var downloading = false
    @Volatile private var call: Call? = null

    private fun dir() = File(App.instance.cacheDir, "timeshift")
    val playlist: Uri get() = Uri.fromFile(File(dir(), "live.m3u8"))

    /** HLS links aren't taped (the provider's own window is used instead). */
    fun supports(url: String) = !url.substringBefore('?').endsWith(".m3u8", true)

    /** A channel starts: an empty ring for it (or none when live rewind is off / the link is HLS). */
    @Synchronized fun begin(url: String, headers: Map<String, String>) {
        stopDownload()
        ring = null
        dir().deleteRecursively()
        val minutes = App.instance.prefs.liveRewind.value.toIntOrNull() ?: 0
        if (minutes <= 0 || !supports(url)) { this.url = null; _state.value = State(); return }
        val free = dir().apply { mkdirs() }.usableSpace
        val maxBytes = minOf(free * 3 / 10, 8L shl 30)
        if (free < 500L shl 20) { this.url = null; _state.value = State(); Log.i(TAG, "off: ${free shr 20} MB free"); return }
        this.url = url; this.headers = headers
        ring = Ring(dir(), minutes * 60_000L, maxBytes)
        _state.value = State(on = true)
        Log.i(TAG, "ring $minutes min, max ${maxBytes shr 20} MB")
    }

    /** The player left this channel. */
    @Synchronized fun end(url: String) {
        if (this.url != url) return
        stopDownload(); ring?.close(); ring = null; this.url = null
        dir().deleteRecursively()
        _state.value = State()
    }

    /** True when there's something on disk to go back to. */
    fun canRewind(): Boolean = ring?.let { it.keptMs() > 0 || it.partial() } == true

    /**
     * Switch to the ring: the open piece is closed, the app's own download takes over from the player's.
     * Returns the playlist and the position in it [behindMs] back from what was being received.
     */
    @Synchronized fun enter(): Uri? {
        val r = ring ?: return null
        r.gap()
        if (r.keptMs() <= 0) return null
        if (!downloading) startDownload(r)
        _state.value = _state.value.copy(ring = true, keptMs = r.keptMs())
        return playlist
    }

    /** Back to the direct stream (the player's connection tapes the ring again). */
    @Synchronized fun leave() {
        stopDownload()
        ring?.gap()
        _state.value = _state.value.copy(ring = false)
    }

    /** Wraps the player's http source: the live channel's bytes are copied into the ring while it plays direct. */
    fun tee(upstream: DataSource.Factory): DataSource.Factory = DataSource.Factory { TeeDataSource(upstream.createDataSource(), Sink()) }

    private class Sink : DataSink {
        private var r: Ring? = null
        override fun open(dataSpec: DataSpec) {
            r = ring?.takeIf { !downloading && dataSpec.uri.toString() == url }
            r?.gap()
        }
        override fun write(buffer: ByteArray, offset: Int, length: Int) { r?.write(buffer, offset, length) }
        override fun close() { r = null }
    }

    private fun startDownload(r: Ring) {
        downloading = true
        val u = url ?: return
        val h = headers
        thread(name = "VLTimeshift", isDaemon = true) {
            var tries = 0
            while (downloading && ring === r) {
                try {
                    val c = Net.http.newCall(Request.Builder().url(u).apply { h.forEach { (k, v) -> header(k, v) } }.build())
                    call = c
                    c.execute().use { resp ->
                        if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                        r.gap()
                        val ins = resp.body!!.byteStream()
                        val buf = ByteArray(64 * 1024)
                        while (downloading && ring === r) {
                            val n = ins.read(buf); if (n < 0) break
                            r.write(buf, 0, n); tries = 0
                            _state.value = _state.value.copy(keptMs = r.keptMs())
                        }
                    }
                } catch (e: Exception) { if (downloading) Log.w(TAG, "download: ${e.message}") }
                if (!downloading || ring !== r) break
                runCatching { Thread.sleep(minOf(1_000L * ++tries, 5_000L)) }
            }
        }
    }

    private fun stopDownload() { downloading = false; call?.cancel(); call = null }

    /** Pieces on disk + the live.m3u8 that lists the finished ones. */
    private class Ring(val dir: File, val maxMs: Long, val maxBytes: Long) {
        private class Seg(val seq: Long, val file: File, val disc: Boolean) { var ms = 0L; var bytes = 0L }
        private val segs = ArrayDeque<Seg>()
        private var seq = 0L
        private var discSeq = 0L
        private var keptMs = 0L
        private var keptBytes = 0L
        private var cur: Seg? = null
        private var out: FileOutputStream? = null
        private var nextDisc = false
        // Stream facts from PAT / PMT.
        private var pmtPid = -1; private var videoPid = -1; private var pcrPid = -1
        private var pat: ByteArray? = null; private var pmt: ByteArray? = null
        private var pcr0 = -1L; private var pcrNow = -1L; private var wall0 = 0L
        // Bytes between reads that don't make a whole packet.
        private val carry = ByteArray(PKT); private var carryN = 0; private var needSync = true

        @Synchronized fun keptMs() = keptMs
        @Synchronized fun partial() = cur != null

        /** A break in the bytes (new connection): close the open piece, the next one starts a new HLS discontinuity. */
        @Synchronized fun gap() {
            finish()
            carryN = 0; needSync = true; nextDisc = segs.isNotEmpty()
            pcr0 = -1; pcrNow = -1
        }

        @Synchronized fun close() { runCatching { out?.close() }; out = null; cur = null }

        @Synchronized fun write(b: ByteArray, off: Int, len: Int) {
            var i = off; val end = off + len
            if (carryN > 0) {
                val n = minOf(PKT - carryN, end - i)
                System.arraycopy(b, i, carry, carryN, n); carryN += n; i += n
                if (carryN == PKT) { carryN = 0; if (carry[0] == SYNC) packet(carry, 0) else needSync = true }
            }
            while (i < end) {
                if (needSync) {
                    while (i < end && (b[i] != SYNC || (i + PKT < end && b[i + PKT] != SYNC))) i++
                    if (i >= end) break
                    needSync = false
                }
                if (end - i >= PKT) {
                    if (b[i] != SYNC) { needSync = true; continue }
                    packet(b, i); i += PKT
                } else { System.arraycopy(b, i, carry, 0, end - i); carryN = end - i; i = end }
            }
        }

        private fun packet(b: ByteArray, p: Int) {
            val pid = ((b[p + 1].toInt() and 0x1F) shl 8) or (b[p + 2].toInt() and 0xFF)
            val pusi = b[p + 1].toInt() and 0x40 != 0
            val afc = (b[p + 3].toInt() shr 4) and 3
            var payload = p + 4
            var rai = false
            if (afc and 2 != 0) {
                val afLen = b[p + 4].toInt() and 0xFF
                payload = p + 5 + afLen
                if (afLen > 0) {
                    val flags = b[p + 5].toInt() and 0xFF
                    rai = flags and 0x40 != 0
                    if (flags and 0x10 != 0 && afLen >= 7 && pid == pcrPid.let { if (it < 0) pid else it }) {
                        val base = ((b[p + 6].toLong() and 0xFF) shl 25) or ((b[p + 7].toLong() and 0xFF) shl 17) or
                            ((b[p + 8].toLong() and 0xFF) shl 9) or ((b[p + 9].toLong() and 0xFF) shl 1) or ((b[p + 10].toLong() and 0xFF) shr 7)
                        pcrNow = base / 90
                        if (pcr0 < 0) pcr0 = pcrNow
                    }
                }
            }
            if (pusi && afc and 1 != 0 && payload < p + PKT) {
                if (pid == 0) { pat = b.copyOfRange(p, p + PKT); parsePat(b, payload, p + PKT) }
                else if (pid == pmtPid) { pmt = b.copyOfRange(p, p + PKT); parsePmt(b, payload, p + PKT) }
            }
            val dur = segMs()
            val key = rai && (pid == videoPid || videoPid < 0)
            if (cur != null && ((key && dur >= SEG_MIN_MS) || dur >= SEG_MAX_MS)) finish()
            if (cur == null) start(pid)
            runCatching { out?.write(b, p, PKT) }.onFailure { Log.w(TAG, "write: ${it.message}"); close() }
            cur?.let { it.bytes += PKT }
        }

        private fun segMs(): Long {
            val byPcr = if (pcr0 >= 0 && pcrNow >= pcr0) pcrNow - pcr0 else -1
            return if (byPcr in 0..20_000) byPcr else android.os.SystemClock.elapsedRealtime() - wall0
        }

        private fun start(pid: Int) {
            val s = Seg(seq++, File(dir, "s${seq - 1}.ts"), nextDisc)
            nextDisc = false
            out = runCatching { FileOutputStream(s.file) }.getOrNull() ?: return
            cur = s
            wall0 = android.os.SystemClock.elapsedRealtime(); pcr0 = pcrNow
            // A piece played on its own (after a seek) needs the tables before its first picture.
            if (pid != 0) pat?.let { out?.write(it); s.bytes += PKT }
            if (pid != pmtPid) pmt?.let { out?.write(it); s.bytes += PKT }
        }

        private fun finish() {
            val s = cur ?: return
            s.ms = segMs().coerceIn(1, 20_000)
            runCatching { out?.close() }; out = null; cur = null
            if (s.bytes <= PKT * 2) { s.file.delete(); return }
            segs.addLast(s); keptMs += s.ms; keptBytes += s.bytes
            while (segs.size > 2 && (keptMs > maxMs || keptBytes > maxBytes)) {
                val old = segs.removeFirst()
                keptMs -= old.ms; keptBytes -= old.bytes
                if (old.disc) discSeq++
                old.file.delete()
            }
            writePlaylist()
            _state.value = _state.value.copy(keptMs = keptMs)
        }

        private fun writePlaylist() {
            if (segs.isEmpty()) return
            val target = (segs.maxOf { it.ms } + 999) / 1000
            val sb = StringBuilder(64 + segs.size * 32)
            sb.append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:").append(target)
                .append("\n#EXT-X-MEDIA-SEQUENCE:").append(segs.first().seq)
                .append("\n#EXT-X-DISCONTINUITY-SEQUENCE:").append(discSeq).append('\n')
            segs.forEachIndexed { i, s ->
                if (s.disc && i > 0) sb.append("#EXT-X-DISCONTINUITY\n")
                sb.append("#EXTINF:").append(String.format(java.util.Locale.US, "%.3f", s.ms / 1000.0)).append(",\n").append(s.file.name).append('\n')
            }
            val tmp = File(dir, "live.m3u8.tmp")
            runCatching { tmp.writeText(sb.toString()); tmp.renameTo(File(dir, "live.m3u8")) }
        }

        private fun parsePat(b: ByteArray, payload: Int, end: Int) {
            var q = payload + 1 + (b[payload].toInt() and 0xFF)   // pointer field
            if (q + 8 > end || b[q].toInt() != 0) return
            val secLen = ((b[q + 1].toInt() and 0x0F) shl 8) or (b[q + 2].toInt() and 0xFF)
            val stop = minOf(q + 3 + secLen - 4, end)
            q += 8
            while (q + 4 <= stop) {
                val prog = ((b[q].toInt() and 0xFF) shl 8) or (b[q + 1].toInt() and 0xFF)
                val p = ((b[q + 2].toInt() and 0x1F) shl 8) or (b[q + 3].toInt() and 0xFF)
                if (prog != 0) { pmtPid = p; return }
                q += 4
            }
        }

        private fun parsePmt(b: ByteArray, payload: Int, end: Int) {
            var q = payload + 1 + (b[payload].toInt() and 0xFF)
            if (q + 12 > end || b[q].toInt() != 2) return
            val secLen = ((b[q + 1].toInt() and 0x0F) shl 8) or (b[q + 2].toInt() and 0xFF)
            val stop = minOf(q + 3 + secLen - 4, end)
            pcrPid = ((b[q + 8].toInt() and 0x1F) shl 8) or (b[q + 9].toInt() and 0xFF)
            val infoLen = ((b[q + 10].toInt() and 0x0F) shl 8) or (b[q + 11].toInt() and 0xFF)
            q += 12 + infoLen
            while (q + 5 <= stop) {
                val type = b[q].toInt() and 0xFF
                val p = ((b[q + 1].toInt() and 0x1F) shl 8) or (b[q + 2].toInt() and 0xFF)
                val esLen = ((b[q + 3].toInt() and 0x0F) shl 8) or (b[q + 4].toInt() and 0xFF)
                if (videoPid < 0 && type in VIDEO_TYPES) videoPid = p
                q += 5 + esLen
            }
        }
    }

    private const val SYNC: Byte = 0x47
    private val VIDEO_TYPES = setOf(0x01, 0x02, 0x10, 0x1B, 0x24, 0x42, 0xEA)
}
