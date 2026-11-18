package com.lekathedon.visionlite.tb

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.phone.PhoneBox
import com.lekathedon.visionlite.phone.PhoneForm
import com.lekathedon.visionlite.ui.Focusable
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Palette
import kotlinx.coroutines.launch

/** Settings > Vision TB: the switch and the TorBox key (from the phone, shown masked, checked once when saved). */
@Composable
fun VisionTbScreen() {
    val p = App.instance.prefs
    val on by p.visionTb.flow.collectAsState()
    val key by p.torboxKey.flow.collectAsState()
    var asking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(asking) { if (!asking) runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize().background(LocalLook.current.page).padding(48.dp).verticalScroll(rememberScrollState())) {
        Text("Vision TB", color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Asks the torrent sites directly and lists only what TorBox already has cached, as the first group of links. " +
            "Runs only when a link list opens.", color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp).fillMaxWidth(0.7f))
        Row("Vision TB: ${if (on) "On" else "Off"}", if (key.isBlank()) "Needs your TorBox key first" else "Show TorBox-cached links first", Modifier.focusRequester(first)) {
            if (key.isNotBlank()) p.visionTb.value = !on
        }
        Row("TorBox key", if (key.isBlank()) "Not set: enter it from your phone" else "••••" + key.takeLast(4) + (if (status.isNotEmpty()) " · $status" else "")) { asking = true }
        if (key.isNotBlank()) Row("Remove key", "Turns Vision TB off") { p.torboxKey.value = ""; p.visionTb.value = false; status = "" }
        if (asking) PhoneBox("TorBox key", listOf(PhoneForm.Field("TorBox API key", "From torbox.app/settings", secret = true)),
            onSave = { v ->
                asking = false
                val k = v.firstOrNull().orEmpty()
                if (k.isNotBlank()) {
                    p.torboxKey.value = k
                    status = "checking…"
                    scope.launch { status = if (works(k)) { p.visionTb.value = true; "works, Vision TB on" } else "TorBox didn't accept this key" }
                }
            },
            onCancel = { asking = false })
    }
}

private suspend fun works(key: String): Boolean = runCatching {
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        Net.http.newCall(okhttp3.Request.Builder().url("https://api.torbox.app/v1/api/user/me").header("Authorization", "Bearer $key").build())
            .execute().use { it.isSuccessful }
    }
}.getOrDefault(false)

@Composable
private fun Row(title: String, sub: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Focusable(onClick, modifier.padding(top = 12.dp).fillMaxWidth(0.6f)) { f ->
        Column(Modifier.fillMaxWidth().background(if (f) Color(0x33FFFFFF) else Palette.Card).padding(14.dp)) {
            Text(title, color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Palette.TextDim, fontSize = 13.sp)
        }
    }
}
