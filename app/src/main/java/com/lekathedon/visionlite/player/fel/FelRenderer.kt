package com.lekathedon.visionlite.player.fel

import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.SampleStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Cause of the error that sends a stream back to the normal decoder. */
class FelFallback(message: String) : Exception(message)

fun Throwable?.isFelFallback(): Boolean {
    var t = this; var n = 0
    while (t != null && n++ < 8) { if (t is FelFallback) return true; t = t.cause }
    return false
}

/** Dolby Vision profile 7 (HEVC) from the codec string. */
fun Format.isDv7(): Boolean {
    val mime = sampleMimeType
    if (mime != MimeTypes.VIDEO_DOLBY_VISION && mime != MimeTypes.VIDEO_H265) return false
    return Regex("""(?:^|,)\s*dv(?:he|h1)\.0?7\.""").containsMatchIn(codecs?.lowercase() ?: return false)
}

/**
 * Video renderer for DV7 FEL/MEL on the AM9: samples go untouched into the dual-layer port, which decodes both layers.
 * The picture is an Amlogic sideband stream on the SurfaceView; the hardware clock follows ExoPlayer's position.
 * Start/seek: open the port, write parameter sets, feed (paced), hold until the first frame plus a few decoded frames
 * are up. Anything unexpected throws [FelFallback] and the player replays the stream on the normal decoder.
 */
