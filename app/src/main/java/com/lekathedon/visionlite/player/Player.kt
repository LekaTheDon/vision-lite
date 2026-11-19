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
private class Renderers(context: Context, private val fel: Boolean) : DefaultRenderersFactory(context) {
    init { setEnableDecoderFallback(true); setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON) }
    override fun buildVideoRenderers(
        context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
        eventHandler: Handler, eventListener: VideoRendererEventListener, allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>
    ) {
        // Vision's FEL renderer, ahead of MediaCodec so it wins the DV7 track.
        if (fel) out.add(AmlDvFelVideoRenderer(eventHandler, eventListener))
        super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs, out)
    }
}

/** Codecs MPV may send as a bitstream: the ones the soundbar reports over HDMI (as ExoPlayer's passthrough). */
private fun spdifCodecs(context: Context): String {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    val enc = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        .filter { it.type == android.media.AudioDeviceInfo.TYPE_HDMI || it.type == android.media.AudioDeviceInfo.TYPE_HDMI_ARC ||
            (android.os.Build.VERSION.SDK_INT >= 31 && it.type == android.media.AudioDeviceInfo.TYPE_HDMI_EARC) }
        .flatMap { it.encodings.toList() }.toSet()
    return buildList {
        if (android.media.AudioFormat.ENCODING_AC3 in enc) add("ac3")
        if (android.media.AudioFormat.ENCODING_E_AC3 in enc) add("eac3")
        if (android.media.AudioFormat.ENCODING_DTS in enc) add("dts")
        if (android.media.AudioFormat.ENCODING_DTS_HD in enc) add("dts-hd")
        if (android.media.AudioFormat.ENCODING_DOLBY_TRUEHD in enc) add("truehd")
    }.joinToString(",")
}

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

    var felAllowed by remember(s.url) { mutableStateOf(prefs.dv7.value == "fel" && AmlFelSupport.nativeFelUsableOnce()) }
    // A dead add-on subtitle link must never stop the film: on a load error the file restarts without them.
    var addonSubs by remember(s.url) { mutableStateOf(true) }
    // Any stored choice but Off matches (the old "seamless" never switched an HDMI TV).
    val afr = prefs.afr.value != "off"
    val resMatch = prefs.matchRes.value
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var failNote by remember(s.url) { mutableStateOf<String?>(null) }
    var firstFrame by remember(s.url, gen) { mutableStateOf(false) }
    val exoStats = remember(s.url, gen, felAllowed, addonSubs) { ExoStats() }
    val mpvView = remember(s.url, gen) {
        if (current != Engine.MPV) null else com.lekathedon.visionlite.player.mpv.MpvView(context).apply {
            init(session.mpvSoftware, spdifCodecs(context))
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
                    if (addonSubs) com.lekathedon.visionlite.addons.Subs.forId(s.id).forEach { sub -> view.addSubtitle(sub.url, "${sub.lang} · add-on", sub.lang) }
                    if (afr || resMatch) DisplayMatch.apply(context, view.prop("container-fps")?.toFloatOrNull() ?: 0f,
                        view.prop("width")?.toIntOrNull() ?: 0, view.prop("height")?.toIntOrNull() ?: 0, afr, resMatch)
                    // Smart Auto: a Dolby Vision file that started on MPV goes to ExoPlayer's video layer (one look, before it plays).
                    if (session.smartAuto && !session.rerouted && !view.prop("current-tracks/video/dolby-vision-profile").isNullOrBlank()) {
                        session.rerouted = true; session.reason = EngineRules.REASON_DV_FROM_MPV
                        switchTo(Engine.EXO, "smart-auto", currentPosition)
                    }
                }
            }
        } else {
            val http = OkHttpDataSource.Factory(Net.http).setDefaultRequestProperties(s.headers)
            val data = androidx.media3.datasource.DefaultDataSource.Factory(context, http)
            val ass = if (prefs.libass.value && s.type != "live") io.github.peerless2012.ass.media.AssHandler(
                io.github.peerless2012.ass.media.type.AssRenderType.OVERLAY_OPEN_GL,
                io.github.peerless2012.ass.media.AssHandlerConfig(maxRenderPixels = 1920 * 1080)) else null
            assRef[0] = ass
            val renderers: androidx.media3.exoplayer.RenderersFactory = Renderers(context, felAllowed).let { r ->
                if (ass != null) io.github.peerless2012.ass.media.factory.AssRenderersFactory(ass, r) else r }
            val sources = if (ass == null) DefaultMediaSourceFactory(data) else {
                val parsers = io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory(ass)
                DefaultMediaSourceFactory(data, androidx.media3.extractor.DefaultExtractorsFactory().let { f ->
                    androidx.media3.extractor.ExtractorsFactory {
                        f.createExtractors().also { list -> list.forEachIndexed { i, e ->
                            if (e is androidx.media3.extractor.mkv.MatroskaExtractor) list[i] = io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor(parsers, ass) } }
                    }
                }).also { it.setSubtitleParserFactory(parsers) }
            }
            ExoPlayer.Builder(context, renderers)
                .setMediaSourceFactory(sources)
                .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                .build().apply {
                    ass?.init(this)
                    Hud.decoder = if (felAllowed) "native FEL renderer (if the file is DV7)" else null
                    addAnalyticsListener(exoStats)
                    addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                        override fun onVideoDecoderInitialized(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, name: String, initMs: Long, durMs: Long) { Hud.decoder = name }
                    })
                    trackSelectionParameters = trackPrefs(trackSelectionParameters, s.id)
                    // A file with no frame rate in its header: measured from the first frames' timestamps.
                    if (afr) {
                        val stamps = ArrayList<Long>(48)
                        val main = Handler(android.os.Looper.getMainLooper())
                        setVideoFrameMetadataListener { t, _, f, _ ->
                            if (f.frameRate > 0 || stamps.size >= 48) return@setVideoFrameMetadataListener
                            stamps.add(t)
                            if (stamps.size == 48) {
                                val sorted = stamps.sorted().drop(8)
                                val fps = 1_000_000f * (sorted.size - 1) / (sorted.last() - sorted.first()).coerceAtLeast(1)
                                if (fps in 10f..121f) main.post { DisplayMatch.apply(context, fps, f.width, f.height, true, resMatch) }
                            }
                        }
                    }
                    setMediaItem(if (addonSubs) withSubs(s) else MediaItem.fromUri(s.url), session.resumeAt)
                    prepare()
                    playWhenReady = true
                }
        }
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
    androidx.compose.runtime.LaunchedEffect(switchNote) { if (switchNote != null) { kotlinx.coroutines.delay(2_200); switchNote = null } }
    DisposableEffect(player) {
        val myAss = assRef[0]
        var subsChecked = false
        var pausedByUser = false
        val listener = object : MediaPlayer.Listener {
            override fun onRenderedFirstFrame() { firstFrame = true }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (reason != MediaPlayer.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) return
                if (!playWhenReady) { pausedByUser = true; return }
                val back = (prefs.rewindOnResume.value.toLongOrNull() ?: 0L) * 1000L
                if (pausedByUser && back > 0) player.seekTo((player.currentPosition - back).coerceAtLeast(0L))
                pausedByUser = false
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == MediaPlayer.STATE_READY && current == Engine.MPV) firstFrame = true
                if (state == MediaPlayer.STATE_ENDED && prefs.nextEpisode.value && s.type == "series") scope.launch { nextEpisode(nav, s) }
            }
            override fun onPlayerError(error: PlaybackException) {
                val at = player.currentPosition
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
                // The link died before it really played: the next link of the same list (Vision's source fallback).
                if (at < 15_000 && com.lekathedon.visionlite.streams.LinkFallback.has(s.id)) {
                    failNote = "Link failed · trying the next one"
                    scope.launch { com.lekathedon.visionlite.streams.LinkFallback.next(s.id)?.let { nav.replace(it) } ?: run { failNote = "No other link worked" } }
                }
            }
            override fun onTracksChanged(tracks: Tracks) {
                if (!subsChecked && tracks.groups.isNotEmpty()) { subsChecked = true; foreignOnly(player, tracks) }
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
            com.lekathedon.visionlite.link.LinkState.setPlayer(null); player.removeListener(listener); player.release()
            runCatching { myAss?.release() }; if (assRef[0] === myAss) assRef[0] = null
        }
    }
    DisposableEffect(Unit) { onDispose { DisplayMatch.restore(context) } }
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
                modifier = Modifier.fillMaxSize().background(Color.Black),
                factory = { ctx ->
                    PlayerView(ctx).apply {
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
                    // libass draws in its own view inside the subtitle view, once per player.
                    val ass = assRef[0]
                    if (ass != null && pv.getTag(R_ASS) !== ass) {
                        pv.subtitleView?.let { sv -> (sv.getTag(R_ASS) as? android.view.View)?.let(sv::removeView)
                            val v = io.github.peerless2012.ass.media.widget.AssSubtitleView(sv.context, ass); sv.addView(v); sv.setTag(R_ASS, v) }
                        pv.setTag(R_ASS, ass)
                    }
                }
            )
        }
        // Vision's buttons, drawn by Lite (Controls.kt); keys go there, not to the video view.
        val skipMode = prefs.skipIntro.value
        val segments by androidx.compose.runtime.produceState(emptyList<Segment>(), s.id) {
            if (skipMode != "off" || (prefs.nextPopup.value && s.type == "series")) value = Skips.find(s.type, s.id)
        }
        Controls(player, s.title, canNext = s.type == "series",
            onNext = { scope.launch { nextEpisode(nav, s) } },
            onSources = { nav.pop() },
            onAspect = { mode ->
                if (current == Engine.MPV) mpvView?.aspect(when (mode) {
                    androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "zoom"
                    androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL -> "stretch"
                    else -> "fit"
                }) else view?.resizeMode = mode
            },
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
        if (s.type == "live") ChannelBanner(s)
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
    val subs = com.lekathedon.visionlite.addons.Subs.forId(s.id)
    if (subs.isEmpty()) return MediaItem.fromUri(s.url)
    return MediaItem.Builder().setUri(s.url).setSubtitleConfigurations(subs.map { sub ->
        val u = sub.url.substringBefore('?').lowercase()
        val mime = when {
            u.endsWith(".vtt") -> androidx.media3.common.MimeTypes.TEXT_VTT
            u.endsWith(".ass") || u.endsWith(".ssa") -> androidx.media3.common.MimeTypes.TEXT_SSA
            else -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
        }
        MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sub.url)).setMimeType(mime)
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

/** Size and look from Settings; the file's own sizes/styles are ignored so the choice always shows. */
@OptIn(UnstableApi::class)
private fun styleSubtitles(v: androidx.media3.ui.SubtitleView) {
    val p = App.instance.prefs
    val k = when (p.subSize.value) { "s" -> 0.8f; "l" -> 1.25f; "xl" -> 1.5f; else -> 1f }
    v.setApplyEmbeddedFontSizes(false)
    v.setApplyEmbeddedStyles(false)
    v.setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * k)
    val white = android.graphics.Color.WHITE; val clear = android.graphics.Color.TRANSPARENT; val black = android.graphics.Color.BLACK
    v.setStyle(when (p.subStyle.value) {
        "white" -> androidx.media3.ui.CaptionStyleCompat(white, clear, clear, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, black, null)
        "yellow" -> androidx.media3.ui.CaptionStyleCompat(0xFFFFE14D.toInt(), clear, clear, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE, black, null)
        "box" -> androidx.media3.ui.CaptionStyleCompat(white, 0xCC000000.toInt(), clear, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE, black, null)
        else -> androidx.media3.ui.CaptionStyleCompat(white, clear, clear, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE, black, null)
    })
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
            resumeTo = trakt.savedProgress(imdb, season, episode)
            asked = true
        }
        val listener = object : MediaPlayer.Listener {
            override fun onPlaybackStateChanged(state: Int) {
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
        }
    }
}

