package com.lekathedon.visionlite.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.vector.ImageVector
import com.lekathedon.visionlite.ui.Icons
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
import com.lekathedon.visionlite.ui.Text
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
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
import com.lekathedon.visionlite.ui.Kit
import com.lekathedon.visionlite.ui.KitSwitch
import com.lekathedon.visionlite.ui.Style
import com.lekathedon.visionlite.ui.Theme
import com.lekathedon.visionlite.ui.skinPage
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.Screen
import kotlinx.coroutines.launch

private val Langs = listOf("" to "Any", "en" to "English", "ar" to "Arabic", "fr" to "French", "de" to "German", "es" to "Spanish",
    "it" to "Italian", "pt" to "Portuguese", "ru" to "Russian", "tr" to "Turkish", "sr" to "Serbian", "hr" to "Croatian",
    "hi" to "Hindi", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese")
/** Audio adds Vision's "Original language" (the title's own, from TMDB). */
private val NightOpts = listOf("off" to "Off", "light" to "Light", "medium" to "Medium", "strong" to "Strong")
private val DialogueOpts = listOf("0" to "Off", "3" to "+3 dB", "6" to "+6 dB", "9" to "+9 dB")
private val VolumeOpts = listOf("0" to "Off", "3" to "+3 dB", "6" to "+6 dB", "9" to "+9 dB", "12" to "+12 dB")
private val AudioLangs = listOf(Langs[0], com.lekathedon.visionlite.player.OriginalLang.ORIGINAL to "Original language") + Langs.drop(1)
/** Preferred dub: off = original audio stays the rule. */
private val DubLangs = listOf("" to "Off") + Langs.drop(1)

/** A pane line: a small heading, or a row (gets the pane's first-focus modifier when it is the first row). */
private sealed interface Item
private class Head(val text: String) : Item
private class Line(val content: @Composable (Modifier) -> Unit) : Item
/** A part that takes no focus (Overview's welcome card). */
private class Block(val content: @Composable () -> Unit) : Item

/** One settings page: its name in the rail, a plain sentence on what it holds, a one-line summary of how it is set. */
private class Page(val name: String, val about: String, val summary: @Composable () -> String, val items: List<Item>) {
    val icon: ImageVector = PageStyle[name]?.first ?: Icons.Info
    val tint: Color = PageStyle[name]?.second ?: Color(0xFF64748B)
}

/** Each page's icon and colour (rail tile, page header, section names), Echo style. */
private val PageStyle by lazy { mapOf(
    "Overview" to (Icons.Dashboard to Color(0xFF22D3EE)),
    "Where links come from" to (Icons.Link to Color(0xFF3B82F6)),
    "Tracking and lists" to (Icons.Bookmark to Color(0xFFEF4444)),
    "Player" to (Icons.Play to Color(0xFF10B981)),
    "Audio and subtitles" to (Icons.Subtitles to Color(0xFFF59E0B)),
    "Home and look" to (Icons.Home to Color(0xFF8B5CF6)),
    "Spoilers and trailers" to (Icons.Movie to Color(0xFFEC4899)),
    "Title pages" to (Icons.Info to Color(0xFF06B6D4)),
    "Recommendations" to (Icons.Star to Color(0xFFEAB308)),
    "Live TV and more" to (Icons.LiveTv to Color(0xFFF97316)),
    "This TV" to (Icons.Tv to Color(0xFF6366F1)),
    "Speed and network" to (Icons.Speed to Color(0xFF84CC16)),
    "Profiles" to (Icons.People to Color(0xFF14B8A6)),
    "About" to (Icons.Info to Color(0xFF64748B)),
) }

/** The brand's two colours (from the Nuvio Vision logo). */
private val BrandCyan = Color(0xFF22D3EE)
private val BrandViolet = Color(0xFF8B5CF6)
private val Glass = Color(0x12FFFFFF)
private val GlassEdge = Color(0x1FFFFFFF)
private val GlassFocus = Color(0x40FFFFFF)

private class PageBuilder {
    val items = mutableListOf<Item>()
    fun head(t: String) { items += Head(t) }
    fun row(c: @Composable (Modifier) -> Unit) { items += Line(c) }
    fun block(c: @Composable () -> Unit) { items += Block(c) }
}

private fun page(name: String, about: String, summary: @Composable () -> String, build: PageBuilder.() -> Unit) =
    Page(name, about, summary, PageBuilder().apply(build).items)

private fun label(options: List<Pair<String, String>>, v: String) = options.firstOrNull { it.first == v }?.second ?: options.first().second

private val AfrOpts = listOf("off" to "Off", "always" to "On")
private val EngineOpts = listOf("auto" to "Auto", "exo" to "ExoPlayer", "mpv" to "MPV")
private val SkipOpts = listOf("button" to "Show a button", "auto" to "Skip by itself", "off" to "Off")
private val ControlsSizeOpts = listOf("60" to "Smallest (60%)", "70" to "Small (70%)", "80" to "Medium (80%)", "90" to "Large (90%)", "100" to "Largest (100%)")
private val RewindOpts = listOf("0" to "Off", "3" to "3 s", "5" to "5 s", "10" to "10 s")
private val SubsWhenOpts = listOf("foreign" to "Foreign audio only", "always" to "Always", "off" to "Off")
private val SeekThumbOpts = listOf("hd" to "Up to 1080p", "all" to "All, including 4K")
private val SubSizeOpts = listOf("s" to "Small", "m" to "Medium", "l" to "Large", "xl" to "Extra large")
private val SubHdrBrightOpts = listOf("30" to "Dim", "58" to "Normal (Vision)", "80" to "Bright", "100" to "Full")
private val SortOpts = listOf("addon" to "Add-on order", "quality" to "Best quality", "biggest" to "Biggest file", "smallest" to "Smallest file")
private val QualityOpts = listOf("0" to "Any", "2160" to "4K", "1080" to "1080p", "720" to "720p")
private val SizeOpts = listOf("0" to "No limit", "5" to "5 GB", "10" to "10 GB", "20" to "20 GB", "40" to "40 GB", "80" to "80 GB")
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
        block { Welcome(p, nav) }
        head("To do")
        row { m ->
            watchServices()
            val list = toDos(nav, p)
            Column {
                if (list.isEmpty()) Link("You're all set", "Links, tracking and this TV are ready", m, value = "✓") {}
                list.forEachIndexed { i, t -> Link(t.title, t.sub, if (i == 0) m else Modifier, value = "Fix") { t.go() } }
            }
        }
        head("Every page")
        row { m ->
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                pages.drop(1).chunked(3).forEachIndexed { r, line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        line.forEachIndexed { c, pg ->
                            val i = r * 3 + c
                            PageTile(pg, if (i == 0) m else Modifier, Modifier.weight(1f)) { picked = i + 1; jumpPane = true }
                        }
                        repeat(3 - line.size) { Box(Modifier.weight(1f)) }
                    }
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
        row { m ->
            val tbTick = com.lekathedon.visionlite.tb.TbSources.tick.collectAsState().value
            val bad = remember(tbTick) { com.lekathedon.visionlite.tb.TbSources.problems() }
            Link("Vision TB", if (bad > 0) "$bad source(s) paused: open to edit or remove" else "TorBox-cached links straight from the torrent sites, plus your add-ons", m,
                value = if (p.visionTb.flow.collectAsState().value) "On" else "Off") { nav.push(Screen.VisionTb) } }
        head("Add-ons")
        row { m -> Link("Add-ons", "Add by phone, turn on or off, change the order", m, value = "${addonsOn()} on") { nav.push(Screen.Addons) } }
        head("Which links you see")
        row { m -> Choice("Sort links", "", p.streamSort, SortOpts, m) }
        row { m -> Choice("Highest quality", "Links above this are hidden", p.maxQuality, QualityOpts, m) }
        row { m -> Choice("File size limit", "Bigger links are hidden", p.sizeLimit, SizeOpts, m) }
        row { m -> Toggle("Hide torrent-only links", "Links that need a debrid account or add-on to play", p.hideTorrents, m) }
        row { m -> Toggle("Hide cinema recordings", "CAM and TS copies", p.hideCam, m) }
        head("Autoplay")
        row { m -> Toggle("Smart autoplay", "Plays the right episode, 4K first, then the biggest file. Off until Vision TB is autoplay safe", p.autoplay, m) }
        row { m -> Toggle("IPTV VOD also in Vision TB", "Off (default): VOD stays separate, on the title page's Play with VOD button. On: VOD copies also join Vision TB's links", p.vodInTb, m) }
        head("Debrid accounts (torrent links from your add-ons)")
        row { m -> ServiceRow("realdebrid", nav, m) }
        row { m -> ServiceRow("alldebrid", nav, m) }
        row { m -> ServiceRow("premiumize", nav, m) }
        head("Your media servers")
        row { m -> ServiceRow("plex", nav, m) }
        row { m -> ServiceRow("jellyfin", nav, m) }
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
        head("While playing")
        row { m -> Toggle("Quiet playback", "Closes other apps 4 s into a title so the box gives it everything. With root they are force-stopped", p.quietPlayback, m) }
        row { m -> Toggle("Rate after watching", "Asks for a Trakt rating when you finish a film or a show's episode; it steers your recommendations", p.rateAfter, m) }
        head("Picture, sound and engine")
        row { m -> Choice("Controls size", "How big the player's buttons, bars and panels are (films, shows and Live TV)", p.controlsSize, ControlsSizeOpts, m) }
        row { m -> Toggle("True black bars", "Bars around the film are the TV's own black, not the app's grey-ish black in HDR/DV (Vision)", p.blackBars, m) }
        row { m -> Link("This TV", "Dolby Vision, frame rate, passthrough, engine and buffer are set for this TV", m) { picked = pages.indexOfFirst { it.name == "This TV" }; jumpPane = true } }
    }
    pages += page("Audio and subtitles", "Languages, when subtitles show, how they look", {
        "${label(AudioLangs, p.audioLang.flow.collectAsState().value)} · subtitles ${label(SubsWhenOpts, p.subsWhen.flow.collectAsState().value).lowercase()}"
    }) {
        head("Audio")
        row { m -> Choice("Audio language", "Original = the title's own language, never the dub", p.audioLang, AudioLangs, m) }
        row { m -> Choice("If that's missing", "Second audio language", p.audioLang2, AudioLangs, m) }
        row { m -> Choice("Preferred dub", "A copy dubbed in this language plays first, even over 4K (your VOD's dubbed films and series count)", p.dubLang, DubLangs, m) }
        head("Sound for TV speakers and headphones")
        row { m -> Choice("Night mode", "Quiet speech comes up, explosions are held down (decoded sound only, not a soundbar bitstream)", p.nightMode, NightOpts, m) }
        row { m -> Choice("Dialogue boost", "Voices louder: the centre channel goes up (5.1 / 7.1, or when mixed to stereo)", p.dialogueBoost, DialogueOpts, m) }
        row { m -> Choice("Volume boost", "For quiet films on small speakers; a limiter stops it clipping", p.volumeBoost, VolumeOpts, m) }
        row { m -> Link("Sound system", "Soundbar, TV speakers or headphones: This TV › Sound (headphones plugged in always get stereo)", m) { picked = pages.indexOfFirst { it.name == "This TV" }; jumpPane = true } }
        head("Subtitles")
        row { m -> Choice("Show subtitles", "Foreign only = none when the audio is already your language", p.subsWhen, SubsWhenOpts, m) }
        row { m -> Choice("Subtitle language", "", p.subLang, Langs, m) }
        row { m -> Choice("If that's missing", "Second subtitle language", p.subLang2, Langs, m) }
        row { m -> Toggle("Remove hearing-impaired tags", "Add-on subtitles: [sighs], (door creaks), JOHN: and lone ♪ taken out", p.subsStripSdh, m) }
        row { m -> Toggle("Auto-sync add-on subtitles", "Lined up with the file's own subtitles while it plays, section by section", p.subsAutoSync, m) }
        head("How they look")
        row { m -> Choice("Size", "", p.subSize, SubSizeOpts, m) }
        row { m -> Choice("Colour", "", p.subColor, com.lekathedon.visionlite.player.SubStyle.TextColors, m) }
        row { m -> Choice("Edge", "Shadow = old Vision's soft shadow", p.subEdge, com.lekathedon.visionlite.player.SubStyle.Edges, m) }
        row { m -> Choice("Outline colour", "For the Outline edge", p.subOutlineColor, com.lekathedon.visionlite.player.SubStyle.OutlineColors, m) }
        row { m -> Choice("Background", "", p.subBack, com.lekathedon.visionlite.player.SubStyle.Backgrounds, m) }
        row { m -> Toggle("Bold", "", p.subBold, m) }
        row { m -> Choice("Position", "How high above the bottom", p.subPos, com.lekathedon.visionlite.player.SubStyle.Positions, m) }
        row { m -> Toggle("HDR and Dolby Vision subtitles", "White and dimmed on HDR/DV films, so they don't glare (Vision)", p.subHdr, m) }
        row { m -> Choice("HDR subtitle brightness", "", p.subHdrBright, SubHdrBrightOpts, m) }
        row { m -> Toggle("Seek thumbnails", "Nuvio Arc's: pictures of the spots either side while you seek, made in the background and kept per title (ExoPlayer)", p.seekThumbs, m) }
        row { m -> Choice("Seek thumbnails for", "", p.seekThumbsMode, SeekThumbOpts, m) }
        row { m -> Toggle("Content warnings at the start", "IMDb's parents guide top left for 15 s, as old Vision", p.ageRating, m) }
        row { m -> Toggle("Styled anime subtitles", "Anime subtitles keep their own fonts, colours and places (libass)", p.libass, m) }
    }
    pages += page("Home and look", "Rows, posters, clock, background", {
        "${com.lekathedon.visionlite.ui.Style.of(p.style.flow.collectAsState().value).label} · ${com.lekathedon.visionlite.ui.Skin.of(p.skin.flow.collectAsState().value).label}"
    }) {
        head("Look and skin")
        row { m -> Choice("Look", "Fonts, shapes, buttons and switches past Home (Home, its pill and the clock keep theirs)", p.style,
            com.lekathedon.visionlite.ui.Style.entries.map { it.key to "${it.label} · ${it.about}" }, m) }
        row { m -> Choice("Skin", "The app's colours, Home included", p.skin,
            com.lekathedon.visionlite.ui.Skin.entries.map { it.key to when (it) {
                com.lekathedon.visionlite.ui.Skin.TINT -> "Tinted · colours from the title's art"
                com.lekathedon.visionlite.ui.Skin.OLED -> "OLED black · pure black pages"
                else -> it.label } }, m) }
        row { m -> Toggle("Home follows the look", "Home's fonts, colours, pills and focus in the look and skin (sizes stay); off = Home's own look", p.homeLook, m) }
        row { m -> Choice("Button size", "Buttons, icons and switches past Home", p.uiSize,
            com.lekathedon.visionlite.ui.Theme.sizes.map { (k, l) -> k to if (k == "small") "$l · default" else l }, m) }
        head("Home hubs")
        row { m -> HubToggle("Films & TV", "Films and series: Discover, Search, Library, Calendar, Cloud", "NUVIO", p.hubFilms, m) }
        row { m -> HubToggle("Anime", "Its own hub (Settings › Anime: Separate hub)", "ANIME", p.hubAnime, m) }
        row { m -> HubToggle("IPTV", "Live TV, Sports, VOD and Recordings", "IPTV", p.hubIptv, m) }
        head("Rows")
        row { m -> Link("Arrange rows", "Turn rows on or off, change the order, refresh", m) { nav.push(Screen.Rows) } }
        row { m -> Link("Your interests", "The genres you watch most (learned) and the ones you like: each gets a \"Because you like\" row", m) { nav.push(Screen.Interests) } }
        row { m -> AnimeModeRow(p, m) }
        row { m -> Link("Taste questions", "The first-start questions again: titles, genres, people and anime you like", m) { nav.push(Screen.TasteStart) } }
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
        row { m ->
            val rows by com.lekathedon.visionlite.home.BillboardRows.rows.collectAsState()
            Choice("Billboard titles", "Which row feeds the big billboard on Home", p.billboardSource,
                listOf("" to "Continue watching, then first row", "random" to "A random row each visit") + rows, m)
        }
        row { m -> Choice("App language", "Menus and buttons; titles and plots keep their own language", p.appLang,
            listOf("" to "This TV's language", "en" to "English") + com.lekathedon.visionlite.ui.Lang.bundled, m) }
        row { m -> Toggle("Clock", "Time in the top bar", p.clock, m) }
        row { m -> Choice("Screensaver", "Backdrops from your rows after no key is pressed for a while", p.screensaverMin,
            listOf("0" to "Off", "2" to "2 minutes", "5" to "5 minutes", "10" to "10 minutes", "20" to "20 minutes"), m) }
    }
    pages += page("Spoilers and trailers", "What stays hidden, when trailers play", {
        val blur = p.blurUnwatched.flow.collectAsState().value
        val plots = p.hidePlots.flow.collectAsState().value
        val tr = p.trailers.flow.collectAsState().value
        val wait = label(DelayOpts, p.trailerDelay.flow.collectAsState().value)
        val sp = when { blur && plots -> "Pictures and plots hidden"; blur -> "Pictures hidden"; plots -> "Plots hidden"; else -> "Spoilers shown" }
        "$sp · ${if (tr) "trailers wait $wait" else "no trailers"}"
    }) {
        head("Spoilers")
        row { m -> Toggle("Hide episode pictures", "Unwatched episodes' pictures stay blurred until watched, here and in Continue watching", p.blurUnwatched, m) }
        row { m -> Toggle("Hide episode plots", "Unwatched episodes' plots stay hidden until watched, here and in Continue watching", p.hidePlots, m) }
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
    pages += page("Recommendations", "Picks, Love / Not for me, adult filter, AI", {
        watchServices()
        val min = p.recMinImdb.flow.collectAsState().value
        val ai = Integrations.driver(Integrations.Group.AI)?.name
        listOfNotNull(if (min == "0") "Any rating" else "IMDb $min+", ai?.let { "AI: $it" }).joinToString(" · ")
    }) {
        head("Picks")
        row { m -> Choice("IMDb minimum", "Nothing rated lower is recommended", p.recMinImdb, ImdbMinOpts, m) }
        com.lekathedon.visionlite.anime.Hub.entries.forEach { hub -> row { m ->
            val pool by com.lekathedon.visionlite.recs.Recs.pool(hub).collectAsState()
            val busy by com.lekathedon.visionlite.recs.Recs.building(hub).collectAsState()
            val status = when {
                busy -> "Building…"
                pool == null -> "Not built yet · OK builds now"
                pool!!.seeds == 0 -> "Nothing to learn from yet"
                else -> "${pool!!.picks.size} picks from ${pool!!.seeds} titles · OK rebuilds"
            }
            Link("Picks · ${hub.label}", status, m) { if (!busy) com.lekathedon.visionlite.recs.Recs.rebuildNow(hub) }
        } }
        row { m -> Link("Start over", "Forgets the picks; keeps Love, Like and Not for me", m) { com.lekathedon.visionlite.recs.Recs.reset() } }
        row { m ->
            val n = com.lekathedon.visionlite.recs.Recs.changed.collectAsState().value.let { com.lekathedon.visionlite.recs.Recs.feedback() }
            Link("Your Love / Like / Not for me / Watched", "${n.count { it.love }} loved · ${n.count { it.like && !it.love && !it.watched }} liked · ${n.count { !it.like }} not for me · ${n.count { it.watched }} watched · hold OK on any title to change", m, value = "") {}
        }
        head("Leave out")
        row { m ->
            val lo by p.leaveOut.flow.collectAsState()
            val l = remember(lo) { com.lekathedon.visionlite.recs.LeaveOut.lists() }
            val n = l.genres.size + l.langs.size + l.regions.size
            Link("Leave out genres, languages and countries", "Never shown anywhere: no row, tile, page or pick. Only Search finds them, marked Left out", m,
                value = if (n == 0) "Nothing" else "$n left out") { nav.push(Screen.LeaveOut) }
        }
        head("Hide adult titles")
        row { m -> Toggle("Hide adult titles", "Explicit films (9 Songs, softcore, porn) leave every row, page, search and pick; R-rated stays. Each title is checked once", p.hideAdult, m) }
        head("AI (free tiers): helps every recommendation, no row of its own")
        row { m -> ServiceRow("gemini", nav, m) }
        row { m -> ServiceRow("groq", nav, m) }
        row { m -> ServiceRow("mistral", nav, m) }
        row { m -> DriverRow(Integrations.Group.AI, "Asked first", m) }
        row { m -> AiModeRow(m) }
    }
    pages += page("Live TV and more", "IPTV playlists and sports", {
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
        row { m -> Choice("Update channels, VOD and guide", "Downloaded once in full; after that a quiet update at 05:00 changes only what the provider changed",
            p.iptvUpdateHours, listOf("12" to "Every 12 hours", "24" to "Every day", "48" to "Every 2 days"), m) }
        row { m -> Toggle("Update on app start", "Also update every time the app starts (heavier; off like TiviMate)", p.iptvUpdateOnStart, m) }
        row { m -> Choice("Live rewind", "Pause and rewind live channels: what you watch is kept on disk (never more than 30 % of free space; emptied on channel change)",
            p.liveRewind, listOf("0" to "Off", "15" to "15 minutes", "30" to "30 minutes", "60" to "1 hour", "120" to "2 hours"), m) }
        head("Remote buttons in Live TV")
        com.lekathedon.visionlite.player.LiveKeys.Button.entries.forEach { b ->
            row { m ->
                p.liveKeys.flow.collectAsState().value
                ChoiceOf(b.label, "Default: ${b.default.label}", com.lekathedon.visionlite.player.LiveKeys.get(b).name,
                    com.lekathedon.visionlite.player.LiveKeys.Action.entries.map { it.name to it.label }, m) { pick ->
                    com.lekathedon.visionlite.player.LiveKeys.set(b, com.lekathedon.visionlite.player.LiveKeys.Action.valueOf(pick)) }
            }
        }
        row { m -> Link("Reset all buttons", "Back to the defaults (TiviMate's layout, Left / Right rewind)", m, value = "Reset") { com.lekathedon.visionlite.player.LiveKeys.reset() } }
        head("Sports")
        row { m -> Link("My teams", "Follow teams and leagues", m) { nav.push(Screen.SportsTeams) } }
        row { m -> SportsSwitch("Remind me of my teams' games", "A card a few minutes before they start", m, { it.remindMyTeams }) { s, v -> s.copy(remindMyTeams = v) } }
        row { m ->
            var v by remember { mutableStateOf(com.lekathedon.visionlite.sports.Sports.settings.remindMinutes.toString()) }
            ChoiceOf("Remind me", "How long before the start", v, RemindOpts, m) { pick -> v = pick
                com.lekathedon.visionlite.sports.SportsMine.saveSettings(com.lekathedon.visionlite.sports.Sports.settings.copy(remindMinutes = pick.toInt())) }
        }
        row { m -> SportsSwitch("Switch to the game by itself", "When a reminder is up: its best channel a minute before the start (10 s to cancel)", m, { it.autoSwitch }) { s, v -> s.copy(autoSwitch = v) } }
        row { m -> SportsSwitch("Scoring alerts", "A card when a team you follow scores (waits 30 s so it never beats your stream)", m, { it.scoringAlerts }) { s, v -> s.copy(scoringAlerts = v) } }
        row { m -> SportsSwitch("Hide scores", "No spoilers: scores stay hidden until you open a game", m, { it.hideScores }) { s, v -> s.copy(hideScores = v) } }
        row { m -> SportsSwitch("Only games on my channels", "Leave out games none of your channels show (your teams always show)", m, { it.onlyOnMyChannels }) { s, v -> s.copy(onlyOnMyChannels = v) } }
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
    pages += page("Speed and network", "Set for this TV and internet: DNS, scrolling, how the app closes; tests and cache", {
        tvWatch()
        val f = DeviceCheck.saved()
        val mine = f?.let { DeviceCheck.choices(it).filter { c -> c.section == "Speed and network" && DeviceCheck.current(c.key) != c.recommended } }
        when {
            f == null || f.dnsMs.isEmpty() -> "Not checked yet (This TV)"
            mine.isNullOrEmpty() -> "Set for this TV and internet"
            else -> "${mine.size} changed by you"
        }
    }) {
        // This TV's check chooses these per box and per internet (and again when either changes, with Let the app
        // decide); the rows below stay for doing it by hand.
        head("Chosen for this TV and internet")
        row { m ->
            tvWatch()
            val f = DeviceCheck.saved()
            val list = f?.let { DeviceCheck.choices(it).filter { c -> c.section == "Speed and network" } }.orEmpty()
            val diff = list.filter { DeviceCheck.current(it.key) != it.recommended }
            val sub = when {
                f == null || f.dnsMs.isEmpty() -> "This TV's check hasn't measured this internet yet: Check again there"
                else -> list.joinToString("  ·  ") { c ->
                    val cur = DeviceCheck.current(c.key)
                    "${c.title.substringBefore(" (")}: ${c.label(c.recommended).substringBefore(" · ")}" +
                        if (cur != c.recommended) " (you: ${c.label(cur).substringBefore(" · ")})" else ""
                } + (if (p.tvAuto.value) "\nThe app keeps these right when the TV, box or internet changes" else "")
            }
            Link(if (diff.isEmpty()) "Set by This TV" else "Use what This TV chose", sub, m,
                value = if (diff.isEmpty()) "This TV ›" else "${diff.size} change${if (diff.size == 1) "" else "s"}") {
                if (diff.isEmpty() || f == null) { picked = pages.indexOfFirst { it.name == "This TV" }; jumpPane = true }
                else diff.forEach { DeviceCheck.pick(it.key, it.recommended, it.recommended) }
            }
        }
        head("Scrolling")
        row { m -> Toggle("Fast horizontal navigation", "Held Left / Right moves quicker along the rows", p.fastHorizontal, m) }
        row { m ->
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val names = remember { com.lekathedon.visionlite.data.QuietA11y.labels(ctx) }
            val on by p.quietA11y.flow.collectAsState()
            val sub = when {
                names.isEmpty() -> "No accessibility service is on: nothing to do here"
                com.lekathedon.visionlite.data.QuietA11y.quiet(ctx) -> "On for ${names.joinToString(", ")}: the app's screen isn't described to it, so scrolling is smoother"
                on -> "Not used: ${names.joinToString(", ")} (a screen reader or a service that reads the screen keeps the app described)"
                else -> "Off: ${names.joinToString(", ")} gets every screen described"
            }
            Toggle("Smoother with key remappers", sub, p.quietA11y, m)
        }
        head("DNS")
        row { m -> Choice("Who looks up addresses", "For add-ons, images, debrid and the player. Pick a service if your provider blocks or changes addresses; if it can't be reached, the box's own is used",
            p.dns, com.lekathedon.visionlite.data.SwitchDns.SERVICES.map { it.key to it.name }, m) }
        row { m ->
            var res by remember { mutableStateOf<String?>(null) }
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            Link("Test DNS", res ?: "Looks up TMDB and Cinemeta with the chosen DNS", m, value = if (res == "…") "…" else "Run") {
                res = "…"; scope.launch { res = com.lekathedon.visionlite.data.SwitchDns.test() }
            }
        }
        head("Network tests")
        row { m ->
            var res by remember { mutableStateOf<String?>(null) }
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            Link("Speed test", res ?: "Latency and about 10 s of download from Cloudflare (up to 100 MB)", m, value = if (res == "Testing…") "…" else "Run") {
                if (res != "Testing…") { res = "Testing…"; scope.launch { res = com.lekathedon.visionlite.data.NetTests.speed() } }
            }
        }
        row { m ->
            var res by remember { mutableStateOf<String?>(null) }
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            Link("Test the last played link", res ?: "Reads the link you played last for 10 s and says if this connection keeps up with it", m,
                value = if (res == "Testing…") "…" else "Run") {
                if (res != "Testing…") { res = "Testing…"; scope.launch { res = com.lekathedon.visionlite.data.NetTests.lastLink() } }
            }
        }
        head("Cache")
        row { m ->
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            var size by remember { mutableStateOf<Long?>(null) }
            androidx.compose.runtime.LaunchedEffect(Unit) { size = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                coil3.SingletonImageLoader.get(ctx).diskCache?.size } }
            Link("Clear image cache", "Posters and backdrops load fresh (they come back as you browse)", m,
                value = size?.let { "%.0f MB".format(it / 1e6) } ?: "…") {
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val l = coil3.SingletonImageLoader.get(ctx); l.memoryCache?.clear(); l.diskCache?.clear(); size = l.diskCache?.size
                }
            }
        }
        head("Leaving the app")
        row { m -> Toggle("Ask before leaving", "Back on Home asks first", p.exitConfirm, m) }
        row { m -> Toggle("Close fully", "Leaving ends the app too, so its memory goes back to the box at once (next start is a cold start). Not while recording or playing music", p.fullExit, m) }
    }
    pages += page("Profiles", "Who uses this box: each profile keeps its own accounts, library, picks and settings", {
        com.lekathedon.visionlite.data.Profiles.version.collectAsState().value
        val all = com.lekathedon.visionlite.data.Profiles.all()
        if (all.size == 1) "Just ${all[0].name}" else "${all.size} profiles · ${com.lekathedon.visionlite.data.Profiles.current().name} now"
    }) {
        head("Profiles")
        row { m ->
            com.lekathedon.visionlite.data.Profiles.version.collectAsState().value
            val now = com.lekathedon.visionlite.data.Profiles.currentId()
            Column {
                com.lekathedon.visionlite.data.Profiles.all().forEachIndexed { i, pr ->
                    Link(pr.name, listOfNotNull(if (pr.id == now) "In use" else null, if (pr.locked) "PIN" else null).joinToString(" · "),
                        if (i == 0) m else Modifier) { nav.push(Screen.ProfileEdit(pr.id)) }
                }
            }
        }
        row { m -> Link("Add a profile", "Starts empty: its own sign-ins, library and settings (This TV and data keys are shared)", m, value = "+") {
            nav.push(Screen.ProfileEdit(com.lekathedon.visionlite.data.Profiles.add("").id)) } }
        row { m -> Toggle("Keep my setup in my Nuvio account", "Keys, sign-ins, profiles and rows come back on any device you sign in to Nuvio on (This TV's picture and sound settings stay on each box)",
            App.instance.prefs.cloudSetup, m) }
        row { m ->
            var note by remember { mutableStateOf("") }
            val last = com.lekathedon.visionlite.integrations.CloudSetup.lastSync()
            Link("Sync my setup now", if (last == 0L) "Not synced yet" else "Last synced " + android.text.format.DateUtils.getRelativeTimeSpanString(last), m, value = note.ifBlank { "›" }) {
                scope.launch {
                    if (!com.lekathedon.visionlite.integrations.SignIns.signedIn("nuvio")) { note = "Sign in to Nuvio first"; return@launch }
                    note = "…"
                    note = if (runCatching { com.lekathedon.visionlite.integrations.CloudSetup.push() }.getOrDefault(false)) "Synced" else "Couldn't reach Nuvio"
                }
            }
        }
        row { m ->
            var note by remember { mutableStateOf("") }
            Link("Bring in Nuvio's profiles", "Your Nuvio account's profiles as Lite profiles: name, picture, and each follows its own Nuvio profile", m,
                value = note.ifBlank { "›" }) {
                scope.launch {
                    note = "…"
                    val faces = runCatching { com.lekathedon.visionlite.integrations.SignIns.nuvioFaces() }.getOrDefault(emptyList())
                    if (faces.isEmpty()) { note = "Sign in to Nuvio first"; return@launch }
                    val have = com.lekathedon.visionlite.data.Profiles.all().map { it.name.lowercase() }.toSet()
                    var n = 0
                    for (f in faces) {
                        if (f.name.lowercase() in have) continue
                        val pr = com.lekathedon.visionlite.data.Profiles.add(f.name, f.color.ifBlank { "#1E88E5" })
                        com.lekathedon.visionlite.data.Profiles.putFor(pr.id, "si_nuvio_profile" to f.index.toString(), "si_nuvio_profile_name" to f.name,
                            "si_nuvio_avatar" to f.picture, "si_nuvio_avatar_color" to f.color)
                        if (f.picture.isNotBlank()) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.lekathedon.visionlite.data.Profiles.setPicture(pr.id, f.picture) }
                        n++
                    }
                    note = if (n == 0) "All here" else "$n added"
                }
            }
        }
        head("Start")
        row { m -> Toggle("Ask who's watching", "At start, when there's more than one profile (a PIN is always asked)", p.askProfile, m) }
    }
    pages += page("About", "Version, report a problem, cache, first-start setup", { "Version ${com.lekathedon.visionlite.BuildConfig.VERSION_NAME}" }) {
        head("Help")
        row { m -> Link("Report a problem", "Sends this box's details and the app's log to the Nuvio Vision team (no keys or logins)", m) { nav.push(Screen.Report()) } }
        head("Nuvio Vision")
        row { m -> Link("Version", "", m, value = com.lekathedon.visionlite.BuildConfig.VERSION_NAME) {} }
        row { m -> Link("Clear image cache", "Frees memory and disk; posters load again", m, value = cleared) { scope.launch { cleared = clearImages() } } }
        row { m -> Link("Run the first-start setup again", "One page per service, then back to Home", m) { Integrations.setupDone.value = false; nav.pop() } }
    }

    val sel = if (picked >= 0) picked else pages.indexOfFirst { it.name == start }.coerceAtLeast(0)
    val groupReq = remember { List(pages.size) { FocusRequester() } }
    var inPane by remember { mutableStateOf(false) }
    // Entering a page (Right / OK on the rail): its row you were last on, else its first. The page is scrolled to that
    // row first: a row scrolled out of the list isn't there to take focus, which locked Right out (user 2026-11-25:
    // after going deep into a page and back to the rail, Right did nothing until Settings was reopened).
    val paneState = androidx.compose.foundation.lazy.rememberLazyListState()
    val rowReq = remember { HashMap<String, FocusRequester>() }
    fun rowReqOf(page: Int, i: Int) = rowReq.getOrPut("$page:$i") { FocusRequester() }
    val lastRow = remember { HashMap<Int, Int>() }
    val enterScope = androidx.compose.runtime.rememberCoroutineScope()
    fun enterPane() {
        val page = sel
        val rows = pages[page].items
        val i = lastRow[page]?.takeIf { rows.getOrNull(it) is Line } ?: rows.indexOfFirst { it is Line }
        if (i < 0) return
        enterScope.launch {
            // +1: the page's heading is item 0.
            val shown = paneState.layoutInfo.visibleItemsInfo.any { it.index == i + 1 }
            if (!shown) paneState.scrollToItem((i + 1 - 1).coerceAtLeast(0))
            repeat(4) {
                androidx.compose.runtime.withFrameNanos { }
                if (runCatching { rowReqOf(page, i).requestFocus() }.isSuccess) return@launch
            }
        }
    }
    // A new page on the rail starts at its top.
    LaunchedEffect(sel) { if (!inPane) paneState.scrollToItem(0) }
    LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; runCatching { groupReq[sel].requestFocus() } }
    LaunchedEffect(jumpPane) { if (jumpPane) { androidx.compose.runtime.withFrameNanos { }; enterPane(); jumpPane = false } }
    androidx.activity.compose.BackHandler(enabled = inPane) { runCatching { groupReq[sel].requestFocus() } }
    val g = pages[sel]
    val skin = Theme.skin
    val oled = !skin.tinted
    val style = Kit.style
    // Tinted: each settings page takes its own colour as the accent (no title art here).
    LaunchedEffect(sel, skin) { if (skin.tinted) Theme.artAccent = Kit.lerp(g.tint, Color.White, 0.25f) }
    val wash by androidx.compose.animation.animateColorAsState(g.tint.copy(alpha = 0.16f), androidx.compose.animation.core.tween(500), label = "wash")
    Box(Modifier.fillMaxSize().then(if (oled) Modifier.skinPage(skin) else Modifier.background(Color(0xFF05070D))).drawBehind {
        if (oled) return@drawBehind
        drawRect(Brush.radialGradient(listOf(BrandCyan.copy(alpha = 0.14f), Color.Transparent), Offset(size.width * 0.08f, 0f), size.width * 0.55f))
        drawRect(Brush.radialGradient(listOf(BrandViolet.copy(alpha = 0.16f), Color.Transparent), Offset(size.width * 0.95f, size.height), size.width * 0.6f))
        drawRect(Brush.radialGradient(listOf(wash, Color.Transparent), Offset(size.width * 0.7f, 0f), size.width * 0.5f))
    }) {
    Column(Modifier.fillMaxSize().padding(start = 48.dp, top = 28.dp, end = 48.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            com.lekathedon.visionlite.ui.VisionWordmark(30.dp)
            Box(Modifier.padding(horizontal = 16.dp).size(1.dp, 22.dp).background(Color(0x40FFFFFF)))
            Text(if (style == Style.MARQUEE) "SETTINGS" else "Settings", color = Palette.Text, fontFamily = Kit.display,
                fontSize = if (style == Style.MARQUEE) 22.sp else 26.sp, fontWeight = FontWeight.ExtraBold,
                letterSpacing = if (style == Style.MARQUEE) 4.sp else (-0.3).sp, modifier = Modifier.weight(1f))
            Clock()
        }
    Row(Modifier.fillMaxSize()) {
        // Focus stays where you are (user 2026-11-22): the rail leaves only to the right (into the page), the page only
        // to the left (back to the rail); Up/Down at either end stop there instead of jumping to another page.
        Column(Modifier.width(300.dp).fillMaxHeight()
            .focusProperties { onExit = {
                when (requestedFocusDirection) {
                    androidx.compose.ui.focus.FocusDirection.Right -> { cancelFocusChange(); enterPane() }
                    androidx.compose.ui.focus.FocusDirection.Up, androidx.compose.ui.focus.FocusDirection.Down,
                    androidx.compose.ui.focus.FocusDirection.Left -> cancelFocusChange()
                    else -> {}
                }
            } }
            .focusGroup()
            .verticalScroll(rememberScrollState())) {
            pages.forEachIndexed { i, pg ->
                GroupItem(pg, i == sel, Modifier.focusRequester(groupReq[i])
                    .onFocusChanged { if (it.isFocused) picked = i }
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { enterPane(); true } else false
                    }) { enterPane() }
            }
            Text("Changes save as you go", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 24.dp))
        }
        LazyColumn(state = paneState, modifier =
            Modifier.weight(1f).fillMaxHeight().padding(start = 36.dp)
                .onFocusChanged { inPane = it.hasFocus }
                .focusProperties { onExit = {
                    when (requestedFocusDirection) {
                        androidx.compose.ui.focus.FocusDirection.Left -> { cancelFocusChange(); runCatching { groupReq[sel].requestFocus() } }
                        androidx.compose.ui.focus.FocusDirection.Up, androidx.compose.ui.focus.FocusDirection.Down,
                        androidx.compose.ui.focus.FocusDirection.Right -> cancelFocusChange()
                        else -> {}
                    }
                } }
                .focusGroup(),
            contentPadding = PaddingValues(bottom = 48.dp)
        ) {
            item(key = "head$sel") {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(g, 56.dp)
                    Column(Modifier.weight(1f).padding(start = 18.dp)) {
                        com.lekathedon.visionlite.ui.DisplayText(g.name, if (style == Style.MARQUEE) 34.sp else 30.sp, maxLines = 1)
                        Text(g.about, color = Palette.TextDim, fontSize = 14.sp)
                    }
                    Text((if (style == Style.HALO) "" else "● ") + g.summary(), color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                        modifier = Modifier.padding(start = 16.dp).clip(RoundedCornerShape(if (style == Style.TONAL) 12.dp else 50.dp)).background(Kit.lerp(Kit.card, Kit.accent, 0.22f))
                            .border(1.dp, Kit.accent.copy(alpha = 0.45f), RoundedCornerShape(if (style == Style.TONAL) 12.dp else 50.dp)).padding(horizontal = 14.dp, vertical = 6.dp))
                }
            }
            g.items.forEachIndexed { i, it ->
                item(key = "$sel:$i") {
                    when (it) {
                        is Head -> GroupHead(it.text)
                        is Line -> it.content(Modifier.focusRequester(rowReqOf(sel, i)).onFocusChanged { f -> if (f.isFocused) lastRow[sel] = i })
                        is Block -> it.content()
                    }
                }
            }
        }
    }
    }
    }
}

