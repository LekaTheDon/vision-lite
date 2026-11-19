package com.lekathedon.visionlite.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Match frame rate / resolution: Vision's proven mode picker (FrameRateUtils.selectDisplayMode), ported as is.
 * Frame rate first (exact, doubled, clean multiple, 3:2 pulldown, closest cadence); with resolution matching the
 * smallest TV mode that fits the video (never below 720p) that also shows the rate.
 */
object DisplayMatch {
    private const val TAG = "DisplayMatch"
    private const val NTSC_FILM = 24000f / 1001f
    private val CLEAN_MULTIPLES = listOf(3f, 4f, 5f, 6f)
    private var originalModeId: Int? = null

    private data class M(val id: Int, val w: Int, val h: Int, val hz: Float)

    fun activityOf(context: Context): Activity? {
        var c: Context? = context
        while (c != null) { if (c is Activity) return c; c = (c as? ContextWrapper)?.baseContext }
        return null
    }

    fun snap(fps: Float): Float = when (fps) {
        in 23.90f..23.988f -> NTSC_FILM
        in 23.988f..24.1f -> 24f
        in 24.9f..25.1f -> 25f
        in 29.90f..29.985f -> 30000f / 1001f
        in 29.985f..30.1f -> 30f
        in 49.9f..50.1f -> 50f
        in 59.9f..59.97f -> 60000f / 1001f
        in 59.97f..60.1f -> 60f
        else -> fps
    }

    private fun matches(hz: Float, target: Float) = abs(hz - target) <= max(0.01f, target * 0.003f)
    private fun best(modes: List<M>, target: Float): M? =
        if (target <= 0f) null else modes.minByOrNull { abs(it.hz - target) }?.takeIf { matches(it.hz, target) }
    private fun err(hz: Float, target: Float) = abs(hz - target) / target

    private fun weight(hz: Float, fps: Float): Float {
        val div = hz / fps
        if (div < 0.5f) return (fps - hz) / fps
        var w = floatArrayOf(1f, 2f, 2.5f, 3f, 4f, 5f, 6f).minOf { abs(div / it - 1f) }
        val cinemaOrNtsc = fps in 23.5f..24.5f || fps in 29.5f..30.5f || fps in 59.5f..60.5f
        if (cinemaOrNtsc && (abs(hz - 25f) <= 0.1f || abs(hz - 50f) <= 0.1f)) w += 0.5f
        if (hz > 60f && div > 1f) w += div / 10000f
        return w
    }

    private fun forRate(active: M, modes: List<M>, fps: Float): M {
        val pulldown = best(modes, fps * 2.5f)
        val pulldownErr = pulldown?.let { err(it.hz, fps * 2.5f) } ?: Float.MAX_VALUE
        val multiple = CLEAN_MULTIPLES.firstNotNullOfOrNull { k -> best(modes, fps * k)?.takeIf { err(it.hz, fps * k) <= pulldownErr + 0.0005f } }
        return best(modes, fps) ?: best(modes, fps * 2f) ?: multiple ?: pulldown ?: modes.minByOrNull { weight(it.hz, fps) } ?: active
    }

    private fun select(modes: List<M>, active: M, fps: Float, vw: Int, vh: Int, resolution: Boolean): M? {
        if (resolution && vw > 0 && vh > 0) {
            val tw = max(vw, vh); val th = min(vw, vh)
            fun area(m: M) = max(m.w, m.h).toLong() * min(m.w, m.h)
            val eligible = modes.filter { min(it.w, it.h) >= 720 }.ifEmpty { modes }
            val fitting = eligible.filter { max(it.w, it.h) >= tw && min(it.w, it.h) >= th }
            val groups = if (fitting.isNotEmpty()) fitting.groupBy { area(it) }.toSortedMap().values.toList()
                else eligible.maxOf { area(it) }.let { top -> listOf(eligible.filter { area(it) == top }) }
            if (groups.isEmpty()) return null
            if (fps <= 0f) return groups.first().minByOrNull { abs(it.hz - active.hz) }
            for (g in groups) (best(g, fps) ?: best(g, fps * 2f))?.let { return it }
            return forRate(active, groups.first(), fps)
        }
        if (fps <= 0f) return null
        val same = modes.filter { it.w == active.w && it.h == active.h }
        if (same.size <= 1) return null
        return forRate(active, same, fps)
    }

    /** Switches the TV for this video. [fps] ≤ 0 = unknown (only resolution can change). Main thread. */
    fun apply(context: Context, fps: Float, width: Int, height: Int, rate: Boolean, resolution: Boolean) {
        if (!rate && !resolution) return
        val activity = activityOf(context) ?: return
        val window = activity.window ?: return
        val display = window.decorView.display ?: return
        val cur = display.mode
        val modes = display.supportedModes.map { M(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val active = M(cur.modeId, cur.physicalWidth, cur.physicalHeight, cur.refreshRate)
        val pick = select(if (rate) modes else modes.filter { matches(it.hz, active.hz) }.ifEmpty { modes },
            active, if (rate) snap(fps) else 0f, width, height, resolution) ?: return
        Log.i(TAG, "video ${width}x$height ${fps}fps: ${active.w}x${active.h}@${active.hz} -> ${pick.w}x${pick.h}@${pick.hz}")
        if (pick.id == active.id) return
        if (originalModeId == null) originalModeId = active.id
        val set = { window.attributes = window.attributes.also { it.preferredDisplayModeId = pick.id } }
        // A TV with QMS: Amlogic switches 50 / 59.94 / 60 "seamlessly" (HWC: seamless:1 enableVrr:1), the HDMI timing stays
        // 60 Hz and the TV keeps showing 60 (seen 2026-11-19 on the AM9 + TCL). With root, QMS goes off first (once per
        // run), so every switch is a real HDMI mode change. Without root nothing changes.
        val grouped = android.os.Build.VERSION.SDK_INT >= 31 && (cur.alternativeRefreshRates.isNotEmpty() ||
            display.supportedModes.firstOrNull { it.modeId == pick.id }?.alternativeRefreshRates?.isNotEmpty() == true)
        if (grouped && !qmsTried && java.io.File(MESON_CLIENT).exists()) {
            qmsTried = true
            Thread {
                val out = com.lekathedon.visionlite.link.Root.run("$MESON_CLIENT -v true", 4)
                Log.i(TAG, "QMS off: ${if (out != null) "done" else "no root"}")
                activity.runOnUiThread { set() }
            }.start()
        } else set()
    }

    private const val MESON_CLIENT = "/vendor/bin/meson_display_client"
    @Volatile private var qmsTried = false

    /** Back to the mode the TV had before the film. */
    fun restore(context: Context) {
        val window = activityOf(context)?.window ?: return
        val id = originalModeId ?: return
        originalModeId = null
        window.attributes = window.attributes.also { it.preferredDisplayModeId = id }
    }
}
