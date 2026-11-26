package com.lekathedon.visionlite.integrations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.lekathedon.visionlite.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.integrations.Integrations.Group
import com.lekathedon.visionlite.integrations.Integrations.Service
import com.lekathedon.visionlite.phone.PhoneBox
import com.lekathedon.visionlite.ui.Button
import com.lekathedon.visionlite.ui.Focusable
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.Screen
import kotlinx.coroutines.launch

/** Redraws when keys, drivers, helpers or the Trakt sign-in change. */
@Composable
private fun watchChanges() {
    Integrations.changed.collectAsState().value
    App.instance.trakt.connected.collectAsState().value
}

private fun roleOf(s: Service): String = when {
    !Integrations.isSet(s) -> if (s.fields.isEmpty() && !s.signIn) "Not in Lite yet" else "Not set up"
    s.live == null -> "Key kept · not in Lite yet, makes no calls"
    s.group == Group.EXTRAS -> "On"
    Integrations.driver(s.group)?.id == s.id -> "Driving"
    Integrations.helper(s).value -> "Helper · on"
    else -> "Helper · off (no calls)"
}

private fun checkText(s: Service): String = when (Integrations.status(s).value) {
    "ok" -> " · key works"
    "refused" -> " · key refused"
    else -> ""
}

/**
 * First start (once): every integration and tracker, one page each; enter its key from the phone or skip.
 * Ends on the driving seats, then Home.
 */
@Composable
fun Setup(nav: Nav) {
    watchChanges()
    // Nuvio is asked first (user 2026-11-23): signing in brings your whole setup from the account, so it isn't a step.
    val steps = Integrations.all.filter { (it.fields.isNotEmpty() || it.signIn) && it.id != "nuvio" }
    var step by rememberSaveable { mutableStateOf(-1) }
    val first = remember { FocusRequester() }
    val nuvio = SignIns.signedIn("nuvio")
    LaunchedEffect(step, nuvio) { runCatching { first.requestFocus() } }
    androidx.activity.compose.BackHandler(enabled = step >= 0) { step-- }
    Column(Modifier.fillMaxSize().background(LocalLook.current.page).padding(48.dp).verticalScroll(rememberScrollState())) {
        when {
            step < 0 -> {
                Title(if (nuvio) "Signed in to Nuvio" else "Integrations and tracking")
                Body((if (nuvio) "Your account has no Vision setup yet (keys you saved in Nuvio came over). Set up the rest here once; " +
                    "it's kept in your Nuvio account, so other devices get all of it when you sign in there. " else "") +
                    "One page per service: enter its key from your phone, or skip it. Anything not set up makes no calls. " +
                    "All of this stays in Settings → Integrations.")
                Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button("Start", { step = 0 }, Modifier.focusRequester(first))
                    Button("Skip all", { Integrations.setupDone.value = true })
                }
            }
            step < steps.size -> {
                val s = steps[step]
                Text("${step + 1} of ${steps.size} · ${s.group.title}", color = Palette.TextDim, fontSize = 14.sp)
                ServicePanel(s, nav, first, setup = true, next = { step++ }, back = { step-- })
            }
            else -> {
                Title("Who drives what")
                Body("The driver is where the app takes its answer from; helpers get the same plays or fill gaps. Change any time in Settings → Integrations.")
                Drivers(first)
                Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button("Done", { Integrations.setupDone.value = true }, if (Group.entries.none { many(it) }) Modifier.focusRequester(first) else Modifier)
                    Button("Back", { step-- })
                }
            }
        }
    }
}

private fun many(g: Group) = g != Group.EXTRAS && Integrations.inGroup(g).count { Integrations.isSet(it) } >= 1

/** One row per group with something set up: its driver (OK = next eligible one) and the helpers' switches. */
@Composable
private fun Drivers(first: FocusRequester?) {
    var firstUsed = false
    Group.entries.filter { many(it) }.forEach { g ->
        val set = Integrations.inGroup(g).filter { Integrations.isSet(it) }
        val able = set.filter { it.live != null }
        val driver = Integrations.driver(g)
        Section(g.title)
        val mod = if (first != null && !firstUsed) Modifier.focusRequester(first).also { firstUsed = true } else Modifier
        Item("Driver: ${driver?.name ?: "none yet"}",
            when {
                driver == null -> "None of these works in Lite yet: ${set.joinToString { it.name }} (keys kept, no calls)"
                able.size > 1 -> "${g.driverDoes}. OK switches to ${able[(able.indexOf(driver) + 1) % able.size].name}"
                else -> g.driverDoes
            }, mod) {
            if (driver != null && able.size > 1) Integrations.setDriver(g, able[(able.indexOf(driver) + 1) % able.size])
        }
        set.filter { it.id != driver?.id }.forEach { s ->
            if (s.live == null) Item("${s.name}: not in Lite yet", "Key kept; it can drive or help once it works here. Makes no calls.") {}
            else {
                val on = Integrations.helper(s).value
                Item("${s.name} helps: ${if (on) "On" else "Off"}", if (on) g.helperDoes else "Off: makes no calls") { Integrations.setHelper(s, !on) }
            }
        }
    }
}

