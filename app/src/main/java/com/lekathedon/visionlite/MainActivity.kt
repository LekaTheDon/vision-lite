package com.lekathedon.visionlite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
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
import com.lekathedon.visionlite.home.Home
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

class MainActivity : ComponentActivity() {
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        com.lekathedon.visionlite.ui.HoldGuard.consume(event) || super.dispatchKeyEvent(event)

    override fun onResume() { super.onResume(); com.lekathedon.visionlite.link.LinkState.front = true }
    override fun onPause() { com.lekathedon.visionlite.link.LinkState.front = false; super.onPause() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.lekathedon.visionlite.link.LinkService.sync(this)
        val app = application as App
        setContent {
            val nav = remember { Nav() }
            val oled by app.prefs.oledBlack.flow.collectAsState()
            val look = remember(oled) { Look(oled) }
            val asked by app.prefs.deviceAsked.flow.collectAsState()
            val saved = rememberSaveableStateHolder()
            val setupDone by com.lekathedon.visionlite.integrations.Integrations.setupDone.flow.collectAsState()
            // Ended badly last time (crash, freeze, memory kill)? One card offers a report before Home.
            var problem by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(
                com.lekathedon.visionlite.report.Problems.lastUnseen(this@MainActivity)) }
            BackHandler(enabled = nav.canPop) { nav.pop() }
            // The phone companion's requests (Play, details, a channel…): opened like a press on the remote.
            androidx.compose.runtime.LaunchedEffect(Unit) {
                for (s in com.lekathedon.visionlite.link.LinkState.open) {
                    if (s == Screen.Home) nav.popToHome()
                    else { if (nav.current is Screen.Player || nav.current is Screen.YtPlay) nav.pop(); nav.push(s) }
                }
            }
            // Let the app decide: a new TV or soundbar since the last check gets its recommended settings by itself.
            val ctx = androidx.compose.ui.platform.LocalContext.current
            androidx.compose.runtime.LaunchedEffect(Unit) {
                val n = runCatching { com.lekathedon.visionlite.device.DeviceCheck.autoUpdate(ctx) }.getOrDefault(0)
                if (n > 0) android.widget.Toast.makeText(ctx, "New TV or soundbar: $n settings updated (Settings › This TV)", android.widget.Toast.LENGTH_LONG).show()
            }
            // Music never plays over a video: a film, an episode, Live TV or a YouTube video pauses it.
            val top = nav.current
            androidx.compose.runtime.LaunchedEffect(top) {
                if (top is Screen.Player || top is Screen.YtPlay) com.lekathedon.visionlite.music.MusicPlayer.pause()
            }
            CompositionLocalProvider(LocalLook provides look) {
                Box(Modifier.fillMaxSize().background(look.page)) {
                  // A hub's pages (Discover, Library, Live TV…) carry the pill on top, that page marked (Vision).
                  val hub = com.lekathedon.visionlite.home.hubPageOf(nav.current)
                  // The pill on a hub's page shows that hub, however the page was reached.
                  androidx.compose.runtime.LaunchedEffect(nav.current) { com.lekathedon.visionlite.home.hubOf(nav.current)?.let { com.lekathedon.visionlite.anime.Hubs.setSection(it) } }
                  Box(Modifier.fillMaxSize().padding(top = if (hub != null) 50.dp else 0.dp)) {
                    // Home keeps its scroll/focus position under the screens opened from it.
                    when (val s = nav.current) {
                        // First start: the device check alone on screen (nothing behind it to steal focus).
                        Screen.Home -> if (!asked) com.lekathedon.visionlite.device.FirstStartCheck(onChoose = { nav.push(Screen.ThisTv) })
                            // Then, once: every integration and tracker, one page each.
                            else if (!setupDone) saved.SaveableStateProvider("setup") { com.lekathedon.visionlite.integrations.Setup(nav) }
                            else if (problem != null) problem?.let { pr ->
                                com.lekathedon.visionlite.report.ProblemPrompt(pr,
                                    onReport = { problem = null; com.lekathedon.visionlite.report.Problems.markSeen(); nav.push(Screen.Report(pr.kind)) },
                                    onLater = { problem = null; com.lekathedon.visionlite.report.Problems.markSeen() })
                            }
                            else saved.SaveableStateProvider("home") {
                                if (app.prefs.homeLook.flow.collectAsState().value == "classic") Home(nav)
                                else com.lekathedon.visionlite.home.EchoHome(nav)
                            }
                        is Screen.Detail -> Detail(nav, s)
                        is Screen.Streams -> Streams(nav, s)
                        is Screen.Player -> Player(nav, s)
                        Screen.Search -> Search(nav)
                        Screen.Settings -> Settings(nav)
                        Screen.Addons -> Addons()
                        Screen.ThisTv -> Settings(nav, start = "This TV")
                        Screen.Library -> com.lekathedon.visionlite.library.Library(nav)
                        Screen.LiveTv -> com.lekathedon.visionlite.iptv.LiveTv(nav)
                        Screen.Sports -> com.lekathedon.visionlite.sports.SportsScreen(nav)
                        Screen.SportsTeams -> com.lekathedon.visionlite.sports.SportsTeams()
                        Screen.SportsMyTeams -> com.lekathedon.visionlite.sports.SportsMyTeamsPage(nav)
                        is Screen.SportsGame -> com.lekathedon.visionlite.sports.SportsGamePage(nav, s.id)
                        is Screen.Standings -> com.lekathedon.visionlite.sports.SportsStandings(s.leagueId)
                        Screen.Vod -> com.lekathedon.visionlite.iptv.VodScreen(nav)
                        Screen.Recordings -> com.lekathedon.visionlite.iptv.RecordingsScreen(nav)
                        Screen.Discover -> com.lekathedon.visionlite.browse.Discover(nav)
                        Screen.Genres -> com.lekathedon.visionlite.browse.Genres(nav)
                        Screen.Decades -> com.lekathedon.visionlite.browse.Decades(nav)
                        Screen.Scout -> com.lekathedon.visionlite.browse.Scout(nav)
                        Screen.Calendar -> com.lekathedon.visionlite.browse.CalendarPage(nav)
                        is Screen.Service -> com.lekathedon.visionlite.browse.ServicePage(nav, s.id)
                        is Screen.Person -> com.lekathedon.visionlite.browse.PersonPage(nav, s.id, s.name)
                        is Screen.YouTube -> com.lekathedon.visionlite.youtube.YouTubeScreen(nav, s.tab)
                        is Screen.YtPlay -> com.lekathedon.visionlite.youtube.YtPlayer(nav, s)
                        is Screen.Music -> com.lekathedon.visionlite.music.MusicScreen(nav, s.tab)
                        Screen.Trakt -> com.lekathedon.visionlite.trakt.TraktScreen()
                        Screen.Rows -> com.lekathedon.visionlite.settings.Rows()
                        Screen.VisionTb -> com.lekathedon.visionlite.tb.VisionTbScreen()
                        Screen.PhoneLink -> com.lekathedon.visionlite.link.PhoneLinkScreen()
                        Screen.Integrations -> com.lekathedon.visionlite.integrations.IntegrationsScreen(nav)
                        is Screen.SignIn -> com.lekathedon.visionlite.integrations.SignInScreen(nav, s.id)
                        is Screen.Integration -> com.lekathedon.visionlite.integrations.ServiceScreen(nav, s.id)
                        is Screen.Report -> com.lekathedon.visionlite.report.ReportScreen(nav, s.kind)
                    }
                  }
                  // Sports wherever you are: reminders, scoring cards, pinned scores (dormant until Live TV / Sports opened).
                  com.lekathedon.visionlite.sports.SportsHost(nav)
                  if (hub != null) {
                      val pillHome = remember { androidx.compose.ui.focus.FocusRequester() }
                      var pillFocused by remember { androidx.compose.runtime.mutableStateOf(false) }
                      val fm = androidx.compose.ui.platform.LocalFocusManager.current
                      TopPill(nav, pillFocused, pillHome, onDown = { fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }, onRight = null,
                          onFocus = { pillFocused = it }, current = hub)
                  }
                }
            }
        }
    }
}
