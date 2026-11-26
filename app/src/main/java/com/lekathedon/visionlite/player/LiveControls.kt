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
import com.lekathedon.visionlite.App
import androidx.media3.common.MimeTypes
import androidx.media3.common.MediaItem
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
private val Gold: Color get() = com.lekathedon.visionlite.ui.KitInk.accent
private val Glass: Color get() = com.lekathedon.visionlite.ui.KitInk.panel.copy(alpha = 0.94f)

private enum class LivePanel { LIST, GROUPS, GUIDE, OPTIONS, AUDIO, SUBS, STATS, SLEEP }

private fun span(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

/**
 * The live TV player's own controls, TiviMate style. Every button does what you assigned in Settings › Live TV and
 * more › Remote buttons (LiveKeys.kt); defaults: OK = channel list (left), hold OK / Menu = control bar along the
 * bottom, Up / Down = channels, Left / Right = rewind / forward (live rewind, Timeshift.kt), Guide = this channel's
 * programmes next to the channel list, Play/Pause = pause live. Nothing opens on the right.
 * Cost: nothing runs while hidden but a 30 s clock (1 s while rewound, for the "behind live" line).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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
    val ts by Timeshift.state.collectAsState()

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

    var paused by remember { mutableStateOf(!player.playWhenReady) }
    DisposableEffect(player) {
        val l = object : Player.Listener { override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { paused = !playWhenReady } }
        player.addListener(l); onDispose { player.removeListener(l) }
    }
    // While rewound: a 1 s tick for the "behind live" line.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(ts.ring) { while (ts.ring) { delay(1_000); tick++ } }

    // ---- live rewind ----
    fun ringItem(uri: android.net.Uri) = MediaItem.Builder().setUri(uri).setMimeType(MimeTypes.APPLICATION_M3U8)
        .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build()).build()
    fun behind(): Long = if (ts.ring && player.duration != C.TIME_UNSET) (player.duration - player.currentPosition).coerceAtLeast(0) else 0
    /** Into the ring [back] ms before what's on screen; paused when [pause]. */
    fun enterRing(back: Long, pause: Boolean): Boolean {
        if (engine != Engine.EXO) return false
        val lag = (player.bufferedPosition - player.currentPosition).coerceIn(0, 60_000)
        val uri = Timeshift.enter() ?: return false
        val kept = Timeshift.state.value.keptMs
        player.setMediaItem(ringItem(uri), (kept - lag - back).coerceAtLeast(0)); player.prepare(); player.playWhenReady = !pause
        return true
    }
    fun goLive() {
        if (!ts.ring) { if (!player.playWhenReady) player.play(); return }
        Timeshift.leave(); player.setMediaItem(MediaItem.fromUri(s.url)); player.prepare(); player.play()
        note = "Live"; banner = SystemClock.uptimeMillis()
    }
    fun applySeek(d: Long) {
        when {
            engine != Engine.EXO -> note = "Live rewind needs ExoPlayer (hold OK › Engine)"
            ts.ring -> {
                val t = player.currentPosition + d
                if (d > 0 && player.duration != C.TIME_UNSET && t >= player.duration - 3_000) goLive() else player.seekTo(t.coerceAtLeast(0))
            }
            d < 0 && ts.on -> if (!enterRing(-d, false)) note = "Nothing to rewind yet"
            player.isCurrentMediaItemLive && player.isCurrentMediaItemSeekable && player.duration != C.TIME_UNSET ->
                player.seekTo((player.currentPosition + d).coerceIn(0, player.duration))
            d > 0 -> note = "You're watching live"
            App.instance.prefs.liveRewind.value == "0" -> note = "Live rewind is off · Settings › Live TV and more"
            else -> note = "This channel can't be rewound (HLS link without a rewind window)"
        }
        banner = SystemClock.uptimeMillis()
    }
    // Presses add up (10 s, then 3 s per repeat while held) and are applied once you stop: one seek, not twenty.
    var pending by remember { mutableLongStateOf(0L) }
    LaunchedEffect(pending) { if (pending == 0L) return@LaunchedEffect; delay(650); val d = pending; pending = 0; applySeek(d) }
    fun seekBy(d: Long) { pending += d; banner = SystemClock.uptimeMillis() }
    fun pauseToggle() {
        if (!player.playWhenReady) { player.play(); return }
        if (!(ts.on && !ts.ring && enterRing(0, pause = true))) player.pause()
        banner = SystemClock.uptimeMillis()
    }
    fun startOver() {
        val c = channel ?: return
        val pr = now ?: run { note = "Nothing in the guide right now"; return }
        Iptv.startOverUrl(c, pr)?.let { playArchive(it, "${c.name} · ${pr.title}", c.headers) } ?: run { note = "No catch-up for this show" }
    }

    fun zap(step: Int) { Iptv.zap(s.url, step)?.let { tune(it, null) } }
    fun perform(a: LiveKeys.Action, repeat: Int) {
        when (a) {
            LiveKeys.Action.NONE -> {}
            LiveKeys.Action.CHANNELS -> panel = LivePanel.LIST
            LiveKeys.Action.GROUPS -> panel = LivePanel.GROUPS
            LiveKeys.Action.GUIDE -> panel = LivePanel.GUIDE
            LiveKeys.Action.MENU -> panel = LivePanel.OPTIONS
            LiveKeys.Action.INFO -> banner = SystemClock.uptimeMillis()
            LiveKeys.Action.REWIND -> seekBy(if (repeat == 0) -10_000 else -3_000)
            LiveKeys.Action.FORWARD -> seekBy(if (repeat == 0) 10_000 else 3_000)
            LiveKeys.Action.PLAY_PAUSE -> if (repeat == 0) pauseToggle()
            LiveKeys.Action.GO_LIVE -> if (repeat == 0) goLive()
            LiveKeys.Action.START_OVER -> if (repeat == 0) startOver()
            LiveKeys.Action.ZAP_PREV -> if (repeat % 5 == 0) zap(-1)
            LiveKeys.Action.ZAP_NEXT -> if (repeat % 5 == 0) zap(1)
            LiveKeys.Action.LAST_CHANNEL -> if (repeat == 0) scope.launch {
                withContext(Dispatchers.IO) { Iptv.previous(s.url) }?.let { tune(it, null) } ?: run { note = "No channel before this one" }
            }
            LiveKeys.Action.FAVOURITE -> channel?.takeIf { repeat == 0 }?.let { c ->
                Iptv.toggleFavourite(c); note = if (Iptv.isFavourite(c)) "Added to favourites" else "Removed from favourites"
            }
            LiveKeys.Action.RECORD -> channel?.takeIf { repeat == 0 }?.let { c ->
                if (recording != null) { Recorder.stop(recording.id); note = "Recording stopped" }
                else { Recorder.recordNow(c, now?.title ?: c.name, now?.stopMs?.plus(120_000L)); note = "Recording" }
            }
            LiveKeys.Action.AUDIO -> panel = LivePanel.AUDIO
            LiveKeys.Action.SUBTITLES -> panel = LivePanel.SUBS
            LiveKeys.Action.PICTURE -> if (repeat == 0) {
                aspect = (aspect + 1) % 3
                onAspect(listOf(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT, androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                    androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL)[aspect])
                note = "Picture: ${listOf("Fit", "Zoom", "Stretch")[aspect]}"
            }
            LiveKeys.Action.SLEEP -> panel = LivePanel.SLEEP
            LiveKeys.Action.STATS -> panel = LivePanel.STATS
        }
    }
    // A key with a hold action of its own decides on release (short) or on its first repeat (hold).
    var downKey by remember { mutableStateOf<Key?>(null) }
    var heldFired by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().focusRequester(root).focusable().onPreviewKeyEvent { e ->
        if (e.key == Key.Back) {
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            // The channel list handles its own Back (programmes → channels → groups → closed).
            if (panel in listOf(LivePanel.LIST, LivePanel.GROUPS, LivePanel.GUIDE)) return@onPreviewKeyEvent false
            if (panel != null) { panel = if (panel in listOf(LivePanel.AUDIO, LivePanel.SUBS, LivePanel.SLEEP, LivePanel.STATS)) LivePanel.OPTIONS else null; return@onPreviewKeyEvent true }
            if (pending != 0L) { pending = 0; return@onPreviewKeyEvent true }
            if (bannerOn) { bannerOn = false; return@onPreviewKeyEvent true }
            return@onPreviewKeyEvent false
        }
        if (panel != null) return@onPreviewKeyEvent false
        if (e.type == KeyEventType.KeyDown) {
            val digit = when (e.key) {
                Key.Zero, Key.NumPad0 -> 0; Key.One, Key.NumPad1 -> 1; Key.Two, Key.NumPad2 -> 2; Key.Three, Key.NumPad3 -> 3
                Key.Four, Key.NumPad4 -> 4; Key.Five, Key.NumPad5 -> 5; Key.Six, Key.NumPad6 -> 6; Key.Seven, Key.NumPad7 -> 7
                Key.Eight, Key.NumPad8 -> 8; Key.Nine, Key.NumPad9 -> 9; else -> -1
            }
            if (digit >= 0) { if (digits.length < 5) digits += digit; return@onPreviewKeyEvent true }
        }
        val (short, long) = LiveKeys.buttonOf(e.key) ?: return@onPreviewKeyEvent false
        val sa = LiveKeys.get(short)
        val la = long?.let { LiveKeys.get(it) }
        val rc = e.nativeKeyEvent.repeatCount
        val ownHold = la != null && la != sa
        if (!ownHold) {
            // Same action held: act on press, and again while held for the repeating ones (rewind, channels).
            if (e.type == KeyEventType.KeyDown && (rc == 0 || LiveKeys.repeats(sa))) perform(sa, rc)
            return@onPreviewKeyEvent true
        }
        when {
            e.type == KeyEventType.KeyDown && rc == 0 -> { downKey = e.key; heldFired = false }
            e.type == KeyEventType.KeyDown -> if (downKey == e.key) {
                if (!heldFired) { heldFired = true; HoldGuard.held(); perform(la!!, 0) }
                else if (LiveKeys.repeats(la!!)) perform(la, rc)
            }
            e.type == KeyEventType.KeyUp -> { if (downKey == e.key && !heldFired) perform(sa, 0); downKey = null }
        }
        true
    }) {
        if (note.isNotEmpty()) Text(note, color = com.lekathedon.visionlite.ui.KitInk.text, fontSize = 15.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp)
            .background(com.lekathedon.visionlite.ui.KitInk.panel.copy(alpha = 0.92f), RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 8.dp))
        if (digits.isNotEmpty()) Text(digits, color = com.lekathedon.visionlite.ui.KitInk.text, fontSize = 44.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 48.dp).background(com.lekathedon.visionlite.ui.KitInk.panel.copy(alpha = 0.92f), RoundedCornerShape(10.dp))
                .padding(horizontal = 22.dp, vertical = 6.dp))
        if (paused) Text("❚❚  Paused" + (if (ts.ring) " · ${span(behind())} behind live" else "") + " · Play to go on", color = com.lekathedon.visionlite.ui.KitInk.text, fontSize = 15.sp,
            modifier = Modifier.align(Alignment.TopStart).padding(top = 32.dp, start = 48.dp).background(com.lekathedon.visionlite.ui.KitInk.panel.copy(alpha = 0.92f), RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp))

        val listOpen = panel == LivePanel.LIST || panel == LivePanel.GROUPS || panel == LivePanel.GUIDE
        val bar = panel in listOf(LivePanel.OPTIONS, LivePanel.AUDIO, LivePanel.SUBS, LivePanel.SLEEP)
        if ((bannerOn || bar || pending != 0L) && !listOpen) {
            tick
            val shift = Shift(ts.on, ts.ring, ts.keptMs, (behind() - pending).coerceIn(0, maxOf(ts.keptMs, behind())), pending != 0L)
            Banner(channel, s.title, nowNext, now, nowMs, recording != null, sleepAt, player, shift, if (bar) 128.dp else 0.dp, Modifier.align(Alignment.BottomStart))
        }

        when (panel) {
            LivePanel.LIST, LivePanel.GROUPS, LivePanel.GUIDE -> ChannelList(s.url, minute, startGroups = panel == LivePanel.GROUPS,
                guideOf = if (panel == LivePanel.GUIDE) channel else null,
                onPick = { c, list -> panel = null; tune(c, list) },
                onArchive = { c, url, title -> panel = null; playArchive(url, "${c.name} · $title", c.headers) },
                onNote = { note = it }, onClose = { panel = null })
            LivePanel.OPTIONS, LivePanel.AUDIO, LivePanel.SUBS, LivePanel.SLEEP -> {
                val c = channel
                val I = com.lekathedon.visionlite.ui.Icons
                val items = buildList {
                    if (engine == Engine.EXO && (ts.on || player.isCurrentMediaItemSeekable)) add(LiveBtn(I.Rewind, "Back 10 s") { seekBy(-10_000) })
                    add(LiveBtn(if (paused) I.Play else I.Pause, if (paused) "Play" else "Pause") { pauseToggle() })
                    if (ts.ring) { add(LiveBtn(I.Forward, "Forward 10 s") { seekBy(10_000) }); add(LiveBtn(I.Next, "Back to live") { goLive() }) }
                    if (c != null) {
                        now?.let { pr -> Iptv.startOverUrl(c, pr)?.let { url -> add(LiveBtn(I.Restart, "Start over") { panel = null; playArchive(url, "${c.name} · ${pr.title}", c.headers) }) } }
                        add(LiveBtn(I.Guide, "TV guide") { panel = LivePanel.GUIDE })
                        val fav = Iptv.isFavourite(c)
                        add(LiveBtn(if (fav) I.Favorite else I.FavoriteBorder, if (fav) "Remove from favourites" else "Add to favourites", if (fav) Color(0xFFFFC107) else null) { perform(LiveKeys.Action.FAVOURITE, 0) })
                        add(if (recording != null) LiveBtn(I.Stop, "Stop recording", Color(0xFFE53935)) { perform(LiveKeys.Action.RECORD, 0) }
                            else LiveBtn(I.Record, "Record", Color(0xFFE53935)) { perform(LiveKeys.Action.RECORD, 0) })
                    }
                    add(LiveBtn(I.Audio, "Audio" + (currentTrack(player, C.TRACK_TYPE_AUDIO)?.let { ": $it" } ?: "")) { panel = LivePanel.AUDIO })
                    add(LiveBtn(I.Subtitles, "Subtitles: " + currentTrack(player, C.TRACK_TYPE_TEXT)) { panel = LivePanel.SUBS })
                    add(LiveBtn(I.Aspect, "Picture: ${listOf("Fit", "Zoom", "Stretch")[aspect]}") { perform(LiveKeys.Action.PICTURE, 0) })
                    add(LiveBtn(I.Sleep, sleepAt?.let { "Sleep in ${((it - System.currentTimeMillis()) / 60_000) + 1} min" } ?: "Sleep timer", if (sleepAt != null) Color(0xFF64B5F6) else null) { panel = LivePanel.SLEEP })
                    add(LiveBtn(I.History, "Last channel") { panel = null; perform(LiveKeys.Action.LAST_CHANNEL, 0) })
                    add(LiveBtn(I.Info, "Stats") { panel = LivePanel.STATS })
                    add(LiveBtn(I.Engine, "Engine: ${engine.label}") { panel = null; onEngine() })
                }
                ControlBar(items, active = panel == LivePanel.OPTIONS, Modifier.align(Alignment.BottomStart))
                when (panel) {
                    LivePanel.AUDIO -> TrackPanel("Audio", player, C.TRACK_TYPE_AUDIO, allowOff = false, bottom = true) { panel = LivePanel.OPTIONS }
                    LivePanel.SUBS -> TrackPanel("Subtitles / captions", player, C.TRACK_TYPE_TEXT, allowOff = true, bottom = true) { panel = LivePanel.OPTIONS }
                    LivePanel.SLEEP -> {
                        val opts = listOf(0, 15, 30, 60, 90, 120)
                        val labels = opts.map { if (it == 0) "Off" else "$it minutes" } + listOfNotNull(now?.let { "End of \"${it.title}\" (${t(it.stopMs)})" })
                        SidePanel("Sleep timer", labels, if (sleepAt == null) 0 else -1, bottom = true) { i ->
                            sleepAt = when {
                                i == 0 -> null
                                i < opts.size -> System.currentTimeMillis() + opts[i] * 60_000L
                                else -> now?.stopMs
                            }
                            note = sleepAt?.let { "Pauses at ${t(it)}" } ?: "Sleep timer off"
                            panel = LivePanel.OPTIONS
                        }
                    }
                    else -> {}
                }
            }
            LivePanel.STATS -> StatsPanel(remember(engine) { stats() }, Modifier.align(Alignment.TopStart))
            null -> {}
        }
    }
}

