// Quiet playback (old Vision's PlaybackQuietMode, user 2026-11-24). Settings › Player, off by default (dormant: off
// = no command, no lookup). 4 s after a title starts playing (a quick look at a link stops nothing):
//  - Lite drops its poster memory cache;
//  - without root (any device): Android's own "close background processes" for the launchable apps (the normal
//    KILL_BACKGROUND_PROCESSES permission, no prompt);
//  - with root, only if the shared root shell is already granted: running apps are force-stopped too, except the
//    launcher, keyboard, accessibility services, Magisk, VS10 Engine, Termux, Vision Link and the box's system parts.
// Nothing is restarted when the film ends. Cost: one pass per play (~100-300 ms on a background thread).
package com.lekathedon.visionlite.player

import android.content.Context
import android.content.Intent
import android.util.Log
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.link.Root
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object QuietPlayback {
    private const val TAG = "QuietPlayback"
    private const val SETTLE_MS = 4_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** The player opened: quiet the box after it settles (if on). */
    fun playing(context: Context) {
        if (!App.instance.prefs.quietPlayback.value) return
        job?.cancel()
        val app = context.applicationContext
        job = scope.launch { delay(SETTLE_MS); quiet(app) }
    }

    /** The player closed before it settled: do nothing. */
    fun closed() { job?.cancel(); job = null }

    private fun quiet(context: Context) {
        val t = android.os.SystemClock.elapsedRealtime()
        runCatching { coil3.SingletonImageLoader.get(context).memoryCache?.clear() }
        val stopped = if (Root.granted()) runCatching { Root.run(script(context.packageName), 20) }.getOrNull()?.trim()?.lines()?.lastOrNull() else null
        if (stopped == null) runCatching { withoutRoot(context) }
        Log.i(TAG, "quiet in ${android.os.SystemClock.elapsedRealtime() - t} ms; root stopped=${stopped ?: "-"}")
    }

    private fun script(self: String) = """
        keep=" $self com.topjohnwu.magisk com.am9fel.vs10 app.vision.link com.lekathedon.visionlink "
        home=${'$'}(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1 | cut -d/ -f1)
        ime=${'$'}(settings get secure default_input_method | cut -d/ -f1)
        acc=${'$'}(settings get secure enabled_accessibility_services | tr ':' '\n' | cut -d/ -f1 | tr '\n' ' ')
        keep="${'$'}keep ${'$'}home ${'$'}ime ${'$'}acc "
        running=" ${'$'}(ps -A -o NAME | cut -d: -f1 | sort -u | tr '\n' ' ') "
        stopped=""
        for p in ${'$'}(pm list packages -3 | cut -d: -f2); do
          case "${'$'}keep" in *" ${'$'}p "*) continue;; esac
          case "${'$'}p" in com.termux*|*ugoos*|*droidlogic*) continue;; esac
          case "${'$'}running" in *" ${'$'}p "*) ;; *) continue;; esac
          am force-stop "${'$'}p" && stopped="${'$'}stopped ${'$'}p"
        done
        am kill-all
        echo "${'$'}stopped"
    """.trimIndent()

    private fun withoutRoot(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val pm = context.packageManager
        val keep = setOfNotNull(context.packageName,
            pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName,
            android.provider.Settings.Secure.getString(context.contentResolver, "default_input_method")?.substringBefore('/'))
        listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)
            .flatMap { c -> pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(c), 0) }
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filter { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName !in keep }
            .map { it.packageName }.distinct()
            .forEach { runCatching { am.killBackgroundProcesses(it) } }
    }
}
