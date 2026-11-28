package com.lekathedon.visionlite.probe

import java.io.IOException

/** The file can't be read the way the probe needs (not MKV/MP4, no Range, damaged). */
internal class ProbeException(message: String) : IOException(message)

/** Dolby Vision configuration record (MP4 dvcC/dvvC, MKV BlockAdditionMapping): profile + base-layer compatibility. */
internal data class DvConfig(val profile: Int, val compat: Int)

/** The main picture track. [transfer] is the ISO 23001-8 code (16 PQ, 18 HLG, -1 unknown). */
internal class VideoInfo(
    val codec: String, val width: Int, val height: Int, val transfer: Int, val bitDepth: Int,
    val dv: DvConfig?, val lenSize: Int, val paramSets: ByteArray,
)

/** What one pass over the head of a file gave. [keyframe] reads the first keyframe on demand. */
internal class FileFacts(val video: VideoInfo, val tracks: List<ProbeTrack>, private val first: (() -> Frame?)?) {
    class Frame(val offset: Long, val size: Int)

    /** The first keyframe as Annex-B, parameter sets in front; null when the file doesn't say where it is. */
    fun keyframe(w: Window): ByteArray? {
        val f = first?.invoke() ?: return null
        if (f.size <= 0 || f.size > HttpRangeReader.MAX_READ) return null
        val data = w.fetch(f.offset, f.size)
        if (data.size < f.size) return null
        return Nal.toAnnexB(data, video.lenSize, video.paramSets)
    }
}

/**
 * Range reads through a cached window, so walking many small headers costs few requests. The head of the file
 * (the first read) stays cached for the whole pass.
 */
internal class Window(private val r: HttpRangeReader, private val head: ByteArray) {
    private var at = -1L
    private var buf = ByteArray(0)

    val total get() = r.totalLength

    /** Up to [len] bytes at [pos] (fewer only at end of file). */
    fun bytes(pos: Long, len: Int): ByteArray {
        if (pos + len <= head.size) return head.copyOfRange(pos.toInt(), (pos + len).toInt())
        if (at >= 0 && pos >= at && pos + len <= at + buf.size) return buf.copyOfRange((pos - at).toInt(), (pos - at + len).toInt())
        if (len > CHUNK) return fetch(pos, len)
        buf = r.read(pos, CHUNK); at = pos
        return if (buf.size >= len) buf.copyOf(len) else buf.copyOf()
    }

    /** Exactly [len] bytes at [pos] in reads of at most [HttpRangeReader.MAX_READ]; shorter only at end of file. */
    fun fetch(pos: Long, len: Int): ByteArray {
        if (len <= CHUNK) return bytes(pos, len)
        val out = java.io.ByteArrayOutputStream(len)
        while (out.size() < len) {
            val b = r.read(pos + out.size(), minOf(HttpRangeReader.MAX_READ, len - out.size()))
            if (b.isEmpty()) break
            out.write(b)
        }
        return out.toByteArray()
    }

    private companion object { const val CHUNK = 128 * 1024 }
}

internal object Bytes {
    fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    fun u16(b: ByteArray, i: Int) = u8(b, i) shl 8 or u8(b, i + 1)
    fun u32(b: ByteArray, i: Int): Long = (u16(b, i).toLong() shl 16) or u16(b, i + 2).toLong()
    fun u64(b: ByteArray, i: Int): Long = (u32(b, i) shl 32) or u32(b, i + 4)
    fun uint(b: ByteArray, from: Int, to: Int): Long { var v = 0L; for (i in from until to) v = v shl 8 or u8(b, i).toLong(); return v }
    fun fourcc(b: ByteArray, i: Int) = String(b, i, 4, Charsets.ISO_8859_1)
}

/** HEVC / AVC framing helpers. */
internal object Nal {
    fun isAnnexB(b: ByteArray) = b.size >= 4 && b[0].toInt() == 0 && b[1].toInt() == 0 &&
        (b[2].toInt() == 1 || b[2].toInt() == 0 && b[3].toInt() == 1)

