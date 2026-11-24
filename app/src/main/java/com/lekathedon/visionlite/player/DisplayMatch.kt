package com.lekathedon.visionlite.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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

    private val STANDARD = listOf(NTSC_FILM, 24f, 25f, 30000f / 1001f, 30f, 48f, 50f, 60000f / 1001f, 60f, 100f, 120000f / 1001f, 120f)
    /** A measured rate as a real video rate (nearest standard within 2%), or null when it is noise. */
    fun standard(fps: Float): Float? = STANDARD.minByOrNull { abs(it - fps) }?.takeIf { abs(it - fps) / it <= 0.02f }

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
        val gen = ++generation
        status.value = "Video %s fps → asking for %d×%d @ %s Hz".format(fmt(snap(fps)), pick.w, pick.h, fmt(pick.hz))
        if (pick.id != active.id && originalModeId == null) originalModeId = active.id
        val set = { if (gen == generation && pick.id != active.id) window.attributes = window.attributes.also { it.preferredDisplayModeId = pick.id } }
        // QMS off BEFORE every switch (HWC turns it on again after a TV hotplug): with QMS on, Amlogic switches 50 / 59.94 / 60
        // "seamlessly" and the TV stays at 60 (AM9 + TCL). Switching first and fixing after didn't work: once Android's books
        // say 50 Hz nothing re-sends the mode. Then measured on HDMI. Without root: Android's way only.
        if (rate && java.io.File(MESON_CLIENT).exists()) Thread {
            com.lekathedon.visionlite.link.Root.run("$MESON_CLIENT -v true >/dev/null 2>&1; true", 4)
            activity.runOnUiThread(set)
            verify(activity, pick, active, gen)
        }.start() else set()
    }

    /**
     * Measured, not assumed: 2.5 s after the request, read what the HDMI output really sends (Amlogic, root). Android's
     * own mode can say 50 Hz while QMS keeps the TV at 60 (AM9 + TCL, 2026-11-24: "seamless", rate hint 6000, VRR none).
     * Then QMS goes off and the switch is redone as a real HDMI mode change, checked again. Without root or on other
     * chips, Android's mode is all there is to read.
     */
    private fun verify(activity: Activity, pick: M, active: M, gen: Int) {
        fun stale() = gen != generation
        Thread.sleep(2500); if (stale()) return
        val real = hdmi() ?: run { status.value = "TV at %s Hz (Android's word; can't read the HDMI output here)".format(fmt(pick.hz)); return }
        if (matches(real.second, pick.hz)) { status.value = "✓ TV gets %s Hz (measured on HDMI)".format(fmt(real.second)); Log.i(TAG, "verified ${real.first}"); return }
        Log.i(TAG, "HDMI sends ${real.first} but ${pick.hz} was asked: QMS off, real switch")
        status.value = "HDMI was still %s Hz: switching for real…".format(fmt(real.second))
        com.lekathedon.visionlite.link.Root.run("$MESON_CLIENT -v true >/dev/null 2>&1; true", 4)
        // Android again, from a different mode, so it really re-sends one (QMS is off now).
        val bounce = activity.window?.decorView?.display?.supportedModes?.firstOrNull { it.modeId != pick.id &&
            it.physicalWidth == pick.w && it.physicalHeight == pick.h && !matches(it.refreshRate, pick.hz) }?.modeId ?: active.id
        if (bounce != pick.id) {
            activity.runOnUiThread { if (!stale()) activity.window.attributes = activity.window.attributes.also { it.preferredDisplayModeId = bounce } }
            Thread.sleep(1500); if (stale()) return
            activity.runOnUiThread { if (!stale()) activity.window.attributes = activity.window.attributes.also { it.preferredDisplayModeId = pick.id } }
            Thread.sleep(2500); if (stale()) return
            hdmi()?.takeIf { matches(it.second, pick.hz) }?.let { status.value = "✓ TV gets %s Hz (measured on HDMI, QMS bypassed)".format(fmt(it.second)); Log.i(TAG, "verified after re-switch ${it.first}"); return }
        }
        if (hdmiBack == null && originalModeId == null) hdmiBack = real.first
        val spec = "${pick.w}x${pick.h}@${pick.hz.roundToInt()}"
        // Through the box's own display service (HWC stays in step), the raw HDMI mode only if that didn't take.
        com.lekathedon.visionlite.link.Root.run("$MESON_CLIENT -c $spec >/dev/null 2>&1; true", 6)
        Thread.sleep(2000); if (stale()) return
        var now = hdmi()
        if (now != null && !matches(now.second, pick.hz)) {
            com.lekathedon.visionlite.link.Root.run("echo ${min(pick.w, pick.h)}p${pick.hz.roundToInt()}hz > $SYS_MODE", 4)
            Thread.sleep(2000); if (stale()) return
            now = hdmi()
        }
        val ok = now != null && matches(now.second, pick.hz)
        Log.i(TAG, "after real switch: ${now?.first} ok=$ok")
        status.value = if (ok) "✓ TV gets %s Hz (measured on HDMI, QMS bypassed)".format(fmt(now!!.second))
            else "✗ TV still gets %s Hz: the box refused %s".format(now?.let { fmt(it.second) } ?: "?", spec)
    }

    /** The HDMI output's real mode name and rate ("3840x2160p60hz", 60.0), 59.94 etc. from the fractional policy. */
    private fun hdmi(): Pair<String, Float>? {
        // Any app can read these on Amlogic boxes (no root); root only if this box locks them.
        val out = runCatching { java.io.File("$TX/disp_mode").readLines().filter { it.startsWith("name:") }.joinToString("\n") + "\n" + java.io.File("$TX/frac_rate_policy").readText().trim() }
            .getOrNull()?.takeIf { it.contains("name:") }
            ?: com.lekathedon.visionlite.link.Root.run("grep '^name:' $TX/disp_mode; cat $TX/frac_rate_policy", 3) ?: return null
        val lines = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val name = lines.firstOrNull { it.startsWith("name:") }?.substringAfter(':')?.trim() ?: return null
        val n = Regex("""p(\d+)hz""").find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: return null
        val frac = lines.lastOrNull() == "1" && n.toInt() in setOf(24, 30, 48, 60, 120)
        return name to if (frac) n * 1000f / 1001f else n
    }

    private fun fmt(hz: Float) = "%.3f".format(hz).trimEnd('0').trimEnd('.')

    /** What the last switch did, measured (shown in the player's stats). */
    val status = kotlinx.coroutines.flow.MutableStateFlow("")
    @Volatile private var generation = 0
    private const val MESON_CLIENT = "/vendor/bin/meson_display_client"
    private const val TX = "/sys/class/amhdmitx/amhdmitx0"
    /** The HDMI mode before a switch made behind Android's books, put back after the film. */
    @Volatile private var hdmiBack: String? = null
    private const val SYS_MODE = "/sys/class/display/mode"

    /** Back to the mode the TV had before the film. */
    fun restore(context: Context) {
        generation++
        hdmiBack?.let { m -> hdmiBack = null
            val spec = Regex("""(\d+)x(\d+)p(\d+)hz""").find(m)?.destructured?.let { (w, h, r) -> "${w}x$h@$r" }
            if (spec != null) Thread { com.lekathedon.visionlite.link.Root.run("$MESON_CLIENT -c $spec >/dev/null 2>&1; true", 6) }.start() }
        val window = activityOf(context)?.window ?: return
        val id = originalModeId ?: return
        originalModeId = null
        window.attributes = window.attributes.also { it.preferredDisplayModeId = id }
    }
}
