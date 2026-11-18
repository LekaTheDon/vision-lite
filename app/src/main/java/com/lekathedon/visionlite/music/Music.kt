package com.lekathedon.visionlite.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.browse.Chips
import com.lekathedon.visionlite.ui.Button
import com.lekathedon.visionlite.ui.Img
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.youtube.Card
import com.lekathedon.visionlite.youtube.SearchBox
import com.lekathedon.visionlite.youtube.Yt
import com.lekathedon.visionlite.youtube.YtItem
import com.lekathedon.visionlite.youtube.YtShelf
import com.lekathedon.visionlite.youtube.ytDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Music's player: one audio-only ExoPlayer for the whole app, made the first time a song plays. Each track's link is
 * asked only when the player reaches it. Keeps playing while you browse; a small foreground service holds it.
 */
@OptIn(UnstableApi::class)
object MusicPlayer {
    data class Now(val title: String, val artist: String, val art: String?, val playing: Boolean, val index: Int, val count: Int)
    private val _now = MutableStateFlow<Now?>(null)
    val now: StateFlow<Now?> get() = _now

    private val player: ExoPlayer by lazy {
        ExoPlayer.Builder(App.instance).setMediaSourceFactory(DefaultMediaSourceFactory(ytDataSource())).setHandleAudioBecomingNoisy(true).build().apply {
            addListener(object : Player.Listener {
                override fun onEvents(p: Player, events: Player.Events) = publish()
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    val i = Intent(App.instance, MusicService::class.java)
                    runCatching {
                        if (isPlaying) { if (Build.VERSION.SDK_INT >= 26) App.instance.startForegroundService(i) else App.instance.startService(i) }
                        else if (!playWhenReady || playbackState == Player.STATE_ENDED) App.instance.stopService(i)
                    }
                }
            })
        }
    }
    @Volatile private var made = false

    private fun publish() {
        val p = player
        val md = p.currentMediaItem?.mediaMetadata
        _now.value = if (p.mediaItemCount == 0) null else Now(md?.title?.toString().orEmpty(), md?.artist?.toString().orEmpty(), md?.artworkUri?.toString(),
            p.isPlaying || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING), p.currentMediaItemIndex, p.mediaItemCount)
    }

    fun play(queue: List<YtItem>, start: Int = 0) {
        val items = queue.filter { it.videoId != null }.map { t ->
            MediaItem.Builder().setUri("yt-a://${t.videoId}").setMediaId(t.videoId!!)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.sub).setArtworkUri(t.image?.let(android.net.Uri::parse)).build()).build()
        }
        if (items.isEmpty()) return
        made = true
        player.setMediaItems(items, start.coerceIn(0, items.lastIndex), 0); player.prepare(); player.playWhenReady = true
    }

    fun toggle() { if (made) player.playWhenReady = !player.playWhenReady }
    fun next() { if (made && player.hasNextMediaItem()) player.seekToNextMediaItem() }
    fun previous() { if (made) player.seekToPreviousMediaItem() }
    fun stop() { if (made) { player.stop(); player.clearMediaItems(); publish() }; App.instance.stopService(Intent(App.instance, MusicService::class.java)) }
}

/** Holds the process while music plays; gone when it stops. */
class MusicService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("music", "Music", NotificationManager.IMPORTANCE_LOW))
        val t = MusicPlayer.now.value
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "music") else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(t?.title ?: "Vision Music").setContentText(t?.artist).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(8, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(8, n)
        return START_NOT_STICKY
    }
}

/** Vision Music, the lite way: YouTube Music's home and search; OK on a song plays its radio, on an album/playlist/artist opens it. */
@Composable
fun MusicScreen(nav: Nav) {
    var tab by rememberSaveable { mutableStateOf("home") }
    val pages = remember { mutableStateListOf<Pair<String, String>>() } // (title, browseId) opened inside Music
    androidx.activity.compose.BackHandler(pages.isNotEmpty()) { pages.removeAt(pages.lastIndex) }
    val scope = rememberCoroutineScope()
    fun open(it: YtItem, shelf: List<YtItem>) {
        when {
            it.videoId != null -> {
                // A song in a list of songs (an album, a playlist page) plays that list; a song on its own plays its radio.
                val songs = shelf.filter { s -> s.videoId != null }
                if (pages.isNotEmpty() && songs.size > 1) MusicPlayer.play(songs, songs.indexOf(it))
                else scope.launch { MusicPlayer.play(runCatching { Yt.queue(it.videoId, it.playlistId) }.getOrDefault(emptyList()).ifEmpty { listOf(it) }) }
            }
            it.playlistId != null && it.browseId == null -> scope.launch { MusicPlayer.play(runCatching { Yt.queue(null, it.playlistId) }.getOrDefault(emptyList())) }
            it.browseId != null -> pages.add(it.title to it.browseId)
        }
    }
    Column(Modifier.fillMaxSize().background(LocalLook.current.page).padding(start = 40.dp, end = 24.dp, top = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(pages.lastOrNull()?.first ?: "Vision Music", color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 18.dp))
            if (pages.isEmpty()) Chips(listOf("home" to "Home", "search" to "Search"), tab) { tab = it }
        }
        NowPlaying()
        val page = pages.lastOrNull()
        when {
            page != null -> Shelves("b:${page.second}", { Yt.musicBrowse(page.second) }, ::open)
            tab == "search" -> SearchBox()?.let { q -> Shelves("s:$q", { Yt.musicSearch(q) }, ::open) }
            else -> Shelves("home", { Yt.musicHome() }, ::open)
        }
    }
}

@Composable
private fun NowPlaying() {
    val now by MusicPlayer.now.collectAsState()
    val n = now ?: return
    Row(Modifier.padding(top = 12.dp).fillMaxWidth().background(Palette.Card).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        n.art?.let { Img(it, 56.dp, 56.dp, Modifier.size(56.dp)) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(n.title, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${n.artist} · ${n.index + 1}/${n.count}", color = Palette.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button("⏮", { MusicPlayer.previous() })
            Button(if (n.playing) "Pause" else "Play", { MusicPlayer.toggle() })
            Button("⏭", { MusicPlayer.next() })
            Button("Stop", { MusicPlayer.stop() })
        }
    }
}

@Composable
private fun Shelves(key: String, load: suspend () -> List<YtShelf>, open: (YtItem, List<YtItem>) -> Unit) {
    var shelves by remember(key) { mutableStateOf<List<YtShelf>?>(null) }
    var err by remember(key) { mutableStateOf("") }
    LaunchedEffect(key) { runCatching { shelves = load() }.onFailure { err = it.message ?: "error"; shelves = emptyList() } }
    when {
        shelves == null -> Text("Loading…", color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 16.dp))
        shelves!!.isEmpty() -> Text(if (err.isNotEmpty()) "YouTube Music didn't answer: $err" else "Nothing here.", color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 16.dp))
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 50.dp)) {
            items(shelves!!, key = { it.title + it.items.first().key }) { s ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) {
                    Text(s.title.ifEmpty { "Songs" }, color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    if (s.items.count { it.videoId != null } > 1) Button("Play all", { MusicPlayer.play(s.items) }, Modifier.padding(start = 12.dp))
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(s.items, key = { it.key }) { v -> Card(v, 150.dp, 150.dp) { open(v, s.items) } }
                }
            }
        }
    }
}
