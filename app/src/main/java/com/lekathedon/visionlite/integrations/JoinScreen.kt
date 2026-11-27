package com.lekathedon.visionlite.integrations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.Qr
import com.lekathedon.visionlite.ui.Text

/**
 * First start: "Coming from Nuvio or Stremio?" One scan-and-approve sign-in brings your debrid, add-ons, library, what
 * you watched and where you stopped ([Join]); then that account is signed out and never called again. Or start fresh,
 * or set everything up from the phone app.
 */
@Composable
fun JoinFirst(nav: Nav, onPast: () -> Unit) {
    var from by remember { mutableStateOf<String?>(null) }      // null = choosing, "nuvio", "stremio", "phone"
    var code by remember { mutableStateOf<SignIns.Code?>(null) }
    var msg by remember { mutableStateOf("") }
    var done by remember { mutableStateOf<String?>(null) }
    var profiles by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var pick by remember { mutableStateOf<Int?>(null) }
    var round by remember { mutableStateOf(0) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(from, code != null, done, profiles.size) { runCatching { first.requestFocus() } }

    if (from == "phone") {
        androidx.activity.compose.BackHandler { from = null }
        LaunchedEffect(Unit) { App.instance.prefs.phoneLink.value = true; com.lekathedon.visionlite.link.LinkService.sync(ctx) }
        Column(Modifier.fillMaxSize().background(LocalLook.current.page)) {
            Text("Set up from your phone: scan this with the phone app and follow it there. It moves on by itself when the phone says Done. " +
                "Back = choose again.", color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.fillMaxWidth(0.85f).padding(start = 56.dp, top = 32.dp))
            com.lekathedon.visionlite.link.PhoneLinkScreen()
        }
        return
    }
    if (from != null) androidx.activity.compose.BackHandler(enabled = done == null) { from = null; code = null; msg = ""; profiles = emptyList(); pick = null }

    // The sign-in, then the read, then applying it.
    LaunchedEffect(from, round) {
        val f = from ?: return@LaunchedEffect
        code = null; msg = "Asking ${name(f)} for a code…"
        val c = runCatching { if (f == "nuvio") SignIns.nuvioStart() else Join.stremioStart() }
            .getOrElse { msg = "${name(f)} isn't reachable right now. Try again, or start fresh."; return@LaunchedEffect }
        code = c; msg = ""
        val ok: Any? = if (f == "nuvio") SignIns.nuvioWait(c).takeIf { it } else Join.stremioWait(c)
        code = null
        if (ok == null) { msg = "The code expired or was refused. Try again."; return@LaunchedEffect }
        if (f == "nuvio") {
            val ps = runCatching { SignIns.nuvioProfiles() }.getOrDefault(emptyList())
            if (ps.size > 1) { profiles = ps; return@LaunchedEffect }   // asks which profile, then runs [bring]
            pick = ps.firstOrNull()?.first ?: 1
        } else {
            msg = "Bringing over your Stremio add-ons, library and progress…"
            done = runCatching { Join.apply(Join.fromStremio(ok as String)) }.getOrElse { "Couldn't read everything: ${it.message}" }; msg = ""
        }
    }
    LaunchedEffect(pick) {
        val p = pick ?: return@LaunchedEffect
        profiles = emptyList()
        msg = "Bringing over your debrid, add-ons, library and progress…"
        val h = runCatching { Join.fromNuvio(p) }.getOrNull()
        done = if (h == null) "Couldn't read your Nuvio account. You can carry on and set up here." else Join.apply(h)
        msg = ""
        if (h?.restart == true) { Integrations.setupDone.value = true; kotlinx.coroutines.delay(1500); com.lekathedon.visionlite.App.restart(ctx) }
    }

    Column(Modifier.fillMaxSize().background(LocalLook.current.page).padding(48.dp)) {
        when {
            done != null -> {
                Text("You're in", color = Palette.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("From ${name(from ?: "")}: ${done}.", color = Palette.Text, fontSize = 17.sp, modifier = Modifier.padding(top = 8.dp))
                Text("Your watched list and Continue Watching fill in as soon as Trakt or Simkl is signed in (next step). " +
                    "${name(from ?: "")} is signed out here and won't be contacted again.",
                    color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.fillMaxWidth(0.8f).padding(top = 8.dp))
                Button("Continue", onPast, Modifier.padding(top = 18.dp).focusRequester(first))
            }
            profiles.isNotEmpty() -> {
                Text("Which Nuvio profile is yours?", color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.padding(top = 14.dp)) {
                    profiles.forEachIndexed { i, (n, label) ->
                        Button(label, { pick = n }, Modifier.padding(end = 12.dp).let { if (i == 0) it.focusRequester(first) else it })
                    }
                }
            }
            from != null && code != null -> Row {
                Qr(code!!.url, 220.dp)
                Column(Modifier.padding(start = 28.dp)) {
                    Text("Sign in to ${name(from!!)}", color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("Scan with your phone and approve. Or open", color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                    Text(code!!.url, color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("and enter", color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
                    Text(code!!.userCode, color = Palette.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text("Only used once, to bring your things over.", color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                    Button("New code", { round++ }, Modifier.padding(top = 14.dp).focusRequester(first))
                }
            }
            from != null -> {
                Text(msg.ifBlank { "…" }, color = Palette.Text, fontSize = 17.sp)
                if (msg.startsWith("The code") || msg.contains("isn't reachable"))
                    Row { Button("Try again", { round++ }, Modifier.padding(top = 14.dp, end = 12.dp).focusRequester(first)); Button("Start fresh", onPast, Modifier.padding(top = 14.dp)) }
            }
            else -> {
                Text("Coming from Nuvio or Stremio?", color = Palette.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("Sign in once and bring your debrid (TorBox, Real-Debrid, AllDebrid, Premiumize, Offcloud), your add-ons, your library, " +
                    "what you've watched and where you stopped. Nothing to type, and that app is never contacted again.",
                    color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.fillMaxWidth(0.8f).padding(top = 6.dp))
                Row(Modifier.padding(top = 18.dp)) {
                    Button("From Nuvio", { from = "nuvio" }, Modifier.padding(end = 12.dp).focusRequester(first))
                    Button("From Stremio", { from = "stremio" }, Modifier.padding(end = 12.dp))
                    Button("Start fresh", onPast, Modifier.padding(end = 12.dp))
                    Button("Set up from your phone", { from = "phone" })
                }
            }
        }
    }
}

private fun name(f: String) = when (f) { "nuvio" -> "Nuvio"; "stremio" -> "Stremio"; else -> "" }
