package com.lekathedon.visionlite.streams

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.addons.Stream
import com.lekathedon.visionlite.ui.Focusable
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.Screen
import kotlinx.coroutines.launch

data class Link(val source: String, val stream: Stream, val tb: com.lekathedon.visionlite.tb.VisionTb.Pick? = null) {
    val playable get() = !stream.url.isNullOrBlank() || tb != null
    /** 2160 / 1080 / 720 / 480 read from the add-on's text; 0 if not given. */
    val height: Int get() {
        val t = "${stream.name} ${stream.details} ${stream.behaviorHints?.filename}".lowercase()
        return when { "2160" in t || "4k" in t || "uhd" in t -> 2160; "1080" in t -> 1080; "720" in t -> 720; "480" in t -> 480; else -> 0 }
    }
    val bytes: Long get() = stream.behaviorHints?.videoSize ?: Regex("""([\d.]+)\s*(gb|mb)""", RegexOption.IGNORE_CASE)
        .find(stream.details)?.destructured?.let { (n, u) -> ((n.toDoubleOrNull() ?: 0.0) * if (u.equals("gb", true)) 1e9 else 1e6).toLong() } ?: 0L
}

private val Cam = Regex("""\b(cam|camrip|hdcam|ts|hdts|telesync|tc|telecine)\b""", RegexOption.IGNORE_CASE)

/** The user's link settings: what is hidden, then the order. Add-on order keeps each add-on's own order. */
fun arrange(all: List<Link>, p: com.lekathedon.visionlite.data.Prefs): List<Link> {
    val maxH = p.maxQuality.value.toIntOrNull() ?: 0
    val maxBytes = (p.sizeLimit.value.toLongOrNull() ?: 0L) * 1_000_000_000L
    val kept = all.filter { l ->
        (!p.hideTorrents.value || l.playable) &&
            (maxH == 0 || l.height <= maxH) &&
            (maxBytes == 0L || l.bytes == 0L || l.bytes <= maxBytes) &&
            (!p.hideCam.value || !Cam.containsMatchIn("${l.stream.name} ${l.stream.details} ${l.stream.behaviorHints?.filename}"))
    }
    return when (p.streamSort.value) {
        "quality" -> kept.sortedWith(compareByDescending<Link> { it.playable }.thenByDescending { it.height }.thenByDescending { it.bytes })
        "biggest" -> kept.sortedWith(compareByDescending<Link> { it.playable }.thenByDescending { it.bytes })
        "smallest" -> kept.sortedWith(compareByDescending<Link> { it.playable }.thenBy { if (it.bytes == 0L) Long.MAX_VALUE else it.bytes })
        else -> kept
    }
}

/** Autoplay pick: playable, highest resolution, then biggest file. */
fun best(links: List<Link>): Link? = links.filter { it.playable }.maxWithOrNull(compareBy<Link>({ it.height }, { it.bytes }))

/** Links for a title, asked from every stream add-on only now (Play was pressed); each add-on's answer shows as it lands. */
@Composable
fun Streams(nav: Nav, s: Screen.Streams) {
    val app = App.instance
    val links = remember(s.id) { mutableStateListOf<Link>() }
    var pending by remember(s.id) { mutableStateOf(-1) }
    var getting by remember(s.id) { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(s.id) {
        val tbOn = com.lekathedon.visionlite.tb.VisionTb.isOn()
        val asked = app.addons.addons.value.count { it.serves("stream", s.type, s.id) } + if (tbOn) 1 else 0
        pending = asked
        kotlinx.coroutines.coroutineScope {
            // Add-on subtitles for the player, asked alongside the links (nothing before Play was pressed).
            launch { runCatching { com.lekathedon.visionlite.addons.Subs.load(s.type, s.id) } }
            if (tbOn) launch {
                com.lekathedon.visionlite.tb.VisionTb.discover(s.type, s.id) { rows ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        links.addAll(rows.map { (st, pick) -> Link(com.lekathedon.visionlite.tb.VisionTb.NAME, st, pick) })
                    }
                }
                pending--
            }
            app.client.streams(s.type, s.id) { r ->
                links.addAll(r.streams.map { Link(r.addon.manifest.name, it) })
                pending--
            }
        }
        if (app.prefs.autoplay.value) best(arrange(links, app.prefs))?.let { play(nav, s, it, replace = true) { getting = it } }
    }
    // Links in installed add-on order (Vision TB's group first), so "Add-on order" is stable however answers land.
    val order = remember(s.id) { listOf(com.lekathedon.visionlite.tb.VisionTb.NAME) + app.addons.addons.value.map { it.manifest.name } }
    val shown = remember(links.size) { arrange(links.sortedBy { order.indexOf(it.source) }, app.prefs) }
    val hiddenCount = links.size - shown.size
    val first = remember { FocusRequester() }
    val firstPlayable = shown.firstOrNull { it.playable }
    LaunchedEffect(firstPlayable != null) { if (firstPlayable != null) runCatching { first.requestFocus() } }

    Box(Modifier.fillMaxSize().background(LocalLook.current.page)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(48.dp)) {
            item {
                Text(s.title, color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                val status = when {
                    pending == 0 && links.isEmpty() -> "No links found"
                    pending > 0 -> "${shown.size} links · waiting for $pending add-on(s)"
                    pending == 0 -> "${shown.size} links"
                    else -> ""
                } + (if (hiddenCount > 0) " · $hiddenCount hidden by your link settings" else "") + (if (getting.isNotEmpty()) " · $getting" else "")
                Text(status, color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))
            }
            items(shown) { l ->
                val mod = if (l === firstPlayable) Modifier.focusRequester(first) else Modifier
                Focusable({ if (l.playable) scope.launch { play(nav, s, l, replace = false) { getting = it } } }, mod.padding(bottom = 8.dp).fillMaxWidth(0.85f)) { f ->
                    Column(Modifier.fillMaxWidth().background(if (f) Color(0x33FFFFFF) else Palette.Card).padding(12.dp)) {
                        Text((if (l.tb != null) "" else "${l.source} · ") + l.stream.name.orEmpty().replace('\n', ' '), color = if (l.playable) Palette.Text else Palette.TextDim,
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(if (l.playable) l.stream.details else "Torrent link: needs a debrid add-on", color = Palette.TextDim, fontSize = 13.sp, maxLines = 3)
                    }
                }
            }
        }
    }
}

/** Vision TB rows get their TorBox link first (about a second); [say] shows how that goes. */
private suspend fun play(nav: Nav, s: Screen.Streams, l: Link, replace: Boolean, say: (String) -> Unit) {
    val url = l.stream.url ?: l.tb?.let { pick ->
        say("Getting the TorBox link…")
        com.lekathedon.visionlite.tb.VisionTb.resolve(pick).fold({ say(""); it }, { say("TorBox: ${it.message}"); null })
    } ?: return
    val p = Screen.Player(url, s.title, l.stream.behaviorHints?.proxyHeaders?.request.orEmpty(), s.type, s.id)
    if (replace) nav.replace(p) else nav.push(p)
}
