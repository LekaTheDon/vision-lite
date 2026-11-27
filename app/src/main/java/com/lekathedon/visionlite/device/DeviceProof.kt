package com.lekathedon.visionlite.device

import android.content.Context
import com.lekathedon.visionlite.App

/**
 * What this device really did, not what it claims (user rule 2026-11-27: never over-promise; the TCL C7K was told
 * "Convert to 8.1" and played HDR10). Kept per device (not per profile), read by This TV's recommendations and by
 * DeviceFit when links are ranked.
 *  - Dolby Vision 8.1: proven when a converted film reached a Dolby Vision decoder, failed when it didn't.
 *  - Internet: the speed real playback got (median of the last plays), next to the one-off speed test.
 */
object DeviceProof {
    private val sp by lazy { App.instance.getSharedPreferences("vl_device_proof", Context.MODE_PRIVATE) }

    /** "ok", "fail" or "" (not tried yet). */
    val dv81: String get() = sp.getString("dv81", "").orEmpty()
    val dv81Why: String get() = sp.getString("dv81_why", "").orEmpty()

    fun dv81Result(ok: Boolean, why: String) {
        // One good play outweighs an earlier failure (a bad file); a failure after a success needs two in a row.
        val fails = if (ok) 0 else sp.getInt("dv81_fails", 0) + 1
        val v = when { ok -> "ok"; dv81 == "ok" && fails < 2 -> "ok"; else -> "fail" }
        sp.edit().putString("dv81", v).putString("dv81_why", why).putInt("dv81_fails", fails).apply()
        android.util.Log.i("DeviceProof", "8.1: ${if (ok) "worked" else "failed"} ($why) -> $v")
    }

    /** Download speed a play reached (ExoPlayer's estimate, Mb/s); the last 5 are kept. */
    fun playSpeed(mbps: Double) {
        if (mbps <= 0.5) return
        val list = (seen() + mbps).takeLast(5)
        sp.edit().putString("mbps_seen2", list.joinToString(",") { "%.1f".format(java.util.Locale.ROOT, it) }).apply()
    }

    private fun seen(): List<Double> = sp.getString("mbps_seen2", "").orEmpty().split(',').mapNotNull { it.toDoubleOrNull() }

    /** What plays that really stalled reached (median), null when none did: the only speed that rules links out. */
    fun provenMbps(): Double? = seen().sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }

    /** The internet speed to plan with: the lower of the speed test and what plays really got (median), null = unknown. */
    fun mbps(test: Double?): Double? {
        val s = seen().sorted()
        val median = if (s.size >= 2) s[s.size / 2] else null
        return listOfNotNull(test?.takeIf { it > 0 }, median).minOrNull()
    }
}
