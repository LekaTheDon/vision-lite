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

/** Home rows: on/off, up/down, refresh. Same model as Home (activity-scoped), so changes show at once. */
@Composable
fun Rows() {
    val vm: HomeModel = viewModel()
    val prefs = App.instance.prefs
    val rows by vm.rows.collectAsState()
    val hiddenText by prefs.rowsHidden.flow.collectAsState()
    val hidden = hiddenText.split('\n').filter { it.isNotEmpty() }.toSet()
    var msg by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    fun move(key: String, by: Int) {
        val keys = rows.map { it.key }.toMutableList()
        val i = keys.indexOf(key); val j = (i + by).coerceIn(0, keys.lastIndex)
        if (i < 0 || i == j) return
        keys.add(j, keys.removeAt(i))
        prefs.rowOrder.value = keys.joinToString("\n")
    }
    LazyColumn(Modifier.fillMaxSize().background(LocalLook.current.page), contentPadding = PaddingValues(48.dp)) {
        item {
            Text("Home rows", color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button("Refresh all rows", { vm.refreshAll(); msg = "Rows will load fresh" }, Modifier.focusRequester(first))
                Button("Reset order", { prefs.rowOrder.value = ""; prefs.rowsHidden.value = "" })
                if (msg.isNotEmpty()) Text(msg, color = Palette.TextDim, fontSize = 13.sp)
            }
        }
        items(rows, key = { it.key }) { r ->
            val on = r.key !in hidden
            Row(Modifier.padding(top = 10.dp).fillMaxWidth(0.8f).background(Palette.Card).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.title + " · " + r.addon.manifest.name, color = if (on) Palette.Text else Palette.TextDim, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Button(if (on) "On" else "Off", { prefs.rowsHidden.value = (if (on) hidden + r.key else hidden - r.key).joinToString("\n") })
                Button("Up", { move(r.key, -1) })
                Button("Down", { move(r.key, 1) })
            }
        }
    }
}
