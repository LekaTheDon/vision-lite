package com.lekathedon.visionlite.player

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.ui.PlayerView
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.player.dv.DvRoute
import com.lekathedon.visionlite.player.fel.AmlDvFelVideoRenderer
import com.lekathedon.visionlite.player.fel.AmlFelSupport
import com.lekathedon.visionlite.ui.Screen
import kotlin.math.abs
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Set when the player closes, so Home refreshes Continue Watching right away. */
object PlayerReturn {
    @Volatile private var flag = false
    fun mark() { flag = true }
    fun consume(): Boolean = flag.also { flag = false }
}

/**
 * Native FEL first (DV7 only, when chosen and the box can), then the normal decoders; FFmpeg audio after the box's own
 * decoders (DTS / TrueHD / anything the soundbar can't take and the box can't decode becomes PCM).
 */
@OptIn(UnstableApi::class)
private class Renderers(context: Context, private val fel: Boolean, private val blocked: Set<String> = emptySet(), private val dv81: Boolean = false) : DefaultRenderersFactory(context) {
    /** Sync (Vision's SubtitleOffsetRenderer): text is drawn at the position moved by the audio and subtitle delays. */
    override fun buildTextRenderers(context: Context, output: androidx.media3.exoplayer.text.TextOutput, outputLooper: android.os.Looper,
                                    extensionRendererMode: Int, out: ArrayList<Renderer>) {
        val start = out.size
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
        for (i in start until out.size) out[i] = object : androidx.media3.exoplayer.ForwardingRenderer(out[i]) {
            override fun render(positionUs: Long, elapsedRealtimeUs: Long) =
                super.render((positionUs + Sync.audioMs * 1000 - (Sync.subMs + Sync.autoSubMs) * 1000).coerceAtLeast(0L), elapsedRealtimeUs)
        }
    }
    init {
        setEnableDecoderFallback(true); setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
        // Convert to 8.1: the TV's Dolby Vision decoder takes the converted picture (Vision's fallback selector).
        if (dv81) setMediaCodecSelector(com.lekathedon.visionlite.player.dv.DvDecoders.selector(true))
    }
    /** This TV › Sound: a format set to "Decode in the box" is never sent as a bitstream; FFmpeg/MediaCodec decode it. */
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink? {
        // Sound.kt: stereo mix, night mode, dialogue and volume boost (inactive while all off or on a bitstream).
        val sink = androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput).setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf<androidx.media3.common.audio.AudioProcessor>(SoundProcessor(context))).build()
        if (blocked.isEmpty()) return sink
        return object : androidx.media3.exoplayer.audio.ForwardingAudioSink(sink) {
            override fun supportsFormat(format: androidx.media3.common.Format) = format.sampleMimeType !in blocked && super.supportsFormat(format)
            override fun getFormatSupport(format: androidx.media3.common.Format) =
                if (format.sampleMimeType in blocked) androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)
        }
    }
    override fun buildVideoRenderers(
        context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
        eventHandler: Handler, eventListener: VideoRendererEventListener, allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>
    ) {
        // Vision's FEL renderer, ahead of MediaCodec so it wins the DV7 track.
        if (fel) out.add(AmlDvFelVideoRenderer(eventHandler, eventListener))
        val start = out.size
        super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs, out)
        // Convert to 8.1: the stock video renderer swapped for one that puts the Dolby Vision decoder first.
        if (dv81) for (i in start until out.size) if (out[i].javaClass == androidx.media3.exoplayer.video.MediaCodecVideoRenderer::class.java)
            out[i] = com.lekathedon.visionlite.player.dv.DvFirstVideoRenderer(androidx.media3.exoplayer.video.MediaCodecVideoRenderer.Builder(context)
                .setCodecAdapterFactory(codecAdapterFactory).setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs).setEnableDecoderFallback(enableDecoderFallback)
                .setEventHandler(eventHandler).setEventListener(eventListener).setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY))
    }
}

/** Codecs MPV may send as a bitstream: the ones the soundbar reports over HDMI (as ExoPlayer's passthrough). */
private fun spdifCodecs(context: Context): String {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    val enc = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        .filter { it.type == android.media.AudioDeviceInfo.TYPE_HDMI || it.type == android.media.AudioDeviceInfo.TYPE_HDMI_ARC ||
            (android.os.Build.VERSION.SDK_INT >= 31 && it.type == android.media.AudioDeviceInfo.TYPE_HDMI_EARC) }
        .flatMap { it.encodings.toList() }.toSet()
    val p = App.instance.prefs
    return buildList {
        if (android.media.AudioFormat.ENCODING_AC3 in enc && p.ptAc3.value) add("ac3")
        if (android.media.AudioFormat.ENCODING_E_AC3 in enc && p.ptEac3.value) add("eac3")
        if (android.media.AudioFormat.ENCODING_DTS in enc && p.ptDts.value) add("dts")
        if (android.media.AudioFormat.ENCODING_DTS_HD in enc && p.ptDtshd.value) add("dts-hd")
        if (android.media.AudioFormat.ENCODING_DOLBY_TRUEHD in enc && p.ptTruehd.value) add("truehd")
    }.joinToString(",")
}

/** The TV takes some HDR (unknown counts as yes, so nothing moves on a guess). */
private fun displayShowsHdr(context: Context): Boolean = runCatching {
    @Suppress("DEPRECATION")
    val types = DisplayMatch.activityOf(context)?.window?.decorView?.display?.hdrCapabilities?.supportedHdrTypes
    types == null || types.isNotEmpty()
}.getOrDefault(true)

/** ExoPlayer mime types the user set to "Decode in the box" (This TV › Sound). */
@OptIn(UnstableApi::class)
private fun decodedInBox(p: com.lekathedon.visionlite.data.Prefs, stereo: Boolean = false): Set<String> = buildSet {
    // TV speakers / headphones: nothing goes out as a bitstream; it is decoded here and mixed to stereo (Sound.kt).
    val all = stereo
    if (all || !p.ptAc3.value) add(androidx.media3.common.MimeTypes.AUDIO_AC3)
    if (all || !p.ptEac3.value) { add(androidx.media3.common.MimeTypes.AUDIO_E_AC3); add(androidx.media3.common.MimeTypes.AUDIO_E_AC3_JOC) }
    if (all || !p.ptTruehd.value) add(androidx.media3.common.MimeTypes.AUDIO_TRUEHD)
    if (all || !p.ptDts.value) { add(androidx.media3.common.MimeTypes.AUDIO_DTS); add(androidx.media3.common.MimeTypes.AUDIO_DTS_EXPRESS) }
    if (all || !p.ptDtshd.value) { add(androidx.media3.common.MimeTypes.AUDIO_DTS_HD); add(androidx.media3.common.MimeTypes.AUDIO_DTS_X) }
}

/**
 * This TV › Playback buffer, inside the box's memory class (device/Tier.kt): Standard = media3's defaults where the
 * heap has room for them (~140 MB of video), else the same durations held to the class ceiling.
 */
@OptIn(UnstableApi::class)
private fun loadControl(choice: String): androidx.media3.exoplayer.DefaultLoadControl? {
    val mb = 1024 * 1024
    val ceiling = com.lekathedon.visionlite.device.Tier.exoBufferCeilingMb
    return when (choice) {
        "small" -> androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 30_000, 1_500, 3_000).setTargetBufferBytes(minOf(48, ceiling) * mb).build()
        "large" -> androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(50_000, 120_000, 2_500, 5_000)
            .setTargetBufferBytes(minOf(200, ceiling) * mb).build()
        else -> if (com.lekathedon.visionlite.device.Tier.current == com.lekathedon.visionlite.device.Tier.Class.FULL || ceiling >= 150) null
            else androidx.media3.exoplayer.DefaultLoadControl.Builder().setTargetBufferBytes(ceiling * mb).build()
    }
}

/** MPV's read-ahead for the same choice (native memory, outside the app's Java heap), sized by the box's class. */
private fun mpvBufferMb(choice: String) = com.lekathedon.visionlite.device.Tier.mpvBufferMb(choice)

