package com.lekathedon.visionlite.tb

import com.lekathedon.visionlite.detail.Imdb

/**
 * What a title is, known before any link is looked at (user 2026-11-26: "Monster" the 2004 anime autoplayed a 2026
 * Netflix show, 4K, 56 min): film / TV show / anime, the years it ran, how long an episode or the film is. The details
 * page records it from what it already loaded (no extra call); Play from anywhere else reads IMDb's cached title, or
 * asks for it once. Vision TB checks every release against it, and the player checks the real duration.
 */
object TitleFacts {
    enum class Kind { MOVIE, SHOW, ANIME }
    data class Facts(val kind: Kind, val name: String, val year: Int?, val endYear: Int?, val runtimeMin: Int?)

    /** "S01E01-E02", "S01E01E02", "S01E01-02": two episodes in one file. */
    private val DOUBLE = Regex("""(?i)s\d{1,2}e\d{1,3}(?:[-–]?e\d{1,3}|[-–]\d{1,3}\b)""")
    private val map = java.util.concurrent.ConcurrentHashMap<String, Facts>()

    fun get(imdb: String?): Facts? = imdb?.substringBefore(':')?.let { map[it] ?: Imdb.cachedTitle(it)?.let(::from)?.also { f -> map[it] = f } }

    /** The details page's title, as it is drawn. */
    fun put(t: Imdb.Title) { map[t.id] = from(t) }

    /** Facts for [imdb]: memory, else IMDb (cached for the run). Null only when IMDb can't be reached. */
    suspend fun ensure(imdb: String): Facts? {
        val id = imdb.substringBefore(':')
        get(id)?.let { return it }
        return Imdb.title(id)?.let(::from)?.also { map[id] = it }
    }

    private fun from(t: Imdb.Title): Facts {
        val series = (t.type.startsWith("tv", true) && !t.type.equals("tvMovie", true)) || t.type.contains("series", true)
        val anime = com.lekathedon.visionlite.anime.AnimeIds.isAnime(t.id) ||
            (t.genres.any { it.equals("Animation", true) || it.equals("Anime", true) } &&
                com.lekathedon.visionlite.player.OriginalLang.cached(t.id) == "ja")
        val kind = when { anime -> Kind.ANIME; series || t.seasons.isNotEmpty() -> Kind.SHOW; else -> Kind.MOVIE }
        return Facts(kind, t.name, t.year, t.endYear, t.runtimeMin)
    }

    /**
     * The real file against the facts (player, once the file is open): wrong when an episode runs far from the show's
     * episode length (24 vs 56 min) or a film far from its own. Null = fine or unknown. Double episodes are allowed.
     */
    fun wrongDuration(imdb: String?, series: Boolean, durationMs: Long, releaseName: String): String? {
        val f = get(imdb) ?: return null
        val exp = f.runtimeMin?.takeIf { it in 3..400 } ?: return null
        val got = (durationMs / 60_000L).toInt().takeIf { it > 0 } ?: return null
        val wrong = if (series) {
            if (DOUBLE.containsMatchIn(releaseName)) return null
            (got > exp * 1.75 && got - exp >= 15) || (got < exp * 0.5 && exp - got >= 10)
        } else (got > exp * 1.6 && got - exp >= 40) || (got < exp * 0.6 && exp - got >= 25)
        return if (wrong) "Wrong video: $got min, ${if (series) "an episode" else "the film"} is about $exp min" else null
    }
}
