package com.lekathedon.visionlite.player.pics

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.mutableIntStateOf
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.device.Tier
import java.io.File
import java.security.MessageDigest

/**
 * Seek thumbnails (own design, spec/SEEK-THUMBS.md). One session per playing link:
 * the file's own index → one keyframe read per picture → the device's decoder → a small JPEG (memory + disk).
 * Pictures near where the user is seeking come first; the rest fill in evenly, and only while playback's buffer is
 * healthy. Anything that can't be read or decoded just means no pictures; the seek bar works as before.
 */
object SeekPics {
    private const val TAG = "SeekPics"
    /** Bumped when a picture arrives (the seek pane redraws on it). */
    val tick = mutableIntStateOf(0)
    /** Playback's read-ahead (ms), set by the player once a second; pictures wait while it's low. */
    @Volatile var bufferAheadMs = Long.MAX_VALUE

    @Volatile private var s: Session? = null
    private val main = Handler(Looper.getMainLooper())

    class Pic(val bitmap: Bitmap, val atMs: Long)

    /** Starts pictures for this link. [blocked] = true while they must stay off (DV7 on the FEL path). */
    fun start(url: String, headers: Map<String, String>, id: String, hdOnly: Boolean, blocked: () -> Boolean) {
        stop()
        bufferAheadMs = Long.MAX_VALUE
        s = Session(url, headers, id, hdOnly, blocked).also { it.begin() }
    }

    fun stop() { s?.cancel(); s = null }

    /** The picture for a seek target: its own slot, else the nearest finished one close by. */
    fun pictureFor(ms: Long): Pic? = s?.near(ms)
    /** Only the slot's own picture (strip tiles). */
    fun exactFor(ms: Long): Pic? = s?.exact(ms)
    /** Distance between strip tiles once most of the film has pictures, null before. */
    fun stripSpacingMs(): Long? = s?.strip()
    fun aspect(): Float? = s?.aspect
    /** Where a seek to [ms] should land: the keyframe its picture shows, so picture and video match. */
    fun landing(ms: Long): Long? = s?.landing(ms)
    /** The user is seeking around [ms]: pictures there first. */
    fun focus(ms: Long) { s?.focus(ms) }

    private fun bump() = main.post { tick.intValue++ }

    private class Session(val url: String, val headers: Map<String, String>, val id: String, val hdOnly: Boolean, val blocked: () -> Boolean) {
        @Volatile var alive = true
        @Volatile var aspect: Float? = null
        @Volatile var spacing = 0L
        @Volatile var slotMs = LongArray(0)          // keyframe time each slot shows
        val jpeg = java.util.concurrent.ConcurrentHashMap<Int, ByteArray>()
        val shown = LruCache<Int, Bitmap>(if (Tier.current == Tier.Class.LIGHT) 12 else 24)
        @Volatile var focusAt = -1L; @Volatile var focusSince = 0L
        var thread: Thread? = null
        val width = if (Tier.current == Tier.Class.LIGHT) 240 else 320

