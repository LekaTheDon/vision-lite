package com.lekathedon.visionlite.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * TiviMate-fast lists (channels, groups): a held Up / Down moves one row per key repeat with an instant scroll instead
 * of chaining scroll animations (which fall behind and keep scrolling after you let go); a held Left jumps to the top
 * of a list that asks for it (groups: Favourites). A single press still moves as before.
 * Cost: nothing until a key is held.
 */
class FastList(val state: LazyListState, private val scope: CoroutineScope) {
    var target by mutableIntStateOf(-1)
        private set
    private val focus = FocusRequester()
    private var job: Job? = null

    /** Goes on row [i]'s focusable modifier. */
    fun mod(i: Int): Modifier = if (i == target) Modifier.focusRequester(focus) else Modifier

    /** Call from row [i]'s key handler; true = handled. [leftTop]: a held Left goes to row 0. */
    fun onKey(e: KeyEvent, i: Int, size: Int, leftTop: Boolean = false): Boolean {
        if (e.type != KeyEventType.KeyDown || e.nativeKeyEvent.repeatCount == 0 || size <= 0) return false
        val n = when (e.key) {
            Key.DirectionDown -> (i + 1).coerceAtMost(size - 1)
            Key.DirectionUp -> (i - 1).coerceAtLeast(0)
            Key.DirectionLeft -> if (leftTop) 0 else return false
            else -> return false
        }
        go(n)
        return true
    }

    fun go(n: Int) {
        target = n
        job?.cancel()
        job = scope.launch {
            val seen = state.layoutInfo.visibleItemsInfo
            val first = seen.firstOrNull()?.index ?: 0
            val last = seen.lastOrNull()?.index ?: 0
            when {
                n == 0 -> state.scrollToItem(0)
                n <= first -> state.scrollToItem((n - 1).coerceAtLeast(0))
                n >= last -> state.scrollToItem((n - (last - first) + 2).coerceAtLeast(0))
            }
            withFrameNanos { }
            runCatching { focus.requestFocus() }
        }
    }
}

@Composable
fun rememberFastList(state: LazyListState = rememberLazyListState()): FastList {
    val scope = rememberCoroutineScope()
    return remember(state) { FastList(state, scope) }
}
