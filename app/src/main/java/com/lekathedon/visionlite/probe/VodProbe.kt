package com.lekathedon.visionlite.probe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One sound ('a') or subtitle ('s') track as the container names it. */
data class ProbeTrack(val kind: Char, val codec: String, val lang: String?, val channels: Int, val name: String?, val forced: Boolean)

/**
 * The real file behind a VOD link (Play with VOD, user 2026-11-26): picture size, Dolby Vision / HDR (Vision's
 * DvProfileProbe), sound and subtitle tracks. Bounded Range reads only (head, Tracks / moov, one keyframe for HEVC);
 * at most 2 at once (panels count connections), 20 s each; results kept per URL for the run (200 max). Nothing runs until the list is opened.
 */
object VodProbe {
    data class Result(val width: Int, val height: Int, val hdr: String, val tracks: List<ProbeTrack>)

    private val gate = Semaphore(2)
    private val cache = object : LinkedHashMap<String, Result?>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Result?>) = size > 200
    }

    fun cached(url: String): Result? = synchronized(cache) { cache[url] }

    /** Null when the file can't be read (no Range, not MKV/MP4, timeout); a failure is kept too, so it is asked once. */
    suspend fun probe(url: String, headers: Map<String, String> = emptyMap()): Result? {
        synchronized(cache) { if (cache.containsKey(url)) return cache[url] }
        val r = gate.withPermit {
            withTimeoutOrNull(20_000) { withContext(Dispatchers.IO) { runCatching { DvProfileProbe.full(url, headers) }.getOrNull() } }
        }
        synchronized(cache) { cache[url] = r }
        return r
    }
}
