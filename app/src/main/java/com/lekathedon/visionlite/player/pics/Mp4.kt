package com.lekathedon.visionlite.player.pics

import java.nio.ByteBuffer

/** MP4 / MOV: the video track's sample tables (stss sync samples, stts times, stsc/stco/stsz places). */
internal object Mp4 {
    private class Box(val type: String, val start: Int, val end: Int)

    private fun boxes(b: ByteBuffer, from: Int, to: Int): List<Box> {
        val out = ArrayList<Box>(); var p = from
        while (p + 8 <= to) {
            var size = b.getInt(p).toLong() and 0xffffffffL
            val type = String(ByteArray(4) { b.get(p + 4 + it) }, Charsets.US_ASCII)
            var hdr = 8
            if (size == 1L) { size = b.getLong(p + 8); hdr = 16 } else if (size == 0L) size = (to - p).toLong()
            if (size < hdr || p + size > to) break
            out.add(Box(type, p + hdr, (p + size).toInt())); p += size.toInt()
        }
        return out
    }
    private fun ByteBuffer.u32(i: Int) = getInt(i).toLong() and 0xffffffffL

    fun open(r: Ranges): Index? {
        // Top-level walk with small reads until moov (it may sit at the end of the file).
        var pos = 0L; var moov: ByteArray? = null
        repeat(64) {
            if (pos + 8 > r.size || moov != null) return@repeat
            val h = ByteBuffer.wrap(r.read(pos, 16))
            if (h.capacity() < 8) return null
            var size = h.getInt(0).toLong() and 0xffffffffL
            val type = String(ByteArray(4) { h.get(4 + it) }, Charsets.US_ASCII)
            var hdr = 8
            if (size == 1L && h.capacity() >= 16) { size = h.getLong(8); hdr = 16 } else if (size == 0L) size = r.size - pos
            if (pos == 0L && type != "ftyp" && type != "moov" && type != "mdat" && type != "free" && type != "wide") return null
            if (type == "moov") { if (size > 96L * 1024 * 1024) return null; moov = r.read(pos + hdr, (size - hdr).toInt()) }
            if (size < 8) return null
            pos += size
        }
        val m = ByteBuffer.wrap(moov ?: return null)
        for (trak in boxes(m, 0, m.capacity()).filter { it.type == "trak" }) {
            val mdia = boxes(m, trak.start, trak.end).firstOrNull { it.type == "mdia" } ?: continue
            val kids = boxes(m, mdia.start, mdia.end)
            val hdlr = kids.firstOrNull { it.type == "hdlr" } ?: continue
            if (String(ByteArray(4) { m.get(hdlr.start + 8 + it) }, Charsets.US_ASCII) != "vide") continue
            val mdhd = kids.firstOrNull { it.type == "mdhd" } ?: continue
            val scale = if (m.get(mdhd.start).toInt() == 1) m.u32(mdhd.start + 20) else m.u32(mdhd.start + 12)
            val minf = kids.firstOrNull { it.type == "minf" } ?: continue
            val stbl = boxes(m, minf.start, minf.end).firstOrNull { it.type == "stbl" } ?: continue
            val t = boxes(m, stbl.start, stbl.end).associateBy { it.type }
            val track = sampleEntry(m, t["stsd"] ?: continue) ?: continue
            val keys = keys(m, t, scale) ?: continue
            if (keys.isEmpty()) continue
            return object : Index(track, keys) { override fun frame(k: Key) = r.read(k.pos, k.size) }
        }
        return null
    }