        fun begin() {
            thread = Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); runCatching { work() }.onFailure { Log.i(TAG, "stopped: $it") } }, "seekpics").apply { isDaemon = true; start() }
        }
        fun cancel() { alive = false; thread?.interrupt() }

        fun slotOf(ms: Long) = if (spacing <= 0) -1 else ((ms + spacing / 2) / spacing).toInt().coerceIn(0, slotMs.size - 1)
        fun pic(slot: Int): Pic? {
            if (slot < 0) return null
            shown.get(slot)?.let { return Pic(it, slotMs[slot]) }
            val b = jpeg[slot] ?: return null
            val bmp = BitmapFactory.decodeByteArray(b, 0, b.size) ?: return null
            shown.put(slot, bmp); return Pic(bmp, slotMs[slot])
        }
        fun exact(ms: Long): Pic? = pic(slotOf(ms))
        fun near(ms: Long): Pic? {
            val c = slotOf(ms); if (c < 0) return null
            for (d in 0..3) { pic(c - d)?.let { return it }; if (d > 0) pic(c + d)?.let { return it } }
            return null
        }
        fun strip(): Long? = spacing.takeIf { it > 0 && slotMs.isNotEmpty() && jpeg.size * 10 >= slotMs.size * 6 }
        fun landing(ms: Long): Long? { val c = slotOf(ms); return if (c >= 0 && (jpeg[c]?.size ?: 0) > 0) slotMs[c] else null }
        fun focus(ms: Long) { focusAt = ms; focusSince = System.currentTimeMillis(); thread?.interrupt() }

        private fun work() {
            val r = Ranges(url, headers); r.open()
            val t0 = System.currentTimeMillis()
            val idx = Mkv.open(r) ?: Mp4.open(r) ?: Avi.open(r) ?: run { Log.i(TAG, "no index we can read"); return }
            val tr = idx.track
            if (hdOnly && tr.width.toLong() * tr.height > 1920L * 1088) { Log.i(TAG, "HD only: ${tr.width}x${tr.height} skipped"); return }
            if (tr.dvProfile == 5) { Log.i(TAG, "Dolby Vision 5: no pictures yet"); return }
            if (!FrameDecoder.canDecode(tr.mime)) { Log.i(TAG, "no decoder for ${tr.mime}"); return }
            // Slots: about one picture per 10 s, at most 300; each shows the keyframe at or before its time.
            val dur = idx.keys.last().ms
            spacing = maxOf(10_000L, (dur / 300 / 1000 + 1) * 1000)
            val n = (dur / spacing + 1).toInt()
            val keyOf = IntArray(n) { slot -> idx.keys.binarySearchMs(slot * spacing) }
            slotMs = LongArray(n) { idx.keys[keyOf[it]].ms }
            Log.i(TAG, "index ${tr.mime} ${tr.width}x${tr.height}: ${idx.keys.size} keyframes, $n slots, ${System.currentTimeMillis() - t0} ms, ${r.fetched / 1024} KB")
            val dir = folder(r.size); loadDisk(dir, n)
            val order = spread(n)
            var fails = 0; var made = 0
            val byKey = HashMap<Int, ByteArray>()   // slots that share a keyframe share its picture
            while (alive) {
                val slot = next(order) ?: break
                if (blocked()) { nap(2_000); continue }
                val seeking = System.currentTimeMillis() - focusSince < 8_000
                if (!seeking && bufferAheadMs < 20_000) { nap(1_000); continue }
                byKey[keyOf[slot]]?.let { jpeg[slot] = it; continue }
                val k = idx.keys[keyOf[slot]]
                val got = runCatching { idx.frame(k)?.let { FrameDecoder.decode(tr, FrameDecoder.prepare(tr, it), width) } }.getOrNull()
                if (got == null) { jpeg.putIfAbsent(slot, EMPTY); if (++fails >= 6 && made == 0) { Log.i(TAG, "no pictures: decoding failed"); return }; continue }
                if (aspect == null) aspect = got.width.toFloat() / got.height
                val out = java.io.ByteArrayOutputStream(); got.compress(Bitmap.CompressFormat.JPEG, 78, out)
                val bytes = out.toByteArray(); jpeg[slot] = bytes; byKey[keyOf[slot]] = bytes; shown.put(slot, got); made++
                runCatching { File(dir, "$slot.jpg").writeBytes(bytes) }
                bump()
            }
            Log.i(TAG, "done: $made new pictures, ${r.fetched / 1024} KB read")
        }

        /** Next slot: around the seek point while seeking, else the next one in the spread order. */
        private fun next(order: IntArray): Int? {
            val c = if (focusAt >= 0) slotOf(focusAt) else -1
            if (c >= 0 && System.currentTimeMillis() - focusSince < 8_000)
                for (d in 0..4) { for (x in intArrayOf(c + d, c - d)) if (x in slotMs.indices && !jpeg.containsKey(x)) return x }
            return order.firstOrNull { !jpeg.containsKey(it) }
        }

        private fun nap(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) { }

        private fun folder(size: Long): File {
            val key = MessageDigest.getInstance("SHA-1").digest("$id|$size".toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
            val root = File(App.instance.cacheDir, "seekpics"); trim(root)
            return File(root, key).apply { mkdirs(); setLastModified(System.currentTimeMillis()) }
        }
        private fun loadDisk(dir: File, n: Int) {
            dir.listFiles()?.forEach { f -> f.nameWithoutExtension.toIntOrNull()?.takeIf { it < n }?.let { jpeg[it] = f.readBytes() } }
            if (jpeg.isNotEmpty()) { jpeg.values.firstOrNull()?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size)?.let { aspect = it.width.toFloat() / it.height } }; bump() }
        }
    }

    private val EMPTY = ByteArray(0)

    /** Disk budget: newest titles kept, oldest folders removed past 60 MB (25 MB on Light boxes). */
    private fun trim(root: File) {
        val budget = if (Tier.current == Tier.Class.LIGHT) 25L shl 20 else 60L shl 20
        val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() } ?: return
        var used = 0L
        for (d in dirs) { used += d.listFiles()?.sumOf { it.length() } ?: 0; if (used > budget) d.deleteRecursively() }
    }

    /** 0, n/2, n/4, 3n/4, ... so the whole film gets coarse coverage first, then finer. */
    private fun spread(n: Int): IntArray {
        val seen = BooleanArray(n); val out = ArrayList<Int>(n)
        var step = Integer.highestOneBit(maxOf(1, n))
        while (step >= 1) { var i = 0; while (i < n) { if (!seen[i]) { seen[i] = true; out.add(i) }; i += step }; step /= 2 }
        return out.toIntArray()
    }

    /** Index of the last keyframe at or before [ms] (the first one if none). */
    private fun List<Key>.binarySearchMs(ms: Long): Int {
        var lo = 0; var hi = size - 1; var best = 0
        while (lo <= hi) { val m = (lo + hi) / 2; if (this[m].ms <= ms) { best = m; lo = m + 1 } else hi = m - 1 }
        return best
    }
}
