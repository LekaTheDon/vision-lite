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

/**
 * Smart autoplay (Vision's goal): runs only after every add-on and Vision TB answered. The right episode first (names,
 * the show's own episode titles, Torrentio's episode index), then playable now, no CAM, 4K, the biggest file. A
 * finished add-on link is asked once (1 byte, 3 s) what file it serves; a wrong episode moves to the next pick, up to
 * 3 times, then the list stays with a note. Null pick = nothing safe, the list stays.
 */
fun best(links: List<Link>, id: String = ""): Link? = AutoPick.order(links, id).firstOrNull()

/** True when it started a link; false leaves the list (with a note) for you to pick. */
private suspend fun autoplay(nav: Nav, s: Screen.Streams, all: List<Link>, say: (String) -> Unit): Boolean {
    val target = EpisodeCheck.targetOf(s.id)
    val order = AutoPick.order(all, s.id)
    if (order.isEmpty()) { say(if (all.any { it.playable }) "Autoplay: no link is surely this episode, pick one" else ""); return false }
    var wrong = 0
    for (l in order.take(4)) {
        val url = l.stream.url
        if (target != null && l.tb == null && url != null) {
            say("Checking the link…")
            if (AutoPick.checkLink(url, l.stream.behaviorHints?.proxyHeaders?.request.orEmpty(), target) == EpisodeCheck.Verdict.WRONG) {
                if (++wrong >= 3) break
                continue
            }
        }
        // A TorBox link that won't come (removed from TorBox meanwhile) moves on to the next pick.
        if (play(nav, s, l, replace = true, order, say)) { say(""); return true }
    }
    say("Autoplay: those links were another episode, pick one")
    return false
}

