package com.lekathedon.visionlite.player.dv

import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorsFactory
import com.lekathedon.visionlite.data.Prefs

/**
 * Lite's switch onto Vision's Dolby Vision pipeline (this package, copied from Vision as is). The This TV settings
 * pick the route: Convert to 8.1 (libdovi, DV7 and optionally DV5), HDR10 base layer (RPU and enhancement layer
 * stripped, HDR10 colour signalled), and HDR10+ strip. Native FEL and "the box decides" keep Lite's plain path.
 */
@UnstableApi
class DvRoute private constructor(internal val config: DvPlan, val strip: Boolean, val stripHdr10Plus: Boolean, val injectHdr10: Boolean = false) {
    companion object {
        /** What Let the app decide chose for the last play (Stats / play log). */
        @Volatile var lastWhy: String? = null

        /**
         * The route for the current settings and this link, or null when nothing needs Vision's pipeline (native FEL,
         * or "box" on a decoder that lists profile 7). Arc's modes: auto (DvPolicy), fel, dv81, box (= Arc's Off), hdr10.
         * Rule (user 2026-11-28): profile 7 reaches a decoder untouched only on native FEL or "box" where the decoder
         * itself lists profile 7. Every other path converts to 8.1, or strips to HDR10 when it can't convert.
         */
        fun of(p: Prefs, nativeFel: Boolean, url: String? = null, ctx: android.content.Context = com.lekathedon.visionlite.App.instance): DvRoute? {
            if (nativeFel) { lastWhy = "Native FEL"; return null }
            val mode = p.dv7.value
            val conv = Dovi.available()
            val pol = if (mode == "auto" || mode == "fel") DvPolicy.resolve(ctx, false) else null
            val forcedHdr10 = url != null && url in DvRetry.hdr10
            val mode1 = url != null && url in DvRetry.mode1
            val p7Decoder = mode == "box" && com.lekathedon.visionlite.device.DeviceCheck.saved()?.dv7Decoder == true
            val to81 = !forcedHdr10 && conv && when (mode) {
                "auto", "fel" -> pol?.decision == DvPolicy.Decision.CONVERT_81
                "hdr10" -> false
                "box" -> !p7Decoder
                else -> true   // dv81
            }
            val strip = !to81 && !(mode == "box" && p7Decoder && !forcedHdr10)
            lastWhy = when {
                forcedHdr10 -> "8.1 failed on this link: HDR10 picture"
                pol != null -> pol.why()
                to81 -> "Convert to 8.1"
                strip -> "HDR10 picture (Dolby Vision stripped)"
                else -> "Profile 7 to the box's own profile 7 decoder"
            }
            val dv5 = conv && p.dv5To81.value
            val plus = p.stripHdr10Plus.value
            if (!to81 && !dv5 && !strip && !plus) return null
            val manual = mode == "dv81" || mode == "box"
            val override = p.dvConvMode.value.toIntOrNull()?.takeIf { it in 0..4 }
            val mode2 = (to81 && manual && !mode1) || (to81 && !manual && pol?.mode2 == true && !mode1)
            // Arc's choices: a hand-picked mode applies to a hand-picked Convert; mode 2 falls back to 1 per RPU;
            // a failed mode 2 on this link retries at mode 1; profile 5 converts only by hand.
            val forced = when { mode1 -> 1; manual && override != null -> override; else -> -1 }
            val cfg = DvPlan(convert7 = to81, convert5 = dv5, mode = if (forced >= 0) forced else if (mode2) 2 else 1,
                fallback1 = mode2 && forced < 0)
            return DvRoute(cfg, strip, plus, strip && p.dvInjectHdr10.value)
        }

        /**
         * The vendored MKV reader's global switches (Arc sets them per play): on the HDR10 path the track is handed
         * over as plain HEVC, so no decoder ever sees it as Dolby Vision. Reset for every play.
         */
        /** The route of the play in progress (Stats reads it; never recomputed there). */
        @Volatile var last: DvRoute? = null

        fun arm(r: DvRoute?) {
            last = r
        }

        private val dvName = Regex("""(?i)(\bDV\b|\bDoVi\b|dolby[ ._-]?vision|\bDV[ ._-]?HDR)""")
        fun namedDv(name: String) = dvName.containsMatchIn(name)

        fun factory(base: ExtractorsFactory, r: DvRoute): ExtractorsFactory {
            DvStats.reset(); DvDecoders.openedAs = null
            return DvExtractors(base, r.config, strip = r.strip, stripPlus = r.stripHdr10Plus, inject = r.injectHdr10)
        }

        /** The converter's native library is in this build and loads on this box. */
        fun converterAvailable(): Boolean = Dovi.available()
    }
}