/**
 * Two engines, as in Vision: ExoPlayer (passthrough, DV7 native FEL, VS10, the box's video layer) and MPV (software
 * decoders, styled subtitles, formats ExoPlayer can't open). Auto picks one per link and corrects itself once the
 * file's tracks are known; the backup engine takes over when one can't play a file; the Engine button switches by hand.
 * Every switch restarts the same link at the same spot.
 */
@OptIn(UnstableApi::class)
@Composable
fun Player(nav: com.lekathedon.visionlite.ui.Nav, s: Screen.Player) {
    val context = LocalContext.current
    // Memory and what's starting, for problem reports (the link itself is blanked there).
    androidx.compose.runtime.LaunchedEffect(s.url) {
        android.util.Log.i("VLPlayer", "start type=${s.type} id=${s.id} title=${s.title} host=${runCatching { java.net.URI(s.url).host }.getOrNull()}")
        if (s.type != "live") kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.lekathedon.visionlite.data.LastLink.record(s.url, s.headers, s.title) }
        com.lekathedon.visionlite.report.Mem.snapshot(context, "player-start")
    }
    val prefs = App.instance.prefs
    val session = remember(s.url) { EngineSession(s.url) }
    // null until the engine is decided (Auto may look the title up for a moment, as Vision does for anime).
    var engine by remember(s.url) { mutableStateOf<Engine?>(null) }
    var gen by remember(s.url) { mutableIntStateOf(0) }
    var switchNote by remember(s.url) { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(s.url) {
        engine = when (prefs.engine.value) {
            "exo" -> Engine.EXO
            "mpv" -> if (s.type == "live") Engine.EXO else Engine.MPV
            else -> {
                session.smartAuto = true
                val quick = EngineRules.autoPick(session, s.name, s.id, s.type, emptyList(), null)
                if (quick == Engine.MPV || session.reason != "default → ExoPlayer" || !s.id.startsWith("tt")) quick
                else {
                    val meta = kotlinx.coroutines.withTimeoutOrNull(1_500) { runCatching { App.instance.client.meta(s.type, s.id.substringBefore(':')) }.getOrNull() }
                    EngineRules.autoPick(session, s.name, s.id, s.type, meta?.genres.orEmpty(), null)
                }
            }
        }.also { session.engine = it; if (it == Engine.MPV && EngineRules.namedHi10p(s.name)) session.mpvSoftware = true }
    }
    val current = engine ?: run { Box(Modifier.fillMaxSize().background(Color.Black)); return }

    fun switchTo(target: Engine, why: String, at: Long) {
        if (target == current) return
        session.resumeAt = at.coerceAtLeast(0)
        session.engine = target
        if (why != "smart-auto") session.smartAuto = false
        switchNote = "Switching to ${target.label}…"
        engine = target
        gen++
    }

    // The file itself turned out to be Dolby Vision the DV route converts (DV7 / DV5 not named "DV"): reopened on it.
    var forceDv by remember(s.url) { mutableStateOf(false) }
    val dvOn = remember(s.url) { booleanArrayOf(false) }
    var felAllowed by remember(s.url) { mutableStateOf(prefs.dv7.value == "fel" && AmlFelSupport.nativeFelUsableOnce()) }
    // A dead add-on subtitle link must never stop the film: on a load error the file restarts without them.
    var addonSubs by remember(s.url) { mutableStateOf(true) }
    // Any stored choice but Off matches (the old "seamless" never switched an HDMI TV).
    val afr = prefs.afr.value != "off"
    val resMatch = prefs.matchRes.value
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var failNote by remember(s.url) { mutableStateOf<String?>(null) }
    remember(s.id) { Sync.open(if (s.type == "live") "" else s.id); 0 }
    // Live rewind: a fresh ring for this channel before the player opens it (Timeshift.kt); deleted when the player goes.
    remember(s.url) { if (s.type == "live") Timeshift.begin(s.url, s.headers); 0 }
    DisposableEffect(s.url) { onDispose { if (s.type == "live") Timeshift.end(s.url) } }
    var firstFrame by remember(s.url, gen) { mutableStateOf(false) }
    val exoStats = remember(s.url, gen, felAllowed, addonSubs) { ExoStats() }
    // True while DV7 plays on the native FEL path: seek thumbnails stay off then (they took a third hardware decoder
    // and a second download of the remux, and the film stuttered; Vision/Arc never let thumbnails touch FEL either).
    val felNow = remember(s.url) { java.util.concurrent.atomic.AtomicBoolean(false) }
    // HDR/Dolby Vision film on ExoPlayer: its subtitles take Vision's HDR style (restyled in the view's update).
    var hdrVideo by remember(s.url, gen) { mutableStateOf(false) }
    var sourcesOpen by remember(s.url) { mutableStateOf(false) }
    // Back from Sources lands on the Sources button (the controls are rebuilt when the panel closes).
    var sourcesClosed by remember(s.url) { mutableStateOf(false) }
    // MPV: the film is HDR/DV (found once its picture is known), for restyling from the subtitle style panel.
    var mpvHdr by remember(s.url, gen) { mutableStateOf(false) }
    val mpvView = remember(s.url, gen) {
        if (current != Engine.MPV) null else com.lekathedon.visionlite.player.mpv.MpvView(context).apply {
            init(session.mpvSoftware, if (Sound.stereo(context)) "" else spdifCodecs(context), mpvBufferMb(prefs.buffer.value), session.mpvGpuNext, Sound.mpvOptions(Sound.stereo(context)))
            languages(OriginalLang.withIso3(OriginalLang.audioLangs(prefs.audioLang.value, prefs.audioLang2.value, s.id)), langs(prefs.subLang.value, prefs.subLang2.value).toList(), prefs.subsWhen.value == "off")
            subtitleStyle(when (prefs.subSize.value) { "s" -> 0.8; "l" -> 1.25; "xl" -> 1.5; else -> 1.0 }, prefs.subStyle.value)
        }
    }
    // libass (styled ASS/SSA subtitles drawn with the file's own fonts and positions) for ExoPlayer, Vision's overlay mode.
    val assRef = remember { arrayOfNulls<io.github.peerless2012.ass.media.AssHandler>(1) }
    val player: MediaPlayer = remember(s.url, gen, felAllowed, addonSubs) {
        if (current == Engine.MPV) {
            assRef[0] = null
            com.lekathedon.visionlite.player.mpv.MpvPlayer(mpvView!!, s.url, s.headers, session.resumeAt).apply {
                Hud.decoder = null
                onLoaded = {
                    view.delays(Sync.audioMs, Sync.subMs)
                    // Vision's HDR subtitle style on MPV: once the picture's transfer is known (PQ/HLG or a DV profile).
                    if (prefs.subHdr.value) scope.launch {
                        repeat(20) {
                            val g = view.prop("video-params/gamma")
                            val dvp = view.prop("current-tracks/video/dolby-vision-profile")
                            if (!g.isNullOrBlank() || !dvp.isNullOrBlank()) {
                                if (g == "pq" || g == "hlg" || !dvp.isNullOrBlank()) mpvHdr = true
                                if (mpvHdr)
                                    view.subtitleStyle(when (prefs.subSize.value) { "s" -> 0.8; "l" -> 1.25; "xl" -> 1.5; else -> 1.0 }, prefs.subStyle.value, hdr = true)
                                return@launch
                            }
                            kotlinx.coroutines.delay(250)
                        }
                    }
                    if (addonSubs) com.lekathedon.visionlite.addons.Subs.ranked(s.id, s.name).forEach { sub -> view.addSubtitle(sub.url, "${sub.lang} · add-on", sub.lang) }
                    if (afr || resMatch) DisplayMatch.apply(context, view.prop("container-fps")?.toFloatOrNull() ?: 0f,
                        view.prop("width")?.toIntOrNull() ?: 0, view.prop("height")?.toIntOrNull() ?: 0, afr, resMatch)
                    // Smart Auto: a Dolby Vision file that started on MPV goes to ExoPlayer's video layer (one look, before it plays).
                    if (session.smartAuto && !session.rerouted && !session.mpvGpuNext && !view.prop("current-tracks/video/dolby-vision-profile").isNullOrBlank()) {
                        session.rerouted = true; session.reason = EngineRules.REASON_DV_FROM_MPV
                        switchTo(Engine.EXO, "smart-auto", currentPosition)
                    }
                }
            }
        } else {
            val http = OkHttpDataSource.Factory(Net.http).setDefaultRequestProperties(s.headers)
            // Live: the bytes the player downloads are also kept for rewind (no second connection).
            if (s.type == "live") Timeshift.leave()
            // Add-on subtitles are cleaned up and their times noted as they load (SubFix); the rest passes straight through.
            SubFix.begin(if (addonSubs && s.type != "live") com.lekathedon.visionlite.addons.Subs.forId(s.id).map { it.url } else emptyList())
            val data = SubFix.factory(androidx.media3.datasource.DefaultDataSource.Factory(context, if (s.type == "live") Timeshift.tee(http) else com.lekathedon.visionlite.trailer.ChunkedYt.Factory(http)))
            // This TV › Dolby Vision: Vision's converter (DV7/DV5 -> 8.1 via libdovi, HDR10 base layer, HDR10+ strip) reads MKV
            // through Vision's own Matroska extractor. libass needs its own MKV reader, so when both are on, the DV path
            // is taken only for links named as Dolby Vision (anime keeps its styled subtitles).
            val dv = DvRoute.of(prefs, felAllowed)
            // The converter is chosen up front for every film/show that isn't anime (user 2026-11-26: a Xiaomi Box S set to
            // Convert 8.1 never converted, the remux's name didn't say DV and the plain reader didn't flag profile 7).
            // Anime keeps libass's styled subtitles and joins the converter only when its file turns out to be DV.
            val anime = com.lekathedon.visionlite.tb.TitleFacts.get(s.id)?.kind == com.lekathedon.visionlite.tb.TitleFacts.Kind.ANIME
            val useDv = dv != null && (forceDv || !prefs.libass.value || !anime || s.type == "live" || DvRoute.namedDv(s.name.ifBlank { s.title }))
            if (dv != null) android.util.Log.i("DvRoute", "open: converter ${if (useDv) "on" else "off (anime, libass)"}")
            dvOn[0] = useDv
            val ass = if (prefs.libass.value && s.type != "live" && !useDv) io.github.peerless2012.ass.media.AssHandler(
                // CUES (user 2026-11-26, anime stuttered on every file, 4K and 90s alike): libass draws each subtitle line
                // once into a picture the subtitle view shows until the line changes. The old OVERLAY_OPEN_GL mode
                // redrew a full-screen layer over the video for every frame. Fonts, colours and positions stay;
                // only moving effects (karaoke sweeps) hold still.
                io.github.peerless2012.ass.media.type.AssRenderType.CUES,
                io.github.peerless2012.ass.media.AssHandlerConfig(maxRenderPixels = 1920 * 1080)) else null
            assRef[0] = ass
            val renderers: androidx.media3.exoplayer.RenderersFactory = Renderers(context, felAllowed, decodedInBox(prefs, Sound.stereo(context)), dv81 = useDv && dv!!.config.active).let { r ->
                if (ass != null) io.github.peerless2012.ass.media.factory.AssRenderersFactory(ass, r) else r }
            // MKV: the file's declared frame rate goes to the decoder (MkvFrameRate), libass reader when styled subs are on.
            val base = androidx.media3.extractor.DefaultExtractorsFactory()
            // SubFix.capture goes outside: the MKV swaps inside look for the plain Matroska reader.
            val sources = if (useDv) DefaultMediaSourceFactory(data, SubFix.capture(DvRoute.factory(base, dv!!)))
            else if (ass == null) DefaultMediaSourceFactory(data, SubFix.capture(MkvFrameRate.factory(base, null))) else {
                val parsers = io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory(ass)
                DefaultMediaSourceFactory(data, SubFix.capture(MkvFrameRate.factory(base, parsers to ass))).also { it.setSubtitleParserFactory(parsers) }
            }
            ExoPlayer.Builder(context, renderers)
                // One playback thread of Lite's own, pinned to the big cores on boxes that have them (BigCores).
                .setPlaybackLooper(BigCores.playbackLooper)
                .setMediaSourceFactory(Sync.wrap(sources))
                .apply { loadControl(prefs.buffer.value)?.let { setLoadControl(it) } }
                .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                .build().apply {
                    ass?.init(this)
                    Hud.decoder = if (felAllowed) "native FEL renderer (if the file is DV7)" else null
                    addAnalyticsListener(exoStats)
                    addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                        override fun onVideoDecoderInitialized(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, name: String, initMs: Long, durMs: Long) { Hud.decoder = name }
                    })
                    trackSelectionParameters = trackPrefs(trackSelectionParameters, s.id)
                    // Measured, not guessed: the frame rate from the first frames' timestamps, every file. The header's rate
                    // (if any) switches early; when the frames say otherwise (live channels, mislabelled files) they win.
                    if (afr) {
                        val stamps = ArrayList<Long>(48)
                        val main = Handler(android.os.Looper.getMainLooper())
                        setVideoFrameMetadataListener { t, _, f, _ ->
                            if (stamps.size >= 48) return@setVideoFrameMetadataListener
                            stamps.add(t)
                            if (stamps.size == 48) {
                                // Median frame gap, not first-to-last span: dropped/skipped frames at a live start made
                                // a 25 fps channel read 20.3 fps (2026-11-24). Only a real video rate counts.
                                val gaps = stamps.sorted().drop(8).zipWithNext { a, b -> b - a }.filter { it > 0 }.sorted()
                                val raw = if (gaps.isEmpty()) 0f else 1_000_000f / gaps[gaps.size / 2]
                                val fps = DisplayMatch.standard(raw)
                                val header = f.frameRate
                                val differs = fps != null && (header <= 0 || kotlin.math.abs(DisplayMatch.snap(fps) - DisplayMatch.snap(header)) / fps > 0.02f)
                                android.util.Log.i("DisplayMatch", "measured %.3f fps -> %s (header %.3f)".format(raw, fps, header))
                                if (fps != null && differs) main.post { DisplayMatch.apply(context, fps, f.width, f.height, true, resMatch) }
                            }
                        }
                    }
                    setMediaItem(if (addonSubs) withSubs(s) else MediaItem.fromUri(s.url), session.resumeAt)
                    prepare()
                    playWhenReady = true
                }
        }
    }
    androidx.compose.runtime.LaunchedEffect(player) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { BigCores.pinForPlayback() } }
    // Subtitle auto-sync (SubFix): the add-on subtitle on now is lined up with the file's own lines, every 15 s.
    androidx.compose.runtime.LaunchedEffect(player) {
        val exo = player as? ExoPlayer ?: return@LaunchedEffect
        kotlinx.coroutines.delay(8_000)
        while (true) { if (exo.isPlaying) runCatching { SubFix.tick(exo, exo.currentPosition) }; kotlinx.coroutines.delay(15_000) }
    }
    // Audio "Original": normally known already (looked up when the links page opened); if not, looked up now and the
    // audio moves to it once (only if you haven't picked a track yourself meanwhile).
    androidx.compose.runtime.LaunchedEffect(player) {
        if (OriginalLang.ORIGINAL !in listOf(prefs.audioLang.value, prefs.audioLang2.value) || OriginalLang.cached(s.id.substringBefore(':')) != null) return@LaunchedEffect
        OriginalLang.resolve(s.id, s.type) ?: return@LaunchedEffect
        val want = OriginalLang.audioLangs(prefs.audioLang.value, prefs.audioLang2.value, s.id)
        (player as? ExoPlayer)?.let { it.trackSelectionParameters = trackPrefs(it.trackSelectionParameters, s.id) }
        mpvView?.let { v ->
            val forms = OriginalLang.withIso3(want)
            v.tracks().filter { it.type == "audio" }.firstOrNull { t -> t.lang?.lowercase()?.let { it in forms } == true }?.let { if (!it.selected) v.selectAudio(it.id) }
        }
    }
    // Sports failover (Vision, idea from CouchSide): a game's channel that fails, or shows nothing for 15 s, hands over
    // to the next channel found for the same game. Only for channels started from Sports; other channels are left alone.
    fun sportsFailover(): Boolean {
        if (s.type != "live") return false
        val key = com.lekathedon.visionlite.iptv.Iptv.byUrl(s.url)?.key ?: return false
        val (game, ch) = com.lekathedon.visionlite.sport.Host.next(key) ?: return false
        failNote = "This channel won't play · trying ${ch.name} for $game"
        com.lekathedon.visionlite.iptv.Iptv.channelsByKey(listOf(ch.key)).firstOrNull()?.let { com.lekathedon.visionlite.iptv.Iptv.played(it) }
        nav.replace(Screen.Player(ch.url, ch.name, ch.headers, type = "live"))
        return true
    }
    // Channel versions (user 2026-11-26): a channel that won't play moves to another version of itself (4K → FHD → HD
    // → SD, same region), each tried once. Settings › Live TV › Switch to another version.
    remember(s.url) { if (s.type == "live") com.lekathedon.visionlite.iptv.Iptv.byUrl(s.url)?.let { com.lekathedon.visionlite.iptv.ChannelVersions.opened(it.key) }; 0 }
    fun versionFailover(): Boolean {
        if (s.type != "live") return false
        val cur = com.lekathedon.visionlite.iptv.Iptv.byUrl(s.url) ?: return false
        val ch = com.lekathedon.visionlite.iptv.ChannelVersions.next(cur) ?: return false
        failNote = "This channel won't play · trying ${ch.name}"
        com.lekathedon.visionlite.iptv.Iptv.played(ch)
        nav.replace(Screen.Player(ch.url, ch.name, ch.headers, type = "live"))
        return true
    }
    androidx.compose.runtime.LaunchedEffect(s.url, gen) {
        if (s.type != "live") return@LaunchedEffect
        kotlinx.coroutines.delay(15_000)
        if (firstFrame || sportsFailover()) return@LaunchedEffect
        val ch = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.lekathedon.visionlite.iptv.Iptv.byUrl(s.url)?.let { com.lekathedon.visionlite.iptv.ChannelVersions.next(it) } } ?: return@LaunchedEffect
        failNote = "This channel won't play · trying ${ch.name}"
        com.lekathedon.visionlite.iptv.Iptv.played(ch)
        nav.replace(Screen.Player(ch.url, ch.name, ch.headers, type = "live"))
    }
    // Live reconnect (Zero TV / TiviDeck idea): providers that cut the stream every so often, a stream that ends, or
    // one stuck buffering are reopened quietly. 5 tries in 2 minutes (2 when it never showed a picture), then it says so.
    val drops = remember(s.url) { ArrayList<Long>() }
    var everPlayed by remember(s.url) { mutableStateOf(false) }
    fun liveReconnect(why: String): Boolean {
        if (s.type != "live") return false
        val t = android.os.SystemClock.elapsedRealtime()
        drops.removeAll { t - it > 120_000 }
        if (drops.size >= (if (everPlayed) 5 else 2)) {
            if (versionFailover()) return true
            failNote = "This channel keeps failing · try another one (OK = channel list)"; return false
        }
        drops += t
        failNote = "$why · reconnecting…"
        scope.launch { kotlinx.coroutines.delay(if (drops.size == 1) 300L else 1_500L * drops.size); gen++ }
        return true
    }
    androidx.compose.runtime.LaunchedEffect(firstFrame) { if (firstFrame && s.type == "live") { everPlayed = true; failNote = null } }
    // Convert to 8.1 proven or not on this device (user rule 2026-11-27: never promise what the TV doesn't do).
    // 6 s into a converted film: frames converted AND the decoder opened as Dolby Vision = proven; otherwise the film
    // is showing HDR10, said so on screen, remembered, and with Let the app decide on This TV moves to the next best.
    androidx.compose.runtime.LaunchedEffect(firstFrame) {
        if (!firstFrame || current != Engine.EXO || !dvOn[0] || prefs.dv7.value != "dv81") return@LaunchedEffect
        kotlinx.coroutines.delay(6_000)
        val tried = com.lekathedon.visionlite.player.dv.DoviBridge.getConversionCallCount()
        if (tried == 0L) return@LaunchedEffect   // no profile 7 in this file: nothing to prove
        val done = com.lekathedon.visionlite.player.dv.DoviBridge.getConversionSuccessCount()
        val dvDec = com.lekathedon.visionlite.player.dv.DvDecoders.reachedDv
        val ok = done > 0 && dvDec
        val why = when { done == 0L -> "the converter failed on every frame"; !dvDec -> "the TV opened its HEVC decoder (${exoStats.videoDecoder ?: "?"}), not Dolby Vision"; else -> "Dolby Vision decoder ${exoStats.videoDecoder}" }
        com.lekathedon.visionlite.device.DeviceProof.dv81Result(ok, why)
        if (!ok) {
            failNote = "Dolby Vision 8.1 didn't work on this TV: playing HDR10"
            if (prefs.tvAuto.value) com.lekathedon.visionlite.device.DeviceCheck.saved()?.let { com.lekathedon.visionlite.device.DeviceCheck.useRecommended(it) }
            kotlinx.coroutines.delay(6_000); if (failNote?.startsWith("Dolby Vision 8.1") == true) failNote = null
        }
    }
    androidx.compose.runtime.LaunchedEffect(s.url, gen, firstFrame) {
        if (s.type != "live" || !firstFrame) return@LaunchedEffect
        var stuck = 0
        while (true) {
            kotlinx.coroutines.delay(2_000)
            stuck = if (player.playWhenReady && player.playbackState == MediaPlayer.STATE_BUFFERING) stuck + 2 else 0
            if (stuck >= 12) { liveReconnect("Stuck buffering"); break }
        }
    }
    // Link memory: a link that plays 2 minutes without buffering trouble clears its mark and becomes your pick for the show.
    // Play with VOD (separate from normal Play): no link memory, no pick, no fallback to normal links, its own resume %.
    val isVod = s.addon == com.lekathedon.visionlite.streams.VodLinks.ADDON
    val linkKey = remember(s.url) { if (s.type != "live" && s.addon.isNotBlank() && !isVod) com.lekathedon.visionlite.streams.LinkMemory.keyOf(s.addon, s.name) else null }
    val stalls = remember(s.url) { intArrayOf(0) }
    androidx.compose.runtime.LaunchedEffect(firstFrame, linkKey) {
        if (!firstFrame || linkKey == null) return@LaunchedEffect
        kotlinx.coroutines.delay(120_000)
        if (stalls[0] < 3) { com.lekathedon.visionlite.streams.LinkMemory.clear(linkKey); com.lekathedon.visionlite.streams.LinkMemory.rememberPick(s.id, s.addon, s.name) }
    }
    androidx.compose.runtime.LaunchedEffect(switchNote) { if (switchNote != null) { kotlinx.coroutines.delay(2_200); switchNote = null } }
    DisposableEffect(player) {
        val myAss = assRef[0]
        var subsChecked = false
        var lengthChecked = false
        var pausedByUser = false
        var lastSeek = 0L
        var stallFrom = 0L
        val listener = object : MediaPlayer.Listener {
            override fun onRenderedFirstFrame() { firstFrame = true }
            override fun onPositionDiscontinuity(oldPosition: MediaPlayer.PositionInfo, newPosition: MediaPlayer.PositionInfo, reason: Int) {
                if (reason == MediaPlayer.DISCONTINUITY_REASON_SEEK) lastSeek = System.currentTimeMillis()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (reason != MediaPlayer.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) return
                if (!playWhenReady) { pausedByUser = true; return }
                val back = (prefs.rewindOnResume.value.toLongOrNull() ?: 0L) * 1000L
                if (pausedByUser && back > 0) player.seekTo((player.currentPosition - back).coerceAtLeast(0L))
                pausedByUser = false
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == MediaPlayer.STATE_READY && current == Engine.MPV) firstFrame = true
                if (state == MediaPlayer.STATE_READY && s.type != "live" && player.duration > 0) player.duration.let { d -> scope.launch(kotlinx.coroutines.Dispatchers.IO) { com.lekathedon.visionlite.data.LastLink.duration(d) } }
                // The file against what the title is (tb/TitleFacts, user 2026-11-26): an episode far from the show's
                // length (56 min for a 24-min anime) is the wrong video: stop, mark the link, play the next one.
                if (state == MediaPlayer.STATE_READY && !lengthChecked && (s.type == "series" || s.type == "movie") && player.duration > 0) {
                    lengthChecked = true
                    com.lekathedon.visionlite.tb.TitleFacts.wrongDuration(s.id, s.type == "series", player.duration, s.name)?.let { why ->
                        player.pause()
                        linkKey?.let { com.lekathedon.visionlite.streams.LinkMemory.mark(it, com.lekathedon.visionlite.streams.LinkMemory.Bad.WRONG) }
                        if (!isVod && com.lekathedon.visionlite.streams.LinkFallback.has(s.id)) {
                            failNote = "$why · trying the next link"
                            scope.launch { com.lekathedon.visionlite.streams.LinkFallback.next(s.id)?.let { nav.replace(it) } ?: run { failNote = "$why · no other link fits this title" } }
                        } else failNote = "$why · no other link fits this title"
                    }
                }
                // Buffering that isn't a seek, after the picture came, lasting over 2 s: 3 of them mark the link.
                val nowMs = System.currentTimeMillis()
                if (state == MediaPlayer.STATE_BUFFERING && firstFrame && nowMs - lastSeek > 3_000) stallFrom = nowMs
                if (state == MediaPlayer.STATE_READY && stallFrom > 0) {
                    if (nowMs - stallFrom > 2_000 && ++stalls[0] == 3) linkKey?.let { com.lekathedon.visionlite.streams.LinkMemory.mark(it, com.lekathedon.visionlite.streams.LinkMemory.Bad.BUFFERED) }
                    stallFrom = 0
                }
                if (state == MediaPlayer.STATE_ENDED && prefs.nextEpisode.value && s.type == "series") scope.launch { nextEpisode(nav, s) }
                if (state == MediaPlayer.STATE_ENDED && s.type == "live") liveReconnect("The stream ended")
            }
            override fun onPlayerError(error: PlaybackException) {
                val at = player.currentPosition
                // Into the log a problem report carries (report/Report.kt redacts links and keys).
                android.util.Log.e("VLPlayer", "error ${error.errorCodeName} (${error.errorCode}) engine=$current at=${at / 1000}s firstFrame=$firstFrame", error)
                if (current == Engine.EXO) {
                    if (addonSubs && error.errorCode in 2000..2999 && com.lekathedon.visionlite.addons.Subs.forId(s.id).isNotEmpty()) {
                        session.resumeAt = at; addonSubs = false; return
                    }
                    if (felAllowed && AmlDvFelVideoRenderer.isFallbackError(error)) { session.resumeAt = at; felAllowed = false; return }
                    // A format ExoPlayer can't show at all goes to MPV (Vision's unsupported-format fallback, once per link).
                    val video = (player as? ExoPlayer)?.videoFormat ?: selectedVideo(player.currentTracks)
                    if (prefs.engineBackup.value && !session.formatFallback && s.type != "live" && EngineRules.exoFatalGoesToMpv(error, video, s.url, firstFrame)) {
                        session.formatFallback = true
                        if (EngineRules.isHi10p(video)) session.mpvSoftware = true
                        session.reason = "ExoPlayer can't play this format"
                        switchTo(Engine.MPV, "unsupported-format", at); return
                    }
                } else if (prefs.engineBackup.value && !session.startupFailover && !firstFrame) {
                    // MPV failed while starting: ExoPlayer takes the same link (Vision's startup engine failover).
                    session.startupFailover = true
                    session.reason = "MPV couldn't start it"
                    switchTo(Engine.EXO, "startup-failover", at); return
                }
                // Rewinding live and the oldest minutes were dropped under the paused picture: on from the oldest kept.
                if (s.type == "live" && Timeshift.state.value.ring && error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekTo(0); player.prepare(); return
                }
                if (sportsFailover()) return
                if (liveReconnect(if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) "Fell behind live" else "Signal dropped")) return
                if (at < 15_000) linkKey?.let { com.lekathedon.visionlite.streams.LinkMemory.mark(it, com.lekathedon.visionlite.streams.LinkMemory.Bad.FAILED) }
                // The link died before it really played: the next link of the same list (Vision's source fallback).
                if (at < 15_000 && !isVod && com.lekathedon.visionlite.streams.LinkFallback.has(s.id)) {
                    failNote = "Link failed · trying the next one"
                    scope.launch { com.lekathedon.visionlite.streams.LinkFallback.next(s.id)?.let { nav.replace(it) } ?: run { failNote = "No other link worked" } }
                }
            }
            override fun onTracksChanged(tracks: Tracks) {
                // Subtitles once per file, when its text tracks are known: SDH, then the normal track, never forced.
                if (!subsChecked && tracks.groups.any { it.type == C.TRACK_TYPE_TEXT }) { subsChecked = true; pickSubtitle(player, tracks); foreignOnly(player, tracks) }
                felNow.set(felAllowed && current == Engine.EXO && selectedVideo(tracks)?.let { AmlFelSupport.isProfile7(it) } == true)
                // Vision's HDR subtitle style: white and dimmed on HDR/Dolby Vision films, the normal style otherwise.
                if (current == Engine.EXO) selectedVideo(tracks)?.let { v -> hdrVideo = EngineRules.isHdr(v) || namedHdr(s.name) }
                if (current == Engine.EXO && tracks.groups.isNotEmpty()) exoTracks(tracks)
                if (!(afr || resMatch) || current != Engine.EXO) return
                val v = selectedVideo(tracks) ?: return
                // No frame rate in the header: the frame timestamps measure it, then one switch does rate and resolution
                // together (a resolution-only switch first meant two TV blanks, the second a QMS one).
                if (afr && v.frameRate <= 0) return
                if (v.frameRate > 0 || (resMatch && v.width > 0)) DisplayMatch.apply(context, v.frameRate, v.width, v.height, afr, resMatch)
            }
            /** ExoPlayer read the file: Smart Auto and the no-picture rule may move it to MPV before it plays. */
            fun exoTracks(tracks: Tracks) {
                if (firstFrame || s.type == "live") return
                val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                val video = selectedVideo(tracks) ?: groups.firstOrNull()?.takeIf { it.length > 0 }?.getTrackFormat(0)
                // This TV › Picture: pictures this box / TV can't show right on ExoPlayer go to MPV's gpu-next once.
                if (!session.mpvGpuNext) {
                    val dv5 = Hud.dvProfile(video)?.first == 5 && prefs.dv5Mpv.value && !prefs.dv5To81.value
                    val hdrOnSdr = !dv5 && EngineRules.isHdr(video) && prefs.hdrOnSdr.value == "mpv" && !displayShowsHdr(context)
                    if (dv5 || hdrOnSdr) {
                        session.mpvGpuNext = true
                        session.reason = if (dv5) EngineRules.REASON_DV5 else EngineRules.REASON_HDR_SDR
                        switchTo(Engine.MPV, "picture", player.currentPosition); return
                    }
                }
                // This TV › Dolby Vision converts, but the file was opened without the converter (libass on and the
                // name never said DV; UHD Blu-ray remuxes rarely do): reopen it on the DV route once (user 2026-11-26).
                val prof = Hud.dvProfile(video)?.first
                if (!dvOn[0] && !forceDv && (prof == 7 || (prof == 5 && prefs.dv5To81.value)) && DvRoute.of(prefs, felAllowed) != null) {
                    android.util.Log.i("DvRoute", "DV$prof file not opened on the converter: reopening on it")
                    forceDv = true; session.resumeAt = player.currentPosition.coerceAtLeast(0); gen++; return
                }
                if (session.smartAuto && EngineRules.isHdr(video)) session.reason = EngineRules.REASON_HDR
                if (session.smartAuto && !session.rerouted && !EngineRules.isHdr(video) && EngineRules.isHi10p(video)) {
                    session.rerouted = true; session.mpvSoftware = true; session.reason = EngineRules.REASON_HI10P
                    switchTo(Engine.MPV, "smart-auto", player.currentPosition); return
                }
                if (!prefs.engineBackup.value || session.formatFallback) return
                val support = groups.firstOrNull()?.takeIf { it.length > 0 }?.getTrackSupport(0) ?: C.FORMAT_HANDLED
                val hasAudio = tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }
                val hi10pUnsupported = EngineRules.isHi10p(video) && support != C.FORMAT_HANDLED
                if (hi10pUnsupported || EngineRules.exoNoPictureGoesToMpv(groups.isNotEmpty(), groups.any { it.isSelected }, support, hasAudio, s.url)) {
                    session.formatFallback = true
                    if (hi10pUnsupported) session.mpvSoftware = true
                    session.reason = if (hi10pUnsupported) EngineRules.REASON_HI10P else "ExoPlayer can't show this picture"
                    switchTo(Engine.MPV, "unsupported-format", player.currentPosition)
                }
            }
        }
        player.addListener(listener)
        // The phone companion sees and controls this player (only references; nothing runs until it asks).
        val title = s.title.substringBefore(" · ")
        com.lekathedon.visionlite.link.LinkState.setPlayer(player, title, s.title.substringAfter(" · ", ""),
            s.id.substringBefore(':').takeIf { it.startsWith("tt") }?.let { com.lekathedon.visionlite.library.backdropOf(it) },
            s.type, s.id)
        onDispose {
            session.resumeAt = player.currentPosition
            // One line per play in files/reports/playback.log (last 60): what played how, so a stutter is measured next time.
            // What this play really downloaded at: the internet speed autoplay plans with next time.
            if (current == Engine.EXO && s.type != "live" && exoStats.bandwidth > 0) com.lekathedon.visionlite.device.DeviceProof.playSpeed(exoStats.bandwidth / 1e6)
            if (current == Engine.EXO && s.type != "live") PlayLog.add(title, s.name.lineSequence().firstOrNull().orEmpty(), session.reason,
                exoStats.videoDecoder, myAss?.renderType?.name, dvOn[0], felNow.get(), exoStats.dropped, exoStats.rebuffers, player.currentPosition,
                subs = picked(player, androidx.media3.common.C.TRACK_TYPE_TEXT), audio = picked(player, androidx.media3.common.C.TRACK_TYPE_AUDIO))
            com.lekathedon.visionlite.link.LinkState.setPlayer(null); player.removeListener(listener); player.release()
            runCatching { myAss?.release() }; if (assRef[0] === myAss) assRef[0] = null
        }
    }
    DisposableEffect(Unit) { QuietPlayback.playing(context); onDispose { QuietPlayback.closed(); DisplayMatch.restore(context) } }
    // The phone's Next episode press (only for an episode).
    androidx.compose.runtime.LaunchedEffect(s.url) {
        while (com.lekathedon.visionlite.link.LinkState.next.tryReceive().isSuccess) { }
        for (x in com.lekathedon.visionlite.link.LinkState.next) if (s.type == "series") nextEpisode(nav, s)
    }
    TraktSync(player, s)
    var view by remember { mutableStateOf<PlayerView?>(null) }
    Box(Modifier.fillMaxSize()) {
        androidx.compose.runtime.key(gen, current) {
            if (current == Engine.MPV && mpvView != null) AndroidView(modifier = Modifier.fillMaxSize().background(Color.Black), factory = { mpvView.apply { keepScreenOn = true } })
            else AndroidView(
                // True black bars (Vision's transparent letterbox): nothing painted around the film.
                modifier = Modifier.fillMaxSize().then(if (prefs.blackBars.value) Modifier else Modifier.background(Color.Black)),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        if (prefs.blackBars.value) setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        keepScreenOn = true
                        useController = false
                        isFocusable = false
                        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                        subtitleView?.let { styleSubtitles(it) }
                        view = this
                    }
                },
                update = { pv ->
                    pv.player = player
                    pv.subtitleView?.let { styleSubtitles(it, hdrVideo) }
                    // libass draws in its own view inside the subtitle view, once per player.
                    val ass = assRef[0]
                    if (ass != null && ass.renderType != io.github.peerless2012.ass.media.type.AssRenderType.CUES && pv.getTag(R_ASS) !== ass) {
                        pv.subtitleView?.let { sv -> (sv.getTag(R_ASS) as? android.view.View)?.let(sv::removeView)
                            val v = io.github.peerless2012.ass.media.widget.AssSubtitleView(sv.context, ass); sv.addView(v); sv.setTag(R_ASS, v) }
                        pv.setTag(R_ASS, ass)
                    }
                }
            )
        }
        // Until the first picture: the title's backdrop and logo, not a black screen (user 2026-11-22).
        if (s.type != "live") androidx.compose.animation.AnimatedVisibility(!firstFrame, enter = androidx.compose.animation.EnterTransition.None,
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(400))) {
            com.lekathedon.visionlite.ui.LoadingHero(s.id, s.title, "Starting…")
        }
        // Old Vision's start-of-play overlays (15 s): content warnings top left, refresh rate + resolution top right.
        if (s.type != "live" && s.type != "catchup") androidx.compose.foundation.layout.Row(Modifier.align(androidx.compose.ui.Alignment.TopStart)) {
            MaturityBadge(s.id, firstFrame)
            // Lite's own: 4K · Dolby Vision · Atmos · Remux … beside the warnings, same 15 s.
            MediaBadge(firstFrame, s.name, {
                StatsSource(player, current, null, exoStats.takeIf { current == Engine.EXO }, mpvView, s.addon, s.url,
                    s.name.lineSequence().firstOrNull { it.isNotBlank() }, s.bytes)
            })
        }
        DisplayModeBadge(firstFrame, Modifier.align(androidx.compose.ui.Alignment.TopEnd))
        // Vision's buttons, drawn by Lite (Controls.kt); keys go there, not to the video view.
        val skipMode = prefs.skipIntro.value
        val segments by androidx.compose.runtime.produceState(emptyList<Segment>(), s.id) {
            if (skipMode != "off" || (prefs.nextPopup.value && s.type == "series")) value = Skips.find(s.type, s.id)
        }
        // Nuvio Arc's seek thumbnails (its engine, ported as is): ExoPlayer only, as in Arc; never live TV. Arc's own
        // rules decide the rest (boxes under 3.3 GB start with it off, 4K only in "All resolutions", memory governor).
        val thumbs = s.type != "live" && s.type != "catchup" && prefs.seekThumbs.value && current == Engine.EXO
        androidx.compose.runtime.LaunchedEffect(s.url, gen, thumbs) {
            com.nuvio.tv.core.player.thumbnail.SeekThumbnails.stopSession()
            if (!thumbs) return@LaunchedEffect
            val app = context.applicationContext
            val want = if (prefs.seekThumbsMode.value == "hd") com.nuvio.tv.core.player.thumbnail.SeekThumbMode.HD else com.nuvio.tv.core.player.thumbnail.SeekThumbMode.ALL
            runCatching { if (com.nuvio.tv.core.player.thumbnail.SeekThumbnailPreferences.mode(app) != want) com.nuvio.tv.core.player.thumbnail.SeekThumbnailPreferences.setMode(app, want) }
            com.nuvio.tv.core.player.thumbnail.SeekThumbnails.startWhenEligible(
                context = app,
                source = com.nuvio.tv.core.player.thumbnail.ThumbSource(
                    url = s.url, headers = s.headers, identity = s.id.ifBlank { s.title },
                    holdBeforePlay = false, playbackConnections = 1, identityExact = s.id.startsWith("tt"),
                    usenet = com.nuvio.tv.ui.screens.player.SlowStartServer.isUsenet(s.url),
                    kindNote = { "Vision addon HTTP" }),
                playerProvider = { player as? ExoPlayer })
        }
        DisposableEffect(Unit) { onDispose { com.nuvio.tv.core.player.thumbnail.SeekThumbnails.playerClosed() } }
        fun aspectTo(mode: Int) {
            if (current == Engine.MPV) mpvView?.aspect(when (mode) {
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "zoom"
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL -> "stretch"
                else -> "fit"
            }) else view?.resizeMode = mode
        }
        // Live TV has its own controls (LiveControls.kt): channel list, guide, numbers, options; not the film deck.
        val controlsScale = (prefs.controlsSize.flow.collectAsState().value.toFloatOrNull() ?: 80f).coerceIn(50f, 100f) / 100f
        val baseDensity = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
            androidx.compose.ui.unit.Density(baseDensity.density * controlsScale, baseDensity.fontScale)) {
        // Plot when paused (Settings, off by default): fetched only when switched on; episodes follow Hide episode plots.
        val plotOn by prefs.pausePlot.flow.collectAsState()
        val plotsHidden by prefs.hidePlots.flow.collectAsState()
        val pausePlot by androidx.compose.runtime.produceState<String?>(null, s.id, plotOn, plotsHidden) {
            value = null
            if (!plotOn || s.type == "live" || !s.id.startsWith("tt")) return@produceState
            val ep = s.type == "series" && s.id.count { it == ':' } >= 2
            if (ep && plotsHidden) return@produceState
            value = runCatching {
                val meta = App.instance.client.meta(if (s.type == "series") "series" else "movie", s.id.substringBefore(':'))
                if (ep) meta?.videos?.firstOrNull { it.id == s.id }?.let { it.overview ?: it.description } else meta?.description
            }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }
        if (s.type == "live") LiveControls(player, s,
            tune = { c, list ->
                if (list != null) com.lekathedon.visionlite.iptv.Iptv.zapList = list
                com.lekathedon.visionlite.iptv.Iptv.played(c)
                nav.replace(Screen.Player(c.url, c.name, c.headers, type = "live"))
            },
            playArchive = { url, title, headers -> nav.push(Screen.Player(url, title, headers, type = "catchup")) },
            onAspect = ::aspectTo,
            stats = {
                StatsSource(player, current, (if (session.smartAuto || session.reason != null) session.reason?.let { if (session.smartAuto) "Auto: $it" else it } else null),
                    exoStats.takeIf { current == Engine.EXO }, mpvView, s.addon, s.url,
                    s.name.lineSequence().firstOrNull { it.isNotBlank() } ?: s.url.substringBefore('?').substringAfterLast('/').takeIf { it.contains('.') }, s.bytes)
            },
            engine = current,
            onEngine = { switchTo(if (current == Engine.EXO) Engine.MPV else Engine.EXO, "manual", player.currentPosition) })
        else if (!sourcesOpen) Controls(player, s.title, canNext = s.type == "series", focusSources = sourcesClosed, plot = pausePlot,
            logo = s.id.substringBefore(':').takeIf { it.startsWith("tt") && s.type != "live" }?.let { com.lekathedon.visionlite.data.Our.logo(it, "medium") },
            release = s.name,
            onSubStyle = {
                if (current == Engine.MPV) mpvView?.subtitleStyle(1.0, prefs.subStyle.value, hdr = mpvHdr && prefs.subHdr.value, live = true)
                else view?.subtitleView?.let { styleSubtitles(it, hdrVideo) }
            },
            onDelays = {
                if (current == Engine.MPV) mpvView?.delays(Sync.audioMs, Sync.subMs)
                else player.seekTo(player.currentPosition)
            },
            thumbs = thumbs,
            onNext = { scope.launch { nextEpisode(nav, s) } },
            // Sources opens this title's link list to choose from (never autoplay again, never Home): the list under the
            // player is reused when it is this title's, else the player is swapped for a fresh list.
            // Sources over the film (user 2026-11-27): it keeps playing; a picked link starts where this one is.
            onSources = { sourcesOpen = true },
            onAspect = ::aspectTo,
            stats = {
                StatsSource(player, current, (if (session.smartAuto || session.reason != null) session.reason?.let { if (session.smartAuto) "Auto: $it" else it } else null),
                    exoStats.takeIf { current == Engine.EXO }, mpvView, s.addon, s.url,
                    s.name.lineSequence().firstOrNull { it.isNotBlank() } ?: s.url.substringBefore('?').substringAfterLast('/').takeIf { it.contains('.') }, s.bytes)
            },
            engine = current,
            onEngine = { switchTo(if (current == Engine.EXO) Engine.MPV else Engine.EXO, "manual", player.currentPosition) },
            segments = segments, skipMode = skipMode, nextPopup = prefs.nextPopup.value, autoNext = prefs.nextEpisode.value,
            onZap = if (s.type != "live") null else { d ->
                com.lekathedon.visionlite.iptv.Iptv.zap(s.url, d)?.let { c ->
                    com.lekathedon.visionlite.iptv.Iptv.played(c)
                    nav.replace(Screen.Player(c.url, c.name, c.headers, type = "live"))
                }
            })
        }
        if (sourcesOpen) com.lekathedon.visionlite.streams.Streams(nav,
            Screen.Streams(if (s.type == "series") "series" else s.type, s.id, s.title, s.id.substringBefore(':'), manual = true),
            overlay = { p -> SourceSwitch.set(s.id, player.currentPosition); sourcesOpen = false; nav.replace(p) },
            onClose = { sourcesOpen = false; sourcesClosed = true })
        (switchNote ?: failNote)?.let {
            androidx.compose.material3.Text(it, color = Color.White, fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp),
                modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = 40.dp)
                    .background(Color(0xCC121316), androidx.compose.foundation.shape.RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 8.dp))
        }
    }
}

