package com.lekathedon.visionlite.player.dv

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import android.view.Display

/**
 * Let the app decide (This TV › Dolby Vision profile 7 = "auto"), user rule 2026-11-28:
 *  1. The whole chain plays profile 7 (FEL kernel box + a display that takes Dolby Vision): profile 7 goes through.
 *  2. The display takes Dolby Vision: convert to 8.1 (libdovi), the RPU's metadata kept.
 *  3. No Dolby Vision on the display: only then is Dolby Vision stripped, the HDR10 picture under it plays
 *     (HDR10 metadata written from the RPU). Samsung / Fire TV / Xiaomi with a profile 8 decoder still convert on an
 *     HDR10 TV: their HEVC path fails on that base layer and their DV decoder outputs HDR10 (Arc's Auto, same cases).
 * Profile 7 never reaches a decoder untouched otherwise. The decision follows the display as it is now (a box can move
 * to another TV), read once per play: a display query and one decoder list (cached).
 */
object DvPolicy {
    enum class Decision { NATIVE_FEL, CONVERT_81, STRIP_HDR10, STRIP_UNKNOWN, STRIP_SDR }

    data class Result(
        val decision: Decision,
        val capsKnown: Boolean, val displayDv: Boolean, val displayHdr10: Boolean,
        val decP7: Boolean, val decP8: Boolean, val decP5: Boolean, val converter: Boolean,
    ) {
        /** Convert the way a hand-picked Convert to 8.1 does (libdovi mode 2, per-RPU fallback to 1): a Dolby Vision
         *  display whose decoder doesn't list profile 8 alone (Arc's usesManualConversion). */
        val mode2: Boolean get() = decision == Decision.CONVERT_81 && displayDv && (decP7 || (!decP8 && !isXiaomi))
        val strips: Boolean get() = decision == Decision.STRIP_HDR10 || decision == Decision.STRIP_UNKNOWN || decision == Decision.STRIP_SDR

        fun why(): String = when (decision) {
            Decision.NATIVE_FEL -> "This box and your TV play dual-layer Dolby Vision: profile 7 goes through as it is (FEL)"
            Decision.CONVERT_81 -> if (displayDv) "Your TV shows Dolby Vision: profile 7 is converted to 8.1, its Dolby Vision metadata kept"
                else "HDR10 TV, but this device's Dolby Vision decoder makes the HDR10 picture: converted to 8.1 for it"
            Decision.STRIP_HDR10 -> if (displayDv) "The Dolby Vision converter didn't load here: the HDR10 picture plays"
                else "Your TV doesn't show Dolby Vision: Dolby Vision is stripped, the HDR10 picture plays"
            Decision.STRIP_UNKNOWN -> "The TV's HDR list can't be read: the HDR10 picture plays (safest)"
            Decision.STRIP_SDR -> "Your TV shows no HDR: the HDR10 picture plays, tone-mapped"
        }
    }

    private val isXiaomi get() = Build.MANUFACTURER.equals("Xiaomi", true)
    private val isSamsung get() = Build.MANUFACTURER.equals("Samsung", true)
    private val isAmazon get() = Build.MANUFACTURER.equals("Amazon", true)

    private data class Profiles(val p7: Boolean, val p5: Boolean, val p8: Boolean)
    @Volatile private var decoders: Profiles? = null

    private fun decoderProfiles(): Profiles = decoders ?: runCatching {
        var p7 = false; var p5 = false; var p8 = false
        for (i in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (i.isEncoder || i.supportedTypes.none { it.equals(DV, true) }) continue
            val pl = runCatching { i.getCapabilitiesForType(DV).profileLevels }.getOrNull() ?: continue
            for (x in pl) when (x.profile) {
                CodecProfileLevel.DolbyVisionProfileDvheDtb -> p7 = true
                CodecProfileLevel.DolbyVisionProfileDvheStn -> p5 = true
                CodecProfileLevel.DolbyVisionProfileDvheSt -> p8 = true
            }
        }
        Profiles(p7, p5, p8)
    }.getOrDefault(Profiles(false, false, false)).also { decoders = it }

    @Suppress("DEPRECATION")
    private fun displayHdr(ctx: Context): IntArray? = runCatching {
        ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.hdrCapabilities?.supportedHdrTypes
    }.getOrNull()

    /** [felUsable]: the box's dual-layer port works (AmlFelSupport). */
    fun resolve(ctx: Context, felUsable: Boolean): Result {
        val conv = Dovi.available()
        val hdr = displayHdr(ctx)
        val known = hdr != null
        val dvTv = hdr?.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) == true
        val hdr10 = hdr?.any { it == Display.HdrCapabilities.HDR_TYPE_HDR10 || it == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS } == true
        val d = decoderProfiles()
        val decision = when {
            dvTv && felUsable -> Decision.NATIVE_FEL
            !known -> Decision.STRIP_UNKNOWN
            dvTv && conv -> Decision.CONVERT_81
            dvTv -> Decision.STRIP_HDR10
            hdr10 && conv && d.p8 && (isSamsung || isAmazon) -> Decision.CONVERT_81
            hdr10 && conv && isXiaomi -> Decision.CONVERT_81
            hdr10 -> Decision.STRIP_HDR10
            else -> Decision.STRIP_SDR
        }
        return Result(decision, known, dvTv, hdr10, d.p7, d.p8, d.p5, conv).also {
            android.util.Log.i("DvPolicy", "auto: $decision dvTv=$dvTv hdr10=$hdr10 known=$known decP7=${d.p7} decP8=${d.p8} converter=$conv fel=$felUsable")
        }
    }

    private const val DV = "video/dolby-vision"
}

/**
 * Per link, for this session (Arc's recovery): Convert at mode 2 failed to decode -> mode 1 once; still failing ->
 * the HDR10 base layer. Never back to profile 7 untouched.
 */
object DvRetry {
    val mode1: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    val hdr10: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
}
