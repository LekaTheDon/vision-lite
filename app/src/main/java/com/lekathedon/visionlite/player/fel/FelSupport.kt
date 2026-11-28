package com.lekathedon.visionlite.player.fel

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.io.File
import kotlin.math.roundToInt

/**
 * Can this box play Dolby Vision profile 7 with its enhancement layer (FelRenderer)? It needs the dual-layer port,
 * /dev/amvideo and the Dolby Vision driver, plus a kernel that keeps the EL: stock Ugoos kernels decode the EL and
 * throw it away (BL-only picture, nothing to detect), the AM9 FEL kernel announces itself with its own parameters.
 */
internal object FelSupport {
    private val kernelMarks = listOf(
        "/sys/module/aml_media/parameters/am9_dv_dual_layer_hold",
        "/sys/module/aml_media/parameters/am9_dv_fel_keep_el",
    )

    fun portUsable(): Boolean = FelPort.caps().let { it and 7 == 7 }

    fun felKernel(): Boolean = kernelMarks.all { read(it)?.lowercase() in setOf("1", "y", "yes", "true") }

    @Volatile private var cached: Boolean? = null
    /** Asked once per app run (the kernel can't change meanwhile). Test builds also allow stock kernels with enable_fel set. */
    fun usable(): Boolean = cached ?: (portUsable() && (ALLOW_STOCK_KERNEL || felKernel())).also { cached = it }
    private const val ALLOW_STOCK_KERNEL = true

    fun profile(f: Format): Int? =
        Regex("(?:^|,)\\s*(?:dvhe|dvh1|dvav|dva1)\\.(\\d+)\\.").find(f.codecs.orEmpty().trim().lowercase())?.groupValues?.get(1)?.toIntOrNull()

    fun isProfile7(f: Format): Boolean =
        (f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || f.sampleMimeType == MimeTypes.VIDEO_H265) &&
            profile(f) == 7 && f.codecs.orEmpty().lowercase().let { "dvhe" in it || "dvh1" in it }

    /** The port's "rate": 96000 / fps (4004 for 23.976). */
    fun rate(fps: Float): Int = if (fps > 1f && fps < 241f) (96000f / fps).roundToInt() else 4004

    /** Decoded pictures waiting in the display queue, -1 when unreadable. */
    fun queued(): Int = read("/sys/class/video/vframe_states")?.let { Regex("buf_avail_num=(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: -1

    fun read(path: String): String? = runCatching { File(path).readText().trim() }.getOrNull()
}
