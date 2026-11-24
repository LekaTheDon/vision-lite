package com.lekathedon.visionlite.iptv

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.lekathedon.visionlite.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * TiviMate's model (its database and settings read on the box, 2026-11-24): the subscription downloads once in full;
 * after that, quiet updates every N hours change only what changed (same channel list = not rewritten; guide asked
 * with ETag / Last-Modified, unchanged = not downloaded), past guide days kept, nothing updates because the app opened
 * unless "Update on app start" is on.
 *
 * IPTV always ready (user 2026-11-24: "open the app at any moment and find everything there", like TiviMate).
 * Channels, guide and VOD live in iptv.db / vod.db and are shown from there at once; they are never rebuilt because a
 * page opened or closed. Updates run here, outside any page, so leaving Live TV or VOD can't cancel one half-way:
 *  - every day at 05:00 (an alarm wakes a short foreground service; skipped while something plays, tried again later);
 *  - when Live TV, VOD or Sports opens and a playlist is over a day old (in the background, old data stays on screen);
 *  - Refresh, by hand.
 * After VOD loads, the first 40 titles of every category are matched to TMDB (saved for good) and the first posters
 * of the top rows are put in the image cache, so VOD opens with its posters. Nothing runs without a playlist.
 * Cost: no memory while idle; the nightly run is the provider download plus ~40 TMDB searches per new category.
 */
object IptvSync {
    private const val TAG = "IptvSync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> get() = _running

    /** Starts an update when one is due (or [force]); returns at once. Old data stays readable meanwhile. */
    fun kick(force: Boolean = false, then: () -> Unit = {}): Job? {
        if (Iptv.playlists().isEmpty()) { then(); return null }
        job?.takeIf { it.isActive }?.let { return it }
        _running.value = true
        return scope.launch {
            try { run(force) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "update: ${e.message}")
            } finally { _running.value = false; then() }
        }.also { job = it }
    }

    private suspend fun run(force: Boolean) {
        Iptv.openedThisSession.value = true
        if (force) Iptv.refreshAll() else Iptv.ensureLoaded()
        Vod.ensureLoaded(force)
        warmVod()
        warmLogos()
    }

    /** The first row of every VOD category matched to TMDB, and the first posters of the top rows in the image cache. */
    private suspend fun warmVod() {
        if (Vod.count() == 0L) return
        for (kind in listOf("movie", "series")) {
            val groups = runCatching { Vod.groups(kind) }.getOrDefault(emptyList())
            groups.forEachIndexed { i, (g, _) ->
                val items = Vod.items(kind, g, 0, 40)
                if (!VodArabic.match(items)) return            // no TMDB key: the playlist's own posters are used
                if (i < 25) prefetch(items.take(10).mapNotNull { VodArabic.card(it).poster })
            }
        }
    }

    /** Logos of favourites, recent channels and the first channels of the list. */
    private fun warmLogos() {
        val keys = (Iptv.favourites() + Iptv.recents()).toList()
        val list = runCatching { Iptv.channelsByKey(keys) + Iptv.channels(null).take(150) }.getOrDefault(emptyList())
        prefetch(list.mapNotNull { it.logo }.distinct())
    }

    private fun prefetch(urls: List<String>) {
        val ctx = App.instance
        val loader = coil3.SingletonImageLoader.get(ctx)
        urls.forEach { u ->
            loader.enqueue(coil3.request.ImageRequest.Builder(ctx).data(u).memoryCachePolicy(coil3.request.CachePolicy.DISABLED).build())
        }
    }

    // ---- the 05:00 update ----

    /** Sets the next 05:00 alarm (only while there is a playlist). Called at app start, after boot and after each run. */
    fun arm(ctx: Context = App.instance) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(ctx, 5005, Intent(ctx, IptvAlarm::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (Iptv.playlists().isEmpty()) { am.cancel(pi); return }
        // 05:00 (and 17:00 with a 12 h interval); a 48 h interval still wakes daily and finds nothing due every other day.
        val hours = if ((App.instance.prefs.iptvUpdateHours.value.toIntOrNull() ?: 24) <= 12) listOf(5, 17) else listOf(5)
        val at = hours.map { h -> Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 60_000) add(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis }.min()
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
    }

    /** Something plays: the nightly update waits (a big download must not stall the picture). */
    internal fun busy() = com.lekathedon.visionlite.ui.DpadPace.inPlayer || Recorder.active.value.isNotEmpty()
}

/** 05:00: starts the update service; after the box restarts, sets the alarm again. */
class IptvAlarm : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) { IptvSync.arm(context); return }
        runCatching {
            val i = Intent(context, IptvUpdateService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }
}

/** The nightly update as a foreground service (so the box doesn't stop it half-way); stops itself when done. */
class IptvUpdateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        foreground()
        scope.launch {
            // Something playing: try again every 30 min (up to 3 h), then leave it to the next open.
            var tries = 0
            while (IptvSync.busy() && tries++ < 6) delay(30 * 60_000L)
            if (!IptvSync.busy()) IptvSync.kick(force = false)?.join()
            IptvSync.arm(this@IptvUpdateService)
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun foreground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("iptv", "Live TV updates", NotificationManager.IMPORTANCE_MIN))
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "iptv") else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Updating channels, guide and VOD").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(8, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(8, n)
    }

    override fun onDestroy() { scope.coroutineContext[Job]?.cancel(); super.onDestroy() }
}
