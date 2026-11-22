package com.lekathedon.visionlite.link

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.ui.Button
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.Qr

/** Settings › Phone companion: the switch, and (on) the QR + code the phone scans once. */
@Composable
fun PhoneLinkScreen() {
    val context = LocalContext.current
    val prefs = App.instance.prefs
    val on by prefs.phoneLink.flow.collectAsStateWithLifecycle()
    val pairing = remember { Pairing(context) }
    var secret by remember { mutableStateOf(pairing.secret) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; runCatching { first.requestFocus() } }
    val ip = remember(on) { localIp() }
    Row(Modifier.fillMaxSize().background(LocalLook.current.page).padding(56.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Phone companion", color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Nuvio Vision Companion on your phone: browse, save, Play on TV, see what plays and seek. " +
                "Lite listens on your home network only while this is on (a few MB of memory, no CPU while idle).",
                color = Palette.TextDim, fontSize = 14.sp)
            Button(if (on) "Phone companion: On" else "Phone companion: Off", {
                prefs.phoneLink.value = !on
                LinkService.sync(context)
            }, Modifier.focusRequester(first))
            if (on) {
                Button("New code (the old phone has to scan again)", { secret = pairing.newSecret() })
                val seen = pairing.lastSeen
                Text(if (seen == 0L) "No phone paired yet" else "Last heard from ${pairing.lastPhone ?: "your phone"} " +
                    android.text.format.DateUtils.getRelativeTimeSpanString(seen), color = Palette.TextDim, fontSize = 13.sp)
            }
        }
        if (on) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val name = android.net.Uri.encode(android.os.Build.MODEL)
            Qr("visionlite://pair?h=${ip.orEmpty()}&p=${Pairing.PORT}&k=$secret&n=$name", 300.dp)
            Text("Scan with Nuvio Vision Companion", color = Palette.Text, fontSize = 15.sp)
            Text("Code ${Pairing.pretty(secret)}", color = Palette.TextDim, fontSize = 14.sp)
            Text("TV ${ip ?: "(no network)"} : ${Pairing.PORT}", color = Palette.TextDim, fontSize = 13.sp)
        }
    }
}

/** The box's address on the home network (IPv4, not loopback / VPN tun). */
private fun localIp(): String? = runCatching {
    java.net.NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") }
        .sortedBy { if (it.name.startsWith("eth")) 0 else if (it.name.startsWith("wlan")) 1 else 2 }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }?.hostAddress
}.getOrNull()
