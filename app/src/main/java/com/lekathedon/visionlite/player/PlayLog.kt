package com.lekathedon.visionlite.player

import com.lekathedon.visionlite.App
import java.io.File

/**
 * files/reports/playback.log: one line per ExoPlayer play when the player closes (title, release, engine reason,
 * decoder, libass mode, DV route, FEL, dropped frames, rebuffers, minutes watched); the last 60 kept. Read with root
 * when a stutter is reported. Cost: one small file write per play, off the main thread.
 */
object PlayLog {
    private const val KEEP = 60

    fun add(title: String, release: String, reason: String?, decoder: String?, ass: String?, dv: Boolean, fel: Boolean,
            dropped: Long, rebuffers: Int, posMs: Long) {
        val line = "%tF %<tT | %s | %s | %s | dec=%s | ass=%s | dvRoute=%s | fel=%s | dropped=%d | rebuffers=%d | %.1f min".format(
            System.currentTimeMillis(), title, release.take(120), reason ?: "-", decoder ?: "-", ass ?: "off", dv, fel, dropped, rebuffers, posMs / 60_000.0)
        Thread {
            runCatching {
                val f = File(App.instance.filesDir, "reports/playback.log").apply { parentFile?.mkdirs() }
                val lines = (if (f.isFile) f.readLines() else emptyList()) + line
                f.writeText(lines.takeLast(KEEP).joinToString("\n") + "\n")
            }
        }.start()
    }
}
