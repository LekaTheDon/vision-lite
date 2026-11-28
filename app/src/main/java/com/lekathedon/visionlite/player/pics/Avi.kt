package com.lekathedon.visionlite.player.pics

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** AVI: the video stream's format (strh/strf) and the idx1 index's keyframe entries. */
internal object Avi {
    private fun cc(b: ByteBuffer, i: Int) = String(ByteArray(4) { b.get(i + it) }, Charsets.US_ASCII)

    fun open(r: Ranges): Index? {
        val head = ByteBuffer.wrap(r.read(0, 64 * 1024)).order(ByteOrder.LITTLE_ENDIAN)
        if (head.capacity() < 64 || cc(head, 0) != "RIFF" || cc(head, 8) != "AVI ") return null
        // Top level: LIST hdrl, LIST movi, idx1 (walked with small reads, as movi is most of the file).
        var p = 12L; var hdrl: ByteBuffer? = null; var movi = -1L; var idx: ByteBuffer? = null
        while (p + 8 <= r.size && (hdrl == null || movi < 0 || idx == null)) {
            val h = ByteBuffer.wrap(r.read(p, 12)).order(ByteOrder.LITTLE_ENDIAN)
            if (h.capacity() < 12) break
            val id = cc(h, 0); val size = h.getInt(4).toLong() and 0xffffffffL
            when {
                id == "LIST" && cc(h, 8) == "hdrl" -> hdrl = ByteBuffer.wrap(r.read(p + 12, (size - 4).toInt())).order(ByteOrder.LITTLE_ENDIAN)
                id == "LIST" && cc(h, 8) == "movi" -> movi = p + 8
                id == "idx1" -> if (size < 64L * 1024 * 1024) idx = ByteBuffer.wrap(r.read(p + 8, size.toInt())).order(ByteOrder.LITTLE_ENDIAN)
            }
            p += 8 + size + (size and 1)
        }
        val hd = hdrl ?: return null; val ix = idx ?: return null
        if (movi < 0) return null
        // Find the video stream (its number names its chunks: "00dc", "01dc"...).
        var q = 0; var stream = -1; var n = 0; var rate = 0L; var scale = 0L; var track: Track? = null
        var usPerFrame = 0L
        while (q + 8 <= hd.capacity()) {
            val id = cc(hd, q); val size = hd.getInt(q + 4)
            if (id == "avih") usPerFrame = hd.getInt(q + 8).toLong() and 0xffffffffL
            if (id == "LIST" && cc(hd, q + 8) == "strl") {
                var s = q + 12; val end = q + 8 + size; var vids = false
                while (s + 8 <= end) {
                    val sid = cc(hd, s); val ss = hd.getInt(s + 4)
                    if (sid == "strh" && cc(hd, s + 8) == "vids") { vids = true; scale = hd.getInt(s + 28).toLong(); rate = hd.getInt(s + 32).toLong() }
                    if (sid == "strf" && vids && stream < 0) {
                        val w = hd.getInt(s + 12); val h = Math.abs(hd.getInt(s + 16))
                        val mime = Codecs.fourcc(cc(hd, s + 24))
                        val extra = if (ss > 40) ByteArray(ss - 40) { hd.get(s + 48 + it) } else null
                        if (mime != null) track = when (mime) {
                            "video/avc" -> extra?.let { Codecs.avc(it, w, h) } ?: Track(mime, emptyList(), 0, w, h)
                            else -> Track(mime, listOfNotNull(extra), 0, w, h)
                        }
                        stream = n
                    }
                    s += 8 + ss + (ss and 1)
                }
                n++
                q = end + (size and 1)
                continue
            }
            q += 8 + size + (size and 1)
        }
        val t = track ?: return null
        val msPer = if (rate > 0 && scale > 0) scale * 1000.0 / rate else if (usPerFrame > 0) usPerFrame / 1000.0 else return null
        val tag = "%02d".format(stream)
        // idx1 offsets count from the "movi" fourcc, or from the file start in some writers.
        val first = (0 until ix.capacity() / 16).firstOrNull { cc(ix, it * 16).startsWith(tag) }?.let { ix.getInt(it * 16 + 8).toLong() and 0xffffffffL } ?: return null
        val base = if (first >= movi) 0L else movi
        val keys = ArrayList<Key>(); var frame = 0
        for (e in 0 until ix.capacity() / 16) {
            val id = cc(ix, e * 16)
            if (!id.startsWith(tag) || (id[3] != 'c' && id[3] != 'b') || id[2] != 'd') continue
            val flags = ix.getInt(e * 16 + 4); val off = ix.getInt(e * 16 + 8).toLong() and 0xffffffffL; val size = ix.getInt(e * 16 + 12)
            if (flags and 0x10 != 0 && size > 0) keys.add(Key((frame * msPer).toLong(), base + off + 8, size))
            frame++
        }
        if (keys.isEmpty()) return null
        return object : Index(t, keys) { override fun frame(k: Key) = r.read(k.pos, k.size) }
    }
}
