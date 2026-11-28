package com.lekathedon.visionlite.player.dv

import java.util.concurrent.atomic.AtomicLong

/**
 * Vision's Dolby Vision sample rewriter (native/vldovi: own Rust over the MIT dolby_vision crate, JNI in
 * cpp/vl_dovi_jni.cpp). One call per video sample; a damaged RPU only loses itself, never the app.
 */
object Dovi {
    const val CONVERT = 1
    const val STRIP_DV = 2
    const val STRIP_HDR10PLUS = 4
    const val FALLBACK_MODE1 = 8
    const val INJECT_HDR10 = 16

    const val EL_NONE = 0
    const val EL_MEL = 1
    const val EL_FEL = 2

    private val loaded = runCatching { System.loadLibrary("vl_dovi") }
        .onFailure { android.util.Log.w("Dovi", "vl_dovi not loaded: ${it.message}") }.isSuccess

    fun available() = loaded

    /** RPUs offered to the converter / converted, this play (Stats and the 8.1 proof read them). */
    val tried = AtomicLong()
    val converted = AtomicLong()
    fun resetCounts() { tried.set(0); converted.set(0) }

    private val counts = LongArray(2)

    /**
     * Rewrites [len] bytes of [sample] into [out] by [flags]. Returns the new length, 0 when the sample stays as it is,
     * or <0 on error / [out] too small. Callers give [out] room for len + 64 KB (converted RPUs are a little larger).
     */
    fun sample(sample: ByteArray, len: Int, annexB: Boolean, lenSize: Int, flags: Int, mode: Int, out: ByteArray): Int {
        if (!loaded) return -1
        val r = synchronized(counts) {
            val v = nSample(sample, len, if (annexB) 1 else 0, lenSize, flags, mode, out, counts)
            tried.addAndGet(counts[0]); converted.addAndGet(counts[1]); v
        }
        return r
    }

    /** (profile, enhancement layer EL_*) of the RPU NAL at [off]..[off]+[len], null when unreadable. */
    fun info(b: ByteArray, off: Int, len: Int): Pair<Int, Int>? {
        if (!loaded) return null
        val v = IntArray(2)
        return if (runCatching { nInfo(b, off, len, v) }.getOrDefault(-1) == 0) v[0] to v[1] else null
    }

    @JvmStatic private external fun nSample(b: ByteArray, len: Int, annexB: Int, lenSize: Int, flags: Int, mode: Int, out: ByteArray, counts: LongArray): Int
    @JvmStatic private external fun nInfo(b: ByteArray, off: Int, len: Int, info: IntArray): Int
}
