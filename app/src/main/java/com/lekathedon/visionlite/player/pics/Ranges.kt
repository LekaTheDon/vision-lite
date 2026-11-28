package com.lekathedon.visionlite.player.pics

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Byte ranges of one remote file: the final address (after redirects) is found once, then every read is one request. */
internal class Ranges(private var url: String, private val headers: Map<String, String>) {
    var size = -1L; private set
    var fetched = 0L; private set

    fun open() {
        val c = conn(url, 0, 0)
        try {
            val code = c.responseCode
            url = c.url.toString()
            size = c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1
            if (code != 206 || size <= 0) throw IOException("no byte ranges ($code)")
        } finally { c.disconnect() }
    }

    fun read(pos: Long, want: Int): ByteArray {
        val len = minOf(want.toLong(), size - pos).toInt()
        if (len <= 0 || pos < 0) return ByteArray(0)
        val c = conn(url, pos, pos + len - 1)
        try {
            if (c.responseCode != 206) throw IOException("range ${c.responseCode}")
            val b = ByteArray(len); var o = 0
            c.inputStream.use { s -> while (o < len) { val n = s.read(b, o, len - o); if (n < 0) break; o += n } }
            fetched += o
            return if (o == len) b else b.copyOf(o)
        } finally { c.disconnect() }
    }

    private fun conn(u: String, from: Long, to: Long) = (URL(u).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000; readTimeout = 15_000; instanceFollowRedirects = true
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
        setRequestProperty("Range", "bytes=$from-$to")
    }
}
