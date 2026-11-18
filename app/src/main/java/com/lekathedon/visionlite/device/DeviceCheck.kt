package com.lekathedon.visionlite.device

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.view.Display
import android.view.WindowManager
import com.lekathedon.visionlite.data.Prefs
import com.lekathedon.visionlite.player.fel.AmlFelSupport

/** One device setting: only the options this box/TV can do, one of them Recommended. */
data class Choice(val key: String, val title: String, val options: List<Pair<String, String>>, val recommended: String, val why: String) {
    fun label(id: String) = options.firstOrNull { it.first == id }?.second ?: id
    fun next(id: String): String { val i = options.indexOfFirst { it.first == id }; return options[(i + 1).mod(options.size)].first }
}

data class Findings(val lines: List<String>, val choices: List<Choice>)

/** Reads the box, TV and soundbar; suggests, never applies by itself. */
object DeviceCheck {
    fun run(context: Context): Findings {
        val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        @Suppress("DEPRECATION")
        val hdr = display.hdrCapabilities?.supportedHdrTypes?.toSet().orEmpty()
        val dvTv = Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in hdr
        val hdrNames = buildList {
            if (dvTv) add("Dolby Vision")
            if (Display.HdrCapabilities.HDR_TYPE_HDR10 in hdr) add("HDR10")
            if (Build.VERSION.SDK_INT >= 29 && Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in hdr) add("HDR10+")
            if (Display.HdrCapabilities.HDR_TYPE_HLG in hdr) add("HLG")
        }
        val fel = AmlFelSupport.isDeviceUsable() && AmlFelSupport.hasFelKernelMarkers()
        val mode = display.mode
        val rates = display.supportedModes.filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }
            .map { it.refreshRate }.distinctBy { (it * 100).toInt() }.sorted()
        val sound = soundbarFormats(context)

        val lines = listOf(
            "TV picture: " + (hdrNames.joinToString(", ").ifEmpty { "SDR only" }),
            "TV mode: ${mode.physicalWidth}x${mode.physicalHeight}, refresh " + rates.joinToString(" / ") { "%.3f".format(it).trimEnd('0').trimEnd('.') } + " Hz",
            "Box: " + if (fel) "Dolby Vision FEL kernel (both layers decoded)" else "no native FEL (box decoder plays DV7)",
            "Sound: " + (sound.joinToString(", ").ifEmpty { "stereo PCM only" }) + " — passed through as the soundbar reports"
        )

        val choices = buildList {
            val dvOptions = buildList {
                if (fel && (dvTv || hdr.isEmpty())) add("fel" to "Native FEL (full picture)")
                add("box" to "Box decoder")
            }
            if (dvOptions.size > 1) add(Choice("dv7", "Dolby Vision 7 (disc remuxes)", dvOptions, "fel", "This box decodes both layers; your TV takes Dolby Vision"))
            if (rates.size > 1) add(Choice("afr", "Match frame rate", listOf("off" to "Off", "seamless" to "Only without a blink", "always" to "Always (TV switches)"),
                "always", "24p films play without judder; the TV blanks for a second at the start"))
        }
        return Findings(lines, choices)
    }

    fun current(prefs: Prefs, key: String): String = when (key) { "dv7" -> prefs.dv7.value; "afr" -> prefs.afr.value; else -> "" }

    fun apply(prefs: Prefs, picks: Map<String, String>) {
        picks["dv7"]?.let { prefs.dv7.value = it }
        picks["afr"]?.let { prefs.afr.value = it }
        prefs.deviceAsked.value = true
    }

    private fun soundbarFormats(context: Context): List<String> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val enc = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_HDMI || it.type == AudioDeviceInfo.TYPE_HDMI_ARC || (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_HDMI_EARC) }
            .flatMap { it.encodings.toList() }.toSet()
        return buildList {
            if (AudioFormat.ENCODING_AC3 in enc) add("Dolby Digital")
            if (AudioFormat.ENCODING_E_AC3 in enc) add("Dolby Digital+")
            if (Build.VERSION.SDK_INT >= 29 && AudioFormat.ENCODING_E_AC3_JOC in enc) add("Atmos (DD+)")
            if (AudioFormat.ENCODING_DOLBY_TRUEHD in enc) add("TrueHD")
            if (AudioFormat.ENCODING_DTS in enc) add("DTS")
            if (AudioFormat.ENCODING_DTS_HD in enc) add("DTS-HD")
        }
    }
}
