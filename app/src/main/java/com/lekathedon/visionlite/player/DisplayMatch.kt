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
        set()
        // Android's request alone isn't enough on every box (AM9 + TCL: Android says 50 Hz, HDMI stays at 60). So the HDMI
        // output is measured and, when it didn't change, the real mode is written straight to the box (root).
        if (java.io.File("$TX/disp_mode").exists()) Thread { verify(pick, gen) }.start()
    }

    /** Measured, not assumed: what the HDMI output really sends; when it isn't the picked mode, force it (root). */
    private fun verify(pick: M, gen: Int) {
        fun stale() = gen != generation
        Thread.sleep(1200); if (stale()) return
        val real = hdmi() ?: run { status.value = "TV at %s Hz (Android's word; can't read the HDMI output here)".format(fmt(pick.hz)); return }
        if (matches(real.second, pick.hz) && real.first.contains("${min(pick.w, pick.h)}p")) {
            status.value = "✓ TV gets %s Hz (measured on HDMI)".format(fmt(real.second)); Log.i(TAG, "verified ${real.first}"); return }
        if (hdmiBack == null) hdmiBack = sysMode()
        val (name, frac) = modeName(pick.w, pick.h, pick.hz)
        Log.i(TAG, "HDMI sends ${real.first} but ${pick.w}x${pick.h}@${pick.hz} was asked: writing $name frac=$frac")
        status.value = "HDMI was still %s Hz: switching to %s…".format(fmt(real.second), name)
        force(name, frac)
        Thread.sleep(1500); if (stale()) return
        val now = hdmi()
        val ok = now != null && matches(now.second, pick.hz)
        Log.i(TAG, "after forced switch: ${now?.first} ok=$ok")
        status.value = if (ok) "✓ TV gets %s Hz (measured on HDMI)".format(fmt(now!!.second))
            else "✗ TV still gets %s Hz: the box refused %s".format(now?.let { fmt(it.second) } ?: "?", name)
    }

    /** Amlogic mode name for a TV mode ("2160p50hz", frac 0; 59.94 = "…p60hz" + frac 1). */
    private fun modeName(w: Int, h: Int, hz: Float): Pair<String, Int> {
        val n = hz.roundToInt()
        val frac = abs(hz - n) > 0.01f
        val r = if (frac) (hz * 1001f / 1000f).roundToInt() else n
        return (if (max(w, h) == 4096) "smpte${r}hz" else "${min(w, h)}p${r}hz") to (if (frac) 1 else 0)
    }

    /** The kernel ignores mode writes unless display/debug is on ("vout: enable display/debug to set voutmode"). */
    private fun force(name: String, frac: Int) {
        com.lekathedon.visionlite.link.Root.run("echo $frac > $TX/frac_rate_policy; echo 1 > $SYS_DIR/debug; " +
            "echo $name > $SYS_DIR/mode; echo 0 > $SYS_DIR/debug; true", 5)
    }

    /** The box's current output mode + frac policy ("2160p60hz" to 0). */
    private fun sysMode(): Pair<String, Int>? = runCatching {
        java.io.File("$SYS_DIR/mode").readText().trim() to java.io.File("$TX/frac_rate_policy").readText().trim().toInt() }.getOrNull()
        ?.takeIf { it.first.isNotEmpty() }

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
    private const val TX = "/sys/class/amhdmitx/amhdmitx0"
    /** The HDMI mode before a forced switch, put back after the film. */
    @Volatile private var hdmiBack: Pair<String, Int>? = null
    private const val SYS_DIR = "/sys/class/display"

    /** Back to the mode the TV had before the film. */
    fun restore(context: Context) {
        generation++
        status.value = ""
        hdmiBack?.let { (m, frac) -> hdmiBack = null; Thread { Thread.sleep(300); force(m, frac) }.start() }
        val window = activityOf(context)?.window ?: return
        val id = originalModeId ?: return
        originalModeId = null
        window.attributes = window.attributes.also { it.preferredDisplayModeId = id }
    }
}
