package com.lekathedon.visionlite.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import com.lekathedon.visionlite.ui.Text
import com.lekathedon.visionlite.ui.Icons
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.withStyle
import androidx.compose.animation.animateContentSize
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

private enum class Panel { AUDIO, SUBS, SPEED, STATS, SYNC, SLEEP, MORE, BUTTONS, SUBSTYLE }
/** Player button sizes (More › Button size): small = the original, medium 1.5x, big 2x. */
private val ButtonSizes = listOf("s" to "Small", "m" to "Medium", "l" to "Big")
/** The player buttons' scale from More › Button size. */
@Composable
internal fun buttonScale(): Float = when (com.lekathedon.visionlite.App.instance.prefs.playerButtons.flow.collectAsState().value) { "m" -> 1.5f; "l" -> 2f; else -> 1f }

private val Aspects = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
    AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch",
)
private val Speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * The player's own controls, DebridStream's layout: title, episode and badges top left, clock and end time top right;
 * one timeline line and five round buttons centred at the bottom; one right-hand panel for audio, subtitles and More
 * (the choice in use ticked). Hidden: OK = pause/play, Left/Right = seek,
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
    /** Subtitle style panel changed a setting: the engine restyles its subtitles now. */
    onSubStyle: () -> Unit = {},
    /** Nuvio Arc's seek thumbnails run for this link (ExoPlayer, not live TV, switched on). */
    thumbs: Boolean = false,
    /** The title's styled name (logo picture), shown instead of the plain name as in Vision; null = the name. */
    logo: String? = null,
    /** The link's release name (file name, add-on name and details): source type, Atmos, DTS-HD MA for the badges. */
    release: String = "",
    /** Just back from the Sources panel: focus starts on the Sources button. */
    focusSources: Boolean = false,
    /** The film's or episode's plot, shown while paused (Settings › Plot when paused); null = none. */
    plot: String? = null,
) {
    val context = LocalContext.current
    val prefs = com.lekathedon.visionlite.App.instance.prefs
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(true) }
    var lastInput by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var playing by remember { mutableStateOf(player.playWhenReady) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var syncSubsFirst by remember { mutableStateOf(false) }
    var aspect by remember { mutableIntStateOf(0) }
    var speed by remember { mutableStateOf(1f) }
    var seekTo by remember { mutableStateOf<Long?>(null) }
    var seekRepeats by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf("") }
    val vs10 = remember { Vs10Engine.installed(context) }
    val root = remember { FocusRequester() }
    val bar = remember { FocusRequester() }
    val playButton = remember { FocusRequester() }
    // The dock button that opened the panel: focus goes back to it when the panel closes (pick or Back), else focus was lost.
    val dockFocus = remember { Panel.values().associateWith { FocusRequester() } }
    var opener by remember { mutableStateOf<Panel?>(null) }

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
        // Lands on the keyframe the thumbnail showed (Arc's landingFor), so the picture and the video match.
        delay(650)
        val land = if (thumbs) com.nuvio.tv.core.player.thumbnail.SeekThumbnails.landingFor(t) ?: t else t
        player.seekTo(land); pos = land; seekTo = null; seekRepeats = 0
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
    // Arc's engine makes the pictures for the spot first (as Arc's notePriority on every seek step).
    LaunchedEffect(seekTo) { if (thumbs) seekTo?.let { com.nuvio.tv.core.player.thumbnail.SeekThumbnails.notePriority(it) } }
    val sourcesButton = remember { FocusRequester() }
    var startOnSources by remember { mutableStateOf(focusSources) }
    LaunchedEffect(visible) {
        if (visible && startOnSources) { startOnSources = false; delay(30); if (runCatching { sourcesButton.requestFocus() }.isSuccess) return@LaunchedEffect }
        runCatching { if (visible) playButton.requestFocus() else root.requestFocus() }
    }
    LaunchedEffect(panel) {
        if (panel != null) return@LaunchedEffect
        val o = opener ?: return@LaunchedEffect
        opener = null
        delay(30)
        runCatching { if (visible) dockFocus.getValue(o).requestFocus() else root.requestFocus() }
    }

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
        if (note.isNotEmpty()) com.lekathedon.visionlite.ui.Inside(true) { Text(note, color = com.lekathedon.visionlite.ui.Palette.Text, fontSize = 15.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp)
            .background(K.panel.copy(alpha = 0.96f), RoundedCornerShape(K.cardR)).border(1.dp, K.line, RoundedCornerShape(K.cardR)).padding(horizontal = 16.dp, vertical = 8.dp)) }
        // Bottom right, over the video: OK takes it, Back waves it off.
        if (nextUp) com.lekathedon.visionlite.ui.Inside(true) { Column(Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = if (visible) 150.dp else 48.dp)
            .background(K.panel.copy(alpha = 0.96f), RoundedCornerShape(K.cardR)).border(1.dp, K.line, RoundedCornerShape(K.cardR)).padding(horizontal = 22.dp, vertical = 16.dp)) {
            com.lekathedon.visionlite.ui.Eyebrow("Up next")
            com.lekathedon.visionlite.ui.DisplayText(if (K.style == com.lekathedon.visionlite.ui.Style.MARQUEE) "NEXT EPISODE" else "Next episode", 18.sp, maxLines = 1)
            Text(if (autoNext) "Starts in $countdown s · OK now · Back to stay" else "OK to play · Back to stay", color = com.lekathedon.visionlite.ui.Palette.TextDim, fontSize = 14.sp)
        } } else if (seg != null && skipMode == "button") com.lekathedon.visionlite.ui.Inside(true) { Text("${seg.label}   OK", color = K.onAccent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = if (visible) 150.dp else 48.dp)
                .background(K.accent, K.buttonShape).padding(horizontal = 22.dp, vertical = 11.dp)) }
        // Top right, with the controls or paused (not live TV): the time and when this ends, small.
        if ((visible || !playing) && onZap == null && dur > 0) {
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(5_000) } }
            val fmt = remember { java.text.SimpleDateFormat(if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm", Locale.getDefault()) }
            val shade = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.85f), blurRadius = 10f))
            Column(Modifier.align(Alignment.TopEnd).padding(top = 26.dp, end = 32.dp), horizontalAlignment = Alignment.End) {
                Text(fmt.format(java.util.Date(now)), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, style = shade)
                Text("ends " + endsAt((dur - (seekTo ?: pos)).coerceAtLeast(0), speed), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, style = shade)
            }
        }
        // Paused: a small accent ring in the middle.
        if (!playing && panel == null && !nextUp) Box(Modifier.align(Alignment.Center).size(58.dp)
            .background(Color.Black.copy(alpha = 0.35f), androidx.compose.foundation.shape.CircleShape)
            .border(1.5.dp, com.lekathedon.visionlite.ui.KitInk.accent.copy(alpha = 0.85f), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(Icons.Pause, "Paused", Modifier.size(24.dp), tint = Color.White)
        }
        // Paused: the plot bottom left (above the buttons when they show).
        if (!playing && panel == null && !nextUp && !plot.isNullOrBlank()) {
            val shade = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.9f), blurRadius = 12f))
            Text(plot, color = Color.White.copy(alpha = 0.9f), fontSize = 16.sp, lineHeight = 23.sp, maxLines = 5, style = shade,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = if (visible) 150.dp * buttonScale() else 56.dp).then(Modifier.widthIn(max = 900.dp)))
        }
        if (!visible) return@Box

        // Lite's own chrome (2026-11-26): small title top left, and one floating glass dock at the bottom with the
        // timeline on top and compact icon buttons that open into a labelled pill when focused.
        Box(Modifier.align(Alignment.TopStart).fillMaxWidth().height(140.dp)
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(180.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))))
        Column(Modifier.align(Alignment.TopStart).padding(start = 32.dp, top = 26.dp, end = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val ep = remember(title) { EPISODE.find(title) }
            val name = ep?.let { title.substring(0, it.range.first).trim() } ?: title
            var logoFailed by remember(logo) { mutableStateOf(logo.isNullOrBlank()) }
            if (!logoFailed) coil3.compose.AsyncImage(logo, name, Modifier.sizeIn(maxWidth = 267.dp, maxHeight = 69.dp),
                alignment = Alignment.CenterStart, contentScale = androidx.compose.ui.layout.ContentScale.Fit, onError = { logoFailed = true })
            else Text(name, color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            ep?.let { m ->
                val rest = title.substring(m.range.last + 1).trim().trimStart('-', '·', '–').trim()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("S${m.groupValues[1].toInt()} · E${m.groupValues[2].toInt()}", color = com.lekathedon.visionlite.ui.KitInk.accent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    if (rest.isNotEmpty()) Text("   $rest", color = Color.White.copy(alpha = 0.78f), fontSize = 17.sp, maxLines = 1)
                }
            }
        }

        val shown = seekTo ?: pos
        if (thumbs && seekTo != null) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp)) { ArcThumbPane(shown, dur) }
        // No panel round it (user 2026-11-26): the timeline and the buttons sit straight on the bottom shade.
        Column(Modifier.align(Alignment.BottomCenter).padding(start = 48.dp, end = 48.dp, bottom = 24.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            var barFocused by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(clock(shown), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(56.dp))
                // The timeline: a 3 dp line, the played part in the look's accent; focused it thickens and shows its knob.
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).height(16.dp)
                    .focusRequester(bar).onFocusChanged { barFocused = it.isFocused }.focusable()
                    .onKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (e.key) {
                            Key.DirectionLeft -> { step(false); true }
                            Key.DirectionRight -> { step(true); true }
                            Key.DirectionCenter, Key.Enter -> { toggle(); true }
                            else -> false
                        }
                    }, contentAlignment = Alignment.CenterStart) {
                    val accent = com.lekathedon.visionlite.ui.KitInk.accent
                    val frac = if (dur > 0) (shown.toFloat() / dur).coerceIn(0f, 1f) else 0f
                    Box(Modifier.fillMaxWidth().height(if (barFocused) 5.dp else 3.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.14f))) {
                        if (dur > 0) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth((buffered.toFloat() / dur).coerceIn(0f, 1f)).background(Color.White.copy(alpha = 0.16f)))
                            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.55f), accent))))
                        }
                    }
                    val dot = 12.dp
                    if (dur > 0 && (barFocused || seekTo != null)) Box(Modifier.padding(start = ((maxWidth - dot) * frac)).size(dot)
                        .background(Color.White, androidx.compose.foundation.shape.CircleShape).border(2.dp, accent, androidx.compose.foundation.shape.CircleShape))
                }
                Text(if (dur > 0) "-" + clock(dur - shown) else "", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayButton(playing, Modifier.focusRequester(playButton)) { toggle() }
                Spacer(Modifier.width(6.dp))
                DockButton(Icons.Restart, "Restart") { player.seekTo(0); player.play() }
                DockButton(Icons.Subtitles, "Subtitles", Modifier.focusRequester(dockFocus.getValue(Panel.SUBS))) { opener = Panel.SUBS; panel = Panel.SUBS }
                DockButton(Icons.Audio, "Audio", Modifier.focusRequester(dockFocus.getValue(Panel.AUDIO))) { opener = Panel.AUDIO; panel = Panel.AUDIO }
                DockButton(Icons.Sources, "Sources", Modifier.focusRequester(sourcesButton)) { onSources() }
                if (canNext) DockButton(Icons.Next, "Next episode") { onNext() }
                DockButton(Icons.Speed, "Speed", Modifier.focusRequester(dockFocus.getValue(Panel.SPEED))) { opener = Panel.SPEED; panel = Panel.SPEED }
                DockButton(Icons.Aspect, "Aspect · ${Aspects[aspect].second}") { aspect = (aspect + 1) % Aspects.size; onAspect(Aspects[aspect].first); note = "Aspect: ${Aspects[aspect].second}" }
                DockButton(Icons.Dashboard, "Stats", Modifier.focusRequester(dockFocus.getValue(Panel.STATS))) { opener = Panel.STATS; panel = Panel.STATS }
                Spacer(Modifier.weight(1f))
                DockButton(Icons.More, "More", Modifier.focusRequester(dockFocus.getValue(Panel.MORE))) { opener = Panel.MORE; panel = Panel.MORE }
            }
        }

        when (panel) {
            Panel.AUDIO -> TrackPanel("Audio", player, C.TRACK_TYPE_AUDIO, allowOff = false,
                extra = "Audio delay · ${Sync.label(Sync.audioMs)}  ›" to { syncSubsFirst = false; panel = Panel.SYNC }) { panel = null }
            Panel.SUBS -> TrackPanel("Subtitles", player, C.TRACK_TYPE_TEXT, allowOff = true,
                extra = "Subtitle delay · ${Sync.label(Sync.subMs)}  ›" to { syncSubsFirst = true; panel = Panel.SYNC },
                extra2 = "Style, colour and HDR dimming  ›" to { panel = Panel.SUBSTYLE }) { panel = null }
            Panel.SUBSTYLE -> SubStylePanel(onSubStyle)
            Panel.SPEED -> SidePanel("Speed", Speeds.map { fmtSpeed(it) }, Speeds.indexOf(speed)) { i -> speed = Speeds[i]; player.setPlaybackSpeed(speed); panel = null }
            Panel.STATS -> StatsPanel(remember(engine) { stats() }, Modifier.align(Alignment.TopEnd))
            Panel.SYNC -> SyncPanel(onDelays, subsFirst = syncSubsFirst)
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
            Panel.MORE -> {
                val items = buildList<Pair<String, () -> Unit>> {
                    if (canNext) add("Sources" to { panel = null; onSources() })
                    if (vs10) add("VS10 · switch" to {
                        panel = null; note = "VS10…"
                        scope.launch { note = withContext(Dispatchers.IO) { Vs10Engine.cycle() }?.let(::vs10Text) ?: "VS10 didn't answer (root?)" }
                    })
                    add("Speed · ${fmtSpeed(speed)}" to { panel = Panel.SPEED })
                    if (onZap == null) add("Sync · audio ${Sync.label(Sync.audioMs)} · subs ${Sync.label(Sync.subMs)}" to { panel = Panel.SYNC })
                    add((sleepAt?.let { "Sleep in ${((it - System.currentTimeMillis()) / 60_000) + 1} min" } ?: if (sleepEnd) "Sleep at the end" else "Sleep timer") to { panel = Panel.SLEEP })
                    add("Button size · ${ButtonSizes.firstOrNull { it.first == prefs.playerButtons.value }?.second ?: "Small"}" to { panel = Panel.BUTTONS })
                    add("Engine · ${engine.label} (switch)" to { panel = null; onEngine() })
                }
                SidePanel("More", items.map { it.first }, -1) { i -> items[i].second() }
            }
            Panel.BUTTONS -> SidePanel("Button size", ButtonSizes.map { it.second }, ButtonSizes.indexOfFirst { it.first == prefs.playerButtons.value }) { i ->
                prefs.playerButtons.value = ButtonSizes[i].first; panel = null
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

/**
 * Subtitle look while watching (user 2026-11-27): size, colour, edge, outline, background, bold, position and the HDR/DV
 * dimming, the same settings as Settings › Player › Subtitles. Left / Right (or OK) change a line; applied at once.
 */
@Composable
private fun SubStylePanel(onChange: () -> Unit) {
    val p = com.lekathedon.visionlite.App.instance.prefs
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(30); runCatching { first.requestFocus() } }
    var gen by remember { mutableIntStateOf(0) }
    val bold = listOf("false" to "Off", "true" to "On")
    class Line(val name: String, val opts: List<Pair<String, String>>, val get: () -> String, val set: (String) -> Unit)
    val lines = listOf(
        Line("HDR / DV brightness", SubStyle.HdrLevels, { p.subHdrBright.value }) { p.subHdrBright.value = it },
        Line("Size", SubStyle.Sizes, { p.subSize.value }) { p.subSize.value = it },
        Line("Colour", SubStyle.TextColors, { p.subColor.value }) { p.subColor.value = it },
        Line("Edge", SubStyle.Edges, { p.subEdge.value }) { p.subEdge.value = it },
        Line("Outline colour", SubStyle.OutlineColors, { p.subOutlineColor.value }) { p.subOutlineColor.value = it },
        Line("Background", SubStyle.Backgrounds, { p.subBack.value }) { p.subBack.value = it },
        Line("Bold", bold, { p.subBold.value.toString() }) { p.subBold.value = it == "true" },
        Line("Position", SubStyle.Positions, { p.subPos.value }) { p.subPos.value = it },
        Line("HDR / DV dimming", listOf("true" to "On", "false" to "Off"), { p.subHdr.value.toString() }) { p.subHdr.value = it == "true" },
    )
    Box(Modifier.fillMaxSize()) {
        com.lekathedon.visionlite.ui.Inside(true) { Column(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(380.dp).background(K.panel.copy(alpha = 0.96f)).padding(24.dp)) {
            com.lekathedon.visionlite.ui.DisplayText(if (K.style == com.lekathedon.visionlite.ui.Style.MARQUEE) "SUBTITLE STYLE" else "Subtitle style", 20.sp, maxLines = 1)
            Text("Left / Right to change · applied now", color = com.lekathedon.visionlite.ui.Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            androidx.compose.foundation.lazy.LazyColumn {
                items(lines.size) { idx ->
                    val l = lines[idx]
                    var f by remember { mutableStateOf(false) }
                    val cur = gen.let { l.opts.indexOfFirst { it.first == l.get() }.coerceAtLeast(0) }
                    fun move(d: Int) { val n = l.opts.size; l.set(l.opts[((cur + d) % n + n) % n].first); gen++; onChange() }
                    Row(Modifier.padding(top = 6.dp).fillMaxWidth().then(if (idx == 0) Modifier.focusRequester(first) else Modifier)
                        .onFocusChanged { f = it.isFocused }.focusable().onKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (e.key) {
                                Key.DirectionLeft -> { move(-1); true }
                                Key.DirectionRight, Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { move(1); true }
                                else -> false
                            }
                        }.clip(RoundedCornerShape(K.rowR)).background(if (f) com.lekathedon.visionlite.ui.kitFocus.first else K.rowFill).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        val ink = if (f) com.lekathedon.visionlite.ui.kitFocus.second else com.lekathedon.visionlite.ui.Palette.Text
                        Text(l.name, color = ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text("‹ " + l.opts[cur].second + " ›", color = if (f) ink else K.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        } }
    }
}

/** Audio and subtitle delay: Left / Right move a line by 50 ms (held: faster), OK on a line sets it back to 0. */
@Composable
private fun SyncPanel(onDelays: () -> Unit, subsFirst: Boolean = false) {
    var a by remember { mutableLongStateOf(Sync.audioMs) }
    var sub by remember { mutableLongStateOf(Sync.subMs) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(30); runCatching { first.requestFocus() } }
    // Applied once the presses stop (the ExoPlayer flush is a short re-buffer).
    LaunchedEffect(a, sub) { if (a != Sync.audioMs || sub != Sync.subMs) { delay(500); Sync.set(a, sub); onDelays() } }
    Box(Modifier.fillMaxSize()) {
        com.lekathedon.visionlite.ui.Inside(true) { Column(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(380.dp).background(K.panel.copy(alpha = 0.96f)).padding(24.dp)) {
            com.lekathedon.visionlite.ui.DisplayText(if (K.style == com.lekathedon.visionlite.ui.Style.MARQUEE) "SYNC" else "Sync", 20.sp, maxLines = 1)
            Text("Remembered for this title. Left / Right: 50 ms; OK: back to 0", color = com.lekathedon.visionlite.ui.Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
            listOf(true, false).forEach { audio ->
                var reps by remember { mutableIntStateOf(0) }
                Box(Modifier.padding(top = 8.dp).fillMaxWidth().then(if (audio != subsFirst) Modifier.focusRequester(first) else Modifier)) {
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
                    }.clip(RoundedCornerShape(K.rowR)).background(if (f) com.lekathedon.visionlite.ui.kitFocus.first else K.rowFill).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        val ink = if (f) com.lekathedon.visionlite.ui.kitFocus.second else com.lekathedon.visionlite.ui.Palette.Text
                        Column(Modifier.weight(1f)) {
                            Text(if (audio) "Audio delay" else "Subtitle delay", color = ink, fontSize = 16.sp)
                            Text(if (audio) "+ = sound later" else "+ = subtitles later", color = ink.copy(alpha = 0.7f), fontSize = 12.sp)
                        }
                        Text("‹  " + Sync.label(if (audio) a else sub) + "  ›", color = if (f) ink else K.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        } }
    }
}

/** The play button (user 2026-11-26): just the play / pause icon, no disc; focused, only the skin's ring, like the other buttons. */
@Composable
internal fun PlayButton(playing: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    com.lekathedon.visionlite.ui.Inside(true) { Focusable(onClick, modifier, androidx.compose.foundation.shape.CircleShape) { f ->
        val k = buttonScale()
        Box(Modifier.size(42.dp * k), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(if (playing) Icons.Pause else Icons.Play, if (playing) "Pause" else "Play", Modifier.size(24.dp * k),
                tint = if (f) Color.White else Color.White.copy(alpha = 0.85f))
        }
    } }
}

/** A player button: a quiet 36 dp icon; focused, it opens to show its name inside a ring in the skin's focus colour (no fill). */
@Composable
internal fun DockButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    com.lekathedon.visionlite.ui.Inside(true) { Focusable(onClick, modifier, RoundedCornerShape(50)) { f ->
        val k = buttonScale()
        Row(Modifier.height(36.dp * k).animateContentSize(androidx.compose.animation.core.tween(160))
            .padding(horizontal = (if (f) 12.dp else 8.dp) * k), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(icon, label, Modifier.size(20.dp * k), tint = if (f) Color.White else Color.White.copy(alpha = 0.72f))
            if (f) Text(label, color = Color.White, fontSize = 13.sp * k, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(start = 8.dp * k))
        }
    } }
}

@Composable
private fun Pill(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Focusable(onClick, modifier, RoundedCornerShape(20.dp)) { f ->
        Text(text, color = if (f) com.lekathedon.visionlite.ui.KitInk.ink else com.lekathedon.visionlite.ui.KitInk.text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.background(if (f) com.lekathedon.visionlite.ui.KitInk.bg else com.lekathedon.visionlite.ui.Kit.rowFill).padding(horizontal = 18.dp, vertical = 9.dp))
    }
}

@Composable
internal fun SidePanel(title: String, items: List<String>, selected: Int, bottom: Boolean = false, onPick: (Int) -> Unit) {
    val first = remember { FocusRequester() }
    // The ticked line can sit far down a long list (add-on subtitles): the list opens scrolled to it, so it exists to
    // take focus; tried for a few frames (user 2026-11-27: Subtitles opened with nothing focused).
    val state = androidx.compose.foundation.lazy.rememberLazyListState((selected - 3).coerceAtLeast(0))
    LaunchedEffect(Unit) {
        delay(30)
        repeat(10) { if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect; androidx.compose.runtime.withFrameNanos { } }
    }
    Box(Modifier.fillMaxSize()) {
        // bottom = the live player's popup over its control bar (TiviMate style), not a side panel.
        com.lekathedon.visionlite.ui.Inside(true) { Column((if (bottom) Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 120.dp).width(440.dp)
            .heightIn(max = 470.dp).background(K.panel.copy(alpha = 0.96f), RoundedCornerShape(K.cardR)).border(1.dp, K.line, RoundedCornerShape(K.cardR)).padding(20.dp)
        else Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(380.dp).background(K.panel.copy(alpha = 0.96f)).padding(24.dp))) {
            com.lekathedon.visionlite.ui.DisplayText(if (K.style == com.lekathedon.visionlite.ui.Style.MARQUEE) title.uppercase() else title, 20.sp, Modifier.padding(bottom = 12.dp), maxLines = 1)
            LazyColumn(state = state) {
                itemsIndexed(items) { i, label ->
                    Focusable({ onPick(i) }, (if (i == selected.coerceAtLeast(0)) Modifier.focusRequester(first) else Modifier).padding(top = 6.dp).fillMaxWidth(), RoundedCornerShape(K.rowR)) { f ->
                        val ink = if (f) com.lekathedon.visionlite.ui.kitFocus.second else com.lekathedon.visionlite.ui.Palette.Text
                        Row(Modifier.fillMaxWidth().background(if (f) com.lekathedon.visionlite.ui.kitFocus.first else K.rowFill).padding(12.dp)) {
                            Text(if (i == selected) "✓  " else "    ", color = if (f) ink else K.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(label, color = ink, fontSize = 15.sp)
                        }
                    }
                }
            }
        } }
    }
}

/** The file's tracks of [type]; picking one pins it, "Off" turns subtitles off. */
@Composable
internal fun TrackPanel(title: String, player: Player, type: Int, allowOff: Boolean, bottom: Boolean = false,
                        extra: Pair<String, () -> Unit>? = null, extra2: Pair<String, () -> Unit>? = null, close: () -> Unit) {
    val groups = player.currentTracks.groups.filter { it.type == type }
    val options = groups.flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { i -> g to i } }
    val off = allowOff && player.trackSelectionParameters.disabledTrackTypes.contains(type)
    val selected0 = if (off) 0 else options.indexOfFirst { (g, i) -> g.isTrackSelected(i) }.let { if (allowOff && it >= 0) it + 1 else it }
    val labels0 = (if (allowOff) listOf("Off") else emptyList()) + options.map { (g, i) -> trackLabel(g.getTrackFormat(i), type) }
    if (labels0.isEmpty() && extra == null) { SidePanel(title, listOf("None in this file"), -1, bottom) { close() }; return }
    // [extra] (the delay line) on top; the tracks below it.
    val tops = listOfNotNull(extra, extra2)
    val shift = tops.size
    val labels = tops.map { it.first } + labels0.ifEmpty { listOf("None in this file") }
    val selected = if (selected0 >= 0) selected0 + shift else -1
    SidePanel(title, labels, selected, bottom) { pick0 ->
        if (pick0 < shift) { tops[pick0].second(); return@SidePanel }
        val pick = pick0 - shift
        if (pick !in 0 until labels0.size) { close(); return@SidePanel }
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

/** The track of [type] playing now, as the panels name it; "Off" / null. */
internal fun currentTrack(player: Player, type: Int): String? {
    if (player.trackSelectionParameters.disabledTrackTypes.contains(type)) return "Off"
    player.currentTracks.groups.filter { it.type == type }.forEach { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { return trackLabel(g.getTrackFormat(it), type) } }
    return if (type == C.TRACK_TYPE_TEXT) "Off" else null
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

private val K get() = com.lekathedon.visionlite.ui.Kit

private val EPISODE = Regex("""\s[Ss](\d+)[Ee](\d+)\b""")

private fun fmtSpeed(s: Float) = if (s == 1f) "1×" else "${s.toString().trimEnd('0').trimEnd('.')}×"

internal fun clock(ms: Long): String {
    val t = (ms.coerceAtLeast(0) / 1000)
    val h = t / 3600; val m = t % 3600 / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun endsAt(leftMs: Long, speed: Float): String {
    val at = System.currentTimeMillis() + (leftMs / speed).toLong()
    return java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(at))
}