private const val R_ASS = 0x7f0a7a55

private fun selectedVideo(tracks: Tracks): androidx.media3.common.Format? =
    tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
        ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }

/** The file plus the add-on subtitles found for this title (side-loaded; picked by the language settings). */
private fun withSubs(s: Screen.Player): MediaItem {
    val subs = com.lekathedon.visionlite.addons.Subs.ranked(s.id, s.name)
    if (subs.isEmpty()) return MediaItem.fromUri(s.url)
    return MediaItem.Builder().setUri(s.url).setSubtitleConfigurations(subs.mapIndexed { n, sub ->
        val u = sub.url.substringBefore('?').lowercase()
        val mime = when {
            u.endsWith(".vtt") -> androidx.media3.common.MimeTypes.TEXT_VTT
            u.endsWith(".ass") || u.endsWith(".ssa") -> androidx.media3.common.MimeTypes.TEXT_SSA
            else -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
        }
        MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sub.url)).setMimeType(mime).setId("ext:$n|${sub.url}")
            .setLanguage(sub.lang).setLabel("${sub.lang} · add-on").build()
    }).build()
}

private fun langs(vararg codes: String) = codes.filter { it.isNotEmpty() }.distinct().toTypedArray()

/** Audio and subtitle languages from Settings; subtitles "Off" disables the text track. */
@OptIn(UnstableApi::class)
private fun trackPrefs(base: androidx.media3.common.TrackSelectionParameters, id: String): androidx.media3.common.TrackSelectionParameters {
    val p = App.instance.prefs
    return base.buildUpon()
        .setPreferredAudioLanguages(*OriginalLang.audioLangs(p.audioLang.value, p.audioLang2.value, id).toTypedArray())
        .setPreferredTextLanguages(*langs(p.subLang.value, p.subLang2.value))
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, p.subsWhen.value == "off")
        .build()
}

