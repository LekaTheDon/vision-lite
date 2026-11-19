package com.lekathedon.visionlite.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Plain shared preferences, read once at start; settings the UI watches are StateFlows. */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("vl", Context.MODE_PRIVATE)
    /** Every Flag and Text by key, so a change from the phone reaches the screens watching it ([setByKey]). */
    private val byKey = HashMap<String, (String) -> Unit>()

    /** Sets a Flag ("true"/"false") or Text by its key; false when no such setting. */
    fun setByKey(key: String, v: String): Boolean = byKey[key]?.let { it(v); true } ?: false

    /** For the phone link: also turns the link on/off with the setting. */
    val phoneLink by lazy { Flag("phone_link", false) }
    /** Lists added from the phone (Trakt / MDBList / IMDb Picks), each a Home row: JSON array of the phone's list choices. */
    val importedLists by lazy { Text("imported_lists", "[]") }
    /** The phone's reminder bells (show keys) and Live TV programme reminders: kept here so the phone's alarms follow the TV. */
    val phoneBells by lazy { Text("phone_bells", "") }
    val phoneLive by lazy { Text("phone_live", "[]") }
    /** What happened to each change the phone sent (id -> applied / failed: why), the last 200. */
    val phoneQueue by lazy { Text("phone_queue", "{}") }

    inner class Flag(private val key: String, default: Boolean) {
        private val state = MutableStateFlow(sp.getBoolean(key, default))
        init { byKey[key] = { value = it == "true" } }
        val flow: StateFlow<Boolean> get() = state
        var value: Boolean
            get() = state.value
            set(v) { state.value = v; sp.edit().putBoolean(key, v).apply() }
    }

    inner class Text(private val key: String, default: String) {
        private val state = MutableStateFlow(sp.getString(key, default) ?: default)
        init { byKey[key] = { value = it } }
        val flow: StateFlow<String> get() = state
        var value: String
            get() = state.value
            set(v) { state.value = v; sp.edit().putString(key, v).apply() }
    }

    /** Pure black page instead of the colour tint. */
    val oledBlack = Flag("oled_black", false)
    /** Play picks the best link by itself (off until autoplay is safe). */
    val autoplay = Flag("autoplay", false)
    /** IMDb ids dropped from Continue watching (hold menu), one per line; playing the title again brings it back. */
    val cwDropped = Text("cw_dropped", "")
    /** Dolby Vision 7: "fel" = native FEL port (AM9 FEL kernel), "box" = the box's normal decoder. */
    val dv7 = Text("dv7", "box")
    /** Frame-rate matching: "off" or "always" (an old "seamless" counts as on). */
    val afr = Text("afr", "off")
    /** Match resolution: the TV takes the smallest mode that fits the video (never below 720p). */
    val matchRes = Flag("match_res", false)
    init { if (afr.value == "seamless") afr.value = "always" }
    /** The first-start device check was answered (Use these or Not now). */
    val deviceAsked = Flag("device_asked", false)

    // Playback
    /** Player engine: "auto" (Vision's Smart Auto), "exo" (ExoPlayer) or "mpv". */
    val engine = Text("player_engine", "auto")
    /** When one engine can't play a file, the other one takes over at the same spot (Vision's engine failover). */
    val engineBackup = Flag("engine_backup", true)
    /** Styled ASS/SSA subtitles (anime) drawn by libass on ExoPlayer, with the file's own fonts, colours and positions. */
    val libass = Flag("libass", true)
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
    /** Watched titles in Home rows (not Continue watching): "show", "dim" or "hide" (Vision's Show / Dim / Hide). */
    val watchedOnHome = Text("watched_home", "show")
    /** The pill's section (Vision's AppSection): "NUVIO" or "IPTV" (YouTube and Music join as they're built). */
    val appSection = Text("app_section", "NUVIO")
    val ratings = Flag("ratings", false)
    // Details page (Vision's IMDb parts)
    /** Episode IMDb ratings: "all", "watched" (hide on episodes not watched yet: no spoilers), "off". */
    val epRatings = Text("detail_ep_ratings", "all")
    /** Reviews row source: "imdb" or "trakt". */
    val reviewsSource = Text("detail_reviews", "imdb")
    val sequelPrequel = Flag("detail_sequel_prequel", true)
    val connections = Flag("detail_connections", true)
    /** Row keys in the user's order (newline separated); rows not listed go last in add-on order. */
    val rowOrder = Text("row_order", "")
    val rowsHidden = Text("rows_hidden", "")
    /** Add-on catalogs as Home rows (Vision's "Add-on rows"); off like the user's Vision. */
    val addonRows = Flag("home_addon_rows", false)
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
    val iptvRecent = Text("iptv_recent", "")
    val recordings = Text("recordings", "")
    val ytTokens = Text("yt_tokens", "")
    /** Sports: Vision's settings and follows (JSON, "" = defaults). */
    val sportsSettings = Text("sports_settings", "")
    val sportsFollows = Text("sports_follows", "")
    val visionTb = Flag("vision_tb", false)
    val torboxKey = Text("torbox_key", "")
    // App
    val exitConfirm = Flag("exit_confirm", true)
}