/** The page's icon on a rounded tile in its colour. */
@Composable
private fun IconTile(g: Page, size0: androidx.compose.ui.unit.Dp) {
    val size = size0 * Kit.soft
    val style = Kit.style
    val c = if (Theme.skin.tinted) g.tint else Kit.accent
    Box(Modifier.size(size).clip(when (style) { Style.TONAL -> RoundedCornerShape(size * 0.36f); Style.MARQUEE -> RoundedCornerShape(size * 0.22f); Style.HALO -> CircleShape })
        .background(when (style) {
            Style.TONAL -> SolidColor(Kit.lerp(Kit.card, c, 0.3f))
            Style.MARQUEE -> SolidColor(Kit.card2)
            Style.HALO -> Brush.linearGradient(listOf(c, c.copy(alpha = 0.55f)))
        }), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Icon(g.icon, null, tint = when (style) { Style.TONAL -> c; Style.MARQUEE -> Palette.Text; Style.HALO -> Color.White }, modifier = Modifier.size(size * 0.56f))
    }
}

/** The time, top right as on Home. */
@Composable
private fun Clock() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val fmt = remember { android.text.format.DateFormat.getTimeFormat(ctx) }
    var now by remember { mutableStateOf(fmt.format(java.util.Date())) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(15_000); now = fmt.format(java.util.Date()) } }
    Text(now, color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
}