private fun iso3(code: String?): String? = code?.takeIf { it.isNotBlank() && it != "und" }
    ?.let { runCatching { java.util.Locale(it.substringBefore('-')).isO3Language }.getOrNull() ?: it }

/** A forced track (only the foreign lines and signs): its flag, or "forced" in its name. */
internal fun isForcedSub(f: androidx.media3.common.Format): Boolean =
    f.selectionFlags and C.SELECTION_FLAG_FORCED != 0 || f.label?.contains("forced", true) == true

private val SDH = Regex("(?i)(^|[^a-z])(sdh|hoh|hearing|deaf|cc)([^a-z]|$)")

/** SDH (subtitles for the deaf and hard of hearing): the role flag, or SDH / CC / hearing in its name. */
internal fun isSdhSub(f: androidx.media3.common.Format): Boolean =
    f.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0 || f.label?.let { SDH.containsMatchIn(it) } == true

/**
 * The subtitle Lite starts with (user 2026-11-20): your first language, else your second; in it SDH first, then the
 * normal track, the file's own before an add-on's. A forced track is never picked; with nothing else in your
 * languages and a forced track chosen by the player, subtitles go off (the Subtitles panel still lists every track).
 */
@OptIn(UnstableApi::class)
private fun pickSubtitle(player: MediaPlayer, tracks: Tracks) {
    val p = App.instance.prefs
    if (p.subsWhen.value == "off") return
    val texts = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    val wanted = langs(p.subLang.value, p.subLang2.value).mapNotNull { iso3(it) }
    var best: Pair<Tracks.Group, Int>? = null
    var bestScore = Int.MIN_VALUE
    texts.forEach { g ->
        for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            val f = g.getTrackFormat(i)
            if (isForcedSub(f)) continue
            val li = wanted.indexOf(iso3(f.language))
            if (li < 0) continue
            val addon = f.label?.contains("add-on") == true
            val score = (wanted.size - li) * 100 + (if (isSdhSub(f)) 10 else 0) + (if (addon) 0 else 1)
            if (score > bestScore) { bestScore = score; best = g to i }
        }
    }
    val pick = best
    val chosen = texts.firstNotNullOfOrNull { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
    val b = player.trackSelectionParameters.buildUpon()
    if (pick != null) {
        val (g, i) = pick
        if (g.isTrackSelected(i)) return
        b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i))
        android.util.Log.i("Subs", "start with ${g.getTrackFormat(i).language} ${g.getTrackFormat(i).label ?: ""}" + if (isSdhSub(g.getTrackFormat(i))) " (SDH)" else "")
    } else if (chosen != null && isForcedSub(chosen)) {
        b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        android.util.Log.i("Subs", "only a forced track in your languages: subtitles off")
    } else return
    player.trackSelectionParameters = b.build()
}

