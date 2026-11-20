package com.lekathedon.visionlite.report

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log

/**
 * One-line memory snapshots in the log (tag VLMem): app start, player start, Android's memory warnings. At most one
 * every 2 s, a few microseconds each; a report then shows how memory went before a problem (NuvioTV-Lite's NuvioMem).
 */
object Mem {
    private const val TAG = "VLMem"
    @Volatile private var last = 0L

    fun snapshot(context: Context, reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - last < 2_000) return
        last = now
        runCatching { Log.i(TAG, "[$reason] ${line(context)}") }
    }

    /** "pss=… dalvik=… native=… graphics=… heap=used/max sysAvail=… lowMem=…" */
    fun line(context: Context): String {
        val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val rt = Runtime.getRuntime()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val sys = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val gfx = mi.getMemoryStat("summary.graphics")?.toIntOrNull()?.div(1024) ?: -1
        return "pss=${mi.totalPss / 1024}MB dalvik=${mi.dalvikPss / 1024}MB native=${mi.nativePss / 1024}MB graphics=${gfx}MB " +
            "heap=${(rt.totalMemory() - rt.freeMemory()) / 1_048_576}/${rt.maxMemory() / 1_048_576}MB " +
            "sysAvail=${sys.availMem / 1_048_576}MB of ${sys.totalMem / 1_048_576}MB lowMem=${sys.lowMemory}"
    }
}
