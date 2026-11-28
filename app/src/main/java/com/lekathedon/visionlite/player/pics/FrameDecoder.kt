package com.lekathedon.visionlite.player.pics

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.pow

/**
 * One keyframe → one small picture, with whatever decoders this device has (asked from the device, not a fixed list):
 * hardware first, software as the fallback, and the one that worked is remembered per device and format.
 * The picture is scaled while it's converted from YUV, so no full-size bitmap is ever made.
 */
internal object FrameDecoder {
    private val prefs by lazy { com.lekathedon.visionlite.App.instance.getSharedPreferences("vl_seekpics", Context.MODE_PRIVATE) }
    private val lists = HashMap<String, List<String>>()

    @Synchronized private fun decoders(mime: String): List<String> = lists.getOrPut(mime) {
        val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && !info.name.endsWith(".secure") && info.supportedTypes.any { it.equals(mime, true) }
        }
        val soft = { i: MediaCodecInfo -> if (Build.VERSION.SDK_INT >= 29) i.isSoftwareOnly else i.name.startsWith("OMX.google.") || i.name.startsWith("c2.android.") }
        val ordered = all.sortedBy { if (soft(it)) 1 else 0 }.map { it.name }
        val good = prefs.getString("ok_$mime", null)
        if (good != null && good in ordered) listOf(good) + (ordered - good) else ordered
    }

    fun canDecode(mime: String) = decoders(mime).isNotEmpty()

    /** Bytes as the decoder takes them: stripped header back, zlib undone, length-prefixed NALs to start codes. */
    fun prepare(t: Track, raw: ByteArray): ByteArray {
        var f = raw
        if (t.zlib) f = runCatching { java.util.zip.InflaterInputStream(f.inputStream()).readBytes() }.getOrDefault(f)
        t.prefix?.let { f = it + f }
        if (t.nalLen <= 0) return f
        val o = ByteArrayOutputStream(f.size + 64); var i = 0
        while (i + t.nalLen <= f.size) {
            var l = 0; repeat(t.nalLen) { l = l shl 8 or (f[i + it].toInt() and 0xff) }; i += t.nalLen
            if (l <= 0 || i + l > f.size) break
            o.write(0); o.write(0); o.write(0); o.write(1); o.write(f, i, l); i += l
        }
        return o.toByteArray()
    }

    /** Parameter sets found in a start-code frame (files without a codec record). */
    private fun paramSets(mime: String, f: ByteArray): List<ByteArray> {
        if (mime == "video/mp4v-es") return Codecs.inBandCsd(mime, f)
        if (mime != "video/avc" && mime != "video/hevc") return emptyList()
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < f.size) { if (f[i].toInt() == 0 && f[i + 1].toInt() == 0 && f[i + 2].toInt() == 1) { starts.add(i + 3); i += 3 } else i++ }
        val sps = ByteArrayOutputStream(); val pps = ByteArrayOutputStream()
        starts.forEachIndexed { k, s ->
            val end = if (k + 1 < starts.size) starts[k + 1] - 3 else f.size
            val type = if (mime == "video/avc") f[s].toInt() and 0x1f else (f[s].toInt() shr 1) and 0x3f
            val dst = when { mime == "video/avc" && type == 7 -> sps; mime == "video/avc" && type == 8 -> pps
                mime == "video/hevc" && type in 32..34 -> sps; else -> null }
            dst?.apply { write(0); write(0); write(0); write(1); write(f, s, end - s) }
        }
        return listOf(sps.toByteArray(), pps.toByteArray()).filter { it.isNotEmpty() }
    }

    fun decode(t: Track, frame: ByteArray, width: Int): Bitmap? {
        val csd = t.csd.ifEmpty { paramSets(t.mime, frame) }
        for (name in decoders(t.mime)) {
            val bmp = runCatching { one(name, t, csd, frame, width) }.getOrNull()
            if (bmp != null) {
                if (prefs.getString("ok_${t.mime}", null) != name) prefs.edit().putString("ok_${t.mime}", name).apply()
                synchronized(this) { lists.remove(t.mime) }
                return bmp
            }
        }
        return null
    }

    private fun one(name: String, t: Track, csd: List<ByteArray>, frame: ByteArray, width: Int): Bitmap? {
        val mc = MediaCodec.createByCodecName(name)
        try {
            val f = MediaFormat.createVideoFormat(t.mime, t.width.coerceAtLeast(16), t.height.coerceAtLeast(16))
            csd.forEachIndexed { i, b -> f.setByteBuffer("csd-$i", ByteBuffer.wrap(b)) }
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frame.size + 4096)
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            mc.configure(f, null, null, 0); mc.start()
            val until = System.currentTimeMillis() + 4_000
            var fed = false; var ended = false
            val info = MediaCodec.BufferInfo()
            while (System.currentTimeMillis() < until) {
                if (!ended) {
                    val i = mc.dequeueInputBuffer(20_000)
                    if (i >= 0) {
                        if (!fed) { mc.getInputBuffer(i)!!.apply { clear(); put(frame) }; mc.queueInputBuffer(i, 0, frame.size, 0, 0); fed = true }
                        else { mc.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); ended = true }
                    }
                }
                val o = mc.dequeueOutputBuffer(info, 20_000)
                if (o >= 0) {
                    var bmp: Bitmap? = null
                    if (info.size > 0) mc.getOutputImage(o)?.use { bmp = small(it, mc.outputFormat, width) }
                    mc.releaseOutputBuffer(o, false)
                    if (bmp != null) return bmp
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return null
                }
            }
            return null
        } finally { runCatching { mc.stop() }; mc.release() }
    }

    // PQ (HDR10 / Dolby Vision base) code value → SDR: SMPTE ST 2084 to light, a soft roll-off, then display gamma.
    private val pqLut: IntArray by lazy {
        IntArray(256) { c ->
            val n = (c / 255.0).pow(1 / 78.84375)
            val nits = 10_000 * (maxOf(n - 0.8359375, 0.0) / (18.8515625 - 18.6875 * n)).pow(1 / 0.1593017578125)
            val x = nits / 203.0
            val y = x * (1 + x / 16) / (1 + x)
            (255 * y.coerceIn(0.0, 1.0).pow(1 / 2.2)).toInt().coerceIn(0, 255)
        }
    }

    private fun small(im: android.media.Image, fmt: MediaFormat, tw: Int): Bitmap? {
        val crop = im.cropRect; val w = crop.width(); val h = crop.height()
        if (w <= 0 || h <= 0 || im.planes.size < 3) return null
        val th = (tw.toLong() * h / w).toInt().coerceAtLeast(2)
        val p = im.planes
        val Y = p[0].buffer; val U = p[1].buffer; val V = p[2].buffer
        val ys = p[0].rowStride; val yp = p[0].pixelStride; val cs = p[1].rowStride; val cp = p[1].pixelStride
        val wide = yp == 2 || (Build.VERSION.SDK_INT >= 29 && runCatching { fmt.getInteger(MediaFormat.KEY_COLOR_FORMAT) }.getOrNull() == 54)
        val transfer = runCatching { fmt.getInteger(MediaFormat.KEY_COLOR_TRANSFER) }.getOrDefault(0)
        val bt2020 = runCatching { fmt.getInteger(MediaFormat.KEY_COLOR_STANDARD) }.getOrDefault(0) == MediaFormat.COLOR_STANDARD_BT2020 || wide
        val pq = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || (wide && transfer != MediaFormat.COLOR_TRANSFER_HLG)
        val step = if (wide) 2 else 1
        fun at(b: ByteBuffer, i: Int) = (if (step == 2) b.get(i + 1) else b.get(i)).toInt() and 0xff
        val (rv, gu, gv, bu) = if (bt2020) listOf(1.6787, 0.1873, 0.6504, 2.1418) else listOf(1.7927, 0.2132, 0.5329, 2.1124)
        val px = IntArray(tw * th)
        for (y in 0 until th) {
            val sy = crop.top + y * h / th
            for (x in 0 until tw) {
                val sx = crop.left + x * w / tw
                val yy = 1.1644 * (at(Y, sy * ys + sx * yp) - 16)
                val ci = (sy / 2) * cs + (sx / 2) * cp
                val u = at(U, ci) - 128.0; val v = at(V, ci) - 128.0
                var r = (yy + rv * v).toInt().coerceIn(0, 255)
                var g = (yy - gu * u - gv * v).toInt().coerceIn(0, 255)
                var b = (yy + bu * u).toInt().coerceIn(0, 255)
                if (pq) { r = pqLut[r]; g = pqLut[g]; b = pqLut[b] }
                px[y * tw + x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return trimBars(px, tw, th)
    }

    /** Black bars above and below (scope films) cut off, the same amount both sides. */
    private fun trimBars(px: IntArray, w: Int, h: Int): Bitmap {
        fun dark(row: Int): Boolean {
            var max = 0
            for (x in 0 until w step 4) { val c = px[row * w + x]; max = maxOf(max, (c shr 16 and 0xff) + (c shr 8 and 0xff) + (c and 0xff)) }
            return max < 3 * 28
        }
        var top = 0; while (top < h / 4 && dark(top)) top++
        var bottom = 0; while (bottom < h / 4 && dark(h - 1 - bottom)) bottom++
        val cut = minOf(top, bottom).let { if (it * 25 < h) 0 else it }
        val out = Bitmap.createBitmap(px, cut * w, w, w, h - 2 * cut, Bitmap.Config.ARGB_8888)
        return out
    }
}
