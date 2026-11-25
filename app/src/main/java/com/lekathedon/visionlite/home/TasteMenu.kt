// Love it / Like / Not for me on any title anywhere in the app (pages, search, library, details' "more like this"):
// hold OK on a poster. Everything goes to the one engine (Vision Picks + Scout + the AIs + Moods), so a title turned
// down never shows in any recommendation again. Home's rows have their own hold menu with the same three choices.
package com.lekathedon.visionlite.home

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lekathedon.visionlite.addons.MetaPreview
import com.lekathedon.visionlite.library.Saved
import com.lekathedon.visionlite.library.Saves
import com.lekathedon.visionlite.recs.Recs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

object TasteMenu {
    internal val open = MutableStateFlow<MetaPreview?>(null)
    internal val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    /** Opens the menu for [m] (titles only: films and shows). */
    fun show(m: MetaPreview) { if (m.type == "movie" || m.type == "series") open.value = m }
    fun isTitle(m: MetaPreview) = m.type == "movie" || m.type == "series"
}

/** Watched titles (from the engine's feedback) plus ones just pressed: the cards' tick. */
object Ticks {
    private val pending = MutableStateFlow<Set<String>>(emptySet())
    private val all = MutableStateFlow<Set<String>>(emptySet())
    val ids: kotlinx.coroutines.flow.StateFlow<Set<String>> get() = all
    init {
        TasteMenu.scope.launch {
            kotlinx.coroutines.flow.combine(Recs.changed, pending) { _, p -> Recs.watchedIds() + p }.collect { all.value = it }
        }
    }
    /** The tick shows at once; ~1.2 s later the title is marked, and recommendation rows drop it. */
    fun watched(m: MetaPreview) {
        pending.value = pending.value + m.id
        TasteMenu.scope.launch {
            kotlinx.coroutines.delay(1200)
            Recs.watched(m.id, m.type == "series", m.name)
            pending.value = pending.value - m.id
        }
    }
}

/** Drawn once at the app's root: the menu over whatever page asked. A dialog, so focus comes back to the poster. */
@Composable
fun TasteMenuHost() {
    val m by TasteMenu.open.collectAsState()
    val cur = m ?: return
    val close = { TasteMenu.open.value = null }
    val tv = cur.type == "series"
    val verdict = remember(cur.id) { Recs.verdict(cur.id) }
    val saved = remember(cur.id) { Saves.has(cur.id) }
    val scope = TasteMenu.scope
    val first = remember { FocusRequester() }
    // The hold that opened it is still on OK: its repeats and release must not pick the first choice.
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(cur.id) { withFrameNanos { }; runCatching { first.requestFocus() } }
    Dialog(onDismissRequest = close) {
        com.lekathedon.visionlite.ui.KitPopup(cur.name,
            Modifier.width(380.dp)
                .onPreviewKeyEvent { e ->
                    val k = e.nativeKeyEvent
                    val ok = k.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || k.keyCode == KeyEvent.KEYCODE_ENTER || k.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                    if (!ok || armed) false
                    else { if (k.action == KeyEvent.ACTION_DOWN && k.repeatCount == 0) { armed = true; false } else true }
                },
            sub = "Love and Like steer every recommendation; Not for me and Watched are never recommended again."
        ) {
            com.lekathedon.visionlite.ui.KitMenuItem(if (verdict == "love") "Loved ✓ (undo)" else "Love it", {
                if (verdict == "love") Recs.forget(cur.id) else Recs.love(cur.id, tv, cur.name); close()
            }, Modifier.fillMaxWidth().focusRequester(first))
            com.lekathedon.visionlite.ui.KitMenuItem(if (verdict == "like") "Liked ✓ (undo)" else "Like · more like this", {
                if (verdict == "like") Recs.forget(cur.id) else Recs.moreLikeThis(cur.id, tv, cur.name); close()
            }, Modifier.fillMaxWidth())
            com.lekathedon.visionlite.ui.KitMenuItem(if (verdict == "nope") "Not for me ✓ (undo)" else "Not for me", {
                if (verdict == "nope") Recs.forget(cur.id) else Recs.notForMe(cur.id, tv, cur.name); close()
            }, Modifier.fillMaxWidth())
            com.lekathedon.visionlite.ui.KitMenuItem(if (verdict == "watched") "Watched ✓ (undo)" else "Watched", {
                if (verdict == "watched") Recs.forget(cur.id) else Ticks.watched(cur); close()
            }, Modifier.fillMaxWidth())
            if (Rate.available()) com.lekathedon.visionlite.ui.KitMenuItem(Rate.mine(cur.id).takeIf { it > 0 }?.let { "Rated $it/10 on Trakt (change)" } ?: "Rate on Trakt…", {
                close(); scope.launch { imdbIdOf(cur)?.let { id -> Rate.ask(id, tv, cur.name) } }
            }, Modifier.fillMaxWidth())
            com.lekathedon.visionlite.ui.KitMenuItem(if (saved) "Remove from Library" else "Add to Library", {
                scope.launch { imdbIdOf(cur)?.let { id -> Saves.toggle(Saved(cur.type, id, cur.name, cur.poster)) } }; close()
            }, Modifier.fillMaxWidth())
            com.lekathedon.visionlite.ui.KitMenuItem("Cancel", { close() }, Modifier.fillMaxWidth())
        }
    }
}
