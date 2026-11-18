package com.lekathedon.visionlite.player.fel

/**
 * The few Vision helpers the copied FEL renderer calls. Lite has no libdovi and no stats HUD: every in-band RPU is
 * taken as profile 7 (the port decodes FEL and MEL alike) and the HUD calls do nothing.
 */
internal object DoviBridge {
    const val EL_TYPE_FEL = 2
    const val EL_TYPE_MEL = 1
    const val EL_TYPE_NONE = 0
    fun isAvailable() = false
    fun detectRpuElType(sample: ByteArray, offset: Int, len: Int): Int = EL_TYPE_FEL
    fun getRpuStaticMetadata(sample: ByteArray, offset: Int, len: Int): Any? = null
    fun getRpuDmInfo(sample: ByteArray, offset: Int, len: Int): Any? = null
}

internal object DolbyVisionConversionStats {
    const val LIVE_DM_WRITER_RENDERER = 1
    val session = Session()
    class Session {
        fun recordElType(code: Int) {}
        fun recordRpuMetadata(meta: Any?) {}
        fun shouldSampleLiveDm(writer: Int, timeUs: Long) = false
        fun recordLiveDm(writer: Int, timeUs: Long, info: Any?) {}
    }
}

/** Vision's HevcDvRpuStripper.findRpuNalAnnexB: (offset, length) of the first type-62 NAL in an Annex-B sample. */
internal object HevcDvRpuStripper {
    fun findRpuNalAnnexB(sample: ByteArray, sampleLen: Int): Pair<Int, Int>? {
        var scan = 0
        while (scan < sampleLen) {
            val sc = findStartCode(sample, scan, sampleLen)
            if (sc < 0) break
            val begin = sc + if (sc + 3 < sampleLen && sample[sc + 2].toInt() == 0) 4 else 3
            val next = findStartCode(sample, begin + 2, sampleLen)
            val end = if (next < 0) sampleLen else next
            if (begin < end && (sample[begin].toInt() ushr 1) and 0x3F == 62) return begin to (end - begin)
            scan = end
        }
        return null
    }

    private fun findStartCode(b: ByteArray, from: Int, len: Int): Int {
        var i = from
        while (i + 2 < len) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) {
                if (b[i + 2].toInt() == 1) return i
                if (b[i + 2].toInt() == 0 && i + 3 < len && b[i + 3].toInt() == 1) return i
            }
            i++
        }
        return -1
    }
}
