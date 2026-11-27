package com.lekathedon.visionlite.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One HTTP client and one JSON reader for the whole app. */
object Net {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // Settings › Speed and network › DNS (the box's own unless a DNS-over-HTTPS service is picked).
        .dns(SwitchDns)
        // Our server's key on requests to it (none elsewhere).
        .addInterceptor(Our.Key)
        .build()

    suspend fun get(url: String, timeoutSec: Long = 0): String = withContext(Dispatchers.IO) {
        val client = if (timeoutSec > 0) http.newBuilder().callTimeout(timeoutSec, TimeUnit.SECONDS).build() else http
        client.newCall(Request.Builder().url(url).header("User-Agent", "VisionLite").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string() ?: throw IOException("empty body")
        }
    }

    suspend inline fun <reified T> getJson(url: String, timeoutSec: Long = 0): T = json.decodeFromString(get(url, timeoutSec))
}
