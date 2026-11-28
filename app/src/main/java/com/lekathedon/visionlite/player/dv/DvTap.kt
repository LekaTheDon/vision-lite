package com.lekathedon.visionlite.player.dv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.EOFException

/**
 * What the Dolby Vision tap does with a track (built by [DvRoute] from This TV's settings).
 *  - [convert7]: profile 7 goes to 8.1 (enhancement layer dropped, RPU converted).
 *  - [convert5]: profile 5 goes to 8.1 too (only when chosen by hand: converting it in Auto breaks colours).
 *  - [mode]: libdovi's conversion mode (1 = MEL-compatible, 2 = 8.1 with neutral mapping, 0..4 when forced);
 *    [fallback1] retries an RPU that mode 2 can't convert at mode 1.
 */
internal data class DvPlan(val convert7: Boolean, val convert5: Boolean, val mode: Int, val fallback1: Boolean) {
    val active get() = convert7 || convert5
    fun converts(profile: Int?) = (profile == 7 && convert7) || (profile == 5 && convert5)
}

/** What the tap saw this play (Stats). */
internal object DvStats {
    @Volatile var sourceProfile: Int? = null
    @Volatile var mode: Int? = null
    fun reset() { sourceProfile = null; mode = null; Dovi.resetCounts() }
}

/**
 * Wraps the extractors so every HEVC video track of MP4, TS and MKV passes through [Tap]: Dolby Vision converted
 * to 8.1, or stripped to its HDR10 base layer, and HDR10+ removed, per [plan] / [strip] / [stripPlus].
 * Containers that carry the RPU outside the sample (none of these three) and every other track pass untouched.
 */
@UnstableApi
internal class DvExtractors(
    private val base: ExtractorsFactory,
    private val plan: DvPlan,
    private val strip: Boolean,
    private val stripPlus: Boolean,
    private val inject: Boolean,
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = base.createExtractors().map(::wrap).toTypedArray()
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        base.createExtractors(uri, responseHeaders).map(::wrap).toTypedArray()

    private fun wrap(e: Extractor): Extractor {
        if (!plan.active && !strip && !stripPlus) return e
        // By type, not class name: release builds rename media3's classes.
        val ok = when (e.underlyingImplementation) {
            is androidx.media3.extractor.mp4.Mp4Extractor, is androidx.media3.extractor.mp4.FragmentedMp4Extractor,
            is androidx.media3.extractor.ts.TsExtractor, is androidx.media3.extractor.mkv.MatroskaExtractor -> true
            else -> false
        }
        return if (ok) Wrapped(e, plan, strip, stripPlus, inject) else e
    }

    private class Wrapped(private val inner: Extractor, val plan: DvPlan, val strip: Boolean, val stripPlus: Boolean, val inject: Boolean) : Extractor by inner {
        override fun init(output: ExtractorOutput) = inner.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput =
                output.track(id, type).let { if (type == C.TRACK_TYPE_VIDEO) Tap(it, plan, strip, stripPlus, inject) else it }
            override fun endTracks() = output.endTracks()
            override fun seekMap(seekMap: SeekMap) = output.seekMap(seekMap)
        })
        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = inner.read(input, seekPosition)
    }
}

/** One video track: holds each sample until its metadata arrives, then rewrites it in one native call. */
@UnstableApi
internal class Tap(private val out: TrackOutput, private val plan: DvPlan, private val strip: Boolean,
                   private val stripPlus: Boolean, private val inject: Boolean) : TrackOutput by out {
    private var on = false
    private var flags = 0
    private var mode = 1
    private var lenSize = 4
    private var buf = ByteArray(0)
    private var len = 0
    private var res = ByteArray(0)
    private val view = ParsableByteArray()

    override fun format(format: Format) {
        val hevc = format.sampleMimeType == MimeTypes.VIDEO_H265 ||
            (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION && format.codecs.orEmpty().lowercase().let { it.startsWith("dvhe") || it.startsWith("dvh1") })
        if (!hevc || format.drmInitData != null || !Dovi.available()) { on = false; out.format(format); return }
        val profile = profileOf(format.codecs)
        if (profile != null) DvStats.sourceProfile = profile
        val convert = plan.converts(profile)
        // Profile 5 has no HDR10 picture under its RPU (IPT colours): it is never stripped.
        val stripDv = strip && !convert && profile != null && profile != 5
        flags = (if (convert) Dovi.CONVERT else 0) or (if (stripDv) Dovi.STRIP_DV else 0) or (if (stripDv && inject) Dovi.INJECT_HDR10 else 0) or
            (if (stripPlus) Dovi.STRIP_HDR10PLUS else 0) or (if (convert && plan.fallback1) Dovi.FALLBACK_MODE1 else 0)
        mode = if (profile == 5 && plan.mode == 1) 2 else plan.mode
        if (convert) DvStats.mode = mode
        lenSize = format.initializationData.firstOrNull()?.takeIf { it.size > 21 && it[0].toInt() == 1 }?.let { (it[21].toInt() and 3) + 1 } ?: 4
        on = flags != 0
        out.format(when {
            convert -> format.buildUpon().setCodecs(format.codecs?.replace(DV_57, "$1.08.")).build()
            stripDv -> format.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(HEVC_MAIN10)
                .apply { if (format.colorInfo?.colorTransfer.let { it != C.COLOR_TRANSFER_ST2084 && it != C.COLOR_TRANSFER_HLG }) setColorInfo(HDR10) }
                .build()
            else -> format
        })
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (!on || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) return out.sampleData(input, length, allowEndOfInput, sampleDataPart)
        grow(length)
        val n = input.read(buf, len, length)
        if (n == C.RESULT_END_OF_INPUT) { if (allowEndOfInput) return n; throw EOFException() }
        if (n > 0) len += n
        return n
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (!on || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) { out.sampleData(data, length, sampleDataPart); return }
        grow(length); data.readBytes(buf, len, length); len += length
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        if (!on || len == 0) { out.sampleMetadata(timeUs, flags, size, offset, cryptoData); return }
        val carry = offset.coerceIn(0, len)
        val end = len - carry
        // MP4 readers may hand over start codes: the sample itself says which framing it has.
        val annexB = end >= 4 && buf[0].toInt() == 0 && buf[1].toInt() == 0 && (buf[2].toInt() == 1 || buf[2].toInt() == 0 && buf[3].toInt() == 1)
        if (res.size < end + 65536) res = ByteArray(end + 65536)
        val n = Dovi.sample(buf, end, annexB, lenSize, this.flags, mode, res)
        if (n > 0) { view.reset(res, n); out.sampleData(view, n) } else { view.reset(buf, end); out.sampleData(view, end) }
        out.sampleMetadata(timeUs, flags, if (n > 0) n else end, 0, cryptoData)
        if (carry > 0) System.arraycopy(buf, end, buf, 0, carry)
        len = carry
    }

    private fun grow(extra: Int) { if (buf.size < len + extra) buf = buf.copyOf(maxOf(len + extra, buf.size * 2, 64 * 1024)) }

    private companion object {
        val DV_57 = Regex("(?i)(dvhe|dvh1)\\.0[57]\\.")
        const val HEVC_MAIN10 = "hvc1.2.4.L153.B0"
        val HDR10: ColorInfo = ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020)
            .setColorRange(C.COLOR_RANGE_LIMITED).setColorTransfer(C.COLOR_TRANSFER_ST2084).build()
        fun profileOf(codecs: String?): Int? =
            Regex("^(?:dvhe|dvh1)\\.(\\d+)").find(codecs.orEmpty().trim().lowercase())?.groupValues?.get(1)?.toIntOrNull()
    }
}
