// Vision's MPV surface (NuvioMpvSurfaceView), trimmed to what Lite uses: load with headers and a start position,
// pause / seek / speed, tracks, external subtitles, passthrough codecs, aspect, and one property read for Stats.
package com.lekathedon.visionlite.player.mpv

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.Utils
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

class MpvView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : BaseMPVView(context, attrs) {

    private var initialized = false
    private var pendingUrl: String? = null
    private var pendingOptions: String? = null
    private var softwareDecode = false
    private var gpuNext = false
    private var extra: List<Pair<String, String>> = emptyList()
    private var spdif = ""

    val ready get() = initialized

    /** [software]: decode on the CPU with gpu-next (Hi10p H.264, which no TV chip decodes in hardware). */
    private var bufferMb = 64

    /** [gpuNext]: gpu-next on copied hardware frames (Dolby Vision 5 reshaping, HDR → SDR tone-mapping). [extra]: sound options (Sound.kt). */
    fun init(software: Boolean, spdifCodecs: String, readAheadMb: Int = 64, gpuNext: Boolean = false, extra: List<Pair<String, String>> = emptyList()) {
        if (initialized) return
        softwareDecode = software
        this.gpuNext = gpuNext
        this.extra = extra
        bufferMb = readAheadMb
        spdif = spdifCodecs
        Utils.copyAssets(context)
        initialize(context.filesDir.path, context.cacheDir.path)
        initialized = true
    }

    /** Loads [url]; [startMs] > 0 starts there (mpv's per-file `start` option, so nothing plays from 0 first). */
    fun load(url: String, headers: Map<String, String>, startMs: Long) {
        applyHeaders(headers)
        val options = buildList {
            if (startMs > 0) add(String.format(Locale.US, "start=+%.3f", startMs / 1000.0))
            if (startMs > 0 && softwareDecode) add("hr-seek=no")
        }.joinToString(",")
        if (holder.surface?.isValid == true) loadNow(url, options)
        else { pendingUrl = url; pendingOptions = options }
    }

    private fun loadNow(url: String, options: String) {
        // loadfile <url> <flags> <index> <options>: the options belong in the fourth argument.
        if (options.isEmpty()) mpv.command("loadfile", url, "replace") else mpv.command("loadfile", url, "replace", "-1", options)
        runCatching { mpv.setPropertyString("aid", "auto"); mpv.setPropertyString("sid", "auto") }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        super.surfaceCreated(holder)
        val url = pendingUrl ?: return
        pendingUrl = null
        loadNow(url, pendingOptions.orEmpty())
    }

    fun setPaused(paused: Boolean) { if (initialized) mpv.setPropertyBoolean("pause", paused) }
    fun paused() = initialized && mpv.getPropertyBoolean("pause") == true
    fun pausedForCache() = initialized && mpv.getPropertyBoolean("paused-for-cache") == true
    fun seeking() = initialized && mpv.getPropertyBoolean("seeking") == true
    fun eof() = initialized && mpv.getPropertyBoolean("eof-reached") == true
    fun idle() = initialized && mpv.getPropertyBoolean("idle-active") == true
    fun seekable() = initialized && mpv.getPropertyBoolean("seekable") != false
    fun cacheAheadSec() = if (initialized) mpv.getPropertyDouble("demuxer-cache-duration") ?: 0.0 else 0.0

    fun seekTo(ms: Long) {
        if (!initialized) return
        runCatching { mpv.command("seek", String.format(Locale.US, "%.3f", ms.coerceAtLeast(0) / 1000.0), "absolute") }
    }

    fun positionMs(): Long {
        if (!initialized) return 0
        val t = mpv.getPropertyDouble("time-pos/full") ?: mpv.getPropertyDouble("time-pos") ?: 0.0
        // h264_mediacodec can stamp a packet with pts 0: while audio runs far ahead outside a seek, audio holds the
        // real position (Vision's mpvPlaybackPositionSec).
        val a = mpv.getPropertyDouble("audio-pts")
        val delay = mpv.getPropertyDouble("audio-delay") ?: 0.0
        val s = if (a != null && !seeking() && a - t > 10.0 + abs(delay)) a else t
        return (s * 1000).roundToLong().coerceAtLeast(0)
    }

