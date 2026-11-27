package com.lekathedon.visionlite.streams

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.lekathedon.visionlite.ui.Text
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

/** [vod]: an IPTV VOD file matched by Vision TB (streams/VodLinks.kt). [dub]: the language it is dubbed in, when known. */
/** [tech]: a VOD copy's real file facts from the panel ("4K · Dolby Vision · HEVC · Atmos DD+ 5.1 · 26 Mbps"), Play with VOD only. */
data class Link(val source: String, val stream: Stream, val tb: com.lekathedon.visionlite.tb.VisionTb.Pick? = null, val vod: Boolean = false, val dub: String? = null,
                val tech: String? = null, val group: String? = null, val probe: com.lekathedon.visionlite.probe.VodProbe.Result? = null) {
    val playable get() = !stream.url.isNullOrBlank() || tb != null || (!stream.infoHash.isNullOrBlank() && Debrid.any())
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
    LinkLog.add("${s.title}: ${order.size} safe links of ${all.size}; top: " + order.take(5).mapIndexed { i, l -> "\n  ${i + 1}. ${AutoPick.describe(l)}" }.joinToString(""))
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
        LinkLog.add("try: ${AutoPick.describe(l)}")
        if (play(nav, s, l, replace = true, order, say = say)) { say(""); return true }
        LinkLog.add("didn't start, next")
    }
    say("Autoplay: those links were another episode, pick one")
    return false
}

