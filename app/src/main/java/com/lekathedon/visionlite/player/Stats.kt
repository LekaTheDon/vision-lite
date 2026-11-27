// Vision's playback stats HUD (PlaybackStatsOverlay + PlaybackStatsMpv): rows grouped Playback / Source / Video /
// Audio / Network / System, each with a quality dot, sampled once a second only while the panel is open.
package com.lekathedon.visionlite.player

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.lekathedon.visionlite.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.lekathedon.visionlite.player.mpv.MpvView
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

enum class StatsDot { NONE, GOOD, WARN, BAD }
data class StatsRow(val label: String, val value: String, val dot: StatsDot = StatsDot.NONE, val marquee: Boolean = false)

private enum class StatsGroup(val header: String) { PLAYBACK("Playback"), SOURCE("Source"), VIDEO("Video"), AUDIO("Audio"), NETWORK("Network"), SYSTEM("System") }

private val GROUP: Map<String, StatsGroup> = buildMap {
    listOf("Position", "Engine").forEach { put(it, StatsGroup.PLAYBACK) }
    listOf("Add-on", "File", "Size", "File bitrate", "Source host").forEach { put(it, StatsGroup.SOURCE) }
    listOf("Video", "HDR", "V bitrate", "Dolby Vision", "FEL", "DV core input", "HDMI", "Decoder", "Renderer", "Upscaler", "Dropped", "Display")
        .forEach { put(it, StatsGroup.VIDEO) }
    listOf("Audio", "Route", "Underruns").forEach { put(it, StatsGroup.AUDIO) }
    listOf("Buffer", "Speed", "Load errors", "Rebuffers").forEach { put(it, StatsGroup.NETWORK) }
}

/** Vision's thresholds for the dots. */
private object T {
    const val RECENT_MS = 30_000L
    const val BUFFER_GOOD_S = 10.0
    const val BUFFER_WARN_S = 3.0
    const val BUFFER_GRACE_MS = 10_000L
    const val BUFFER_EOF_MS = 5_000L
    const val CPU_GOOD = 30
    const val CPU_BAD = 60
    const val HEAP_GOOD = 0.60
    const val HEAP_BAD = 0.85
    const val TEMP_WARN_C = 75f
    const val TEMP_BAD_C = 95f
}

/** What ExoPlayer tells about itself while it plays (attached for the player's life; a few counters). */
@OptIn(UnstableApi::class)
class ExoStats : AnalyticsListener {
    var dropped = 0L; var droppedAt = 0L
    var underruns = 0; var underrunAt = 0L
    var loadErrors = 0
    var bandwidth = 0L
    /** The highest estimate this play reached (the last one, taken when the buffer is full, says nothing of the line). */
    var peakBandwidth = 0L
    var videoDecoder: String? = null
    var audioDecoder: String? = null
    var rebuffers = 0; var rebufferAt = 0L
    private var wasReady = false
    /** One log line per playback hitch, with the film position, so a stutter can be traced (logcat tag Hitch). */
    private fun hitch(t: AnalyticsListener.EventTime, what: String) {
        val sec = t.currentPlaybackPositionMs / 1000
        android.util.Log.i("Hitch", "at %d:%02d:%02d %s".format(sec / 3600, sec / 60 % 60, sec % 60, what))
    }
    override fun onDroppedVideoFrames(t: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) { dropped += droppedFrames; droppedAt = SystemClock.elapsedRealtime(); hitch(t, "$droppedFrames frames dropped in ${elapsedMs} ms") }
    override fun onAudioUnderrun(t: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) { underruns++; underrunAt = SystemClock.elapsedRealtime(); hitch(t, "audio ran dry (buffer ${bufferSizeMs} ms, last feed ${elapsedSinceLastFeedMs} ms ago)") }
    override fun onLoadError(t: AnalyticsListener.EventTime, l: LoadEventInfo, m: MediaLoadData, e: java.io.IOException, wasCanceled: Boolean) { loadErrors++; if (!wasCanceled) hitch(t, "download error: ${e.javaClass.simpleName}") }
    override fun onBandwidthEstimate(t: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) { bandwidth = bitrateEstimate; if (bitrateEstimate > peakBandwidth) peakBandwidth = bitrateEstimate }
    override fun onVideoDecoderInitialized(t: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) { videoDecoder = decoderName }
    override fun onAudioDecoderInitialized(t: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) { audioDecoder = decoderName }
    override fun onPlaybackStateChanged(t: AnalyticsListener.EventTime, state: Int) {
        if (state == Player.STATE_BUFFERING && wasReady) { rebuffers++; rebufferAt = SystemClock.elapsedRealtime(); hitch(t, "buffering") }
        wasReady = state == Player.STATE_READY
    }
}