/** "Foreign audio only": once per file, no subtitles when the chosen audio is already in a subtitle language. */
@OptIn(UnstableApi::class)
private fun foreignOnly(player: MediaPlayer, tracks: Tracks) {
    val p = App.instance.prefs
    if (p.subsWhen.value != "foreign") return
    val mine = langs(p.subLang.value, p.subLang2.value).mapNotNull { iso3(it) }.toSet()
    if (mine.isEmpty()) return
    val audio = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
        ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { iso3(g.getTrackFormat(it).language) } } ?: return
    if (audio in mine) player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
}

/** Old Vision's subtitle look (SubStyle): size, colour, edge, background, bold and position from Settings. */
@OptIn(UnstableApi::class)
private fun styleSubtitles(v: androidx.media3.ui.SubtitleView, hdr: Boolean = false) =
    SubStyle.apply(v, hdr && App.instance.prefs.subHdr.value)

/** Vision's HDR dimming: setting 0-100 maps onto 30-100 % brightness (58 = white at ~180/255). */
/** The link's own text says HDR / Dolby Vision (last resort when neither the container nor the SPS tells). */
private val HdrName = Regex("""(?i)(^|[^a-z0-9])(hdr|hdr10|hdr10\+|hdr10plus|dv|dovi|dolby[ ._-]?vision|hlg)([^a-z0-9]|$)""")
internal fun namedHdr(text: String) = HdrName.containsMatchIn(text)

