package com.lekathedon.visionlite.player

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Seek thumbnails, the light way (Vision has a 17k-line engine; Lite asks Android's own retriever): only while you
 * seek, one frame at the key frame nearest the spot, 320 px wide, kept per 10 s step (last 40 frames, ~10 MB at most).
 * The retriever opens the link on the first seek and is closed when the player goes. Nothing runs while you watch.
 * Not for live TV. Skipped on boxes under 3 GB of memory.
 */
class SeekThumbs(private val url: String, private val headers: Map<String, String>) {
    private var mmr: MediaMetadataRetriever? = null
    private var failed = false
    private val lock = Mutex()
    private val cache = object : LinkedHashMap<Long, Bitmap>(48, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, Bitmap>?) = size > 40
    }

    /** The frame near [ms] (null when the link can't give one); call from a coroutine, it works off the main thread. */
    suspend fun at(ms: Long): Bitmap? = withContext(Dispatchers.IO) {
        val key = ms / 10_000
        synchronized(cache) { cache[key] }?.let { return@withContext it }
        if (failed) return@withContext null
        lock.withLock {
            synchronized(cache) { cache[key] }?.let { return@withLock it }
            val r = mmr ?: runCatching { MediaMetadataRetriever().apply { setDataSource(url, headers) } }.getOrElse { failed = true; null }?.also { mmr = it }
                ?: return@withLock null
            val bmp = runCatching { r.getScaledFrameAtTime(key * 10_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180) }.getOrNull()
            if (bmp != null) synchronized(cache) { cache[key] = bmp }
            bmp
        }
    }

    fun close() { runCatching { mmr?.release() }; mmr = null; synchronized(cache) { cache.clear() } }

    companion object {
        fun allowed(context: android.content.Context): Boolean {
            val mi = android.app.ActivityManager.MemoryInfo()
            (context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
            return mi.totalMem >= 2_800L * 1024 * 1024
        }
    }
}
