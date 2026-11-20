package com.lekathedon.visionlite.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.device.DeviceCheck
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lekathedon.visionlite.integrations.Integrations
import com.lekathedon.visionlite.iptv.Iptv
import com.lekathedon.visionlite.data.Prefs
import com.lekathedon.visionlite.ui.Button
import com.lekathedon.visionlite.ui.Focusable
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.Screen
import kotlinx.coroutines.launch

private val Langs = listOf("" to "Any", "en" to "English", "ar" to "Arabic", "fr" to "French", "de" to "German", "es" to "Spanish",
    "it" to "Italian", "pt" to "Portuguese", "ru" to "Russian", "tr" to "Turkish", "sr" to "Serbian", "hr" to "Croatian",
    "hi" to "Hindi", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese")
/** Audio adds Vision's "Original language" (the title's own, from TMDB). */
private val AudioLangs = listOf(Langs[0], com.lekathedon.visionlite.player.OriginalLang.ORIGINAL to "Original language") + Langs.drop(1)

/** A pane line: a small heading, or a row (gets the pane's first-focus modifier when it is the first row). */
private sealed interface Item
private class Head(val text: String) : Item
private class Line(val content: @Composable (Modifier) -> Unit) : Item

/** One settings page: its name in the rail, a plain sentence on what it holds, a one-line summary of how it is set. */
private class Page(val name: String, val about: String, val summary: @Composable () -> String, val items: List<Item>)

private class PageBuilder {
    val items = mutableListOf<Item>()
    fun head(t: String) { items += Head(t) }
    fun row(c: @Composable (Modifier) -> Unit) { items += Line(c) }
}

private fun page(name: String, about: String, summary: @Composable () -> String, build: PageBuilder.() -> Unit) =
    Page(name, about, summary, PageBuilder().apply(build).items)

private fun label(options: List<Pair<String, String>>, v: String) = options.firstOrNull { it.first == v }?.second ?: options.first().second

private val AfrOpts = listOf("off" to "Off", "always" to "On")
private val EngineOpts = listOf("auto" to "Auto", "exo" to "ExoPlayer", "mpv" to "MPV")
private val SkipOpts = listOf("button" to "Show a button", "auto" to "Skip by itself", "off" to "Off")
private val RewindOpts = listOf("0" to "Off", "3" to "3 s", "5" to "5 s", "10" to "10 s")
private val SubsWhenOpts = listOf("foreign" to "Foreign audio only", "always" to "Always", "off" to "Off")
private val SubSizeOpts = listOf("s" to "Small", "m" to "Medium", "l" to "Large", "xl" to "Extra large")
private val SubStyleOpts = listOf("outline" to "Outline", "white" to "Shadow", "yellow" to "Yellow", "box" to "Black box")
private val SortOpts = listOf("addon" to "Add-on order", "quality" to "Best quality", "biggest" to "Biggest file", "smallest" to "Smallest file")
private val QualityOpts = listOf("0" to "Any", "2160" to "4K", "1080" to "1080p", "720" to "720p")
private val SizeOpts = listOf("0" to "No limit", "5" to "5 GB", "10" to "10 GB", "20" to "20 GB", "40" to "40 GB", "80" to "80 GB")
private val LookOpts = listOf("echo" to "Echo", "classic" to "Classic")
private val PosterOpts = listOf("s" to "Small", "m" to "Medium", "l" to "Large")
private val WatchedOpts = listOf("show" to "Show", "dim" to "Dim", "hide" to "Hide")
private val EpRatingOpts = listOf("all" to "Show all", "watched" to "Watched only", "off" to "Hide")
private val ReviewOpts = listOf("imdb" to "IMDb", "trakt" to "Trakt")
private val DelayOpts = listOf("1.2" to "1.2 s", "5" to "5 s", "10" to "10 s", "20" to "20 s", "30" to "30 s", "60" to "60 s")
private val ImdbMinOpts = listOf("0" to "Off", "6" to "6", "6.5" to "6.5", "7" to "7", "7.5" to "7.5", "8" to "8")

@Composable
private fun watchServices() {
    Integrations.changed.collectAsState().value
    App.instance.trakt.connected.collectAsState().value
}

@Composable
private fun addonsOn(): Int {
    val store = App.instance.addons
    val all by store.all.collectAsState()
    store.addons.collectAsState().value
    return all.count { store.isOn(it.base) }
}

/** What still needs doing, Haven's Overview cards: each one opens the place that fixes it. */
private class ToDo(val title: String, val sub: String, val go: () -> Unit)

/**
 * Settings, Haven style: Overview first (what needs doing + every page's current setup at a glance), then pages
 * named for what they are about. Each sign-in or key lives on the page it serves (TorBox under links, Trakt under
 * tracking, TMDB under title pages, Gemini under recommendations), never in one long list. Every row shows its
 * current value; choices open a short list. Rail on the left: Right goes in, Left or Back comes out.
 */
@Composable
fun Settings(nav: Nav, start: String? = null) {
    val p = App.instance.prefs
    val scope = rememberCoroutineScope()
    var cleared by remember { mutableStateOf("") }
    var picked by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(-1) }
    var jumpPane by remember { mutableStateOf(false) }

    val pages = mutableListOf<Page>()
    pages += page("Overview", "What needs doing, and how everything is set", {
        watchServices()
        val n = toDos(nav, p).size
        if (n == 0) "All set" else "$n to do"
    }) {
        head("To do")
        row { m ->
            watchServices()
            val list = toDos(nav, p)
            Column {
                if (list.isEmpty()) Link("You're all set", "Links, tracking and this TV are ready", m, value = "✓") {}
                list.forEachIndexed { i, t -> Link(t.title, t.sub, if (i == 0) m else Modifier, value = "Fix") { t.go() } }
            }
        }
        head("At a glance")
        row { m ->
            Column {
                pages.drop(1).forEachIndexed { i, pg ->
                    Link(pg.name, pg.about, if (i == 0) m else Modifier, value = pg.summary()) { picked = i + 1; jumpPane = true }
                }
            }
        }
    }
    pages += page("Where links come from", "Debrid, Vision TB, add-ons, and which links you see", {
        watchServices()
        val vt = p.visionTb.flow.collectAsState().value
        val tb = vt && Integrations.isSet(Integrations.byId("torbox"))
        listOfNotNull(if (tb) "Vision TB" else null, "${addonsOn()} add-ons").joinToString(" · ")
    }) {
        head("Debrid")
        row { m -> ServiceRow("torbox", nav, m) }
        row { m -> Link("Vision TB", "TorBox-cached links straight from the torrent sites", m,
            value = if (p.visionTb.flow.collectAsState().value) "On" else "Off") { nav.push(Screen.VisionTb) } }
        head("Add-ons")
        row { m -> Link("Add-ons", "Add by phone, turn on or off, change the order", m, value = "${addonsOn()} on") { nav.push(Screen.Addons) } }
        head("Which links you see")
        row { m -> Choice("Sort links", "", p.streamSort, SortOpts, m) }
        row { m -> Choice("Highest quality", "Links above this are hidden", p.maxQuality, QualityOpts, m) }
        row { m -> Choice("File size limit", "Bigger links are hidden", p.sizeLimit, SizeOpts, m) }
        row { m -> Toggle("Hide torrent-only links", "Links that need a debrid add-on to play", p.hideTorrents, m) }
        row { m -> Toggle("Hide cinema recordings", "CAM and TS copies", p.hideCam, m) }
        head("Autoplay")
        row { m -> Toggle("Smart autoplay", "Plays the right episode, 4K first, then the biggest file. Off until Vision TB is autoplay safe", p.autoplay, m) }
        head("Not in Lite yet")
        row { m -> ServiceRow("realdebrid", nav, m) }
        row { m -> ServiceRow("premiumize", nav, m) }
    }
    pages += page("Tracking and lists", "Where Continue watching, Library and watched marks come from", {
        watchServices()
        Integrations.driver(Integrations.Group.TRACKING)?.name ?: "Not connected"
    }) {
        head("Accounts")
        row { m -> ServiceRow("trakt", nav, m) }
        row { m -> ServiceRow("nuvio", nav, m) }
        row { m -> DriverRow(Integrations.Group.TRACKING, "Continue watching from", m) }
        head("Watching")
        row { m -> Toggle("Resume", "Start where you stopped", p.resume, m) }
        row { m -> Choice("Watched titles on Home", "Not Continue watching; needs Trakt", p.watchedOnHome, WatchedOpts, m) }
        head("Not in Lite yet")
        row { m -> ServiceRow("simkl", nav, m) }
        row { m -> ServiceRow("anilist", nav, m) }
        row { m -> ServiceRow("mal", nav, m) }
    }
    pages += page("Player", "Next episode, skips, resume", {
        val next = p.nextEpisode.flow.collectAsState().value
        "${if (next) "Next episode on" else "Next episode off"} · skips ${label(SkipOpts, p.skipIntro.flow.collectAsState().value).lowercase()}"
    }) {
        head("Episodes")
        row { m -> Toggle("Next episode", "When an episode ends, open the next one's links", p.nextEpisode, m) }
        row { m -> Toggle("Next episode card", "A card at the outro; OK plays the next one", p.nextPopup, m) }
        row { m -> Choice("Skip intro and outro", "IntroDB, AniSkip for anime", p.skipIntro, SkipOpts, m) }
        head("Pausing")
        row { m -> Choice("Step back after pause", "Replays a few seconds when you press play again", p.rewindOnResume, RewindOpts, m) }
        head("Picture, sound and engine")
        row { m -> Link("This TV", "Dolby Vision, frame rate, passthrough, engine and buffer are set for this TV", m) { picked = pages.indexOfFirst { it.name == "This TV" }; jumpPane = true } }
    }
    pages += page("Audio and subtitles", "Languages, when subtitles show, how they look", {
        "${label(AudioLangs, p.audioLang.flow.collectAsState().value)} · subtitles ${label(SubsWhenOpts, p.subsWhen.flow.collectAsState().value).lowercase()}"
    }) {
        head("Audio")
        row { m -> Choice("Audio language", "Original = the title's own language, never the dub", p.audioLang, AudioLangs, m) }
        row { m -> Choice("If that's missing", "Second audio language", p.audioLang2, AudioLangs, m) }
        head("Subtitles")
        row { m -> Choice("Show subtitles", "Foreign only = none when the audio is already your language", p.subsWhen, SubsWhenOpts, m) }
        row { m -> Choice("Subtitle language", "", p.subLang, Langs, m) }
        row { m -> Choice("If that's missing", "Second subtitle language", p.subLang2, Langs, m) }
        head("How they look")
        row { m -> Choice("Size", "", p.subSize, SubSizeOpts, m) }
        row { m -> Choice("Style", "", p.subStyle, SubStyleOpts, m) }
        row { m -> Toggle("Styled anime subtitles", "Anime subtitles keep their own fonts, colours and places (libass)", p.libass, m) }
    }
    pages += page("Home and look", "Rows, posters, clock, background", {
        "${label(LookOpts, p.homeLook.flow.collectAsState().value)}${if (p.oledBlack.flow.collectAsState().value) " · OLED black" else ""}"
    }) {
        head("Rows")
        row { m -> Link("Arrange rows", "Turn rows on or off, change the order, refresh", m) { nav.push(Screen.Rows) } }
        row { m ->
            var n by remember { mutableStateOf(com.lekathedon.visionlite.home.ComingUp.dropped().size) }
            Link("Dropped from Coming up", if (n == 0) "Hold OK on a Coming up card to drop a show" else "OK brings them all back", m,
                value = if (n == 0) "None" else "$n shows") { com.lekathedon.visionlite.home.ComingUp.undropAll(); n = 0 }
        }
        head("Posters")
        row { m -> Toggle("Custom posters", "Your poster source with ratings on the art; off = add-on art", p.customPosters, m) }
        row { m -> Choice("Poster size", "", p.posterSize, PosterOpts, m) }
        row { m -> Toggle("Ratings on posters", "IMDb rating when the catalog gives it", p.ratings, m) }
        head("Look")
        row { m -> Choice("Home look", "", p.homeLook, LookOpts, m) }
        row { m -> Toggle("OLED black", "Pure black pages instead of the colour tint", p.oledBlack, m) }
        row { m -> Toggle("Clock", "Time in the top bar", p.clock, m) }
        row { m -> Toggle("Ask before leaving", "Back on Home asks first", p.exitConfirm, m) }
    }
    pages += page("Spoilers and trailers", "What stays hidden, when trailers play", {
        val blur = p.blurUnwatched.flow.collectAsState().value
        val tr = p.trailers.flow.collectAsState().value
        val wait = label(DelayOpts, p.trailerDelay.flow.collectAsState().value)
        "${if (blur) "Spoilers hidden" else "Spoilers shown"} · ${if (tr) "trailers wait $wait" else "no trailers"}"
    }) {
        head("Spoilers")
        row { m -> Toggle("Hide unwatched episodes", "Pictures and plots stay blurred until watched, here and in Continue watching", p.blurUnwatched, m) }
        row { m -> Choice("Episode ratings", "IMDb rating on each episode", p.epRatings, EpRatingOpts, m) }
        head("Trailers")
        row { m -> Toggle("Trailers", "When you rest on the billboard or an opened poster", p.trailers, m) }
        row { m -> Choice("Trailers wait", "", p.trailerDelay, DelayOpts, m) }
        row { m -> Toggle("Trailer sound", "", p.trailerSound, m) }
    }
    pages += page("Title pages", "Where details and ratings come from, what the page shows", {
        watchServices()
        "${Integrations.driver(Integrations.Group.DATA)?.name ?: "IMDb"} · ${label(ReviewOpts, p.reviewsSource.flow.collectAsState().value)} reviews"
    }) {
        head("Where details come from")
        row { m -> Link("IMDb", "Ratings, cast, episodes, reviews; no key needed", m, value = "Built in") {} }
        row { m -> ServiceRow("tmdb", nav, m) }
        head("On the page")
        row { m -> Choice("Reviews first", "", p.reviewsSource, ReviewOpts, m) }
        row { m -> Toggle("Sequel and prequel", "IMDb's Follows / Followed by", p.sequelPrequel, m) }
        row { m -> Toggle("Connections", "Spin-offs, remakes and other versions", p.connections, m) }
        head("Not in Lite yet")
        row { m -> ServiceRow("tvdb", nav, m) }
        row { m -> ServiceRow("mdblist", nav, m) }
    }
    pages += page("Recommendations", "Picks, the IMDb bar, AI Picks", {
        watchServices()
        val min = p.recMinImdb.flow.collectAsState().value
        val ai = Integrations.driver(Integrations.Group.AI)?.name
        listOfNotNull(if (min == "0") "Any rating" else "IMDb $min+", ai?.let { "AI: $it" }).joinToString(" · ")
    }) {
        head("Picks")
        row { m -> Choice("IMDb minimum", "Nothing rated lower is recommended", p.recMinImdb, ImdbMinOpts, m) }
        row { m ->
            val pool by com.lekathedon.visionlite.recs.Recs.pool.collectAsState()
            val busy by com.lekathedon.visionlite.recs.Recs.building.collectAsState()
            val status = when {
                busy -> "Building…"
                pool == null -> "Not built yet · OK builds now"
                pool!!.seeds == 0 -> "Nothing to learn from yet"
                else -> "${pool!!.picks.size} picks from ${pool!!.seeds} titles · OK rebuilds"
            }
            Link("Picks", status, m) { if (!busy) com.lekathedon.visionlite.recs.Recs.rebuildNow() }
        }
        row { m -> Link("Start over", "Forgets the picks; keeps More like this and Not for me", m) { com.lekathedon.visionlite.recs.Recs.reset() } }
        head("AI Picks (free tiers)")
        row { m -> ServiceRow("gemini", nav, m) }
        row { m -> ServiceRow("groq", nav, m) }
        row { m -> ServiceRow("mistral", nav, m) }
        row { m -> DriverRow(Integrations.Group.AI, "Asked first", m) }
    }
    pages += page("Live TV and more", "IPTV playlists, sports, YouTube", {
        Iptv.version.collectAsState().value
        val n = Iptv.playlists().size
        if (n == 0) "No playlists" else "$n playlist${if (n == 1) "" else "s"}"
    }) {
        head("Live TV")
        row { m ->
            Iptv.version.collectAsState().value
            val n = Iptv.playlists().size
            Link("Playlists", "M3U or Xtream; add, refresh or remove on the Live TV page", m, value = if (n == 0) "Add" else "$n") { nav.push(Screen.LiveTv) }
        }
        head("Sports")
        row { m -> Link("My teams", "Follow teams and leagues", m) { nav.push(Screen.SportsTeams) } }
        head("YouTube and Music")
        row { m -> Link("YouTube account", "Your subscriptions, Music library and history", m,
            value = if (com.lekathedon.visionlite.youtube.Yt.signedIn) "Signed in" else "Sign in") { nav.push(Screen.YouTube("ACCOUNT")) } }
    }
    pages += page("This TV", "What this TV, box and soundbar can do; settings made for them", {
        tvWatch()
        val f = DeviceCheck.saved()
        when {
            f == null -> "Not checked yet"
            p.tvAuto.flow.collectAsState().value -> "The app decides"
            else -> DeviceCheck.differing(f).size.let { if (it == 0) "Set for this TV" else "$it suggestions" }
        }
    }) {
        row { m -> ThisTvPane(m, nav) }
    }
    pages += page("About", "Version, cache, first-start setup", { "Version ${com.lekathedon.visionlite.BuildConfig.VERSION_NAME}" }) {
        head("Vision Lite")
        row { m -> Link("Version", "", m, value = com.lekathedon.visionlite.BuildConfig.VERSION_NAME) {} }
        row { m -> Link("Clear image cache", "Frees memory and disk; posters load again", m, value = cleared) { scope.launch { cleared = clearImages() } } }
        row { m -> Link("Run the first-start setup again", "One page per service, then back to Home", m) { Integrations.setupDone.value = false; nav.pop() } }
    }

    val sel = if (picked >= 0) picked else pages.indexOfFirst { it.name == start }.coerceAtLeast(0)
    val groupReq = remember { List(pages.size) { FocusRequester() } }
    val paneFirst = remember { FocusRequester() }
    var inPane by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; runCatching { groupReq[sel].requestFocus() } }
    LaunchedEffect(jumpPane) { if (jumpPane) { androidx.compose.runtime.withFrameNanos { }; runCatching { paneFirst.requestFocus() }; jumpPane = false } }
    androidx.activity.compose.BackHandler(enabled = inPane) { runCatching { groupReq[sel].requestFocus() } }
    Row(Modifier.fillMaxSize().background(LocalLook.current.page).padding(start = 48.dp, top = 36.dp, end = 48.dp)) {
        Column(Modifier.width(280.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
            Text("Settings", color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, bottom = 14.dp))
            pages.forEachIndexed { i, g ->
                GroupItem(g.name, i == sel, Modifier.focusRequester(groupReq[i])
                    .onFocusChanged { if (it.isFocused) picked = i }
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { runCatching { paneFirst.requestFocus() }; true } else false
                    }) { runCatching { paneFirst.requestFocus() } }
            }
            Text("Changes save as you go", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 24.dp))
        }
        Box(Modifier.padding(start = 24.dp).width(1.dp).fillMaxHeight().background(Color(0x22FFFFFF)))
        val g = pages[sel]
        val firstRow = g.items.indexOfFirst { it is Line }
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight().padding(start = 28.dp)
                .onFocusChanged { inPane = it.hasFocus }
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { runCatching { groupReq[sel].requestFocus() }; true } else false
                },
            contentPadding = PaddingValues(bottom = 48.dp)
        ) {
            item(key = "head$sel") {
                Column(Modifier.padding(bottom = 4.dp, top = 6.dp)) {
                    Text(g.name, color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(g.about, color = Palette.TextDim, fontSize = 13.sp)
                }
            }
            g.items.forEachIndexed { i, it ->
                item(key = "$sel:$i") {
                    when (it) {
                        is Head -> Text(it.text.uppercase(), color = Palette.TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 2.dp))
                        is Line -> it.content(if (i == firstRow) Modifier.focusRequester(paneFirst) else Modifier)
                    }
                }
            }
        }
    }
}

