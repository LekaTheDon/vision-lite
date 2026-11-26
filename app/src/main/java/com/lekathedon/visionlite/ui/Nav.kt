package com.lekathedon.visionlite.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf

/** Screens and the back stack. No navigation library: a list of screens, the last one drawn. */
sealed interface Screen {
    data object Home : Screen
    /** [season]: open on that season (an anime season card grouped under its IMDb show). */
    data class Detail(val type: String, val id: String, val name: String, val season: Int? = null) : Screen
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
    /** Your debrid cloud (streams/Cloud.kt). */
    data object Cloud : Screen
    data object Discover : Screen
    data class Genre(val slug: String) : Screen
    data class Decade(val slug: String) : Screen
    data class Mood(val key: String) : Screen
    /** Scout (recs/Scout.kt): "build", "ask", "mixes" or a slice id. */
    data class Scout(val id: String) : Screen
    data object Calendar : Screen
    data class Service(val id: String) : Screen
    data class Person(val id: String, val name: String) : Screen
    /** A production company's page (IMDb co id): its films and shows. */
    data class Company(val id: String, val name: String) : Screen
    data object Settings : Screen
    /** Settings › Profiles, and one profile's page (name, picture, PIN…). */
    data object Profiles : Screen
    data class ProfileEdit(val id: Int) : Screen
    data object Addons : Screen
    data object ThisTv : Screen
    data object Trakt : Screen
    data object Rows : Screen
    data object Interests : Screen
    /** First start's taste questions (also Settings › Home and look › Taste questions). */
    data object TasteStart : Screen
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
    /** Per stack entry: its saved-state key and its [FocusMemory] (Back lands on the part you left from). */
    private val keys = mutableListOf(0L)
    private val mems = mutableListOf(FocusMemory())
    private var nextKey = 1L
    /** Saved state of screens taken off the stack, for the host to drop. */
    val dropped = ArrayList<String>()
    val current: Screen get() = stack.last()
    val canPop: Boolean get() = stack.size > 1
    /** The top screen's saved-state key and focus memory. */
    val key: String get() = "s" + keys.last()
    val memory: FocusMemory get() = mems.last()
    fun push(s: Screen) { keys.add(nextKey++); mems.add(FocusMemory()); stack.add(s) }
    fun pop() {
        if (stack.size <= 1) return
        dropped += "s" + keys.removeAt(keys.lastIndex); mems.removeAt(mems.lastIndex)
        mems.last().comeBack()
        stack.removeAt(stack.lastIndex)
    }
    fun popToHome() { while (stack.size > 1) pop() }
    fun replace(s: Screen) {
        dropped += "s" + keys.last(); keys[keys.lastIndex] = nextKey++; mems[mems.lastIndex] = FocusMemory()
        stack[stack.lastIndex] = s
    }
}
