package com.lekathedon.visionlite.device

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.lekathedon.visionlite.ui.Palette

/** The findings in plain words, one line each (Settings › This TV and the first-start card). */
fun findings(f: Facts): List<String> = buildList {
    add("TV picture: " + if (!f.capsKnown) "HDR formats can't be read" else listOfNotNull(
        "Dolby Vision".takeIf { f.dvTv }, "HDR10".takeIf { f.hdr10 }, "HDR10+".takeIf { f.hdr10Plus }, "HLG".takeIf { f.hlg }
    ).ifEmpty { listOf("SDR only") }.joinToString(", "))
    if (f.width > 0) add("TV mode: ${f.width}x${f.height}, " + if (f.rates.size > 1) "switches refresh rate" else "one refresh rate")
    add("Box: " + listOfNotNull("native FEL".takeIf { f.fel }, "Dolby Vision 7 decoder".takeIf { f.dv7Decoder },
        "Dolby Vision decoder".takeIf { f.dvDecoder && !f.dv7Decoder }, "Dolby Vision 8.1 converter".takeIf { f.converter }, "AV1 chip".takeIf { f.av1Hw }
    ).ifEmpty { listOf("no Dolby Vision extras") }.joinToString(", ") + if (f.ramMb > 0) " · %.1f GB memory".format(f.ramMb / 1024.0) else "")
    add("Sound: " + if (!f.soundKnown) "the box can't read what the soundbar takes" else listOfNotNull(
        "Dolby Digital".takeIf { "ac3" in f.sound }, "DD+".takeIf { "eac3" in f.sound }, "Atmos".takeIf { "joc" in f.sound || "truehd" in f.sound },
        "TrueHD".takeIf { "truehd" in f.sound }, "DTS".takeIf { "dts" in f.sound }, "DTS-HD".takeIf { "dtshd" in f.sound }
    ).ifEmpty { listOf("PCM only (no passthrough)") }.joinToString(", "))
    add("Internet: " + (f.mbps?.let { "%.0f Mb/s".format(it) } ?: "not tested yet"))
}

/**
 * First start: the check runs (picture, decoders, soundbar, memory, internet speed), then one card: what it found,
 * how many settings it suggests, and one press to use them. "Let the app decide" also keeps them up to date when the
 * TV or soundbar changes. "I'll choose" opens Settings › This TV.
 */
@Composable
fun FirstStartCheck(onChoose: () -> Unit) {
    val app = App.instance
    val context = LocalContext.current
    var step by remember { mutableStateOf("Getting ready…") }
    var facts by remember { mutableStateOf<Facts?>(null) }
    LaunchedEffect(Unit) { facts = DeviceCheck.probe(context, speed = true) { step = it } }
    val first = remember { FocusRequester() }
    LaunchedEffect(facts) { if (facts != null) { androidx.compose.runtime.withFrameNanos { }; runCatching { first.requestFocus() } } }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(660.dp).background(Color(0xFF141418)).padding(28.dp).verticalScroll(rememberScrollState())) {
            Text("Setting up for this TV", color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
            val f = facts
            if (f == null) { Text(step, color = Palette.TextDim, fontSize = 15.sp); return@Column }
            findings(f).forEach { Text(it, color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp)) }
            val diff = DeviceCheck.differing(f)
            Text(if (diff.isEmpty()) "Everything is already set right for this TV." else "${diff.size} settings suggested for this TV:",
                color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
            diff.forEach { c -> Text("${c.title}: ${c.label(c.recommended)}", color = Color(0xFF8FD18F), fontSize = 14.sp) }
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button("Use recommended", { DeviceCheck.useRecommended(f) }, Modifier.focusRequester(first))
                Button("Let the app decide", { app.prefs.tvAuto.value = true; DeviceCheck.useRecommended(f) })
                Button("I'll choose", { app.prefs.deviceAsked.value = true; onChoose() })
            }
            Text("Let the app decide also updates these when you change TV or soundbar. All of it stays in Settings › This TV.",
                color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}
