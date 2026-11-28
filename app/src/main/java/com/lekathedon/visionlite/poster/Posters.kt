package com.lekathedon.visionlite.poster

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.lekathedon.visionlite.App

/**
 * Custom poster source (Vision's, built in): every poster and wide card asks here. Off = the original art.
 * Only builds a URL; the image request is the same one the card would make anyway (no extra calls).
 */
object Posters {
    /** The user's Vision poster source (postersplus), kept in the app; Settings > Look switches it. */
    private const val BASE = "https://postersplus.slokker.cc/poster"
    private const val STYLE = "&pc=stremio_tv_nuvio&sm=notch&tst=ribbon&ts=opposite&trst=gold&tc=1&_bgr=off&_sm=sash&_tst=ribbon&_ts=opposite&_trst=gold&_tc=1" +
        "&bp=bottom_left&_soo10=1&_sep=pip&_bst=gold&fti=1&rdm=3&mam=2&mmfsr=0.056&mmfxo=0.065&msoo10=1&mrs=bullet&mw=tk:.01,im:1&tw=im:1" +
        "&amw=mal:.33,al:.32,im:.3,mc:.05&atw=mal:.34,al:.33,im:.33&fbs=photoreal&_mo=genre,year,rating&sws=1&sbs=gold&sbsw=1.40&sbsh=1.20" +
        "&sbpo=right&bdm=7&bms=4&_bdm=7&wfq=1"

    private fun custom(on: Boolean, id: String, type: String, shape: String): String? {
        if (!on || id.isBlank()) return null
        val t = when (type) { "movie" -> "movie"; "series", "tv", "show" -> "series"; else -> return null }
        // Lite's TMDB cards carry "tmdb:movie:123" / "tmdb:tv:123"; the poster server only takes the number (else 400 = blank card).
        val clean = Regex("^tmdb:(?:movie|tv|series):(\\d+)$").find(id)?.let { "tmdb:" + it.groupValues[1] } ?: id
        val imdb = if (clean.startsWith("tt")) clean else ""
        val tmdb = if (clean.startsWith("tmdb:")) clean.removePrefix("tmdb:") else ""
        return "$BASE?tmdb_id=$tmdb&imdb_id=$imdb&stremio_id=$clean&type=$t&shape=$shape$STYLE"
    }

    /** The tall poster's link outside a screen (Home's start-up warm-up), the same one [poster] gives. */
    fun posterNow(id: String, type: String, original: String?): String? = custom(App.instance.prefs.customPosters.value, id, type, "poster") ?: original
    fun wideNow(id: String, type: String, original: String?): String? = custom(App.instance.prefs.customPosters.value, id, type, "landscape") ?: original

    /** Tall poster. */
    @Composable fun poster(id: String, type: String, original: String?): String? {
        val on by App.instance.prefs.customPosters.flow.collectAsState()
        return custom(on, id, type, "poster") ?: original
    }

    /** Wide card (landscape art). */
    @Composable fun wide(id: String, type: String, original: String?): String? {
        val on by App.instance.prefs.customPosters.flow.collectAsState()
        return custom(on, id, type, "landscape") ?: original
    }

    /** True when the wide card shows the custom art, which has the title written on it already (no logo over it then). */
    @Composable fun wideHasTitle(id: String, type: String): Boolean {
        val on by App.instance.prefs.customPosters.flow.collectAsState()
        return custom(on, id, type, "landscape") != null
    }
}