/** Links for a title, asked from every stream add-on only now (Play was pressed); each add-on's answer shows as it lands. */
@Composable
fun Streams(nav: Nav, s: Screen.Streams) {
    val app = App.instance
    val links = remember(s.id) { mutableStateListOf<Link>() }
    var pending by remember(s.id) { mutableStateOf(-1) }
    var getting by remember(s.id) { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Autoplay on: no list while it looks (user 2026-11-19); the list shows only if you ask or it finds nothing safe.
    var auto by remember(s.id) { mutableStateOf(app.prefs.autoplay.value && !s.manual) }
    var notOut by remember(s.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(s.id) {
        // An episode that hasn't aired: nothing plays and Vision TB doesn't search (user 2026-11-19); the add-ons'
        // links still list, for you to look at.
        val target = EpisodeCheck.targetOf(s.id)
        if (s.type == "series" && target != null) EpisodeFacts.ensure(s.type, s.id)
        if (s.type == "movie" && s.id.startsWith("tt") && auto) launch { MovieFacts.ensure(s.id) }
        val airs = EpisodeFacts.notOutYet(target)
        if (airs != null) {
            // Not out: no add-on or Vision TB is asked and there is nothing to press (user 2026-11-19).
            auto = false
            notOut = "Airs " + java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM, HH:mm")
                .format(airs.atZone(java.time.ZoneId.systemDefault()))
            pending = 0
            return@LaunchedEffect
        }
        val tbOn = com.lekathedon.visionlite.tb.VisionTb.isOn()
        val asked = app.addons.addons.value.count { it.serves("stream", s.type, s.id) } + if (tbOn) 1 else 0
        pending = asked
        kotlinx.coroutines.coroutineScope {
            // Add-on subtitles for the player, asked alongside the links (nothing before Play was pressed).
            launch { runCatching { com.lekathedon.visionlite.addons.Subs.load(s.type, s.id) } }
            // Torrents your add-ons return (hash only, no debrid in the add-on) go to Vision TB's TorBox check.
            val harvest = if (tbOn) kotlinx.coroutines.channels.Channel<List<com.lekathedon.visionlite.tb.DiscoveryCandidate>>(kotlinx.coroutines.channels.Channel.UNLIMITED) else null
            val early = HashSet<String>()
            if (tbOn) launch {
                com.lekathedon.visionlite.tb.VisionTb.discover(s.type, s.id, harvest) { rows ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        links.addAll(rows.map { (st, pick) -> Link(com.lekathedon.visionlite.tb.VisionTb.NAME, st, pick) })
                        // Autoplay's likely pick gets its TorBox link now (at most 2 a title), so it starts sooner.
                        if (app.prefs.autoplay.value && early.size < 2) AutoPick.order(links.toList(), s.id).firstOrNull()?.tb?.let { p ->
                            if (early.add(p.hash)) com.lekathedon.visionlite.tb.VisionTb.prefetch(p)
                        }
                    }
                }
                pending--
            }
            // The episode list, so autoplay can tell this episode's file from one that only shares its numbers.
            app.client.streams(s.type, s.id) { r ->
                links.addAll(r.streams.map { Link(r.addon.manifest.name, it) })
                harvest?.trySend(r.streams.filter { it.url == null && it.infoHash != null }.mapNotNull { st ->
                    com.lekathedon.visionlite.tb.AddonHarvest.candidate(st.infoHash, st.url, st.title, st.description,
                        st.behaviorHints?.filename, st.behaviorHints?.videoSize, st.fileIdx)
                })
                if (r.addon.manifest.name.startsWith("Torrentio", true))
                    AutoPick.vouch(s.id, r.streams.mapNotNull { st -> st.infoHash?.let { it to (st.behaviorHints?.filename ?: st.title ?: st.description) } })
                pending--
            }
            harvest?.close()
        }
        if (auto && !autoplay(nav, s, arrange(links, app.prefs)) { getting = it }) auto = false
    }
    // Links in installed add-on order (Vision TB's group first), so "Add-on order" is stable however answers land.
    val order = remember(s.id) { listOf(com.lekathedon.visionlite.tb.VisionTb.NAME) + app.addons.addons.value.map { it.manifest.name } }
    val shown = remember(links.size) { arrange(links.filterNot { AutoPick.namesAnotherShow(it, s.id) }.sortedBy { order.indexOf(it.source) }, app.prefs) }
    val hiddenCount = links.size - shown.size
    val first = remember { FocusRequester() }
    val firstPlayable = shown.firstOrNull { it.playable }
    LaunchedEffect(firstPlayable != null) { if (firstPlayable != null) runCatching { first.requestFocus() } }

    notOut?.let { when_ ->
        Box(Modifier.fillMaxSize().background(LocalLook.current.page), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text(s.title, color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("Not out yet", color = Palette.Text, fontSize = 18.sp, modifier = Modifier.padding(top = 14.dp))
                Text(when_, color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        return
    }
    if (auto) {
        val pick = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { pick.requestFocus() } }
        Box(Modifier.fillMaxSize().background(LocalLook.current.page), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text(s.title, color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(getting.ifEmpty { "Finding the best link…" }, color = Palette.Text, fontSize = 17.sp, modifier = Modifier.padding(top = 14.dp))
                Text(if (pending > 0) "${links.size} links · waiting for $pending source(s)" else "${links.size} links · choosing",
                    color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 22.dp))
                Focusable({ auto = false }, Modifier.focusRequester(pick)) { f ->
                    Text("Choose a link myself", color = Palette.Text, fontSize = 15.sp,
                        modifier = Modifier.background(if (f) Color(0x33FFFFFF) else Palette.Card).padding(horizontal = 20.dp, vertical = 10.dp))
                }
            }
        }
        return
    }
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
                Focusable({ if (l.playable) scope.launch { play(nav, s, l, replace = false, shown) { getting = it } } }, mod.padding(bottom = 8.dp).fillMaxWidth(0.85f)) { f ->
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
private suspend fun play(nav: Nav, s: Screen.Streams, l: Link, replace: Boolean, list: List<Link>, say: (String) -> Unit): Boolean {
    LinkFallback.set(s, list.dropWhile { it !== l }.drop(1).filter { it.playable })
    val url = l.stream.url ?: l.tb?.let { pick ->
        say("Getting the TorBox link…")
        com.lekathedon.visionlite.tb.VisionTb.resolve(pick).fold({ say(""); it }, { say("TorBox: ${it.message}"); null })
    } ?: return false
    val p = Screen.Player(url, s.title, l.stream.behaviorHints?.proxyHeaders?.request.orEmpty(), s.type, s.id, listOfNotNull(l.stream.behaviorHints?.filename, l.stream.name, l.stream.details).joinToString("\n"), l.source, l.bytes)
    if (replace) nav.replace(p) else nav.push(p)
    return true
}

/**
 * A dead link is not a dead end (Vision's source fallback): when a link fails before it really started, the player
 * moves on to the next playable link of the same list, in the order it was shown. Only references; nothing is
 * resolved until a link fails.
 */
object LinkFallback {
    private var id = ""
    private var screen: Screen.Streams? = null
    private var rest: List<Link> = emptyList()

    fun set(s: Screen.Streams, next: List<Link>) { id = s.id; screen = s; rest = next.take(8) }
    fun has(id: String) = id == this.id && rest.isNotEmpty()

    /** The next link's player screen (TorBox links resolved first); null when none is left or none resolves. */
    suspend fun next(id: String): Screen.Player? {
        val s = screen?.takeIf { id == this.id } ?: return null
        while (rest.isNotEmpty()) {
            val l = rest.first(); rest = rest.drop(1)
            val url = l.stream.url ?: l.tb?.let { com.lekathedon.visionlite.tb.VisionTb.resolve(it).getOrNull() } ?: continue
            return Screen.Player(url, s.title, l.stream.behaviorHints?.proxyHeaders?.request.orEmpty(), s.type, s.id, listOfNotNull(l.stream.behaviorHints?.filename, l.stream.name, l.stream.details).joinToString("\n"), l.source, l.bytes)
        }
        return null
    }
}