/** Live TV: the channel and what's on, top left, for a few seconds after tuning (and after each channel change). */
@Composable
private fun ChannelBanner(s: Screen.Player) {
    var show by remember(s.url) { mutableStateOf(true) }
    val info by androidx.compose.runtime.produceState<Pair<com.lekathedon.visionlite.iptv.Channel?, com.lekathedon.visionlite.iptv.EpgProgram?>>(null to null, s.url) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val c = com.lekathedon.visionlite.iptv.Iptv.byUrl(s.url)
            c to c?.let { com.lekathedon.visionlite.iptv.Iptv.nowNext(it).firstOrNull { p -> p.isLive(System.currentTimeMillis()) } }
        }
    }
    androidx.compose.runtime.LaunchedEffect(s.url) { kotlinx.coroutines.delay(5_000); show = false }
    if (!show) return
    val (c, now) = info
    androidx.compose.foundation.layout.Row(
        Modifier.padding(start = 40.dp, top = 32.dp).background(Color(0xCC000000), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(14.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        c?.logo?.let { com.lekathedon.visionlite.ui.Img(it, 80.dp, 45.dp, Modifier.size(80.dp, 45.dp), androidx.compose.ui.layout.ContentScale.Fit) }
        androidx.compose.foundation.layout.Column(Modifier.padding(start = 12.dp)) {
            androidx.compose.material3.Text((c?.n?.let { "$it  " } ?: "") + s.title, color = Color.White, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            now?.let { androidx.compose.material3.Text(it.title, color = Color(0xFFD9DC8E), fontSize = 14.sp) }
        }
    }
}
