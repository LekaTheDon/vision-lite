package com.lekathedon.visionlite.player.pics

/** EBML cursor over a byte array (element ids keep their marker bits, sizes drop them; -1 = unknown size). */
internal class Ebml(val b: ByteArray, var p: Int = 0) {
    fun has(n: Int) = p + n <= b.size
    fun id(): Long {
        val f = b[p].toInt() and 0xff
        val n = when { f >= 0x80 -> 1; f >= 0x40 -> 2; f >= 0x20 -> 3; else -> 4 }
        var v = 0L; repeat(n) { v = v shl 8 or (b[p++].toLong() and 0xff) }; return v
    }
    fun size(): Long {
        val f = b[p].toInt() and 0xff
        var n = 1; while (n <= 8 && f and (0x80 shr (n - 1)) == 0) n++
        if (n > 8) { p++; return -1 }
        val mask = 0xff shr n
        var v = (f and mask).toLong(); var all = v == mask.toLong(); p++
        repeat(n - 1) { val x = b[p++].toInt() and 0xff; v = v shl 8 or x.toLong(); if (x != 0xff) all = false }
        return if (all) -1 else v
    }
    fun uint(n: Long): Long { var v = 0L; repeat(n.toInt()) { v = v shl 8 or (b[p++].toLong() and 0xff) }; return v }
    fun bytes(n: Long): ByteArray { val r = b.copyOfRange(p, p + n.toInt()); p += n.toInt(); return r }
    fun str(n: Long) = String(bytes(n), Charsets.US_ASCII).trimEnd('\u0000')
}

/** Matroska / WebM: SeekHead → Tracks + Cues; a picture is the video keyframe a cue point names. */
internal object Mkv {
    private const val SEGMENT = 0x18538067L; private const val CLUSTER = 0x1F43B675L; private const val SEEKHEAD = 0x114D9B74L
    private const val INFO = 0x1549A966L; private const val TRACKS = 0x1654AE6BL; private const val CUES = 0x1C53BB6BL

    private class T(var num: Long = -1, var type: Long = 0, var codec: String = "", var priv: ByteArray? = null,
                    var w: Int = 0, var h: Int = 0, var dv: Int = -1, var prefix: ByteArray? = null, var zlib: Boolean = false)

    fun open(r: Ranges): Index? {
        val head = r.read(0, 256 * 1024)
        val e = Ebml(head)
        if (head.size < 64 || e.id() != 0x1A45DFA3L) return null
        e.p += e.size().toInt()
        if (e.id() != SEGMENT) return null
        e.size(); val seg = e.p.toLong()
        var cuesAt = -1L; var tracksAt = -1L; var scale = 1_000_000L; var video: T? = null
        while (e.has(12)) {
            val at = e.p
            val id = e.id(); val s = e.size()
            if (id == CLUSTER || s < 0) break
            val end = e.p + s
            val inside = end <= head.size
            when (id) {
                SEEKHEAD -> if (inside) while (e.p < end) {
                    e.id(); val s2 = e.size(); val end2 = e.p + s2; var sid = 0L; var pos = -1L
                    while (e.p < end2) { val i3 = e.id(); val s3 = e.size(); when (i3) { 0x53ABL -> sid = e.uint(s3); 0x53ACL -> pos = e.uint(s3); else -> e.p += s3.toInt() } }
                    if (sid == CUES && cuesAt < 0) cuesAt = seg + pos
                    if (sid == TRACKS && tracksAt < 0) tracksAt = seg + pos
                }
                INFO -> if (inside) while (e.p < end) { val i2 = e.id(); val s2 = e.size(); if (i2 == 0x2AD7B1L) scale = e.uint(s2) else e.p += s2.toInt() }
                TRACKS -> if (inside) video = tracks(e, end.toInt())
                CUES -> if (cuesAt < 0) cuesAt = at.toLong()
            }
            if (!inside) break
            e.p = end.toInt()
        }
        if (video == null && tracksAt >= 0) {
            val t = r.read(tracksAt, 512 * 1024); val q = Ebml(t)
            if (q.id() == TRACKS) { val s = q.size(); video = tracks(q, minOf(q.p + s, t.size.toLong()).toInt()) }
        }
        val v = video ?: return null
        val track = trackOf(v) ?: return null
        val keys = ArrayList<Key>()
        if (cuesAt >= 0) {
            val h = r.read(cuesAt, 16); val q = Ebml(h)
            if (q.id() != CUES) return null
            val s = q.size(); if (s <= 0 || s > 32L * 1024 * 1024) return null
            val c = r.read(cuesAt + q.p, s.toInt())
            cues(Ebml(c), c.size, v.num, seg, scale, keys)
        }
        if (keys.isEmpty()) return null
        keys.sortBy { it.ms }
        return object : Index(track, keys) {
            override fun frame(k: Key): ByteArray? = keyframe(r, k, v.num)
        }
    }

