package com.lekathedon.visionlite.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import com.lekathedon.visionlite.ui.Icons
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.lekathedon.visionlite.ui.Focusable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private enum class Panel { AUDIO, SUBS, SPEED, STATS, SYNC, SLEEP }

private val Aspects = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
    AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch",
)
private val Speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * The player's own controls (Vision's button set, drawn light): title and end time on top; seek bar and labelled
 * buttons below; side panels for audio, subtitles, speed and stats. Hidden: OK = pause/play, Left/Right = seek,
 * Up/Down = show. Shown: Back hides. Updates only while shown (once a second otherwise, for the auto-hide).
 */
@OptIn(UnstableApi::class)
@Composable
internal fun Controls(
    player: Player, title: String, canNext: Boolean,
    onNext: () -> Unit, onSources: () -> Unit, onAspect: (Int) -> Unit,
    /** The Stats panel's source (the engine in use and its counters). */
    stats: () -> StatsSource,
    /** "ExoPlayer" / "MPV": the Engine button switches to the other one at the same position. */
    engine: Engine, onEngine: () -> Unit,
    segments: List<Segment> = emptyList(), skipMode: String = "button", nextPopup: Boolean = true, autoNext: Boolean = false,
    /** Live TV: Up / Down (and CH+ / CH−) switch channel while the buttons are hidden. */
    onZap: ((Int) -> Unit)? = null,
    /** Sync panel changed a delay: the engine applies it (MPV properties / an ExoPlayer flush). */
    onDelays: () -> Unit = {},
    /** Seek thumbnails for this link (null = none: live TV, small boxes, switched off). */
    thumbs: SeekThumbs? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(true) }
    var lastInput by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var playing by remember { mutableStateOf(player.playWhenReady) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var aspect by remember { mutableIntStateOf(0) }
    var speed by remember { mutableStateOf(1f) }
    var seekTo by remember { mutableStateOf<Long?>(null) }
    var seekRepeats by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf("") }
    val vs10 = remember { Vs10Engine.installed(context) }
    val root = remember { FocusRequester() }
    val bar = remember { FocusRequester() }
    val playButton = remember { FocusRequester() }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { playing = playWhenReady }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(player) {
        while (true) {
            pos = player.currentPosition; dur = player.duration.coerceAtLeast(0L); buffered = player.bufferedPosition
            if (visible && playing && panel == null && seekTo == null && SystemClock.uptimeMillis() - lastInput > 4_000) visible = false
            delay(if (visible) 250 else 1_000)
        }
    }
    // A seek runs once the presses stop (so ten presses are one jump, not ten).
    LaunchedEffect(seekTo) {
        val t = seekTo ?: return@LaunchedEffect
        delay(650); player.seekTo(t); pos = t; seekTo = null; seekRepeats = 0
    }
    LaunchedEffect(note) { if (note.isNotEmpty()) { delay(3_500); note = "" } }
    // Sleep timer: pauses at the time picked, or just before the end of this episode (no next episode then).
    var sleepAt by remember { mutableStateOf<Long?>(null) }
    var sleepEnd by remember { mutableStateOf(false) }
    LaunchedEffect(sleepAt, sleepEnd) {
        if (sleepAt == null && !sleepEnd) return@LaunchedEffect
        while (true) {
            delay(1_000)
            val d = player.duration
            val due = sleepAt?.let { System.currentTimeMillis() >= it } ?: (d > 0 && d - player.currentPosition < 2_000)
            if (due) { player.pause(); sleepAt = null; sleepEnd = false; note = "Sleep timer: paused"; break }
        }
    }
    // Seek thumbnail: asked only while a seek is pending, for the spot it would land on.
    var thumb by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(seekTo != null) { if (seekTo == null) thumb = null }
    LaunchedEffect(seekTo?.div(10_000)) {
        val t = seekTo ?: return@LaunchedEffect
        if (thumbs == null) return@LaunchedEffect
        delay(120)
        thumbs.at(t)?.let { thumb = it }
    }
    LaunchedEffect(visible) { runCatching { if (visible) playButton.requestFocus() else root.requestFocus() } }

    // Skip intro / recap / outro / credits (Vision's IntroDB + AniSkip) and the next-episode popup.
    val dismissed = remember { mutableStateOf(setOf<String>()) }
    var nextDismissed by remember { mutableStateOf(false) }
    val at = seekTo ?: pos
    val outro = segments.firstOrNull { it.kind == "outro" }
    val nextUp = canNext && nextPopup && !nextDismissed && dur > 300_000 &&
        (outro?.let { at >= it.startMs } ?: (dur - at in 1..40_000))
    val seg = if (skipMode == "off") null else segments.firstOrNull {
        at >= it.startMs && at < it.endMs - 1_000 && it.kind !in dismissed.value && !(nextUp && it.kind == "outro")
    }
    LaunchedEffect(seg) {
        if (seg != null && skipMode == "auto") { player.seekTo(seg.endMs); dismissed.value += seg.kind; note = seg.label.replace("Skip", "Skipped") }
    }
    var countdown by remember { mutableIntStateOf(10) }
    LaunchedEffect(nextUp, autoNext) {
        if (!nextUp || !autoNext) return@LaunchedEffect
        countdown = 10
        while (countdown > 0) { delay(1_000); countdown-- }
        onNext()
    }

    fun toggle() { if (player.playWhenReady) player.pause() else player.play() }
    fun step(forward: Boolean) {
        seekRepeats++
        val stepMs = when { seekRepeats > 15 -> 60_000L; seekRepeats > 5 -> 30_000L; else -> 10_000L }
        val from = seekTo ?: player.currentPosition
        seekTo = (from + if (forward) stepMs else -stepMs).coerceIn(0L, dur.takeIf { it > 0 } ?: Long.MAX_VALUE)
    }

    Box(
        Modifier.fillMaxSize().focusRequester(root).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            lastInput = SystemClock.uptimeMillis()
            when (e.key) {
                Key.MediaPlayPause -> { toggle(); return@onPreviewKeyEvent true }
                Key.MediaPlay -> { player.play(); return@onPreviewKeyEvent true }
                Key.MediaPause -> { player.pause(); return@onPreviewKeyEvent true }
                Key.MediaFastForward -> { visible = true; step(true); return@onPreviewKeyEvent true }
                Key.MediaRewind -> { visible = true; step(false); return@onPreviewKeyEvent true }
                Key.ChannelUp -> if (onZap != null) { onZap(-1); return@onPreviewKeyEvent true }
                Key.ChannelDown -> if (onZap != null) { onZap(1); return@onPreviewKeyEvent true }
            }
            if (e.key == Key.Back) {
                when {
                    panel != null -> panel = null
                    visible -> visible = false
                    nextUp -> nextDismissed = true
                    seg != null && skipMode == "button" -> dismissed.value += seg.kind
                    else -> return@onPreviewKeyEvent false
                }
                return@onPreviewKeyEvent true
            }
            if (visible) return@onPreviewKeyEvent false
            val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
            if (ok && nextUp) { onNext(); return@onPreviewKeyEvent true }
            if (ok && seg != null && skipMode == "button") { player.seekTo(seg.endMs); pos = seg.endMs; dismissed.value += seg.kind; return@onPreviewKeyEvent true }
            when (e.key) {
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { toggle(); visible = true }
                Key.DirectionLeft, Key.DirectionRight -> { visible = true; step(e.key == Key.DirectionRight); scope.launch { delay(50); runCatching { bar.requestFocus() } } }
                Key.DirectionUp, Key.DirectionDown -> { if (onZap != null) onZap(if (e.key == Key.DirectionUp) -1 else 1) else visible = true }
                else -> return@onPreviewKeyEvent false
            }
            true
        }
    ) {
        if (note.isNotEmpty()) Text(note, color = Color.White, fontSize = 15.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp)
            .background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 8.dp))
        // Bottom right, over the video: OK takes it, Back waves it off.
        if (nextUp) Column(Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = if (visible) 150.dp else 48.dp)
            .background(Color(0xF0101114), RoundedCornerShape(12.dp)).padding(horizontal = 22.dp, vertical = 16.dp)) {
            Text("Next episode", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(if (autoNext) "Starts in $countdown s · OK now · Back to stay" else "OK to play · Back to stay", color = Color(0xFFBDBDBD), fontSize = 14.sp)
        } else if (seg != null && skipMode == "button") Text("${seg.label}   OK", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = if (visible) 150.dp else 48.dp)
                .background(Color.White, RoundedCornerShape(22.dp)).padding(horizontal = 22.dp, vertical = 11.dp))
        if (!visible) return@Box

        // Top: title and when it ends.
        Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))).padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 40.dp)) {
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (dur > 0) Text("Ends at ${endsAt(dur - pos, speed)}", color = Color(0xFFBDBDBD), fontSize = 14.sp)
        }

        // Bottom: seek bar and buttons.
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)))).padding(start = 48.dp, end = 48.dp, top = 60.dp, bottom = 28.dp)) {
            val shown = seekTo ?: pos
            var barFocused by remember { mutableStateOf(false) }
            thumb?.takeIf { seekTo != null && dur > 0 }?.let { b ->
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    val x = ((maxWidth - 240.dp) * (shown.toFloat() / dur).coerceIn(0f, 1f))
                    Column(Modifier.padding(start = x).width(240.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        androidx.compose.foundation.Image(b.asImageBitmap(), null, Modifier.width(240.dp).height(135.dp).clip(RoundedCornerShape(8.dp)))
                        Text(clock(shown), color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(clock(shown), color = Color.White, fontSize = 14.sp)
                Box(Modifier.weight(1f).padding(horizontal = 14.dp).height(if (barFocused) 8.dp else 4.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x40FFFFFF))
                    .focusRequester(bar).onFocusChanged { barFocused = it.isFocused }.focusable()
                    .onKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (e.key) {
                            Key.DirectionLeft -> { step(false); true }
                            Key.DirectionRight -> { step(true); true }
                            Key.DirectionCenter, Key.Enter -> { toggle(); true }
                            else -> false
                        }
                    }) {
                    if (dur > 0) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth((buffered.toFloat() / dur).coerceIn(0f, 1f)).background(Color(0x66FFFFFF)))
                        Box(Modifier.fillMaxHeight().fillMaxWidth((shown.toFloat() / dur).coerceIn(0f, 1f)).background(if (barFocused) Color(0xFFD9DC8E) else Color.White))
                    }
                }
                Text(if (dur > 0) "-" + clock(dur - shown) else "", color = Color.White, fontSize = 14.sp)
            }
            Row(Modifier.padding(top = 16.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconPill(if (playing) Icons.Pause else Icons.Play, if (playing) "Pause" else "Play", Modifier.focusRequester(playButton)) { toggle() }
                IconPill(Icons.Restart, "Restart") { player.seekTo(0); player.play() }
                if (canNext) IconPill(Icons.Next, "Next episode") { onNext() }
                IconPill(Icons.Audio, "Audio") { panel = Panel.AUDIO }
                IconPill(Icons.Subtitles, "Subtitles") { panel = Panel.SUBS }
                IconPill(Icons.Speed, "Speed ${fmtSpeed(speed)}") { panel = Panel.SPEED }
                if (onZap == null) IconPill(Icons.Sync, "Sync · audio ${Sync.label(Sync.audioMs)} · subs ${Sync.label(Sync.subMs)}") { panel = Panel.SYNC }
                IconPill(Icons.Sleep, sleepAt?.let { "Sleep in ${((it - System.currentTimeMillis()) / 60_000) + 1} min" } ?: if (sleepEnd) "Sleep at the end" else "Sleep timer") { panel = Panel.SLEEP }
                IconPill(Icons.Aspect, Aspects[aspect].second) { aspect = (aspect + 1) % Aspects.size; onAspect(Aspects[aspect].first) }
                if (vs10) IconPill(Icons.Hdr, "VS10") {
                    note = "VS10…"
                    scope.launch { note = withContext(Dispatchers.IO) { Vs10Engine.cycle() }?.let(::vs10Text) ?: "VS10 didn't answer (root?)" }
                }
                IconPill(Icons.Engine, "Engine: ${engine.label}") { onEngine() }
                IconPill(Icons.Info, "Stats · HDR · FEL") { panel = if (panel == Panel.STATS) null else Panel.STATS }
                IconPill(Icons.Sources, "Sources") { onSources() }
            }
        }

        when (panel) {
            Panel.AUDIO -> TrackPanel("Audio", player, C.TRACK_TYPE_AUDIO, allowOff = false) { panel = null }
            Panel.SUBS -> TrackPanel("Subtitles", player, C.TRACK_TYPE_TEXT, allowOff = true) { panel = null }
            Panel.SPEED -> SidePanel("Speed", Speeds.map { fmtSpeed(it) }, Speeds.indexOf(speed)) { i -> speed = Speeds[i]; player.setPlaybackSpeed(speed); panel = null }
            Panel.STATS -> StatsPanel(remember(engine) { stats() }, Modifier.align(Alignment.TopEnd))
            Panel.SYNC -> SyncPanel(onDelays)
            Panel.SLEEP -> {
                val opts = listOf(0, 15, 30, 45, 60, 90)
                val labels = opts.map { if (it == 0) "Off" else "$it minutes" } + (if (dur > 0 && onZap == null) listOf("End of this episode / film") else emptyList())
                val sel = when { sleepEnd -> opts.size; sleepAt == null -> 0; else -> -1 }
                SidePanel("Sleep timer", labels, sel) { i ->
                    when {
                        i == 0 -> { sleepAt = null; sleepEnd = false; note = "Sleep timer off" }
                        i < opts.size -> { sleepEnd = false; sleepAt = System.currentTimeMillis() + opts[i] * 60_000L; note = "Pauses in ${opts[i]} minutes" }
                        else -> { sleepAt = null; sleepEnd = true; note = "Pauses at the end" }
                    }
                    panel = null
                }
            }
            null -> {}
        }
    }
}

