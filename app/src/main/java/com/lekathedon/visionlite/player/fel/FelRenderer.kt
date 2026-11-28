package com.lekathedon.visionlite.player.fel

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.lekathedon.visionlite.player.dv.Dovi
import java.nio.ByteBuffer

/** Cause of the playback error that sends a film off the FEL path (the player re-opens it the normal way). */
internal class FelFallback(why: String) : Exception(why)

/**
 * Dolby Vision profile 7 with its enhancement layer, on Amlogic boxes with the FEL kernel. MediaCodec would drop the
 * EL, so the untouched samples (BL + EL in NAL 63 + RPU in NAL 62, Annex-B) go to the dual-layer port, whose two
 * decoders and the Dolby core compose the full picture on the video plane. The SurfaceView carries a sideband stream
 * so the composer shows that plane in its place.
 *
 * Lessons from the box that shape it (AM9, 2026-10):
 *  - the sideband must be on the Surface before the port opens, or the plane stays off;
 *  - feeding to the brim stops both decoders for good: keep at most [AHEAD_US] queued and the buffer half full;
 *  - no end-of-stream to the port (it wedges the EL decoder): playback ends on position;
 *  - after a seek the RASL pictures of the first CRA are dropped whole (BL and EL together), and the clock waits
 *    until [PREROLL_FRAMES] pictures are decoded ahead, else a BL-only frame blinks at the seek;
 *  - the hardware clock (pcrscr) follows the audio clock, corrected only past [DRIFT_90K] (hard-setting it every
 *    time made passthrough audio jitter into repeated / skipped frames);
 *  - every HDMI mode change (and closing the port at a seek) blanks the plane: the sideband is re-sent after it.
 * Anything it can't do (other profiles, MEL off, no in-band RPU, DRM, speed != 1, device errors, a decoder that
 * stops) becomes a [FelFallback].
 */
