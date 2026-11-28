package com.lekathedon.visionlite.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DtsUtil
import androidx.media3.extractor.TrackOutput
import java.io.EOFException

/**
 * MKV writes A_DTS for every DTS kind, so media3 calls DTS-HD MA and DTS:X plain DTS and the soundbar is sent the
 * core only (rule soundbar-decides-audio). This holds a DTS track's format until its first frame is read and names
 * it from the frame itself: a core followed by an extension substream (or a bare substream) is DTS-HD, a UHD sync
 * is DTS:X. Every other track passes straight through. Cost: one frame (a few KB) held once per DTS track.
 */
@UnstableApi
internal class MkvDts(private val out: TrackOutput) : TrackOutput by out {
    private var held: Format? = null
    private var buf = ByteArray(0)
    private var len = 0

    override fun format(format: Format) {
        if (format.sampleMimeType == MimeTypes.AUDIO_DTS && held == null && len == 0) held = format else out.format(format)
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (held == null) return out.sampleData(input, length, allowEndOfInput, sampleDataPart)
        grow(length)
        val n = input.read(buf, len, length)
        if (n == C.RESULT_END_OF_INPUT) { if (allowEndOfInput) return n; throw EOFException() }
        if (n > 0) len += n
        return n
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (held == null) { out.sampleData(data, length, sampleDataPart); return }
        grow(length); data.readBytes(buf, len, length); len += length
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        val f = held
        if (f != null) {
            held = null
            val mime = kind(buf, len)
            out.format(if (mime == f.sampleMimeType) f else f.buildUpon().setSampleMimeType(mime).build())
            android.util.Log.i("MkvDts", "A_DTS track is $mime")
            out.sampleData(ParsableByteArray(buf, len), len)
            buf = ByteArray(0); len = 0
        }
        out.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }

    private fun grow(extra: Int) { if (buf.size < len + extra) buf = buf.copyOf(maxOf(len + extra, buf.size * 2, 8192)) }

    companion object {
        private fun word(b: ByteArray, at: Int) =
            (b[at].toInt() and 0xFF shl 24) or (b[at + 1].toInt() and 0xFF shl 16) or (b[at + 2].toInt() and 0xFF shl 8) or (b[at + 3].toInt() and 0xFF)

        /** The DTS kind of one frame (exposed for tests). */
        fun kind(b: ByteArray, n: Int): String {
            if (n < 4) return MimeTypes.AUDIO_DTS
            return when (DtsUtil.getFrameType(word(b, 0))) {
                DtsUtil.FRAME_TYPE_EXTENSION_SUBSTREAM -> MimeTypes.AUDIO_DTS_HD
                DtsUtil.FRAME_TYPE_UHD_SYNC, DtsUtil.FRAME_TYPE_UHD_NON_SYNC -> MimeTypes.AUDIO_DTS_X
                DtsUtil.FRAME_TYPE_CORE -> {
                    val core = runCatching { DtsUtil.getDtsFrameSize(b.copyOf(n)) }.getOrDefault(C.LENGTH_UNSET)
                    if (core > 0 && n >= core + 4 && DtsUtil.getFrameType(word(b, core)) == DtsUtil.FRAME_TYPE_EXTENSION_SUBSTREAM)
                        MimeTypes.AUDIO_DTS_HD else MimeTypes.AUDIO_DTS
                }
                else -> MimeTypes.AUDIO_DTS
            }
        }
    }
}
