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

    // Playback
    val nextEpisode = Flag("next_episode", false)
    /** Skip intro / recap / outro / credits: "button" (OK skips), "auto", "off" (nothing asked). */
    val skipIntro = Text("skip_intro", "button")
    /** Next-episode card near the end (at the outro when known); with [nextEpisode] it counts down 10 s. */
    val nextPopup = Flag("next_popup", true)
    val resume = Flag("resume", true)
    /** Seconds to step back when playing again after a pause ("0" = off). */
    val rewindOnResume = Text("rewind_on_resume", "0")
    // Audio & subtitles ("" = no preference; ISO 639 codes)
    val audioLang = Text("audio_lang", "")
    val audioLang2 = Text("audio_lang2", "")
    val subLang = Text("sub_lang", "")
    val subLang2 = Text("sub_lang2", "")
    /** "off", "foreign" (only when the audio isn't in a subtitle language), "always". */
    val subsWhen = Text("subs_when", "foreign")
    /** "s", "m", "l", "xl". */
    val subSize = Text("sub_size", "m")
    /** "white", "yellow", "box", "outline". */
    val subStyle = Text("sub_style", "outline")
    // Streams
    /** "addon" (installed order), "quality", "biggest", "smallest". */
    val streamSort = Text("stream_sort", "addon")
    /** Highest resolution listed: "0" = any, else 2160 / 1080 / 720. */
    val maxQuality = Text("max_quality", "0")
    /** Largest file in GB ("0" = no limit). */
    val sizeLimit = Text("size_limit", "0")
    val hideTorrents = Flag("hide_torrents", false)
    val hideCam = Flag("hide_cam", true)
    // Home
    /** "s", "m", "l". */
    val posterSize = Text("poster_size", "m")
    /** "echo" (Vision's Echo look) or "classic". */
    val homeLook = Text("home_look", "echo")
    /** Trailers on Home (Vision's IMDb source): after [trailerDelay] seconds of rest on the billboard or an opened poster. */
    val trailers = Flag("trailers", true)
    val trailerDelay = Text("trailer_delay", "5")
    val trailerSound = Flag("trailer_sound", true)
    val clock = Flag("clock", true)
    val ratings = Flag("ratings", false)
    /** Row keys in the user's order (newline separated); rows not listed go last in add-on order. */
    val rowOrder = Text("row_order", "")
    val rowsHidden = Text("rows_hidden", "")
    // Library + search
    /** Local saves, JSON list. */
    val saved = Text("saved", "[]")
    /** Last searches, newline separated, newest first. */
    val recentSearches = Text("recent_searches", "")
    // Vision TB (off until switched on; the key comes from the phone form, never from the repo)
    /** Recommendations: IMDb minimum ("0" = off) and the More like this / Not for me marks. */
    val recMinImdb = Text("rec_min_imdb", "7")
    val recFeedback = Text("rec_feedback", "[]")
    /** Live TV: playlists (JSON; Xtream passwords stay on this TV) and favourite channels. */
    val iptvPlaylists = Text("iptv_playlists", "[]")
    val iptvFavs = Text("iptv_favs", "")
    val visionTb = Flag("vision_tb", false)
    val torboxKey = Text("torbox_key", "")
    // App
    val exitConfirm = Flag("exit_confirm", true)
}