private fun vs10Text(s: Vs10Engine.Step): String {
    if (s.source == null) return "VS10: nothing to convert (${s.unconverted ?: "this video"} plays as is)"
    val src = when (s.source) { Vs10Engine.Source.SDR -> "SDR"; Vs10Engine.Source.HDR10 -> "HDR10"; Vs10Engine.Source.MPV -> "mpv" }
    val to = when (s.mode) { Vs10Engine.Mode.OFF -> "off"; Vs10Engine.Mode.HDR10 -> "HDR10"; Vs10Engine.Mode.DV -> "Dolby Vision"; null -> "?" }
    return "VS10: $src → $to"
}

/** Audio and subtitle delay: Left / Right move a line by 50 ms (held: faster), OK on a line sets it back to 0. */
@Composable
private fun SyncPanel(onDelays: () -> Unit) {
    var a by remember { mutableLongStateOf(Sync.audioMs) }
    var sub by remember { mutableLongStateOf(Sync.subMs) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(30); runCatching { first.requestFocus() } }
    // Applied once the presses stop (the ExoPlayer flush is a short re-buffer).
    LaunchedEffect(a, sub) { if (a != Sync.audioMs || sub != Sync.subMs) { delay(500); Sync.set(a, sub); onDelays() } }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(380.dp).background(Color(0xF0101114)).padding(24.dp)) {
            Text("Sync", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Remembered for this title. Left / Right: 50 ms; OK: back to 0", color = Color(0xFFBDBDBD), fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
            listOf(true, false).forEach { audio ->
                var reps by remember { mutableIntStateOf(0) }
                Box(Modifier.padding(top = 8.dp).fillMaxWidth().then(if (audio) Modifier.focusRequester(first) else Modifier)) {
                    var f by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().onFocusChanged { f = it.isFocused }.focusable().onKeyEvent { e ->
                        if (e.type == KeyEventType.KeyUp) { reps = 0; return@onKeyEvent false }
                        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                        val d = Sync.STEP * if (++reps > 10) 4 else 1
                        when (e.key) {
                            Key.DirectionLeft -> { if (audio) a = (a - d).coerceAtLeast(-Sync.LIMIT) else sub = (sub - d).coerceAtLeast(-Sync.LIMIT); true }
                            Key.DirectionRight -> { if (audio) a = (a + d).coerceAtMost(Sync.LIMIT) else sub = (sub + d).coerceAtMost(Sync.LIMIT); true }
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (audio) a = 0 else sub = 0; true }
                            else -> false
                        }
                    }.background(if (f) Color.White else Color(0x14FFFFFF)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (audio) "Audio delay" else "Subtitle delay", color = if (f) Color.Black else Color.White, fontSize = 16.sp)
                            Text(if (audio) "+ = sound later" else "+ = subtitles later", color = if (f) Color.DarkGray else Color(0xFFBDBDBD), fontSize = 12.sp)
                        }
                        Text("‹  " + Sync.label(if (audio) a else sub) + "  ›", color = if (f) Color.Black else Color(0xFFD9DC8E), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** A round icon button; its name shows under it while focused (Vision's control deck). */
@Composable
private fun IconPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
        Focusable(onClick, modifier.onFocusChanged { f = it.isFocused }, androidx.compose.foundation.shape.CircleShape) { focused ->
            Box(Modifier.size(52.dp).background(if (focused) Color.White else Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(icon, label, Modifier.size(26.dp), tint = if (focused) Color.Black else Color.White)
            }
        }
        Text(if (f) label else "", color = Color.White, fontSize = 12.sp, maxLines = 1, softWrap = false,
            modifier = Modifier.padding(top = 6.dp).height(16.dp).wrapContentWidth(unbounded = true))
    }
}

@Composable
private fun Pill(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Focusable(onClick, modifier, RoundedCornerShape(20.dp)) { f ->
        Text(text, color = if (f) Color.Black else Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.background(if (f) Color.White else Color(0x33FFFFFF)).padding(horizontal = 18.dp, vertical = 9.dp))
    }
}

@Composable
private fun SidePanel(title: String, items: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(30); runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(380.dp).background(Color(0xF0101114)).padding(24.dp)) {
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
            LazyColumn {
                itemsIndexed(items) { i, label ->
                    Focusable({ onPick(i) }, (if (i == selected.coerceAtLeast(0)) Modifier.focusRequester(first) else Modifier).padding(top = 6.dp).fillMaxWidth()) { f ->
                        Row(Modifier.fillMaxWidth().background(if (f) Color.White else Color(0x14FFFFFF)).padding(12.dp)) {
                            Text(if (i == selected) "● " else "   ", color = if (f) Color.Black else Color(0xFFD9DC8E), fontSize = 15.sp)
                            Text(label, color = if (f) Color.Black else Color.White, fontSize = 15.sp)
                        }
                    }
                }
            }
        }
    }
}

/** The file's tracks of [type]; picking one pins it, "Off" turns subtitles off. */
@Composable
private fun TrackPanel(title: String, player: Player, type: Int, allowOff: Boolean, close: () -> Unit) {
    val groups = player.currentTracks.groups.filter { it.type == type }
    val options = groups.flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { i -> g to i } }
    val off = allowOff && player.trackSelectionParameters.disabledTrackTypes.contains(type)
    val selected = if (off) 0 else options.indexOfFirst { (g, i) -> g.isTrackSelected(i) }.let { if (allowOff && it >= 0) it + 1 else it }
    val labels = (if (allowOff) listOf("Off") else emptyList()) + options.map { (g, i) -> trackLabel(g.getTrackFormat(i), type) }
    if (labels.isEmpty()) { SidePanel(title, listOf("None in this file"), -1) { close() }; return }
    SidePanel(title, labels, selected) { pick ->
        val b = player.trackSelectionParameters.buildUpon()
        if (allowOff && pick == 0) b.setTrackTypeDisabled(type, true)
        else {
            val (g, i) = options[if (allowOff) pick - 1 else pick]
            b.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
        }
        player.trackSelectionParameters = b.build()
        close()
    }
}