/** Links for a title, asked from every stream add-on only now (Play was pressed); each add-on's answer shows as it lands. */
@Composable
fun Streams(nav: Nav, s: Screen.Streams,
            /** Opened from the player's Sources button: a panel over the film; a pick hands its player screen here. */
            overlay: ((Screen.Player) -> Unit)? = null, onClose: () -> Unit = {}) {
    val app = App.instance
    if (overlay != null) androidx.activity.compose.BackHandler { onClose() }
    val links = remember(s.id) { mutableStateListOf<Link>() }
    var pending by remember(s.id) { mutableStateOf(-1) }
    var getting by remember(s.id) { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Autoplay on: no list while it looks (user 2026-11-19); the list shows only if you ask or it finds nothing safe.
    var auto by remember(s.id) { mutableStateOf(app.prefs.autoplay.value && !s.manual) }
    var notOut by remember(s.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(s.id) {
        // Audio "Original": the title's language is fetched while you pick a link (one TMDB call per title, ever).
        if (com.lekathedon.visionlite.player.OriginalLang.ORIGINAL in listOf(app.prefs.audioLang.value, app.prefs.audioLang2.value))
            launch { com.lekathedon.visionlite.player.OriginalLang.resolve(s.metaId.ifBlank { s.id }, s.type) }
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
        // Hand fixes for titles (TitleFixes): a newer signed copy, at most every 12 h, only when FIXES_URL is set.
        launch { com.lekathedon.visionlite.tb.TitleFixes.refresh() }
        // Resume on the exact link: a title stopped part-way plays the same file again, no search (falls through if it won't come).
        if (auto && ResumeLinks.has(s.id)) {
            getting = "Resuming on the same link…"
            val back = ResumeLinks.screen(s)
            if (back != null) com.lekathedon.visionlite.addons.Subs.await(s.type, s.id)
            if (back != null) { LinkFallback.set(s, emptyList()); nav.replace(back); return@LaunchedEffect }
            getting = ""
        }
        val tbOn = com.lekathedon.visionlite.tb.VisionTb.isOn()
        val servers = MediaServers.plexOn() || MediaServers.jellyfinOn()
        // IPTV VOD in Vision TB's group (user 2026-11-22): only when a playlist listed films / series.
        val vodOn = app.prefs.vodInTb.value && (s.type == "movie" || s.type == "series") && s.id.startsWith("tt") && VodLinks.available()
        val asked = app.addons.addons.value.count { it.serves("stream", s.type, s.id) } + (if (tbOn) 1 else 0) + (if (servers) 1 else 0) + (if (vodOn) 1 else 0)
        pending = asked
        // Autoplay doesn't wait for the slowest source (an add-on may take its full 20 s): once a link that plays well
        // here is in, the rest get a short grace (AutoPick.settleMs), then it plays. Sources still answering keep
        // filling the list in case autoplay finds nothing safe.
        val started = android.os.SystemClock.elapsedRealtime()
        LinkLog.add("${s.title} (${s.id}): asking $asked source(s), autoplay=$auto")
        fun took() = android.os.SystemClock.elapsedRealtime() - started
        // Vision TB still searching: autoplay never settles without its answer (user 2026-11-26: Next episode from the
        // phone played an add-on's 1080p because TorBox's 4K came in after the grace; 15 s at most, its own budget).
        var tbOut = tbOn
        val decider = if (auto) launch {
            var seen = -1; var top: Link? = null; var topAt = 0L
            while (pending > 0) {
                kotlinx.coroutines.delay(300)
                if (links.size != seen) {
                    seen = links.size
                    val snap = links.toList()
                    val now = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { AutoPick.order(snap, s.id).firstOrNull() }
                    if (now?.stream != top?.stream) { top = now; topAt = android.os.SystemClock.elapsedRealtime() }
                }
                val t = top ?: continue
                val waited = android.os.SystemClock.elapsedRealtime() - topAt
                val tbWait = tbOut && android.os.SystemClock.elapsedRealtime() - started < 15_000
                if (!tbWait && waited >= AutoPick.settleMs(t)) {
                    LinkLog.add("settled after ${android.os.SystemClock.elapsedRealtime() - started} ms, $pending source(s) still out")
                    break
                }
            }
            // The title's original language must be known before the pick (one TMDB call per title, ever; 3 s at most).
            if (com.lekathedon.visionlite.player.OriginalLang.ORIGINAL in listOf(app.prefs.audioLang.value, app.prefs.audioLang2.value))
                kotlinx.coroutines.withTimeoutOrNull(3_000) { com.lekathedon.visionlite.player.OriginalLang.resolve(s.metaId.ifBlank { s.id }, s.type) }
            if (!autoplay(nav, s, arrange(links.toList(), app.prefs)) { getting = it }) auto = false
        } else null
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
                tbOut = false
                LinkLog.add("Vision TB answered after ${took()} ms")
                pending--
            }
            if (vodOn) launch {
                val vod = runCatching { VodLinks.links(s.type, s.id) }.getOrDefault(emptyList())
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { links.addAll(vod) }
                pending--
            }
            // Your own Plex / Jellyfin files (only when signed in).
            if (servers) launch {
                val mine = MediaServers.links(s.type, s.id, s.title)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { links.addAll(mine) }
                pending--
            }
            // The episode list, so autoplay can tell this episode's file from one that only shares its numbers.
            app.client.streams(s.type, s.id) { r ->
                links.addAll(r.streams.map { Link(r.addon.manifest.name, it) })
                // Only torrents this box / TV / internet can play are worth a TorBox check (DeviceFit).
                harvest?.trySend(r.streams.filter { it.url == null && it.infoHash != null && DeviceFit.fit(listOfNotNull(it.name, it.title, it.description, it.behaviorHints?.filename).joinToString(" "),
                    bytes = it.behaviorHints?.videoSize ?: 0) != DeviceFit.NO }.mapNotNull { st ->
                    com.lekathedon.visionlite.tb.AddonHarvest.candidate(st.infoHash, st.url, st.title, st.description,
                        st.behaviorHints?.filename, st.behaviorHints?.videoSize, st.fileIdx)
                })
                LinkLog.add("${r.addon.manifest.name}: ${r.streams.size} links after ${took()} ms")
                if (r.addon.manifest.name.startsWith("Torrentio", true))
                    AutoPick.vouch(s.id, r.streams.mapNotNull { st -> st.infoHash?.let { it to (st.behaviorHints?.filename ?: st.title ?: st.description) } })
                pending--
            }
            harvest?.close()
        }
        LinkLog.add("${s.title}: all sources in after ${android.os.SystemClock.elapsedRealtime() - started} ms")
        decider?.join()
    }
    // Links in installed add-on order (Vision TB's group first), so "Add-on order" is stable however answers land.
    val order = remember(s.id) { listOf(MediaServers.PLEX, MediaServers.JELLYFIN, com.lekathedon.visionlite.tb.VisionTb.NAME) + app.addons.addons.value.map { it.manifest.name } }
    // Link memory: marked links sink to the bottom (still pressable); your pick for the show rises to the top.
    val shown = remember(links.size) {
        arrange(links.filterNot { AutoPick.namesAnotherShow(it, s.id) }.sortedBy { order.indexOf(it.source) }, app.prefs)
            .sortedWith(compareBy<Link> { if (LinkMemory.bad(it) != null) 1 else 0 }.thenByDescending { if (it.playable) LinkMemory.pickMatch(s.id, it) else 0 })
    }
    val minutes = remember(s.id) { LinkMemory.minutesOf(s.type, s.metaId.ifBlank { s.id }) }
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
        // The title's big backdrop while autoplay looks (user 2026-11-22: never a black page).
        com.lekathedon.visionlite.ui.LoadingHero(s.metaId.ifBlank { s.id }.let { if (s.id.startsWith("tt")) s.id else it }, s.title,
            getting.ifEmpty { if (pending > 0) "Finding the best link…" else "Picking the best one…" }) {
            // The look's own button (same as every page), not a washed bubble.
            com.lekathedon.visionlite.ui.KitButton("Choose a link myself", com.lekathedon.visionlite.ui.Icons.Sources, { auto = false },
                Modifier.padding(top = 22.dp).focusRequester(pick))
        }
        return
    }
    // Over the film (Sources in the player): a right-hand panel, the film keeps playing on the left.
    val boxMod = if (overlay != null) Modifier.fillMaxSize() else Modifier.fillMaxSize().background(LocalLook.current.page)
    Box(boxMod) {
        LazyColumn(if (overlay != null) Modifier.align(androidx.compose.ui.Alignment.CenterEnd).fillMaxHeight().width(640.dp)
                .background(com.lekathedon.visionlite.ui.Kit.panel.copy(alpha = 0.96f))
            else Modifier.fillMaxSize(), contentPadding = PaddingValues(if (overlay != null) 28.dp else 48.dp)) {
            item {
                Text(s.title, color = Palette.Text, fontSize = if (overlay != null) 20.sp else 24.sp, fontWeight = FontWeight.Bold)
                if (overlay != null) Text("The film keeps playing · Back to keep watching", color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
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
                Focusable({ if (l.playable) scope.launch { play(nav, s, l, replace = false, shown, overlay) { getting = it } } }, mod.padding(bottom = 8.dp).fillMaxWidth(if (overlay != null) 1f else 0.85f)) { f ->
                    Column(Modifier.fillMaxWidth().background(if (f) com.lekathedon.visionlite.ui.Kit.card2 else Palette.Card).padding(12.dp)) {
                        Text((if (l.tb != null) "" else "${l.source} · ") + l.stream.name.orEmpty().replace('\n', ' '), color = if (l.playable) Palette.Text else Palette.TextDim,
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(if (l.stream.url == null && l.tb == null && l.playable) "Torrent · plays through ${Debrid.names()} · " + l.stream.details
                            else if (l.playable) l.stream.details else "Torrent link: sign in to a debrid service (Settings › links) or use a debrid add-on", color = Palette.TextDim, fontSize = 13.sp, maxLines = 3)
                        if (l.playable && LinkMemory.pickMatch(s.id, l) == 2) Text("★ Same release you watched before", color = com.lekathedon.visionlite.ui.KitInk.accent, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                        LinkMemory.warning(l, minutes)?.let { Text("⚠ $it", color = Color(0xFFFFB454), fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp)) }
                    }
                }
            }
        }
    }
}

/** Vision TB rows get their TorBox link first (about a second); [say] shows how that goes. */
private suspend fun play(nav: Nav, s: Screen.Streams, l: Link, replace: Boolean, list: List<Link>, overlay: ((Screen.Player) -> Unit)? = null, say: (String) -> Unit): Boolean {
    LinkFallback.set(s, list.dropWhile { it !== l }.drop(1).filter { it.playable })
    val url = l.stream.url ?: l.tb?.let { pick ->
        say("Getting the TorBox link…")
        com.lekathedon.visionlite.tb.VisionTb.resolve(pick).fold({ say(""); it }, { say("TorBox: ${it.message}"); null })
    } ?: l.stream.infoHash?.takeIf { Debrid.any() }?.let { h ->
        say("Getting the ${Debrid.names()} link…")
        Debrid.resolve(h, l.stream.fileIdx, l.stream.behaviorHints?.filename, EpisodeCheck.targetOf(s.id)).fold({ say(""); it }, { say(it.message ?: "Debrid failed"); null })
    } ?: return false
    // The player opens with the outside subtitles in (OpenSubtitles / add-ons), not before they land (2.5 s at most).
    com.lekathedon.visionlite.addons.Subs.await(s.type, s.id)
    ResumeLinks.remember(s.id, l)
    // Vision TB's report: the sources that had the link you (or autoplay) played get the credit.
    l.tb?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.lekathedon.visionlite.tb.TbSources.played(s.id, it.hash) } } }
    val p = Screen.Player(url, s.title, l.stream.behaviorHints?.proxyHeaders?.request.orEmpty(), s.type, s.id, listOfNotNull(l.stream.behaviorHints?.filename, l.stream.name, l.stream.details).joinToString("\n"), l.source, l.bytes)
    if (overlay != null) overlay(p) else if (replace) nav.replace(p) else nav.push(p)
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
            val url = l.stream.url ?: l.tb?.let { com.lekathedon.visionlite.tb.VisionTb.resolve(it).getOrNull() }
                ?: l.stream.infoHash?.takeIf { Debrid.any() }?.let { Debrid.resolve(it, l.stream.fileIdx, l.stream.behaviorHints?.filename, EpisodeCheck.targetOf(s.id)).getOrNull() } ?: continue
            return Screen.Player(url, s.title, l.stream.behaviorHints?.proxyHeaders?.request.orEmpty(), s.type, s.id, listOfNotNull(l.stream.behaviorHints?.filename, l.stream.name, l.stream.details).joinToString("\n"), l.source, l.bytes)
        }
        return null
    }
}
