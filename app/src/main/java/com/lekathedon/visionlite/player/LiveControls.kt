package com.lekathedon.visionlite.player

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import com.lekathedon.visionlite.iptv.Channel
import com.lekathedon.visionlite.iptv.EpgProgram
import com.lekathedon.visionlite.iptv.Iptv
import com.lekathedon.visionlite.iptv.Recorder
import com.lekathedon.visionlite.ui.Focusable
import com.lekathedon.visionlite.ui.HoldGuard
import com.lekathedon.visionlite.ui.Img
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun t(ms: Long) = hm.format(Date(ms))
private val Gold = Color(0xFFD9DC8E)
private val Glass = Color(0xEE0E0F12)

private enum class LivePanel { LIST, GUIDE, OPTIONS, AUDIO, SUBS, STATS, SLEEP }

/**
 * The live TV player's own controls (not the film player's): a channel banner with now / next, a channel list over
 * the picture, this channel's guide, typed channel numbers and an options menu. Keys while nothing is open:
 * Up / Down and CH± = next / previous channel, OK = channel list, hold OK or Menu = options, Right or Guide = this
 * channel's guide, Left = the channel before, Info = banner, digits = channel number, Play/Pause = pause.
 * Cost: nothing runs while hidden but a 30 s clock; the guide is read from the local guide file only when shown.
 */
