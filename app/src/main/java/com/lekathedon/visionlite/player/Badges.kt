package com.lekathedon.visionlite.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import com.lekathedon.visionlite.R

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

/**
 * Vision's built-in player badges (PlayerBuiltInBadges, same pictures): picture size, source type, HDR / Dolby Vision,
 * audio format, Atmos, channels. Picture and sound come from the player; only the source type, Atmos (for a codec
 * that can carry it) and DTS-HD MA come from the release name.
 */
internal fun badgesFor(info: Playing, releaseText: String): List<Int> {
    val out = mutableListOf<Int>()
    val w = info.width; val h = info.height
    when {
        w >= 3200 || h >= 2000 -> out += R.drawable.badge_res_2160
        w >= 1800 || h >= 1000 -> out += R.drawable.badge_res_1080
        w >= 1200 || h >= 700 -> out += R.drawable.badge_res_720
        w > 0 || h > 0 -> out += R.drawable.badge_res_480
    }
    val release = releaseText.lowercase()
    sourceBadge(release)?.let { out += it }
    when (info.hdr) {
        "DV" -> out += R.drawable.badge_dv
        "HDR10" -> out += if (HDR10_PLUS.containsMatchIn(release)) R.drawable.badge_hdr10_plus else R.drawable.badge_hdr10
        "HLG" -> out += R.drawable.badge_hdr
    }
    val codec = info.audioCodec
    val profile = info.audioProfile.uppercase()
    when {
        codec == "TRUEHD" -> R.drawable.badge_truehd
        codec == "DTS-HD" && DTS_X.containsMatchIn(release) -> R.drawable.badge_dts_x
        codec.startsWith("DTS") && (profile.contains("MA") || codec == "DTS-HD" && DTS_HD_MA.containsMatchIn(release)) -> R.drawable.badge_dts_hd_ma
        codec == "DTS-HD" -> R.drawable.badge_dts_hd
        codec == "DTS" -> R.drawable.badge_dts
        codec.startsWith("E-AC-3") -> R.drawable.badge_ddp
        codec == "AC-3" -> R.drawable.badge_dd
        else -> null
    }?.let { out += it }
    if (codec == "E-AC-3-JOC" || profile.contains("ATMOS") || (codec == "TRUEHD" || codec.startsWith("E-AC-3")) && ATMOS.containsMatchIn(release))
        out += R.drawable.badge_atmos
    when {
        info.channels >= 8 -> out += R.drawable.badge_ch_7_1
        info.channels >= 6 -> out += R.drawable.badge_ch_5_1
    }
    return out
}

private fun sourceBadge(release: String): Int? = when {
    REMUX.containsMatchIn(release) -> R.drawable.badge_remux
    WEB_DL.containsMatchIn(release) -> R.drawable.badge_webdl
    WEBRIP.containsMatchIn(release) -> R.drawable.badge_webrip
    BLURAY.containsMatchIn(release) -> R.drawable.badge_bluray
    HDTV.containsMatchIn(release) -> R.drawable.badge_hdtv
    DVDRIP.containsMatchIn(release) -> R.drawable.badge_dvdrip
    else -> null
}

private val ATMOS = Regex("""(?:(?<![a-z])|(?<=ddp|truehd))atmos(?![a-z])""")
private val DTS_HD_MA = Regex("""(?<![a-z])dts[\W_]*hd[\W_]*(?:ma|master[\W_]*audio)(?![a-z])""")
private val DTS_X = Regex("""(?<![a-z])dts[ .:_-]?x(?![a-z0-9])""")
private val HDR10_PLUS = Regex("""(?<![a-z])hdr10(?:\+|plus|p)(?![a-z])""")
private val REMUX = Regex("""(?<![a-z])remux(?![a-z])""")
private val WEB_DL = Regex("""(?<![a-z])web[ ._-]?dl(?![a-z])""")
private val WEBRIP = Regex("""(?<![a-z])web[ ._-]?rip(?![a-z])""")
private val BLURAY = Regex("""(?<![a-z])(blu[ ._-]?ray|bdrip|brrip)(?![a-z])""")
private val HDTV = Regex("""(?<![a-z])hdtv(?![a-z])""")
private val DVDRIP = Regex("""(?<![a-z])dvd[ ._-]?rip(?![a-z])""")

/** Vision's badge row: 20 dp pictures, not focusable; when too wide the last ones drop (channels, then Atmos). */
@Composable
internal fun BadgeRow(badges: List<Int>, modifier: Modifier = Modifier) {
    Layout(content = {
        badges.forEach { Image(painterResource(it), null, Modifier.height(20.dp), contentScale = ContentScale.FillHeight) }
    }, modifier = modifier) { measurables, constraints ->
        val gap = 6.dp.roundToPx()
        val placeables = measurables.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }
        var width = 0; var count = 0
        for (p in placeables) {
            val next = if (count == 0) p.width else width + gap + p.width
            if (next > constraints.maxWidth) break
            width = next; count++
        }
        val shown = placeables.take(count)
        val height = shown.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            var x = 0
            shown.forEach { p -> p.placeRelative(x, (height - p.height) / 2); x += p.width + gap }
        }
    }
}