    /** Length-prefixed sample -> Annex-B with [prefix] (parameter sets, already Annex-B) in front. */
    fun toAnnexB(sample: ByteArray, lenSize: Int, prefix: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(sample.size + prefix.size + 64)
        out.write(prefix)
        if (isAnnexB(sample)) { out.write(sample); return out.toByteArray() }
        var p = 0
        while (p + lenSize <= sample.size) {
            val n = Bytes.uint(sample, p, p + lenSize).toInt()
            p += lenSize
            if (n <= 0 || p + n > sample.size) break
            out.write(START); out.write(sample, p, n)
            p += n
        }
        return out.toByteArray()
    }

    private val START = byteArrayOf(0, 0, 0, 1)

    class Config(val lenSize: Int, val bitDepth: Int, val paramSets: ByteArray)

    /** HEVCDecoderConfigurationRecord (ISO 14496-15 8.3.3.1). */
    fun hvcC(c: ByteArray?): Config? {
        if (c == null || c.size < 23) return null
        val out = java.io.ByteArrayOutputStream()
        var p = 23
        repeat(Bytes.u8(c, 22)) {
            if (p + 3 > c.size) return@repeat
            val count = Bytes.u16(c, p + 1); p += 3
            repeat(count) {
                if (p + 2 > c.size) return@repeat
                val n = Bytes.u16(c, p); p += 2
                if (p + n > c.size) return@repeat
                out.write(START); out.write(c, p, n); p += n
            }
        }
        return Config((Bytes.u8(c, 21) and 3) + 1, (Bytes.u8(c, 18) and 7) + 8, out.toByteArray())
    }

    /** AVCDecoderConfigurationRecord (ISO 14496-15 5.3.3.1). */
    fun avcC(c: ByteArray?): Config? {
        if (c == null || c.size < 7) return null
        val out = java.io.ByteArrayOutputStream()
        var p = 5
        for (group in 0..1) {
            if (p >= c.size) break
            val count = if (group == 0) Bytes.u8(c, p) and 31 else Bytes.u8(c, p)
            p++
            repeat(count) {
                if (p + 2 > c.size) return@repeat
                val n = Bytes.u16(c, p); p += 2
                if (p + n > c.size) return@repeat
                out.write(START); out.write(c, p, n); p += n
            }
        }
        return Config((Bytes.u8(c, 4) and 3) + 1, 8, out.toByteArray())
    }

    /** Dolby Vision configuration record payload (after the box / mapping header). */
    fun dv(c: ByteArray?): DvConfig? {
        if (c == null || c.size < 5) return null
        return DvConfig((Bytes.u8(c, 2) shr 1) and 0x7F, (Bytes.u8(c, 4) shr 4) and 0xF)
    }
}

/** Matroska / WebM: EBML header, Segment children up to the first Cluster, Tracks, then the first video block. */
internal object MkvFile {
    private const val EBML = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val SEEKHEAD = 0x114D9B74L
    private const val SEEK = 0x4DBBL
    private const val SEEK_ID = 0x53ABL
    private const val SEEK_POS = 0x53ACL
    private const val TRACKS = 0x1654AE6BL
    private const val CLUSTER = 0x1F43B675L
    private const val SIMPLE_BLOCK = 0xA3L
    private const val BLOCK_GROUP = 0xA0L
    private const val BLOCK = 0xA1L

    fun looks(head: ByteArray) = head.size >= 4 && Bytes.u32(head, 0) == EBML

    /** Element header: id, where its data starts, data size (-1 = unknown size). */
    private class El(val id: Long, val data: Long, val size: Long) { val end get() = if (size < 0) Long.MAX_VALUE else data + size }

    /** (value, length) of an EBML variable-size integer at [i]; [keepMarker] for ids. Null when not one. */
    private fun vint(b: ByteArray, i: Int, keepMarker: Boolean): Pair<Long, Int>? {
        if (i >= b.size) return null
        val first = Bytes.u8(b, i)
        if (first == 0) return null
        val n = Integer.numberOfLeadingZeros(first) - 23
        if (i + n > b.size || (keepMarker && n > 4)) return null
        var v = if (keepMarker) first.toLong() else (first and (0xFF shr n)).toLong()
        var ones = v == (0xFF shr n).toLong()
        for (k in 1 until n) { val x = Bytes.u8(b, i + k); v = v shl 8 or x.toLong(); ones = ones && x == 0xFF }
        return (if (!keepMarker && ones) -1L else v) to n
    }

