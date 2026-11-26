package com.lekathedon.visionlite.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Plain shared preferences, read once at start; settings the UI watches are StateFlows. */
class Prefs(context: Context) {
    val sp: SharedPreferences = run { Profiles.init(context); Profiles.prefs() }
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

    /** Who's watching? at app start when there's more than one profile (box-wide; data/Profiles.kt). */
    val askProfile = Flag("lp_ask_start", true)
    /** Pure black page instead of the colour tint. */
    val oledBlack = Flag("oled_black", false)
    /** Colour skin (ui/Look.kt Skin): oled, tint, night, ember, moss. First run takes the old OLED switch. */
    val skin = Text("skin", if (oledBlack.value) "oled" else "tint")
    /** The look past Home (ui/Look.kt Style): marquee (Haven's, default since 0.37.1), tonal, halo. */
    val style = Text("style", "marquee").also { t ->
        if (!sp.getBoolean("style_marquee_v1", false)) { t.value = "marquee"; sp.edit().putBoolean("style_marquee_v1", true).apply() }
    }
    /** Buttons, icons and switches past Home: smallest, small (default, a third under 0.37.0), medium, large. */
    val uiSize = Text("ui_size", "small")
    /** Home look: on = Home follows the look and skin (fonts, colours, pills); off = Home's own look (as in 0.39.1). */
    val homeLook = Flag("home_follows", true)
    /** Play picks the best link by itself (off until autoplay is safe). */
    val autoplay = Flag("autoplay", false)
    /** IMDb ids dropped from Continue watching (hold menu), one per line; playing the title again brings it back. */
    val cwDropped = Text("cw_dropped", "")
    /** Dolby Vision 7: "fel" = native FEL port (AM9 FEL kernel), "box" = the box's normal decoder, "dv81" = converted to 8.1 (libdovi), "hdr10" = HDR10 base layer only. */
    val dv7 = Text("dv7", "box")
    /** Frame-rate matching: "off" or "always" (an old "seamless" counts as on). */
    val afr = Text("afr", "off")
    /** Match resolution: the TV takes the smallest mode that fits the video (never below 720p). */
    val matchRes = Flag("match_res", false)
    /** Quiet playback: 4 s into a title, other apps are closed (root: force-stopped). Off = nothing runs. */
    val quietPlayback = Flag("quiet_playback", false)
    /** Rate on Trakt after finishing a film or a show's episode (only when Trakt is signed in). */
    val rateAfter = Flag("rate_after", true)
    init { if (afr.value == "seamless") afr.value = "always" }
    /** The first-start device check was answered (Use these or Not now). */
    val deviceAsked = Flag("device_asked", false)
    /** Passthrough per format (only offered for formats the soundbar/TV reports); off = decoded in the box. */
    val ptAc3 = Flag("pt_ac3", true)
    val ptEac3 = Flag("pt_eac3", true)
    val ptTruehd = Flag("pt_truehd", true)
    val ptDts = Flag("pt_dts", true)
    val ptDtshd = Flag("pt_dtshd", true)
    /** Dolby Vision profile 5 converted to 8.1 (libdovi) on a TV without Dolby Vision. */
    val dv5To81 = Flag("dv5_to_81", false)
    /** Dolby Vision profile 5 on a box without a Dolby Vision decoder: MPV reshapes its colours on the GPU (libplacebo). */
    val dv5Mpv = Flag("dv5_mpv", false)
    /** HDR on a TV without HDR: "box" = the box / TV converts it, "mpv" = MPV tone-maps it on the GPU. */
    val hdrOnSdr = Text("hdr_on_sdr", "box")
    /** Sound system: "soundbar" (passthrough what it takes), "tv" (TV speakers: decoded, mixed to stereo), "headphones". */
    val soundOut = Text("sound_out", "soundbar")
    /** Night mode: "off", "light", "medium", "strong" (quiet parts up, loud parts held). */
    val nightMode = Text("night_mode", "off")
    /** Dialogue boost in dB (the centre channel up): "0", "3", "6", "9". */
    val dialogueBoost = Text("dialogue_boost", "0")
    /** Volume boost in dB with a limiter: "0", "3", "6", "9", "12". */
    val volumeBoost = Text("volume_boost", "0")
    /** HDR10+ data dropped (the TV shows HDR10 but not HDR10+). */
    val stripHdr10Plus = Flag("strip_hdr10plus", false)
    /** Playback buffer: "small", "normal" or "large" (large only offered with 3+ GB of RAM). */
    val buffer = Text("buffer", "normal")
    /** Live rewind (timeshift) in minutes kept on disk per channel; "0" = off. */
    val liveRewind = Text("live_rewind", "30")
    /** Live player remote buttons you assigned, "BUTTON=ACTION;…" (player/LiveKeys.kt); empty = defaults. */
    val liveKeys = Text("live_keys", "")
    /** Let the app decide: the device check reruns when the TV or soundbar changes and applies its recommendations. */
    val tvAuto = Flag("tv_auto", false)
    /** The last device check's findings (JSON, DeviceCheck.Facts). */
    val tvFacts = Text("tv_facts", "")
    /** Device settings you picked yourself over the recommendation, "key=value;…": automatic re-checks leave them alone. */
    val tvMine = Text("tv_mine", "")
    /** Performance class chosen in This TV ("light"/"standard"/"full"); "" = measured from memory (device/Tier.kt). */
    val perfClass = Text("perf_class", "")
    /** Newest app exit (ms) already looked at by the "closed unexpectedly" prompt (report/Problems.kt). */
    val exitSeen = Text("exit_seen", "0")

