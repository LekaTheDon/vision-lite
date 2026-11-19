// Vision's two-engine rules (SmartAutoRoute, UnsupportedFormatMpvFallback, MpvHi10pFallback, EngineFailover):
// which engine a stream starts on, and when it moves to the other one. At most one move per stream for each rule,
// so a stream can never bounce between engines.
package com.lekathedon.visionlite.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi

enum class Engine(val label: String) { EXO("ExoPlayer"), MPV("MPV") }

/** One stream's engine state, kept across engine switches for that stream (reset when the link changes). */
class EngineSession(val url: String) {
    var engine: Engine = Engine.EXO
    /** Auto picked the engine and nothing forced it since (manual switch, failover). */
    var smartAuto = false
    var reason: String? = null
    var rerouted = false
    var formatFallback = false
    var startupFailover = false
    /** Hi10p H.264: MPV decodes on the CPU with gpu-next. */
    var mpvSoftware = false
    /** Where the next engine starts (the position at the switch). */
    var resumeAt = 0L
}

object EngineRules {
    const val REASON_HDR = "HDR / Dolby Vision → video layer, untouched"
    const val REASON_HI10P = "Hi10p H.264 → MPV (hardware can't decode it)"
    const val REASON_DV_FROM_MPV = "Dolby Vision file → ExoPlayer video layer"

    private val HDR_NAME = Regex("""(?i)\b(hdr|hdr10\+?|dv|dolby\s*vision)\b""")

    /**
     * Auto's first pick from names and ids only (no network, no delay): HDR / Dolby Vision → ExoPlayer; anime →
     * ExoPlayer with libass (MPV when libass is off); everything else → ExoPlayer. Live TV and YouTube stay on ExoPlayer.
     */
    fun autoPick(s: EngineSession, name: String, id: String, type: String, genres: List<String>, country: String?): Engine {
        if (type == "live" || type == "youtube") { s.reason = "live / YouTube → ExoPlayer"; return Engine.EXO }
        if (HDR_NAME.containsMatchIn(name)) { s.reason = "named HDR / Dolby Vision → ExoPlayer"; return Engine.EXO }
        val animeId = id.startsWith("kitsu:") || id.startsWith("mal:") || id.startsWith("anilist:")
        val anime = animeId || genres.any { it.equals("anime", true) } ||
            (genres.any { it.equals("animation", true) } && country?.contains("Japan", true) == true)
        if (anime) {
            // libass draws the styled subtitles on ExoPlayer (keeps passthrough and the video layer); without it, MPV.
            if (com.lekathedon.visionlite.App.instance.prefs.libass.value) { s.reason = "anime, libass on → ExoPlayer"; return Engine.EXO }
            s.reason = "anime → MPV (styled subtitles)"; return Engine.MPV
        }
        s.reason = "default → ExoPlayer"
        return Engine.EXO
    }

    fun isHdr(v: Format?): Boolean {
        v ?: return false
        if (v.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) return true
        if (v.codecs?.lowercase()?.let { c -> listOf("dvhe", "dvh1", "dav1").any { c.contains(it) } } == true) return true
        val t = v.colorInfo?.colorTransfer
        return t == C.COLOR_TRANSFER_ST2084 || t == C.COLOR_TRANSFER_HLG
    }

    private val AVC_PROFILE = Regex("avc[13]\\.([0-9a-f]{2})")

    /** H.264 High 10 / 4:2:2 / 4:4:4: no TV chip decodes them in hardware. */
    fun isHi10p(v: Format?): Boolean {
        if (v?.sampleMimeType != MimeTypes.VIDEO_H264) return false
        val depth = v.colorInfo?.lumaBitdepth?.takeIf { it > 0 }
        if (depth != null) return depth >= 10
        val profile = v.codecs?.lowercase()?.let { AVC_PROFILE.find(it) }?.groupValues?.get(1)?.toIntOrNull(16) ?: return false
        return profile in setOf(110, 122, 244)
    }

    /** Release names that say Hi10p (Vision's mpvPlaybackHintsIndicateHi10p). */
    fun namedHi10p(vararg hints: String?): Boolean = hints.filterNotNull().any { h ->
        val v = h.lowercase()
        val tenBit = "10bit" in v || "10-bit" in v || "10 bit" in v
        val avc = "x264" in v || "h264" in v || "h.264" in v || "avc" in v
        val hevc = "x265" in v || "h265" in v || "h.265" in v || "hevc" in v
        "hi10" in v || (tenBit && avc && !hevc)
    }

    private val LEGACY_VIDEO = setOf(
        MimeTypes.VIDEO_VC1, "video/vc1", MimeTypes.VIDEO_MP4V, MimeTypes.VIDEO_DIVX, MimeTypes.VIDEO_MP42, MimeTypes.VIDEO_MP43, MimeTypes.VIDEO_H263
    )
    private fun legacy(mime: String?) = mime?.lowercase()?.let { it in LEGACY_VIDEO || "wvc1" in it || "wmv" in it } == true

    private val MPV_ONLY_EXT = listOf(".wmv", ".asf", ".rm", ".rmvb", ".ogm")
    private val TS_EXT = listOf(".ts", ".m2ts", ".mts", ".m2t")

    fun mpvOnlyContainer(url: String): Boolean = url.substringBefore('?').substringBefore('#').lowercase().let { n -> MPV_ONLY_EXT.any { n.endsWith(it) } }

    /** ExoPlayer can't show this file at all (DivX 3, WMV, RealVideo, a VC-1 / AV1 decoder that won't start, Hi10p). */
    @OptIn(UnstableApi::class)
    fun exoFatalGoesToMpv(e: PlaybackException, video: Format?, url: String, firstFrame: Boolean): Boolean = when (e.errorCode) {
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> !firstFrame && mpvOnlyContainer(url)
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
            legacy(video?.sampleMimeType) || isHi10p(video) || (!firstFrame && video?.sampleMimeType == MimeTypes.VIDEO_AV1)
        else -> false
    }

    /** Sound but no picture: the file has a video track ExoPlayer can't select, or a .ts with VC-1 (no reader). */
    fun exoNoPictureGoesToMpv(videoPresent: Boolean, videoSelected: Boolean, videoSupport: Int, hasAudio: Boolean, url: String): Boolean {
        if (videoPresent && !videoSelected && (videoSupport == C.FORMAT_UNSUPPORTED_SUBTYPE || videoSupport == C.FORMAT_UNSUPPORTED_TYPE)) return true
        if (!videoPresent && hasAudio) return url.substringBefore('?').lowercase().let { n -> TS_EXT.any { n.endsWith(it) } }
        return false
    }
}
