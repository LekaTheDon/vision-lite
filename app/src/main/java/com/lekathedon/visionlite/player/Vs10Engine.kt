// Vision's core/runtime/Vs10Engine.kt, unchanged but for the package.
package com.lekathedon.visionlite.player

import android.content.Context
import java.util.concurrent.TimeUnit

/**
 * VS10 Engine (com.am9fel.vs10, AM9 Pro with the FEL kernel): its root service converts SDR and HDR10 video
 * through the Dolby Vision chip, and mpv's picture too. The player's VS10 button asks that service to step the
 * setting for what is playing now (SDR, HDR10 or mpv) to its next choice; the service applies it within half a
 * second. Nothing runs unless the app is installed and the button is pressed.
 */
object Vs10Engine {
    private const val PACKAGE = "com.am9fel.vs10"
    private const val SERVICE = "/data/adb/service.d/vs10_engine.sh"

    enum class Source { SDR, HDR10, MPV }
    enum class Mode { OFF, HDR10, DV }

    /** [source] null: nothing convertible plays; [unconverted] then names what does (e.g. "DV"). */
    data class Step(val source: Source?, val mode: Mode?, val unconverted: String? = null)

    fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0); true }.getOrDefault(false)

    /**
     * Blocking (runs su), at most 10 s. Null when root is refused, su hangs (e.g. an unanswered grant prompt) or the
     * service isn't there. The output is one short line, so it is read after the process ends.
     */
    fun cycle(): Step? {
        val process = runCatching {
            ProcessBuilder("su", "-c", "sh $SERVICE cycle").redirectErrorStream(true).start()
        }.getOrNull() ?: return null
        return try {
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) return null
            val output = process.inputStream.bufferedReader().readText()
            parse(output.trim().lines().lastOrNull().orEmpty())
        } catch (e: Exception) {
            null
        } finally {
            process.destroy()
        }
    }

    /** The service prints "<sdr|hdr10|mpv> <off|hdr10|dv>", or "none <source>". */
    internal fun parse(line: String): Step? {
        val parts = line.trim().split(' ')
        if (parts.size != 2) return null
        if (parts[0] == "none") return Step(null, null, parts[1])
        val source = when (parts[0]) {
            "sdr" -> Source.SDR
            "hdr10" -> Source.HDR10
            "mpv" -> Source.MPV
            else -> return null
        }
        val mode = when (parts[1]) {
            "off" -> Mode.OFF
            "hdr10" -> Mode.HDR10
            "dv" -> Mode.DV
            else -> return null
        }
        return Step(source, mode)
    }
}