    // Playback
    /** Player engine: "auto" (Vision's Smart Auto), "exo" (ExoPlayer) or "mpv". */
    val engine = Text("player_engine", "auto")
    /** When one engine can't play a file, the other one takes over at the same spot (Vision's engine failover). */
    val engineBackup = Flag("engine_backup", true)
    /** Styled ASS/SSA subtitles (anime) drawn by libass on ExoPlayer, with the file's own fonts, colours and positions. */
    val libass = Flag("libass", true)
    /** Vision's transparent letterbox: the app paints nothing around the film, so the bars are the TV's own true black
     *  (the app's black goes grey on HDR/Dolby Vision output). Off on Fire TV, as Vision (its compositor needs the paint). */
    val blackBars = Flag("black_bars", !android.os.Build.MANUFACTURER.orEmpty().trim().equals("Amazon", ignoreCase = true))
    /** Vision's HDR subtitle style: on HDR/Dolby Vision films subtitles are white, dimmed to [subHdrBright] (0-100). */
    val subHdr = Flag("sub_hdr", true)
    val subHdrBright = Text("sub_hdr_bright", "58")
    val nextEpisode = Flag("next_episode", false)
    /** Skip intro / recap / outro / credits: "button" (OK skips), "auto", "off" (nothing asked). */
    val skipIntro = Text("skip_intro", "button")
    /** Next-episode card near the end (at the outro when known); with [nextEpisode] it counts down 10 s. */
    val nextPopup = Flag("next_popup", true)
    val resume = Flag("resume", true)
    /** Seconds to step back when playing again after a pause ("0" = off). */
    val rewindOnResume = Text("rewind_on_resume", "0")
    /** Player controls size, percent of the original (user 2026-11-24: too big). Films, shows and Live TV. */
    val controlsSize = Text("controls_size", "80")
    // Audio & subtitles ("" = no preference; ISO 639 codes)
    val audioLang = Text("audio_lang", "original")
    val audioLang2 = Text("audio_lang2", "")
    /** Once (2026-11-19, user): audio "Any" becomes "Original language", so dubs never start by themselves. */
    init { if (!sp.getBoolean("audio_orig_v1", false)) { if (audioLang.value.isEmpty()) audioLang.value = "original"; sp.edit().putBoolean("audio_orig_v1", true).apply() } }
    val subLang = Text("sub_lang", "")
    val subLang2 = Text("sub_lang2", "")
    /** "off", "foreign" (only when the audio isn't in a subtitle language), "always". */
    val subsWhen = Text("subs_when", "foreign")
    /** Add-on subtitles: [sighs], (door creaks), "JOHN:" and lone ♪ taken out as they load (player/SubFix.kt). */
    val subsStripSdh = Flag("subs_strip_sdh", true)
    /** Add-on subtitles lined up with the file's own subtitle lines while it plays (player/SubFix.kt). */
    val subsAutoSync = Flag("subs_auto_sync", true)
    /** "s", "m", "l", "xl". */
    val subSize = Text("sub_size", "m")
    /** "white", "yellow", "box", "outline". */
    val subStyle = Text("sub_style", "outline")
    /** Old Vision's subtitle look (player/SubStyle.kt): colours as AARRGGBB, edge shadow/outline/none, offset in %. */
    val subColor = Text("sub_color", "FFFFFFFF")
    val subEdge = Text("sub_edge", "shadow")
    val subOutlineColor = Text("sub_outline_color", "FF000000")
    val subBack = Text("sub_back", "00000000")
    val subBold = Flag("sub_bold", false)
    val subPos = Text("sub_pos", "5")
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
    /** Trailers on Home (Vision's IMDb source): after [trailerDelay] seconds of rest on the billboard or an opened poster. */
    val trailers = Flag("trailers", true)
    val trailerDelay = Text("trailer_delay", "5")
    val trailerSound = Flag("trailer_sound", true)
    val clock = Flag("clock", true)
    /** Watched titles in Home rows (not Continue watching): "show", "dim" or "hide" (Vision's Show / Dim / Hide). */
    val watchedOnHome = Text("watched_home", "show")
    /** The pill's section (Vision's AppSection): "NUVIO", "ANIME" or "IPTV". */
    val appSection = Text("app_section", "NUVIO").also { if (it.value == "YOUTUBE" || it.value == "MUSIC") it.value = "NUVIO" }
    val ratings = Flag("ratings", false)
    // Details page (Vision's IMDb parts)
    /** Episode IMDb ratings: "all", "watched" (hide on episodes not watched yet: no spoilers), "off". */
    val epRatings = Text("detail_ep_ratings", "all")
    /** Spoiler blur: unwatched episodes' images and plots blurred (details page, Continue watching billboard). */
    val blurUnwatched = Flag("blur_unwatched", false)
    /** Spoiler plots: unwatched episodes' plots hidden (its own switch since 0.39.0; starts as the picture switch was). */
    val hidePlots = Flag("hide_plots", sp.getBoolean("blur_unwatched", false))
    /** Custom poster source (the built-in postersplus pattern) on every poster and wide card. */
    val customPosters = Flag("custom_posters", true)
    /** Reviews row source: "imdb" or "trakt". */
    val reviewsSource = Text("detail_reviews", "imdb")
    val sequelPrequel = Flag("detail_sequel_prequel", true)
    val connections = Flag("detail_connections", true)
    /** Row keys in the user's order (newline separated); rows not listed go last in add-on order. */
    val rowOrder = Text("row_order", "")
    val rowsHidden = Text("rows_hidden", "")
    /** The Anime hub's own Home row order and hidden rows. */
    val rowOrderAnime = Text("row_order_anime", "")
    val rowsHiddenAnime = Text("rows_hidden_anime", "")
    /** Settings › Home hubs: each of Home's hubs (Films & TV, Anime, IPTV) on or off; one always stays on. */
    val hubFilms = Flag("hub_on_films", true)
    val hubAnime = Flag("hub_on_anime", true)
    val hubIptv = Flag("hub_on_iptv", true)
    /** Home's content hub: "NUVIO" (Films & TV) or "ANIME". */
    val contentHub = Text("content_hub", "NUVIO")
    /** Anime: "separate" (its own hub, default), "merged" (its own rows on Films & TV's Home) or "off" (gone from the whole app). */
    val animeMode = Text("anime_mode", "separate")
    /** First start's taste questions (titles, genres, people, anime) answered or skipped. */
    val tasteStartDone = Flag("taste_start_done", false)
    /** Titles moved by hand between Anime (+id) and Films & TV (-id), one per line. */
    val animeMoves = Text("anime_moves", "")
    /** Add-on catalogs as Home rows (Vision's "Add-on rows"); off like the user's Vision. */
    val addonRows = Flag("home_addon_rows", false)
    /** VOD categories you picked as Home rows (iptv/VodArabic keys, one per line), per profile. None by default (user
     *  2026-11-23: "VOD rows shouldn't appear by default … I'll choose which ones"); empty = no reads, no TMDB matching. */
    val vodRows = Text("vod_rows", "")
    /** VOD page: categories you moved, in your order ("kind\tgroup" per line); the rest keep the provider's order. */
    val vodOrder = Text("vod_order", "")
    /** Hidden IPTV groups, "kind\tgroup" lines (kind live / movie / series); iptv/Groups.kt. */
    val groupsHidden = Text("groups_hidden", "")
    /** Keep this setup in the Nuvio account (integrations/CloudSetup), box-wide; on = restored on any device you sign in on. */
    val cloudSetup = Flag("cloud_setup", true)
    /** Rotating rows (home/Rotation.kt): a fresh set of themed rows under your own every start. */
    val rotation = Flag("home_rotation", true)
    // Library + search
    /** Local saves, JSON list. */
    val saved = Text("saved", "[]")
    /** Last searches, newline separated, newest first. */
    val recentSearches = Text("recent_searches", "")
    /** The Anime hub's own recent searches (Films & TV keeps [recentSearches]). */
    val recentSearchesAnime = Text("recent_searches_anime", "")
    // Vision TB (off until switched on; the key comes from the phone form, never from the repo)
    /** Recommendations: IMDb minimum ("0" = off) and the More like this / Not for me marks. */
    val recMinImdb = Text("rec_min_imdb", "7")
    val recFeedback = Text("rec_feedback", "[]")
    /** Hide adult titles: explicit films (9 Songs, softcore, porn) anywhere in the app. Off = no lookups at all. */
    val hideAdult = Flag("hide_adult", false)
    /** Leave out (Settings › Recommendations): genres / languages / countries that never show (recs/LeaveOut.kt). */
    val leaveOut = Text("leave_out", "")
    /** Live TV: playlists (JSON; Xtream passwords stay on this TV) and favourite channels. */
    val iptvPlaylists = Text("iptv_playlists", "[]")
    val iptvFavs = Text("iptv_favs", "")
    /** TiviMate's model: playlist + VOD + guide update every N hours in the background (05:00 / 17:00), never on open. */
    val iptvUpdateHours = Text("iptv_update_hours", "24")
    val iptvUpdateOnStart = Flag("iptv_update_on_start", false)
    val iptvRecent = Text("iptv_recent", "")
    val recordings = Text("recordings", "")
    // YouTube sign-in left with YouTube (2026-11-26): its saved token is wiped once.
    init { if (sp.contains("yt_tokens")) sp.edit().remove("yt_tokens").apply() }
    /** Sports: Vision's settings and follows (JSON, "" = defaults). */
    val sportsSettings = Text("sports_settings", "")
    val sportsFollows = Text("sports_follows", "")
    /** Link memory (Haven idea): 7-day "didn't play" / "kept buffering" marks, and your release pick per show. */
    val linkMarks = Text("link_marks", "")
    /** Player: audio/subtitle delay per title (JSON) and seek thumbnails (on; boxes under 3 GB skip them anyway). */
    val syncDelays = Text("sync_delays", "")
    val seekThumbs = Flag("seek_thumbs", true)
    /** Nuvio Arc's seek-thumbnail mode: "hd" = up to 1080p, "all" = 4K too (Arc's default on boxes with 3.3 GB+). */
    val seekThumbsMode = Text("seek_thumbs_mode", "all")
    /** Netflix's age rating and warnings at the start of a film / episode (30 s, top left). */
    val ageRating = Flag("age_rating", true)
    val linkPicks = Text("link_picks", "")
    /** Resume on the exact link (streams/ResumeLinks.kt): JSON per episode / film. */
    val resumeLinks = Text("resume_links", "")
    val visionTb = Flag("vision_tb", false)
    /** Your IPTV VOD films and series join Vision TB's links (autoplay can pick them). */
    // VOD has its own "Play with VOD" button on the title page (user 2026-11-26: separate from Vision TB); new key, off.
    val vodInTb = Flag("vod_in_tb2", false)
    /** Preferred dub (user 2026-11-24): "" = off (original audio); a language code = a link dubbed in it wins autoplay. */
    val dubLang = Text("dub_lang", "")
    /** Fast horizontal navigation (Vision's Advanced): held Left/Right repeats every 48 ms instead of 80 ms. */
    val fastHorizontal = Flag("fast_horizontal", false)
    /** Screensaver (Vision's Ambient Mode): after this many idle minutes, 4K backdrops from your rows ("0" = off). */
    val screensaverMin = Text("screensaver_min", "5")
    /** Where the Home billboard takes its titles: "" = Continue watching then the first row; else a row key. */
    val billboardSource = Text("billboard_source", "")
    val torboxKey = Text("torbox_key", "")
    // App
    val exitConfirm = Flag("exit_confirm", true)
    /** Speed and network (data/NetSpeed.kt): DNS service, quiet accessibility for key remappers, full exit. */
    val dns = Text("dns", "system")
    val quietA11y = Flag("quiet_a11y", true)
    val fullExit = Flag("full_exit", false)
}
