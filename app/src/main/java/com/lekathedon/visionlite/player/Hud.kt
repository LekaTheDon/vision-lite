// The Stats panel's HDR / Dolby Vision rows (from Vision's PlaybackStatsDv): what the file is, what the box's
// decoders and Dolby Vision core are doing with it, and what leaves the HDMI port. Kernel files are read directly
// (no root needed), at most every 2 s, off the main thread.
package com.lekathedon.visionlite.player

import androidx.media3.common.C
import androidx.media3.common.Format
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object Hud {
    /** Snapshot of the box, refreshed in the background. */
    data class Box(
        val hdmi: String? = null,        // "DV std RGB 8-bit 1920x1080p24hz"
        val signal: String? = null,      // DV std / DV LL / HDR10 / HDR10+ / HLG / SDR
        val dvCore: Boolean? = null,     // the Dolby Vision core (VS10) is processing
        val dvInput: String? = null,     // what reached the DV core: DOVI / HDR10 / SDR / HLG
        val decoders: List<Pair<String, String>> = emptyList()  // device name to WxH
    )

    @Volatile var box = Box(); private set
    /** The video decoder ExoPlayer opened (MediaCodec name), or the FEL route. */
    @Volatile var decoder: String? = null
    @Volatile private var at = 0L
    private val busy = AtomicBoolean(false)
    private val reader = Executors.newSingleThreadExecutor { Thread(it, "hud").apply { isDaemon = true } }

    fun refresh() {
        val now = System.currentTimeMillis()
        if (now - at < 2000 || !busy.compareAndSet(false, true)) return
        reader.execute { try { box = read(); at = System.currentTimeMillis() } finally { busy.set(false) } }
    }

    private fun text(p: String) = runCatching { File(p).readText() }.getOrNull()

    private fun read(): Box {
        val cfg = text("/sys/class/amhdmitx/amhdmitx0/config")
        val vic = cfg?.let { Regex("VIC:\\s*\\d+\\s+(\\S+)").find(it)?.groupValues?.get(1) }
        val space = cfg?.let { Regex("Colourspace:\\s*(\\S+)").find(it)?.groupValues?.get(1) }?.let {
            when (it.uppercase(Locale.ROOT)) { "RGB" -> "RGB"; "YUV444" -> "4:4:4"; "YUV422" -> "4:2:2"; "YUV420" -> "4:2:0"; else -> it }
        }
        val depth = cfg?.let { Regex("Colour depth:\\s*(\\d+)-bit").find(it)?.groupValues?.get(1) }
        val signal = text("/sys/class/amhdmitx/amhdmitx0/hdmi_hdr_status")?.trim()?.let { s ->
            when {
                s.contains("DolbyVision", true) && s.contains("Low", true) -> "DV LL"
                s.contains("DolbyVision", true) -> "DV std"
                s.contains("HLG", true) -> "HLG"
                s.contains("Plus", true) -> "HDR10+"
                s.contains("ST2084", true) || s.contains("HDR10", true) -> "HDR10"
                s.equals("SDR", true) -> "SDR"
                else -> s
            }
        }
        val core = text("/sys/module/aml_media/parameters/dolby_vision_status")?.trim()?.toIntOrNull()?.let { it != 0 }
        val input = text("/sys/class/amdolby_vision/src_format")?.lineSequence()?.firstOrNull { it.startsWith("vd1") }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() }
        val decoders = text("/sys/class/vdec/vdec_status")?.split("vdec channel")?.drop(1)?.mapNotNull { ch ->
            val name = Regex("device name\\s*:\\s*(\\S+)").find(ch)?.groupValues?.get(1) ?: return@mapNotNull null
            val w = Regex("frame width\\s*:\\s*(\\d+)").find(ch)?.groupValues?.get(1)
            val h = Regex("frame height\\s*:\\s*(\\d+)").find(ch)?.groupValues?.get(1)
            name to if (w != null && h != null) "${w}×$h" else ""
        }.orEmpty()
        val hdmi = listOfNotNull(signal, space, depth?.let { "$it-bit" }, vic).joinToString(" ").takeIf { it.isNotBlank() }
        return Box(hdmi, signal, core, input, decoders)
    }

    /** "Dolby Vision", "HDR10", "HDR10+", "HLG" or "SDR" from the file's format. */
    fun source(v: Format?): String {
        val dv = v?.sampleMimeType == "video/dolby-vision" || v?.codecs?.lowercase()?.let { c -> listOf("dvhe", "dvh1", "dav1").any { c.contains(it) } } == true
        return when {
            dv -> "Dolby Vision"
            v?.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
            v?.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
            else -> "SDR"
        }
    }

    /** Profile and level from "dvhe.07.06" → 7 to 6. */
    fun dvProfile(v: Format?): Pair<Int, Int?>? {
        val m = Regex("(?:dvhe|dvh1|dav1|dvav)\\.(\\d+)(?:\\.(\\d+))?").find(v?.codecs?.lowercase() ?: return null) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toIntOrNull()
    }

    /** What the 8.1 converter really did this play (frames rewritten), so Stats never claims a conversion that didn't run. */
    fun converterRow(): String? {
        val p = com.lekathedon.visionlite.App.instance.prefs
        if (p.dv7.value != "dv81" && !p.dv5To81.value) return null
        val st = com.lekathedon.visionlite.player.dv.DolbyVisionConversionStats
        val n = com.lekathedon.visionlite.player.dv.DoviBridge.getConversionSuccessCount()
        val tried = com.lekathedon.visionlite.player.dv.DoviBridge.getConversionCallCount()
        val from = st.getLastSourceProfile()
        return "8.1 converter  " + when {
            !com.lekathedon.visionlite.player.dv.DvRoute.converterAvailable() -> "not loaded on this box"
            n > 0 -> "converting" + (from?.let { " profile $it" } ?: "") + " → 8.1 · $n of $tried frames" + (st.getLastSelectedConversionMode()?.let { " · mode $it" } ?: "")
            tried > 0 -> "FAILED · 0 of $tried frames converted"
            else -> "not used for this file"
        }
    }

    /** The rows: Dolby Vision (profile and route), FEL, HDR in → out with who converts, and the HDMI signal. */
    fun rows(v: Format?, decoderName: String?): List<String> {
        refresh()
        val b = box
        val src = source(v)
        val out = ArrayList<String>()
        val dv = dvProfile(v)
        val hevc = b.decoders.filter { it.first.contains("h265") || it.first.contains("hevc") || it.first.contains("dv", true) }
        val elRunning = hevc.size >= 2
        val felRoute = decoderName?.startsWith("amstream_dves") == true
        if (src == "Dolby Vision") {
            val name = "Profile ${dv?.first ?: "?"}" + (dv?.second?.let { ".$it" } ?: "")
            out += "Dolby Vision  " + when {
                dv?.first == 7 && (elRunning || felRoute) -> "$name · RPU + BL + EL (native FEL route)"
                dv?.first == 7 -> "$name · base layer + RPU (no enhancement layer)"
                else -> "$name · native"
            }
            converterRow()?.let { out += it }
            if (dv?.first == 7) out += "FEL  " + when {
                elRunning && b.dvInput.equals("DOVI", true) -> "running · EL ${hevc.drop(1).firstOrNull()?.second.orEmpty()} into the DV core"
                elRunning -> "EL decoding, DV core sees ${b.dvInput ?: "?"}"
                else -> "not running (one decoder: base layer only)"
            }
        }
        val engine = when {
            b.signal == "DV std" || b.signal == "DV LL" -> if (src == "Dolby Vision") null else "VS10"
            b.dvCore == true -> "VS10"
            b.dvCore == false -> "Amlogic"
            else -> null
        }
        val outName = when (b.signal) { "DV std" -> "Dolby Vision · TV-led"; "DV LL" -> "Dolby Vision · player-led"; else -> b.signal }
        val srcKind = if (src == "Dolby Vision") "DV" else src
        val sigKind = if (b.signal?.startsWith("DV") == true) "DV" else b.signal
        out += "HDR  " + if (outName == null || sigKind == srcKind) src else listOfNotNull("$src → $outName", engine).joinToString(" · ")
        b.dvInput?.let { out += "DV core input  $it" }
        b.hdmi?.let { out += "HDMI  $it" }
        out += "Sound  " + Sound.describe(com.lekathedon.visionlite.App.instance)
        DisplayMatch.status.value.takeIf { it.isNotEmpty() }?.let { out += "Frame rate  $it" }
        val decs = b.decoders.joinToString(" + ") { "${it.first} ${it.second}".trim() }
        out += "Decoder  " + (decoderName ?: "-") + if (decs.isNotEmpty()) "  ($decs)" else ""
        return out
    }
}