    private fun header(b: ByteArray, i: Int, base: Long): El? {
        val (id, a) = vint(b, i, true) ?: return null
        val (size, c) = vint(b, i + a, false) ?: return null
        return El(id, base + i + a + c, size)
    }

    private fun header(w: Window, pos: Long): El? = header(w.bytes(pos, 12), 0, pos)

    /** Children of a loaded master element: (id, data from, data to) within [b]. */
    private fun children(b: ByteArray, from: Int = 0, to: Int = b.size) = sequence {
        var p = from
        while (p < to) {
            val e = header(b, p, 0) ?: break
            if (e.size < 0 || e.data + e.size > to) break
            yield(Triple(e.id, e.data.toInt(), (e.data + e.size).toInt()))
            p = (e.data + e.size).toInt()
        }
    }

    private fun str(b: ByteArray, f: Int, t: Int) = String(b, f, t - f, Charsets.UTF_8).trimEnd('\u0000')

    private class Entry {
        var number = 0L; var type = 0; var codec = ""; var priv: ByteArray? = null; var lang = "eng"; var bcp: String? = null
        var name: String? = null; var forced = false; var width = 0; var height = 0; var bits = 0; var transfer = -1
        var channels = 0; var dv: DvConfig? = null
    }

    fun read(w: Window): FileFacts {
        val ebml = header(w, 0) ?: throw ProbeException("bad EBML header")
        val seg = header(w, ebml.end) ?: throw ProbeException("no Segment")
        if (seg.id != SEGMENT) throw ProbeException("no Segment")
        var tracks: ByteArray? = null
        var tracksAt = -1L
        var cluster = -1L
        var p = seg.data
        var steps = 0
        while (p < seg.end && steps++ < 96 && cluster < 0) {
            val e = header(w, p) ?: break
            when (e.id) {
                SEEKHEAD -> if (e.size in 1..65536) {
                    val b = w.bytes(e.data, e.size.toInt())
                    for ((id, f, t) in children(b)) if (id == SEEK) {
                        var sid = 0L; var pos = -1L
                        for ((cid, cf, ct) in children(b, f, t)) when (cid) { SEEK_ID -> sid = Bytes.uint(b, cf, ct); SEEK_POS -> pos = Bytes.uint(b, cf, ct) }
                        if (sid == TRACKS && pos >= 0) tracksAt = seg.data + pos
                    }
                }
                TRACKS -> if (e.size in 1..(4 shl 20)) tracks = w.bytes(e.data, e.size.toInt())
                CLUSTER -> cluster = p
            }
            if (e.size < 0) break
            p = e.end
        }
        if (tracks == null && tracksAt >= 0) {
            val e = header(w, tracksAt)
            if (e != null && e.id == TRACKS && e.size in 1..(4 shl 20)) tracks = w.fetch(e.data, e.size.toInt())
        }
        val t = tracks ?: throw ProbeException("no Tracks")
        val entries = children(t).filter { it.first == 0xAEL }.map { (_, f, to) -> entry(t, f, to) }.toList()
        val v = entries.firstOrNull { it.type == 1 } ?: throw ProbeException("no video track")
        val codec = when (v.codec) { "V_MPEGH/ISO/HEVC" -> "hevc"; "V_MPEG4/ISO/AVC" -> "avc"; "V_AV1" -> "av1"; else -> v.codec.lowercase() }
        val cfg = when (codec) { "hevc" -> Nal.hvcC(v.priv); "avc" -> Nal.avcC(v.priv); else -> null }
        val video = VideoInfo(codec, v.width, v.height, v.transfer, if (v.bits > 0) v.bits else cfg?.bitDepth ?: 8, v.dv,
            cfg?.lenSize ?: 4, cfg?.paramSets ?: ByteArray(0))
        val side = entries.filter { it.type == 2 || it.type == 17 }.map {
            ProbeTrack(if (it.type == 2) 'a' else 's', it.codec, it.bcp ?: it.lang, it.channels, it.name, it.forced)
        }
        return FileFacts(video, side, if (cluster >= 0) ({ firstBlock(w, cluster, v.number) }) else null)
    }