    fun durationMs(): Long = if (!initialized) 0 else ((mpv.getPropertyDouble("duration/full") ?: 0.0) * 1000).roundToLong().coerceAtLeast(0)

    fun setSpeed(speed: Float) {
        if (!initialized) return
        mpv.setPropertyDouble("speed", speed.toDouble())
        // Another speed needs decoded sound, so the bitstream steps aside (as in Vision).
        runCatching { mpv.setPropertyString("audio-spdif", if (speed == 1f) spdif else "") }
    }

    fun languages(audio: List<String>, subs: List<String>, subsOff: Boolean) {
        if (!initialized) return
        runCatching {
            mpv.setOptionString("alang", audio.joinToString(","))
            if (subsOff) { mpv.setOptionString("sid", "no") } else mpv.setOptionString("slang", subs.joinToString(","))
        }
    }

    /** Old Vision's subtitle look (player/SubStyle.kt); [scale]/[style] kept for the callers, Settings decide. */
    @Suppress("UNUSED_PARAMETER")
    fun subtitleStyle(scale: Double, style: String, hdr: Boolean = false) {
        if (!initialized) return
        runCatching { com.lekathedon.visionlite.player.SubStyle.mpv({ k, v -> if (hdr) mpv.setPropertyString(k, v) else mpv.setOptionString(k, v) }, hdr) }
    }

    /** Sync: audio and subtitle delay in ms (mpv's audio-delay / sub-delay, seconds). */
    fun delays(audioMs: Long, subMs: Long) {
        if (!initialized) return
        runCatching { mpv.setPropertyDouble("audio-delay", audioMs / 1000.0); mpv.setPropertyDouble("sub-delay", subMs / 1000.0) }
    }
    fun selectAudio(id: Int) = runCatching { mpv.setPropertyInt("aid", id) }.isSuccess
    fun selectSub(id: Int) = runCatching { mpv.setPropertyBoolean("sub-visibility", true); mpv.setPropertyInt("sid", id) }.isSuccess
    fun subsOff() = runCatching { mpv.setPropertyString("sid", "no") }.isSuccess

    fun addSubtitle(url: String, title: String?, lang: String?) {
        if (!initialized || url.isBlank()) return
        runCatching {
            when {
                title != null && lang != null -> mpv.command("sub-add", url, "auto", title, lang)
                title != null -> mpv.command("sub-add", url, "auto", title)
                else -> mpv.command("sub-add", url, "auto")
            }
        }
    }

    /** "fit", "zoom" (crop to fill) or "stretch". */
    fun aspect(mode: String) {
        if (!initialized) return
        runCatching {
            mpv.setPropertyString("keepaspect", if (mode == "stretch") "no" else "yes")
            mpv.setPropertyDouble("panscan", if (mode == "zoom") 1.0 else 0.0)
        }
    }

