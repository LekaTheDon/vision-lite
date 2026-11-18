package com.lekathedon.visionlite.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Plain shared preferences, read once at start; settings the UI watches are StateFlows. */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("vl", Context.MODE_PRIVATE)

    inner class Flag(private val key: String, default: Boolean) {
        private val state = MutableStateFlow(sp.getBoolean(key, default))
        val flow: StateFlow<Boolean> get() = state
        var value: Boolean
            get() = state.value
            set(v) { state.value = v; sp.edit().putBoolean(key, v).apply() }
    }

    inner class Text(private val key: String, default: String) {
        private val state = MutableStateFlow(sp.getString(key, default) ?: default)
        val flow: StateFlow<String> get() = state
        var value: String
            get() = state.value
            set(v) { state.value = v; sp.edit().putString(key, v).apply() }
    }

    /** Pure black page instead of the colour tint. */
    val oledBlack = Flag("oled_black", false)
    /** Play picks the best link by itself (off until autoplay is safe). */
    val autoplay = Flag("autoplay", false)
    /** Dolby Vision 7: "fel" = native FEL port (AM9 FEL kernel), "box" = the box's normal decoder. */
    val dv7 = Text("dv7", "box")
    /** Frame-rate matching: "off", "seamless" (only switches the TV can do without a blink), "always". */
    val afr = Text("afr", "off")
    /** The first-start device check was answered (Use these or Not now). */
    val deviceAsked = Flag("device_asked", false)
}