private fun trackLabel(f: Format, type: Int): String {
    val lang = f.language?.takeIf { it != "und" }?.let { Locale.forLanguageTag(it).displayLanguage.ifBlank { it } } ?: "Unknown"
    val extra = buildList {
        f.label?.takeIf { it.isNotBlank() && !it.equals(lang, true) }?.let { add(it) }
        if (type == C.TRACK_TYPE_AUDIO) {
            codecName(f.sampleMimeType)?.let { add(it) }
            when (f.channelCount) { 1 -> add("mono"); 2 -> add("stereo"); 6 -> add("5.1"); 8 -> add("7.1"); Format.NO_VALUE -> {} else -> add("${f.channelCount} ch") }
        } else {
            if (isForcedSub(f)) add("forced · foreign lines only") else if (isSdhSub(f) && f.label?.contains("sdh", true) != true) add("SDH")
            codecName(f.sampleMimeType)?.let { add(it) }
        }
    }
    return (listOf(lang) + extra).joinToString(" · ")
}

private fun codecName(mime: String?): String? = when (mime) {
    "audio/eac3-joc" -> "Atmos (DD+)"
    "audio/eac3" -> "DD+"
    "audio/ac3" -> "Dolby Digital"
    "audio/true-hd" -> "TrueHD"
    "audio/vnd.dts", "audio/vnd.dts.hd", "audio/vnd.dts.hd;profile=lbr" -> if (mime == "audio/vnd.dts") "DTS" else "DTS-HD"
    "audio/mp4a-latm" -> "AAC"
    "audio/opus" -> "Opus"
    "audio/flac" -> "FLAC"
    "audio/raw" -> "PCM"
    "application/x-subrip" -> "SRT"
    "text/x-ssa" -> "ASS"
    "application/pgs" -> "PGS"
    "text/vtt" -> "VTT"
    "video/hevc" -> "HEVC"
    "video/avc" -> "H.264"
    "video/av01" -> "AV1"
    "video/dolby-vision" -> "Dolby Vision"
    "video/x-vnd.on2.vp9" -> "VP9"
    else -> null
}

private fun bitDepth(f: androidx.media3.common.Format): Int? = f.colorInfo?.lumaBitdepth?.takeIf { it > 0 } ?: f.codecs?.lowercase()?.let { c ->
    when { c.startsWith("dvh") || c.startsWith("hvc1.2.") || c.startsWith("hev1.2.") -> 10; c.startsWith("hvc1.1.") || c.startsWith("hev1.1.") -> 8; else -> null }
}

private fun fmtSpeed(s: Float) = if (s == 1f) "1×" else "${s.toString().trimEnd('0').trimEnd('.')}×"

private fun clock(ms: Long): String {
    val t = (ms.coerceAtLeast(0) / 1000)
    val h = t / 3600; val m = t % 3600 / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun endsAt(leftMs: Long, speed: Float): String {
    val at = System.currentTimeMillis() + (leftMs / speed).toLong()
    return java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(at))
}