    private fun entry(b: ByteArray, from: Int, to: Int) = Entry().apply {
        for ((id, f, t) in children(b, from, to)) when (id) {
            0xD7L -> number = Bytes.uint(b, f, t)
            0x83L -> type = Bytes.uint(b, f, t).toInt()
            0x86L -> codec = str(b, f, t)
            0x63A2L -> priv = b.copyOfRange(f, t)
            0x22B59CL -> lang = str(b, f, t)
            0x22B59DL -> bcp = str(b, f, t)
            0x536EL -> name = str(b, f, t)
            0x55AAL -> forced = Bytes.uint(b, f, t) != 0L
            0xE0L -> for ((vid, vf, vt) in children(b, f, t)) when (vid) {
                0xB0L -> width = Bytes.uint(b, vf, vt).toInt()
                0xBAL -> height = Bytes.uint(b, vf, vt).toInt()
                0x55B0L -> for ((cid, cf, ct) in children(b, vf, vt)) when (cid) {
                    0x55B2L -> bits = Bytes.uint(b, cf, ct).toInt()
                    0x55BAL -> transfer = Bytes.uint(b, cf, ct).toInt()
                }
            }
            0xE1L -> for ((aid, af, at) in children(b, f, t)) if (aid == 0x9FL) channels = Bytes.uint(b, af, at).toInt()
            0x41E4L -> {
                var kind = 0L; var extra: ByteArray? = null
                for ((mid, mf, mt) in children(b, f, t)) when (mid) { 0x41E7L -> kind = Bytes.uint(b, mf, mt); 0x41EDL -> extra = b.copyOfRange(mf, mt) }
                if (kind == 0x64766343L || kind == 0x64767643L) Nal.dv(extra)?.let { dv = it }   // dvcC / dvvC
            }
        }
    }

    /** The first unlaced block of [track] in the cluster at [at]: where its frame is. */
    private fun firstBlock(w: Window, at: Long, track: Long): FileFacts.Frame? {
        val c = header(w, at) ?: return null
        var p = c.data
        var steps = 0
        while (p < c.end && steps++ < 400) {
            val e = header(w, p) ?: return null
            if (e.size < 0) return null
            if (e.id == CLUSTER && p != at) return null
            val blk = when (e.id) {
                SIMPLE_BLOCK -> e
                BLOCK_GROUP -> {
                    var q = e.data; var found: El? = null
                    while (q < e.end && found == null) { val g = header(w, q) ?: break; if (g.size < 0) break; if (g.id == BLOCK) found = g; q = g.end }
                    found
                }
                else -> null
            }
            if (blk != null) {
                val h = w.bytes(blk.data, 12)
                val (num, n) = vint(h, 0, false) ?: return null
                if (num == track) {
                    if ((Bytes.u8(h, n + 2) and 0x06) != 0) return null   // laced video: not worth it
                    val skip = n + 3
                    return FileFacts.Frame(blk.data + skip, (blk.size - skip).toInt())
                }
            }
            p = e.end
        }
        return null
    }
}

/** ISO BMFF (MP4 / MOV): top-level boxes to moov, its traks, and the first sync sample of the video trak. */
internal object Mp4File {
    fun looks(head: ByteArray) = head.size >= 12 && Bytes.fourcc(head, 4).let { it == "ftyp" || it == "moov" || it == "free" || it == "skip" || it == "wide" || it == "mdat" }

    private class Box(val type: String, val from: Int, val to: Int)