/** What the stats read from the screen: the player, which engine, and the link's own labels. */
class StatsSource(
    val player: Player,
    val engine: Engine,
    val engineNote: String?,
    val exo: ExoStats?,
    val mpv: MpvView?,
    val addon: String?,
    val url: String,
    val fileName: String?,
    /** The link's size from the list (add-on's videoSize or the size in its text): used when the engine can't tell. */
    val linkBytes: Long = 0,
)

private class Sampler {
    private var cpuTicks = -1L
    private var cpuAt = -1L
    private val tick = runCatching { android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK) }.getOrDefault(100L)
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    fun cpu(): Int? {
        val f = runCatching { File("/proc/self/stat").readText().substringAfterLast(')').trim().split(' ') }.getOrNull() ?: return null
        val ticks = (f.getOrNull(11)?.toLongOrNull() ?: return null) + (f.getOrNull(12)?.toLongOrNull() ?: return null)
        val now = SystemClock.elapsedRealtime()
        val out = if (cpuTicks >= 0 && now > cpuAt) (((ticks - cpuTicks) * 1000.0 / tick) / ((now - cpuAt).toDouble() * cores) * 100).toInt().coerceIn(0, 100) else null
        cpuTicks = ticks; cpuAt = now
        return out
    }

    fun chipTemp(): Float? = (0..9).asSequence().mapNotNull { i ->
        val type = runCatching { File("/sys/class/thermal/thermal_zone$i/type").readText().trim() }.getOrNull() ?: return@mapNotNull null
        if (!type.contains("soc", true) && !type.contains("cpu", true)) return@mapNotNull null
        runCatching { File("/sys/class/thermal/thermal_zone$i/temp").readText().trim().toFloat() / 1000f }.getOrNull()
    }.firstOrNull()

    fun cpuClock(): String? = runCatching {
        (0 until cores).mapNotNull { File("/sys/devices/system/cpu/cpu$it/cpufreq/scaling_cur_freq").takeIf { f -> f.canRead() }?.readText()?.trim()?.toLongOrNull() }
            .maxOrNull()?.let { "%.2f GHz".format(Locale.US, it / 1_000_000.0) }
    }.getOrNull()
}

