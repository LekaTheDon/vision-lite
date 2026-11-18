package com.lekathedon.visionlite

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
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

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        addons = AddonStore(prefs)
        client = AddonClient(addons)
    }

    /** Images: shared HTTP client, no crossfade, memory cache sized to a quarter of the app's heap share. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { Net.http })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
        .crossfade(false)
        .build()

    companion object {
        lateinit var instance: App; private set
    }
}
