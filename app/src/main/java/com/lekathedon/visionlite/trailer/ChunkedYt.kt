package com.lekathedon.visionlite.trailer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * YouTube's video server (googlevideo "videoplayback") throttles one open-ended request to a trickle (measured on the
 * AM9 2026-11-24: ~0.29 MB/s, and it can stall: Dexter: Resurrection S2 trailer froze at the same spot ~30 s every
 * time), while a request of a byte range is served at full speed (10 MB in 0.2 s). YouTube's own players ask in
 * pieces, so this does too: every googlevideo request is split into 10 MB ranges, opened one after another as the
 * player reads. Anything else passes straight through.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ChunkedYt(private val upstream: DataSource) : DataSource {
    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = ChunkedYt(upstream.createDataSource())
    }

    private var spec: DataSpec? = null
    private var chunked = false
    private var pos = 0L          // absolute position of the next byte
    private var end = C.LENGTH_UNSET.toLong()   // absolute end (exclusive), unset = unknown
    private var chunkLeft = 0L
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        chunked = dataSpec.uri.host.orEmpty().endsWith("googlevideo.com") && dataSpec.uri.path.orEmpty().contains("videoplayback")
        if (!chunked) { opened = true; return upstream.open(dataSpec) }
        val total = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()
        pos = dataSpec.position
        end = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> pos + dataSpec.length
            total != null -> total
            else -> C.LENGTH_UNSET.toLong()
        }
        openChunk()
        return if (end == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else end - pos
    }

    private fun openChunk(): Boolean {
        if (end != C.LENGTH_UNSET.toLong() && pos >= end) return false
        val len = if (end == C.LENGTH_UNSET.toLong()) CHUNK else minOf(CHUNK, end - pos)
        val s = spec!!.buildUpon().setPosition(pos).setLength(len).build()
        val got = try { upstream.open(s) } catch (e: androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode == 416) return false else throw e   // past the end of a file of unknown size
        }
        opened = true
        chunkLeft = if (got == C.LENGTH_UNSET.toLong()) len else got
        return true
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!chunked) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        while (true) {
            val n = upstream.read(buffer, offset, length)
            if (n != C.RESULT_END_OF_INPUT) { pos += n; chunkLeft -= n; return n }
            // This piece is done: the next one, unless the file is.
            val short = chunkLeft > 0
            upstream.close(); opened = false
            if (short && end == C.LENGTH_UNSET.toLong()) return C.RESULT_END_OF_INPUT   // the server ended the file early
            if (!openChunk()) return C.RESULT_END_OF_INPUT
        }
    }

    override fun getUri(): Uri? = upstream.uri ?: spec?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() { if (opened) { opened = false; upstream.close() } }

    private companion object { const val CHUNK = 10L * 1024 * 1024 }
}