internal fun hdrSubColor(color: Int): Int {
    val b = App.instance.prefs.subHdrBright.value.toIntOrNull()?.coerceIn(0, 100) ?: 30
    val f = (0.7f * b + 30f) / 100f
    val a = (color ushr 24) and 0xFF
    return (a shl 24) or ((((color shr 16) and 0xFF) * f).toInt() shl 16) or ((((color shr 8) and 0xFF) * f).toInt() shl 8) or ((color and 0xFF) * f).toInt()
}

/** Next episode: the one after this in the series' own list; its links open (autoplay setting decides the rest). */
private suspend fun nextEpisode(nav: com.lekathedon.visionlite.ui.Nav, s: Screen.Player) {
    val imdb = s.id.substringBefore(':')
    val meta = App.instance.client.meta("series", imdb) ?: return
    val eps = meta.videos.filter { (it.season ?: 0) > 0 }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
    val i = eps.indexOfFirst { it.id == s.id }
    val next = eps.getOrNull(i + 1)?.takeIf { i >= 0 } ?: return
    nav.replace(Screen.Streams("series", next.id, "${meta.name} S${next.season}E${next.episode}", imdb))
}

/**
 * Trakt: resume from the saved % (asked once, only when Trakt is connected), scrobble start / pause / stop.
 * Stop at 80 % or more marks it watched on Trakt.
 */
