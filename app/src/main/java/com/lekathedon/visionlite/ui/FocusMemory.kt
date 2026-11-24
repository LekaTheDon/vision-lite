package com.lekathedon.visionlite.ui

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch

/**
 * Back lands exactly where you were (user 2026-11-24: "get back to exactly the same place, on the same button I
 * pressed"). One per screen on the back stack (Nav). Every focusable part ([Focusable], Kit buttons, cards) gets an id
 * that survives the screen being closed and drawn again (rememberSaveable under the screen's own saved state); the
 * last focused id is kept, and when the screen comes back that part takes focus again.
 *
 * Cost: one FocusRequester + a map entry per focusable part on screen; nothing at all on Home (it has its own).
 */
class FocusMemory {
    var last: Long = -1L
    internal var pending: Long = -1L
    private var pendingUntil = 0L
    internal val reqs = HashMap<Long, FocusRequester>()

    /** The screen is coming back: put focus where it was (the part may appear a little later, when its data is in). */
    fun comeBack() { if (last >= 0) { pending = last; pendingUntil = System.currentTimeMillis() + 2500 } }
    /** Still putting focus back: a screen's own first focus waits. */
    val restoring: Boolean get() = pending >= 0 && System.currentTimeMillis() < pendingUntil
    /** Focus back on part [id] (a dialog or menu closed); false when it isn't on screen. */
    fun focus(id: Long): Boolean = reqs[id]?.let { runCatching { it.requestFocus() }.isSuccess } == true
    internal fun done() { pending = -1L }

    companion object { private var n = 0L; @Synchronized internal fun nextId() = ++n }
}

val LocalFocusMemory = staticCompositionLocalOf<FocusMemory?> { null }

/**
 * Remembered focus for one part, plus Up at the top of a page: when Up finds nothing above, whatever scrolls the part
 * (page, dialog list) goes up as far as it can, so a heading or the top of the page is never left hidden.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Modifier.remembersFocus(isFocused: () -> Boolean): Modifier {
    val mem = LocalFocusMemory.current ?: return this
    val id = rememberSaveable { FocusMemory.nextId() }
    val req = remember { FocusRequester() }
    DisposableEffect(mem, id) { mem.reqs[id] = req; onDispose { if (mem.reqs[id] === req) mem.reqs.remove(id) } }
    LaunchedEffect(mem, id) {
        if (mem.pending == id && mem.restoring) {
            repeat(3) { withFrameNanos { } }
            if (mem.pending == id && runCatching { req.requestFocus() }.isSuccess) mem.done()
        }
    }
    val biv = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val h = remember { intArrayOf(0, 0) }
    val up = with(LocalDensity.current) { 700.dp.toPx() }
    return this.focusRequester(req)
        .onFocusChanged { if (it.isFocused || it.hasFocus) mem.last = id }
        .bringIntoViewRequester(biv)
        .onSizeChanged { h[0] = it.width; h[1] = it.height }
        .onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp && isFocused()) scope.launch {
                withFrameNanos { }; withFrameNanos { }
                // Still here: nothing above took focus, so show what's above (scrolls stop at their top).
                if (isFocused()) runCatching { biv.bringIntoView(Rect(0f, -up, h[0].toFloat(), -up + 1f)) }
            }
            false
        }
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
