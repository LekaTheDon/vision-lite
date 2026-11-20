package com.lekathedon.visionlite.report

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Catches a crash before Android closes the app and keeps it in files/reports (the last 5), so the next start can
 * offer to send it (report/Problems.kt) and every report carries it. Then Android's own handler runs as usual.
 */
object Crash {
    private const val KEEP = 5

    fun dir(context: Context): File = File(context.filesDir, "reports").apply { mkdirs() }

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { write(app, thread, e) }
            if (previous != null) previous.uncaughtException(thread, e)
            else { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(10) }
        }
    }

    private fun write(context: Context, thread: Thread, e: Throwable) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val v = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val text = buildString {
            appendLine("Crash at ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date())}")
            appendLine("App ${v?.versionName} on ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            append(sw.toString().take(60_000))
        }
        val d = dir(context)
        File(d, "crash-${System.currentTimeMillis()}.txt").writeText(text)
        d.listFiles { f -> f.name.startsWith("crash-") }?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
    }

    /** Saved crashes, newest first. */
    fun saved(context: Context): List<File> =
        dir(context).listFiles { f -> f.name.startsWith("crash-") }?.sortedByDescending { it.name }.orEmpty()

    /** Time of a saved crash file (from its name). */
    fun timeOf(f: File): Long = f.name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull() ?: 0L
}
