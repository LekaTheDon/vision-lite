package com.lekathedon.visionlite.probe

import android.util.Log
import com.lekathedon.visionlite.player.dv.Dovi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a link's Dolby Vision profile from the file itself, the way the player learns it: the container's DV
 * configuration (Matroska BlockAdditionMapping / MP4 dvcC) gives profile and base-layer compatibility, and for
 * profile 7 the first keyframe's RPU tells FEL from MEL (libdovi, as [Dovi.info] at playback).
 * Files without a DV configuration are checked for an in-band RPU, then for HDR10+ / HDR10 SEI.
 *
 * Bounded Range reads only (head 256 KB, the Cues, one keyframe): never the whole file. Never logs URLs.
 */
object DvProfileProbe {
    private const val TAG = "DvProfileProbe"
    private const val NAL_SEI_PREFIX = 39
    private const val NAL_DV_RPU = 62
    private const val SEI_T35 = 4
    private const val SEI_MASTERING = 137
    private const val TRC_PQ = 16
    private const val TRC_HLG = 18

    /** The label, or throws with a short reason ([UnsupportedOperationException] / IO errors). */
    suspend fun probe(url: String, headers: Map<String, String>): DvProfileLabel = probeWithSize(url, headers).first

    /** As [probe], with the file's real size from the server's Range answer (null when it gave none). */
    suspend fun probeWithSize(url: String, headers: Map<String, String>): Pair<DvProfileLabel, Long?> =
        withContext(Dispatchers.IO) {
            val reader = HttpRangeReader(url, headers)
            try {
                inspect(reader).first to reader.totalLength.takeIf { it > 0 }
            } finally {
                reader.close()
            }
        }

    /** Only the file's real size: one 1-byte Range read. Throws as [probe] does. */
    suspend fun probeSize(url: String, headers: Map<String, String>): Long? = withContext(Dispatchers.IO) {
        val reader = HttpRangeReader(url, headers)
        try {
            reader.read(0, 1)
            reader.totalLength.takeIf { it > 0 }
        } finally {
            reader.close()
        }
    }

    /** Lite's VOD probe: picture size, the HDR / DV text and the sound + subtitle tracks, from one pass. Blocking. */
    internal fun full(url: String, headers: Map<String, String>): VodProbe.Result {
        val reader = HttpRangeReader(url, headers)
        try {
            val (label, video, tracks) = inspect(reader)
            return VodProbe.Result(video.width, video.height, label.text, tracks)
        } finally {
            reader.close()
        }
    }

    private fun inspect(r: HttpRangeReader): Triple<DvProfileLabel, VideoTrack, List<ProbeTrack>> {
        val head = r.read(0, MkvIndexReader.HEAD_BYTES)
        val mkv = MkvIndexReader.looksLikeMatroska(head)
        if (!mkv && !Mp4IndexReader.looksLikeMp4(head)) throw UnsupportedOperationException("not MKV or MP4")
        var index: MediaIndex? = null
        val video: VideoTrack = if (mkv) MkvIndexReader.peek(r, head).video
            else Mp4IndexReader.read(r, head).also { index = it }.video
        val tracks = runCatching {
            if (mkv) MkvIndexReader.sideTracks(r, head) else Mp4IndexReader.lastMoov.get()?.let(Mp4IndexReader::sideTracks).orEmpty()
        }.getOrDefault(emptyList())
        Mp4IndexReader.lastMoov.remove()
        return Triple(label(r, head, mkv, video, index), video, tracks)
    }

    private fun label(r: HttpRangeReader, head: ByteArray, mkv: Boolean, video: VideoTrack, index: MediaIndex?): DvProfileLabel {
        val dv = video.dolbyVision
        // Profiles 5, 8 and 10 are fully described by the configuration record.
        if (dv != null && dv.profile != 7) return checked(dvText(dv.profile, dv.blCompatId, null))

        val frame = if (video.codec == VideoCodec.HEVC) firstKeyframe(r, head, mkv, index) else null
        val rpu = frame?.let { f -> findNal(f, NAL_DV_RPU)?.let { f to it } }
        if (dv != null) {
            // Profile 7: FEL or MEL from the first RPU. Dual-track MP4 keeps the RPU with the EL track.
            val el = rpu?.let { (f, at) -> elName(Dovi.info(f, at.first, at.second)?.second ?: -1) }
            return checked(dvText(7, dv.blCompatId, el) + if (el == null) " · FEL or MEL unknown" else "")
        }
        if (rpu != null) {
            // No DV configuration in the container, but the stream carries RPUs: the player still sees Dolby Vision.
            val (f, at) = rpu
            val profile = Dovi.info(f, at.first, at.second)?.first
                ?: return checked("Dolby Vision (profile unreadable)")
            val el = if (profile == 7) elName(Dovi.info(f, at.first, at.second)?.second ?: -1) else null
            return checked(dvText(profile, if (profile == 8) 1 else null, el))
        }
        val sei = frame?.let(::hdrSei)
        val colour = video.colour.transfer
        val kind = when {
            sei == SEI_T35 -> "HDR10+"
            colour == TRC_HLG -> "HLG"
            colour == TRC_PQ || sei == SEI_MASTERING -> "HDR10"
            frame == null && video.bitDepth >= 10 -> "HDR (type unknown)"
            else -> "SDR"
        }
        return checked("No Dolby Vision · $kind")
    }

