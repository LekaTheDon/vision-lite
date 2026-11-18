package com.lekathedon.visionlite.tb

/**
 * TheTVDB's view of one episode, for Vision TB (idea from Nuvio ReSeq, which plays shows in TVDB's orders). Many
 * releases are numbered in another order than the app's list: Firefly's S01E01 is "The Train Job" on the aired
 * list and "Serenity" on the DVD (live 2026-10-29: 26 of Torrentio's 50 S01E01 results were "Serenity").
 *
 * [title] is this episode's name, [otherTitles] the show's other episode names, [alternates] this episode's
 * season / episode in TVDB's other orders (DVD, ...) where they differ. Only titles decide: a number alone never
 * says which order a release follows.
 */
data class EpisodeOrderHint(
    val title: String,
    val otherTitles: List<String>,
    val alternates: List<Pair<Int, Int>>
)

enum class TitleEvidence { CONFIRMS, CONTRADICTS, NONE }

object EpisodeTitleCheck {
    /** Titles too plain to tell episodes apart ("Pilot", "Episode 3", "Part 2"). */
    private val GENERIC = Regex("^(?:pilot|finale|the finale|episode \\d+|chapter \\d+|part \\d+|\\d+)$")

    /** An episode title worth comparing: normalised, 4+ characters, not generic, not the show's own name. */
    fun usable(title: String, showTitles: List<String> = emptyList()): String? {
        val n = ReleaseMatcher.normalizeTitle(title)
        if (n.length < 4 || GENERIC.matches(n)) return null
        if (showTitles.any { ReleaseMatcher.normalizeTitle(it) == n }) return null
        return n
    }

    /**
     * CONFIRMS: the name carries this episode's title. CONTRADICTS: it carries another episode's title and not
     * this one's. NONE: neither (most names), so numbering decides as before.
     */
    fun evidence(name: String, hint: EpisodeOrderHint, showTitles: List<String> = emptyList()): TitleEvidence {
        val text = " " + ReleaseMatcher.normalizeTitle(name) + " "
        val own = usable(hint.title, showTitles)
        if (own != null && text.contains(" $own ")) return TitleEvidence.CONFIRMS
        val contradicts = hint.otherTitles.asSequence()
            .mapNotNull { usable(it, showTitles) }
            // "Out of Gas" vs "Gas": a title inside this one's (or the other way) proves nothing.
            .filter { other -> own == null || (!own.contains(other) && !other.contains(own)) }
            .any { text.contains(" $it ") }
        return if (contradicts) TitleEvidence.CONTRADICTS else TitleEvidence.NONE
    }
}
