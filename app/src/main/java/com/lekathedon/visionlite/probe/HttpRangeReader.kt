package com.lekathedon.visionlite.probe

import com.lekathedon.visionlite.data.Net
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Bounded Range reads for the VOD probe over the app's client (5 s connect/read, so a slow panel never holds the
 * list). A 200 (Range ignored) is refused without reading the body; never logs URLs.
 */
internal class HttpRangeReader(private val url: String, private val headers: Map<String, String>) {
    companion object {
        /** Largest single read (and largest keyframe taken: 4K remux keyframes are about 0.5-5 MB). */
        const val MAX_READ = 8 * 1024 * 1024
        val client: OkHttpClient by lazy {
            Net.http.newBuilder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
        }
    }

    @Volatile var totalLength: Long = -1L
        private set
    @Volatile private var target = url
    @Volatile private var closed = false
    private val calls = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<okhttp3.Call, Boolean>())

    fun close() { closed = true; calls.forEach { it.cancel() } }

    fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length > 0)
        if (closed) throw IOException("closed")
        val n = minOf(length, MAX_READ)
        val b = Request.Builder().url(target)
        for ((k, v) in headers) if (!k.equals("Range", true)) b.header(k, v)
        b.header("User-Agent", headers["User-Agent"] ?: "VisionLite")
            .header("Range", "bytes=$offset-${offset + n - 1}").header("Accept-Encoding", "identity")
        val call = client.newCall(b.build())
        calls += call
        try {
            call.execute().use { r ->
                when (r.code) {
                    206 -> Unit
                    416 -> return ByteArray(0)
                    200 -> throw ProbeException("server ignored Range")
                    else -> throw IOException("HTTP ${r.code}")
                }
                r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.let { totalLength = it }
                r.request.url.toString().let { if (it != target) target = it }
                val src = r.body?.source() ?: throw IOException("empty body")
                val out = ByteArray(n)
                var got = 0
                while (got < n) { val k = src.read(out, got, n - got); if (k < 0) break; got += k }
                return if (got == n) out else out.copyOf(got)
            }
        } finally { calls -= call }
    }
}
