package com.lekathedon.visionlite.report

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.lekathedon.visionlite.App

/**
 * Did the app end badly last time? A saved crash, or Android's own exit record (Android 11+): crash, native crash,
 * froze (ANR), or closed for memory while on screen. The next start shows one prompt to send a report; it's read
 * once at start (one binder call, a few ms).
 */
object Problems {
    data class Problem(val kind: String, val title: String, val at: Long)

    fun lastUnseen(context: Context): Problem? {
        val seen = App.instance.prefs.exitSeen.value.toLongOrNull() ?: 0L
        // First start with reports: older exits (other versions, installs) aren't news.
        if (seen == 0L) { markSeen(); return null }
        return find(context, seen)
    }

    private fun find(context: Context, seen: Long): Problem? = runCatching {
        val found = mutableListOf<Problem>()
        Crash.saved(context).map { Crash.timeOf(it) }.filter { it > seen }.maxOrNull()?.let { found += Problem("crash", "Vision Lite crashed", it) }
        if (Build.VERSION.SDK_INT >= 30) exits(context).filter { it.timestamp > seen }.forEach { e -> problemOf(e)?.let { found += it } }
        found.maxByOrNull { it.at }
    }.getOrNull()

    fun markSeen() { App.instance.prefs.exitSeen.value = System.currentTimeMillis().toString() }

    fun exits(context: Context, max: Int = 8): List<ApplicationExitInfo> =
        if (Build.VERSION.SDK_INT < 30) emptyList()
        else runCatching {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getHistoricalProcessExitReasons(context.packageName, 0, max)
        }.getOrDefault(emptyList())

    private fun problemOf(e: ApplicationExitInfo): Problem? {
        if (Build.VERSION.SDK_INT < 30) return null
        val onScreen = e.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        return when (e.reason) {
            ApplicationExitInfo.REASON_CRASH -> Problem("crash", "Vision Lite crashed", e.timestamp)
            ApplicationExitInfo.REASON_CRASH_NATIVE -> Problem("native", "Vision Lite crashed (in the player or a native part)", e.timestamp)
            ApplicationExitInfo.REASON_ANR -> Problem("anr", "Vision Lite froze and Android closed it", e.timestamp)
            ApplicationExitInfo.REASON_LOW_MEMORY -> if (onScreen) Problem("memory", "Android closed Vision Lite: the box ran out of memory", e.timestamp) else null
            ApplicationExitInfo.REASON_SIGNALED -> if (onScreen) Problem("killed", "Android closed Vision Lite while it was on screen", e.timestamp) else null
            else -> null
        }
    }

    fun reasonName(r: Int): String = when (r) {
        1 -> "EXIT_SELF"; 2 -> "SIGNALED"; 3 -> "LOW_MEMORY"; 4 -> "CRASH"; 5 -> "CRASH_NATIVE"; 6 -> "ANR"; 7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"; 9 -> "EXCESSIVE_RESOURCE_USAGE"; 10 -> "USER_REQUESTED"; 11 -> "USER_STOPPED"; 12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"; 14 -> "FREEZER"; 15 -> "PACKAGE_STATE_CHANGE"; 16 -> "PACKAGE_UPDATED"; else -> "UNKNOWN($r)"
    }
}
