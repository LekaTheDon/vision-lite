package com.lekathedon.visionlite.player

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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

/** Native FEL first (DV7 only, when chosen and the box can), then the normal decoders. */
@OptIn(UnstableApi::class)
private class Renderers(context: Context, private val fel: Boolean) : DefaultRenderersFactory(context) {
    init { setEnableDecoderFallback(true) }
    override fun buildVideoRenderers(
        context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
        eventHandler: Handler, eventListener: VideoRendererEventListener, allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>
    ) {
        // Vision's FEL renderer, ahead of MediaCodec so it wins the DV7 track.
        if (fel) out.add(AmlDvFelVideoRenderer(eventHandler, eventListener))
        super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs, out)
    }
}

/**
 * ExoPlayer. Audio passes through whatever the soundbar reports it takes. DV7 uses native FEL when chosen in This TV;
 * if the FEL path gives up on a stream, the same stream replays on the box decoder from where it was.
 */
@OptIn(UnstableApi::class)
@Composable
fun Player(nav: com.lekathedon.visionlite.ui.Nav, s: Screen.Player) {
    val context = LocalContext.current
    val prefs = App.instance.prefs
    var felAllowed by remember(s.url) { mutableStateOf(prefs.dv7.value == "fel" && AmlFelSupport.nativeFelUsableOnce()) }
    var resumeAt by remember(s.url) { mutableLongStateOf(0L) }
    // A dead add-on subtitle link must never stop the film: on a load error the file restarts without them.
    var addonSubs by remember(s.url) { mutableStateOf(true) }
    val afr = prefs.afr.value
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val player = remember(s.url, felAllowed, addonSubs) {
        val http = OkHttpDataSource.Factory(Net.http).setDefaultRequestProperties(s.headers)
        ExoPlayer.Builder(context, Renderers(context, felAllowed))
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setVideoChangeFrameRateStrategy(
                if (afr == "seamless") C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
            )
            .build().apply {
                trackSelectionParameters = trackPrefs(trackSelectionParameters)
                setMediaItem(if (addonSubs) withSubs(s) else MediaItem.fromUri(s.url), resumeAt)
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(player) {
        var subsChecked = false
        var pausedByUser = false
        val listener = object : MediaPlayer.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (reason != MediaPlayer.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) return
                if (!playWhenReady) { pausedByUser = true; return }
                val back = (prefs.rewindOnResume.value.toLongOrNull() ?: 0L) * 1000L
                if (pausedByUser && back > 0) player.seekTo((player.currentPosition - back).coerceAtLeast(0L))
                pausedByUser = false
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == MediaPlayer.STATE_ENDED && prefs.nextEpisode.value && s.type == "series") scope.launch { nextEpisode(nav, s) }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (addonSubs && error.errorCode in 2000..2999 && com.lekathedon.visionlite.addons.Subs.forId(s.id).isNotEmpty()) {
                    resumeAt = player.currentPosition
                    addonSubs = false
                    return
                }
                if (felAllowed && AmlDvFelVideoRenderer.isFallbackError(error)) {
                    resumeAt = player.currentPosition
                    felAllowed = false
                }
            }
            override fun onTracksChanged(tracks: Tracks) {
                if (!subsChecked && tracks.groups.isNotEmpty()) { subsChecked = true; foreignOnly(player, tracks) }
                if (afr != "always") return
                val fps = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
                    ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).frameRate } }
                if (fps != null && fps > 0) matchFrameRate(context, fps)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    DisposableEffect(Unit) { onDispose { if (afr == "always") restoreMode(context) } }
    TraktSync(player, s)
    AndroidView(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        factory = { ctx ->
            PlayerView(ctx).apply {
                keepScreenOn = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                controllerShowTimeoutMs = 3000
                isFocusable = true
                subtitleView?.let { styleSubtitles(it) }
                requestFocus()
                setOnKeyListener { _, code, e ->
                    if (e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_CENTER && !isControllerFullyVisible) { showController(); true } else false
                }
            }
        },
        update = { it.player = player }
    )
}

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
private fun trackPrefs(base: androidx.media3.common.TrackSelectionParameters): androidx.media3.common.TrackSelectionParameters {
    val p = App.instance.prefs
    return base.buildUpon()
        .setPreferredAudioLanguages(*langs(p.audioLang.value, p.audioLang2.value))
        .setPreferredTextLanguages(*langs(p.subLang.value, p.subLang2.value))
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, p.subsWhen.value == "off")
        .build()
}

private fun iso3(code: String?): String? = code?.takeIf { it.isNotBlank() && it != "und" }
    ?.let { runCatching { java.util.Locale(it.substringBefore('-')).isO3Language }.getOrNull() ?: it }

/** "Foreign audio only": once per file, no subtitles when the chosen audio is already in a subtitle language. */
@OptIn(UnstableApi::class)
private fun foreignOnly(player: ExoPlayer, tracks: Tracks) {
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

/** Picks the display mode with the current resolution whose refresh is the video's rate or an exact multiple. */
private fun matchFrameRate(context: Context, fps: Float) {
    val activity = context as? Activity ?: return
    val window = activity.window
    @Suppress("DEPRECATION") val display = activity.windowManager.defaultDisplay
    val cur = display.mode
    val same = display.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
    val best = same.filter { m -> val k = (m.refreshRate / fps).roundToInt(); k >= 1 && abs(m.refreshRate - fps * k) < 0.05f }
        .minByOrNull { (it.refreshRate / fps).roundToInt() } ?: return
    if (best.modeId == cur.modeId) return
    window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
}

private fun restoreMode(context: Context) {
    val window = (context as? Activity)?.window ?: return
    window.attributes = window.attributes.also { it.preferredDisplayModeId = 0 }
}

/**
 * Trakt: resume from the saved % (asked once, only when Trakt is connected), scrobble start / pause / stop.
 * Stop at 80 % or more marks it watched on Trakt.
 */
@OptIn(UnstableApi::class)
@Composable
private fun TraktSync(player: ExoPlayer, s: Screen.Player) {
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