    private fun tracks(e: Ebml, end: Int): T? {
        var found: T? = null
        while (e.p < end) {
            val id = e.id(); val s = e.size(); val te = e.p + s.toInt()
            if (id == 0xAEL) {
                val t = T()
                while (e.p < te) {
                    val i2 = e.id(); val s2 = e.size(); val e2 = e.p + s2.toInt()
                    when (i2) {
                        0xD7L -> t.num = e.uint(s2)
                        0x83L -> t.type = e.uint(s2)
                        0x86L -> t.codec = e.str(s2)
                        0x63A2L -> t.priv = e.bytes(s2)
                        0xE0L -> while (e.p < e2) { val i3 = e.id(); val s3 = e.size(); when (i3) { 0xB0L -> t.w = e.uint(s3).toInt(); 0xBAL -> t.h = e.uint(s3).toInt(); else -> e.p += s3.toInt() } }
                        0x41E4L -> while (e.p < e2) { val i3 = e.id(); val s3 = e.size(); if (i3 == 0x41EDL) t.dv = Codecs.dvProfile(e.bytes(s3)) else e.p += s3.toInt() }
                        0x6D80L -> encodings(e, e2, t)
                    }
                    e.p = e2
                }
                if (t.type == 1L && found == null) found = t
            }
            e.p = te
        }
        return found
    }

    /** Header stripping (algo 3: bytes to put back in front) or zlib (algo 0) on every frame. */
    private fun encodings(e: Ebml, end: Int, t: T) {
        while (e.p < end) {
            val id = e.id(); val s = e.size(); val x = e.p + s.toInt()
            if (id == 0x6240L || id == 0x5034L) { encodings(e, x, t); continue }
            when (id) {
                0x4254L -> { val algo = e.uint(s); if (algo == 0L) t.zlib = true }
                0x4255L -> t.prefix = e.bytes(s)
            }
            e.p = x
        }
    }

    private fun trackOf(t: T): Track? {
        val c = t.codec
        return when {
            c.startsWith("V_MPEGH/ISO/HEVC") -> Codecs.hevc(t.priv, t.w, t.h, t.dv, t.prefix, t.zlib)
            c.startsWith("V_MPEG4/ISO/AVC") -> Codecs.avc(t.priv, t.w, t.h, t.prefix, t.zlib)
            c.startsWith("V_MPEG4/ISO/") -> Track("video/mp4v-es", listOfNotNull(t.priv), 0, t.w, t.h, prefix = t.prefix, zlib = t.zlib)
            c == "V_AV1" -> Track("video/av01", listOfNotNull(t.priv), 0, t.w, t.h, prefix = t.prefix, zlib = t.zlib)
            c == "V_VP9" -> Track("video/x-vnd.on2.vp9", emptyList(), 0, t.w, t.h)
            c == "V_VP8" -> Track("video/x-vnd.on2.vp8", emptyList(), 0, t.w, t.h)
            c == "V_MPEG2" || c == "V_MPEG1" -> Track("video/mpeg2", listOfNotNull(t.priv), 0, t.w, t.h, prefix = t.prefix, zlib = t.zlib)
            c == "V_MJPEG" -> Track("video/mjpeg", emptyList(), 0, t.w, t.h)
            c == "V_MS/VFW/FOURCC" -> {
                val p = t.priv ?: return null
                if (p.size < 40) return null
                val mime = Codecs.fourcc(String(p, 16, 4, Charsets.US_ASCII)) ?: return null
                val extra = if (p.size > 40) p.copyOfRange(40, p.size) else null
                when (mime) {
                    "video/avc" -> extra?.let { Codecs.avc(it, t.w, t.h) } ?: Track(mime, emptyList(), 0, t.w, t.h)
                    else -> Track(mime, listOfNotNull(extra), 0, t.w, t.h, prefix = t.prefix, zlib = t.zlib)
                }
            }
            else -> null
        }
    }

