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
fun Player(s: Screen.Player) {
    val context = LocalContext.current
    val prefs = App.instance.prefs
    var felAllowed by remember(s.url) { mutableStateOf(prefs.dv7.value == "fel" && AmlFelSupport.nativeFelUsableOnce()) }
    var resumeAt by remember(s.url) { mutableLongStateOf(0L) }
    val afr = prefs.afr.value
    val player = remember(s.url, felAllowed) {
        val http = OkHttpDataSource.Factory(Net.http).setDefaultRequestProperties(s.headers)
        ExoPlayer.Builder(context, Renderers(context, felAllowed))
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setVideoChangeFrameRateStrategy(
                if (afr == "seamless") C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
            )
            .build().apply {
                setMediaItem(MediaItem.fromUri(s.url), resumeAt)
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(player) {
        val listener = object : MediaPlayer.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (felAllowed && AmlDvFelVideoRenderer.isFallbackError(error)) {
                    resumeAt = player.currentPosition
                    felAllowed = false
                }
            }
            override fun onTracksChanged(tracks: Tracks) {
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
                requestFocus()
                setOnKeyListener { _, code, e ->
                    if (e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_CENTER && !isControllerFullyVisible) { showController(); true } else false
                }
            }
        },
        update = { it.player = player }
    )
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
        if (trakt.connected.value != null) scope.launch {
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
