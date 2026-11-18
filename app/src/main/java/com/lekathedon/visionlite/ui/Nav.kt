package com.lekathedon.visionlite.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf

/** Screens and the back stack. No navigation library: a list of screens, the last one drawn. */
sealed interface Screen {
    data object Home : Screen
    data class Detail(val type: String, val id: String, val name: String) : Screen
    data class Streams(val type: String, val id: String, val title: String, val metaId: String) : Screen
    /** [type]/[id] = what is playing ("tt…" or "tt…:s:e"), for Trakt scrobbling and resume. */
    data class Player(val url: String, val title: String, val headers: Map<String, String> = emptyMap(), val type: String = "", val id: String = "") : Screen
    data object Search : Screen
    data object Library : Screen
    data object Settings : Screen
    data object Addons : Screen
    data object ThisTv : Screen
    data object Trakt : Screen
    data object Rows : Screen
    data object VisionTb : Screen
    data object Integrations : Screen
}

@Stable
class Nav {
    private val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()
    val canPop: Boolean get() = stack.size > 1
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun replace(s: Screen) { stack[stack.lastIndex] = s }
}
