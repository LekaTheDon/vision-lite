package com.lekathedon.visionlite.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import com.lekathedon.visionlite.App

/**
 * The phone link, inside Vision (what Vision needed the separate Vision Link app for). Runs only while
 * Settings › Phone companion is on: started then, at boot and after an update. Idle cost: one thread blocked in
 * accept(), no polling, no timers. Announces itself as _visionlite._tcp so the phone finds the box again.
 */
class LinkService : Service() {
    private var server: Server? = null
    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Phone companion", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Vision").setContentText("Waiting for your phone")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(7, n)
        Thread { grantOnce(this) }.start()
        runCatching { Server(Pairing(this), Commands(this)).also { it.start(); server = it } }
            .onFailure { Log.e(TAG, "listener failed: ${it.message}") }
        announce()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        server?.stop()
        runCatching { nsdListener?.let { nsd?.unregisterService(it) } }
        super.onDestroy()
    }

    private fun announce() {
        val m = getSystemService(NsdManager::class.java) ?: return
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { Log.w(TAG, "nsd register failed $code") }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        val info = NsdServiceInfo().apply { serviceName = "Vision ${Build.MODEL}"; serviceType = Pairing.SERVICE_TYPE; port = Pairing.PORT }
        runCatching { m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l); nsd = m; nsdListener = l }
    }

    companion object {
        private const val TAG = "VisionLiteLink"
        private const val CHANNEL = "link"

        /** Starts or stops the listener to match the setting. */
        fun sync(c: Context) {
            val i = Intent(c, LinkService::class.java)
            if (App.instance.prefs.phoneLink.value) runCatching { c.startForegroundService(i) } else c.stopService(i)
        }

        /**
         * Root grants, each only if missing: draw over apps (so the phone can open Lite while it's in the background)
         * and the battery whitelist (Doze doesn't put the listener to sleep).
         */
        fun grantOnce(c: Context) {
            val pkg = c.packageName
            // Root only for what's missing: nothing runs once both are granted (or on a device without root).
            val idleOk = (c.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(pkg)
            if (Settings.canDrawOverlays(c) && idleOk) return
            val script = buildString {
                if (!Settings.canDrawOverlays(c)) append("appops set $pkg SYSTEM_ALERT_WINDOW allow; ")
                if (!idleOk) append("dumpsys deviceidle whitelist +$pkg >/dev/null; ")
                append("true")
            }
            Root.run(script, 10)
        }
    }
}

/** After boot and after an update of Lite: the listener comes back if the phone companion is on. */
class LinkBoot : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { LinkService.sync(context) }
}
