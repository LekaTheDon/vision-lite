package com.lekathedon.visionlite.tb

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Hashes Torrentio returned when asked for an exact episode by IMDb id. Its index maps each file to an episode, so this
 * tells real episodes from releases that merely use the same numbers. Memory only, last [MAX] hashes.
 */
object EpisodeVouches {
    private const val MAX = 3000
    private const val MAX_NUMBERING = 200
    private const val MIN_SAME_NUMBERS = 3
    private val seen = object : LinkedHashMap<String, Unit>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > MAX
    }

    private fun key(season: Int, episode: Int, hash: String) = "$season:$episode:${hash.lowercase()}"

    /** Torrentio answered for this episode of this show. */
    data class Change(val showKey: String, val season: Int, val episode: Int)

    private val _changes = MutableSharedFlow<Change>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** A list already ranked may now rank differently, with no new link to show for it. */
    val changes: SharedFlow<Change> = _changes.asSharedFlow()

    fun record(season: Int, episode: Int, hashes: Collection<String>) = synchronized(seen) {
        hashes.forEach { seen[key(season, episode, it)] = Unit }
    }

    fun has(season: Int, episode: Int, hash: String?): Boolean =
        hash != null && synchronized(seen) { seen.containsKey(key(season, episode, hash)) }

    /** The files Torrentio listed for one episode, by how their names read: this episode's numbers, or others. */
    private class Numbering(val same: HashSet<String> = HashSet(), val other: HashSet<String> = HashSet())

    private val numbering = object : LinkedHashMap<String, Numbering>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Numbering>?) = size > MAX_NUMBERING
    }

    /** Sets, not counts: Torrentio's main and regional answers, or asking again, list the same files. */
    fun recordNumbering(showKey: String, season: Int, episode: Int, same: Collection<String>, other: Collection<String>) {
        synchronized(numbering) {
            val entry = numbering.getOrPut("$showKey:$season:$episode") { Numbering() }
            same.mapTo(entry.same) { it.lowercase() }
            other.mapTo(entry.other) { it.lowercase() }
        }
        _changes.tryEmit(Change(showKey, season, episode))
    }

    /**
     * Files that really are this episode use the numbers asked for, with none naming another: the show has one
     * numbering. Firefly, Doctor Who and Money Heist have rows that disagree here; 25 of 25 ordinary episodes had none.
     */
    fun numberingAgrees(showKey: String, season: Int, episode: Int): Boolean = synchronized(numbering) {
        numbering["$showKey:$season:$episode"]?.let { it.same.size >= MIN_SAME_NUMBERS && it.other.isEmpty() } == true
    }

    internal fun clear() {
        synchronized(seen) { seen.clear() }
        synchronized(numbering) { numbering.clear() }
    }
}
