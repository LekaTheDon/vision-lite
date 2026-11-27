package com.lekathedon.visionlite

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.bitmapFactoryMaxParallelism
import android.content.ComponentCallbacks2
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.lekathedon.visionlite.addons.AddonClient
import com.lekathedon.visionlite.addons.AddonStore
import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.data.Prefs

/** The app's few long-lived objects, made once (no DI framework). */
class App : Application(), SingletonImageLoader.Factory {
    lateinit var prefs: Prefs; private set
    lateinit var addons: AddonStore; private set
    lateinit var client: AddonClient; private set
    lateinit var trakt: com.lekathedon.visionlite.trakt.Trakt; private set

    override fun onCreate() {
        // First: a crash anywhere after this point leaves a report behind (Settings › About › Report a problem).
        com.lekathedon.visionlite.report.Crash.install(this)
        super.onCreate()
        instance = this
        com.lekathedon.visionlite.data.Profiles.init(this)
        prefs = Prefs(this)
        // Arc seek thumbnails: stored pictures kept within their budget, 60 s after start (off cold start).
        com.nuvio.tv.core.player.thumbnail.SeekThumbnails.scheduleStartupHousekeeping(this)
        com.lekathedon.visionlite.device.Tier.init(this, prefs.perfClass.value)
        android.util.Log.i("VLDevice", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} · " +
            com.lekathedon.visionlite.device.Tier.summary())
        com.lekathedon.visionlite.report.Mem.snapshot(this, "app-start")
        addons = AddonStore(prefs)
        client = AddonClient(addons)
        trakt = com.lekathedon.visionlite.trakt.Trakt(prefs)
        Thread { runCatching { com.lekathedon.visionlite.data.Profiles.keepPictures() } }.start()
        // Service intro clips are gone (0.41.15): the 20-40 MB they kept on the box goes too.
        Thread { runCatching { java.io.File(filesDir, "service_clips").takeIf { it.exists() }?.deleteRecursively() } }.start()
        // A Nuvio / Stremio import still waiting for Trakt or Simkl (nothing when there's none).
        com.lekathedon.visionlite.integrations.Join.later()
        com.lekathedon.visionlite.integrations.Join.migrate()
    }

    /** Android asks the app to give memory back: logged (VLMem) so reports show the pressure before a kill. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) com.lekathedon.visionlite.report.Mem.snapshot(this, "trim-$level")
        // Running critically low (Light boxes mostly): drop decoded posters; they reload from the disk cache.
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            runCatching { SingletonImageLoader.get(this).memoryCache?.clear() }
    }

    /** Images: shared HTTP client, no crossfade, memory cache and parallel decodes sized by the box's class (device/Tier.kt). */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { Net.http }))
            // Animated profile pictures (GIF/WebP) move, as in Vision. Only animated files take this path.
            if (android.os.Build.VERSION.SDK_INT >= 28) add(coil3.gif.AnimatedImageDecoder.Factory()) else add(coil3.gif.GifDecoder.Factory())
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, com.lekathedon.visionlite.device.Tier.imageCachePercent).build() }
        .crossfade(false)
        .bitmapFactoryMaxParallelism(com.lekathedon.visionlite.device.Tier.imageDecoders)
        .build()

    /** The profile in use's own files folder (data/Profiles.kt); filesDir itself for profile 1. */
    val profileDir: java.io.File get() = com.lekathedon.visionlite.data.Profiles.dir(com.lekathedon.visionlite.data.Profiles.currentId())

    companion object {
        lateinit var instance: App; private set

        /** Starts the app again (after a whole setup came in), like a profile switch. */
        fun restart(c: android.content.Context) {
            val i = c.packageManager.getLaunchIntentForPackage(c.packageName)!!
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            c.startActivity(i)
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
