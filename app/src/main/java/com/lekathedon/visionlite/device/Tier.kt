package com.lekathedon.visionlite.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/**
 * The box's performance class, from physical memory (NuvioTV-Lite's lesson: the heap size can't tell a 2 GB box from
 * an 8 GB one, physical RAM can). It sizes things, it never switches features off by itself:
 * - Light (1.5 GB class or Android's low-RAM flag): small image cache, 3 add-ons asked at once, small buffers,
 *   trailers capped at 720p, no video tiles on Streaming services.
 * - Standard (2 GB class): medium caches and buffers, trailers capped at 1080p.
 * - Full (3 GB and up, the AM9): everything as before.
 * Unknown memory counts as Light: over-budgeting a 1.5 GB stick gets the app killed, under-budgeting an 8 GB box doesn't.
 * Settings › This TV › Performance can override it ([com.lekathedon.visionlite.data.Prefs.perfClass]).
 */
object Tier {
    enum class Class(val label: String) { LIGHT("Light"), STANDARD("Standard"), FULL("Full") }

    /** 1.5 GB sticks report ~1.4 GB, 2 GB boxes ~1.8 GB, 3 GB boxes ~2.8 GB. */
    private const val LIGHT_MAX_MB = 1600L
    private const val STANDARD_MAX_MB = 2560L

    @Volatile var ramMb = 0L; private set
    @Volatile var lowRamFlag = false; private set
    /** The app's standard heap (largeHeap is off), MB. */
    @Volatile var heapMb = 0L; private set
    @Volatile var cores = 0; private set
    @Volatile var is64 = false; private set
    /** The user's choice ("light"/"standard"/"full"), "" = measured. Read once at start (images are sized then). */
    @Volatile private var chosen = ""

    fun init(context: Context, choice: String) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val total = am?.let { ActivityManager.MemoryInfo().also(it::getMemoryInfo).totalMem }?.takeIf { it > 0 } ?: memInfoBytes()
        ramMb = total / (1024 * 1024)
        lowRamFlag = am?.isLowRamDevice == true
        heapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        cores = Runtime.getRuntime().availableProcessors()
        is64 = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        chosen = choice
    }

    fun setChoice(choice: String) { chosen = choice }

    /** What the memory says, ignoring the user's choice. */
    fun measured(): Class = when {
        lowRamFlag || ramMb <= LIGHT_MAX_MB -> Class.LIGHT
        ramMb <= STANDARD_MAX_MB -> Class.STANDARD
        else -> Class.FULL
    }

    val current: Class
        get() = when (chosen) { "light" -> Class.LIGHT; "standard" -> Class.STANDARD; "full" -> Class.FULL; else -> measured() }

    fun id(c: Class) = c.name.lowercase()

    // ---- What each class sizes ----

    /** Share of the app's heap for decoded posters (Coil memory cache). */
    val imageCachePercent: Double get() = when (current) { Class.LIGHT -> 0.10; Class.STANDARD -> 0.18; Class.FULL -> 0.25 }

    /** Posters decoded at once. */
    val imageDecoders: Int get() = if (current == Class.LIGHT) 2 else 4

    /** Add-ons asked for links / subtitles at the same time (each holds a whole answer in memory). */
    val addonFetches: Int get() = when (current) { Class.LIGHT -> 3; Class.STANDARD -> 6; Class.FULL -> 64 }

    /**
     * Most memory ExoPlayer's read-ahead may hold (Java heap). Light keeps ~40% of the heap for the UI, posters and
     * decoders; media3's own default (~140 MB for video) would not fit a 192 MB heap.
     */
    val exoBufferCeilingMb: Int
        get() {
            val heap = heapMb.takeIf { it > 0 } ?: 128
            return when (current) {
                Class.LIGHT -> (heap * 0.35).toInt().coerceIn(24, 64)
                Class.STANDARD -> (heap * 0.45).toInt().coerceIn(32, 120)
                Class.FULL -> (heap / 2).toInt().coerceIn(48, 200)
            }
        }

    /** MPV read-ahead (native memory) for Small / Standard / Large. */
    fun mpvBufferMb(choice: String): Int = when (current) {
        Class.LIGHT -> when (choice) { "small" -> 24; "large" -> 48; else -> 32 }
        Class.STANDARD -> when (choice) { "small" -> 32; "large" -> 96; else -> 48 }
        Class.FULL -> when (choice) { "small" -> 32; "large" -> 192; else -> 64 }
    }

    /** Tallest trailer this class decodes on top of the UI (a 4K trailer costs a few hundred MB). */
    // Trailers decode in software (so the TV never switches): 4K on the CPU can't keep up and froze mid-trailer
    // (2026-11-24, every box). 1080p tops for all.
    fun trailerHeight(wanted: Int): Int = minOf(wanted, when (current) { Class.LIGHT -> 720; else -> 1080 })

    /** Streaming services' short video tiles (cached 20-40 MB, a decoder each time one plays). */
    val videoTiles: Boolean get() = current != Class.LIGHT

    /** One line for reports and Settings. */
    fun summary(): String = "${current.label}${if (chosen.isNotEmpty()) " (chosen; measured ${measured().label})" else ""} · " +
        "%.1f GB RAM%s · heap %d MB · %d cores · %s".format(ramMb / 1024.0, if (lowRamFlag) " (low-RAM flag)" else "", heapMb, cores,
            if (is64) "64-bit" else "32-bit")

    private fun memInfoBytes(): Long = runCatching {
        File("/proc/meminfo").useLines { l -> l.firstOrNull { it.startsWith("MemTotal") } }
            ?.let { Regex("\\d+").find(it)?.value?.toLong()?.times(1024) } ?: 0L
    }.getOrDefault(0L)
}
