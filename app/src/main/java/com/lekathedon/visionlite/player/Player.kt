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
import com.lekathedon.visionlite.player.fel.FelNative
import com.lekathedon.visionlite.player.fel.FelRenderer
import com.lekathedon.visionlite.player.fel.isFelFallback
import com.lekathedon.visionlite.ui.Screen
import kotlin.math.abs
import kotlin.math.roundToInt

/** Native FEL first (DV7 only, when chosen and the box can), then the normal decoders. */
@OptIn(UnstableApi::class)
private class Renderers(context: Context, private val fel: Boolean) : DefaultRenderersFactory(context) {
    init { setEnableDecoderFallback(true) }
    override fun buildVideoRenderers(
        context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
        eventHandler: Handler, eventListener: VideoRendererEventListener, allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>
    ) {
        if (fel) out.add(FelRenderer())
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
    var felAllowed by remember(s.url) { mutableStateOf(prefs.dv7.value == "fel" && FelNative.usable) }
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
                if (felAllowed && error.isFelFallback()) {
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