private fun clockText(ms: Long): String {
    val t = ms.coerceAtLeast(0) / 1000
    val h = t / 3600; val m = t % 3600 / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun bytes(b: Long) = when {
    b >= 1_073_741_824L -> "%.2f GB".format(Locale.US, b / 1_073_741_824.0)
    b >= 1_048_576L -> "%.1f MB".format(Locale.US, b / 1_048_576.0)
    else -> "${b / 1024} KB"
}

private fun bufferDot(aheadS: Double, posMs: Long, durMs: Long): StatsDot = when {
    durMs > 0 && durMs - (posMs + aheadS * 1000) <= T.BUFFER_EOF_MS -> StatsDot.GOOD
    posMs < T.BUFFER_GRACE_MS -> StatsDot.NONE
    aheadS >= T.BUFFER_GOOD_S -> StatsDot.GOOD
    aheadS >= T.BUFFER_WARN_S -> StatsDot.WARN
    else -> StatsDot.BAD
}

private fun recency(total: Number, at: Long): StatsDot = when {
    total.toLong() <= 0 -> StatsDot.GOOD
    SystemClock.elapsedRealtime() - at <= T.RECENT_MS -> StatsDot.BAD
    else -> StatsDot.WARN
}

private fun mbit(bps: Double) = "%.1f Mbit/s".format(Locale.US, bps / 1_000_000.0)

@OptIn(UnstableApi::class)
private fun sample(src: StatsSource, sampler: Sampler, context: Context): List<StatsRow> = buildList {
    val p = src.player
    val pos = p.currentPosition
    val dur = p.duration.takeIf { it > 0 } ?: 0L
    add(StatsRow("Position", if (dur > 0) "${clockText(pos)} / ${clockText(dur)}" else clockText(pos)))
    add(StatsRow("Engine", src.engine.label + (src.engineNote?.let { " · $it" } ?: ""), marquee = (src.engineNote?.length ?: 0) > 30))
    src.addon?.takeIf { it.isNotBlank() }?.let { add(StatsRow("Add-on", it)) }
    src.fileName?.let { add(StatsRow("File", it.replace("\n", " · "), marquee = true)) }
    runCatching { java.net.URI(src.url).host }.getOrNull()?.let { add(StatsRow("Source host", it)) }
    // Size on both engines: MPV's file size, else the link's size from the list (ExoPlayer never reported one).
    ((if (src.engine == Engine.MPV) src.mpv?.prop("file-size")?.toLongOrNull() else null)?.takeIf { it > 0 } ?: src.linkBytes.takeIf { it > 0 })?.let { size ->
        add(StatsRow("Size", bytes(size)))
        if (dur > 0) add(StatsRow("File bitrate", mbit(size * 8_000.0 / dur)))
    }
    val display = (context as? Activity)?.let { a -> @Suppress("DEPRECATION") a.windowManager.defaultDisplay.mode }

    if (src.engine == Engine.MPV && src.mpv != null) {
        val m = src.mpv
        val prop = m::prop
        val w = prop("video-params/w") ?: prop("current-tracks/video/demux-w")
        val h = prop("video-params/h") ?: prop("current-tracks/video/demux-h")
        val fps = (prop("container-fps") ?: prop("estimated-vf-fps"))?.toDoubleOrNull()?.takeIf { it > 0 }
        listOfNotNull(if (w != null && h != null) "$w×$h" else null, fps?.let { "%.3f".format(Locale.US, it).trimEnd('0').trimEnd('.') + " fps" },
            prop("current-tracks/video/codec")?.uppercase()).joinToString(" · ").takeIf { it.isNotBlank() }?.let { add(StatsRow("Video", it)) }
        val dvProfile = prop("current-tracks/video/dolby-vision-profile")
        when (prop("video-params/gamma")?.lowercase(Locale.ROOT)) { null -> null; "pq" -> "HDR10 / PQ"; "hlg" -> "HLG"; else -> "SDR" }?.let { tr ->
            // MPV plays the base layer only; the TV's Dolby Vision processing needs ExoPlayer.
            add(StatsRow("HDR", if (!dvProfile.isNullOrBlank()) "Dolby Vision P$dvProfile file · plays as $tr" else tr))
        }
        prop("video-bitrate")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { add(StatsRow("V bitrate", mbit(it))) }
        val hw = prop("hwdec-current")
        add(StatsRow("Decoder", if (hw == null || hw == "no") "Software (CPU)" else "Hardware · $hw", if (hw == null || hw == "no") StatsDot.WARN else StatsDot.GOOD))
        prop("current-vo")?.let { vo ->
            add(StatsRow("Renderer", when (vo) { "gpu-next" -> "GPU · gpu-next (libplacebo)"; "gpu" -> "GPU · gpu"; "mediacodec_embed" -> "Video layer · mediacodec_embed"; else -> vo }))
            if (vo.startsWith("gpu")) prop("scale")?.let { add(StatsRow("Upscaler", it)) }
        }
        val vo = prop("frame-drop-count")?.toLongOrNull() ?: 0L
        val dec = prop("decoder-frame-drop-count")?.toLongOrNull() ?: 0L
        add(StatsRow("Dropped", "$vo output · $dec decoder", if (vo + dec == 0L) StatsDot.GOOD else StatsDot.WARN))
        Hud.refresh(); Hud.box.hdmi?.let { add(StatsRow("HDMI", it)) }
        display?.let { add(StatsRow("Display", "${it.physicalWidth}×${it.physicalHeight} · %.2f Hz".format(Locale.US, it.refreshRate))) }
        val ch = prop("audio-params/channel-count") ?: prop("current-tracks/audio/demux-channel-count")
        val sr = prop("audio-params/samplerate")?.toIntOrNull()
        listOfNotNull(prop("current-tracks/audio/codec")?.uppercase(), prop("current-tracks/audio/codec-profile"), ch?.let { "$it ch" },
            sr?.let { "%.1f kHz".format(Locale.US, it / 1000.0).replace(".0 kHz", " kHz") }).joinToString(" · ").takeIf { it.isNotBlank() }
            ?.let { add(StatsRow("Audio", it)) }
        val spdif = prop("audio-spdif").orEmpty()
        val codec = prop("current-tracks/audio/codec").orEmpty()
        prop("current-ao")?.let { ao -> add(StatsRow("Route", if (codec.isNotEmpty() && spdif.split(',').any { codec.startsWith(it) }) "$ao · passthrough (bitstream)" else "$ao · decoded (PCM)")) }
        prop("demuxer-cache-duration")?.toDoubleOrNull()?.let { add(StatsRow("Buffer", "%.1f s ahead".format(Locale.US, it), bufferDot(it, pos, dur))) }
        prop("cache-speed")?.toDoubleOrNull()?.let { add(StatsRow("Speed", if (it < 125) "Idle" else mbit(it * 8))) }
    } else {
        val exo = p as? ExoPlayer
        val v = exo?.videoFormat
        val a = exo?.audioFormat
        val st = src.exo
        v?.let { f ->
            listOfNotNull(if (f.width > 0) "${f.width}×${f.height}" else null, f.frameRate.takeIf { it > 0 }?.let { "%.3f".format(Locale.US, it).trimEnd('0').trimEnd('.') + " fps" },
                f.sampleMimeType?.substringAfter('/')?.uppercase(), bitDepthOf(f)?.let { "$it-bit" }).joinToString(" · ").let { add(StatsRow("Video", it)) }
            f.bitrate.takeIf { it > 0 }?.let { add(StatsRow("V bitrate", mbit(it.toDouble()))) }
        }
        // Dolby Vision / FEL / HDR in → out / DV core / HDMI / decoder, read from the box (Hud).
        Hud.rows(v, st?.videoDecoder ?: Hud.decoder).forEach { line ->
            val label = line.substringBefore("  "); val value = line.substringAfter("  ")
            add(StatsRow(label, value, marquee = value.length > 40))
        }
        add(StatsRow("Dropped", "${st?.dropped ?: 0}", recency(st?.dropped ?: 0, st?.droppedAt ?: 0)))
        display?.let { d ->
            val fps = v?.frameRate ?: 0f
            val match = fps > 0 && (d.refreshRate / fps).let { k -> kotlin.math.abs(k - kotlin.math.round(k)) < 0.02f }
            add(StatsRow("Display", "${d.physicalWidth}×${d.physicalHeight} · %.2f Hz".format(Locale.US, d.refreshRate),
                if (fps <= 0) StatsDot.NONE else if (match) StatsDot.GOOD else StatsDot.WARN))
        }
        a?.let { f ->
            listOfNotNull(audioName(f.sampleMimeType), f.channelCount.takeIf { it > 0 }?.let { "$it ch" },
                f.sampleRate.takeIf { it > 0 }?.let { "%.1f kHz".format(Locale.US, it / 1000.0).replace(".0 kHz", " kHz") },
                f.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbit/s" }, f.language).joinToString(" · ").let { add(StatsRow("Audio", it, marquee = it.length > 40)) }
        }
        val dec = st?.audioDecoder
        add(StatsRow("Route", when {
            dec == null && a != null -> "Passthrough (bitstream) → HDMI"
            dec?.contains("ffmpeg", true) == true -> "FFmpeg decode → PCM"
            dec != null -> "Decoded → PCM · $dec"
            else -> "—"
        }, marquee = true))
        add(StatsRow("Underruns", "${st?.underruns ?: 0}", recency(st?.underruns ?: 0, st?.underrunAt ?: 0)))
        val ahead = ((p.bufferedPosition - pos).coerceAtLeast(0)) / 1000.0
        add(StatsRow("Buffer", "%.1f s ahead".format(Locale.US, ahead), bufferDot(ahead, pos, dur)))
        st?.bandwidth?.takeIf { it > 0 }?.let { add(StatsRow("Speed", mbit(it.toDouble()))) }
        st?.let { if (it.loadErrors > 0) add(StatsRow("Load errors", "${it.loadErrors}", StatsDot.WARN)) }
        st?.let { add(StatsRow("Rebuffers", "${it.rebuffers}", recency(it.rebuffers, it.rebufferAt))) }
    }

    // System (both engines).
    sampler.cpu()?.let { add(StatsRow("App CPU", "$it %", when { it <= T.CPU_GOOD -> StatsDot.GOOD; it >= T.CPU_BAD -> StatsDot.BAD; else -> StatsDot.WARN })) }
    val rt = Runtime.getRuntime()
    val used = (rt.totalMemory() - rt.freeMemory()) / 1_048_576; val max = rt.maxMemory() / 1_048_576
    val native = android.os.Debug.getNativeHeapAllocatedSize() / 1_048_576
    val frac = used.toDouble() / max.coerceAtLeast(1)
    add(StatsRow("Memory", "Java $used/$max MB · native $native MB", when { frac <= T.HEAP_GOOD -> StatsDot.GOOD; frac >= T.HEAP_BAD -> StatsDot.BAD; else -> StatsDot.WARN }))
    sampler.chipTemp()?.let { add(StatsRow("Chip temp", "%.0f °C".format(Locale.US, it), when { it >= T.TEMP_BAD_C -> StatsDot.BAD; it >= T.TEMP_WARN_C -> StatsDot.WARN; else -> StatsDot.GOOD })) }
    sampler.cpuClock()?.let { add(StatsRow("CPU clock", it)) }
}

private fun bitDepthOf(f: Format): Int? = f.colorInfo?.lumaBitdepth?.takeIf { it > 0 } ?: f.codecs?.lowercase()?.let { c ->
    when { c.startsWith("dvh") || c.startsWith("hvc1.2.") || c.startsWith("hev1.2.") -> 10; c.startsWith("hvc1.1.") || c.startsWith("hev1.1.") -> 8; else -> null }
}

private fun audioName(mime: String?): String? = when (mime) {
    "audio/eac3-joc" -> "Atmos (DD+)"; "audio/eac3" -> "DD+"; "audio/ac3" -> "Dolby Digital"; "audio/true-hd" -> "TrueHD"
    "audio/vnd.dts" -> "DTS"; "audio/vnd.dts.hd" -> "DTS-HD"; "audio/vnd.dts.hd;profile=lbr" -> "DTS Express"; "audio/mp4a-latm" -> "AAC"
    "audio/opus" -> "Opus"; "audio/flac" -> "FLAC"; "audio/raw" -> "PCM"; "audio/mpeg" -> "MP3"
    else -> mime?.substringAfter('/')
}

private val DotGood = Color(0xFF4CAF50)
private val DotWarn = Color(0xFFFFC107)
private val DotBad = Color(0xFFF44336)

/** The panel: fixed 380 dp wide, top right; rows refreshed once a second while it is open. */
@Composable
internal fun StatsPanel(src: StatsSource, modifier: Modifier) {
    val context = LocalContext.current
    val sampler = remember { Sampler() }
    var rows by remember { mutableStateOf(emptyList<StatsRow>()) }
    LaunchedEffect(src) {
        while (true) {
            rows = runCatching { sample(src, sampler, context) }.getOrDefault(emptyList())
            delay(1_000)
        }
    }
    val sections = rows.groupBy { GROUP[it.label] ?: StatsGroup.SYSTEM }
    Column(
        modifier.padding(top = 32.dp, end = 32.dp).width(380.dp).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        StatsGroup.entries.forEach { g ->
            val list = sections[g] ?: return@forEach
            Text(g.header.uppercase(), color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.5.sp,
                modifier = Modifier.padding(top = 4.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f)))
            list.forEach { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(when (r.dot) { StatsDot.GOOD -> DotGood; StatsDot.WARN -> DotWarn; StatsDot.BAD -> DotBad; StatsDot.NONE -> Color.Transparent }, CircleShape))
                    Text(r.label, color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(88.dp))
                    Text(r.value, color = Color.White, fontSize = 10.sp, maxLines = if (r.marquee) 1 else 2, softWrap = !r.marquee,
                        modifier = Modifier.weight(1f).then(if (r.marquee) Modifier.basicMarquee(iterations = 3, initialDelayMillis = 2000) else Modifier))
                }
            }
        }
        if (src.engine == Engine.MPV && rows.size <= 3) Text("MPV is still opening the file…", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
    }
}
