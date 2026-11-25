package com.nuvio.tv.ui.screens.player

import com.lekathedon.visionlite.data.Net
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okio.ByteString.Companion.decodeBase64

/*
 * Lite's stand-ins for the Nuvio Arc player parts its seek-thumbnail engine (com.nuvio.tv.core.player.thumbnail,
 * ported as is in 0.39.0) reads. Lite has no parallel-range playback source, so its 429 clamp never latches.
 */

internal object ParallelRangeDataSource {
    @Volatile var hudClampLatched: Boolean = false
    @Volatile var hudClampLastHitAtMs: Long = 0L
}

internal object PlayerPlaybackNetworking {
    /** Lite's shared client, with the stream's headers put back on every request (also after a redirect). */
    fun createHttpClient(defaultHeaders: Map<String, String> = emptyMap(), useLongReadTimeout: Boolean = false,
                         slowStart: Boolean = false): OkHttpClient {
        val b = Net.http.newBuilder()
        val h = defaultHeaders.filterKeys { !it.equals("Range", true) }
        if (h.isNotEmpty()) b.addNetworkInterceptor { c -> c.proceed(c.request().newBuilder().apply { h.forEach { (k, v) -> header(k, v) } }.build()) }
        return b.build()
    }
}

/** Arc's slow-start server rules: a home-network host, or an AIOStreams Usenet link. */
internal object SlowStartServer {
    const val FIRST_DATA_WAIT_MS = 60_000L
    const val LOOKUP_WAIT_CAP_MS = 75_000L

    fun isUsenet(url: String?): Boolean {
        val u = url?.trim()?.toHttpUrlOrNull() ?: return false
        if ("/usenet/" in u.encodedPath.lowercase()) return true
        return u.pathSegments.any { it.startsWith("eyJ") && decodedJson(it)?.let(usenetType::containsMatchIn) == true }
    }

    private val usenetType = Regex("\"type\"\\s*:\\s*\"usenet\"", RegexOption.IGNORE_CASE)

    private fun decodedJson(segment: String): String? {
        val s = segment.trimEnd('=')
        return s.dropLast(if (s.length % 4 == 1) 1 else 0).decodeBase64()?.utf8()
    }

    private val homeSuffixes = listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".ts.net")

    fun isLocalHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (homeSuffixes.any { h.endsWith(it) }) return true
        val v4 = h.split('.').takeIf { it.size == 4 }?.map { it.toIntOrNull() ?: return false }
        if (v4 != null) {
            if (v4.any { it !in 0..255 }) return false
            return v4[0] == 10 || (v4[0] == 172 && v4[1] in 16..31) || (v4[0] == 192 && v4[1] == 168) ||
                (v4[0] == 169 && v4[1] == 254) || (v4[0] == 100 && v4[1] in 64..127)
        }
        if (':' in h) {
            val first = h.trim('[', ']').substringBefore(':')
            return first.length == 4 && (first.startsWith("fc") || first.startsWith("fd") || (first.startsWith("fe") && first[2] in '8'..'b'))
        }
        return false
    }
}
