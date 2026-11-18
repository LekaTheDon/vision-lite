package com.lekathedon.visionlite.player.fel

import java.io.File
import java.nio.ByteBuffer

/** JNI to libvlfel (fel.cpp). Handles are native pointers (may be negative as Long); failures are -errno. */
internal object FelNative {
    private val loaded = runCatching { System.loadLibrary("vlfel") }.isSuccess

    @JvmStatic external fun probe(): Int
    @JvmStatic external fun open(width: Int, height: Int, rate: Int): Long
    @JvmStatic external fun queue(handle: Long, data: ByteBuffer, offset: Int, length: Int, ptsUs: Long): Int
    @JvmStatic external fun drain(handle: Long): Int
    @JvmStatic external fun setClock(handle: Long, pts90k: Int): Int
    @JvmStatic external fun shownPts(handle: Long): Long
    @JvmStatic external fun fill(handle: Long): Int
    @JvmStatic external fun pause(handle: Long, on: Boolean): Int
    @JvmStatic external fun close(handle: Long)
    @JvmStatic external fun attach(surface: Any): Long
    @JvmStatic external fun detach(handle: Long)

    fun isError(v: Long) = v == 0L || v in -4095L..-1L

    /** Only the AM9 FEL kernel keeps the enhancement layer; stock firmware decodes it and drops it. */
    private val felKernel get() = listOf("am9_dv_dual_layer_hold", "am9_dv_fel_keep_el").all {
        runCatching { File("/sys/module/aml_media/parameters/$it").readText().trim().lowercase() in setOf("1", "y") }.getOrDefault(false)
    }

    /** Port writable + amvideo + Dolby Vision driver + FEL kernel. Asked once (the kernel can't change while running). */
    val usable: Boolean by lazy { loaded && runCatching { probe() and 7 == 7 }.getOrDefault(false) && felKernel }
}
