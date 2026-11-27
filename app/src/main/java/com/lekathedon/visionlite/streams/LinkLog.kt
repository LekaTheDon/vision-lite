package com.lekathedon.visionlite.streams

import com.lekathedon.visionlite.App
import java.io.File

/**
 * files/reports/links.log: what each link search did and when (each source's answer time, when autoplay settled,
 * its top picks, every try). Last 400 lines kept, so a slow open is measured next time (user 2026-11-27, The Killing).
 * Cost: a few lines per Play, written off the main thread.
 */
object LinkLog {
    private const val KEEP = 400
    private val lock = Any()

    fun add(line: String) {
        android.util.Log.i("AutoPick", line)
        val stamped = "%tF %<tT.%<tL | %s".format(System.currentTimeMillis(), line.replace("\n", " ⏎ "))
        Thread {
            synchronized(lock) {
                runCatching {
                    val f = File(App.instance.filesDir, "reports/links.log").apply { parentFile?.mkdirs() }
                    val lines = (if (f.isFile) f.readLines() else emptyList()) + stamped
                    f.writeText(lines.takeLast(KEEP).joinToString("\n") + "\n")
                }
            }
        }.start()
    }
}
