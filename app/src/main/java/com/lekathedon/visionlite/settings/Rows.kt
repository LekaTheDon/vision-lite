package com.lekathedon.visionlite.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.home.HomeModel
import com.lekathedon.visionlite.ui.Button
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Palette

/** Home rows: on/off, up/down, refresh. Same model as Home (activity-scoped), so changes show at once. One set per hub. */
@Composable
fun Rows() {
    var hub by remember { mutableStateOf(com.lekathedon.visionlite.anime.Hubs.now()) }
    androidx.compose.runtime.key(hub) { RowsOf(hub) { hub = it } }
}

@Composable
private fun RowsOf(hub: com.lekathedon.visionlite.anime.Hub, pick: (com.lekathedon.visionlite.anime.Hub) -> Unit) {
    val vm = com.lekathedon.visionlite.home.homeModel(hub)
    val prefs = App.instance.prefs
    val rowOrder = com.lekathedon.visionlite.anime.Hubs.rowOrder(hub)
    val rowsHidden = com.lekathedon.visionlite.anime.Hubs.rowsHidden(hub)
    val rows by vm.rows.collectAsState()
    val hiddenText by rowsHidden.flow.collectAsState()
    val hidden = hiddenText.split('\n').filter { it.isNotEmpty() }.toSet()
    var msg by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    fun move(key: String, by: Int) {
        val keys = rows.map { it.key }.toMutableList()
        val i = keys.indexOf(key); val j = (i + by).coerceIn(0, keys.lastIndex)
        if (i < 0 || i == j) return
        keys.add(j, keys.removeAt(i))
        rowOrder.value = keys.joinToString("\n")
    }
    LazyColumn(Modifier.fillMaxSize().background(LocalLook.current.page), contentPadding = PaddingValues(48.dp)) {
        item {
            Text("Home rows", color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
            Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                com.lekathedon.visionlite.anime.Hub.entries.forEach { h -> Button(if (h == hub) "● ${h.label}" else h.label, { pick(h) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button("Refresh all rows", { vm.refreshAll(); msg = "Rows will load fresh" }, Modifier.focusRequester(first))
                Button("Reset order", { rowOrder.value = ""; rowsHidden.value = "" })
                val addonRows by prefs.addonRows.flow.collectAsState()
                Button(if (addonRows) "Add-on rows: On" else "Add-on rows: Off", { prefs.addonRows.value = !addonRows })
                val vodAr by prefs.vodArabic.flow.collectAsState()
                Button(if (vodAr) "Arabic VOD rows: On" else "Arabic VOD rows: Off", {
                    prefs.vodArabic.value = !vodAr
                    msg = if (vodAr) "Arabic VOD rows off" else com.lekathedon.visionlite.iptv.VodArabic.groups().let { g ->
                        if (g.isEmpty()) "No Arabic categories in your VOD" else "${g.count { !it.anime }} rows in Films & TV, ${g.count { it.anime }} in Anime: hide any below"
                    }
                })
                Button("Add a catalog", { adding = true })
                val rotation by prefs.rotation.flow.collectAsState()
                Button(if (rotation) "Rotating rows: On" else "Rotating rows: Off", {
                    prefs.rotation.value = !rotation
                    if (rotation) { com.lekathedon.visionlite.home.Rotation.clear(); msg = "Rotating rows off" }
                    else { scope.launch { com.lekathedon.visionlite.home.Rotation.shuffleNow(hub) }; msg = "Building a set of rotating rows…" }
                })
                if (rotation) Button("Shuffle now", { scope.launch { com.lekathedon.visionlite.home.Rotation.shuffleNow(hub) }; msg = "New rotating rows coming up…" })
                if (msg.isNotEmpty()) Text(msg, color = Palette.TextDim, fontSize = 13.sp)
            }
            // A catalog by its link: scan the QR and paste it on the phone, or type it here.
            if (adding) com.lekathedon.visionlite.phone.PhoneBox("Add a catalog", listOf(
                com.lekathedon.visionlite.phone.PhoneForm.Field("List link", "trakt.tv/users/…/lists/…, mdblist.com/lists/…, imdb.com/list/ls…"),
                com.lekathedon.visionlite.phone.PhoneForm.Field("Row name (optional)", "Its own name when empty")),
                onSave = { v -> adding = false; scope.launch { msg = com.lekathedon.visionlite.home.ImportedLists.addLink(v[0], v.getOrElse(1) { "" }) } },
                onCancel = { adding = false })
        }
        items(rows, key = { it.key }) { r ->
            val on = r.key !in hidden
            Row(Modifier.padding(top = 10.dp).fillMaxWidth(0.8f).background(Palette.Card).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.title + " · " + r.source, color = if (on) Palette.Text else Palette.TextDim, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Button(if (on) "On" else "Off", { rowsHidden.value = (if (on) hidden + r.key else hidden - r.key).joinToString("\n") })
                Button("Up", { move(r.key, -1) })
                Button("Down", { move(r.key, 1) })
                if (r.key.startsWith("list:")) Button("Remove", { com.lekathedon.visionlite.home.ImportedLists.remove(r.key); msg = "Removed ${r.title}" })
            }
        }
    }
}