@UnstableApi
internal class FelRenderer(handler: Handler?, listener: VideoRendererEventListener?, private val speed: () -> Float = { 1f }) :
    BaseRenderer(C.TRACK_TYPE_VIDEO) {

    companion object {
        const val TAG = "Fel"
        const val NAME_HOLE = "amstream_dves_hevc"
        const val NAME_BAND = "amstream_dves_hevc.sideband"
        private const val BASE_US = 10_000_000L          // media 0 -> 10 s, so preroll never goes negative
        private const val AHEAD_US = 2_500_000L
        private const val MAX_FILL = 50
        private const val READY_AHEAD_US = 300_000L
        private const val DRIFT_90K = 2_250               // 25 ms
        private const val CLOCK_EVERY_MS = 500L
        private const val OUTPUT_WAIT_MS = 3_000L
        private const val FIRST_FRAME_MS = 4_000L
        private const val ON_TARGET_GRACE_MS = 750L
        private const val PREROLL_FRAMES = 4
        private const val PREROLL_MS = 600L
        private const val PREROLL_BLIND_MS = 250L
        private const val STALL_MS = 8_000L
        private const val MODE_POLL_MS = 100L
        private const val BAND_AFTER_MS = 700L
        private const val RPU_WITHIN = 12
        private const val MAX_SAMPLE = 32 * 1024 * 1024

        fun isFallback(e: Throwable?): Boolean = generateSequence(e) { it.cause }.take(8).any { it is FelFallback }
    }

    private enum class Phase { IDLE, FIRST_FRAME, PREROLL, PLAYING }

    private val events = VideoRendererEventListener.EventDispatcher(handler, listener)
    private val counters = DecoderCounters()
    private val input = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)

    private var format: Format? = null
    private var output: Any? = null
    private var session: FelSession? = null
    private var band: FelBand? = null
    private var phase = Phase.IDLE
    private var streams = 0

    private var started = false
    private var ended = false            // source gave its last sample
    private var positionUs = 0L
    private var maxQueuedUs = C.TIME_UNSET

    // sample checks (per stream)
    private var checkedFraming = false
    private var elKnown = false
    private var samplesSeen = 0
    private var rpuSeen = false
    private var skipRasl = true
    private var sawIrap = false

    // timing (elapsedRealtime ms)
    private var waitOutputSince = 0L
    private var openedAt = 0L
    private var firstWaitFrom = 0L
    private var movedAt = 0L
    private var firstAt = 0L
    private var vptsOpen = -1L
    private var clockSet = false
    private var lastClock = 0L
    private var lastVpts = -1L
    private var progressAt = 0L
    private var lastPoll = 0L
    private var lastStallPoll = 0L
    private var mode: String? = null
    private var modePolled = 0L
    private var bandAt = 0L
    private var fillFails = 0
    private var reportedFirst = false

    override fun getName() = "FelRenderer"

    override fun supportsFormat(f: Format): Int = when {
        !MimeTypes.isVideo(f.sampleMimeType) -> RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        !FelSupport.isProfile7(f) -> RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        protected(f) -> RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM)
        // Listed before MediaCodec, so with equal support this one takes the track.
        else -> RendererCapabilities.create(C.FORMAT_HANDLED, RendererCapabilities.ADAPTIVE_NOT_SUPPORTED,
            RendererCapabilities.TUNNELING_NOT_SUPPORTED, RendererCapabilities.HARDWARE_ACCELERATION_SUPPORTED,
            RendererCapabilities.DECODER_SUPPORT_PRIMARY)
    }

    override fun handleMessage(messageType: Int, message: Any?) {
        if (messageType != Renderer.MSG_SET_VIDEO_OUTPUT) { super.handleMessage(messageType, message); return }
        if (message === output) return
        bandOff(); bandAt = 0L
        output = message
        reportedFirst = false
        val s = session ?: return
        if (message == null && !s.held) s.hold(true)
        bandOn()
    }

    override fun onEnabled(joining: Boolean, mayRenderStartOfStream: Boolean) {
        events.enabled(counters)
        resetChecks(); skipRasl = true; sawIrap = false; reportedFirst = false
    }

    override fun onStreamChanged(formats: Array<Format>, startPositionUs: Long, offsetUs: Long,
                                 mediaPeriodId: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId) {
        if (++streams > 1) throw fallback("a second stream needs the normal decoder")
        resetChecks()
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean) {
        if (session != null && band != null) bandAt = now() + BAND_AFTER_MS   // closing the port blanks the plane
        closeSession()
        skipRasl = true; sawIrap = false
        ended = false; maxQueuedUs = C.TIME_UNSET
        this.positionUs = positionUs
    }

    override fun onStarted() {
        started = true
        progressAt = now()
        val s = session ?: return
        if (phase == Phase.FIRST_FRAME) firstWaitFrom = progressAt   // time held before playing doesn't count
        if (s.held && !s.hold(false)) throw fallback("resume failed")
        lastClock = 0L
    }

    override fun onStopped() {
        started = false
        val s = session ?: return
        if (phase != Phase.FIRST_FRAME && !s.held && !s.hold(true)) throw fallback("pause failed")
    }

    override fun onDisabled() { teardown(); format = null; streams = 0; events.disabled(counters) }
    override fun onReset() = teardown()
    override fun onRelease() = teardown()

    override fun isReady(): Boolean {
        if (format == null) return false
        if (ended && maxQueuedUs == C.TIME_UNSET) return true
        if (session == null || phase != Phase.PLAYING) return false
        return ended || session?.busy == true || isSourceReady() ||
            (maxQueuedUs != C.TIME_UNSET && maxQueuedUs - positionUs >= READY_AHEAD_US)
    }

    override fun isEnded(): Boolean {
        if (!ended || session?.busy == true) return false
        if (maxQueuedUs == C.TIME_UNSET) return true
        val frameUs = (1_000_000f / (format?.frameRate?.takeIf { it > 0 } ?: 24f)).toLong()
        return phase != Phase.IDLE && phase != Phase.FIRST_FRAME && positionUs >= maxQueuedUs + frameUs
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        this.positionUs = positionUs
        if (kotlin.math.abs(speed() - 1f) > 0.001f) throw fallback("FEL plays at normal speed only")
        if (format == null) {
            formatHolder.clear(); input.clear()
            if (readSource(formatHolder, input, SampleStream.FLAG_REQUIRE_FORMAT) != C.RESULT_FORMAT_READ) return
            newFormat(formatHolder.format!!)
        }
        if (session == null && !outputOrTimeout()) return
        feed()
        val s = session ?: return
        val t = now()
        when (phase) {
            Phase.FIRST_FRAME -> firstFrame(s, t)
            Phase.PREROLL -> preroll(t)
            else -> {}
        }
        if (phase == Phase.PLAYING || phase == Phase.PREROLL) {
            if (!reportedFirst) reportFirst(s)
            keepClock(s, t)
        }
        refreshBand(t)
        stall(s, t)
    }

    // ---------------------------------------------------------------- input

    private fun feed() {
        while (true) {
            session?.let { s -> if (s.busy) { if (!push(s)) return } }
            if (ended) return
            if (session != null && paced(session!!)) return
            formatHolder.clear(); input.clear()
            when (readSource(formatHolder, input, 0)) {
                C.RESULT_NOTHING_READ -> return
                C.RESULT_FORMAT_READ -> newFormat(formatHolder.format!!)
                C.RESULT_BUFFER_READ -> {
                    if (input.isEndOfStream) { ended = true; return }
                    if (input.isEncrypted) throw fallback("encrypted samples")
                    input.flip()
                    val data = input.data ?: continue
                    if (data.remaining() <= 0) continue
                    if (data.remaining() > MAX_SAMPLE) throw fallback("sample too large")
                    if (!admit(data)) { counters.skippedInputBufferCount++; continue }
                    val s = session ?: open()
                    s.start(data, data.position(), data.remaining(), input.timeUs - streamOffsetUs + BASE_US)
                    maxQueuedUs = if (maxQueuedUs == C.TIME_UNSET) input.timeUs else maxOf(maxQueuedUs, input.timeUs) // decode order
                    counters.queuedInputBufferCount++
                    if (!push(s)) return
                }
            }
        }
    }

    private fun push(s: FelSession): Boolean = try {
        s.push().also { if (it && phase == Phase.FIRST_FRAME) progressAt = now() }
    } catch (e: IllegalStateException) { throw fail(e.message ?: "write") }

    /** Enough queued: stop reading. The fill is read before every sample (bursts after a seek fill it fast). */
    private fun paced(s: FelSession): Boolean {
        if (maxQueuedUs != C.TIME_UNSET && maxQueuedUs - positionUs > AHEAD_US) return true
        val p = s.fillPercent()
        if (p < 0) { if (++fillFails >= 8) throw fallback("buffer status unreadable"); return true }
        fillFails = 0
        return p > MAX_FILL
    }

    /** The per-sample checks; false = drop this sample (a RASL picture after the session's first CRA). */
    private fun admit(d: ByteBuffer): Boolean {
        if (!checkedFraming) {
            checkedFraming = true
            val p = d.position()
            val annexB = d.remaining() >= 4 && d.get(p).toInt() == 0 && d.get(p + 1).toInt() == 0 &&
                (d.get(p + 2).toInt() == 1 || d.get(p + 2).toInt() == 0 && d.get(p + 3).toInt() == 1)
            if (!annexB) throw fallback("samples are not Annex-B")
        }
        if (!elKnown) classify(d)
        if (skipRasl) {
            val t = firstPictureType(d)
            if (t in 16..23) sawIrap = true
            else if (sawIrap && (t == 8 || t == 9)) return false
            else if (t >= 0) skipRasl = false
        }
        return true
    }

    /** FEL or MEL from the RPU; none within [RPU_WITHIN] samples = dual-track P7 or out-of-band RPU (BL only here). */
    private fun classify(d: ByteBuffer) {
        samplesSeen++
        val (at, len) = nal(d, 62) ?: run {
            if (!rpuSeen && samplesSeen >= RPU_WITHIN) throw fallback("no RPU in the first $samplesSeen samples")
            return
        }
        rpuSeen = true
        val bytes = ByteArray(len).also { d.duplicate().apply { position(at) }.get(it) }
        val info = Dovi.info(bytes, 0, len)
        if (info != null && info.first != 7) throw fallback("RPU says profile ${info.first}")
        elKnown = true
        Log.i(TAG, "profile 7 " + when (info?.second) { Dovi.EL_FEL -> "FEL"; Dovi.EL_MEL -> "MEL"; Dovi.EL_NONE -> "(no EL)"; else -> "(RPU unread)" } + " on the FEL path")
    }

    /** (start, length) of the first NAL of [type] in an Annex-B sample. */
    private fun nal(d: ByteBuffer, type: Int): Pair<Int, Int>? {
        val end = d.limit()
        var i = d.position()
        var begin = -1
        while (i + 3 <= end) {
            if (d.get(i).toInt() == 0 && d.get(i + 1).toInt() == 0 && d.get(i + 2).toInt() == 1) {
                if (begin >= 0) {
                    var e = i
                    while (e > begin && d.get(e - 1).toInt() == 0) e--
                    return begin to (e - begin)
                }
                if (i + 3 < end && (d.get(i + 3).toInt() shr 1) and 0x3F == type) begin = i + 3
                i += 3
            } else i++
        }
        return if (begin >= 0) begin to (end - begin) else null
    }

    /** NAL type of the first picture NAL (< 32) = the base layer picture (the EL rides in NAL 63). */
    private fun firstPictureType(d: ByteBuffer): Int {
        var i = d.position()
        while (i + 3 < d.limit()) {
            if (d.get(i).toInt() == 0 && d.get(i + 1).toInt() == 0 && d.get(i + 2).toInt() == 1) {
                val t = (d.get(i + 3).toInt() shr 1) and 0x3F
                if (t < 32) return t
                i += 3
            } else i++
        }
        return -1
    }

    private fun newFormat(f: Format) {
        val old = format
        format = f
        events.inputFormatChanged(f, null)
        if (f.width > 0 && f.height > 0) events.videoSizeChanged(VideoSize(f.width, f.height, f.pixelWidthHeightRatio))
        if (!FelSupport.isProfile7(f)) throw fallback("not profile 7 (${f.sampleMimeType} ${f.codecs})")
        if (protected(f)) throw fallback("DRM")
        if (old == null) return
        if (old.width != f.width || old.height != f.height) closeSession()   // new decoders: the next sample reopens
        if (differs(old, f)) {
            if (session != null) throw fallback("stream configuration changed")
            resetChecks()
        }
    }

    // ---------------------------------------------------------------- session

    private fun outputOrTimeout(): Boolean {
        if (output != null) return true
        val t = now()
        if (waitOutputSince == 0L) { waitOutputSince = t; return false }
        if (t - waitOutputSince < OUTPUT_WAIT_MS) return false
        Log.w(TAG, "no video output after ${t - waitOutputSince} ms: opening without sideband")
        return true
    }

    private fun open(): FelSession {
        val f = format ?: throw fallback("no format")
        if (band == null) bandOn()                      // sideband before the port
        val s = FelSession.open(f.width, f.height, FelSupport.rate(f.frameRate)) { throw fail(it) }!!
        session = s
        val t = now()
        openedAt = t; firstWaitFrom = t; progressAt = t; movedAt = 0L; lastClock = 0L; lastPoll = t
        clockSet = false; fillFails = 0; reportedFirst = false
        phase = Phase.FIRST_FRAME
        mode = FelSupport.read("/sys/class/display/mode"); modePolled = t
        vptsOpen = s.shown(); lastVpts = vptsOpen
        f.initializationData.firstOrNull()?.takeIf { it.isNotEmpty() }?.let { csd ->   // parameter sets, no timestamp
            val b = ByteBuffer.allocateDirect(csd.size).put(csd).also { it.flip() }
            s.start(b, 0, csd.size, -1)
            var tries = 0
            while (!push(s)) { if (++tries > 50) throw fail("parameter sets did not go in"); SystemClock.sleep(2) }
        }
        events.decoderInitialized(if (band != null) NAME_BAND else NAME_HOLE, t, now() - t)
        return s
    }

    private fun closeSession() {
        session?.close()
        session = null
        phase = Phase.IDLE
        waitOutputSince = 0L
    }

    private fun teardown() { closeSession(); bandOff(); bandAt = 0L }

    private fun bandOn() {
        if (band != null) return
        band = FelBand.on(output as? Surface)
        Log.i(TAG, if (band != null) "sideband on" else "sideband unavailable: see-through mode")
    }

    private fun bandOff() { band?.off(); band = null }

    /** Re-sends the sideband [BAND_AFTER_MS] after an HDMI mode change or a seek (both blank the plane). */
    private fun refreshBand(t: Long) {
        if (output == null) return
        if (t - modePolled >= MODE_POLL_MS) {
            modePolled = t
            val m = FelSupport.read("/sys/class/display/mode")
            if (m != null && m != mode) { mode = m; bandAt = t + BAND_AFTER_MS }
        }
        if (bandAt == 0L || t < bandAt) return
        bandAt = 0L
        bandOff(); bandOn()
    }

    // ---------------------------------------------------------------- presentation

    private fun pts90k(us: Long): Int = ((us - streamOffsetUs + BASE_US) * 9 / 100).toInt()

    private fun firstFrame(s: FelSession, t: Long) {
        val v = s.shown()
        if (v <= 0 || v == vptsOpen) {
            if (started && t - firstWaitFrom >= FIRST_FRAME_MS) throw fallback("no first frame in ${FIRST_FRAME_MS} ms")
            return
        }
        if (movedAt == 0L) movedAt = t
        if (!clockSet) {                                    // the decoder's start may seed the clock: ours goes after it
            if (!s.clock(pts90k(positionUs))) throw fallback("clock unavailable")
            clockSet = true
        }
        val frameUs = (1_000_000.0 / (format?.frameRate?.takeIf { it > 0 } ?: 24f)).toLong()
        val delta = v.toInt() - pts90k(positionUs)          // signed: wrap-safe
        val near = delta >= -(frameUs * 9 / 100).toInt() && delta <= (frameUs * 18 / 100).toInt()
        if (!near && t - movedAt < ON_TARGET_GRACE_MS) return
        val surface = output as? Surface
        if (surface?.isValid != true) return
        firstAt = t
        phase = Phase.PREROLL
        Log.i(TAG, "first frame at ${positionUs / 1000} ms, ${t - openedAt} ms after open" + if (near) "" else " (off target ${delta / 90} ms)")
        reportFirst(s)
        if (!started && !s.held) s.hold(true)
    }

    private fun reportFirst(s: FelSession) {
        val surface = output as? Surface ?: return
        if (!surface.isValid) return
        reportedFirst = true
        events.renderedFirstFrame(surface)
        if (started && s.held && !s.hold(false)) throw fallback("resume failed")
    }

    private fun preroll(t: Long) {
        if (t - lastPoll < 20) return
        lastPoll = t
        val q = FelSupport.queued()
        if (q < PREROLL_FRAMES && t - firstAt < (if (q < 0) PREROLL_BLIND_MS else PREROLL_MS) && !ended) return
        phase = Phase.PLAYING
    }

    /** The hardware clock follows the player's (audio) clock: set after a start, then only on a drift past [DRIFT_90K]. */
    private fun keepClock(s: FelSession, t: Long) {
        if (!started || t - lastClock < CLOCK_EVERY_MS) return
        val forced = lastClock == 0L
        lastClock = t
        val target = pts90k(positionUs)
        if (!forced) {
            val cur = FelSupport.read("/sys/class/tsync/pts_pcrscr")?.removePrefix("0x")?.toLongOrNull(16)?.and(0xffffffffL) ?: -1L
            if (cur > 0 && kotlin.math.abs(cur.toInt() - target) < DRIFT_90K) return
        }
        if (!s.clock(target)) throw fallback("clock correction failed")
    }

    /** A stopped decoder never recovers: no new picture for [STALL_MS] while playing = give up. */
    private fun stall(s: FelSession, t: Long) {
        if (!started || ended) return
        if (output == null) { progressAt = t; return }
        if (t - lastStallPoll >= 250) {
            lastStallPoll = t
            val v = s.shown()
            if (v >= 0 && v != lastVpts) { lastVpts = v; progressAt = t }   // a failed read is not progress
        }
        if (t - progressAt >= STALL_MS) throw fallback("no picture for ${t - progressAt} ms")
    }

    // ---------------------------------------------------------------- helpers

    private fun resetChecks() { checkedFraming = false; elKnown = false; samplesSeen = 0; rpuSeen = false }

    private fun protected(f: Format) = f.drmInitData != null || f.cryptoType != C.CRYPTO_TYPE_NONE

    private fun differs(a: Format, b: Format) =
        a.frameRate != b.frameRate || a.sampleMimeType != b.sampleMimeType || a.codecs != b.codecs || a.width != b.width ||
            a.height != b.height || a.initializationData.size != b.initializationData.size ||
            a.initializationData.indices.any { !a.initializationData[it].contentEquals(b.initializationData[it]) }

    private fun now() = SystemClock.elapsedRealtime()

    private fun fail(why: String): ExoPlaybackException {
        teardown()
        return createRendererException(FelFallback("FEL $why"), format, PlaybackException.ERROR_CODE_DECODING_FAILED)
    }

    private fun fallback(why: String): ExoPlaybackException {
        Log.i(TAG, "fallback: $why")
        teardown()                                          // the normal path draws into this Surface again
        return createRendererException(FelFallback(why), format, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
    }
}