    private fun cues(e: Ebml, end: Int, track: Long, seg: Long, scale: Long, out: MutableList<Key>) {
        while (e.p < end && e.has(2)) {
            val id = e.id(); val s = e.size(); val x = e.p + s.toInt()
            if (s < 0 || x > end) return
            if (id == 0xBBL) {
                var time = 0L; var cl = -1L; var rel = -1L
                while (e.p < x) {
                    val i2 = e.id(); val s2 = e.size(); val x2 = e.p + s2.toInt()
                    if (i2 == 0xB3L) time = e.uint(s2)
                    else if (i2 == 0xB7L) {
                        var tr = -1L; var c0 = -1L; var r0 = -1L
                        while (e.p < x2) { val i3 = e.id(); val s3 = e.size(); when (i3) { 0xF7L -> tr = e.uint(s3); 0xF1L -> c0 = e.uint(s3); 0xF0L -> r0 = e.uint(s3); else -> e.p += s3.toInt() } }
                        if (tr == track) { cl = c0; rel = r0 }
                    }
                    e.p = x2
                }
                if (cl >= 0) out.add(Key(time * scale / 1_000_000, seg + cl, rel = rel))
            }
            e.p = x
        }
    }

    /** The video keyframe at a cue: the block the cue's relative position names, else the first one in the cluster. */
    private fun keyframe(r: Ranges, k: Key, track: Long): ByteArray? {
        var b = r.read(k.pos, 256 * 1024)
        val e = Ebml(b)
        if (!e.has(12) || e.id() != CLUSTER) return null
        e.size()
        val from = if (k.rel >= 0) e.p + k.rel.toInt() else e.p
        while (true) {
            val got = scan(b, from, track)
            if (got is Found) {
                val start = got.at; val len = got.len
                return if (start + len <= b.size) b.copyOfRange(start, start + len) else r.read(k.pos + start, len)
            }
            if (got != More || b.size >= 4 * 1024 * 1024 || k.pos + b.size >= r.size) return null
            b += r.read(k.pos + b.size, 512 * 1024)
        }
    }

    private object More
    private object None
    private class Found(val at: Int, val len: Int)

    private fun scan(b: ByteArray, from: Int, track: Long): Any {
        val e = Ebml(b, from)
        while (true) {
            if (!e.has(16)) return More
            val id = e.id(); val s = e.size()
            if (id == 0xA0L) continue                    // BlockGroup: its Block is inside
            if (id == CLUSTER) return None               // next cluster: nothing found
            if (s < 0) return None
            val dataAt = e.p
            if (id == 0xA3L || id == 0xA1L) {
                if (!e.has(8)) return More
                val q = Ebml(b, dataAt); val tr = q.size(); val hdr = q.p - dataAt + 3
                val flags = b[q.p + 2].toInt() and 0xff
                if (tr == track) {
                    if (flags and 0x06 != 0 || (id == 0xA3L && flags and 0x80 == 0)) return None   // laced or not a keyframe
                    return Found(dataAt + hdr, (s - hdr).toInt())
                }
            }
            e.p = dataAt + s.toInt()
        }
    }
}