private val Good = Color(0xFF8FD18F)

/** Redraws when any device setting or the check's result changes. */
@Composable
private fun tvWatch() {
    val p = App.instance.prefs
    listOf(p.dv7.flow, p.afr.flow, p.engine.flow, p.buffer.flow, p.maxQuality.flow, p.sizeLimit.flow, p.tvFacts.flow).forEach { it.collectAsState().value }
    listOf(p.matchRes.flow, p.dv5To81.flow, p.stripHdr10Plus.flow, p.ptAc3.flow, p.ptEac3.flow, p.ptTruehd.flow, p.ptDts.flow, p.ptDtshd.flow, p.tvAuto.flow).forEach { it.collectAsState().value }
}

@Composable
private fun SectionHead(t: String) = Text(t.uppercase(), color = Palette.TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
    letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 2.dp))

/**
 * Settings › This TV, Vision's device check the Lite way: Check runs the probes (picture, decoders, soundbar, memory,
 * internet), the findings show in plain words, every device setting offers only what this TV can do with the
 * recommended one marked, one press uses all recommendations, and Let the app decide keeps them right by itself.
 */
@Composable
private fun ThisTvPane(m: Modifier, nav: Nav) {
    val p = App.instance.prefs
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    tvWatch()
    var step by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    val f = DeviceCheck.saved()
    fun check() {
        if (step != null) return
        step = "Starting…"; note = ""
        scope.launch {
            val nf = runCatching { DeviceCheck.probe(ctx, speed = true) { step = it } }.getOrNull()
            step = null
            if (nf == null) { note = "The check didn't finish"; return@launch }
            note = if (p.tvAuto.value) DeviceCheck.useRecommended(nf).let { if (it == 0) "Checked: nothing to change" else "Checked: $it settings updated" }
                   else DeviceCheck.differing(nf).size.let { if (it == 0) "Checked: everything is set right" else "Checked: $it suggestions below" }
        }
    }
    LaunchedEffect(Unit) { if (f == null) check() }
    Column {
        Link(if (f == null) "Check this TV" else "Check again", step ?: note.ifEmpty {
            f?.let { "Last checked " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(it.at)) }
                ?: "Reads the TV, box, soundbar and memory, then tests the internet (about 10 s)" }, m,
            value = if (step != null) "Checking…" else "Check") { check() }
        if (f == null) return@Column
        Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x0FFFFFFF)).padding(horizontal = 18.dp, vertical = 12.dp)) {
            com.lekathedon.visionlite.device.findings(f).forEach { Text(it, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 1.dp)) }
        }
        val diff = DeviceCheck.differing(f)
        Link(if (diff.isEmpty()) "Everything is set right" else "Use all recommended",
            if (diff.isEmpty()) "Every setting below matches this TV" else diff.joinToString(" · ") { "${it.title}: ${it.label(it.recommended)}" }, Modifier,
            value = if (diff.isEmpty()) "✓" else "${diff.size} change${if (diff.size == 1) "" else "s"}") { note = "Used ${DeviceCheck.useRecommended(f)} recommendations" }
        val auto = p.tvAuto.value
        Focusable({ p.tvAuto.value = !auto; if (!auto) DeviceCheck.useRecommended(f) }, Modifier.padding(top = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(10.dp)) { foc ->
            RowText("Let the app decide", "Uses the recommendations by itself, and again whenever you change TV or soundbar", null, foc, switch = auto)
        }
        DeviceCheck.choices(f).groupBy { it.section }.forEach { (section, list) ->
            SectionHead(section)
            list.forEach { c ->
                ChoiceOf(c.title, c.why, DeviceCheck.current(c.key), c.options, Modifier, recommended = c.recommended) { DeviceCheck.set(c.key, it) }
            }
            if (section == "Playback") Toggle("Backup engine", "When one engine can't play a file, the other takes over where it stopped", p.engineBackup)
        }
        SectionHead("Phone")
        Link("Phone companion", "Play on TV, seek and save from your phone", Modifier,
            value = if (p.phoneLink.flow.collectAsState().value) "On" else "Off") { nav.push(Screen.PhoneLink) }
    }
}

