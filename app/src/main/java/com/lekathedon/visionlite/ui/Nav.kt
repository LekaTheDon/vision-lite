package com.lekathedon.visionlite.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf

/** Screens and the back stack. No navigation library: a list of screens, the last one drawn. */
sealed interface Screen {
    data object Home : Screen
    data class Detail(val type: String, val id: String, val name: String) : Screen
    /** A VOD series from a Home row (no TMDB match): its episodes. */
    data class VodSeries(val pl: String, val id: String, val name: String) : Screen
    data class Streams(val type: String, val id: String, val title: String, val metaId: String, val manual: Boolean = false) : Screen
    /** [type]/[id] = what is playing ("tt…" or "tt…:s:e"), for Trakt scrobbling and resume. */
    /** [name]: the link's own text (release name, size, …) for the engine pick and Stats; [addon]: where it came from. */
    data class Player(val url: String, val title: String, val headers: Map<String, String> = emptyMap(), val type: String = "", val id: String = "",
                      val name: String = "", val addon: String = "", val bytes: Long = 0) : Screen
    data object Search : Screen
    data object Library : Screen
    data object LiveTv : Screen
    data object Sports : Screen
    data object SportsTeams : Screen
    data object SportsMyTeams : Screen
    data class SportsGame(val id: String) : Screen
    data class Standings(val leagueId: String) : Screen
    data object Vod : Screen
    data object Recordings : Screen
    data object Discover : Screen
    data class Genre(val slug: String) : Screen
    data class Decade(val slug: String) : Screen
    data class Mood(val key: String) : Screen
    /** Scout (recs/Scout.kt): "build", "ask", "mixes" or a slice id. */
    data class Scout(val id: String) : Screen
    data object Calendar : Screen
    data class Service(val id: String) : Screen
    data class Person(val id: String, val name: String) : Screen
    data class YouTube(val tab: String = "HOME") : Screen
    data class YtPlay(val id: String, val title: String) : Screen
    data class Music(val tab: String = "HOME") : Screen
    data object Settings : Screen
    /** Settings › Profiles, and one profile's page (name, picture, PIN…). */
    data object Profiles : Screen
    data class ProfileEdit(val id: Int) : Screen
    data object Addons : Screen
    data object ThisTv : Screen
    data object Trakt : Screen
    data object Rows : Screen
    data object Interests : Screen
    data object LeaveOut : Screen
    data object VisionTb : Screen
    data object Integrations : Screen
    data object PhoneLink : Screen
    data class SignIn(val id: String) : Screen
    data class Integration(val id: String) : Screen
    /** Report a problem; [kind] = what ended the app last time ("crash", "anr", "memory"…), "" when opened by hand. */
    data class Report(val kind: String = "") : Screen
}

@Stable
class Nav {
    private val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()
    val canPop: Boolean get() = stack.size > 1
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun popToHome() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun replace(s: Screen) { stack[stack.lastIndex] = s }
}
