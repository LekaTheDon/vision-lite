package com.lekathedon.visionlite.device

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.data.Prefs
import com.lekathedon.visionlite.player.fel.AmlFelSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** One device setting: only the options this box/TV/soundbar can do, one of them Recommended. */
data class Choice(val key: String, val section: String, val title: String, val options: List<Pair<String, String>>,
                  val recommended: String, val why: String) {
    fun label(id: String) = options.firstOrNull { it.first == id }?.second ?: id
}

/**
 * What the check measured on this box. Picture, modes, decoders, sound and memory are read in a moment; internet
 * speed is a short download test, only when the check is run by hand (or on first start).
 */
@Serializable
data class Facts(
    val at: Long = 0,
    /** The TV's HDR list could be read (null caps on some boxes = unknown, everything stays offered). */
    val capsKnown: Boolean = false,
    val dvTv: Boolean = false, val hdr10: Boolean = false, val hdr10Plus: Boolean = false, val hlg: Boolean = false,
    val width: Int = 0, val height: Int = 0,
    val rates: List<Float> = emptyList(),
    /** "3840x2160" etc., every resolution the TV offers. */
    val resolutions: List<String> = emptyList(),
    val fel: Boolean = false,
    /** A Dolby Vision decoder that takes profile 7 (dvhe.dtb). */
    val dv7Decoder: Boolean = false,
    val dvDecoder: Boolean = false,
    /** Vision's libdovi converter loads on this box (DV7/DV5 -> 8.1). */
    val converter: Boolean = false,
    val av1Hw: Boolean = false,
    /** Passthrough formats the soundbar/TV reports: ac3, eac3, joc, truehd, dts, dtshd. */
    val sound: List<String> = emptyList(),
    val soundKnown: Boolean = false,
    val ramMb: Int = 0,
    /** Measured download speed (Mb/s); null = not tested. */
    val mbps: Double? = null,
) {
    /** What decides the choices except speed and the time: when this changes, the TV or soundbar changed. */
    fun print(): String = listOf(capsKnown, dvTv, hdr10, hdr10Plus, hlg, width, height, rates.map { (it * 100).toInt() },
        resolutions, fel, dv7Decoder, dvDecoder, converter, sound, soundKnown).joinToString("|")
}

/** Reads the box, TV and soundbar, then suggests; applies only on "Use all recommended" or with Let the app decide on. */
object DeviceCheck {
    private val p: Prefs get() = App.instance.prefs

    fun saved(): Facts? = p.tvFacts.value.takeIf { it.isNotBlank() }?.let { runCatching { Net.json.decodeFromString(Facts.serializer(), it) }.getOrNull() }
    private fun save(f: Facts) { p.tvFacts.value = Net.json.encodeToString(Facts.serializer(), f) }

    /**
     * Runs every probe. [speed] adds the internet test (~6 s); without it the last measured speed is kept.
     * [step] gets a short line per probe ("Reading the soundbar…").
     */
    suspend fun probe(context: Context, speed: Boolean, step: (String) -> Unit = {}): Facts = withContext(Dispatchers.IO) {
        step("Reading the TV's picture…")
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        val caps = display?.hdrCapabilities
        val hdr = caps?.supportedHdrTypes?.toSet().orEmpty()
        val mode = display?.mode
        val modes = display?.supportedModes.orEmpty()
        val rates = modes.filter { it.physicalWidth == mode?.physicalWidth && it.physicalHeight == mode.physicalHeight }
            .map { it.refreshRate }.distinctBy { (it * 100).toInt() }.sorted()
        val resolutions = modes.map { "${it.physicalWidth}x${it.physicalHeight}" }.distinct()

        step("Looking at the box's decoders…")
        val fel = runCatching { AmlFelSupport.isDeviceUsable() && AmlFelSupport.hasFelKernelMarkers() }.getOrDefault(false)
        var dv = false; var dv7 = false; var av1 = false
        runCatching {
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (info.isEncoder) continue
                for (type in info.supportedTypes) {
                    when (type.lowercase()) {
                        "video/dolby-vision" -> {
                            dv = true
                            val pl = runCatching { info.getCapabilitiesForType(type).profileLevels }.getOrNull().orEmpty()
                            if (pl.any { it.profile == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb }) dv7 = true
                        }
                        "video/av01" -> if (hardware(info)) av1 = true
                    }
                }
            }
        }