/** One control-bar button: icon, its name (shown under the bar while focused), a colour when it is "on". */
private class LiveBtn(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val tint: Color? = null, val act: () -> Unit)

/**
 * The control bar along the bottom (hold OK / Menu), drawn like TiviMate's: round icon buttons in one row, the
 * focused one a white disc, its name under the row. Left / Right to move, Back closes.
 */
@Composable
private fun ControlBar(items: List<LiveBtn>, active: Boolean, modifier: Modifier) {
    val first = remember { FocusRequester() }
    var focusedAt by remember { mutableIntStateOf(if (items.size > 1 && items[0].label.startsWith("Back 10")) 1 else 0) }
    LaunchedEffect(active) { if (active) { delay(30); runCatching { first.requestFocus() } } }
    Column(modifier.fillMaxWidth().padding(bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.lazy.LazyRow(Modifier.background(Color(0xB3101014), RoundedCornerShape(36.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(items) { i, b ->
                Focusable(b.act, (if (i == focusedAt.coerceAtMost(items.lastIndex)) Modifier.focusRequester(first) else Modifier), androidx.compose.foundation.shape.CircleShape,
                    onFocus = { focusedAt = i }) { f ->
                    Box(Modifier.size(52.dp).background(if (f) com.lekathedon.visionlite.ui.KitInk.bg else Color.Transparent), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.Icon(b.icon, b.label, Modifier.size(28.dp), tint = when { f && b.tint != null -> b.tint; f -> com.lekathedon.visionlite.ui.KitInk.ink; else -> b.tint ?: com.lekathedon.visionlite.ui.KitInk.text })
                    }
                }
            }
        }
        Text(items.getOrNull(focusedAt)?.label.orEmpty(), color = com.lekathedon.visionlite.ui.KitInk.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            modifier = Modifier.padding(top = 8.dp))
    }
}

/** Live rewind facts for the banner. */
private class Shift(val on: Boolean, val ring: Boolean, val keptMs: Long, val behindMs: Long, val seeking: Boolean)

/** Bottom banner: number, logo, name, what's on with its progress, what's next, and the line's picture. */
@Composable
private fun Banner(c: Channel?, title: String, nowNext: List<EpgProgram>, now: EpgProgram?, nowMs: Long, rec: Boolean, sleepAt: Long?, player: Player,
                   shift: Shift, lift: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val next = nowNext.firstOrNull { it != now && it.startMs >= (now?.stopMs ?: 0) }
    Box(modifier.fillMaxWidth().height(230.dp + lift).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF0000000)))))
    Row(modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 40.dp + lift), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.size(132.dp, 74.dp).background(Color(0x22FFFFFF), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Img(c?.logo, 120.dp, 66.dp, Modifier.size(120.dp, 66.dp), ContentScale.Fit)
        }
        Column(Modifier.padding(start = 20.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                c?.let { Text("${it.n}", color = Gold, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp)) }
                Text(c?.name ?: title, color = com.lekathedon.visionlite.ui.KitInk.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                val h = player.videoSize.height
                val tags = listOfNotNull(
                    c?.takeIf { Iptv.isFavourite(it) }?.let { "★" },
                    when { h >= 2000 -> "4K"; h >= 1000 -> "FHD"; h >= 700 -> "HD"; h > 0 -> "SD"; else -> null },
                    c?.catchup?.let { "CATCH-UP" }, if (rec) "● REC" else null, sleepAt?.let { "SLEEP ${t(it)}" })
                tags.forEach { tag ->
                    Text(tag, color = if (tag == "● REC") Color(0xFFFF6B6B) else com.lekathedon.visionlite.ui.KitInk.text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp).background(Color(0x33FFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(t(System.currentTimeMillis()), color = com.lekathedon.visionlite.ui.KitInk.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            c?.group?.takeIf { it.isNotBlank() }?.let { Text(it, color = com.lekathedon.visionlite.ui.KitInk.dim, fontSize = 13.sp, maxLines = 1) }
            if (now != null) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${t(now.startMs)}–${t(now.stopMs)}", color = Gold, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(110.dp))
                    Text(now.title, color = com.lekathedon.visionlite.ui.KitInk.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("${((now.stopMs - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)} min left", color = com.lekathedon.visionlite.ui.KitInk.dim, fontSize = 13.sp)
                }
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).background(Color(0x33FFFFFF), RoundedCornerShape(50))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(now.progress(maxOf(nowMs, System.currentTimeMillis()))).background(Gold, RoundedCornerShape(50)))
                }
                now.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = com.lekathedon.visionlite.ui.KitInk.dim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
            } else Text(if (c?.tvg == null) "No guide for this channel" else "Nothing in the guide right now", color = com.lekathedon.visionlite.ui.KitInk.dim, fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp))
            next?.let {
                Row(Modifier.padding(top = 6.dp)) {
                    Text("Next ${t(it.startMs)}", color = com.lekathedon.visionlite.ui.KitInk.dim, fontSize = 14.sp, modifier = Modifier.width(110.dp))
                    Text(it.title, color = com.lekathedon.visionlite.ui.KitInk.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // Live rewind: where you are between the oldest kept minute and live.
            if (shift.ring || shift.seeking) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (shift.behindMs <= 0) "●  LIVE" else "⏪  ${span(shift.behindMs)} behind live", color = if (shift.behindMs <= 0) Color(0xFFFF6B6B) else Gold,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(190.dp))
                    Box(Modifier.weight(1f).height(4.dp).background(Color(0x33FFFFFF), RoundedCornerShape(50))) {
                        val kept = shift.keptMs.coerceAtLeast(1)
                        Box(Modifier.fillMaxHeight().fillMaxWidth((1f - shift.behindMs.toFloat() / kept).coerceIn(0f, 1f)).background(Color(0xFFFF6B6B), RoundedCornerShape(50)))
                    }
                    Text("  ${span(shift.keptMs)} kept", color = com.lekathedon.visionlite.ui.KitInk.dim, fontSize = 12.sp)
                }
            } else if (shift.on && shift.keptMs > 0) Text("●  LIVE · rewind up to ${span(shift.keptMs)}", color = com.lekathedon.visionlite.ui.KitInk.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            fun k(b: LiveKeys.Button) = LiveKeys.get(b).label.lowercase()
            Text("OK ${k(LiveKeys.Button.OK)} · ◀ ${k(LiveKeys.Button.LEFT)} · ▶ ${k(LiveKeys.Button.RIGHT)} · hold OK ${k(LiveKeys.Button.LONG_OK)}",
                color = Color(0x99FFFFFF), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/**
 * Channel list over the picture (left side, TiviMate): the list Up / Down steps through, the playing channel focused,
 * each row with what's on. Left opens the groups; Right shows that channel's programmes next to the list (aired ones
 * play from catch-up, later ones record); OK plays, hold OK = favourite. Back steps back one level.
 */
@Composable
private fun ChannelList(current: String, minute: Long, startGroups: Boolean, guideOf: Channel?, onPick: (Channel, List<Channel>) -> Unit,
                        onArchive: (Channel, String, String) -> Unit, onNote: (String) -> Unit, onClose: () -> Unit) {
    var group by remember { mutableStateOf<String?>(null) }   // null = the list playing now
    var picking by remember { mutableStateOf(startGroups) }
    var progFor by remember { mutableStateOf(guideOf) }
    val version by Iptv.version.collectAsState()
    val grev by com.lekathedon.visionlite.iptv.Groups.rev.collectAsState()
    val channels by produceState(Iptv.zapList, group, version, grev) {
        val g = group
        if (g != null) value = withContext(Dispatchers.IO) { Iptv.channels(g.ifEmpty { null }) }
    }
    val groups by produceState(emptyList<Pair<String, Int>>(), picking, grev) { if (picking) value = withContext(Dispatchers.IO) { Iptv.groups() } }
    val list = rememberLazyListState()
    val fast = com.lekathedon.visionlite.ui.rememberFastList(list)
    val focus = remember { FocusRequester() }
    var focusAt by remember { mutableIntStateOf(-1) }
    val start = if (focusAt >= 0) focusAt.coerceAtMost((channels.size - 1).coerceAtLeast(0)) else channels.indexOfFirst { it.url == current }.coerceAtLeast(0)
    LaunchedEffect(channels, picking, progFor) {
        if (picking || channels.isEmpty() || progFor != null) return@LaunchedEffect
        if (list.firstVisibleItemIndex > start || start > list.firstVisibleItemIndex + 8) list.scrollToItem((start - 3).coerceAtLeast(0))
        delay(40); runCatching { focus.requestFocus() }
    }
    Row(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
        if (e.key != Key.Back || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when {
            progFor != null && guideOf == null -> progFor = null
            picking && !startGroups -> picking = false
            else -> onClose()
        }
        true
    }) {
        if (picking) {
            val gFocus = remember { FocusRequester() }
            LaunchedEffect(groups) { if (groups.isNotEmpty()) { delay(40); runCatching { gFocus.requestFocus() } } }
            val all = listOf("★" to Iptv.favourites().size, Iptv.RECENT to Iptv.recents().size, "" to groups.sumOf { it.second }) + groups
            val gFast = com.lekathedon.visionlite.ui.rememberFastList()
            LazyColumn(Modifier.width(300.dp).fillMaxHeight().background(Glass), state = gFast.state, contentPadding = PaddingValues(vertical = 32.dp, horizontal = 12.dp)) {
                itemsIndexed(all, key = { _, it -> it.first }) { i, (g, n) ->
                    val name = when (g) { "★" -> "★ Favourites"; Iptv.RECENT -> "⟲ Recent"; "" -> "All channels"; else -> g }
                    // Held Left: back to the top (Favourites), as TiviMate.
                    Focusable({ group = g; picking = false; focusAt = 0 }, (if (i == 0) Modifier.focusRequester(gFocus) else Modifier).then(gFast.mod(i))
                        .onPreviewKeyEvent { gFast.onKey(it, i, all.size, leftTop = true) }.padding(bottom = 4.dp).fillMaxWidth()) { f ->
                        Row(Modifier.fillMaxWidth().background(if (f) com.lekathedon.visionlite.ui.KitInk.bg else Color.Transparent).padding(10.dp)) {
                            Text(name, color = if (f) com.lekathedon.visionlite.ui.KitInk.ink else com.lekathedon.visionlite.ui.KitInk.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("$n", color = if (f) com.lekathedon.visionlite.ui.KitInk.inkDim else com.lekathedon.visionlite.ui.KitInk.dim, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        Column(Modifier.width(520.dp).fillMaxHeight().background(Glass).padding(top = 28.dp, start = 16.dp, end = 16.dp)) {
            Text(when (group) { null -> "Channels"; "★" -> "★ Favourites"; Iptv.RECENT -> "⟲ Recent"; "" -> "All channels"; else -> group!! },
                color = com.lekathedon.visionlite.ui.KitInk.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("${channels.size} channels · Left: groups · Right: programmes · hold OK: favourite", color = com.lekathedon.visionlite.ui.KitInk.dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
            if (channels.isEmpty()) Text(if (group == "★") "No favourites yet: hold OK on a channel." else "No channels here.", color = com.lekathedon.visionlite.ui.KitInk.dim, fontSize = 14.sp)
            LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                itemsIndexed(channels, key = { _, c -> c.key }) { i, c ->
                    var favTick by remember { mutableIntStateOf(0) }
                    val nowP by produceState<EpgProgram?>(null, c, minute) {
                        value = withContext(Dispatchers.IO) { runCatching { Iptv.nowNext(c).firstOrNull { it.isLive(System.currentTimeMillis()) } }.getOrNull() }
                    }
                    Focusable({ onPick(c, channels) }, (if (i == start) Modifier.focusRequester(focus) else Modifier).then(fast.mod(i)).fillMaxWidth().onPreviewKeyEvent { e ->
                        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter
                        when {
                            fast.onKey(e, i, channels.size) -> true
                            e.type != KeyEventType.KeyDown -> false
                            e.key == Key.DirectionLeft && e.nativeKeyEvent.repeatCount > 0 -> { picking = true; true }
                            e.key == Key.DirectionLeft -> { picking = true; true }
                            e.key == Key.DirectionRight -> { focusAt = i; progFor = c; true }
                            ok && e.nativeKeyEvent.repeatCount == 1 -> { HoldGuard.held(); Iptv.toggleFavourite(c); favTick++; true }
                            ok && e.nativeKeyEvent.repeatCount > 1 -> true
                            else -> false
                        }
                    }, onFocus = { focusAt = i }) { f ->
                        val playing = c.url == current
                        val shown = progFor?.key == c.key
                        Row(Modifier.fillMaxWidth().background(if (f) com.lekathedon.visionlite.ui.KitInk.bg else if (shown) Color(0x55FFFFFF) else if (playing) com.lekathedon.visionlite.ui.KitInk.accent.copy(alpha = 0.2f) else Color(0x0DFFFFFF))
                            .padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${c.n}", color = if (f) com.lekathedon.visionlite.ui.KitInk.inkDim else com.lekathedon.visionlite.ui.KitInk.dim, fontSize = 13.sp, modifier = Modifier.width(46.dp))
                            Box(Modifier.size(54.dp, 30.dp), contentAlignment = Alignment.Center) { Img(c.logo, 54.dp, 30.dp, Modifier.size(54.dp, 30.dp), ContentScale.Fit) }
                            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                val fav = remember(favTick, c) { Iptv.isFavourite(c) }
                                Text((if (playing) "▶ " else "") + (if (fav) "★ " else "") + c.name, color = if (f) com.lekathedon.visionlite.ui.KitInk.ink else com.lekathedon.visionlite.ui.KitInk.text, fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                nowP?.let { pr ->
                                    Text(pr.title, color = if (f) com.lekathedon.visionlite.ui.KitInk.inkDim else com.lekathedon.visionlite.ui.KitInk.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Box(Modifier.padding(top = 3.dp).fillMaxWidth().height(3.dp).background(Color(0x33808080))) {
                                        Box(Modifier.fillMaxHeight().fillMaxWidth(pr.progress(System.currentTimeMillis())).background(if (f) com.lekathedon.visionlite.ui.KitInk.ink else Gold))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        progFor?.let { c ->
            Programmes(c, minute, onLive = { if (c.url == current) onClose() else onPick(c, channels) },
                onArchive = { url, title -> onArchive(c, url, title) }, onNote = onNote,
                onBack = { if (guideOf != null) onClose() else progFor = null })
        }
    }
}

/**
 * One channel's programmes, next to the channel list: aired shows play from catch-up, the running one plays the
 * channel, later ones set or clear a recording. Left (or Back) returns to the channels.
 */
@Composable
private fun Programmes(c: Channel, minute: Long, onLive: () -> Unit, onArchive: (String, String) -> Unit, onNote: (String) -> Unit, onBack: () -> Unit) {
    val all by produceState(emptyList<EpgProgram>(), c, minute / 10) { value = withContext(Dispatchers.IO) { runCatching { Iptv.schedule(c) }.getOrDefault(emptyList()) } }
    val nowMs = System.currentTimeMillis()
    val nowIndex = all.indexOfFirst { it.stopMs > nowMs }.coerceAtLeast(0)
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(c, all.size) { if (all.isNotEmpty()) { list.scrollToItem((nowIndex - 2).coerceAtLeast(0)); delay(40); runCatching { focus.requestFocus() } } }
    val recs by Recorder.list.collectAsState()
    Column(Modifier.width(500.dp).fillMaxHeight().background(com.lekathedon.visionlite.ui.KitInk.panel.copy(alpha = 0.96f)).padding(top = 28.dp, start = 18.dp, end = 18.dp)
        .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { onBack(); true } else false }) {
        Text(c.name, color = com.lekathedon.visionlite.ui.KitInk.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(c.catchup?.let { "Catch-up ${it.days} days: OK on an aired show plays it" } ?: "OK on a later show records it",
            color = com.lekathedon.visionlite.ui.KitInk.dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
        if (all.isEmpty()) {
            Focusable(onBack, Modifier.focusRequester(focus)) { f -> Text("No guide for this channel", color = if (f) com.lekathedon.visionlite.ui.KitInk.ink else com.lekathedon.visionlite.ui.KitInk.dim, fontSize = 14.sp,
                modifier = Modifier.background(if (f) com.lekathedon.visionlite.ui.KitInk.bg else Color.Transparent).padding(8.dp)) }
            LaunchedEffect(Unit) { delay(40); runCatching { focus.requestFocus() } }
            return@Column
        }
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
                    Column(Modifier.fillMaxWidth().background(if (f) com.lekathedon.visionlite.ui.KitInk.bg else if (live) com.lekathedon.visionlite.ui.KitInk.accent.copy(alpha = 0.13f) else Color.Transparent).padding(8.dp)) {
                        val tag = when { live -> "NOW"; archive != null -> "▶ CATCH-UP"; past -> "AIRED"; set -> "● REC SET"; else -> "" }
                        Text("${t(pr.startMs)}–${t(pr.stopMs)}  $tag", color = if (f) com.lekathedon.visionlite.ui.KitInk.inkDim else if (live || archive != null) Gold else com.lekathedon.visionlite.ui.KitInk.dim,
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(pr.title, color = if (f) com.lekathedon.visionlite.ui.KitInk.ink else if (past && archive == null) com.lekathedon.visionlite.ui.KitInk.dim else com.lekathedon.visionlite.ui.KitInk.text, fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (f || live) pr.description?.let { Text(it, color = if (f) com.lekathedon.visionlite.ui.KitInk.inkDim else com.lekathedon.visionlite.ui.KitInk.dim, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}
