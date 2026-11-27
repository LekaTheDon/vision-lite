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
class DvRoute private constructor(internal val config: DolbyVisionConversionConfig, val strip: Boolean, val stripHdr10Plus: Boolean, val injectHdr10: Boolean = false) {
    companion object {
        /** The route for the current settings, or null when nothing needs Vision's pipeline. */
        fun of(p: Prefs, nativeFel: Boolean): DvRoute? {
            if (nativeFel) return null
            val mode = p.dv7.value
            val conv = DoviBridge.isAvailable()
            val to81 = conv && mode == "dv81"
            val dv5 = conv && p.dv5To81.value
            val strip = mode == "hdr10"
            val plus = p.stripHdr10Plus.value
            if (!to81 && !dv5 && !strip && !plus) return null
            val cfg = DolbyVisionConversionConfig(
                active = to81 || dv5,
                manualDv81 = to81 || dv5,
                dv5Enabled = dv5,
                // DV5 alone: profile 7 is left to the box.
                nativeProfile7 = !to81,
                forcedMode = p.dvConvMode.value.toIntOrNull()?.takeIf { it in 0..4 } ?: -1,
            )
            return DvRoute(cfg, strip && !to81, plus, strip && !to81 && p.dvInjectHdr10.value)
        }

        private val dvName = Regex("""(?i)(\bDV\b|\bDoVi\b|dolby[ ._-]?vision|\bDV[ ._-]?HDR)""")
        fun namedDv(name: String) = dvName.containsMatchIn(name)

        fun factory(base: ExtractorsFactory, r: DvRoute): ExtractorsFactory {
            DolbyVisionConversionStats.reset(); DoviBridge.resetRuntimeCounters(); DvDecoders.openedAs = null
            return DolbyVisionExtractorsFactory(base, r.config, stripDvRpu = r.strip, stripHdr10PlusSei = r.stripHdr10Plus, injectHdr10Sei = r.injectHdr10)
        }

        /** The converter's native library is in this build and loads on this box. */
        fun converterAvailable(): Boolean = runCatching { DoviBridge.isAvailable() }.getOrDefault(false)
    }
}
