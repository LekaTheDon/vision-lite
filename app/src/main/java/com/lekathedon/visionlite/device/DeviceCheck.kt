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
    // The box itself (0.23.0): what decides how heavy the app may be. Not part of [print] (the box doesn't change).
    val maker: String = "", val model: String = "", val soc: String = "", val sdk: Int = 0,
    val is64: Boolean = false, val cores: Int = 0, val maxMhz: Int = 0, val lowRamFlag: Boolean = false, val heapMb: Int = 0,
    /** The decoder list could be read. */
    val decodersKnown: Boolean = false,
    /** A hardware decoder takes 3840x2160 (HEVC, VP9 or AV1). */
    val uhdDecode: Boolean = false,
    /** Hardware HEVC Main10 (10-bit: nearly every 4K HDR file). */
    val hevc10: Boolean = false,
    val hevcHw: Boolean = false, val vp9Hw: Boolean = false,
    /** Tallest video the best hardware HEVC/AVC decoder takes. */
    val maxDecodeHeight: Int = 0,
    /** A video decoder that can tunnel (picture and sound timed by the TV chip). */
    val tunneling: Boolean = false,
    // Speed and network (0.34): which internet, how each DNS service answers on it, what the provider blocks, and the
    // accessibility services that are on.
    val net: String = "",
    val dnsMs: Map<String, Int> = emptyMap(),
    val dnsBlocked: List<String> = emptyList(),
    val a11y: List<String> = emptyList(),
) {
    /** What decides the choices except speed and the time: when this changes, the TV or soundbar changed. */
    fun print(): String = listOf(capsKnown, dvTv, hdr10, hdr10Plus, hlg, width, height, rates.map { (it * 100).toInt() },
        resolutions, fel, dv7Decoder, dvDecoder, converter, sound, soundKnown, net, a11y).joinToString("|")
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
        var hevc = false; var hevc10 = false; var vp9 = false; var uhd = false; var maxH = 0; var tunnel = false; var known = false
        runCatching {
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (info.isEncoder) continue
                known = true
                for (type in info.supportedTypes) {
                    val t = type.lowercase()
                    when (t) {
                        "video/dolby-vision" -> {
                            dv = true
                            val pl = runCatching { info.getCapabilitiesForType(type).profileLevels }.getOrNull().orEmpty()
                            if (pl.any { it.profile == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb }) dv7 = true
                        }
                        "video/av01" -> if (hardware(info)) { av1 = true; if (takes4k(info, type)) uhd = true }
                        "video/hevc" -> if (hardware(info)) {
                            hevc = true
                            val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull()
                            if (caps?.profileLevels.orEmpty().any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }) hevc10 = true
                            if (takes4k(info, type)) uhd = true
                            maxH = maxOf(maxH, tallest(info, type))
                        }
                        "video/x-vnd.on2.vp9" -> if (hardware(info)) { vp9 = true; if (takes4k(info, type)) uhd = true }
                        "video/avc" -> if (hardware(info)) maxH = maxOf(maxH, tallest(info, type))
                    }
                    if (t.startsWith("video/") && hardware(info) && runCatching {
                            info.getCapabilitiesForType(type).isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback)
                        }.getOrDefault(false)) tunnel = true
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

        // Another internet than last time: its speed and DNS are measured again even on the quiet start-up check.
        val old = saved()
        val net = com.lekathedon.visionlite.data.NetCheck.fingerprint(context)
        val newNet = old != null && old.net.isNotEmpty() && net != old.net
        val mbps = if (speed || newNet) { step("Testing the internet speed…"); speedTest() } else old?.mbps
        val (dnsMs, blocked) = if (speed || newNet || old?.dnsMs.isNullOrEmpty()) { step("Testing DNS services…"); com.lekathedon.visionlite.data.NetCheck.measure() }
            else old!!.dnsMs to old.dnsBlocked
        val a11y = com.lekathedon.visionlite.data.QuietA11y.services(context)
        Facts(
            at = System.currentTimeMillis(), capsKnown = caps != null,
            dvTv = Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in hdr, hdr10 = Display.HdrCapabilities.HDR_TYPE_HDR10 in hdr,
            hdr10Plus = Build.VERSION.SDK_INT >= 29 && Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in hdr,
            hlg = Display.HdrCapabilities.HDR_TYPE_HLG in hdr,
            width = mode?.physicalWidth ?: 0, height = mode?.physicalHeight ?: 0, rates = rates, resolutions = resolutions,
            fel = fel, dv7Decoder = dv7, dvDecoder = dv, av1Hw = av1,
            converter = com.lekathedon.visionlite.player.dv.DvRoute.converterAvailable(),
            sound = sound, soundKnown = outs.isNotEmpty(), ramMb = (mi.totalMem / (1024 * 1024)).toInt(), mbps = mbps,
            maker = Build.MANUFACTURER.orEmpty(), model = Build.MODEL.orEmpty(), soc = socName(), sdk = Build.VERSION.SDK_INT,
            is64 = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty(), cores = Runtime.getRuntime().availableProcessors(), maxMhz = maxCpuMhz(),
            lowRamFlag = Tier.lowRamFlag, heapMb = Tier.heapMb.toInt(),
            decodersKnown = known, uhdDecode = uhd, hevc10 = hevc10, hevcHw = hevc, vp9Hw = vp9, maxDecodeHeight = maxH, tunneling = tunnel,
            net = net, dnsMs = dnsMs, dnsBlocked = blocked, a11y = a11y,
        ).also { save(it) }
    }

    /** The decoder takes 3840x2160 (at 24 fps at least). */
    private fun takes4k(info: MediaCodecInfo, type: String): Boolean = runCatching {
        val v = info.getCapabilitiesForType(type).videoCapabilities ?: return false
        v.isSizeSupported(3840, 2160) || v.isSizeSupported(4096, 2160)
    }.getOrDefault(false)

    private fun tallest(info: MediaCodecInfo, type: String): Int = runCatching {
        info.getCapabilitiesForType(type).videoCapabilities?.supportedHeights?.upper ?: 0
    }.getOrDefault(0)

    /** "Amlogic S928X" / "MediaTek MT9653"-style name: Android 12's SoC fields, else the board/hardware name. */
    private fun socName(): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL).filter { !it.isNullOrBlank() && it != Build.UNKNOWN }.joinToString(" ") else ""
        if (soc.isNotBlank()) return soc
        val board = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "ro.board.platform") as? String
        }.getOrNull().orEmpty()
        return listOf(board, Build.HARDWARE.orEmpty()).filter { it.isNotBlank() }.distinct().joinToString(" / ")
    }

    /** Fastest core's top clock, MHz (0 = unreadable). */
    private fun maxCpuMhz(): Int = runCatching {
        (0 until Runtime.getRuntime().availableProcessors()).maxOf { i ->
            java.io.File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").takeIf { it.canRead() }?.readText()?.trim()?.toIntOrNull()?.div(1000) ?: 0
        }
    }.getOrDefault(0)

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
            val rec = when { Tier.measured() == Tier.Class.LIGHT -> "small"; f.ramMb >= 3277 -> "large"; else -> "normal" }
            add(Choice("buffer", "Playback", "Playback buffer", opts, rec, "This box has %.1f GB of memory".format(gb)))
        }

        // Performance: how heavy the app may be on this box (device/Tier.kt); the memory decides the recommendation.
        run {
            val measured = Tier.measured()
            val opts = buildList {
                add("light" to "Light (small caches, 720p trailers, no video tiles)")
                add("standard" to "Standard (1080p trailers)")
                if (measured == Tier.Class.FULL || f.ramMb > 2560) add("full" to "Full (everything, 4K trailers)")
            }
            add(Choice("perf", "Performance", "How heavy the app may be", opts, Tier.id(measured),
                "%.1f GB memory%s, %d cores%s, %s: %s. A change takes full effect next start".format(f.ramMb / 1024.0,
                    if (f.lowRamFlag) " (Android marks it low-memory)" else "", f.cores,
                    if (f.maxMhz > 0) " up to %.1f GHz".format(f.maxMhz / 1000.0) else "", if (f.is64) "64-bit" else "32-bit",
                    when (measured) { Tier.Class.LIGHT -> "Light keeps it from being closed for memory"; Tier.Class.STANDARD -> "Standard fits"; Tier.Class.FULL -> "plenty for everything" })))
            if (measured != Tier.Class.FULL) add(Choice("trailers", "Performance", "Trailers on Home and title pages",
                listOf("on" to "On", "off" to "Off"), if (measured == Tier.Class.LIGHT) "off" else "on",
                if (measured == Tier.Class.LIGHT) "A trailer is a second video decoder on top of the UI; on this box it can make Home lag"
                else "Fine on this box at up to 1080p"))
        }

        // Speed and network: DNS for this internet, scrolling and closing for this box.
        if (f.dnsMs.isNotEmpty()) run {
            val services = com.lekathedon.visionlite.data.SwitchDns.SERVICES
            fun name(k: String) = services.firstOrNull { it.key == k }?.name ?: k
            val working = f.dnsMs.filter { it.value >= 0 }
            val opts = services.filter { it.key == "system" || it.key in working }.map { s ->
                s.key to (s.name + (f.dnsMs[s.key]?.takeIf { it >= 0 }?.let { " · $it ms" } ?: " · no answer"))
            }
            val sys = f.dnsMs["system"] ?: -1
            val fastest = working.filterKeys { it != "system" }.minByOrNull { it.value }
            val (rec, why) = when {
                f.dnsBlocked.isNotEmpty() && fastest != null -> fastest.key to
                    "Your internet provider's DNS blocks ${f.dnsBlocked.joinToString()}; ${name(fastest.key)} finds them (${fastest.value} ms)"
                sys < 0 && fastest != null -> fastest.key to "Your provider's DNS didn't answer properly; ${name(fastest.key)} did (${fastest.value} ms)"
                fastest != null && sys > 150 && fastest.value * 2 < sys -> fastest.key to
                    "Your provider's DNS is slow here ($sys ms); ${name(fastest.key)} answers in ${fastest.value} ms"
                else -> "system" to (if (sys >= 0) "Your provider's DNS answers in $sys ms and blocks nothing the app uses" else "Nothing answered better")
            }
            add(Choice("dns", "Speed and network", "DNS (who looks up addresses)", opts, rec, why))
        }
        if (f.a11y.isNotEmpty()) {
            val keyOnly = com.lekathedon.visionlite.data.QuietA11y.keyOnly(f.a11y)
            add(Choice("quiet_a11y", "Speed and network", "Smoother with key remappers", listOf("on" to "On", "off" to "Off"), if (keyOnly) "on" else "off",
                if (keyOnly) "Only key remappers / launchers use accessibility here: the app's screen needn't be described to them"
                else "A service that reads the screen is on, so the app keeps describing it"))
        }
        run {
            val light = Tier.measured() == Tier.Class.LIGHT
            add(Choice("fast_h", "Speed and network", "Fast horizontal navigation", listOf("on" to "On", "off" to "Off"), if (light) "off" else "on",
                if (light) "On this box quick moves along rows drop frames" else "This box keeps up with quick moves along rows"))
            add(Choice("full_exit", "Speed and network", "Close fully on leaving", listOf("on" to "On", "off" to "Off"), if (light) "on" else "off",
                if (light) "Little memory here: leaving gives it all back to the box" else "Enough memory: the app stays ready, so it opens at once"))
        }

        // Highest quality: what the decoders can show, and what the measured speed carries (a 2.5 h film at 70% of the line).
        val decodeCap = if (f.decodersKnown && !f.uhdDecode) 1080 else 0
        if (decodeCap > 0 && f.mbps == null) add(Choice("max_quality", "Internet", "Highest quality",
            listOf("0" to "Any", "1080" to "1080p", "720" to "720p"), "1080", "The box has no hardware 4K decoder: 4K files would stutter"))
        f.mbps?.let { mbps ->
            val use = mbps * 0.7
            val byLine = when { use < 8 -> "720"; use < 20 -> "1080"; else -> "0" }
            val qRec = if (decodeCap == 1080 && byLine == "0") "1080" else byLine
            val opts = listOf("0" to "Any", "2160" to "4K", "1080" to "1080p", "720" to "720p").filter { decodeCap == 0 || it.first != "2160" }
            add(Choice("max_quality", "Internet", "Highest quality", opts, qRec,
                "Measured %.0f Mb/s".format(mbps) + if (decodeCap == 1080) "; no hardware 4K decoder on this box" else ""))
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
        "perf" -> p.perfClass.value.ifEmpty { Tier.id(Tier.measured()) }
        "trailers" -> on(p.trailers.value)
        "dns" -> p.dns.value
        "quiet_a11y" -> on(p.quietA11y.value)
        "fast_h" -> on(p.fastHorizontal.value)
        "full_exit" -> on(p.fullExit.value)
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
            "perf" -> { p.perfClass.value = v; Tier.setChoice(v) }
            "trailers" -> p.trailers.value = v == "on"
            "dns" -> p.dns.value = v
            "quiet_a11y" -> p.quietA11y.value = v == "on"
            "fast_h" -> p.fastHorizontal.value = v == "on"
            "full_exit" -> p.fullExit.value = v == "on"
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
