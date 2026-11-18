package com.lekathedon.visionlite.youtube

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.ui.Button
import com.lekathedon.visionlite.ui.Focusable
import com.lekathedon.visionlite.ui.Img
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.Qr
import com.lekathedon.visionlite.ui.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "yt-v://id" / "yt-a://id" → the real video / audio link, asked when the player opens it (and again when it expires). */
@OptIn(UnstableApi::class)
fun ytDataSource(): ResolvingDataSource.Factory = ResolvingDataSource.Factory(
    // DefaultDataSource: web links go through OkHttp, songs on the box (content://, file://) are read directly.
    androidx.media3.datasource.DefaultDataSource.Factory(com.lekathedon.visionlite.App.instance, OkHttpDataSource.Factory(Net.http).setUserAgent(Yt.VR_UA))
) { spec: DataSpec ->
    val u = spec.uri
    if (u.scheme != "yt-v" && u.scheme != "yt-a") spec
    else {
        val s = Yt.streams(u.host!!)
        val link = (if (u.scheme == "yt-v") s.video else s.audio ?: s.video) ?: throw java.io.IOException("No stream")
        spec.withUri(Uri.parse(link))
    }
}

/**
 * Vision's YouTube tabs, in its order (Recommended, Subscriptions, History, Playlists, Channels, Shorts, Music, Gaming,
 * News, Live, Sports, Trending), then Search and the sign-in. Each tab is a page of the YouTube hub in the pill; the
 * ones that need your account show once you're signed in. OK on a channel or playlist opens it here; Back returns.
 */
object YtTabs {
    private val all = listOf("HOME" to "Recommended", "SUBSCRIPTIONS" to "Subscriptions", "HISTORY" to "History", "PLAYLISTS" to "Playlists",
        "CHANNELS" to "Channels", "SHORTS" to "Shorts", "MUSIC" to "Music", "GAMING" to "Gaming", "NEWS" to "News", "LIVE" to "Live",
        "SPORTS" to "Sports", "TRENDING" to "Trending", "SEARCH" to "Search", "ACCOUNT" to "Account")
    private val needsAccount = setOf("SUBSCRIPTIONS", "HISTORY", "PLAYLISTS", "CHANNELS")
    fun tabs(signed: Boolean = Yt.signedIn): List<Pair<String, String>> =
        all.filter { signed || it.first !in needsAccount }.map { if (!signed && it.first == "ACCOUNT") it.first to "Sign in" else it }
}

@Composable
fun YouTubeScreen(nav: Nav, tab: String = "HOME") {
    val signed by Yt.signed.collectAsState()
    val pages = remember(tab) { mutableStateListOf<Pair<String, String>>() } // (title, browseId) opened in this tab
    androidx.activity.compose.BackHandler(pages.isNotEmpty()) { pages.removeAt(pages.lastIndex) }
    fun open(v: YtItem) {
        when {
            v.videoId != null -> nav.push(Screen.YtPlay(v.videoId, v.title))
            v.browseId != null -> pages.add(v.title to v.browseId)
            v.playlistId != null -> pages.add(v.title to "VL" + v.playlistId)
        }
    }
    Column(Modifier.fillMaxSize().background(LocalLook.current.page).padding(start = 40.dp, end = 24.dp, top = 8.dp)) {
        pages.lastOrNull()?.let { Text(it.first, color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        val page = pages.lastOrNull()
        when {
            page != null -> Feed("b:${page.second}", ::open) { Yt.browse(page.second) }
            tab == "ACCOUNT" -> Account()
            tab == "SEARCH" -> SearchBox()?.let { q -> Feed("s:$q", ::open) { Yt.search(q) } }
            else -> Feed("$tab:$signed", ::open) { Yt.tab(tab) }
        }
    }
}

/** A search box; gives the last query searched (or null). */
@Composable
fun SearchBox(): String? {
    var q by rememberSaveable { mutableStateOf("") }
    var ran by rememberSaveable { mutableStateOf<String?>(null) }
    BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(color = Palette.Text, fontSize = 18.sp), cursorBrush = SolidColor(Color.White),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { ran = q.trim().ifEmpty { null } }),
        modifier = Modifier.padding(top = 12.dp).fillMaxWidth(0.6f).background(Palette.Card).padding(14.dp),
        decorationBox = { inner -> if (q.isEmpty()) Text("Search", color = Palette.TextDim, fontSize = 18.sp); inner() })
    return ran
}

