package com.lekathedon.visionlite.player.pics

/** A keyframe the file's own index points at: when it shows and where its bytes are. */
internal class Key(val ms: Long, val pos: Long, val size: Int = -1, val rel: Long = -1)

/**
 * The video track as a decoder needs it. [nalLen] > 0: AVC/HEVC samples carry length-prefixed NAL units that are
 * rewritten to start codes; 0: the bytes go to the decoder as they are.
 */
internal class Track(
    val mime: String, val csd: List<ByteArray>, val nalLen: Int, val width: Int, val height: Int,
    val dvProfile: Int = -1, val prefix: ByteArray? = null, val zlib: Boolean = false,
)

/** A file's keyframes plus a way to fetch one keyframe's bytes (as the decoder takes them). */
internal abstract class Index(val track: Track, val keys: List<Key>) {
    abstract fun frame(k: Key): ByteArray?
}

/** Codec ids / FourCCs to Android MIME types and decoder setup, shared by the container readers. */
internal object Codecs {
    private fun be16(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) shl 8 or (b[i + 1].toInt() and 0xff)
    private fun startCode(out: java.io.ByteArrayOutputStream, b: ByteArray, i: Int, n: Int) {
        out.write(0); out.write(0); out.write(0); out.write(1); out.write(b, i, n)
    }

    /** hvcC record → (parameter sets as start-code NALs, NAL length size). */
    fun hvcc(p: ByteArray): Pair<ByteArray, Int>? = runCatching {
        val out = java.io.ByteArrayOutputStream()
        var i = 23
        repeat(p[22].toInt() and 0xff) {
            val count = be16(p, i + 1); i += 3
            repeat(count) { val n = be16(p, i); i += 2; startCode(out, p, i, n); i += n }
        }
        out.toByteArray() to (p[21].toInt() and 3) + 1
    }.getOrNull()

    /** avcC record → (SPS, PPS as start-code NALs, NAL length size). */
    fun avcc(p: ByteArray): Triple<ByteArray, ByteArray, Int>? = runCatching {
        val sps = java.io.ByteArrayOutputStream(); val pps = java.io.ByteArrayOutputStream()
        var i = 6
        repeat(p[5].toInt() and 0x1f) { val n = be16(p, i); i += 2; startCode(sps, p, i, n); i += n }
        repeat(p[i++].toInt() and 0xff) { val n = be16(p, i); i += 2; startCode(pps, p, i, n); i += n }
        Triple(sps.toByteArray(), pps.toByteArray(), (p[4].toInt() and 3) + 1)
    }.getOrNull()

    /** Dolby Vision profile from a dvcC / dvvC / dvwC payload. */
    fun dvProfile(p: ByteArray?): Int = if (p != null && p.size >= 3) (p[2].toInt() and 0xff) shr 1 else -1

    fun hevc(priv: ByteArray?, w: Int, h: Int, dv: Int, prefix: ByteArray? = null, zlib: Boolean = false): Track? {
        val (ps, n) = priv?.let { hvcc(it) } ?: return null
        return Track("video/hevc", listOf(ps), n, w, h, dv, prefix, zlib)
    }

    fun avc(priv: ByteArray?, w: Int, h: Int, prefix: ByteArray? = null, zlib: Boolean = false): Track? {
        val (sps, pps, n) = priv?.let { avcc(it) } ?: return null
        return Track("video/avc", listOf(sps, pps), n, w, h, -1, prefix, zlib)
    }

    /** Video for Windows FourCC → MIME (XviD/DivX, H.264 in AVI, VC-1, MPEG-2, MJPEG). */
    fun fourcc(cc: String): String? = when (cc.uppercase()) {
        "XVID", "DIVX", "DX50", "DIV3", "DIV4", "FMP4", "MP4V", "M4S2", "3IV2", "DIV5", "DIV6", "MP4S" -> "video/mp4v-es"
        "H264", "X264", "AVC1", "DAVC", "VSSH" -> "video/avc"
        "HEVC", "H265", "HVC1", "X265" -> "video/hevc"
        "WVC1", "WMVA" -> "video/wvc1"
        "WMV3" -> "video/x-ms-wmv"
        "MPG2", "MPEG", "MPG1" -> "video/mpeg2"
        "MJPG", "AVRN", "JPEG" -> "video/mjpeg"
        "VP80" -> "video/x-vnd.on2.vp8"
        "VP90" -> "video/x-vnd.on2.vp9"
        "AV01" -> "video/av01"
        else -> null
    }

    /** Start-code stream (no csd given): its own parameter sets become the decoder's csd. */
    fun inBandCsd(mime: String, frame: ByteArray): List<ByteArray> {
        if (mime == "video/mp4v-es") {   // everything before the first picture (VOP start 00 00 01 B6)
            for (i in 0 until frame.size - 3)
                if (frame[i].toInt() == 0 && frame[i + 1].toInt() == 0 && frame[i + 2].toInt() == 1 && (frame[i + 3].toInt() and 0xff) == 0xB6)
                    return if (i > 0) listOf(frame.copyOf(i)) else emptyList()
            return emptyList()
        }
        return emptyList()
    }
}
