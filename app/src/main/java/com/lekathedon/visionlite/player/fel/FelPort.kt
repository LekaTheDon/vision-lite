package com.lekathedon.visionlite.player.fel

import android.view.Surface
import java.nio.ByteBuffer

/** JNI into cpp/vl_fel.cpp: one syscall per call on fds this side owns. */
internal object FelPort {
    const val CAP_PORT = 1
    const val CAP_AMVIDEO = 2
    const val CAP_DV = 4
    const val SET_PCRSCR = 0x118
    const val GET_VPTS = 0x805

    val loaded = runCatching { System.loadLibrary("vl_fel") }.isSuccess

    fun caps(): Int = if (loaded) runCatching { nCaps() }.getOrDefault(0) else 0

    @JvmStatic external fun nCaps(): Int
    @JvmStatic external fun nOpen(w: Int, h: Int, rate: Int): Int
    @JvmStatic external fun nPts(fd: Int, us: Long): Int
    @JvmStatic external fun nWrite(fd: Int, buf: ByteBuffer, off: Int, len: Int): Int
    @JvmStatic external fun nSet(fd: Int, cmd: Int, v: Long): Int
    @JvmStatic external fun nGet(fd: Int, cmd: Int): Long
    @JvmStatic external fun nFill(fd: Int, out: IntArray): Int
    @JvmStatic external fun nCtl(): Int
    @JvmStatic external fun nHold(ctl: Int, on: Boolean): Int
    @JvmStatic external fun nClose(fd: Int)
    @JvmStatic external fun nBand(surface: Surface, flags: Int, channel: Int): Long
    @JvmStatic external fun nBandOff(h: Long)
}

/**
 * One open port: the decoder pair for one stretch of playback (a seek opens a new one). Video hold is always
 * released before the port closes (a hold left behind would freeze every later player).
 */
internal class FelSession private constructor(private val fd: Int) {
    private var ctl = -1
    var held = false
        private set
    private var pending: ByteBuffer? = null
    private var at = 0
    private var end = 0
    private var ptsUs = -1L
    private val fill = IntArray(3)

    companion object {
        /** Opens the port; null with the reason in [why] on failure. */
        fun open(w: Int, h: Int, rate: Int, why: (String) -> Unit): FelSession? {
            val fd = FelPort.nOpen(w, h, rate)
            if (fd < 0) { why("open errno ${-fd}"); return null }
            return FelSession(fd)
        }
    }

    val busy get() = pending != null

    /** Starts a sample (timestamp [us], or -1 for none: parameter sets). The buffer must stay as it is until [push] says done. */
    fun start(buf: ByteBuffer, off: Int, len: Int, us: Long) { pending = buf; at = off; end = off + len; ptsUs = us }

    /** Writes on; true when the sample is fully in. Throws with errno on a device error. */
    fun push(): Boolean {
        val b = pending ?: return true
        if (ptsUs >= 0) { val r = FelPort.nPts(fd, ptsUs); if (r < 0) throw IllegalStateException("timestamp errno ${-r}"); ptsUs = -1 }
        val n = FelPort.nWrite(fd, b, at, end - at)
        if (n < 0) throw IllegalStateException("write errno ${-n}")
        at += n
        if (at < end) return false
        pending = null
        return true
    }

    /** Percent of the stream buffer in use, -1 when unreadable. */
    fun fillPercent(): Int = if (FelPort.nFill(fd, fill) == 0 && fill[0] > 0) (fill[1].toLong() * 100 / fill[0]).toInt() else -1

    fun clock(pts90k: Int): Boolean = FelPort.nSet(fd, FelPort.SET_PCRSCR, pts90k.toLong() and 0xffffffffL) == 0

    /** Last shown picture's timestamp (90 kHz, u32), -1 when unreadable. */
    fun shown(): Long = FelPort.nGet(fd, FelPort.GET_VPTS).let { if (it < 0) -1 else it }

    fun hold(on: Boolean): Boolean {
        if (ctl < 0) ctl = FelPort.nCtl()
        if (ctl < 0) return false
        val ok = FelPort.nHold(ctl, on) == 0
        if (ok) held = on
        return ok
    }

    fun close() {
        if (held && ctl >= 0) FelPort.nHold(ctl, false)
        if (ctl >= 0) FelPort.nClose(ctl)
        FelPort.nClose(fd)
        ctl = -1; held = false; pending = null
    }
}

/** The SurfaceView's sideband stream: the hardware composer shows the decoder's video plane in its rect. */
internal class FelBand private constructor(private val h: Long) {
    companion object {
        fun on(surface: Surface?): FelBand? {
            if (surface == null || !surface.isValid) return null
            val h = FelPort.nBand(surface, 0x20 /* OMX video */, 1)
            return if (h == 0L || h in -4095L..-1L) null else FelBand(h)   // handles are tagged pointers: only -errno fails
        }
    }
    fun off() = FelPort.nBandOff(h)
}
