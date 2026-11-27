package com.lekathedon.visionlite.streams

import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.device.DeviceCheck
import com.lekathedon.visionlite.tb.ReleaseQuality

/**
 * Can this box, this TV and this internet play a link well? Read from the last device check (This TV) and the
 * picture settings, by the link's name and size only (no network). Autoplay skips what can't play and ranks what
 * would struggle after what plays well; Vision TB doesn't spend TorBox checks on links that can't play here.
 * No device check yet = everything fits (nothing is ruled out on a guess).
 */
object DeviceFit {
    const val NO = 0; const val POOR = 1; const val GOOD = 2

    class Can(
        /** Tallest picture the decoders take (0 = unknown). */
        val decodeH: Int,
        /** The TV's own mode height (a 1080p TV gains nothing from 4K). */
        val tvH: Int,
        val hevc10: Boolean, val av1: Boolean, val hdrTv: Boolean,
        val dv7: Boolean, val dv5: Boolean,
        /** Measured internet speed in Mb/s (null = not measured). */
        val mbps: Double?,
    )

    @Volatile private var cache: Pair<String, Can?>? = null

    fun can(): Can? {
        val p = App.instance.prefs
        val key = p.tvFacts.value + p.dv7.value + p.dv5To81.value + p.dv5Mpv.value + p.hdrOnSdr.value + com.lekathedon.visionlite.device.DeviceProof.dv81 + com.lekathedon.visionlite.device.DeviceProof.mbps(null)
        cache?.takeIf { it.first == key }?.let { return it.second }
        val f = DeviceCheck.saved()
        val c = f?.let {
            val known = it.decodersKnown
            Can(
                decodeH = if (!known) 0 else if (it.uhdDecode) 2160 else it.maxDecodeHeight.takeIf { h -> h > 0 } ?: 1080,
                tvH = minOf(it.width, it.height).takeIf { h -> h > 0 } ?: 0,
                hevc10 = !known || it.hevc10, av1 = !known || it.av1Hw,
                hdrTv = !it.capsKnown || it.hdr10 || it.hlg || it.dvTv,
                // Every route plays a DV7 file now (FEL, 8.1, or its HDR10 picture: never profile 7 to a decoder that can't).
                dv7 = true,
                dv5 = !known || (it.dvDecoder && (it.dvTv || p.dv5To81.value)) || p.dv5Mpv.value,
                // The lower of the speed test and what real plays got: a slow evening counts.
                mbps = it.mbps,
            )
        }
        cache = key to c
        return c
    }

    private val P7 = Regex("(?i)(?<![a-z0-9])(?:p7|dv7|dvp7|profile ?7|dv[ ._-]?p7)(?![0-9])|(?<![a-z])fel(?![a-z])")
    private val HDR_FALLBACK = Regex("(?i)hdr10|(?<![a-z])hdr(?![a-z])|hlg")
    private val TEN_BIT = Regex("(?i)10 ?-?bit|hi10|main ?10")

    /** GOOD, POOR or NO for a link named [text] of [bytes] lasting about [minutes]. */
    fun fit(text: String, q: ReleaseQuality = ReleaseQuality.parse(text), bytes: Long = 0, minutes: Int = 0, height: Int = 0): Int {
        val c = can() ?: return GOOD
        val h = maxOf(q.resolution ?: 0, height)
        val codec = q.codec?.lowercase().orEmpty()
        val hevc = codec.contains("265") || codec.contains("hevc")
        val av1 = codec.contains("av1")
        var out = GOOD
        // The decoders can't take it at all.
        if (c.decodeH in 1 until h) return NO
        if (hevc && !c.hevc10 && (TEN_BIT.containsMatchIn(text) || q.hdr == true || q.dolbyVision == true)) return NO
        if (av1 && !c.av1) { if (h >= 2160) return NO; out = POOR }
        // Dolby Vision this box can't show right: DV7 without a route, DV5 (a DV web file with no HDR named) without one.
        if (q.dolbyVision == true || P7.containsMatchIn(text)) {
            val p7 = P7.containsMatchIn(text) || (q.source == ReleaseQuality.Source.REMUX && h >= 2160)
            if (p7 && !c.dv7) out = POOR
            if (!p7 && !c.dv5 && !HDR_FALLBACK.containsMatchIn(text) && (q.source == ReleaseQuality.Source.WEB_DL || q.source == ReleaseQuality.Source.WEBRIP)) out = POOR
        }
        // HDR on a TV without HDR: an SDR copy looks better.
        if (!c.hdrTv && (q.hdr == true || q.dolbyVision == true)) out = POOR
        // 4K on a TV that shows 1080p: no gain, four times the data.
        if (h >= 2160 && c.tvH in 1..1200) out = POOR
        // The internet: the file's average rate against the measured speed (peaks run about twice the average).
        // Plays that really stalled (2+ rebuffers after 2 min) rule a file out; the one-off 6 s speed test (one
        // connection, often slower than a debrid CDN) only moves a file down when it is far too slow for it.
        val proven = com.lekathedon.visionlite.device.DeviceProof.provenMbps()
        val test = c.mbps
        val rate = if (bytes > 0) bytes * 8.0 / ((if (minutes > 0) minutes else 110) * 60.0) / 1e6 else 0.0
        // Over 150 Mb/s on average is no single film (a 4K remux runs 40-80): a season pack's size, so not judged.
        if (rate in 0.1..150.0) {
            if (proven != null && proven > 0) {
                if (rate > proven * 1.2) return NO
                if (rate > proven * 0.8) out = POOR
            } else if (test != null && test > 0 && rate > test * 3) out = POOR
        }
        return out
    }

    /** One line for the log: why a link would struggle here. */
    fun label(v: Int) = when (v) { NO -> "can't play here"; POOR -> "plays poorly here"; else -> "fits" }
}