@UnstableApi
class FelRenderer : BaseRenderer(C.TRACK_TYPE_VIDEO) {
    private val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)
    private var format: Format? = null
    private var surface: Surface? = null
    private var waitSurfaceSince = 0L
    private var port = 0L
    private var sideband = 0L
    private var pending = false
    private var ended = false
    private var maxQueuedUs = C.TIME_UNSET
    private var positionUs = 0L
    private var playing = false
    private var held = false
    private var firstFrame = false
    private var ready = false
    private var openedAt = 0L
    private var firstFrameAt = 0L
    private var ptsAtOpen = -1L
    private var lastPts = -1L
    private var progressAt = 0L
    private var lastClockAt = 0L
    private var lastPollAt = 0L
    private var refreshAt = 0L
    private var mode: String? = null
    private var samples = 0
    private var sawRpu = false
    private var skipLeading = true
    private var sawIrap = false

    override fun getName() = "VisionLiteFel"

    override fun supportsFormat(f: Format): Int = when {
        !MimeTypes.isVideo(f.sampleMimeType) -> RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        !f.isDv7() -> RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        f.drmInitData != null -> RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM)
        else -> RendererCapabilities.create(C.FORMAT_HANDLED, RendererCapabilities.ADAPTIVE_NOT_SUPPORTED,
            RendererCapabilities.TUNNELING_NOT_SUPPORTED, RendererCapabilities.HARDWARE_ACCELERATION_SUPPORTED,
            RendererCapabilities.DECODER_SUPPORT_PRIMARY)
    }

    override fun handleMessage(messageType: Int, message: Any?) {
        if (messageType != Renderer.MSG_SET_VIDEO_OUTPUT) return super.handleMessage(messageType, message)
        if (message === surface) return
        detachSideband()
        surface = message as? Surface
        if (port != 0L) attachSideband()
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean) {
        // Closing the port blanks the plane; the sideband is re-sent once the new session runs.
        if (port != 0L && sideband != 0L) refreshAt = SystemClock.elapsedRealtime() + 700
        closePort()
        ended = false
        maxQueuedUs = C.TIME_UNSET
        this.positionUs = positionUs
        skipLeading = true; sawIrap = false
    }

    override fun onStarted() {
        playing = true
        progressAt = SystemClock.elapsedRealtime()
        if (port != 0L && held) { check(FelNative.pause(port, false), "resume"); held = false }
        lastClockAt = 0L
    }

    override fun onStopped() {
        playing = false
        if (port != 0L && firstFrame && !held) hold()
    }

    override fun onDisabled() { closePort(); detachSideband(); format = null }
    override fun onReset() { closePort(); detachSideband() }

    override fun isReady(): Boolean {
        if (format == null) return false
        if (ended && maxQueuedUs == C.TIME_UNSET) return true
        if (port == 0L || !ready) return false
        return ended || pending || isSourceReady() || (maxQueuedUs != C.TIME_UNSET && maxQueuedUs - positionUs >= 300_000)
    }

    override fun isEnded(): Boolean = ended && !pending &&
        (maxQueuedUs == C.TIME_UNSET || (firstFrame && positionUs >= maxQueuedUs + frameUs()))

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        this.positionUs = positionUs
        if (format == null) {
            val h = formatHolder; h.clear(); buffer.clear()
            if (readSource(h, buffer, SampleStream.FLAG_REQUIRE_FORMAT) != C.RESULT_FORMAT_READ) return
            setFormat(h.format!!)
        }
        if (port == 0L && surface == null) {
            // HWC only routes the video plane once a sideband is on a Surface: wait for one (at most 3 s).
            val now = SystemClock.elapsedRealtime()
            if (waitSurfaceSince == 0L) waitSurfaceSince = now
            if (now - waitSurfaceSince < 3000) return
        }
        feed()
        if (port == 0L) return
        val now = SystemClock.elapsedRealtime()
        watchFirstFrame(now)
        if (firstFrame && !ready && (now - firstFrameAt >= 600 || framesWaiting() >= 4 || ended)) ready = true
        followClock(now)
        refreshSidebandOnModeChange(now)
        watchStall(now)
    }

    private fun feed() {
        while (true) {
            if (pending) {
                val left = FelNative.drain(port)
                if (left < 0) throw fallback("write failed: errno ${-left}")
                if (left > 0) return
                pending = false
            }
            if (ended) return
            // Pacing: both decoders read one ring buffer; filled to the brim they stop for good.
            if (port != 0L) {
                if (maxQueuedUs != C.TIME_UNSET && maxQueuedUs - positionUs > 2_500_000) return
                val fill = FelNative.fill(port)
                if (fill < 0 || fill > 50) return
            }
            val h = formatHolder; h.clear(); buffer.clear()
            when (readSource(h, buffer, 0)) {
                C.RESULT_NOTHING_READ -> return
                C.RESULT_FORMAT_READ -> setFormat(h.format!!)
                C.RESULT_BUFFER_READ -> {
                    if (buffer.isEndOfStream) { ended = true; continue } // no EOS to the port (wedges the EL decoder)
                    if (buffer.isEncrypted) throw fallback("encrypted")
                    buffer.flip()
                    val data = buffer.data ?: continue
                    if (data.remaining() <= 0) continue
                    inspect(data)
                    if (skipLeading && isLeadingPicture(data)) continue
                    openPort()
                    val r = FelNative.queue(port, data, data.position(), data.remaining(), buffer.timeUs - streamOffsetUs + PTS_BASE_US)
                    if (r < 0) throw fallback("queue failed: errno ${-r}")
                    pending = true
                    maxQueuedUs = if (maxQueuedUs == C.TIME_UNSET) buffer.timeUs else maxOf(maxQueuedUs, buffer.timeUs)
                }
            }
        }
    }

    private fun setFormat(f: Format) {
        val old = format
        format = f
        if (!f.isDv7()) throw fallback("not DV profile 7")
        if (old != null && (old.width != f.width || old.height != f.height || old.codecs != f.codecs)) {
            if (port != 0L) throw fallback("stream changed")
        }
    }

    /** Annex-B only; single-track P7 carries an RPU (NAL 62) in every sample, none in 12 = dual-track, not for the port. */
    private fun inspect(d: ByteBuffer) {
        if (samples++ == 0) {
            val p = d.position()
            val annexB = d.remaining() >= 4 && d.get(p).toInt() == 0 && d.get(p + 1).toInt() == 0 &&
                (d.get(p + 2).toInt() == 1 || (d.get(p + 2).toInt() == 0 && d.get(p + 3).toInt() == 1))
            if (!annexB) throw fallback("not Annex-B")
        }
        if (sawRpu) return
        if (hasNal(d, 62)) sawRpu = true else if (samples >= 12) throw fallback("no RPU in the stream (dual-track P7)")
    }

    private fun hasNal(d: ByteBuffer, type: Int): Boolean {
        val end = d.limit(); var i = maxOf(d.position(), end - 256 * 1024)
        while (i + 3 < end) {
            if (d.get(i).toInt() == 0 && d.get(i + 1).toInt() == 0 && d.get(i + 2).toInt() == 1) {
                if ((d.get(i + 3).toInt() shr 1) and 0x3F == type) return true
                i += 3
            } else i++
        }
        return false
    }

    /** After a seek to a CRA, its RASL pictures reference frames never fed: drop whole samples (keeps BL/EL paired). */
    private fun isLeadingPicture(d: ByteBuffer): Boolean {
        val end = d.limit(); var i = d.position(); var type = -1
        while (i + 3 < end) {
            if (d.get(i).toInt() == 0 && d.get(i + 1).toInt() == 0 && d.get(i + 2).toInt() == 1) {
                val t = (d.get(i + 3).toInt() shr 1) and 0x3F
                if (t < 32) { type = t; break }
                i += 3
            } else i++
        }
        if (type < 0) return false
        if (type in 16..23) { sawIrap = true; return false }
        if (sawIrap && (type == 8 || type == 9)) return true
        skipLeading = false
        return false
    }

    private fun openPort() {
        if (port != 0L) return
        val f = format ?: throw fallback("no format")
        attachSideband()
        val rate = if (f.frameRate in 1f..240f) (96000f / f.frameRate).roundToInt() else 4004
        val h = FelNative.open(f.width, f.height, rate)
        if (FelNative.isError(h)) throw fallback("open failed: errno ${-h}")
        port = h
        val now = SystemClock.elapsedRealtime()
        openedAt = now; progressAt = now; lastClockAt = 0L
        firstFrame = false; ready = false; held = false; clockSet = false; movedAt = 0L
        mode = readMode()
        ptsAtOpen = FelNative.shownPts(port); lastPts = ptsAtOpen
        f.initializationData.firstOrNull()?.takeIf { it.isNotEmpty() }?.let { csd ->
            val b = ByteBuffer.allocateDirect(csd.size).put(csd).also { it.flip() }
            var r = FelNative.queue(port, b, 0, csd.size, -1)
            var tries = 0
            while (r >= 0) { r = FelNative.drain(port); if (r == 0) break; if (++tries > 50) { r = -11; break }; SystemClock.sleep(2) }
            if (r < 0) throw fallback("parameter sets: errno ${-r}")
        }
        Log.i(TAG, "port open ${f.width}x${f.height} rate $rate sideband=${sideband != 0L}")
    }

    private fun closePort() {
        if (port == 0L) return
        FelNative.close(port) // also lifts a pause
        port = 0L; pending = false; held = false; firstFrame = false; ready = false; waitSurfaceSince = 0L
    }

    private fun attachSideband() {
        if (sideband != 0L) return
        val s = surface?.takeIf { it.isValid } ?: return
        val h = FelNative.attach(s)
        if (!FelNative.isError(h)) sideband = h else Log.w(TAG, "sideband attach failed: ${-h}")
    }

    private fun detachSideband() {
        if (sideband == 0L) return
        FelNative.detach(sideband); sideband = 0L
    }

    private var clockSet = false

    private var movedAt = 0L

    /**
     * First frame = the shown PTS moved off the value it had at open (a stale one from the last session) and sits
     * near the start position; one elsewhere (open GOP) is taken after the clock has moved for 750 ms.
     */
    private fun watchFirstFrame(now: Long) {
        if (firstFrame) return
        val pts = FelNative.shownPts(port)
        if (pts <= 0 || pts == ptsAtOpen) {
            if (playing && now - openedAt > 6000) throw fallback("no first frame")
            return
        }
        if (movedAt == 0L) movedAt = now
        if (!clockSet) { FelNative.setClock(port, pts90k(positionUs)); clockSet = true }
        val delta = pts.toInt() - pts90k(positionUs)
        val frame90k = (frameUs() * 9 / 100).toInt()
        if ((delta < -frame90k || delta > 2 * frame90k) && now - movedAt < 750) return
        if (surface?.isValid != true) return
        firstFrame = true; firstFrameAt = now; progressAt = now
        Log.i(TAG, "first frame ${now - openedAt} ms after open, ${delta / 90} ms from target")
        if (!playing) hold()
    }

    private fun hold() { check(FelNative.pause(port, true), "pause"); held = true }

    /** Hardware clock on ExoPlayer's position: set after start, then only on a drift of 25 ms or more. */
    private fun followClock(now: Long) {
        if (!playing || !firstFrame || now - lastClockAt < 500) return
        val forced = lastClockAt == 0L
        lastClockAt = now
        val target = pts90k(positionUs)
        if (!forced) {
            val cur = runCatching { File("/sys/class/tsync/pts_pcrscr").readText().trim().removePrefix("0x").toLong(16) }.getOrDefault(-1L)
            if (cur > 0 && kotlin.math.abs(cur.toInt() - target) < 2250) return
        }
        check(FelNative.setClock(port, target), "clock")
    }

    /** A display mode change (frame-rate switch) blanks every plane and SurfaceFlinger doesn't re-send the sideband. */
    private fun refreshSidebandOnModeChange(now: Long) {
        if (now - lastPollAt >= 100) {
            lastPollAt = now
            val m = readMode()
            if (m != null && m != mode) { mode = m; refreshAt = now + 700 }
        }
        if (refreshAt != 0L && now >= refreshAt) { refreshAt = 0L; detachSideband(); attachSideband() }
    }

    private fun watchStall(now: Long) {
        if (!playing || ended || surface == null) { progressAt = now; return }
        val pts = FelNative.shownPts(port)
        if (pts >= 0 && pts != lastPts) { lastPts = pts; progressAt = now }
        if (now - progressAt > 8000) throw fallback("decoder stalled")
    }

    private fun framesWaiting(): Int = runCatching {
        Regex("buf_avail_num=(\\d+)").find(File("/sys/class/video/vframe_states").readText())!!.groupValues[1].toInt()
    }.getOrDefault(-1)

    private fun readMode() = runCatching { File("/sys/class/display/mode").readText().trim() }.getOrNull()
    private fun frameUs() = (1_000_000f / (format?.frameRate?.takeIf { it > 0 } ?: 24f)).toLong()
    private fun pts90k(posUs: Long) = ((posUs - streamOffsetUs + PTS_BASE_US) * 9 / 100).toInt()

    private fun check(r: Int, what: String) { if (r < 0) throw fallback("$what failed: errno ${-r}") }

    private fun fallback(reason: String): ExoPlaybackException {
        Log.i(TAG, "fallback: $reason")
        closePort(); detachSideband()
        return createRendererException(FelFallback(reason), format, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
    }

    private companion object {
        const val TAG = "VLFel"
        /** Media time 0 = 10 s, so preroll never goes below 0. */
        const val PTS_BASE_US = 10_000_000L
    }
}