@Composable
internal fun LiveControls(
    player: Player,
    s: com.lekathedon.visionlite.ui.Screen.Player,
    /** Plays [c]; [list] becomes the Up / Down list when given (the group it was picked from). */
    tune: (Channel, List<Channel>?) -> Unit,
    /** Plays a catch-up link (start over, an aired show). */
    playArchive: (url: String, title: String, headers: Map<String, String>) -> Unit,
    onAspect: (Int) -> Unit,
    stats: () -> StatsSource,
    engine: Engine, onEngine: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var panel by remember { mutableStateOf<LivePanel?>(null) }
    var banner by remember(s.url) { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var minute by remember { mutableLongStateOf(System.currentTimeMillis() / 60_000) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); minute = System.currentTimeMillis() / 60_000 } }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(note) { if (note.isNotEmpty()) { delay(3_500); note = "" } }
    var digits by remember { mutableStateOf("") }
    var aspect by remember { mutableIntStateOf(0) }
    var sleepAt by remember { mutableStateOf<Long?>(null) }
    val root = remember { FocusRequester() }

    val channel by produceState<Channel?>(null, s.url) { value = withContext(Dispatchers.IO) { Iptv.byUrl(s.url) } }
    val nowNext by produceState(emptyList<EpgProgram>(), channel, minute) {
        value = channel?.let { c -> withContext(Dispatchers.IO) { runCatching { Iptv.nowNext(c) }.getOrDefault(emptyList()) } }.orEmpty()
    }
    val nowMs = minute * 60_000
    val now = nowNext.firstOrNull { it.isLive(System.currentTimeMillis()) }
    val recs by Recorder.list.collectAsState()
    val recording = recs.firstOrNull { it.url == s.url && it.state == "recording" }

    // Banner hides itself 5 s after the last change.
    var bannerOn by remember(s.url) { mutableStateOf(true) }
    LaunchedEffect(banner) { bannerOn = true; delay(5_000); bannerOn = false }
    // Typed numbers: the channel is tuned 1.5 s after the last digit.
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1_500)
        val n = digits.toIntOrNull(); digits = ""
        val c = n?.let { withContext(Dispatchers.IO) { Iptv.byNumber(it) } }
        if (c != null) tune(c, null) else note = "No channel $n"
    }
    // Sleep timer: stops the picture at the time picked.
    LaunchedEffect(sleepAt) {
        val at = sleepAt ?: return@LaunchedEffect
        while (System.currentTimeMillis() < at) delay(5_000)
        player.pause(); sleepAt = null; note = "Sleep timer: paused"
    }
    LaunchedEffect(panel) { if (panel == null) runCatching { root.requestFocus() } }

    fun zap(step: Int) { Iptv.zap(s.url, step)?.let { tune(it, null) } }
    var okHeld by remember { mutableStateOf(false) }
    var okDown by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(!player.playWhenReady) }
    DisposableEffect(player) {
        val l = object : Player.Listener { override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { paused = !playWhenReady } }
        player.addListener(l); onDispose { player.removeListener(l) }
    }

    Box(Modifier.fillMaxSize().focusRequester(root).focusable().onPreviewKeyEvent { e ->
        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
        if (e.key == Key.Back) {
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            if (panel != null) { panel = if (panel in listOf(LivePanel.AUDIO, LivePanel.SUBS, LivePanel.SLEEP, LivePanel.STATS)) LivePanel.OPTIONS else null; return@onPreviewKeyEvent true }
            if (bannerOn) { bannerOn = false; return@onPreviewKeyEvent true }
            return@onPreviewKeyEvent false
        }
        if (panel != null) return@onPreviewKeyEvent false
        // OK: short = channel list, hold = options (decided on release / first repeat).
        // okDown: only a press that started here counts (not the release of a press that picked something in a panel).
        if (ok) {
            when {
                e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 -> { okHeld = false; okDown = true }
                e.type == KeyEventType.KeyDown -> if (okDown && !okHeld) { okHeld = true; okDown = false; HoldGuard.held(); panel = LivePanel.OPTIONS }
                e.type == KeyEventType.KeyUp -> { if (okDown && !okHeld) panel = LivePanel.LIST; okDown = false }
            }
            return@onPreviewKeyEvent true
        }
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val digit = when (e.key) {
            Key.Zero, Key.NumPad0 -> 0; Key.One, Key.NumPad1 -> 1; Key.Two, Key.NumPad2 -> 2; Key.Three, Key.NumPad3 -> 3
            Key.Four, Key.NumPad4 -> 4; Key.Five, Key.NumPad5 -> 5; Key.Six, Key.NumPad6 -> 6; Key.Seven, Key.NumPad7 -> 7
            Key.Eight, Key.NumPad8 -> 8; Key.Nine, Key.NumPad9 -> 9; else -> -1
        }
        if (digit >= 0) { if (digits.length < 5) digits += digit; return@onPreviewKeyEvent true }
        when (e.key) {
            Key.DirectionUp, Key.ChannelUp -> zap(-1)
            Key.DirectionDown, Key.ChannelDown -> zap(1)
            Key.DirectionRight, Key.Guide -> panel = LivePanel.GUIDE
            Key.DirectionLeft, Key.LastChannel -> scope.launch {
                withContext(Dispatchers.IO) { Iptv.previous(s.url) }?.let { tune(it, null) } ?: run { note = "No channel before this one" }
            }
            Key.Menu -> panel = LivePanel.OPTIONS
            Key.Info -> banner = SystemClock.uptimeMillis()
            Key.MediaPlayPause -> if (player.playWhenReady) player.pause() else player.play()
            Key.MediaPlay -> player.play()
            Key.MediaPause -> player.pause()
            else -> return@onPreviewKeyEvent false
        }
        true
    }) {
        if (note.isNotEmpty()) Text(note, color = Color.White, fontSize = 15.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp)
            .background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 8.dp))
        if (digits.isNotEmpty()) Text(digits, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 48.dp).background(Color(0xCC000000), RoundedCornerShape(10.dp))
                .padding(horizontal = 22.dp, vertical = 6.dp))
        if (paused) Text("❚❚  Paused · Play to go on", color = Color.White, fontSize = 15.sp,
            modifier = Modifier.align(Alignment.TopStart).padding(top = 32.dp, start = 48.dp).background(Color(0xCC000000), RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp))

        if ((bannerOn || panel == LivePanel.OPTIONS) && panel != LivePanel.LIST && panel != LivePanel.GUIDE)
            Banner(channel, s.title, nowNext, now, nowMs, recording != null, sleepAt, player, Modifier.align(Alignment.BottomStart))

        when (panel) {
            LivePanel.LIST -> ChannelList(s.url, minute, onPick = { c, list -> panel = null; tune(c, list) }, onClose = { panel = null })
            LivePanel.GUIDE -> channel?.let { c ->
                GuidePanel(c, minute, onLive = { panel = null },
                    onArchive = { url, title -> panel = null; playArchive(url, "${c.name} · $title", c.headers) },
                    onNote = { note = it })
            }
            LivePanel.OPTIONS -> {
                val c = channel
                val items = buildList<Pair<String, () -> Unit>> {
                    if (c != null) {
                        now?.let { pr -> Iptv.startOverUrl(c, pr)?.let { url -> add("⟲ Start over · ${pr.title}" to { panel = null; playArchive(url, "${c.name} · ${pr.title}", c.headers) }) } }
                        add((if (Iptv.isFavourite(c)) "★ Remove from favourites" else "☆ Add to favourites") to {
                            Iptv.toggleFavourite(c); note = if (Iptv.isFavourite(c)) "Added to favourites" else "Removed from favourites"; panel = null
                        })
                        add((if (recording != null) "■ Stop recording" else "● Record " + (now?.let { "\"${it.title}\" (to ${t(it.stopMs)})" } ?: "now (2 h)")) to {
                            if (recording != null) { Recorder.stop(recording.id); note = "Recording stopped" }
                            else { Recorder.recordNow(c, now?.title ?: c.name, now?.stopMs?.plus(120_000L)); note = "Recording" }
                            panel = null
                        })
                        add("Guide for this channel" to { panel = LivePanel.GUIDE })
                    }
                    add("Audio" to { panel = LivePanel.AUDIO })
                    add("Subtitles / captions" to { panel = LivePanel.SUBS })
                    add("Picture · ${listOf("Fit", "Zoom", "Stretch")[aspect]}" to {
                        aspect = (aspect + 1) % 3
                        onAspect(listOf(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT, androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL)[aspect])
                        note = "Picture: ${listOf("Fit", "Zoom", "Stretch")[aspect]}"
                    })
                    add((sleepAt?.let { "Sleep in ${((it - System.currentTimeMillis()) / 60_000) + 1} min" } ?: "Sleep timer") to { panel = LivePanel.SLEEP })
                    add("Stats" to { panel = LivePanel.STATS })
                    add("Engine · ${engine.label} (switch)" to { panel = null; onEngine() })
                }
                SidePanel(c?.name ?: s.title, items.map { it.first }, -1) { i -> items[i].second() }
            }
            LivePanel.AUDIO -> TrackPanel("Audio", player, C.TRACK_TYPE_AUDIO, allowOff = false) { panel = null }
            LivePanel.SUBS -> TrackPanel("Subtitles / captions", player, C.TRACK_TYPE_TEXT, allowOff = true) { panel = null }
            LivePanel.STATS -> StatsPanel(remember(engine) { stats() }, Modifier.align(Alignment.TopEnd))
            LivePanel.SLEEP -> {
                val opts = listOf(0, 15, 30, 60, 90, 120)
                val labels = opts.map { if (it == 0) "Off" else "$it minutes" } + listOfNotNull(now?.let { "End of \"${it.title}\" (${t(it.stopMs)})" })
                SidePanel("Sleep timer", labels, if (sleepAt == null) 0 else -1) { i ->
                    sleepAt = when {
                        i == 0 -> null
                        i < opts.size -> System.currentTimeMillis() + opts[i] * 60_000L
                        else -> now?.stopMs
                    }
                    note = sleepAt?.let { "Pauses at ${t(it)}" } ?: "Sleep timer off"
                    panel = null
                }
            }
            null -> {}
        }
    }
}