@Composable
private fun Feed(key: String, open: (YtItem) -> Unit, load: suspend () -> List<YtShelf>) {
    var shelves by remember(key) { mutableStateOf<List<YtShelf>?>(null) }
    var err by remember(key) { mutableStateOf("") }
    LaunchedEffect(key) { runCatching { shelves = load() }.onFailure { err = it.message ?: "error"; shelves = emptyList() } }
    when {
        shelves == null -> Text("Loading…", color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 16.dp))
        shelves!!.isEmpty() -> Text(if (err.isNotEmpty()) "YouTube didn't answer: $err" else "Nothing here.", color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 16.dp))
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 50.dp)) {
            items(shelves!!, key = { it.title + it.items.first().key }) { s ->
                com.lekathedon.visionlite.home.EchoRowTitle(s.title.ifEmpty { "Videos" })
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(s.items, key = { it.key }) { v ->
                        // Channels round-ish and square, videos wide, Shorts tall (as Vision's cards).
                        val (w, h) = when { v.videoId == null && v.browseId?.startsWith("UC") == true -> 150.dp to 150.dp
                            v.title == "Short" -> 135.dp to 240.dp; else -> 240.dp to 135.dp }
                        Card(v, w, h) { open(v) }
                    }
                }
            }
        }
    }
}

@Composable
fun Card(v: YtItem, w: Dp, h: Dp, onClick: () -> Unit) {
    Column(Modifier.width(w)) {
        Focusable(onClick, Modifier.size(w, h)) {
            Box(Modifier.fillMaxSize().background(Palette.Card)) { v.image?.let { Img(it, w, h, Modifier.fillMaxSize()) } }
        }
        Text(v.title, color = Palette.Text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        if (v.sub.isNotBlank()) Text(v.sub, color = Palette.TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Account() {
    val signed by Yt.signed.collectAsState()
    var code by remember { mutableStateOf<Yt.Code?>(null) }
    var msg by remember { mutableStateOf("") }
    var round by remember { mutableStateOf(0) }
    LaunchedEffect(round) {
        if (round == 0) return@LaunchedEffect
        msg = "Asking YouTube for a code…"
        val c = runCatching { Yt.startLogin() }.getOrElse { msg = "YouTube not reachable (${it.message})"; return@LaunchedEffect }
        code = c; msg = ""
        if (!Yt.waitForLogin(c)) msg = "The code expired or was refused. Try again."
        code = null
    }
    Column(Modifier.padding(top = 20.dp)) {
        when {
            signed -> {
                Text("Signed in. Home is yours and Subscriptions shows.", color = Palette.Text, fontSize = 16.sp)
                Button("Sign out", { Yt.signOut() }, Modifier.padding(top = 14.dp))
            }
            code != null -> Row {
                Qr(code!!.url, 200.dp)
                Column(Modifier.padding(start = 28.dp)) {
                    Text("On your phone open", color = Palette.TextDim, fontSize = 15.sp)
                    Text(code!!.url, color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("and enter", color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
                    Text(code!!.user, color = Palette.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text("Waiting for YouTube…", color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
            else -> {
                Text("Sign in the way YouTube's TV app does: approve a code on your phone. Music uses the same sign-in.", color = Palette.Text, fontSize = 16.sp)
                Button(if (round == 0) "Sign in with YouTube" else "Try again", { round++ }, Modifier.padding(top = 14.dp))
            }
        }
        if (msg.isNotEmpty()) Text(msg, color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
    }
}

/** Full-screen YouTube playback: best video + best audio joined in ExoPlayer (live = HLS). Media3's own controls. */
@OptIn(UnstableApi::class)
@Composable
fun YtPlayer(nav: Nav, s: Screen.YtPlay) {
    val context = LocalContext.current
    var err by remember(s.id) { mutableStateOf("") }
    val player = remember(s.id) { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(s.id) {
        val st = runCatching { withContext(Dispatchers.IO) { Yt.streams(s.id) } }.getOrElse { err = it.message ?: "error"; return@LaunchedEffect }
        val ds = ytDataSource()
        val src = when {
            st.hls != null && st.video == null -> HlsMediaSource.Factory(OkHttpDataSource.Factory(Net.http).setUserAgent(Yt.VR_UA)).createMediaSource(MediaItem.fromUri(st.hls))
            st.video != null && st.audio != null -> MergingMediaSource(
                ProgressiveMediaSource.Factory(ds).createMediaSource(MediaItem.fromUri("yt-v://${s.id}")),
                ProgressiveMediaSource.Factory(ds).createMediaSource(MediaItem.fromUri("yt-a://${s.id}")))
            else -> ProgressiveMediaSource.Factory(ds).createMediaSource(MediaItem.fromUri("yt-a://${s.id}"))
        }
        player.setMediaSource(src); player.prepare(); player.playWhenReady = true
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { PlayerView(it).apply { this.player = player; keepScreenOn = true; isFocusable = true; requestFocus() } }, modifier = Modifier.fillMaxSize())
        if (err.isNotEmpty()) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Can't play: $err", color = Color.White, fontSize = 18.sp)
            Button("Back", { nav.pop() }, Modifier.padding(top = 12.dp))
        }
    }
}