    private fun sampleEntry(m: ByteBuffer, stsd: Box): Track? {
        val e = boxes(m, stsd.start + 8, stsd.end).firstOrNull() ?: return null
        val w = m.getShort(e.start + 24).toInt() and 0xffff; val h = m.getShort(e.start + 26).toInt() and 0xffff
        val kids = boxes(m, e.start + 78, e.end).associateBy { it.type }
        fun body(type: String) = kids[type]?.let { ByteArray(it.end - it.start) { i -> m.get(it.start + i) } }
        val dv = Codecs.dvProfile(body("dvcC") ?: body("dvvC") ?: body("dvwC"))
        return when (e.type) {
            "hvc1", "hev1", "dvh1", "dvhe" -> Codecs.hevc(body("hvcC"), w, h, dv)
            "avc1", "avc3", "dva1", "dvav" -> Codecs.avc(body("avcC"), w, h) ?: Track("video/avc", emptyList(), 4, w, h)
            "av01" -> Track("video/av01", listOfNotNull(body("av1C")), 0, w, h, dv)
            "vp09" -> Track("video/x-vnd.on2.vp9", emptyList(), 0, w, h)
            "vp08" -> Track("video/x-vnd.on2.vp8", emptyList(), 0, w, h)
            "mp4v" -> Track("video/mp4v-es", listOfNotNull(body("esds")?.let { dsi(it) }), 0, w, h)
            "mjpa", "mjpb", "jpeg" -> Track("video/mjpeg", emptyList(), 0, w, h)
            "s263", "h263" -> Track("video/3gpp", emptyList(), 0, w, h)
            else -> null
        }
    }

    /** MPEG-4 Part 2 decoder config (DecoderSpecificInfo, tag 5) from an esds box. */
    private fun dsi(b: ByteArray): ByteArray? {
        var p = 4
        fun len(): Int { var n = 0; repeat(4) { val x = b[p++].toInt() and 0xff; n = n shl 7 or (x and 0x7f); if (x and 0x80 == 0) return n }; return n }
        while (p < b.size) {
            val tag = b[p++].toInt() and 0xff; val n = len()
            when (tag) {
                3 -> { val f = b[p + 2].toInt() and 0xff; p += 3; if (f and 0x80 != 0) p += 2; if (f and 0x40 != 0) p += 1 + (b[p].toInt() and 0xff); if (f and 0x20 != 0) p += 2 }
                4 -> p += 13
                5 -> return b.copyOfRange(p, minOf(b.size, p + n))
                else -> p += n
            }
        }
        return null
    }

    private fun keys(m: ByteBuffer, t: Map<String, Box>, scale: Long): List<Key>? {
        if (scale <= 0) return null
        val stts = t["stts"] ?: return null; val stsc = t["stsc"] ?: return null; val stsz = t["stsz"] ?: return null
        val co = t["stco"] ?: t["co64"] ?: return null; val wide = co.type == "co64"
        val count = m.u32(stsz.start + 8).toInt(); val fixed = m.u32(stsz.start + 4).toInt()
        fun sizeOf(i: Int) = if (fixed != 0) fixed else m.getInt(stsz.start + 12 + 4 * i)
        // Sync samples (0-based); none listed = every sample is one (take at most one per ~2 s later).
        val sync: IntArray = t["stss"]?.let { s -> IntArray(m.u32(s.start + 4).toInt()) { m.getInt(s.start + 8 + 4 * it) - 1 } }
            ?: IntArray(count) { it }
        val want = java.util.BitSet(count).apply { sync.forEach { if (it in 0 until count) set(it) } }
        val times = LongArray(count); run {
            var i = 0; var t0 = 0L; val n = m.u32(stts.start + 4).toInt()
            for (e in 0 until n) { val c = m.u32(stts.start + 8 + 8 * e); val d = m.u32(stts.start + 12 + 8 * e)
                for (k in 0 until c) { if (i >= count) break; times[i++] = t0; t0 += d } }
        }
        val chunks = m.u32(co.start + 4).toInt()
        fun chunkAt(c: Int) = if (wide) m.getLong(co.start + 8 + 8 * c) else m.u32(co.start + 8 + 4 * c)
        val runs = m.u32(stsc.start + 4).toInt()
        val out = ArrayList<Key>(sync.size)
        var sample = 0
        for (r in 0 until runs) {
            val first = m.u32(stsc.start + 8 + 12 * r).toInt() - 1
            val per = m.u32(stsc.start + 12 + 12 * r).toInt()
            val last = if (r + 1 < runs) m.u32(stsc.start + 8 + 12 * (r + 1)).toInt() - 1 else chunks
            for (c in first until last) {
                var off = chunkAt(c)
                for (k in 0 until per) {
                    if (sample >= count) return out
                    val sz = sizeOf(sample)
                    if (want[sample]) out.add(Key(times[sample] * 1000 / scale, off, sz))
                    off += sz; sample++
                }
            }
        }
        return out
    }
}
