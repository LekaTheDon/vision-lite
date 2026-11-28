package com.lekathedon.visionlite.link

import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * Root through ONE long-lived `su` shell: Magisk sees a single request per app run, so no "granted Superuser" toast per
 * command (each new `su` showed one). Commands run one at a time; output ends at a marker line with the exit code.
 * Null = no root, failed, or timed out (the shell is then dropped and a new one opened next time).
 */
internal object Root {
    private val lock = Any()
    private var proc: Process? = null
    private var input: OutputStreamWriter? = null
    private var output: BufferedReader? = null

    private fun open(): Boolean {
        // Shared builds never ask for root: BuildConfig.DEV is a constant, so R8 drops the su call from them.
        if (!com.lekathedon.visionlite.BuildConfig.DEV) return false
        if (proc?.isAlive == true) return true
        close()
        return runCatching {
            val p = ProcessBuilder("su").redirectErrorStream(true).start()
            proc = p; input = p.outputStream.writer(); output = p.inputStream.bufferedReader()
            true
        }.getOrDefault(false)
    }

    private fun close() {
        runCatching { proc?.destroy() }
        proc = null; input = null; output = null
    }

    fun run(script: String, timeoutSec: Long = 8): String? = synchronized(lock) {
        if (!open()) return null
        val marker = "__VL_END_${System.nanoTime()}__"
        val out = StringBuilder()
        var code: Int? = null
        val reader = Thread {
            runCatching {
                while (true) {
                    val line = output?.readLine() ?: break
                    val i = line.indexOf(marker)
                    if (i >= 0) { if (i > 0) out.appendLine(line.substring(0, i)); code = line.substring(i + marker.length).trim().toIntOrNull(); break }
                    out.appendLine(line)
                }
            }
        }
        runCatching {
            reader.start()
            // Subshell so `exit` or `cd` inside a script can't end or change the shared shell.
            input!!.write("( $script ) </dev/null 2>&1; echo \"$marker\$?\"\n"); input!!.flush()
            reader.join(TimeUnit.SECONDS.toMillis(timeoutSec))
        }
        if (reader.isAlive || code == null) { close(); return null }
        if (code == 0) out.toString() else null
    }

    /** Root works on this device (no su = false at once; a granted su = the shared shell, no new toast). */
    fun granted(): Boolean = run("true", 3) != null

    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