private fun toDos(nav: Nav, p: Prefs): List<ToDo> {
    val out = mutableListOf<ToDo>()
    val torbox = Integrations.byId("torbox")
    val addons = App.instance.addons
    val anyAddon = addons.all.value.any { addons.isOn(it.base) }
    if (!Integrations.isSet(torbox) && !anyAddon)
        out += ToDo("Add where links come from", "A TorBox key or an add-on; without one, nothing plays") { nav.push(Screen.Integration("torbox")) }
    if (App.instance.trakt.connected.value == null)
        out += ToDo("Connect Trakt", "Continue watching, Library, resume and Coming up come from it") { nav.push(Screen.Trakt) }
    if (!Integrations.isSet(Integrations.byId("tmdb")))
        out += ToDo("Add a TMDB key", "Top 20 Today and Streaming services need it") { nav.push(Screen.Integration("tmdb")) }
    Integrations.all.filter { Integrations.isSet(it) && Integrations.status(it).value == "refused" }.forEach { s ->
        out += ToDo("${s.name} turned the key down", "Check it, or enter a new one") { nav.push(Screen.Integration(s.id)) }
    }
    val f = DeviceCheck.saved()
    if (f == null)
        out += ToDo("Check this TV", "Finds the best Dolby Vision, sound, frame-rate and buffer settings for this box") { nav.push(Screen.ThisTv) }
    else if (!p.tvAuto.value) DeviceCheck.differing(f).let { d ->
        if (d.isNotEmpty()) out += ToDo("${d.size} suggestions for this TV", "OK uses them: " + d.joinToString { "${it.title} ${it.label(it.recommended)}" }) { DeviceCheck.useRecommended(f) }
    }
    return out
}