    private fun boxes(b: ByteArray, from: Int, to: Int) = sequence {
        var p = from
        while (p + 8 <= to) {
            var size = Bytes.u32(b, p); var hdr = 8
            if (size == 1L && p + 16 <= to) { size = Bytes.u64(b, p + 8); hdr = 16 } else if (size == 0L) size = (to - p).toLong()
            if (size < hdr || p + size > to) break
            yield(Box(Bytes.fourcc(b, p + 4), p + hdr, (p + size).toInt()))
            p += size.toInt()
        }
    }

    private fun child(b: ByteArray, parent: Box?, vararg path: String): Box? {
        var cur = parent ?: return null
        for (t in path) cur = boxes(b, cur.from, cur.to).firstOrNull { it.type == t } ?: return null
        return cur
    }

    fun read(w: Window): FileFacts {
        var p = 0L
        var moov: ByteArray? = null
        var steps = 0
        while (steps++ < 64) {
            val h = w.bytes(p, 16)
            if (h.size < 8) break
            var size = Bytes.u32(h, 0); var hdr = 8
            if (size == 1L && h.size >= 16) { size = Bytes.u64(h, 8); hdr = 16 } else if (size == 0L) size = if (w.total > 0) w.total - p else break
            if (size < hdr) break
            if (Bytes.fourcc(h, 4) == "moov") {
                if (size - hdr > (32 shl 20)) throw ProbeException("moov too large")
                moov = w.fetch(p + hdr, (size - hdr).toInt()); break
            }
            p += size
        }
        val m = moov ?: throw ProbeException("no moov")
        var video: VideoInfo? = null
        var first: (() -> FileFacts.Frame?)? = null
        val side = mutableListOf<ProbeTrack>()
        for (trak in boxes(m, 0, m.size).filter { it.type == "trak" }) {
            val mdia = child(m, trak, "mdia") ?: continue
            val handler = child(m, mdia, "hdlr")?.let { Bytes.fourcc(m, it.from + 8) } ?: continue
            val stbl = child(m, mdia, "minf", "stbl") ?: continue
            val stsd = child(m, stbl, "stsd") ?: continue
            val entry = boxes(m, stsd.from + 8, stsd.to).firstOrNull() ?: continue
            when (handler) {
                "vide" -> if (video == null) {
                    video = visual(m, entry)
                    first = sync(m, stbl)?.let { f -> { f } }
                }
                "soun" -> side += audio(m, entry, lang(m, mdia))
                "subt", "sbtl", "text" -> side += ProbeTrack('s', entry.type, lang(m, mdia), 0, null, false)
            }
        }
        return FileFacts(video ?: throw ProbeException("no video track"), side, first)
    }

    private fun lang(m: ByteArray, mdia: Box): String? {
        val h = child(m, mdia, "mdhd") ?: return null
        val at = h.from + if (Bytes.u8(m, h.from) == 1) 32 else 20
        if (at + 2 > h.to) return null
        val v = Bytes.u16(m, at)
        return String(charArrayOf('`' + (v shr 10 and 31), '`' + (v shr 5 and 31), '`' + (v and 31))).takeIf { it.all { c -> c in 'a'..'z' } }
    }

    private fun visual(m: ByteArray, e: Box): VideoInfo {
        val kids = boxes(m, e.from + 78, e.to).toList()
        fun get(t: String) = kids.firstOrNull { it.type == t }?.let { m.copyOfRange(it.from, it.to) }
        val codec = when (e.type) { "hvc1", "hev1", "dvh1", "dvhe" -> "hevc"; "avc1", "avc3", "dvav", "dva1" -> "avc"; "av01", "dav1" -> "av1"; else -> e.type }
        val cfg = when (codec) { "hevc" -> Nal.hvcC(get("hvcC")); "avc" -> Nal.avcC(get("avcC")); else -> null }
        val transfer = get("colr")?.takeIf { it.size >= 10 && Bytes.fourcc(it, 0) == "nclx" }?.let { Bytes.u16(it, 6) } ?: -1
        val dv = Nal.dv(get("dvcC") ?: get("dvvC") ?: get("dvwC"))
        val width = if (e.from + 28 <= e.to) Bytes.u16(m, e.from + 24) else 0
        val height = if (e.from + 28 <= e.to) Bytes.u16(m, e.from + 26) else 0
        return VideoInfo(codec, width, height, transfer, cfg?.bitDepth ?: 8, dv, cfg?.lenSize ?: 4, cfg?.paramSets ?: ByteArray(0))
    }

