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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as App
        setContent {
            val nav = remember { Nav() }
            val oled by app.prefs.oledBlack.flow.collectAsState()
            val look = remember(oled) { Look(oled) }
            val asked by app.prefs.deviceAsked.flow.collectAsState()
            val saved = rememberSaveableStateHolder()
            val setupDone by com.lekathedon.visionlite.integrations.Integrations.setupDone.flow.collectAsState()
            BackHandler(enabled = nav.canPop) { nav.pop() }
            CompositionLocalProvider(LocalLook provides look) {
                Box(Modifier.fillMaxSize().background(look.page)) {
                    // Home keeps its scroll/focus position under the screens opened from it.
                    when (val s = nav.current) {
                        // First start: the device check alone on screen (nothing behind it to steal focus).
                        Screen.Home -> if (!asked) com.lekathedon.visionlite.device.FirstStartCheck()
                            // Then, once: every integration and tracker, one page each.
                            else if (!setupDone) saved.SaveableStateProvider("setup") { com.lekathedon.visionlite.integrations.Setup(nav) }
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
                        Screen.ThisTv -> com.lekathedon.visionlite.device.ThisTv()
                        Screen.Library -> com.lekathedon.visionlite.library.Library(nav)
                        Screen.LiveTv -> com.lekathedon.visionlite.iptv.LiveTv(nav)
                        Screen.Trakt -> com.lekathedon.visionlite.trakt.TraktScreen()
                        Screen.Rows -> com.lekathedon.visionlite.settings.Rows()
                        Screen.VisionTb -> com.lekathedon.visionlite.tb.VisionTbScreen()
                        Screen.Integrations -> com.lekathedon.visionlite.integrations.IntegrationsScreen(nav)
                        is Screen.SignIn -> com.lekathedon.visionlite.integrations.SignInScreen(nav, s.id)
                    }
                }
            }
        }
    }
}
