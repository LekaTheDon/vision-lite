package com.lekathedon.visionlite.player

import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.ui.Screen

/**
 * ExoPlayer. Audio goes to the soundbar untouched when it takes the format (ExoPlayer's passthrough follows what
 * the HDMI sink reports); the TV switches to the video's frame rate where it can. Native FEL comes in phase 2.
 */
@OptIn(UnstableApi::class)
@Composable
fun Player(s: Screen.Player) {
    val context = LocalContext.current
    val player = remember(s.url) {
        val http = OkHttpDataSource.Factory(Net.http).setDefaultRequestProperties(s.headers)
        val selector = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters().setTunnelingEnabled(false))
        }
        ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setTrackSelector(selector)
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
            .build().apply {
                setMediaItem(MediaItem.fromUri(s.url))
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                keepScreenOn = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                controllerShowTimeoutMs = 3000
                isFocusable = true
                isFocusableInTouchMode = true
                requestFocus()
                setOnKeyListener { v, code, e ->
                    if (e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_CENTER && !isControllerFullyVisible) {
                        showController(); true
                    } else false
                }
            }
        }
    )
}