/** A sign-in or key on the page it serves. Not signed in → straight to the sign-in; else its page (check, remove, role). */
@Composable
private fun ServiceRow(id: String, nav: Nav, m: Modifier) {
    watchServices()
    val s = Integrations.byId(id)
    val set = Integrations.isSet(s)
    val value = when {
        !set && s.live == null -> "Later"
        !set -> if (s.signIn) "Sign in" else "Add key"
        Integrations.status(s).value == "refused" -> "Key refused"
        s.live == null -> "Saved"
        s.group == Integrations.Group.EXTRAS || Integrations.driver(s.group)?.id == s.id -> if (s.signIn) "Signed in" else "In use"
        Integrations.helper(s).value -> "Helping"
        else -> "Off"
    }
    val sub = s.live ?: "Coming to Lite; a saved key waits here and makes no calls"
    Link(s.name, sub, m, value = value) {
        when {
            s.id == "trakt" && !set -> nav.push(Screen.Trakt)
            s.signIn && !set && s.live != null -> nav.push(Screen.SignIn(s.id))
            else -> nav.push(Screen.Integration(s.id))
        }
    }
}

/** Which set-up service the group takes its answer from (only when there is a choice), and the others' On/Off. */
@Composable
private fun DriverRow(g: Integrations.Group, title: String, m: Modifier) {
    watchServices()
    val able = Integrations.inGroup(g).filter { it.live != null && Integrations.isSet(it) }
    val d = Integrations.driver(g)
    Column {
        if (able.size < 2) Link(title, if (d == null) "Set one up above" else "Only one is set up", m, value = d?.name ?: "None") {}
        else ChoiceOf(title, g.driverDoes, d?.id ?: "", able.map { it.id to it.name }, m) { id -> Integrations.setDriver(g, Integrations.byId(id)) }
        able.filter { it.id != d?.id }.forEach { s ->
            val on = Integrations.helper(s).value
            Focusable({ Integrations.setHelper(s, !on) }, Modifier.padding(top = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(10.dp)) { f ->
                RowText("Also use ${s.name}", if (on) g.helperDoes else "Off: makes no calls", null, f, switch = on)
            }
        }
    }
}

@Composable
private fun GroupItem(name: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Focusable(onClick, modifier.padding(vertical = 1.dp).fillMaxWidth(), shape = RoundedCornerShape(10.dp)) { f ->
        Text(
            name, color = if (f) Color.Black else if (selected) Palette.Text else Palette.TextDim, fontSize = 15.sp,
            fontWeight = if (selected || f) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
            modifier = Modifier.fillMaxWidth().background(if (f) Color.White else if (selected) Color(0x1FFFFFFF) else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 9.dp)
        )
    }
}

private suspend fun clearImages(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val loader = coil3.SingletonImageLoader.get(App.instance)
    loader.memoryCache?.clear()
    loader.diskCache?.clear()
    "Cleared"
}

@Composable
private fun Link(title: String, sub: String, modifier: Modifier = Modifier, value: String = "›", onClick: () -> Unit) {
    Focusable(onClick, modifier.padding(top = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(10.dp)) { f -> RowText(title, sub, value.ifEmpty { "›" }, f) }
}

@Composable
private fun Toggle(title: String, sub: String, flag: Prefs.Flag, modifier: Modifier = Modifier) {
    val on by flag.flow.collectAsState()
    Focusable({ flag.value = !on }, modifier.padding(top = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(10.dp)) { f -> RowText(title, sub, null, f, switch = on) }
}

@Composable
private fun Choice(title: String, sub: String, pref: Prefs.Text, options: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    val v by pref.flow.collectAsState()
    ChoiceOf(title, sub, v, options, modifier) { pref.value = it }
}

/** A row showing the current choice; OK opens a short list of the options (focus on the current one), OK picks. */
@Composable
private fun ChoiceOf(title: String, sub: String, current: String, options: List<Pair<String, String>>, modifier: Modifier,
                     recommended: String? = null, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var reopened by remember { mutableStateOf(false) }
    val me = remember { FocusRequester() }
    LaunchedEffect(open) { if (!open && reopened) { androidx.compose.runtime.withFrameNanos { }; runCatching { me.requestFocus() } } }
    val shown = options.firstOrNull { it.first == current }?.second ?: options.first().second
    Focusable({ open = true; reopened = true }, modifier.focusRequester(me).padding(top = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(10.dp)) { f ->
        RowText(title, sub, shown, f, hint = recommended?.takeIf { it != current }?.let { r -> "Recommended: " + (options.firstOrNull { it.first == r }?.second ?: r) })
    }
    if (open) Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val reqs = remember(options) { List(options.size) { FocusRequester() } }
        val at = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
        LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; runCatching { reqs[at].requestFocus() } }
        Column(Modifier.width(460.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Card).padding(20.dp)) {
            Text(title, color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (sub.isNotEmpty()) Text(sub, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            LazyColumn(Modifier.padding(top = 10.dp).heightIn(max = 380.dp)) {
                items(options.size) { i ->
                    val (id, name) = options[i]
                    Focusable({ onPick(id); open = false }, Modifier.focusRequester(reqs[i]).padding(top = 4.dp).fillMaxWidth(), shape = RoundedCornerShape(10.dp)) { f ->
                        Row(Modifier.fillMaxWidth().background(if (f) Color.White else Color.Transparent).padding(horizontal = 16.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(name, color = if (f) Color.Black else Palette.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            if (id == recommended) Text("Recommended", color = if (f) Color(0xFF1E6B1E) else Good, fontSize = 13.sp, modifier = Modifier.padding(end = 12.dp))
                            if (id == current) Text("✓", color = if (f) Color.Black else Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

/** A settings row: name and note on the left, the value (or a switch) on the right; focused = white card, dark text. */
@Composable
private fun RowText(title: String, sub: String, value: String?, focused: Boolean, switch: Boolean? = null, hint: String? = null) {
    val fg = if (focused) Color.Black else Palette.Text
    val dim = if (focused) Color(0x99000000) else Palette.TextDim
    Row(Modifier.fillMaxWidth().background(if (focused) Color.White else Color(0x14FFFFFF)).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (sub.isNotEmpty()) Text(sub, color = dim, fontSize = 12.sp, maxLines = 2)
            if (hint != null) Text(hint, color = if (focused) Color(0xFF1E6B1E) else Good, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        if (switch != null) {
            val track = if (switch) (if (focused) Color.Black else Color.White) else (if (focused) Color(0x33000000) else Color(0x33FFFFFF))
            val knob = if (switch) (if (focused) Color.White else Color.Black) else (if (focused) Color.White else Color(0xFFBDBDBD))
            Box(Modifier.padding(start = 16.dp).size(40.dp, 22.dp).clip(RoundedCornerShape(50)).background(track).padding(3.dp),
                contentAlignment = if (switch) Alignment.CenterEnd else Alignment.CenterStart) {
                Box(Modifier.size(16.dp).clip(RoundedCornerShape(50)).background(knob))
            }
        } else if (value != null) Text(value, color = dim, fontSize = 15.sp, maxLines = 1, modifier = Modifier.padding(start = 16.dp))
    }
}

@Composable
private fun Title(t: String) = Text(t, color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))

/** Installed add-ons: add by phone (QR) or by URL, move up/down, remove. */
@Composable
fun Addons() {
    val store = App.instance.addons
    val list by store.all.collectAsState()
    store.addons.collectAsState().value // redraw On / Off
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var byPhone by remember { mutableStateOf(false) }
    fun install(u: String) { scope.launch {
        msg = "Installing…"
        msg = runCatching { store.install(u) }.fold({ url = ""; "Installed ${it.manifest.name}" }, { "Could not install: ${it.message}" })
    } }
    LazyColumn(Modifier.fillMaxSize().background(LocalLook.current.page), contentPadding = PaddingValues(48.dp)) {
        item {
            Title("Add-ons")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BasicTextField(
                    url, { url = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Palette.Text, fontSize = 15.sp),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {}),
                    modifier = Modifier.fillMaxWidth(0.55f).background(Palette.Card).padding(12.dp),
                    decorationBox = { inner -> if (url.isEmpty()) Text("Add-on URL (…/manifest.json)", color = Palette.TextDim, fontSize = 15.sp); inner() }
                )
                Button("Install", { install(url) })
                Button("Add by phone", { byPhone = true })
            }
            if (byPhone) com.lekathedon.visionlite.phone.PhoneBox("Add an add-on",
                listOf(com.lekathedon.visionlite.phone.PhoneForm.Field("Add-on URL", "https://…/manifest.json", note = "Any Stremio add-on or catalog list (IMDb, Trakt, MDBList…); stremio:// links work too")),
                onSave = { v -> byPhone = false; v.firstOrNull()?.takeIf { it.isNotBlank() }?.let { install(it) } },
                onCancel = { byPhone = false })
            if (msg.isNotEmpty()) Text(msg, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
        items(list, key = { it.base }) { a ->
            Row(Modifier.padding(top = 12.dp).fillMaxWidth(0.85f).background(Palette.Card).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("${a.manifest.name} ${a.manifest.version}", color = if (store.isOn(a.base)) Palette.Text else Palette.TextDim, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(a.base.substringBefore("/", a.base).let { a.base.removePrefix("https://").substringBefore('/') }, color = Palette.TextDim, fontSize = 12.sp)
                }
                Button(if (store.isOn(a.base)) "On" else "Off", { store.setOn(a.base, !store.isOn(a.base)) })
                Button("Up", { store.move(a.base, -1) })
                Button("Down", { store.move(a.base, 1) })
                Button("Remove", { store.remove(a.base) })
            }
        }
    }
}