/** Overview's welcome card: who is watching, how many things need doing, the version. */
@Composable
private fun Welcome(p: Prefs, nav: Nav) {
    watchServices()
    com.lekathedon.visionlite.data.Profiles.version.collectAsState().value
    val me = com.lekathedon.visionlite.data.Profiles.current()
    val todo = toDos(nav, p).size
    Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
        .background(Brush.linearGradient(listOf(BrandCyan.copy(alpha = 0.16f), BrandViolet.copy(alpha = 0.16f))))
        .border(1.dp, GlassEdge, RoundedCornerShape(18.dp)).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        com.lekathedon.visionlite.home.ProfileFace(me.name, me.pic, me.color, 56.dp)
        Column(Modifier.weight(1f).padding(start = 18.dp)) {
            Text("Hi, ${me.name.ifBlank { "there" }}", color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(if (todo == 0) "Everything is set up" else "$todo thing${if (todo == 1) "" else "s"} to finish below", color = Palette.TextDim, fontSize = 14.sp)
        }
        Text("Version ${com.lekathedon.visionlite.BuildConfig.VERSION_NAME}", color = Palette.TextDim, fontSize = 13.sp)
    }
}

/** Overview's tile for one page: icon, name, how it is set now. */
@Composable
private fun PageTile(g: Page, focus: Modifier, modifier: Modifier, onClick: () -> Unit) {
    Focusable(onClick, modifier.then(focus).height(104.dp), shape = RoundedCornerShape(16.dp)) { f ->
        Column(Modifier.fillMaxSize().background(if (f) GlassFocus else Glass).border(1.dp, GlassEdge, RoundedCornerShape(16.dp)).padding(14.dp)) {
            IconTile(g, 30.dp)
            Text(g.name, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
            Text(g.summary(), color = Palette.TextDim, fontSize = 12.sp, maxLines = 1)
        }
    }
}

private val Good = Color(0xFF8FD18F)

/** Redraws when any device setting or the check's result changes. */
@Composable
private fun tvWatch() {
    val p = App.instance.prefs
    listOf(p.dv7.flow, p.afr.flow, p.engine.flow, p.buffer.flow, p.maxQuality.flow, p.sizeLimit.flow, p.tvFacts.flow).forEach { it.collectAsState().value }
    listOf(p.matchRes.flow, p.dv5To81.flow, p.stripHdr10Plus.flow, p.ptAc3.flow, p.ptEac3.flow, p.ptTruehd.flow, p.ptDts.flow, p.ptDtshd.flow, p.tvAuto.flow, p.dv5Mpv.flow, p.hdrOnSdr.flow, p.soundOut.flow).forEach { it.collectAsState().value }
    listOf(p.perfClass.flow).forEach { it.collectAsState().value }; p.trailers.flow.collectAsState().value
    p.dns.flow.collectAsState().value; p.tvMine.flow.collectAsState().value; listOf(p.quietA11y.flow, p.fastHorizontal.flow, p.fullExit.flow).forEach { it.collectAsState().value }
}

@Composable
private fun SectionHead(t: String) = GroupHead(t)

/** A group's heading in the look: Tonal a heavy title with an accent bar, Marquee tracked capitals, Halo small spaced caps. */
@Composable
private fun GroupHead(t: String) {
    when (Kit.style) {
        Style.TONAL -> Row(Modifier.padding(start = 4.dp, top = 26.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(4.dp)).background(Kit.accent))
            Text(t, color = Palette.Text, fontFamily = com.lekathedon.visionlite.ui.Fonts.tonalDisplay, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(start = 10.dp))
        }
        Style.MARQUEE -> com.lekathedon.visionlite.ui.Eyebrow(t, Modifier.padding(start = 4.dp, top = 26.dp, bottom = 6.dp))
        Style.HALO -> Text(t.uppercase(), color = Palette.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
            modifier = Modifier.padding(start = 12.dp, top = 22.dp, bottom = 4.dp))
    }
}

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
        Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(Kit.rowR)).background(Glass).border(1.dp, GlassEdge, RoundedCornerShape(Kit.rowR)).padding(horizontal = 18.dp, vertical = 12.dp)) {
            com.lekathedon.visionlite.device.findings(f).forEach { Text(it, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 1.dp)) }
        }
        val diff = DeviceCheck.differing(f)
        Link(if (diff.isEmpty()) "Everything is set right" else "Use all recommended",
            if (diff.isEmpty()) "Every setting below matches this TV" else diff.joinToString(" · ") { "${it.title}: ${it.label(it.recommended)}" }, Modifier,
            value = if (diff.isEmpty()) "✓" else "${diff.size} change${if (diff.size == 1) "" else "s"}") { note = "Used ${DeviceCheck.useRecommended(f, keepMine = false)} recommendations" }
        DeviceCheck.yours(f).takeIf { it.isNotEmpty() }?.let { y ->
            Text("Your choices (kept when the app re-checks): " + y.joinToString(" · ") { "${it.title}: ${it.label(DeviceCheck.current(it.key))}" },
                color = BrandCyan, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
        }
        val auto = p.tvAuto.value
        Focusable({ p.tvAuto.value = !auto; if (!auto) DeviceCheck.useRecommended(f) }, Modifier.padding(top = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(Kit.rowR)) { foc ->
            RowText("Let the app decide", "Uses the recommendations by itself, and again whenever you change TV or soundbar", null, foc, switch = auto)
        }
        DeviceCheck.choices(f).groupBy { it.section }.forEach { (section, list) ->
            SectionHead(section)
            list.forEach { c ->
                ChoiceOf(c.title, c.why, DeviceCheck.current(c.key), c.options, Modifier, recommended = c.recommended) { DeviceCheck.pick(c.key, it, c.recommended) }
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
    else if (!p.tvAuto.value) DeviceCheck.differing(f).filter { !DeviceCheck.isMine(it.key) }.let { d ->
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
            Focusable({ Integrations.setHelper(s, !on) }, Modifier.padding(top = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(Kit.rowR)) { f ->
                RowText("Also use ${s.name}", if (on) g.helperDoes else "Off: makes no calls", null, f, switch = on)
            }
        }
    }
}

@Composable
private fun GroupItem(g: Page, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val style = Kit.style
    Focusable(onClick, modifier.padding(vertical = 2.dp).fillMaxWidth(), shape = RoundedCornerShape(Kit.rowR)) { f ->
        Row(Modifier.fillMaxWidth().background(when {
                f && style == Style.TONAL -> Kit.lerp(Kit.card, Kit.accent, 0.22f)
                f && style == Style.HALO -> Kit.accent.copy(alpha = 0.9f)
                f -> Kit.card2
                selected -> Kit.card
                else -> Color.Transparent
            })
            .padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(g, 32.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                val ink = if (f && style == Style.HALO) Kit.onAccent else if (f || selected) Palette.Text else Palette.TextDim
                Text(g.name, color = ink, fontSize = 15.sp, fontWeight = if (f || selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                if (f || selected) Text(g.summary(), color = if (f && style == Style.HALO) Kit.onAccent.copy(alpha = 0.7f) else Palette.TextDim, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

private suspend fun clearImages(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val loader = coil3.SingletonImageLoader.get(App.instance)
    loader.memoryCache?.clear()
    loader.diskCache?.clear()
    "Cleared"
}

private val RemindOpts = listOf("0" to "At the start", "5" to "5 min before", "10" to "10 min before", "15" to "15 min before", "30" to "30 min before", "60" to "1 hour before")

/** A Sports setting (kept in Sports' own settings, as Vision). */
@Composable
private fun SportsSwitch(title: String, sub: String, modifier: Modifier, get: (com.lekathedon.visionlite.sports.SportsSettings) -> Boolean,
                         set: (com.lekathedon.visionlite.sports.SportsSettings, Boolean) -> com.lekathedon.visionlite.sports.SportsSettings) {
    var on by remember { mutableStateOf(get(com.lekathedon.visionlite.sports.Sports.settings)) }
    Focusable({ on = !on; com.lekathedon.visionlite.sports.SportsMine.saveSettings(set(com.lekathedon.visionlite.sports.Sports.settings, on)) },
        modifier.padding(top = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(Kit.rowR)) { f -> RowText(title, sub, null, f, switch = on) }
}

@Composable
private fun Link(title: String, sub: String, modifier: Modifier = Modifier, value: String = "›", onClick: () -> Unit) {
    Focusable(onClick, modifier.padding(top = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(Kit.rowR)) { f -> RowText(title, sub, value.ifEmpty { "›" }, f) }
}

/** One of Home's hubs on or off; the last one on can't be switched off (says so). */
@Composable
private fun HubToggle(title: String, sub: String, key: String, flag: Prefs.Flag, modifier: Modifier = Modifier) {
    val on by flag.flow.collectAsState()
    var badge by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(badge) { if (badge != null) { kotlinx.coroutines.delay(3_000); badge = null } }
    Focusable({
        badge = if (com.lekathedon.visionlite.anime.Hubs.setOn(key, !on)) "✓ Saved: ${if (!on) "On" else "Off"}" else "One hub has to stay on"
    }, modifier.padding(top = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(Kit.rowR)) { f ->
        RowText(title, sub, null, f, switch = on, badge = badge)
    }
}

@Composable
private fun Toggle(title: String, sub: String, flag: Prefs.Flag, modifier: Modifier = Modifier) {
    val on by flag.flow.collectAsState()
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(saved) { if (saved) { kotlinx.coroutines.delay(3_000); saved = false } }
    Focusable({ flag.value = !on; saved = true }, modifier.padding(top = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(Kit.rowR)) { f ->
        RowText(title, sub, null, f, switch = on, badge = if (saved) "✓ Saved: ${if (on) "On" else "Off"}" else null)
    }
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
    // Every pick is acknowledged on the row: "✓ Saved" for a few seconds; a pick away from the recommendation stays marked "Your choice".
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(saved) { if (saved) { kotlinx.coroutines.delay(3_000); saved = false } }
    Focusable({ open = true; reopened = true }, modifier.focusRequester(me).padding(top = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(Kit.rowR)) { f ->
        RowText(title, sub, shown, f,
            hint = recommended?.let { r -> if (r == current) "✓ Recommended" else "Recommended: " + (options.firstOrNull { it.first == r }?.second ?: r) },
            badge = when { saved -> "✓ Saved: $shown"; recommended != null && recommended != current -> "Your choice"; else -> null })
    }
    if (open) Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val reqs = remember(options) { List(options.size) { FocusRequester() } }
        val at = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
        LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; runCatching { reqs[at].requestFocus() } }
        com.lekathedon.visionlite.ui.Inside(true) {
        Column(Modifier.width(480.dp).clip(RoundedCornerShape(Kit.cardR + 4.dp)).background(Kit.panel)
            .border(1.dp, Kit.accent.copy(alpha = 0.5f), RoundedCornerShape(Kit.cardR + 4.dp)).padding(22.dp)) {
            com.lekathedon.visionlite.ui.DisplayText(title, 22.sp, maxLines = 1)
            if (sub.isNotEmpty()) Text(sub, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            LazyColumn(Modifier.padding(top = 10.dp).heightIn(max = 380.dp)) {
                items(options.size) { i ->
                    val (id, name) = options[i]
                    Focusable({ onPick(id); saved = true; open = false }, Modifier.focusRequester(reqs[i]).padding(top = 4.dp).fillMaxWidth(), shape = RoundedCornerShape(Kit.rowR)) { f ->
                        val halo = f && Kit.style == Style.HALO
                        Row(Modifier.fillMaxWidth().background(if (halo) Kit.accent else if (f) Kit.lerp(Kit.card2, Kit.accent, 0.2f) else if (id == current) Kit.card2 else Color.Transparent).padding(horizontal = 16.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(name, color = if (halo) Kit.onAccent else Palette.Text, fontSize = 16.sp, fontWeight = if (id == current) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                            if (id == recommended) Text("Recommended", color = Good, fontSize = 13.sp, modifier = Modifier.padding(end = 12.dp))
                            else if (id == current && recommended != null) Text("Your choice", color = BrandCyan, fontSize = 13.sp, modifier = Modifier.padding(end = 12.dp))
                            if (id == current) androidx.compose.material3.Icon(Icons.Check, null, tint = if (halo) Kit.onAccent else Kit.accent, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
        }
    }
}

/**
 * A settings row in the look: Tonal = coloured block, focused in the accent's tone; Marquee = hairline tile, focused a
 * shade lighter; Halo = plain list line that fills with the accent when focused. The value (or switch) sits right.
 */
@Composable
private fun RowText(title: String, sub: String, value: String?, focused: Boolean, switch: Boolean? = null, hint: String? = null, badge: String? = null) {
    val style = Kit.style
    val halo = style == Style.HALO && focused
    val fg = if (halo) Kit.onAccent else Palette.Text
    val dim = if (halo) Kit.onAccent.copy(alpha = 0.72f) else if (focused) Palette.Text.copy(alpha = 0.82f) else Palette.TextDim
    val shape = RoundedCornerShape(Kit.rowR)
    val bg = when (style) {
        Style.TONAL -> Modifier.background(if (focused) Kit.lerp(Kit.card, Kit.accent, 0.2f) else Kit.card)
        Style.MARQUEE -> Modifier.background(if (focused) Kit.card2 else Kit.card).border(1.dp, Kit.line, shape)
        Style.HALO -> if (focused) Modifier.background(Kit.accent.copy(alpha = 0.92f)) else Modifier.background(Color(0x0DFFFFFF))
    }
    Row(Modifier.fillMaxWidth().then(bg).padding(horizontal = 18.dp, vertical = if (style == Style.TONAL) 14.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = fg, fontSize = 16.sp, fontWeight = if (style == Style.HALO) FontWeight.Bold else FontWeight.SemiBold)
            if (sub.isNotEmpty()) Text(sub, color = dim, fontSize = 12.sp, maxLines = 2)
            if (hint != null || badge != null) Row {
                if (badge != null) Text(badge, color = if (halo) Kit.onAccent else Kit.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 10.dp))
                if (hint != null) Text(hint, color = if (halo) Kit.onAccent else Good, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        if (switch != null) KitSwitch(switch, focused, Modifier.padding(start = 16.dp))
        else if (value != null) when (style) {
            Style.TONAL -> Text(value, color = if (focused) Kit.onAccent else Kit.lerp(Palette.Text, Kit.accent, 0.4f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.padding(start = 16.dp).clip(RoundedCornerShape(12.dp)).background(if (focused) Kit.accent else Kit.tonal).padding(horizontal = 12.dp, vertical = 6.dp))
            Style.MARQUEE -> Text(value, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(start = 16.dp))
            Style.HALO -> Text(if (value == "›") value else "$value  ›", color = if (halo) Kit.onAccent else dim, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(start = 16.dp))
        }
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
    var prompt by remember { mutableStateOf<com.lekathedon.visionlite.addons.Addon?>(null) }
    fun install(u: String) { scope.launch {
        msg = "Installing…"
        msg = runCatching { store.install(u) }.fold({ url = ""; if (it.rowCatalogs.isNotEmpty()) prompt = it; "Installed ${it.manifest.name}" }, { "Could not install: ${it.message}" })
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
                // What it's used for in this profile: rows, details, links (AddonRoles).
                store.version.collectAsState().value
                com.lekathedon.visionlite.addons.AddonRoles.Role.values().forEach { r ->
                    val res = r.resources.first()
                    if (a.gives(res)) {
                        val on = com.lekathedon.visionlite.addons.AddonRoles.isOn(a.base, r)
                        Button((if (on) "✓ " else "✗ ") + r.label, { com.lekathedon.visionlite.addons.AddonRoles.set(a.base, r, !on) })
                    }
                }
                if (a.rowCatalogs.isNotEmpty()) Button("Choose rows", { prompt = a })
                Button("Up", { store.move(a.base, -1) })
                Button("Down", { store.move(a.base, 1) })
                Button("Remove", { store.remove(a.base) })
            }
        }
    }
    prompt?.let { a -> RowsPrompt(a) { prompt = null } }
}

/** How the AIs agree (moved here from Scout's page): Vote, each AI by its weight (OK cycles the weights), or one Boss decides. */
@Composable
private fun AiModeRow(modifier: Modifier) {
    val ai = com.lekathedon.visionlite.recs.AiPicks
    var tick by remember { mutableStateOf(0) }
    val on = remember(tick) { ai.configured() }
    val boss = remember(tick) { ai.boss() }
    if (on.isEmpty()) return
    Column(modifier) {
        val order = listOf<com.lekathedon.visionlite.recs.AiPicks.Ai?>(null) + on
        Link("How the AIs decide", if (boss == null) "Vote: each AI counts by its weight" else "Boss: ${boss.label} makes the final call",
            value = if (boss == null) "Vote" else "Boss") { ai.setBoss(order[(order.indexOf(boss) + 1) % order.size]); tick++ }
        if (boss == null) on.forEach { a ->
            val w = ai.weight(a)
            Link("${a.label} weight", "OK changes it", value = "×" + if (w % 1.0 == 0.0) w.toInt().toString() else w.toString()) {
                val ws = ai.WEIGHTS; ai.setWeight(a, ws[(ws.indexOf(w).coerceAtLeast(0) + 1) % ws.size]); tick++
            }
        }
    }
}


/**
 * After adding an add-on with rows (user 2026-11-23): which of its rows go on this profile's Home. All are ticked;
 * untick what you don't want. Turns add-on rows on for this profile; Settings › Home rows arranges them later.
 */
@Composable
fun RowsPrompt(a: com.lekathedon.visionlite.addons.Addon, onDone: () -> Unit) {
    val p = App.instance.prefs
    val cats = a.rowCatalogs
    fun key(c: com.lekathedon.visionlite.addons.CatalogDef) = "${a.manifest.id}/${c.type}/${c.id}"
    val hidden = remember { (p.rowsHidden.value.split('\n') + p.rowsHiddenAnime.value.split('\n')).toSet() }
    val picked = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>().apply {
        cats.forEach { c -> put(key(c), !(p.addonRows.value && key(c) in hidden)) }
    } }
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)), contentAlignment = Alignment.Center) {
        com.lekathedon.visionlite.ui.Inside(true) { LazyColumn(Modifier.fillMaxWidth(0.6f).fillMaxSize(0.9f)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(com.lekathedon.visionlite.ui.Kit.cardR))
            .background(com.lekathedon.visionlite.ui.Kit.card.copy(alpha = 0.98f))
            .border(1.dp, com.lekathedon.visionlite.ui.Kit.line, androidx.compose.foundation.shape.RoundedCornerShape(com.lekathedon.visionlite.ui.Kit.cardR))
            .padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("${a.manifest.name} has ${cats.size} rows", color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Add which to this profile's Home? Arrange them later in Settings › Home rows.", color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button("Add selected", {
                        val off = cats.map(::key).filter { picked[it] != true }.toSet(); val on = cats.map(::key).toSet() - off
                        fun upd(t: com.lekathedon.visionlite.data.Prefs.Text) { t.value = ((t.value.split('\n').filter { it.isNotEmpty() }.toSet() - on) + off).joinToString("\n") }
                        upd(p.rowsHidden); upd(p.rowsHiddenAnime)
                        if (on.isNotEmpty()) { p.addonRows.value = true; com.lekathedon.visionlite.addons.AddonRoles.set(a.base, com.lekathedon.visionlite.addons.AddonRoles.Role.ROWS, true) }
                        onDone()
                    }, Modifier.focusRequester(first))
                    Button("Select none", { cats.forEach { picked[key(it)] = false } })
                    Button("Not now", onDone)
                }
            }
            items(cats.size) { i ->
                val c = cats[i]; val k = key(c); val on = picked[k] == true
                Button((if (on) "✓  " else "     ") + (c.name ?: c.id) + "  ·  " + (if (c.type == "movie") "Movies" else if (c.type == "series") "Series" else c.type), { picked[k] = !on }, Modifier.fillMaxWidth())
            }
        } }
    }
}

/** The rows prompt from anywhere (Vision TB's page asks after adding an add-on with rows). */
object RowsAsk { val flow = kotlinx.coroutines.flow.MutableStateFlow<com.lekathedon.visionlite.addons.Addon?>(null) }

@Composable
fun RowsAskHost() {
    val a = RowsAsk.flow.collectAsState().value ?: return
    RowsPrompt(a) { RowsAsk.flow.value = null }
}

/**
 * Settings › Home and look › Anime: Separate hub (default), Merged into Films & TV (the anime rows join its Home, each
 * row still one kind) or Off. Off warns first: anime goes from the whole app, Search included.
 */
@Composable
private fun AnimeModeRow(p: Prefs, m: Modifier) {
    val v by p.animeMode.flow.collectAsState()
    var warn by remember { mutableStateOf(false) }
    ChoiceOf("Anime", "Its own hub, merged into Films & TV (its own rows there), or off", v,
        listOf("separate" to "Separate hub · default", "merged" to "Merged into Films & TV", "off" to "Off"), m) { k ->
        if (k == "off" && v != "off") warn = true else com.lekathedon.visionlite.anime.Hubs.setMode(k)
    }
    if (warn) Dialog(onDismissRequest = { warn = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val no = remember { FocusRequester() }
        LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; runCatching { no.requestFocus() } }
        com.lekathedon.visionlite.ui.Inside(true) {
            Column(Modifier.width(560.dp).clip(RoundedCornerShape(Kit.cardR + 4.dp)).background(Kit.panel)
                .border(1.dp, Kit.accent.copy(alpha = 0.5f), RoundedCornerShape(Kit.cardR + 4.dp)).padding(22.dp)) {
                com.lekathedon.visionlite.ui.DisplayText("Turn anime off?", 22.sp, maxLines = 1)
                Text("Every anime (Japanese, Chinese and Korean animation) disappears from the whole app: Home, pages, " +
                    "recommendations, Continue watching and Library. It can't even be found in Search. Switch back here any time.",
                    color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    com.lekathedon.visionlite.ui.Button("Keep anime", { warn = false }, Modifier.focusRequester(no))
                    com.lekathedon.visionlite.ui.Button("Turn anime off", { com.lekathedon.visionlite.anime.Hubs.setMode("off"); warn = false })
                }
            }
        }
    }
}
