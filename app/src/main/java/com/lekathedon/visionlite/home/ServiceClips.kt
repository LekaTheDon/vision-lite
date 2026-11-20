package com.lekathedon.visionlite.home

import android.net.Uri
import android.util.Log
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.youtube.Yt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Vision's service intro clips (ServiceIntroClips): resting on a Streaming services tile plays that service's own
 * logo animation from YouTube, same clips as Vision (picked by hand 2026-11-04). Sound follows the trailer sound
 * setting; trailers off = silent. Lite fetches the links with its own YouTube client (Yt, VR player = no deciphering).
 *
 * Timing as Vision: the first time the row is drawn, every clip is fetched once, one after another, and kept on the
 * box; landing on a tile then starts its clip after [START_AFTER_MS] (only so a held D-pad doesn't start a player on
 * every tile it passes). Cost: ~1-3 MB per clip at 1080p (~20-40 MB on disk for all 18, filesDir), downloaded once;
 * after that no YouTube call at all. APK cost: none (code only).
 */
object ServiceClips {
    const val START_AFTER_MS = 150L
    private const val MAX_H = 1080
    private const val MAX_BYTES = 30L * 1024 * 1024
    private const val RANGE = 4L * 1024 * 1024

    /** Service id → YouTube video id (Vision's list). */
    private val CLIPS = mapOf(
        "netflix" to "GV3HUDMQ-F8", "prime" to "mv7fFdlNcs4", "disney" to "EWxFJlPN8Iw", "max" to "PZKqFqXCRyM",
        "apple" to "6sX_BfDnyrg", "hulu" to "wgcvJG9K4r0", "paramount" to "qNPQqqQLnjg", "peacock" to "lTzvdIz0HIw",
        "crunchyroll" to "gvkvMo9W2-M", "shudder" to "QARljDGgO4g", "criterion" to "XRPBHT77_84", "starz" to "Ng1_uKSFi5U",
        "amcplus" to "T7D_RnhHKW8", "mgmplus" to "curutqISN9Y", "britbox" to "L5WN7f72Fvs", "discovery" to "_XiqZIHG_2o",
        "itvx" to "moV-lWVQT5I", "now" to "6dz5Wy9rHsQ",
        // Anime hub's services (picked 2026-11-20): "Hidive Logo" ident; Cineverse's official RetroCrush spot;
        // d Anime Store's official 15 s ad "アニメとススメ！" (it has no logo ident of its own).
        "hidive" to "eercartSBPc", "retrocrush" to "5jE-Me90xwI", "danime" to "1h-po7_jFvE"
    )
    /** Logos that are dark on clear on TMDB: drawn white (as Vision). */
    val LIGHT_LOGO = setOf("apple", "peacock", "hidive", "danime")

    data class Clip(val video: String, val audio: String?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var preloadStarted = false
    private val dir get() = File(App.instance.filesDir, "service_clips").apply { mkdirs() }

    fun has(serviceId: String) = CLIPS.containsKey(serviceId)

    /** Once per app run: every clip not yet on the box is fetched, one at a time. */
    fun preloadAll() {
        if (preloadStarted) return
        preloadStarted = true
        scope.launch {
            for (vid in CLIPS.values) {
                if (local(vid) != null) continue
                runCatching { save(vid, Yt.streams(vid, MAX_H)) }.onFailure { Log.w("ServiceClips", "$vid: ${it.message}") }
            }
        }
    }

    /** The saved copy, else YouTube's links (saved by the preload). */
    suspend fun clipFor(serviceId: String): Clip? = withContext(Dispatchers.IO) {
        val vid = CLIPS[serviceId] ?: return@withContext null
        local(vid) ?: runCatching { Yt.streams(vid, MAX_H) }.getOrNull()?.let { s -> s.video?.let { Clip(it, s.audio) } }
    }

    private fun local(vid: String): Clip? {
        val v = File(dir, "$vid.v").takeIf { it.isFile && it.length() > 0 } ?: return null
        val a = File(dir, "$vid.a").takeIf { it.isFile && it.length() > 0 }
        return Clip(Uri.fromFile(v).toString(), a?.let { Uri.fromFile(it).toString() })
    }

    private fun save(vid: String, s: com.lekathedon.visionlite.youtube.YtStreams) {
        val video = s.video ?: return
        // Audio first: the video file is what marks a clip as saved.
        s.audio?.let { download(it, File(dir, "$vid.a")) }
        download(video, File(dir, "$vid.v"))
    }

    /** In 4 MB ranges (googlevideo throttles unranged reads), to a .part file renamed when complete. */
    private fun download(url: String, target: File) {
        val tmp = File(target.parentFile, target.name + ".part")
        tmp.delete()
        var total = -1L
        var done = 0L
        tmp.outputStream().use { out ->
            while (total < 0 || done < total) {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                try {
                    c.connectTimeout = 15_000; c.readTimeout = 15_000
                    c.setRequestProperty("Range", "bytes=$done-${done + RANGE - 1}")
                    val code = c.responseCode
                    if (code !in 200..299) throw IOException("HTTP $code")
                    total = c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                        ?: c.contentLengthLong.takeIf { code == 200 && it > 0 } ?: throw IOException("no length")
                    if (total > MAX_BYTES) throw IOException("too big")
                    val before = done
                    c.inputStream.use { inp -> val buf = ByteArray(64 * 1024); while (true) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n } }
                    if (done == before) throw IOException("empty range")
                    if (code == 200) break
                } finally { c.disconnect() }
            }
        }
        if (total > 0 && done < total) { tmp.delete(); throw IOException("incomplete") }
        if (!tmp.renameTo(target)) { tmp.delete(); throw IOException("rename failed") }
    }
}
