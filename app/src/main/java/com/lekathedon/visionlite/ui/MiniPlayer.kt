package com.lekathedon.visionlite.ui

import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lekathedon.visionlite.data.Net

/**
 * The small window a channel is tried in (Vision's Live now / Sports video window): its own light ExoPlayer on a
 * TextureView, made when the window shows and released when it goes, so nothing plays or holds a decoder after.
 */
@OptIn(UnstableApi::class)
@Composable
fun MiniPlayer(url: String?, headers: Map<String, String>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(headers) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(Net.http).setDefaultRequestProperties(headers)))
            .build()
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player, url) {
        if (url == null) { player.stop(); player.clearMediaItems(); return@LaunchedEffect }
        player.setMediaItem(MediaItem.fromUri(url)); player.prepare(); player.playWhenReady = true
    }
    AndroidView(factory = { TextureView(it).apply { keepScreenOn = true } }, modifier = modifier, update = { player.setVideoTextureView(it) })
}
