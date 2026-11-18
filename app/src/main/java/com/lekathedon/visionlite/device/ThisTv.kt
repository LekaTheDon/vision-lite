package com.lekathedon.visionlite.device

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.ui.Button
import com.lekathedon.visionlite.ui.Focusable
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Palette

/** What the check found + one row per setting (OK cycles the options). Starts from [fromCurrent] or the recommendations. */
@Composable
private fun CheckBody(f: Findings, picks: MutableMap<String, String>, first: FocusRequester?) {
    f.lines.forEach { Text(it, color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp)) }
    if (f.choices.isEmpty()) Text("Nothing to choose on this setup: everything follows the TV and soundbar.", color = Palette.Text, fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp))
    f.choices.forEachIndexed { i, c ->
        val pick = picks[c.key] ?: c.recommended
        Focusable({ picks[c.key] = c.next(pick) }, (if (i == 0 && first != null) Modifier.focusRequester(first) else Modifier).padding(top = 12.dp).fillMaxWidth()) { foc ->
            Column(Modifier.fillMaxWidth().background(if (foc) Color(0x33FFFFFF) else Palette.Card).padding(14.dp)) {
                Text(c.title, color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(c.label(pick) + if (pick == c.recommended) "  · Recommended" else "", color = Color(0xFF8FD18F), fontSize = 15.sp)
                Text(c.why, color = Palette.TextDim, fontSize = 12.sp)
            }
        }
    }
}

/** First start: "Set up Vision Lite for this TV" over Home. Nothing changes unless "Use these" is pressed. */
@Composable
fun FirstStartCheck() {
    val app = App.instance
    val context = LocalContext.current
    val f = remember { DeviceCheck.run(context) }
    if (f.choices.isEmpty()) { LaunchedEffect(Unit) { app.prefs.deviceAsked.value = true }; return }
    val picks = remember { mutableStateMapOf<String, String>() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(640.dp).background(Color(0xFF141418)).padding(28.dp).verticalScroll(rememberScrollState())) {
            Text("Set up Vision Lite for this TV", color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
            CheckBody(f, picks, first)
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button("Use these", { DeviceCheck.apply(app.prefs, f.choices.associate { it.key to (picks[it.key] ?: it.recommended) }) })
                Button("Not now", { app.prefs.deviceAsked.value = true })
            }
        }
    }
}

/** Settings > This TV: same rows, from the current settings; Save, Use all recommended, Reassess. */
@Composable
fun ThisTv() {
    val app = App.instance
    val context = LocalContext.current
    var round by remember { mutableStateOf(0) }
    val f = remember(round) { DeviceCheck.run(context) }
    val picks = remember(round) { mutableStateMapOf<String, String>().apply { f.choices.forEach { c -> DeviceCheck.current(app.prefs, c.key).takeIf { v -> c.options.any { it.first == v } }?.let { put(c.key, it) } } } }
    var msg by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(round) { runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize().background(LocalLook.current.page).padding(48.dp).verticalScroll(rememberScrollState())) {
        Text("This TV", color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 14.dp))
        Column(Modifier.width(680.dp)) { CheckBody(f, picks, first) }
        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button("Save", { DeviceCheck.apply(app.prefs, f.choices.associate { it.key to (picks[it.key] ?: it.recommended) }); msg = "Saved" })
            Button("Use all recommended", { f.choices.forEach { picks[it.key] = it.recommended }; DeviceCheck.apply(app.prefs, picks.toMap()); msg = "Recommended settings saved" })
            Button("Reassess", { round++; msg = "Checked again" })
        }
        if (msg.isNotEmpty()) Text(msg, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

