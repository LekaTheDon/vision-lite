package com.lekathedon.visionlite.player

import androidx.media3.exoplayer.ExoPlayer

/** What is playing, as the badges need it (from ExoPlayer's formats or MPV's properties). */
internal data class Playing(val width: Int, val height: Int, val hdr: String, val audioCodec: String, val audioProfile: String, val channels: Int)

internal fun playing(src: StatsSource): Playing? {
    if (src.engine == Engine.MPV) {
        val m = src.mpv ?: return null
        val w = (m.prop("video-params/w") ?: m.prop("current-tracks/video/demux-w"))?.toIntOrNull() ?: 0
        val h = (m.prop("video-params/h") ?: m.prop("current-tracks/video/demux-h"))?.toIntOrNull() ?: 0
        val hdr = when {
            !m.prop("current-tracks/video/dolby-vision-profile").isNullOrBlank() -> "DV"
            m.prop("video-params/gamma").equals("pq", true) -> "HDR10"
            m.prop("video-params/gamma").equals("hlg", true) -> "HLG"
            else -> ""
        }
        val codec = when (m.prop("current-tracks/audio/codec")?.lowercase()) {
            "truehd" -> "TRUEHD"; "eac3" -> "E-AC-3"; "ac3" -> "AC-3"; "dts" -> "DTS"; null -> ""; else -> ""
        }
        val profile = m.prop("current-tracks/audio/codec-profile").orEmpty()
        val ch = (m.prop("audio-params/channel-count") ?: m.prop("current-tracks/audio/demux-channel-count"))?.toIntOrNull() ?: 0
        return Playing(w, h, hdr, if (codec == "DTS" && profile.contains("hd", true)) "DTS-HD" else codec, profile, ch)
    }
    val exo = src.player as? ExoPlayer ?: return null
    val v = exo.videoFormat
    val a = exo.audioFormat
    val hdr = when (Hud.source(v)) { "Dolby Vision" -> "DV"; "HDR10" -> "HDR10"; "HLG" -> "HLG"; else -> "" }
    val codec = when (a?.sampleMimeType) {
        "audio/true-hd" -> "TRUEHD"; "audio/eac3-joc" -> "E-AC-3-JOC"; "audio/eac3" -> "E-AC-3"; "audio/ac3" -> "AC-3"
        "audio/vnd.dts.hd" -> "DTS-HD"; "audio/vnd.dts" -> "DTS"; else -> ""
    }
    return Playing(v?.width ?: 0, v?.height ?: 0, hdr, codec, "", a?.channelCount ?: 0)
}
