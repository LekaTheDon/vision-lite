package com.lekathedon.visionlite.report

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import com.lekathedon.visionlite.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.phone.PhoneBox
import com.lekathedon.visionlite.phone.PhoneForm
import com.lekathedon.visionlite.ui.Button
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.Qr
import kotlinx.coroutines.launch

private val WHATS = listOf(
    "crash" to "Crashed or closed", "play" to "Wouldn't play", "stutter" to "Stutters or buffers",
    "slow" to "Slow or laggy", "picture" to "Picture or sound wrong", "other" to "Something else",
)

/**
 * Report a problem: pick what happened (one press), add words if you like (TV keyboard or phone), then one press
 * collects the report. It's sent to the team when this build has the report key, and it always opens on the phone
 * by QR (Copy / Share / Download), so it can be passed on by hand.
 */
@Composable
fun ReportScreen(nav: Nav, kind: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var what by remember { mutableStateOf(if (kind.isNotEmpty()) "crash" else "") }
    var note by remember { mutableStateOf("") }
    var byPhone by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var built by remember { mutableStateOf<Report.Built?>(null) }
    var pageUrl by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; runCatching { first.requestFocus() } }

    // The phone page lives while this screen shows the report.
    DisposableEffect(built) {
        val b = built
        val ip = PhoneForm.lanIp(ctx)
        val server = if (b != null && ip != null) ReportServer.start("vision-lite-report-${b.id}.txt", b.text) else null
        pageUrl = server?.url(ip!!)
        onDispose { server?.stop() }
    }

    fun make() {
        if (busy) return
        val label = WHATS.firstOrNull { it.first == what }?.second ?: "Not said"
        busy = true; status = "Collecting the report…"
        scope.launch {
            val b = runCatching { Report.build(ctx, label, note) }.getOrNull()
            if (b == null) { busy = false; status = "The report couldn't be made"; return@launch }
            built = b
            Problems.markSeen()
            status = if (Send.available) {
                status = "Sending…"
                Send.github(Report.title(label), b.text).fold(
                    { "Sent to the Nuvio Vision team. Thanks! (report ${b.id})" },
                    { "Couldn't send it (${it.message}). Open it on your phone below and send it to us." })
            } else "Report ${b.id} is ready. Open it on your phone below and send it to us."
            busy = false
        }
    }

    Box(Modifier.fillMaxSize().background(LocalLook.current.page)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 56.dp, vertical = 36.dp)) {
            Text("Report a problem", color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("It sends this box's details, how the app ended the last times and the app's own log. Keys, logins and personal links are blanked first.",
                color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            if (kind.isNotEmpty()) Text("Last time: " + kindTitle(kind), color = Color(0xFFFFB86B), fontSize = 15.sp, modifier = Modifier.padding(bottom = 10.dp))

            Text("What happened?", color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            WHATS.chunked(3).forEachIndexed { r, row ->
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEachIndexed { i, (id, label) ->
                        Button((if (what == id) "✓ " else "") + label, { what = id },
                            if (r == 0 && i == 0) Modifier.focusRequester(first) else Modifier)
                    }
                }
            }

            Text("Anything else? (optional: what you pressed, which title)", color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(note, { note = it.take(2000) }, singleLine = true,
                    textStyle = TextStyle(color = Palette.Text, fontSize = 15.sp), cursorBrush = SolidColor(Color.White),
                    modifier = Modifier.width(520.dp).background(Palette.Card).padding(12.dp),
                    decorationBox = { inner -> if (note.isEmpty()) Text("Type here, or on your phone", color = Palette.TextDim, fontSize = 15.sp); inner() })
                Button("Type on phone", { byPhone = true })
            }
            if (byPhone) PhoneBox("Describe the problem",
                listOf(PhoneForm.Field("What happened", "What you pressed, which title, what you saw", initial = note)),
                onSave = { v -> byPhone = false; note = v.firstOrNull().orEmpty() },
                onCancel = { byPhone = false })

            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(when { busy -> "Working…"; built != null -> "Make it again"; Send.available -> "Send report"; else -> "Make the report" }, { make() })
                Button("Back", { nav.pop() })
            }
            if (status.isNotEmpty()) Text(status, color = Palette.Text, fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp))

            val b = built
            if (b != null) {
                Row(Modifier.padding(top = 16.dp).fillMaxWidth().background(Palette.Card).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    val u = pageUrl
                    if (u != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Qr(u, 190.dp)
                        Text(u, color = Palette.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("On your phone", color = Palette.Text, fontSize = 18.sp)
                        Text(if (u != null) "Scan it (same Wi-Fi as the TV), then Share or Download and send the file to us. The page works while this screen is open."
                             else "The box isn't on a home network the phone can reach, so the phone page can't open.", color = Palette.TextDim, fontSize = 13.sp)
                        Text("Report ${b.id} · ${b.text.length / 1024} KB · ${b.logLines} log lines", color = Palette.TextDim, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

fun kindTitle(kind: String) = when (kind) {
    "crash" -> "Nuvio Vision crashed"
    "native" -> "Nuvio Vision crashed (in the player or a native part)"
    "anr" -> "Nuvio Vision froze and Android closed it"
    "memory" -> "Android closed Nuvio Vision: the box ran out of memory"
    "killed" -> "Android closed Nuvio Vision while it was on screen"
    else -> "Something went wrong"
}

/** Next start after a crash, a freeze or a memory kill: one card, alone on screen (nothing behind it takes focus). */
@Composable
fun ProblemPrompt(p: Problems.Problem, onReport: () -> Unit, onLater: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(620.dp).background(com.lekathedon.visionlite.ui.Kit.panel).border(1.dp, com.lekathedon.visionlite.ui.Kit.line).padding(28.dp)) {
            Text(p.title, color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Sending a report tells us what happened on this box (details and the app's log; no keys or logins).",
                color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button("Report it", onReport, Modifier.focusRequester(first))
                Button("Not now", onLater)
            }
        }
    }
}
