package com.lekathedon.visionlite.home

import com.lekathedon.visionlite.App
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What you've watched, for Home's Watched titles setting (Vision's Show / Dim / Hide). Asked from Trakt only when the
 * setting isn't Show: two calls, at most every 6 hours (and again after the player closes).
 */
object Watched {
    private val state = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> get() = state
    private var at = 0L

    suspend fun refresh(force: Boolean = false) {
        if (App.instance.prefs.watchedOnHome.value == "show") return
        if (!force && System.currentTimeMillis() - at < 6 * 3600_000L) return
        App.instance.trakt.watchedIds()?.let { state.value = it; at = System.currentTimeMillis() }
    }
}
