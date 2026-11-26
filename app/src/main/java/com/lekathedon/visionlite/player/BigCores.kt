package com.lekathedon.visionlite.player

import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log

/**
 * Playback on the fastest cores (DebridStream's libds_sched idea, written for Lite; native part in cpp/vl_sched.cpp).
 * ExoPlayer runs on one shared playback thread of Lite's own, at audio priority; on a box whose cores differ
 * (big.LITTLE) that thread, the UI thread and the RenderThread (which draws the subtitle pictures) are pinned to the
 * big cores when a video starts. Boxes with equal cores (the AM9: 4 x 2.4 GHz) are left as they are.
 * Cost: one thread for the app's life; a few /sys reads per video.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object BigCores {
    private const val TAG = "BigCores"
    private val loaded = runCatching { System.loadLibrary("vl_sched") }.isSuccess

    @JvmStatic private external fun nativeBigCount(): Int
    @JvmStatic private external fun nativePin(tid: Int): Int

    /** How many fast cores the box has, 0 when all are the same (or unknown). */
    val bigCount: Int by lazy { if (loaded) runCatching { nativeBigCount() }.getOrDefault(0) else 0 }

    @Volatile private var playbackTid = 0

    /** The shared playback thread (ExoPlayer.Builder.setPlaybackLooper). */
    val playbackLooper: Looper by lazy {
        HandlerThread("VL:Playback", Process.THREAD_PRIORITY_AUDIO).apply { start() }.let { t ->
            android.os.Handler(t.looper).post { playbackTid = Process.myTid() }
            t.looper
        }
    }

    /** Pins the playback, UI and RenderThread threads to the big cores (nothing on equal-core boxes). */
    fun pinForPlayback() {
        if (bigCount <= 0) return
        val tids = HashSet<Int>()
        tids += Process.myPid()
        playbackTid.takeIf { it > 0 }?.let(tids::add)
        runCatching {
            java.io.File("/proc/self/task").listFiles()?.forEach { d ->
                val name = runCatching { java.io.File(d, "comm").readText().trim() }.getOrNull()
                if (name == "RenderThread" || name == "VL:Playback") d.name.toIntOrNull()?.let(tids::add)
            }
        }
        val ok = tids.count { runCatching { nativePin(it) }.getOrDefault(-1) == 1 }
        Log.i(TAG, "pinned $ok of ${tids.size} threads to $bigCount big cores")
    }
}