/** Bottom banner: number, logo, name, what's on with its progress, what's next, and the line's picture. */
@Composable
private fun Banner(c: Channel?, title: String, nowNext: List<EpgProgram>, now: EpgProgram?, nowMs: Long, rec: Boolean, sleepAt: Long?, player: Player, modifier: Modifier) {
    val next = nowNext.firstOrNull { it != now && it.startMs >= (now?.stopMs ?: 0) }
    Box(modifier.fillMaxWidth().height(230.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF0000000)))))
    Row(modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 40.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.size(132.dp, 74.dp).background(Color(0x22FFFFFF), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Img(c?.logo, 120.dp, 66.dp, Modifier.size(120.dp, 66.dp), ContentScale.Fit)
        }
        Column(Modifier.padding(start = 20.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                c?.let { Text("${it.n}", color = Gold, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp)) }
                Text(c?.name ?: title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                val h = player.videoSize.height
                val tags = listOfNotNull(
                    c?.takeIf { Iptv.isFavourite(it) }?.let { "★" },
                    when { h >= 2000 -> "4K"; h >= 1000 -> "FHD"; h >= 700 -> "HD"; h > 0 -> "SD"; else -> null },
                    c?.catchup?.let { "CATCH-UP" }, if (rec) "● REC" else null, sleepAt?.let { "SLEEP ${t(it)}" })
                tags.forEach { tag ->
                    Text(tag, color = if (tag == "● REC") Color(0xFFFF6B6B) else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp).background(Color(0x33FFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(t(System.currentTimeMillis()), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            c?.group?.takeIf { it.isNotBlank() }?.let { Text(it, color = Color(0xFFB0B0B0), fontSize = 13.sp, maxLines = 1) }
            if (now != null) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${t(now.startMs)}–${t(now.stopMs)}", color = Gold, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(110.dp))
                    Text(now.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("${((now.stopMs - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)} min left", color = Color(0xFFB0B0B0), fontSize = 13.sp)
                }
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).background(Color(0x33FFFFFF), RoundedCornerShape(50))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(now.progress(maxOf(nowMs, System.currentTimeMillis()))).background(Gold, RoundedCornerShape(50)))
                }
                now.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Color(0xFFCFCFCF), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
            } else Text(if (c?.tvg == null) "No guide for this channel" else "Nothing in the guide right now", color = Color(0xFFB0B0B0), fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp))
            next?.let {
                Row(Modifier.padding(top = 6.dp)) {
                    Text("Next ${t(it.startMs)}", color = Color(0xFFB0B0B0), fontSize = 14.sp, modifier = Modifier.width(110.dp))
                    Text(it.title, color = Color(0xFFDDDDDD), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text("OK channels · Right guide · Left last channel · hold OK options", color = Color(0x99FFFFFF), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/**
 * Channel list over the picture (left side): the list Up / Down steps through, the playing channel focused, each row
 * with what's on. Left opens the groups; picking a group lists its channels; OK plays, hold OK = favourite.
 */
@Composable
private fun ChannelList(current: String, minute: Long, onPick: (Channel, List<Channel>) -> Unit, onClose: () -> Unit) {
    var group by remember { mutableStateOf<String?>(null) }   // null = the list playing now
    var picking by remember { mutableStateOf(false) }
    val version by Iptv.version.collectAsState()
    val channels by produceState(Iptv.zapList, group, version) {
        val g = group
        if (g != null) value = withContext(Dispatchers.IO) { Iptv.channels(g.ifEmpty { null }) }
    }
    val groups by produceState(emptyList<Pair<String, Int>>(), picking) { if (picking) value = withContext(Dispatchers.IO) { Iptv.groups() } }
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val start = channels.indexOfFirst { it.url == current }.coerceAtLeast(0)
    LaunchedEffect(channels, picking) {
        if (picking || channels.isEmpty()) return@LaunchedEffect
        list.scrollToItem((start - 3).coerceAtLeast(0)); delay(40); runCatching { focus.requestFocus() }
    }
    Row(Modifier.fillMaxSize()) {
        if (picking) {
            val gFocus = remember { FocusRequester() }
            LaunchedEffect(groups) { if (groups.isNotEmpty()) { delay(40); runCatching { gFocus.requestFocus() } } }
            val all = listOf("★" to Iptv.favourites().size, Iptv.RECENT to Iptv.recents().size, "" to groups.sumOf { it.second }) + groups
            LazyColumn(Modifier.width(300.dp).fillMaxHeight().background(Glass), contentPadding = PaddingValues(vertical = 32.dp, horizontal = 12.dp)) {
                itemsIndexed(all, key = { _, it -> it.first }) { i, (g, n) ->
                    val name = when (g) { "★" -> "★ Favourites"; Iptv.RECENT -> "⟲ Recent"; "" -> "All channels"; else -> g }
                    Focusable({ group = g; picking = false }, (if (i == 0) Modifier.focusRequester(gFocus) else Modifier).padding(bottom = 4.dp).fillMaxWidth()) { f ->
                        Row(Modifier.fillMaxWidth().background(if (f) Color.White else Color.Transparent).padding(10.dp)) {
                            Text(name, color = if (f) Color.Black else Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("$n", color = if (f) Color.DarkGray else Color(0xFFAAAAAA), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        Column(Modifier.width(520.dp).fillMaxHeight().background(Glass).padding(top = 28.dp, start = 16.dp, end = 16.dp)) {
            Text(when (group) { null -> "Channels"; "★" -> "★ Favourites"; Iptv.RECENT -> "⟲ Recent"; "" -> "All channels"; else -> group!! },
                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("${channels.size} channels · Left: groups · hold OK: favourite", color = Color(0xFFAAAAAA), fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
            if (channels.isEmpty()) Text(if (group == "★") "No favourites yet: hold OK on a channel." else "No channels here.", color = Color(0xFFAAAAAA), fontSize = 14.sp)
            LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                itemsIndexed(channels, key = { _, c -> c.key }) { i, c ->
                    var favTick by remember { mutableIntStateOf(0) }
                    val nowP by produceState<EpgProgram?>(null, c, minute) {
                        value = withContext(Dispatchers.IO) { runCatching { Iptv.nowNext(c).firstOrNull { it.isLive(System.currentTimeMillis()) } }.getOrNull() }
                    }
                    Focusable({ onPick(c, channels) }, (if (i == start) Modifier.focusRequester(focus) else Modifier).fillMaxWidth().onPreviewKeyEvent { e ->
                        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter
                        when {
                            e.type != KeyEventType.KeyDown -> false
                            e.key == Key.DirectionLeft -> { picking = true; true }
                            ok && e.nativeKeyEvent.repeatCount == 1 -> { HoldGuard.held(); Iptv.toggleFavourite(c); favTick++; true }
                            ok && e.nativeKeyEvent.repeatCount > 1 -> true
                            else -> false
                        }
                    }) { f ->
                        val playing = c.url == current
                        Row(Modifier.fillMaxWidth().background(if (f) Color.White else if (playing) Color(0x33D9DC8E) else Color(0x0DFFFFFF)).padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("${c.n}", color = if (f) Color.DarkGray else Color(0xFFAAAAAA), fontSize = 13.sp, modifier = Modifier.width(46.dp))
                            Box(Modifier.size(54.dp, 30.dp), contentAlignment = Alignment.Center) { Img(c.logo, 54.dp, 30.dp, Modifier.size(54.dp, 30.dp), ContentScale.Fit) }
                            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                val fav = remember(favTick, c) { Iptv.isFavourite(c) }
                                Text((if (playing) "▶ " else "") + (if (fav) "★ " else "") + c.name, color = if (f) Color.Black else Color.White, fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                nowP?.let { pr ->
                                    Text(pr.title, color = if (f) Color.DarkGray else Color(0xFFAAAAAA), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Box(Modifier.padding(top = 3.dp).fillMaxWidth().height(3.dp).background(Color(0x33808080))) {
                                        Box(Modifier.fillMaxHeight().fillMaxWidth(pr.progress(System.currentTimeMillis())).background(if (f) Color.Black else Gold))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * This channel's guide (right side): aired shows play from catch-up, the running one says so (OK = back to live,
 * or start over from the options), later ones set or clear a recording.
 */
@Composable
private fun GuidePanel(c: Channel, minute: Long, onLive: () -> Unit, onArchive: (String, String) -> Unit, onNote: (String) -> Unit) {
    val all by produceState(emptyList<EpgProgram>(), c, minute / 10) { value = withContext(Dispatchers.IO) { runCatching { Iptv.schedule(c) }.getOrDefault(emptyList()) } }
    val nowMs = System.currentTimeMillis()
    val nowIndex = all.indexOfFirst { it.stopMs > nowMs }.coerceAtLeast(0)
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(all.size) { if (all.isNotEmpty()) { list.scrollToItem((nowIndex - 2).coerceAtLeast(0)); delay(40); runCatching { focus.requestFocus() } } }
    val recs by Recorder.list.collectAsState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.CenterEnd).width(520.dp).fillMaxHeight().background(Glass).padding(top = 28.dp, start = 18.dp, end = 18.dp)) {
            Text(c.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(c.catchup?.let { "Catch-up ${it.days} days: OK on an aired show plays it" } ?: "OK on a later show records it",
                color = Color(0xFFAAAAAA), fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
            if (all.isEmpty()) { Text("No guide for this channel", color = Color(0xFFAAAAAA), fontSize = 14.sp); return@Column }
            LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                itemsIndexed(all) { i, pr ->
                    val live = pr.isLive(nowMs)
                    val past = pr.stopMs <= nowMs
                    val archive = if (past) Iptv.catchupUrl(c, pr, nowMs) else null
                    val set = !past && !live && recs.any { it.url == c.url && it.title == pr.title && it.start == pr.startMs - 60_000L && it.state == "scheduled" }
                    Focusable({
                        when {
                            live -> onLive()
                            archive != null -> onArchive(archive, pr.title)
                            past -> onNote("Not in this channel's catch-up")
                            set -> { Recorder.unschedule(c, pr); onNote("Recording cancelled") }
                            else -> { Recorder.schedule(c, pr); onNote("Will record \"${pr.title}\" at ${t(pr.startMs)}") }
                        }
                    }, (if (i == nowIndex) Modifier.focusRequester(focus) else Modifier).fillMaxWidth()) { f ->
                        Column(Modifier.fillMaxWidth().background(if (f) Color.White else if (live) Color(0x22D9DC8E) else Color.Transparent).padding(8.dp)) {
                            val tag = when { live -> "NOW"; archive != null -> "▶ CATCH-UP"; past -> "AIRED"; set -> "● REC SET"; else -> "" }
                            Text("${t(pr.startMs)}–${t(pr.stopMs)}  $tag", color = if (f) Color.DarkGray else if (live || archive != null) Gold else Color(0xFFAAAAAA),
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(pr.title, color = if (f) Color.Black else if (past && archive == null) Color(0xFF999999) else Color.White, fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (f || live) pr.description?.let { Text(it, color = if (f) Color.DarkGray else Color(0xFFAAAAAA), fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
        }
    }
}
