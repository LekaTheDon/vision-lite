package com.lekathedon.visionlite.probe

import java.nio.ByteBuffer

internal object ThumbReads {
    /**
     * Largest single range read, and largest keyframe taken. 4K remux keyframes are about 0.5-5 MB (HEVC at
     * 80-100 Mb/s), so this leaves room for the heaviest ones; a bigger keyframe is skipped, never read whole.
     */
    const val MAX_READ = 8 * 1024 * 1024
    /** Parameter sets and block headers in front of a keyframe in the same buffer. */
    const val MAX_PREFIX = 64 * 1024
    const val BUFFER_MAX = MAX_READ + MAX_PREFIX
    /** Index elements (Cues, moov, OpenDML indexes) are read in steps of at most this size. */
    const val INDEX_STEP = 4 * 1024 * 1024

    /** [length] bytes at [offset] through reads of at most [step] bytes; shorter only at end of file. */
    fun readStepped(reader: RangeReader, offset: Long, length: Int, step: Int = INDEX_STEP): ByteArray =
        read(reader, offset, length, step, exact = false)

    /** As [readStepped], but a short read throws instead of copying what arrived. */
    fun readExact(reader: RangeReader, offset: Long, length: Int, step: Int = INDEX_STEP): ByteArray =
        read(reader, offset, length, step, exact = true)

    private fun read(reader: RangeReader, offset: Long, length: Int, step: Int, exact: Boolean): ByteArray {
        val out = ByteArray(length)
        val dst = ByteBuffer.wrap(out)
        var n = 0
        while (n < length) {
            val want = minOf(step, length - n)
            val got = reader.readInto(offset + n, want, dst)
            if (got <= 0) break
            n += got
            if (got < want) break
        }
        if (n == length) return out
        if (exact) throw UnsupportedMediaException("truncated read: $n of $length bytes")
        return out.copyOf(n)
    }
}


/** Lite has no hardware thumbnails: the readers' VP9 branch stays off. */
internal object HardwareThumbSwitch { const val on = false }
