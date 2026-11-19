package com.lekathedon.visionlite.link

import java.util.concurrent.TimeUnit

/** One root command at a time (Magisk grants once, forever: no popups). Null = no root or it failed. */
internal object Root {
    private val lock = Any()
    fun run(script: String, timeoutSec: Long = 8): String? = synchronized(lock) {
        runCatching {
            val p = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
            val out = StringBuilder()
            val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } }.apply { start() }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroy(); return@runCatching null }
            reader.join(500)
            if (p.exitValue() == 0) out.toString() else null
        }.getOrNull()
    }
    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