    private fun checked(text: String) = DvProfileLabel(text, DvProfileLabel.Certainty.CHECKED)

    private fun dvText(profile: Int, compat: Int?, el: String?): String {
        val base = DvProfileNames.baseLayer(profile, compat)
        return "Dolby Vision ${DvProfileNames.profileName(profile, compat, el)}" + (base?.let { " · $it" } ?: "")
    }

    private fun elName(code: Int): String? = when (code) {
        Dovi.EL_FEL -> "FEL"
        Dovi.EL_MEL -> "MEL"
        else -> null
    }

    /** The first keyframe as Annex-B (parameter sets first), or null when the file has no usable index. */
    private fun firstKeyframe(r: HttpRangeReader, head: ByteArray, mkv: Boolean, known: MediaIndex?): ByteArray? {
        val index = known ?: runCatching { if (mkv) MkvIndexReader.read(r, head) else null }
            .onFailure { Log.i(TAG, "no keyframe index: ${it.message}") }
            .getOrNull() ?: return null
        if (index.keyframes.count == 0 || index.kind == ContainerKind.AVI) return null
        return runCatching { KeyframeExtractor(index, r).extract(0) }
            .onFailure { Log.i(TAG, "keyframe read failed: ${it.message}") }
            .getOrNull()
    }

    /** (offset, length) of the first NAL of [type] in Annex-B [data], counting its 2-byte header. */
    private fun findNal(data: ByteArray, type: Int): Pair<Int, Int>? {
        forEachNal(data) { start, end -> if (nalType(data, start) == type) return start to (end - start) }
        return null
    }

    private fun nalType(data: ByteArray, start: Int) = (data[start].toInt() ushr 1) and 0x3F

    private inline fun forEachNal(data: ByteArray, block: (start: Int, end: Int) -> Unit) {
        var p = nextStart(data, 0)
        while (p >= 0) {
            val begin = p
            val next = nextStart(data, begin)
            var end = if (next < 0) data.size else next - 3
            if (next >= 0 && end > begin && data[end - 1].toInt() == 0) end-- // 4-byte start code
            if (end > begin + 1) block(begin, end)
            p = next
        }
    }

    /** Index just after the next 00 00 01 at or after [from], or -1. */
    private fun nextStart(data: ByteArray, from: Int): Int {
        var i = from
        while (i + 2 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) return i + 3
            i++
        }
        return -1
    }

    /** [SEI_T35] for an HDR10+ (ST 2094-40) message, [SEI_MASTERING] for HDR10 mastering metadata, else null. */
    private fun hdrSei(data: ByteArray): Int? {
        var mastering = false
        forEachNal(data) { start, end ->
            if (nalType(data, start) != NAL_SEI_PREFIX) return@forEachNal
            val rbsp = unescape(data, start + 2, end)
            var p = 0
            while (p < rbsp.size && (rbsp[p].toInt() and 0xFF) != 0x80) {
                var payloadType = 0
                while (p < rbsp.size && (rbsp[p].toInt() and 0xFF) == 0xFF) { payloadType += 255; p++ }
                if (p >= rbsp.size) break
                payloadType += rbsp[p++].toInt() and 0xFF
                var size = 0
                while (p < rbsp.size && (rbsp[p].toInt() and 0xFF) == 0xFF) { size += 255; p++ }
                if (p >= rbsp.size) break
                size += rbsp[p++].toInt() and 0xFF
                if (p + size > rbsp.size) break
                // ITU-T T.35, USA (0xB5), Samsung (0x003C), oriented code 0x0001, application 4 = HDR10+.
                if (payloadType == SEI_T35 && size >= 6 && (rbsp[p].toInt() and 0xFF) == 0xB5 &&
                    rbsp[p + 1].toInt() == 0x00 && rbsp[p + 2].toInt() == 0x3C &&
                    rbsp[p + 3].toInt() == 0x00 && rbsp[p + 4].toInt() == 0x01 && rbsp[p + 5].toInt() == 0x04
                ) return SEI_T35
                if (payloadType == SEI_MASTERING) mastering = true
                p += size
            }
        }
        return if (mastering) SEI_MASTERING else null
    }

    /** NAL payload without emulation-prevention bytes (00 00 03 -> 00 00). */
    private fun unescape(data: ByteArray, start: Int, end: Int): ByteArray {
        val out = ByteArray(end - start)
        var n = 0
        var zeros = 0
        for (i in start until end) {
            val b = data[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) { zeros = 0; continue }
            zeros = if (b == 0) zeros + 1 else 0
            out[n++] = b.toByte()
        }
        return out.copyOf(n)
    }
}
