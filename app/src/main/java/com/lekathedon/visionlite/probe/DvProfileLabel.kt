package com.lekathedon.visionlite.probe

import java.util.Locale

/**
 * The Dolby Vision profile a source link will play (fork feature), shown on its stream card before playback.
 * [CHECKED]: read from the file itself (its DV config and first RPU, as the player does); [NAMED]: the link's
 * name states it; [LIKELY]: inferred from the release type (remux, WEB-DL, encode) and its HDR tags.
 */
data class DvProfileLabel(val text: String, val certainty: Certainty) {
    enum class Certainty { CHECKED, NAMED, LIKELY }
}

/** What the stream card shows: the label, or a check under way / failed. */
sealed interface DvProfileState {
    data class Known(val label: DvProfileLabel) : DvProfileState
    data object Checking : DvProfileState
    data class Failed(val reason: String) : DvProfileState
}

object DvProfileNames {
    /** "Profile 7 FEL", "Profile 8.1", "Profile 10.4"; [compat] is the DV base-layer compatibility id. */
    fun profileName(profile: Int, compat: Int? = null, el: String? = null): String = buildString {
        append("Profile ").append(profile)
        if ((profile == 8 || profile == 10 || profile == 9) && compat != null && compat > 0) append('.').append(compat)
        if (profile == 7 && el != null) append(' ').append(el)
    }

    /** Base layer of a cross-compatible profile, for the label's tail ("HDR10 base"), or null. */
    fun baseLayer(profile: Int, compat: Int?): String? = when {
        profile == 7 -> "HDR10 base"
        profile == 5 -> "no HDR10 fallback"
        compat == 1 || compat == 6 -> "HDR10 base"
        compat == 2 -> "SDR base"
        compat == 4 -> "HLG base"
        else -> null
    }
}