    private fun audio(m: ByteArray, e: Box, lang: String?): ProbeTrack {
        val ver = if (e.from + 10 <= e.to) Bytes.u16(m, e.from + 8) else 0
        var ch = if (e.from + 18 <= e.to) Bytes.u16(m, e.from + 16) else 0
        var name: String? = null
        val kids = boxes(m, e.from + 28 + when (ver) { 1 -> 16; 2 -> 36; else -> 0 }, e.to).toList()
        kids.firstOrNull { it.type == "dac3" }?.let { b -> if (b.to - b.from >= 3) ch = ac3Channels(Bytes.u8(m, b.from + 1) shr 3 and 7, Bytes.u8(m, b.from + 1) shr 2 and 1) }
        kids.firstOrNull { it.type == "dec3" }?.let { b ->
            if (b.to - b.from >= 6) {
                val x = Bytes.u32(m, b.from + 2)                  // first independent substream (ETSI TS 102 366 F.6)
                ch = ac3Channels((x shr 17 and 7).toInt(), (x shr 16 and 1).toInt())
                val dep = (x shr 9 and 15).toInt()
                if (dep > 0) ch += 2                                // dependent substream: 7.1
                val tail = b.from + 2 + if (dep > 0) 4 else 3
                if (tail < b.to && Bytes.u8(m, tail) and 1 == 1) name = "Atmos"   // flag_ec3_extension_type_a (JOC)
            }
        }
        if (e.type == "dtsl") name = "DTS-HD MA"
        return ProbeTrack('a', e.type, lang, ch, name, false)
    }

    private fun ac3Channels(acmod: Int, lfe: Int) = intArrayOf(2, 1, 2, 3, 3, 4, 4, 5)[acmod] + lfe

    /** File position of the first sync sample (stss; none = every sample is one) from stsc / stsz / stco. */
    private fun sync(m: ByteArray, stbl: Box): FileFacts.Frame? {
        val stsz = child(m, stbl, "stsz") ?: return null
        val stsc = child(m, stbl, "stsc") ?: return null
        val co = child(m, stbl, "stco") ?: child(m, stbl, "co64") ?: return null
        val wide = co.type == "co64"
        val n = child(m, stbl, "stss")?.takeIf { Bytes.u32(m, it.from + 4) > 0 }?.let { Bytes.u32(m, it.from + 8) } ?: 1L
        val fixed = Bytes.u32(m, stsz.from + 4)
        fun size(i: Long): Long {
            if (fixed != 0L) return fixed
            val at = stsz.from + 12 + (i - 1) * 4
            return if (at + 4 <= stsz.to) Bytes.u32(m, at.toInt()) else -1L
        }
        val chunks = Bytes.u32(m, co.from + 4)
        val runs = Bytes.u32(m, stsc.from + 4).toInt()
        var sample = 1L
        for (r in 0 until runs) {
            val at = stsc.from + 8 + r * 12
            if (at + 12 > stsc.to) return null
            val firstChunk = Bytes.u32(m, at); val per = Bytes.u32(m, at + 4)
            val next = if (r + 1 < runs && at + 24 <= stsc.to) Bytes.u32(m, at + 12) else chunks + 1
            if (per <= 0) return null
            val inRun = (next - firstChunk) * per
            if (n < sample + inRun) {
                val k = (n - sample) / per
                val chunk = firstChunk + k
                val firstInChunk = sample + k * per
                val ca = co.from + 8 + ((chunk - 1) * if (wide) 8 else 4).toInt()
                if (ca + (if (wide) 8 else 4) > co.to) return null
                var off = if (wide) Bytes.u64(m, ca) else Bytes.u32(m, ca)
                for (i in firstInChunk until n) off += size(i).takeIf { it >= 0 } ?: return null
                val s = size(n)
                return if (s > 0) FileFacts.Frame(off, s.toInt()) else null
            }
            sample += inRun
        }
        return null
    }
}
