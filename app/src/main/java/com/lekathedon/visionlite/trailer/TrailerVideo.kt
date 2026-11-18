package com.lekathedon.visionlite.trailer

import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout

/**
 * One trailer, cropped to fill its box, shown only once its first frame is up (the art stays until then), sound
 * as the setting says. The player exists only while this is on screen: focus moving away frees it at once.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun TrailerVideo(url: String, muted: Boolean, modifier: Modifier, onEnded: () -> Unit) {
    val context = LocalContext.current
    var shown by remember(url) { mutableStateOf(false) }
    val frame = remember { AspectRatioFrameLayout(context).apply { resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM } }
    val texture = remember { TextureView(context).also { frame.addView(it) } }
    DisposableEffect(url) {
        val player = ExoPlayer.Builder(context).build()
        player.setVideoTextureView(texture)
        player.volume = if (muted) 0f else 1f
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() { shown = true }
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) frame.setAspectRatio(size.width * size.pixelWidthHeightRatio / size.height)
            }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) onEnded() }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { onEnded() }
        })
        player.setMediaItem(MediaItem.fromUri(url))
        player.playWhenReady = true
        player.prepare()
        onDispose { player.release() }
    }
    AndroidView({ frame }, modifier.clipToBounds().graphicsLayer { alpha = if (shown) 1f else 0f })
}