@OptIn(UnstableApi::class)
@Composable
private fun TraktSync(player: MediaPlayer, s: Screen.Player) {
    val trakt = App.instance.trakt
    val isVod = s.addon == com.lekathedon.visionlite.streams.VodLinks.ADDON
    if (s.id.isBlank() || !s.id.startsWith("tt")) return
    val parts = s.id.split(':')
    val imdb = parts[0]
    val season = parts.getOrNull(1)?.toIntOrNull()
    val episode = parts.getOrNull(2)?.toIntOrNull()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun pct(): Float = player.duration.takeIf { it > 0 }?.let { player.currentPosition * 100f / it } ?: 0f
    DisposableEffect(player) {
        var resumeTo = -1f
        var asked = false
        var resumed = false
        if (trakt.connected.value != null && App.instance.prefs.resume.value) scope.launch {
            resumeTo = trakt.savedProgress(imdb, season, episode).takeIf { it > 0f } ?: com.lekathedon.visionlite.streams.ResumeLinks.pct(if (isVod) "vod|${s.id}" else s.id)
            asked = true
        } else if (App.instance.prefs.resume.value) {
            // No Trakt: the % kept with the exact link (ResumeLinks) is where it resumes.
            resumeTo = com.lekathedon.visionlite.streams.ResumeLinks.pct(if (isVod) "vod|${s.id}" else s.id); asked = true
        }
        val listener = object : MediaPlayer.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                // A link picked from Sources while watching: starts exactly where the old one was.
                if (state == MediaPlayer.STATE_READY && !resumed && player.duration > 0) SourceSwitch.take(s.id)?.let { ms ->
                    resumed = true; player.seekTo(ms); return
                }
                if (state == MediaPlayer.STATE_READY && !resumed && asked && resumeTo in 2f..92f && player.duration > 0 && player.currentPosition < 60_000) {
                    resumed = true
                    player.seekTo((player.duration * resumeTo / 100f).toLong())
                }
                if (state == MediaPlayer.STATE_ENDED) scope.launch { trakt.scrobble("stop", imdb, season, episode, 100f) }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player.duration <= 0) return
                val p = pct()
                scope.launch { trakt.scrobble(if (isPlaying) "start" else "pause", imdb, season, episode, p) }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            val p = pct()
            PlayerReturn.mark()
            if (p > 0f) kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { trakt.scrobble("stop", imdb, season, episode, p) }
            if (p > 0f || player.playbackState == MediaPlayer.STATE_ENDED)
                (if (player.playbackState == MediaPlayer.STATE_ENDED) 100f else p).let { q ->
                    if (isVod) com.lekathedon.visionlite.streams.ResumeLinks.vodProgress(s.id, q) else com.lekathedon.visionlite.streams.ResumeLinks.progress(s.id, q) }
            // Finished: ask for a rating (Settings › Recommendations › Rate after watching).
            if (p >= 90f || player.playbackState == MediaPlayer.STATE_ENDED)
                com.lekathedon.visionlite.home.Rate.afterWatching(imdb, season != null, s.title.replace(Regex("""\s+S\d+\s*E\d+.*$"""), "").ifBlank { s.title })
        }
    }
}

/** Live TV: the channel and what's on, top left, for a few seconds after tuning (and after each channel change). */

/** Sources picked in the player: the position the new link starts at (one use, same title only). */
internal object SourceSwitch {
    private var id = ""; private var at = -1L
    fun set(id: String, ms: Long) { this.id = id; at = ms }
    fun take(id: String): Long? = at.takeIf { it > 0 && id == this.id }?.also { at = -1L }
}

/** The playing track of [type] for playback.log: "en", "en (add-on)", or "none". */
private fun picked(player: androidx.media3.common.Player, type: Int): String = runCatching {
    player.currentTracks.groups.firstNotNullOfOrNull { g ->
        if (g.type != type) null else (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { i ->
            val f = g.getTrackFormat(i)
            (f.language ?: "und") + if (f.id?.contains("ext", true) == true || f.sampleMimeType?.contains("subrip") == true) " (add-on)" else ""
        }
    } ?: "none"
}.getOrDefault("-")
