package com.lekathedon.visionlite.settings

import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.graphics.Color
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
fun Rows(nav: com.lekathedon.visionlite.ui.Nav) {
    var hub by remember { mutableStateOf(com.lekathedon.visionlite.anime.Hubs.now()) }
    androidx.compose.runtime.key(hub) { RowsOf(hub, nav) { hub = it } }
}

@Composable
private fun RowsOf(hub: com.lekathedon.visionlite.anime.Hub, nav: com.lekathedon.visionlite.ui.Nav, pick: (com.lekathedon.visionlite.anime.Hub) -> Unit) {
    val vm = com.lekathedon.visionlite.home.homeModel(hub)
    val prefs = App.instance.prefs
    val rowOrder = com.lekathedon.visionlite.anime.Hubs.rowOrder(hub)
    val rowsHidden = com.lekathedon.visionlite.anime.Hubs.rowsHidden(hub)
    val rows by vm.rows.collectAsState()
    val hiddenText by rowsHidden.flow.collectAsState()
    val hidden = hiddenText.split('\n').filter { it.isNotEmpty() }.toSet()
    var msg by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var choosingVod by remember { mutableStateOf(false) }
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
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
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
                val vodPicked by prefs.vodRows.flow.collectAsState()
                val nVod = vodPicked.split('\n').count { it.isNotEmpty() }
                Button(if (nVod == 0) "VOD rows: None" else "VOD rows: $nVod", { choosingVod = true })
                Button("Add a catalog", { adding = true })
                Button("Build your own row", { nav.push(com.lekathedon.visionlite.ui.Screen.Scout(if (hub == com.lekathedon.visionlite.anime.Hub.ANIME) "abuild" else "build")) })
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
    if (choosingVod) VodRowsPrompt { choosingVod = false }
    }
}

/** Pick which of your playlist's VOD categories are Home rows (this profile). Arabic ones first; none picked by default. */
@Composable
fun VodRowsPrompt(onDone: () -> Unit) {
    val p = App.instance.prefs
    val groups by androidx.compose.runtime.produceState<List<com.lekathedon.visionlite.iptv.VodArabic.Group>?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.lekathedon.visionlite.iptv.VodArabic.all() }
    }
    val picked = remember { androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(p.vodRows.value.split('\n').filter { it.isNotEmpty() }) } }
    val first = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    androidx.activity.compose.BackHandler { onDone() }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)), contentAlignment = Alignment.Center) {
        com.lekathedon.visionlite.ui.Inside(true) { LazyColumn(Modifier.fillMaxWidth(0.6f).fillMaxSize(0.9f)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(com.lekathedon.visionlite.ui.Kit.cardR))
            .background(com.lekathedon.visionlite.ui.Kit.card.copy(alpha = 0.98f))
            .border(1.dp, com.lekathedon.visionlite.ui.Kit.line, androidx.compose.foundation.shape.RoundedCornerShape(com.lekathedon.visionlite.ui.Kit.cardR))
            .padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("VOD rows on Home", color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(when {
                    groups == null -> "Reading your VOD…"
                    groups!!.isEmpty() -> "No VOD yet: add a playlist in Live TV and open VOD once."
                    else -> "Pick the categories to show as rows in this profile (anime ones go to the Anime hub). Arabic categories first."
                }, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button("Save", { p.vodRows.value = picked.joinToString("\n"); onDone() }, Modifier.focusRequester(first))
                    Button("Select none", { picked.clear() })
                    Button("Cancel", onDone)
                }
            }
            val list = groups.orEmpty()
            items(list.size) { i ->
                val g = list[i]; val on = g.key in picked
                Button((if (on) "✓  " else "     ") + g.grp + "  ·  " + (if (g.kind == "series") "Series" else "Films") + " · ${g.count}" + (if (g.anime) " · Anime hub" else ""),
                    { if (on) picked.remove(g.key) else picked.add(g.key) }, Modifier.fillMaxWidth())
            }
        } }
    }
}
