package com.lekathedon.visionlite.probe

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Vision's keyframe buffers (ThumbBuffers.kt) for the VOD probe; stock media3 has no native allocator, so plain heap arrays. */
internal class MemoryShortException(message: String) : Exception(message)

/** Memory for keyframe buffers, outside the Java heap where it can be had. */
internal interface ThumbMemory {
    class Block(val buffer: ByteBuffer, val handle: Any?)

    /** A direct buffer of [size] bytes at position 0, or null when none can be had. */
    fun allocate(size: Int): Block?

    fun free(block: Block)
}

internal object NativeThumbMemory : ThumbMemory {
    override fun allocate(size: Int): ThumbMemory.Block? = runCatching { ThumbMemory.Block(ByteBuffer.allocate(size), null) }.getOrNull()
    override fun free(block: ThumbMemory.Block) = Unit
}

/**
 * One reusable keyframe buffer. It grows to the largest keyframe it has held, at most [ThumbReads.BUFFER_MAX]. When
 * native memory is refused it falls back to a Java array of the size asked for, never above that cap.
 */
internal class ThumbBuffer internal constructor(
    private val memory: ThumbMemory,
    private val pool: Any?,
) {
    private var block: ThumbMemory.Block? = null

    /** Storage indexed from 0; position and limit are not used. */
    var data: ByteBuffer = EMPTY
        private set

    internal var lent = false

    val isNative: Boolean get() = block != null

    /** Room for [size] bytes, keeping the first [keep]. Null above [ThumbReads.BUFFER_MAX]. */
    fun ensure(size: Int, keep: Int = 0): ByteBuffer? {
        if (size > ThumbReads.BUFFER_MAX) return null
        if (data.capacity() >= size) return data
        val cap = roundUp(size)
        val next = memory.allocate(cap)
        val nextData = next?.buffer ?: ByteBuffer.allocate(cap)
        if (keep > 0) {
            val src = data.duplicate()
            src.clear()
            src.limit(minOf(keep, src.capacity()))
            nextData.clear()
            nextData.put(src)
        }
        nextData.clear()
        freeMemory()
        block = next
        data = nextData
        return data
    }

    /** Back to the pool, or freed when the buffer has none. */
    fun release() {
        freeMemory()
    }

    internal fun freeMemory() {
        block?.let { memory.free(it) }
        block = null
        data = EMPTY
    }

    companion object {
        private val EMPTY: ByteBuffer = ByteBuffer.allocate(0)
        private const val GRAIN = 1024 * 1024

        fun roundUp(size: Int): Int = ((size + GRAIN - 1) / GRAIN * GRAIN).coerceIn(GRAIN, ThumbReads.BUFFER_MAX)

        /** A buffer of its own, freed by [release]. */
        fun single(memory: ThumbMemory = NativeThumbMemory): ThumbBuffer = ThumbBuffer(memory, null)
    }
}

/** One access unit for the decoder: the first [length] bytes of [data]. [release] gives its buffer back, once. */
internal class ThumbAu(val data: ByteBuffer, val length: Int, private val buffer: ThumbBuffer? = null) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) buffer?.release()
    }

    fun toByteArray(): ByteArray {
        val out = ByteArray(length)
        val src = data.duplicate()
        src.clear()
        src.limit(length)
        src.get(out)
        return out
    }

    companion object {
        fun of(bytes: ByteArray) = ThumbAu(ByteBuffer.wrap(bytes), bytes.size)
    }
}

/**
 * Keyframe buffers of one session: at most [limit] exist, lent or idle, and a lent one is freed when it comes back
 * after [close]. Thread-safe.
