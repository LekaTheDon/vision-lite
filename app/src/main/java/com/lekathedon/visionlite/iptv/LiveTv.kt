package com.lekathedon.visionlite.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.phone.PhoneBox
import com.lekathedon.visionlite.phone.PhoneForm
import com.lekathedon.visionlite.ui.Button
import com.lekathedon.visionlite.ui.Focusable
import com.lekathedon.visionlite.ui.Img
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun time(ms: Long) = hm.format(Date(ms))

/** Live TV: groups │ channels with what's on │ the focused channel's now and next. OK plays, hold OK = favourite. */
@Composable
fun LiveTv(nav: Nav) {
    val version by Iptv.version.collectAsState()
    val status by Iptv.status.collectAsState()
    val scope = rememberCoroutineScope()
    var managing by rememberSaveable { mutableStateOf(false) }
    val playlists = remember(version) { Iptv.playlists() }
    LaunchedEffect(Unit) { if (playlists.isNotEmpty()) Iptv.ensureLoaded() }
    if (managing || playlists.isEmpty()) {
        androidx.activity.compose.BackHandler(enabled = managing) { managing = false }
        Playlists(onDone = { managing = false; scope.launch { Iptv.ensureLoaded() } })
        return
    }
    var group by rememberSaveable { mutableStateOf<String?>(if (Iptv.favourites().isNotEmpty()) "★" else null) }
    val groups by produceState(emptyList<Pair<String, Int>>(), version) { value = withContext(Dispatchers.IO) { Iptv.groups() } }
    val channels by produceState(emptyList<Channel>(), version, group) { value = withContext(Dispatchers.IO) { Iptv.channels(group) } }
    var focused by remember { mutableStateOf<Channel?>(null) }
    var minute by remember { mutableStateOf(System.currentTimeMillis() / 60_000) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); minute = System.currentTimeMillis() / 60_000 } }
    val first = remember { FocusRequester() }
    LaunchedEffect(channels.isNotEmpty()) { if (channels.isNotEmpty()) runCatching { first.requestFocus() } }

    Column(Modifier.fillMaxSize().background(LocalLook.current.page).padding(start = 36.dp, end = 36.dp, top = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Live TV", color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(status.ifEmpty { "${channels.size} channels" }, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp).weight(1f))
            Button("Refresh", { scope.launch { Iptv.refreshAll() } }, Modifier.padding(end = 8.dp))
            Button("Playlists", { managing = true })
        }
        Row(Modifier.fillMaxSize().padding(top = 14.dp)) {
            // Groups
            LazyColumn(Modifier.width(230.dp).fillMaxHeight(), contentPadding = PaddingValues(bottom = 24.dp)) {
                val all = listOf("★" to Iptv.favourites().size, "" to groups.sumOf { it.second }) + groups
                items(all, key = { it.first }) { (g, n) ->
                    val name = when (g) { "★" -> "★ Favourites"; "" -> "All channels"; else -> g }
                    val selected = (group ?: "") == g
                    Focusable({ group = g.ifEmpty { null } }, Modifier.padding(bottom = 4.dp).fillMaxWidth()) { f ->
                        Row(Modifier.fillMaxWidth().background(if (f) Color.White else if (selected) Color(0x33FFFFFF) else Color.Transparent).padding(10.dp)) {
                            Text(name, color = if (f) Color.Black else Palette.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("$n", color = if (f) Color.DarkGray else Palette.TextDim, fontSize = 12.sp)
                        }
                    }
                }
            }
            // Channels
            val list = rememberLazyListState()
            LazyColumn(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 14.dp), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
                if (channels.isEmpty()) item {
                    Text(if (group == "★") "No favourites yet: hold OK on a channel." else if (status.isNotEmpty()) "Loading…" else "No channels here.",
                        color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.padding(12.dp))
                }
                items(channels, key = { it.key }) { c ->
                    ChannelRow(c, minute, if (c == channels.first()) Modifier.focusRequester(first) else Modifier,
                        onFocus = { focused = c },
                        onPlay = { nav.push(Screen.Player(c.url, c.name, c.headers, type = "live")) },
                        onHold = { Iptv.toggleFavourite(c) })
                }
            }
            // Now / next of the focused channel
            Column(Modifier.width(360.dp).fillMaxHeight().background(Color(0x14FFFFFF)).padding(18.dp)) {
                val c = focused
                if (c != null) {
                    val nn by produceState(emptyList<EpgProgram>(), c, minute, version) { value = withContext(Dispatchers.IO) { Iptv.nowNext(c) } }
                    Img(c.logo, 120.dp, 68.dp, Modifier.size(120.dp, 68.dp), ContentScale.Fit)
                    Text(c.name, color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    Text(c.group + if (Iptv.isFavourite(c)) " · ★" else "", color = Palette.TextDim, fontSize = 13.sp)
                    if (nn.isEmpty()) Text("No guide for this channel", color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp))
                    nn.forEachIndexed { i, pr ->
                        Text(if (i == 0 && pr.isLive(System.currentTimeMillis())) "NOW  ${time(pr.startMs)}–${time(pr.stopMs)}" else "NEXT  ${time(pr.startMs)}–${time(pr.stopMs)}",
                            color = Color(0xFFD9DC8E), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
                        Text(pr.title, color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        if (i == 0) pr.description?.let { Text(it, color = Palette.TextDim, fontSize = 13.sp, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("OK plays · hold OK: favourite", color = Palette.TextDim, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(c: Channel, minute: Long, modifier: Modifier, onFocus: () -> Unit, onPlay: () -> Unit, onHold: () -> Unit) {
    val version by Iptv.version.collectAsState()
    val now by produceState<EpgProgram?>(null, c, minute, version) { value = withContext(Dispatchers.IO) { Iptv.nowNext(c).firstOrNull { it.isLive(System.currentTimeMillis()) } } }
    var held by remember { mutableStateOf(false) }
    Focusable(onPlay, modifier.padding(bottom = 4.dp).fillMaxWidth().onPreviewKeyEvent { e ->
        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter
        when {
            ok && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0 -> { if (!held) { held = true; onHold() }; true }
            ok && e.type == KeyEventType.KeyUp && held -> { held = false; true }
            else -> false
        }
    }, onFocus = onFocus) { f ->
        Row(Modifier.fillMaxWidth().background(if (f) Color.White else Color(0x0FFFFFFF)).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${c.n}", color = if (f) Color.DarkGray else Palette.TextDim, fontSize = 13.sp, modifier = Modifier.width(44.dp))
            Box(Modifier.size(56.dp, 32.dp), contentAlignment = Alignment.Center) { Img(c.logo, 56.dp, 32.dp, Modifier.size(56.dp, 32.dp), ContentScale.Fit) }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text((if (Iptv.isFavourite(c)) "★ " else "") + c.name, color = if (f) Color.Black else Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                now?.let { pr ->
                    Text(pr.title, color = if (f) Color.DarkGray else Palette.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.padding(top = 3.dp).fillMaxWidth().height(3.dp).background(Color(0x33808080))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(pr.progress(System.currentTimeMillis())).background(if (f) Color.Black else Color(0xFFD9DC8E)))
                    }
                }
            }
        }
    }
}

/** Add an M3U link or an Xtream login from the phone; remove playlists. */
@Composable
private fun Playlists(onDone: () -> Unit) {
    val version by Iptv.version.collectAsState()
    val list = remember(version) { Iptv.playlists() }
    var adding by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(adding, list.size) { if (adding == null) runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize().background(LocalLook.current.page).padding(48.dp)) {
        Text("Live TV playlists", color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("An M3U link, or an Xtream login (server, username, password). Entered from your phone. Live TV loads only when you open it, at most once a day unless you press Refresh.",
            color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp).fillMaxWidth(0.7f))
        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button("Add M3U link", { adding = "m3u" }, Modifier.focusRequester(first))
            Button("Add Xtream login", { adding = "xtream" })
            if (list.isNotEmpty()) Button("Done", onDone)
        }
        list.forEach { pl ->
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${pl.name} · ${if (pl.xtream) "Xtream" else "M3U"}", color = Palette.Text, fontSize = 16.sp, modifier = Modifier.width(420.dp))
                Button("Remove", { Iptv.remove(pl.id) })
            }
        }
    }
    when (adding) {
        "m3u" -> PhoneBox("M3U playlist", listOf(
            PhoneForm.Field("Name", initial = "My TV"),
            PhoneForm.Field("M3U link", hint = "http://…"),
            PhoneForm.Field("Guide (EPG) link", hint = "optional: the playlist's own guide is used otherwise")),
            onSave = { v ->
                adding = null
                if (v.getOrNull(1).orEmpty().isNotBlank()) Iptv.add(Playlist(System.currentTimeMillis().toString(), v[0].ifBlank { "M3U" }, url = v[1].trim(), epg = v.getOrNull(2).orEmpty().trim()))
            }, onCancel = { adding = null })
        "xtream" -> PhoneBox("Xtream login", listOf(
            PhoneForm.Field("Name", initial = "My TV"),
            PhoneForm.Field("Server", hint = "http://host:port"),
            PhoneForm.Field("Username"),
            PhoneForm.Field("Password", secret = true)),
            onSave = { v ->
                adding = null
                if (v.getOrNull(1).orEmpty().isNotBlank()) Iptv.add(Playlist(System.currentTimeMillis().toString(), v[0].ifBlank { "Xtream" },
                    server = v[1].trim(), user = v.getOrNull(2).orEmpty().trim(), pass = v.getOrNull(3).orEmpty()))
            }, onCancel = { adding = null })
    }
}
