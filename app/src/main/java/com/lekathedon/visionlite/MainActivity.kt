package com.lekathedon.visionlite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import com.lekathedon.visionlite.ui.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.lekathedon.visionlite.home.TopPill
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.detail.Detail
import com.lekathedon.visionlite.player.Player
import com.lekathedon.visionlite.search.Search
import com.lekathedon.visionlite.settings.Addons
import com.lekathedon.visionlite.settings.Settings
import com.lekathedon.visionlite.streams.Streams
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Look
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.Screen
import com.lekathedon.visionlite.ui.skinPage

class MainActivity : ComponentActivity() {
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        // Any key is use of the app (Ambient Mode's idle clock), whoever takes the key after.
        com.lekathedon.visionlite.ui.DpadPace.touch().let { false } ||
        com.lekathedon.visionlite.ui.Screensaver.dismiss(event) || com.lekathedon.visionlite.ui.HoldGuard.consume(event) ||
            com.lekathedon.visionlite.ui.DpadPace.consume(event) || super.dispatchKeyEvent(event)

    override fun onResume() { super.onResume(); com.lekathedon.visionlite.link.LinkState.front = true; front++ }
    /** Bumps each time the app comes to the front (accessibility services may have changed meanwhile). */
    private var front by androidx.compose.runtime.mutableIntStateOf(0)
    override fun onPause() { com.lekathedon.visionlite.link.LinkState.front = false; super.onPause() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.lekathedon.visionlite.link.LinkService.sync(this)
        val app = application as App
        // Settings shown, decided once: a box already set up keeps every setting it had; a new one starts on Basic.
        if (app.prefs.settingsLevel.value.isEmpty())
            app.prefs.settingsLevel.value = if (com.lekathedon.visionlite.integrations.Integrations.setupDone.value) "advanced" else "basic"
        // Vision: the window paints nothing itself (the pages draw their own background), so the player can leave the
        // bars around the film unpainted and the TV shows its true black there (True black bars).
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        setContent {
            val nav = remember { Nav() }
            val skinKey by app.prefs.skin.flow.collectAsState()
            val skin = com.lekathedon.visionlite.ui.Skin.of(skinKey)
            com.lekathedon.visionlite.ui.Theme.skin = skin
            val sizeKey by app.prefs.uiSize.flow.collectAsState()
            com.lekathedon.visionlite.ui.Theme.scale = com.lekathedon.visionlite.ui.Theme.scaleOf(sizeKey)
            val look = remember(skin) { Look(skin) }
            val asked by app.prefs.deviceAsked.flow.collectAsState()
            val saved = rememberSaveableStateHolder()
            val setupDone by com.lekathedon.visionlite.integrations.Integrations.setupDone.flow.collectAsState()
            // Past the Nuvio QR (signed in and setup brought over, or "I don't use Nuvio"). Not tied to the sign-in
            // itself, so the screen stays up while the setup is fetched.
            var nuvioPassed by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(
                com.lekathedon.visionlite.integrations.Join.joinedFrom() != null) }
            // Ended badly last time (crash, freeze, memory kill)? One card offers a report before Home.
            var problem by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(
                com.lekathedon.visionlite.report.Problems.lastUnseen(this@MainActivity)) }
            BackHandler(enabled = nav.canPop) { nav.pop() }
            // Who's watching? (more than one profile) or the PIN of a locked profile, before anything else.
            var gate by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.lekathedon.visionlite.data.Profiles.startGate()) }
            // The phone companion's requests (Play, details, a channel…): opened like a press on the remote.
            androidx.compose.runtime.LaunchedEffect(Unit) {
                for (s in com.lekathedon.visionlite.link.LinkState.open) {
                    if (s == Screen.Home) nav.popToHome()
                    else { if (nav.current is Screen.Player) nav.pop(); nav.push(s) }
                }
            }
            // Let the app decide: a new TV, soundbar or internet (Wi-Fi / cable / DNS servers) since the last check gets its recommended settings by itself.
            val ctx = androidx.compose.ui.platform.LocalContext.current
            androidx.compose.runtime.LaunchedEffect(Unit) {
                // IPTV: only the 05:00 alarm is set at start (no download; there must be a playlist).
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.lekathedon.visionlite.iptv.IptvSync.arm(ctx) }; runCatching { com.lekathedon.visionlite.iptv.Iptv.warmDb() } }
                if (app.prefs.iptvUpdateOnStart.value) com.lekathedon.visionlite.iptv.IptvSync.kick(force = true)
                val n = runCatching { com.lekathedon.visionlite.device.DeviceCheck.autoUpdate(ctx) }.getOrDefault(0)
                if (n > 0) android.widget.Toast.makeText(ctx, "New TV, soundbar or internet: $n settings updated (Settings › This TV)", android.widget.Toast.LENGTH_LONG).show()
            }
            val top = nav.current
            androidx.compose.runtime.LaunchedEffect(top) {
                com.lekathedon.visionlite.ui.DpadPace.inPlayer = top is Screen.Player
            }
            // App language: a change redraws every screen in it; Arabic / Hebrew / Farsi / Urdu read right to left.
            val langKey by app.prefs.appLang.flow.collectAsState()
            val dir = if (com.lekathedon.visionlite.ui.Lang.rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr
            androidx.compose.runtime.key(langKey) {
            CompositionLocalProvider(LocalLook provides look, androidx.compose.ui.platform.LocalLayoutDirection provides dir) {
                val blackBars by app.prefs.blackBars.flow.collectAsState()
                val clearBehind = blackBars && nav.current is Screen.Player
                // Speed and network › Smoother with key remappers: only Button Mapper / Projectivy listen, so the screen
                // isn't described to them (checked again when the app comes back to the front or the setting changes).
                val quietOn by app.prefs.quietA11y.flow.collectAsState()
                var quiet by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                androidx.compose.runtime.LaunchedEffect(quietOn, front) { quiet = com.lekathedon.visionlite.data.QuietA11y.quiet(this@MainActivity) }
                Box(Modifier.fillMaxSize().then(if (clearBehind) Modifier else Modifier.background(look.page))
                    .then(if (quiet) Modifier.clearAndSetSemantics { } else Modifier)) {
                  // A hub's pages (Discover, Library, Live TV…) carry the pill on top, that page marked (Vision).
                  val hub = com.lekathedon.visionlite.home.hubPageOf(nav.current)
                  // The pill on a hub's page shows that hub, however the page was reached.
                  androidx.compose.runtime.LaunchedEffect(nav.current) { com.lekathedon.visionlite.home.hubOf(nav.current)?.let { com.lekathedon.visionlite.anime.Hubs.setSection(it) } }
                  Box(Modifier.fillMaxSize().padding(top = if (hub != null) 50.dp else 0.dp)) {
                    // Past Home (not the player) every screen wears the look: its font and parts (ui/Kit.kt).
                    val inside = nav.current != Screen.Home && nav.current !is Screen.Player
                    com.lekathedon.visionlite.ui.Inside(inside, font = true) {
                    // Home keeps its scroll/focus position under the screens opened from it. Every other screen
                    // keeps its own (scroll positions, the part last focused) while screens opened from it are on top.
                    val cur = nav.current
                    val keepsFocus = cur != Screen.Home && cur !is Screen.Player && cur != Screen.LiveTv
                    androidx.compose.runtime.SideEffect { nav.dropped.forEach { saved.removeState(it) }; nav.dropped.clear() }
                    androidx.compose.runtime.key(nav.key) {
                    KeepState(saved, if (cur == Screen.Home) null else nav.key) {
                    CompositionLocalProvider(com.lekathedon.visionlite.ui.LocalFocusMemory provides (if (keepsFocus) nav.memory else null)) {
                    when (val s = cur) {
                        // First start: the device check alone on screen (nothing behind it to steal focus).
                        // Everything before Home (sign-in, This TV check, setup, taste questions, the problem card, Who's watching)
                        // wears the look and skin like every page past Home (user rule: every new menu follows the look).
                        Screen.Home -> if (gate != null) com.lekathedon.visionlite.ui.Inside(true) { com.lekathedon.visionlite.profiles.ProfileGate(atStart = true, pinOnly = gate == "pin") { gate = null } }
                            // First start, before anything else: Join (from Nuvio / Stremio, once) or start fresh.
                            else if (!setupDone && !nuvioPassed) com.lekathedon.visionlite.ui.Inside(true) { com.lekathedon.visionlite.integrations.JoinFirst(nav) { nuvioPassed = true } }
                            else if (!asked) com.lekathedon.visionlite.ui.Inside(true) { com.lekathedon.visionlite.device.FirstStartCheck(onChoose = { nav.push(Screen.ThisTv) }) }
                            // Then, once: TorBox and Trakt, one page each (quick start; the rest in Settings).
                            else if (!setupDone) saved.SaveableStateProvider("setup") { com.lekathedon.visionlite.ui.Inside(true) { com.lekathedon.visionlite.integrations.Setup(nav) } }
                            // No taste questions at start (user 2026-11-28): Settings › Teach your account, any time.
                            else if (problem != null) problem?.let { pr -> com.lekathedon.visionlite.ui.Inside(true) {
                                com.lekathedon.visionlite.report.ProblemPrompt(pr,
                                    onReport = { problem = null; com.lekathedon.visionlite.report.Problems.markSeen(); nav.push(Screen.Report(pr.kind)) },
                                    onLater = { problem = null; com.lekathedon.visionlite.report.Problems.markSeen() })
                            } }
                            else saved.SaveableStateProvider("home") {
                                com.lekathedon.visionlite.home.EchoHome(nav)
                            }
                        is Screen.Detail -> Detail(nav, s)
                        is Screen.VodSeries -> com.lekathedon.visionlite.iptv.VodSeriesScreen(nav, s.pl, s.id)
                        is Screen.Streams -> Streams(nav, s)
                        // Players keep left-to-right (timelines, seek keys) in every language.
                        is Screen.Player -> CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) { Player(nav, s) }
                        Screen.Search -> Search(nav)
                        Screen.Settings -> Settings(nav)
                        Screen.Profiles -> Settings(nav, start = "Profiles")
                        is Screen.ProfileEdit -> com.lekathedon.visionlite.profiles.ProfileEdit(nav, s.id)
                        Screen.Addons -> Addons()
                        Screen.ThisTv -> Settings(nav, start = "This TV")
                        Screen.Library -> com.lekathedon.visionlite.library.Library(nav)
                        Screen.LiveTv -> com.lekathedon.visionlite.iptv.LiveTv(nav)
                        Screen.Sports -> com.lekathedon.visionlite.sport.SportPage(nav)
                                                Screen.SportsTeams -> com.lekathedon.visionlite.sport.Picker(nav, arrange = false)
                        Screen.SportsMyTeams -> com.lekathedon.visionlite.sport.Picker(nav, arrange = true)
                        is Screen.SportsGame -> com.lekathedon.visionlite.sport.GamePage(nav, s.id)
                        is Screen.Standings -> com.lekathedon.visionlite.ui.Inside(true) { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().skinPage().padding(start = 40.dp, end = 32.dp, top = 24.dp)) { com.lekathedon.visionlite.sport.Tables(nav, s.leagueId.ifBlank { null }) } }
                        Screen.Vod -> com.lekathedon.visionlite.iptv.VodScreen(nav)
                        Screen.Recordings -> com.lekathedon.visionlite.iptv.RecordingsScreen(nav)
                        Screen.Cloud -> com.lekathedon.visionlite.streams.CloudScreen(nav)
                        Screen.ArabBrowse -> com.lekathedon.visionlite.tb.ArabBrowseScreen(nav)
                        Screen.Discover -> com.lekathedon.visionlite.browse.Discover(nav)
                        is Screen.Genre -> com.lekathedon.visionlite.browse.GenrePage(nav, s.slug)
                        is Screen.Mood -> com.lekathedon.visionlite.browse.MoodPage(nav, s.key)
                        is Screen.Scout -> com.lekathedon.visionlite.browse.ScoutPage(nav, s.id)
                        is Screen.Decade -> com.lekathedon.visionlite.browse.DecadePage(nav, s.slug)
                        Screen.Calendar -> com.lekathedon.visionlite.browse.CalendarPage(nav)
                        is Screen.Service -> com.lekathedon.visionlite.browse.ServicePage(nav, s.id)
                        is Screen.Person -> com.lekathedon.visionlite.browse.PersonPage(nav, s.id, s.name)
                        is Screen.Company -> com.lekathedon.visionlite.browse.CompanyPage(nav, s.id, s.name)
                        Screen.Trakt -> com.lekathedon.visionlite.trakt.TraktScreen()
                        Screen.Rows -> com.lekathedon.visionlite.settings.Rows(nav)
                        Screen.Interests -> com.lekathedon.visionlite.settings.InterestsPage()
                        Screen.TasteStart -> com.lekathedon.visionlite.settings.TasteStart { nav.pop() }
                        Screen.LeaveOut -> com.lekathedon.visionlite.settings.LeaveOutPage()
                        Screen.VisionTb -> com.lekathedon.visionlite.tb.VisionTbScreen()
                        Screen.PhoneLink -> com.lekathedon.visionlite.link.PhoneLinkScreen()
                        Screen.Integrations -> com.lekathedon.visionlite.integrations.IntegrationsScreen(nav)
                        is Screen.SignIn -> com.lekathedon.visionlite.integrations.SignInScreen(nav, s.id)
                        is Screen.Integration -> com.lekathedon.visionlite.integrations.ServiceScreen(nav, s.id)
                        is Screen.Report -> com.lekathedon.visionlite.report.ReportScreen(nav, s.kind)
                    }
                    }
                    }
                    }
                    }
                  }
                  // Sports wherever you are: reminders, scoring cards, pinned scores (dormant until Live TV / Sports opened).
                  com.lekathedon.visionlite.sport.SportsHost(nav)
                  com.lekathedon.visionlite.home.TasteMenuHost()
                  com.lekathedon.visionlite.home.RateHost()
                  // Vision TB: a source that stopped working (or came back), told once.
                  com.lekathedon.visionlite.settings.RowsAskHost()
                  com.lekathedon.visionlite.tb.TbAlertHost(nav.current is Screen.Player)
                  if (hub != null) {
                      val pillHome = remember { androidx.compose.ui.focus.FocusRequester() }
                      var pillFocused by remember { androidx.compose.runtime.mutableStateOf(false) }
                      val fm = androidx.compose.ui.platform.LocalFocusManager.current
                      TopPill(nav, pillFocused, pillHome, onDown = { fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }, onRight = null,
                          onFocus = { pillFocused = it }, current = hub)
                  }
                  // Vision's Ambient Mode: idle minutes off the player → 4K backdrops from your rows. Drawn last, over
                  // everything (the top pill showed through it).
                  com.lekathedon.visionlite.ui.ScreensaverHost(nav)
                }
            }
            }
        }
    }
}

/** A screen past Home keeps its saved state (scroll, remembered focus) under its own key while others are on top. */
@androidx.compose.runtime.Composable
private fun KeepState(holder: androidx.compose.runtime.saveable.SaveableStateHolder, key: String?, content: @androidx.compose.runtime.Composable () -> Unit) {
    if (key == null) content() else holder.SaveableStateProvider(key, content)
}