/** Settings → Integrations: every service with its role; OK opens it. Driving seats on top. */
@Composable
fun IntegrationsScreen(nav: Nav) {
    watchChanges()
    var open by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(open) { runCatching { first.requestFocus() } }
    open?.let { id ->
        androidx.activity.compose.BackHandler { open = null }
        Column(Modifier.fillMaxSize().background(LocalLook.current.page).padding(48.dp).verticalScroll(rememberScrollState())) {
            Text(Integrations.byId(id).group.title, color = Palette.TextDim, fontSize = 14.sp)
            ServicePanel(Integrations.byId(id), nav, first, setup = false, next = { open = null }, back = { open = null })
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().background(LocalLook.current.page), contentPadding = PaddingValues(48.dp)) {
        item { Title("Integrations and tracking") }
        item { Item("Run the setup again", "One page per service, then the driving seats", Modifier.focusRequester(first)) { Integrations.setupDone.value = false; nav.pop(); nav.pop() } }
        item { Column { Drivers(null) } }
        Group.entries.forEach { g ->
            item { Section("All · ${g.title}") }
            Integrations.inGroup(g).forEach { s ->
                item { Item("${s.name}: ${roleOf(s)}${checkText(s)}", s.live?.let { "In Lite: $it" } ?: "Coming to Lite") { open = s.id } }
            }
        }
    }
}

/** Settings → one service, opened from the page it serves (Links, Tracking, Title pages, Recommendations). */
@Composable
fun ServiceScreen(nav: Nav, id: String) {
    watchChanges()
    val first = remember { FocusRequester() }
    LaunchedEffect(id) { androidx.compose.runtime.withFrameNanos { }; runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize().background(LocalLook.current.page).padding(48.dp).verticalScroll(rememberScrollState())) {
        Text(Integrations.byId(id).group.title, color = Palette.TextDim, fontSize = 14.sp)
        ServicePanel(Integrations.byId(id), nav, first, setup = false, next = { nav.pop() }, back = { nav.pop() })
    }
}

/** One service: what it does here, where its key comes from, enter / check / remove, driver or helper. */
@Composable
private fun ServicePanel(s: Service, nav: Nav, first: FocusRequester, setup: Boolean, next: () -> Unit, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    var asking by remember(s.id) { mutableStateOf(false) }
    var note by remember(s.id) { mutableStateOf("") }
    val set = Integrations.isSet(s)
    LaunchedEffect(asking) { if (!asking) runCatching { first.requestFocus() } }
    Title(s.name)
    Body(s.live?.let { "In Lite: $it." } ?: "Not in Lite yet: its key is kept for when it arrives and it makes no calls until then.")
    Body("Where: ${s.where}")
    Body("Status: ${roleOf(s)}${checkText(s)}${if (note.isNotEmpty()) " · $note" else ""}")
    Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            s.signIn -> Button(if (set) "Signed in" else "Sign in", { nav.push(if (s.id == "trakt") Screen.Trakt else Screen.SignIn(s.id)) }, Modifier.focusRequester(first))
            s.id == "jellyfin" -> {
                Button(if (com.lekathedon.visionlite.streams.MediaServers.jfAddress().isBlank()) "Enter address" else "Change address", { asking = true }, if (com.lekathedon.visionlite.streams.MediaServers.jfAddress().isBlank()) Modifier.focusRequester(first) else Modifier)
                if (com.lekathedon.visionlite.streams.MediaServers.jfAddress().isNotBlank()) Button(if (set) "Signed in" else "Quick Connect", { nav.push(Screen.SignIn(s.id)) }, Modifier.focusRequester(first))
            }
            s.fields.isNotEmpty() -> Button(if (set) "Change key" else "Enter key", { asking = true }, Modifier.focusRequester(first))
            else -> Button(if (setup) "Next" else "Back", if (setup) next else back, Modifier.focusRequester(first))
        }
        if (setup) Button(if (set) "Next" else "Skip", next)
        if (set && s.check != null) Button("Check key", { note = "checking…"; scope.launch { note = ""; Integrations.check(s) } })
        if (set) Button(if (s.signIn) "Sign out" else "Remove", { Integrations.clear(s) })
        if (setup) Button("Back", back)
    }
    if (set && s.live != null && s.group != Group.EXTRAS && Integrations.driver(s.group)?.id != s.id) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button("Make it the driver", { Integrations.setDriver(s.group, s) })
        val on = Integrations.helper(s).value
        Button(if (on) "Helper: On" else "Helper: Off", { Integrations.setHelper(s, !on) })
    }
    if (asking) PhoneBox(s.name, s.fields.mapIndexed { i, f -> f.copy(initial = Integrations.values(s).getOrElse(i) { "" }) },
        onSave = { v ->
            asking = false
            if (v.firstOrNull().orEmpty().isNotBlank()) {
                Integrations.save(s, v)
                if (s.check != null) { note = "checking…"; scope.launch { note = ""; Integrations.check(s) } }
            }
        },
        onCancel = { asking = false })
}

@Composable
private fun Title(t: String) = Text(t, color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))

@Composable
private fun Body(t: String) = Text(t, color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp).fillMaxWidth(0.7f))

@Composable
private fun Section(t: String) = Text(t, color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 22.dp, bottom = 2.dp))

@Composable
private fun Item(title: String, sub: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Focusable(onClick, modifier.padding(top = 10.dp).fillMaxWidth(0.6f)) { f ->
        Column(Modifier.fillMaxWidth().background(if (f) com.lekathedon.visionlite.ui.Kit.card2 else com.lekathedon.visionlite.ui.Kit.rowFill).padding(14.dp)) {
            Text(title, color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (sub.isNotEmpty()) Text(sub, color = Palette.TextDim, fontSize = 13.sp)
        }
    }
}
