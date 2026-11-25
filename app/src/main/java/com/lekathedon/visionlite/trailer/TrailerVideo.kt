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
fun TrailerVideo(url: String, muted: Boolean, modifier: Modifier, audioUrl: String? = null, onEnded: () -> Unit) {
    val context = LocalContext.current
    var shown by remember(url) { mutableStateOf(false) }
    val frame = remember { AspectRatioFrameLayout(context).apply { resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM } }
    val texture = remember { TextureView(context).also { frame.addView(it) } }
    DisposableEffect(url) {
        // Vision's fix (f1fa03f77), made strict: trailers decode in software ONLY, so the box's automatic frame
        // rate never switches the TV's refresh rate or resolution when one starts (a format with no software
        // decoder simply shows no trailer). The full-screen player keeps hardware + frame-rate matching.
        val player = ExoPlayer.Builder(context)
            .setRenderersFactory(
                androidx.media3.exoplayer.DefaultRenderersFactory(context)
                    .setMediaCodecSelector(SoftwareVideoOnly)
            )
            .setVideoChangeFrameRateStrategy(androidx.media3.common.C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
            // No stalls mid-trailer: start after 3 s are in, then keep fetching up to the whole trailer (a short
            // network dip never empties the buffer); after a dip wait for 6 s before going on.
            .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(180_000, 180_000, 3_000, 6_000)
                .setTargetBufferBytes(48 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build())
            .build()
        // HLS trailers: never a variant above 1080p (software can't decode 4K in time).
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setMaxVideoSize(1920, 1080).build()
        player.setVideoTextureView(texture)
        player.volume = if (muted) 0f else 1f
        com.lekathedon.visionlite.ui.Screensaver.track(player)
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() { shown = true }
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) frame.setAspectRatio(size.width * size.pixelWidthHeightRatio / size.height)
            }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) onEnded() }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { onEnded() }
        })
        // YouTube links are fetched in 10 MB pieces (ChunkedYt): one long request gets throttled and froze trailers.
        val f = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
            ChunkedYt.Factory(androidx.media3.datasource.DefaultDataSource.Factory(context)))
        if (audioUrl == null) player.setMediaSource(f.createMediaSource(MediaItem.fromUri(url)))
        else {
            // YouTube gives picture and sound as two files (a service's intro clip): played together.
            player.setMediaSource(androidx.media3.exoplayer.source.MergingMediaSource(
                f.createMediaSource(MediaItem.fromUri(url)), f.createMediaSource(MediaItem.fromUri(audioUrl))))
        }
        player.playWhenReady = true
        player.prepare()
        onDispose { com.lekathedon.visionlite.ui.Screensaver.untrack(player); player.release() }
    }
    AndroidView({ frame }, modifier.clipToBounds().graphicsLayer { alpha = if (shown) 1f else 0f })
}

/** Video: software decoders only (a hardware decoder is what makes the box switch the TV); audio unchanged. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private val SoftwareVideoOnly = androidx.media3.exoplayer.mediacodec.MediaCodecSelector { mimeType, secure, tunneling ->
    val all = androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mimeType, secure, tunneling)
    if (!mimeType.startsWith("video/")) all else all.filter { it.softwareOnly }
}