    /** One property as text (Stats); null when mpv has no value. */
    fun prop(name: String): String? = if (!initialized) null
        else runCatching { mpv.getPropertyString(name) }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }

    data class Track(val id: Int, val type: String, val lang: String?, val title: String?, val codec: String?,
                     val channels: Int?, val sampleRate: Int?, val forced: Boolean, val external: Boolean, val selected: Boolean)

    /** Audio and subtitle tracks with the current pick (read only when the track list changed). */
    fun tracks(): List<Track> {
        if (!initialized) return emptyList()
        val n = runCatching { mpv.getPropertyInt("track-list/count") ?: 0 }.getOrDefault(0)
        val aid = mpv.getPropertyString("aid")?.toIntOrNull()
        val sid = mpv.getPropertyString("sid")?.toIntOrNull()
        return (0 until n).mapNotNull { i ->
            val type = mpv.getPropertyString("track-list/$i/type")?.lowercase() ?: return@mapNotNull null
            if (type != "audio" && type != "sub" && type != "video") return@mapNotNull null
            val id = mpv.getPropertyInt("track-list/$i/id") ?: return@mapNotNull null
            val title = mpv.getPropertyString("track-list/$i/title")?.trim()?.takeIf { it.isNotBlank() }
            val lang = mpv.getPropertyString("track-list/$i/lang")?.trim()?.takeIf { it.isNotBlank() }
            Track(
                id, type, lang, title, mpv.getPropertyString("track-list/$i/codec"),
                mpv.getPropertyInt("track-list/$i/demux-channel-count") ?: mpv.getPropertyInt("track-list/$i/audio-channels"),
                mpv.getPropertyInt("track-list/$i/demux-samplerate")?.takeIf { it > 0 },
                mpv.getPropertyBoolean("track-list/$i/forced") == true || listOfNotNull(title, lang).any { it.contains("forced", true) },
                mpv.getPropertyBoolean("track-list/$i/external") == true,
                when (type) { "audio" -> id == aid; "sub" -> id == sid; else -> mpv.getPropertyBoolean("track-list/$i/selected") == true }
            )
        }
    }

    fun release() {
        if (!initialized) return
        runCatching { destroy() }.onFailure { Log.w(TAG, "mpv destroy: ${it.message}") }
        initialized = false
        pendingUrl = null
    }

    override fun initOptions() {
        mpv.setOptionString("profile", "fast")
        mpv.setOptionString("msg-level", "all=warn")
        setVo(if (softwareDecode || gpuNext) "gpu-next" else "gpu")
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("opengl-es", "yes")
        mpv.setOptionString("hwdec", when { softwareDecode -> "no"; gpuNext -> "mediacodec-copy"; else -> "auto-safe" })
        if (gpuNext) {
            // Frames go through libplacebo: Dolby Vision RPU reshaping, then tone-mapping to what the TV shows.
            mpv.setOptionString("tone-mapping", "bt.2390")
            mpv.setOptionString("hdr-compute-peak", "no")
        }
        for ((k, v) in extra) runCatching { mpv.setOptionString(k, v) }
        mpv.setOptionString("hwdec-codecs", "h264,hevc,mpeg2video,vp8,vp9,av1")
        mpv.setOptionString("ao", "audiotrack,opensles")
        mpv.setOptionString("audio-set-media-role", "yes")
        mpv.setOptionString("audio-spdif", spdif)
        mpv.setOptionString("sub-ass-override", "no")
        mpv.setOptionString("sub-codepage", "auto:utf-8")
        mpv.setOptionString("sub-use-margins", "yes")
        mpv.setOptionString("sub-ass-force-margins", "yes")
        mpv.setOptionString("tls-verify", "yes")
        mpv.setOptionString("tls-ca-file", "${context.filesDir.path}/cacert.pem")
        mpv.setOptionString("input-default-bindings", "no")
        mpv.setOptionString("demuxer-max-bytes", "${bufferMb * 1024 * 1024}")
        mpv.setOptionString("demuxer-max-back-bytes", "${64 * 1024 * 1024}")
        mpv.setOptionString("cache", "yes")
        mpv.setOptionString("keep-open", "yes")
        mpv.setOptionString("user-agent", "Mozilla/5.0 (Linux; Android) VisionLite")
    }

    override fun postInitOptions() { mpv.setOptionString("save-position-on-quit", "no") }

    override fun observeProperties() {
        mpv.observeProperty("track-list", MPV.mpvFormat.MPV_FORMAT_NONE)
        mpv.observeProperty("hwdec-current", MPV.mpvFormat.MPV_FORMAT_STRING)
    }

    private fun applyHeaders(headers: Map<String, String>) {
        val raw = headers.filter { it.key.isNotBlank() && it.value.isNotBlank() }.entries
            .joinToString(",") { "${it.key}: ${it.value}".replace("\\", "\\\\").replace(",", "\\,") }
        runCatching { mpv.setOptionString("http-header-fields", raw) }
    }

    companion object { private const val TAG = "MpvView" }
}