        step("Asking the soundbar what it takes…")
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_HDMI || it.type == AudioDeviceInfo.TYPE_HDMI_ARC || (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_HDMI_EARC) }
        val enc = outs.flatMap { it.encodings.toList() }.toSet()
        val sound = buildList {
            if (AudioFormat.ENCODING_AC3 in enc) add("ac3")
            if (AudioFormat.ENCODING_E_AC3 in enc) add("eac3")
            if (Build.VERSION.SDK_INT >= 29 && AudioFormat.ENCODING_E_AC3_JOC in enc) add("joc")
            if (AudioFormat.ENCODING_DOLBY_TRUEHD in enc) add("truehd")
            if (AudioFormat.ENCODING_DTS in enc) add("dts")
            if (AudioFormat.ENCODING_DTS_HD in enc) add("dtshd")
        }

        step("Measuring memory…")
        val mi = ActivityManager.MemoryInfo().also { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }

        val mbps = if (speed) { step("Testing the internet speed…"); speedTest() } else saved()?.mbps
        Facts(
            at = System.currentTimeMillis(), capsKnown = caps != null,
            dvTv = Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in hdr, hdr10 = Display.HdrCapabilities.HDR_TYPE_HDR10 in hdr,
            hdr10Plus = Build.VERSION.SDK_INT >= 29 && Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in hdr,
            hlg = Display.HdrCapabilities.HDR_TYPE_HLG in hdr,
            width = mode?.physicalWidth ?: 0, height = mode?.physicalHeight ?: 0, rates = rates, resolutions = resolutions,
            fel = fel, dv7Decoder = dv7, dvDecoder = dv, av1Hw = av1,
            converter = com.lekathedon.visionlite.player.dv.DvRoute.converterAvailable(),
            sound = sound, soundKnown = outs.isNotEmpty(), ramMb = (mi.totalMem / (1024 * 1024)).toInt(), mbps = mbps,
        ).also { save(it) }
    }

    private fun hardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
        else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")

    /** Downloads for up to 6 s (max 40 MB) from Cloudflare's speed test; Mb/s, or null when it fails. */
    private fun speedTest(): Double? = runCatching {
        val req = okhttp3.Request.Builder().url("https://speed.cloudflare.com/__down?bytes=40000000").build()
        Net.http.newCall(req).execute().use { r ->
            val src = r.body?.byteStream() ?: return null
            val buf = ByteArray(64 * 1024)
            val start = System.nanoTime()
            var total = 0L
            var firstAt = 0L
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                if (firstAt == 0L) firstAt = System.nanoTime()
                total += n
                if (System.nanoTime() - start > 6_000_000_000L) break
            }
            val secs = (System.nanoTime() - (if (firstAt > 0) firstAt else start)) / 1e9
            if (secs <= 0.2 || total < 1_000_000) null else total * 8 / secs / 1e6
        }
    }.getOrNull()

    /** The choices this device has, from [f]; rows with one possible answer are left out. */
    fun choices(f: Facts): List<Choice> = buildList {
        fun add(c: Choice) { if (c.options.size > 1) this.add(c) }
        val caps = f.capsKnown

        // Picture: Dolby Vision profile 7 (disc remuxes), Vision's table.
        run {
            val opts = buildList {
                if (f.fel) add("fel" to "Native FEL (both layers, this box)")
                if (f.converter && f.dvDecoder && (!caps || f.dvTv)) add("dv81" to "Convert to Dolby Vision 8.1")
                add("box" to if (f.dv7Decoder) "The box's Dolby Vision decoder" else "Leave it to the box")
                if (!caps || f.hdr10) add("hdr10" to "Play the HDR10 base layer")
            }
            // Vision's table: FEL box -> native; DV TV without FEL -> 8.1 keeps DV; HDR10 TV -> base layer.
            val (rec, why) = when {
                f.fel -> "fel" to "This box plays dual-layer Dolby Vision (FEL kernel found)"
                caps && f.dvTv && f.converter && f.dvDecoder -> "dv81" to "Your TV shows Dolby Vision; this box has no FEL, so 8.1 keeps Dolby Vision"
                caps && f.dvTv && f.dv7Decoder -> "box" to "Your TV shows Dolby Vision and the box decodes profile 7"
                caps && f.hdr10 && !f.dvTv -> "hdr10" to "Your TV shows HDR10 but not Dolby Vision"
                else -> "box" to if (caps) "No HDR10 on the TV: the box tone-maps" else "The TV's HDR list can't be read, so the box decides"
            }
            add(Choice("dv7", "Picture", "Dolby Vision profile 7", opts, rec, why))
        }
        if (f.converter && f.dvDecoder && caps && !f.dvTv) add(Choice("dv5", "Picture", "Dolby Vision profile 5",
            listOf("on" to "Convert to 8.1 (keeps the colours right)", "off" to "Leave as is"), "on",
            "Your TV doesn't show Dolby Vision; profile 5 has no HDR10 layer of its own"))
        if (caps && f.hdr10 && !f.hdr10Plus) add(Choice("hdr10plus", "Picture", "HDR10+",
            listOf("on" to "Play as HDR10 (drop HDR10+ data)", "off" to "Keep HDR10+ data"), "on", "Your TV shows HDR10 but not HDR10+"))
        if (f.rates.size > 1) add(Choice("afr", "Picture", "Match frame rate", listOf("off" to "Off", "always" to "On"), "always",
            "Your TV switches between ${f.rates.joinToString("/") { r -> "%.2f".format(r).trimEnd('0').trimEnd('.') }} Hz: 24p films play without judder"))
        if (f.resolutions.size > 1) add(Choice("res", "Picture", "Match resolution", listOf("off" to "Off (box upscales)", "on" to "On (TV upscales)"), "off",
            "The box upscales well and one mode means fewer black screens"))

        // Sound: one row per format the soundbar takes. If it can play it, it goes through.
        val names = listOf("ac3" to "Dolby Digital", "eac3" to "Dolby Digital Plus / Atmos", "truehd" to "Dolby TrueHD / Atmos",
            "dts" to "DTS", "dtshd" to "DTS-HD MA / DTS:X")
        for ((id, name) in names) {
            if (f.soundKnown && id !in f.sound) continue
            add(Choice("pt_$id", "Sound", name, listOf("on" to "Passthrough to soundbar", "off" to "Decode in the box"), "on",
                if (f.soundKnown) "Your soundbar or TV takes it directly" else "The box can't tell what the soundbar takes"))
        }

        // Playback: engine and buffer sized to the box.
        add(Choice("engine", "Playback", "Player engine", listOf("auto" to "Auto", "exo" to "ExoPlayer", "mpv" to "MPV"), "auto",
            "Auto: HDR, Dolby Vision and anime on ExoPlayer, Hi10p on MPV" + if (!f.av1Hw) "; no AV1 chip, so AV1 decodes in software" else ""))
        run {
            val gb = f.ramMb / 1024.0
            val opts = buildList {
                add("small" to "Small (starts sooner, less memory)")
                add("normal" to "Standard")
                if (f.ramMb >= 3277) add("large" to "Large (rides out slow links)")
            }
            val rec = when { f.ramMb in 1 until 2048 -> "small"; f.ramMb >= 3277 -> "large"; else -> "normal" }
            add(Choice("buffer", "Playback", "Playback buffer", opts, rec, "This box has %.1f GB of memory".format(gb)))
        }

        // Internet: what the measured speed can carry (a 2.5 h film at 70% of the line).
        f.mbps?.let { mbps ->
            val use = mbps * 0.7
            val qRec = when { use < 8 -> "720"; use < 20 -> "1080"; else -> "0" }
            add(Choice("max_quality", "Internet", "Highest quality",
                listOf("0" to "Any", "2160" to "4K", "1080" to "1080p", "720" to "720p"), qRec, "Measured %.0f Mb/s".format(mbps)))
            val canGb = use * 9000 / 8 / 1000
            val sizes = listOf(5, 10, 20, 40, 80)
            val sRec = if (canGb >= 100) "0" else (sizes.lastOrNull { it <= canGb } ?: 5).toString()
            add(Choice("size_limit", "Internet", "File size limit",
                listOf("0" to "No limit") + sizes.map { it.toString() to "$it GB" }, sRec,
                "At %.0f Mb/s a long film up to about %.0f GB plays without stopping".format(mbps, canGb)))
        }
    }

    fun current(key: String): String = when (key) {
        "dv7" -> p.dv7.value
        "afr" -> if (p.afr.value == "off") "off" else "always"
        "res" -> if (p.matchRes.value) "on" else "off"
        "dv5" -> on(p.dv5To81.value)
        "hdr10plus" -> on(p.stripHdr10Plus.value)
        "pt_ac3" -> on(p.ptAc3.value); "pt_eac3" -> on(p.ptEac3.value); "pt_truehd" -> on(p.ptTruehd.value)
        "pt_dts" -> on(p.ptDts.value); "pt_dtshd" -> on(p.ptDtshd.value)
        "engine" -> p.engine.value
        "buffer" -> p.buffer.value
        "max_quality" -> p.maxQuality.value
        "size_limit" -> p.sizeLimit.value
        else -> ""
    }

    private fun on(b: Boolean) = if (b) "on" else "off"

    fun set(key: String, v: String) {
        when (key) {
            "dv7" -> p.dv7.value = v
            "afr" -> p.afr.value = v
            "res" -> p.matchRes.value = v == "on"
            "dv5" -> p.dv5To81.value = v == "on"
            "hdr10plus" -> p.stripHdr10Plus.value = v == "on"
            "pt_ac3" -> p.ptAc3.value = v == "on"; "pt_eac3" -> p.ptEac3.value = v == "on"; "pt_truehd" -> p.ptTruehd.value = v == "on"
            "pt_dts" -> p.ptDts.value = v == "on"; "pt_dtshd" -> p.ptDtshd.value = v == "on"
            "engine" -> p.engine.value = v
            "buffer" -> p.buffer.value = v
            "max_quality" -> p.maxQuality.value = v
            "size_limit" -> p.sizeLimit.value = v
        }
    }

    /** Rows whose setting differs from the recommendation. */
    fun differing(f: Facts): List<Choice> = choices(f).filter { current(it.key) != it.recommended }

    /** One press: every recommendation applied. Returns how many settings changed. */
    fun useRecommended(f: Facts): Int {
        val d = differing(f)
        d.forEach { set(it.key, it.recommended) }
        p.deviceAsked.value = true
        return d.size
    }

    /**
     * Let the app decide: on app start, a quick check (no speed test); when the TV or soundbar changed since the last
     * check, the recommendations are applied by themselves. Returns the number of changes, 0 when nothing changed.
     */
    suspend fun autoUpdate(context: Context): Int {
        if (!p.tvAuto.value || !p.deviceAsked.value) return 0
        val before = saved()?.print()
        val f = probe(context, speed = false)
        return if (before != f.print()) withContext(Dispatchers.Main) { useRecommended(f) } else 0
    }
}
