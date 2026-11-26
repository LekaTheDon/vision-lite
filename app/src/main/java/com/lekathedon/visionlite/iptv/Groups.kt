package com.lekathedon.visionlite.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import com.lekathedon.visionlite.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.ui.Focusable
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Palette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Your order and hidden groups (TiviMate's group options) for Live TV ("live") and VOD ("movie" / "series").
 * Stored as "kind\tgroup" lines; hidden groups are left out everywhere (lists, All channels, Up / Down) but never deleted.
 */
object Groups {
    private val _rev = MutableStateFlow(0)
    /** Bumps on every change, so lists read again. */
    val rev: StateFlow<Int> = _rev

    private fun lines(v: String, kind: String) = v.split('\n').mapNotNull { l -> l.split('\t', limit = 2).takeIf { it.size == 2 && it[0] == kind }?.get(1) }
    fun order(kind: String): List<String> = lines(App.instance.prefs.vodOrder.value, kind)
    fun hidden(kind: String): Set<String> = lines(App.instance.prefs.groupsHidden.value, kind).toSet()

    /** [list] in your order (then the provider's), hidden ones out unless [withHidden]. */
    fun <T> arrange(kind: String, list: List<T>, name: (T) -> String, withHidden: Boolean = false): List<T> {
        val at = order(kind).withIndex().associate { it.value to it.index }
        val hid = if (withHidden) emptySet() else hidden(kind)
        return list.filter { name(it) !in hid }.withIndex().sortedBy { (i, g) -> at[name(g)] ?: (at.size + i) }.map { it.value }
    }

    fun saveOrder(kind: String, groups: List<String>) {
        val p = App.instance.prefs.vodOrder
        p.value = (p.value.split('\n').filter { it.isNotEmpty() && !it.startsWith("$kind\t") } + groups.map { "$kind\t$it" }).joinToString("\n")
        _rev.value++
    }

    fun setHidden(kind: String, group: String, hide: Boolean) {
        val p = App.instance.prefs.groupsHidden
        val rest = p.value.split('\n').filter { it.isNotEmpty() && it != "$kind\t$group" }
        p.value = (if (hide) rest + "$kind\t$group" else rest).joinToString("\n")
        _rev.value++
    }

    fun reset(kind: String) {
        val o = App.instance.prefs.vodOrder; val h = App.instance.prefs.groupsHidden
        o.value = o.value.split('\n').filter { it.isNotEmpty() && !it.startsWith("$kind\t") }.joinToString("\n")
        h.value = h.value.split('\n').filter { it.isNotEmpty() && !it.startsWith("$kind\t") }.joinToString("\n")
        _rev.value++
    }
}

/**
 * Arrange and hide groups: OK picks one up, Up / Down moves it, OK puts it down; Right hides or shows it again.
 * Saved at once. Back closes.
 */
@Composable
fun GroupArrange(title: String, kind: String, all: List<String>, onChanged: () -> Unit, close: () -> Unit) {
    val list = remember(kind) { mutableStateListOf<String>().also { it.addAll(all) } }
    var hidden by remember(kind) { mutableStateOf(Groups.hidden(kind)) }
    var held by remember { mutableStateOf<String?>(null) }
    val reqs = remember { HashMap<String, FocusRequester>() }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun move(by: Int): Boolean {
        val g = held ?: return false
        val i = list.indexOf(g); val j = i + by
        if (i < 0 || j !in list.indices) return true
        list.removeAt(i); list.add(j, g)
        Groups.saveOrder(kind, list.toList()); onChanged()
        scope.launch { state.animateScrollToItem((j - 3).coerceAtLeast(0)); runCatching { reqs[g]?.requestFocus() } }
        return true
    }
    Column(Modifier.fillMaxSize().background(LocalLook.current.page).padding(start = 40.dp, end = 24.dp, top = 24.dp)) {
        Text(title, color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("OK picks a group up, Up / Down moves it, OK puts it down · Right hides or shows it · ${hidden.size} hidden · Back when done",
            color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 10.dp)) {
            com.lekathedon.visionlite.ui.Button("Provider's order, show all", {
                held = null; Groups.reset(kind); onChanged(); close()
            })
        }
        LazyColumn(Modifier.fillMaxSize().padding(top = 10.dp), state = state, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 60.dp)) {
            items(list, key = { it }) { g ->
                val req = reqs.getOrPut(g) { FocusRequester() }
                val mine = held == g
                val off = g in hidden
                Focusable({ held = if (mine) null else g }, Modifier.fillMaxWidth().focusRequester(req)
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) false
                        else if (!mine && e.key == Key.DirectionRight) {
                            Groups.setHidden(kind, g, !off); hidden = Groups.hidden(kind); onChanged(); true
                        } else if (!mine) false
                        else when (e.key) { Key.DirectionUp -> move(-1); Key.DirectionDown -> move(1); else -> false }
                    }) { f ->
                    Row(Modifier.fillMaxWidth().background(if (mine) com.lekathedon.visionlite.ui.KitInk.accent else if (f) com.lekathedon.visionlite.ui.KitInk.bg else com.lekathedon.visionlite.ui.Kit.rowFill).padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text((if (mine) "↕  " else "") + g.ifEmpty { "(no group)" }, color = when { f || mine -> Color.Black; off -> Palette.TextDim; else -> Palette.Text },
                            fontSize = 16.sp, fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        Text(if (off) "Hidden" else "Shown", color = if (f || mine) Color.DarkGray else if (off) Color(0xFFFF8A80) else Palette.TextDim, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
