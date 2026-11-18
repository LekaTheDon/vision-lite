package com.lekathedon.visionlite.trakt

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.ui.Button
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.Qr

/** Settings > Trakt: scan the QR with the phone, enter the code there; the TV connects by itself. */
@Composable
fun TraktScreen() {
    val trakt = App.instance.trakt
    val tokens by trakt.connected.collectAsState()
    var code by remember { mutableStateOf<DeviceCode?>(null) }
    var msg by remember { mutableStateOf("") }
    var round by remember { mutableStateOf(0) }
    val first = remember { FocusRequester() }
    LaunchedEffect(tokens != null) { runCatching { first.requestFocus() } }
    LaunchedEffect(round, tokens == null) {
        if (tokens != null || !trakt.configured || round == 0) return@LaunchedEffect
        code = null; msg = "Asking Trakt for a code…"
        val dc = runCatching { trakt.startLogin() }.getOrElse { msg = "Trakt not reachable (${it.message})"; return@LaunchedEffect }
        code = dc; msg = ""
        if (!trakt.waitForLogin(dc)) { code = null; msg = "The code expired or was refused. Try again." }
    }
    Column(Modifier.fillMaxSize().background(LocalLook.current.page).padding(48.dp)) {
        Text("Trakt", color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 14.dp))
        when {
            !trakt.configured -> Text("This build has no Trakt app key yet.", color = Palette.Text, fontSize = 16.sp)
            tokens != null -> {
                Text("Connected. Continue Watching, Library and scrobbling are on.", color = Palette.Text, fontSize = 16.sp)
                Button("Disconnect", { trakt.disconnect() }, Modifier.padding(top = 16.dp).focusRequester(first))
            }
            code != null -> Row {
                Qr(code!!.url, 220.dp)
                Column(Modifier.padding(start = 28.dp)) {
                    Text("Scan with your phone, or open", color = Palette.TextDim, fontSize = 15.sp)
                    Text(code!!.url, color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("and enter", color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
                    Text(code!!.userCode, color = Palette.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text("Waiting for Trakt…", color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
            else -> Button("Connect Trakt", { round++ }, Modifier.focusRequester(first))
        }
        if (msg.isNotEmpty()) Text(msg, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
    }
}
