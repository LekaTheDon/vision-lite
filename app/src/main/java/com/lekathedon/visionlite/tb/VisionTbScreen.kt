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
        if (key.isNotBlank()) Sources()
    }
}

/**
 * Your arsenal (user 2026-11-23): add your own add-ons (they feed only Vision TB), and see what each source is worth,
 * scored from the searches you already made: reach, speed, health, and a verdict (keep / costly / remove / paused).
 */
@Composable
private fun Sources() {
    val tick by TbSources.tick.collectAsState()
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }
    var arab by remember { mutableStateOf(false) }
    var arabUser by remember { mutableStateOf(ArabP2P.user()) }
    // An add-on just added here that also has rows / details: offered to this profile's Home.
    var offer by remember { mutableStateOf<com.lekathedon.visionlite.addons.Addon?>(null) }
    val keys = remember(tick) { TbSources.keys() }
    val reports = remember(tick) { keys.map { TbSources.report(it) } }
    Text("Sources", color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 28.dp))
    Text("Add your own add-ons here: they only feed Vision TB (no rows, no link group of their own). Each source is scored from " +
        "the searches you make: what only it found, how long autoplay waits on it, and whether it still answers. A source that " +
        "stops working is paused and you're told.", color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp).fillMaxWidth(0.7f))
    Row("ArabP2P account", if (arabUser.isBlank()) "Arabic films and series (the tracker Aradeb uses), with your own account: no add-on, no key that expires"
        else "Signed in as $arabUser · only links TorBox already has are used (no ratio)") { arab = true }
    if (arab) PhoneBox("ArabP2P", listOf(PhoneForm.Field("Username", "arabp2p.net", initial = arabUser), PhoneForm.Field("Password", "", secret = true,
        note = "Kept on the TV only. Free sign-up at arabp2p.net (confirm the e-mail)")),
        onSave = { v ->
            arab = false
            val u = v.getOrNull(0).orEmpty(); val pw = v.getOrNull(1).orEmpty()
            if (u.isNotBlank() && pw.isNotBlank()) {
                ArabP2P.save(u, pw); arabUser = u; msg = "Checking ArabP2P…"
                scope.launch { msg = if (ArabP2P.check()) "ArabP2P works: used from the next search" else "ArabP2P didn't accept this account (or the site is down)" }
            }
        },
        onCancel = { arab = false })
    if (arabUser.isNotBlank()) Row("Sign out of ArabP2P", "Stops searching it") { ArabP2P.forget(); arabUser = ""; msg = "" }
    Row("Add an add-on", "From your phone: any Stremio stream add-on (Torrentio, Comet, MediaFusion, AIOStreams…, your own instance)") { adding = true }
    if (msg.isNotEmpty()) Text(msg, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    if (adding) PhoneBox("Vision TB add-on", listOf(PhoneForm.Field("Add-on URL", "https://…/manifest.json", note = "Configured links are fine; they stay on the TV and are never logged")),
        onSave = { v ->
            adding = false
            v.firstOrNull()?.takeIf { it.isNotBlank() }?.let { u ->
                msg = "Adding…"
                scope.launch {
                    msg = runCatching { TbSources.add(u) }.fold({ "Added ${it.name}: scored after ${TbSources.LEARN} searches" }, { "Could not add: ${it.message}" })
                    // Rows or details too (Aradeb: 16 Arabic rows, Arabic details)? Offered once.
                    runCatching { Net.getJson<com.lekathedon.visionlite.addons.Manifest>("${com.lekathedon.visionlite.addons.AddonStore.normalize(u)}/manifest.json", timeoutSec = 15) }.getOrNull()
                        ?.let { m -> com.lekathedon.visionlite.addons.Addon(com.lekathedon.visionlite.addons.AddonStore.normalize(u), m) }
                        ?.takeIf { it.rowCatalogs.isNotEmpty() || it.gives("meta") }?.let { offer = it }
                }
            }
        },
        onCancel = { adding = false })
    offer?.let { a ->
        Text("${a.manifest.name} also has ${a.rowCatalogs.size} rows${if (a.gives("meta")) " and its own details" else ""}. Use them in this profile?",
            color = Palette.Text, fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp).fillMaxWidth(0.7f))
        Row("Yes: rows and details (links stay with Vision TB)", "Its links aren't shown as a group of their own; you pick its rows next") {
            scope.launch {
                val inst = runCatching { App.instance.addons.install(a.base) }.getOrNull()
                if (inst != null) {
                    com.lekathedon.visionlite.addons.AddonRoles.set(inst.base, com.lekathedon.visionlite.addons.AddonRoles.Role.LINKS, false)
                    if (inst.rowCatalogs.isNotEmpty()) com.lekathedon.visionlite.settings.RowsAsk.flow.value = inst
                    msg = "${inst.manifest.name}: rows and details on in this profile"
                } else msg = "Couldn't add its rows"
                offer = null
            }
        }
        Row("No, only links for Vision TB", "") { offer = null }
    }
    reports.forEach { r ->
        val mine = r.key.startsWith("addon:")
        val on = TbSources.isOn(r.key)
        val color = when (r.tone) {
            TbSources.Tone.GOOD -> Color(0xFF7BD88F); TbSources.Tone.WARN -> Color(0xFFFFC66D); TbSources.Tone.BAD -> Color(0xFFFF7A7A)
            else -> Palette.TextDim
        }
        val facts = if (r.searches == 0) "Not asked yet" else listOfNotNull(
            "${r.searches} searches",
            "only source ${r.reachPct}%",
            if (r.picks > 0) "${r.picks} plays (${r.solePicks} only here)" else null,
            if (r.medianMs > 0) "~%.1f s".format(r.medianMs / 1000.0) else null,
            if (r.avgDelayMs >= 500) "+%.1f s autoplay".format(r.avgDelayMs / 1000.0) else null
        ).joinToString(" · ")
        Focusable({ open = if (open == r.key) null else r.key }, Modifier.padding(top = 10.dp).fillMaxWidth(0.7f)) { f ->
            Column(Modifier.fillMaxWidth().background(if (f) Color(0x33FFFFFF) else Palette.Card).padding(14.dp)) {
                Text((if (mine) "" else "Built-in · ") + r.label, color = if (on) Palette.Text else Palette.TextDim, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(r.verdict, color = color, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                Text(facts, color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (open == r.key) {
            Row(if (on) "Turn off" else "Turn on", if (on) "Kept, but not asked" else "Asked again in every search") { TbSources.setOn(r.key, !on) }
            if (TbSources.pausedSince(r.key) != null) Row("Try it again now", "Asked in the next search; stays if it answers") { TbSources.unpause(r.key) }
            if (mine) {
                Row("Edit (replace the link)", "From your phone; its score starts over") { TbSources.remove(r.key); adding = true; open = null }
                Row("Remove", "No longer asked") { TbSources.remove(r.key); open = null; msg = "Removed ${r.label}" }
            }
        }
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

/** The once-only notice when a Vision TB source breaks or comes back (not over a playing video). */
@Composable
fun TbAlertHost(inPlayer: Boolean) {
    val alert by TbSources.alert.collectAsState()
    val a = alert ?: return
    if (inPlayer) return
    LaunchedEffect(a) { kotlinx.coroutines.delay(12_000); TbSources.alertSeen() }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = androidx.compose.ui.Alignment.TopEnd) {
        Text(a, color = Color.White, fontSize = 14.sp, modifier = Modifier.fillMaxWidth(0.42f)
            .background(Color(0xE6222226), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(16.dp))
    }
}
