package com.lekathedon.visionlite.integrations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.phone.PhoneBox
import com.lekathedon.visionlite.phone.PhoneForm
import com.lekathedon.visionlite.ui.Button
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.ui.Qr
import kotlinx.coroutines.launch

/** Simkl / AniList / MyAnimeList sign-in: scan, approve on the phone (AniList and MAL: then paste once on the phone). */
@Composable
fun SignInScreen(nav: Nav, id: String) {
    Integrations.changed.collectAsState().value
    val s = Integrations.byId(id)
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var round by remember { mutableStateOf(0) }
    var code by remember { mutableStateOf<SignIns.Code?>(null) }
    var pasting by remember { mutableStateOf(false) }
    val nuvio = id == "nuvio"
    val qr = id == "simkl" || nuvio
    var profiles by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var prof by remember { mutableStateOf(SignIns.nuvioProfile()) }
    LaunchedEffect(nuvio) { if (nuvio && SignIns.signedIn("nuvio")) profiles = SignIns.nuvioProfiles() }
    val first = remember { FocusRequester() }
    LaunchedEffect(code != null, pasting, msg) { runCatching { first.requestFocus() } }
    LaunchedEffect(round) {
        if (round == 0 || !qr) return@LaunchedEffect
        code = null; msg = "Asking ${s.name} for a code…"
        val c = runCatching { if (nuvio) SignIns.nuvioStart() else SignIns.simklStart() }.getOrElse { msg = "${s.name} not reachable (${it.message})"; return@LaunchedEffect }
        code = c; msg = ""
        if (if (nuvio) SignIns.nuvioWait(c) else SignIns.simklWait(c)) {
            Integrations.touch()
            if (nuvio) App.instance.prefs.sp.edit().putBoolean("comingup_v3", false).apply()
            if (nuvio) { profiles = SignIns.nuvioProfiles(); profiles.firstOrNull()?.let { if (SignIns.nuvioProfileName().isBlank()) SignIns.setNuvioProfile(it.first, it.second) } }
            else nav.pop()
        } else { code = null; msg = "The code expired or was refused. Try again." }
    }
    Column(Modifier.fillMaxSize().background(LocalLook.current.page).padding(48.dp)) {
        Text(s.name, color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 14.dp))
        when {
            !SignIns.configured(id) -> {
                Text("This build doesn't have ${s.name}'s app ID yet, so sign-in can't start.", color = Palette.Text, fontSize = 16.sp)
                Button("Back", { nav.pop() }, Modifier.padding(top = 16.dp).focusRequester(first))
            }
            SignIns.signedIn(id) -> {
                Text("Signed in${SignIns.user(id).takeIf { it.isNotBlank() }?.let { " as $it" } ?: ""}.", color = Palette.Text, fontSize = 16.sp)
                if (nuvio && profiles.isNotEmpty()) {
                    Text("Coming up follows this profile:", color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp))
                    Row { profiles.forEach { (n, name) ->
                        Button(if (n == prof) "✓ $name" else name, {
                            prof = n; SignIns.setNuvioProfile(n, name); App.instance.prefs.sp.edit().putBoolean("comingup_v3", false).apply()
                        }, Modifier.padding(top = 8.dp, end = 10.dp))
                    } }
                }
                Row {
                    Button("Done", { nav.pop() }, Modifier.padding(top = 16.dp, end = 10.dp).focusRequester(first))
                    Button("Sign out", { Integrations.clear(s) }, Modifier.padding(top = 16.dp))
                }
            }
            qr && code != null -> Row {
                Qr(code!!.url, 220.dp)
                Column(Modifier.padding(start = 28.dp)) {
                    Text("Scan with your phone and approve. Or open", color = Palette.TextDim, fontSize = 15.sp)
                    Text(code!!.url, color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("and enter", color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
                    Text(code!!.userCode, color = Palette.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text("Waiting for ${s.name}…", color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                    Button("New code", { round++ }, Modifier.padding(top = 14.dp).focusRequester(first))
                }
            }
            qr -> Button(if (round == 0) "Sign in with ${s.name}" else "Try again", { round++ }, Modifier.focusRequester(first))
            pasting -> {
                val anilist = id == "anilist"
                PhoneBox(s.name, listOf(PhoneForm.Field(
                    if (anilist) "Code from AniList" else "Address from MyAnimeList",
                    note = if (anilist) "1. Tap the button and approve. 2. Copy the code AniList shows. 3. Paste it here and send."
                        else "1. Tap the button and approve. 2. The next page won't load: copy its address. 3. Paste it here and send.",
                    link = if (anilist) SignIns.aniListUrl() else SignIns.malUrl(),
                    linkText = "Sign in with ${s.name}")),
                    onSave = { v ->
                        pasting = false; msg = "Checking…"
                        scope.launch {
                            val text = v.firstOrNull().orEmpty()
                            val who = if (anilist) SignIns.aniListFinish(text) else SignIns.malFinish(text)
                            if (who != null) { msg = ""; Integrations.touch() } else msg = "${s.name} didn't accept that. Try again."
                        }
                    },
                    onCancel = { pasting = false })
            }
            else -> {
                Text("Scan the QR with your phone, tap Sign in, approve, then paste once on the phone.", color = Palette.TextDim, fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth(0.7f))
                Button("Sign in with ${s.name}", { pasting = true }, Modifier.padding(top = 16.dp).focusRequester(first))
            }
        }
        if (msg.isNotEmpty()) Text(msg, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
    }
}
