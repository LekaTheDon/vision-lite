package com.lekathedon.visionlite.tb

import java.text.Normalizer

/** How a release name relates to the target. */
data class ReleaseMatch(val kind: Kind, val reason: String) {
    enum class Kind { MOVIE, EPISODE, PACK, REJECT }
    val accepted: Boolean get() = kind != Kind.REJECT

    companion object {
        fun reject(reason: String) = ReleaseMatch(Kind.REJECT, reason)
    }
}

/**
 * Decides whether a release name is the wanted movie / episode. Precision first: a release
 * is accepted only when one of the target's titles is the whole title part of the name (so
 * "El Camino A Breaking Bad Movie" never matches "Breaking Bad") and the year or the
 * season/episode agrees. Packs are accepted here but must later show the episode's file.
 */
object ReleaseMatcher {

    /**
     * [animeNumbers]: anime-only sources, where "Title - 05 [GroupTag]" is the norm: a short number right after the
     * title counts as the release marker whatever follows it (other sources still need a known marker after it).
     */
    fun match(name: String, target: DiscoveryTarget, idTagged: Boolean, animeNumbers: Boolean = false): ReleaseMatch {
        val remainder = titleRemainder(name, target.titles, animeNumbers && target.isAnime) ?: return ReleaseMatch.reject("title")
        return if (target.isSeries) matchSeries(remainder, target) else matchMovie(remainder, target, idTagged)
    }

    private fun matchMovie(remainder: List<String>, target: DiscoveryTarget, idTagged: Boolean): ReleaseMatch {
        val text = remainder.joinToString(" ")
        if (EPISODE.containsMatchIn(text) || SEASON_PACK.containsMatchIn(text) || SEASON_WORD.containsMatchIn(text)) {
            return ReleaseMatch.reject("series_marker")
        }
        val year = remainder.take(3).firstNotNullOfOrNull { token -> token.toIntOrNull()?.takeIf { it in 1900..2099 } }
        val wanted = target.year
        return when {
            year != null && wanted != null ->
                if (kotlin.math.abs(year - wanted) <= 1) ReleaseMatch(ReleaseMatch.Kind.MOVIE, "title+year")
                else ReleaseMatch.reject("year")
            idTagged -> ReleaseMatch(ReleaseMatch.Kind.MOVIE, "title+id")
            else -> ReleaseMatch.reject(if (wanted == null) "year_unknown" else "no_year")
        }
    }

    /**
     * Without the show's titles (its metadata still on the way): an episode's numbering alone, read anywhere in the
     * whole name. Movies need the title and year, so they get "title".
     */
    fun matchNumbersOnly(name: String, target: DiscoveryTarget): ReleaseMatch =
        if (!target.isSeries) ReleaseMatch.reject("title") else matchSeries(tokens(name), target)

    private fun matchSeries(remainder: List<String>, target: DiscoveryTarget): ReleaseMatch {
        val season = target.season ?: return ReleaseMatch.reject("no_target_episode")
        val episode = target.episode ?: return ReleaseMatch.reject("no_target_episode")
        val text = normalizeEpisodeText(remainder.joinToString(" "))
        // "Shogun 1980 S01E03" names its show by year: an earlier year is another show, and so is a
        // later year on season 1 (a reboot). Later seasons often carry the year they aired ("Severance 2025 S02").
        val leadingYear = remainder.firstOrNull()?.toIntOrNull()?.takeIf { it in 1900..2099 }
        if (leadingYear != null && target.year != null &&
            (leadingYear < target.year - 1 || (season == 1 && leadingYear > target.year + 1))
        ) {
            return ReleaseMatch.reject("year")
        }
        // "Doctor Who S01 (1963-64)" when the show started in 2005: a year before the show began
        // is another show. Later years are fine (packs often name the years a season aired).
        if (target.year != null && remainder.any { tok -> tok.toIntOrNull()?.let { it in 1900..2099 && it < target.year - 1 } == true }) {
            return ReleaseMatch.reject("year")
        }

        EPISODE.find(text)?.let { m ->
            val s = m.groupValues[1].toInt()
            val first = m.groupValues[2].toInt()
            val last = (m.groupValues[3].ifEmpty { m.groupValues[4] }).toIntOrNull()?.takeIf { it >= first } ?: first
            if (s != season) return ReleaseMatch.reject("season")
            return if (episode in first..last) ReleaseMatch(ReleaseMatch.Kind.EPISODE, "sxxeyy")
            else ReleaseMatch.reject("episode")
        }
        CROSS_EPISODE.find(text)?.let { m ->
            if (m.groupValues[1].toInt() != season) return ReleaseMatch.reject("season")
            return if (m.groupValues[2].toInt() == episode) ReleaseMatch(ReleaseMatch.Kind.EPISODE, "nxnn")
            else ReleaseMatch.reject("episode")
        }
        // Spanish trackers (MejorTorrent, Wolfmax4K): "Temporada 1 [Cap.101]" = S01E01, "[Cap.101_108]" = E01-E08.
        CAP_EPISODE.find(text)?.let { m ->
            if (m.groupValues[1].toInt() != season) return ReleaseMatch.reject("season")
            val first = m.groupValues[2].toInt()
            val last = m.groupValues[4].toIntOrNull()?.takeIf { m.groupValues[3].toIntOrNull() == season && it >= first } ?: first
            return if (episode in first..last) ReleaseMatch(ReleaseMatch.Kind.EPISODE, "cap") else ReleaseMatch.reject("episode")
        }
        val packRange = SEASON_PACK.find(text)?.let { it.groupValues[1].toInt() to (it.groupValues[2].toIntOrNull()) }
            ?: SEASON_WORD.find(text)?.let { it.groupValues[1].toInt() to (it.groupValues[2].toIntOrNull()) }
        if (packRange != null) {
            val (from, to) = packRange
            val end = to?.takeIf { it >= from } ?: from
            return if (season in from..end) ReleaseMatch(ReleaseMatch.Kind.PACK, "season_pack")
            else ReleaseMatch.reject("season")
        }
        if (remainder.any { it == "complete" }) return ReleaseMatch(ReleaseMatch.Kind.PACK, "complete")
        // Anime releases number episodes absolutely ("Title - 03", "Title E3", batches "(01-28)");
        // only season 1 maps 1:1.
        if (target.isAnime && season == 1) {
            val firstToken = remainder.firstOrNull()
            val first = firstToken?.toIntOrNull() ?: firstToken?.let { ANIME_E.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
            if (first != null && first !in 1900..2099) {
                // "Dandadan 2 - 02": a small number then a smaller-or-equal one is season 2's episode 2, not episode 2.
                val second = remainder.getOrNull(1)?.toIntOrNull()
                if (firstToken?.toIntOrNull() != null && second != null && first in 2..9 && second <= first && second !in 1900..2099) {
                    return ReleaseMatch.reject("season")
                }
                val last = remainder.getOrNull(1)?.toIntOrNull()?.takeIf { it > first && it < 2000 }
                return when {
                    last != null && episode in first..last -> ReleaseMatch(ReleaseMatch.Kind.PACK, "absolute_batch")
                    last == null && first == episode -> ReleaseMatch(ReleaseMatch.Kind.EPISODE, "absolute")
                    else -> ReleaseMatch.reject("episode")
                }
            }
        }
        return ReleaseMatch.reject("no_episode_info")
    }

    /**
     * For a release an add-on matched by id but whose title we don't recognise (another language):
     * the name must still show the movie's year (±1) or the episode's SxxEyy / NxNN, or for
     * season-1 anime its absolute number. Guards against add-on mismatches ("The Prisoner Extra").
     */
    fun hasIdEvidence(name: String, target: DiscoveryTarget): Boolean {
        val tokens = tokens(name)
        if (!target.isSeries) {
            val year = target.year ?: return false
            return tokens.any { t -> t.toIntOrNull()?.let { kotlin.math.abs(it - year) <= 1 } == true }
        }
        val s = target.season ?: return false
        val e = target.episode ?: return false
        val text = normalizeEpisodeText(tokens.joinToString(" "))
        if (Regex("(?<![a-z0-9])(?:s0*$s ?e0*$e|0*${s}x0*$e)(?![0-9])").containsMatchIn(text)) return true
        if (CAP_EPISODE.find(text)?.let { it.groupValues[1].toInt() == s && it.groupValues[2].toInt() == e && it.groupValues[3].isEmpty() } == true) return true
        return target.isAnime && s == 1 && Regex("(?<![a-z0-9])0*$e(?:v\\d)?(?![0-9a-z])").containsMatchIn(text.replace(Regex("\\b(?:1080|720|480|2160|10|8|264|265)\\b"), ""))
    }

    /**
     * Tokens after the target title when the release name starts with one of [titles] and the
     * title is followed by a release marker (year, SxxEyy, resolution...) or nothing. Null = no.
     */
    fun titleRemainder(name: String, titles: List<String>, animeNumbers: Boolean = false): List<String>? {
        val wanted = titles.map { tokens(it) }.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) return null
        val stripped = name.trim()
            .replace(LEADING_GROUPS, "")
            .replace(LEADING_SITE, "")
        // "Title A AKA Title B 2019": try each part
        val parts = tokens(stripped).joinToString(" ").split(" aka ").map { it.split(' ').filter(String::isNotEmpty) }
        for (part in parts) {
            for (title in wanted) {
                for (candidate in listOf(part, part.dropThe())) {
                    for (t in listOf(title, title.dropThe())) {
                        if (t.isEmpty() || candidate.size < t.size) continue
                        if (candidate.subList(0, t.size) != t) continue
                        val rest = candidate.subList(t.size, candidate.size)
                        if (rest.isEmpty() || isMarker(rest.first(), rest.getOrNull(1))) return rest
                        if (animeNumbers && rest.first().length <= 4 && rest.first().toIntOrNull() != null) return rest
                    }
                }
            }
        }
        return null
    }

    fun normalizeTitle(value: String): String = tokens(value).joinToString(" ")

    /** "s02ep3", "s10 e 05", "season 2 episode 03" -> "s02e3" style, so one pattern reads them all. */
    internal fun normalizeEpisodeText(text: String): String =
        text.replace(SPLIT_EPISODE) { "s${it.groupValues[1]}e${it.groupValues[2]}" }
            .replace(WORD_EPISODE) { "s${it.groupValues[1]}e${it.groupValues[2]}" }

    internal fun tokens(value: String): List<String> {
        val folded = Normalizer.normalize(value, Normalizer.Form.NFD).replace(MARKS, "")
            // Arabic spelling variants read as one: أ إ آ → ا, ة → ه, ى → ي (release names and TMDB differ here).
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي')
        return folded.lowercase()
            .replace(ANIME_VERSION, "")
            .replace("&", " and ")
            .replace(APOSTROPHES, "")
            .replace(NON_ALNUM, " ")
            .split(' ')
            .filter { it.isNotEmpty() }
    }

    private fun List<String>.dropThe(): List<String> = if (firstOrNull() == "the") drop(1) else this

    private fun isMarker(token: String, next: String?): Boolean {
        if (token.toIntOrNull()?.let { it in 1900..2099 } == true) return true
        if (MARKER_TOKEN.matches(token)) return true
        if (token in MARKER_WORDS) return true
        // anime "Title - 03": the dash is gone after tokenizing, the number stays
        if (token.toIntOrNull() != null && token.length <= 4 && (next == null || MARKER_TOKEN.matches(next) || next in MARKER_WORDS || next.toIntOrNull() != null)) return true
        return false
    }

    private val MARKS = Regex("\\p{M}+")
    // "03v2" (an anime re-release of episode 3) -> "03"
    private val ANIME_VERSION = Regex("(?<=\\d)v\\d(?![0-9a-z])")
    private val ANIME_E = Regex("(?:e|ep)(\\d{1,4})")
    private val SPLIT_EPISODE = Regex("(?<![a-z0-9])s(\\d{1,2}) ?(?:ep|e) ?(\\d{1,3})(?![0-9])")
    private val WORD_EPISODE = Regex("(?<![a-z])season ?(\\d{1,2}) ?episode ?(\\d{1,3})(?![0-9])")
    private val APOSTROPHES = Regex("['’`]")
    // Arabic letters count as title letters (ArabP2P names Arabic titles in Arabic); other scripts as before.
    private val NON_ALNUM = Regex("[^a-z0-9\\u0621-\\u064A]+")
    private val LEADING_GROUPS = Regex("^(?:\\s*[\\[(【][^\\])】]*[\\])】]\\s*)+")
    private val LEADING_SITE = Regex("^(?:www\\.)?[a-z0-9-]+\\.(?:com|org|net|to|cc|me|io|tv)\\s*-\\s*", RegexOption.IGNORE_CASE)
    private val MARKER_TOKEN = Regex(
        "^(?:s\\d{1,2}(?:ep?\\d{1,3})?(?:e\\d{1,3})?|\\d{1,2}x\\d{2,3}|\\d{3,4}p|4k|8k|uhd|x26[45]|h26[45]|hevc|avc|av1|xvid|divx|10bit|hdr10?|dv|dovi|e\\d{1,3})$"
    )
    private val MARKER_WORDS = setOf(
        "season", "seasons", "complete", "series", "bluray", "blu", "bdrip", "brrip", "bdremux", "remux",
        "web", "webrip", "webdl", "dl", "hdtv", "dvdrip", "dvd", "hdrip", "hdr", "imax", "extended",
        "unrated", "uncut", "directors", "proper", "repack", "internal", "limited", "multi", "dual",
        "integrale", "temporada", "staffel", "saison", "mini", "batch", "vostfr", "ita", "eng", "rus",
        "hindi", "german", "french", "spanish", "korean", "japanese", "remastered", "criterion", "open",
        "theatrical", "cam", "ts", "hdcam", "telesync", "dolby", "vision", "atmos", "truehd", "dts", "ddp",
        "aac", "ac3", "x264", "x265", "nf", "amzn", "dsnp", "hmax", "atvp", "pcok", "hulu", "max"
    )

    // Applied to the remainder joined with spaces (dots, dashes and underscores already spaces).
    private val EPISODE = Regex("(?<![a-z0-9])s(\\d{1,2}) ?e(\\d{1,3})(?: ?e(\\d{1,3})| (\\d{1,3})(?= |$))?(?![0-9])")
    private val CROSS_EPISODE = Regex("(?<![a-z0-9])(\\d{1,2})x(\\d{2,3})(?![0-9])")
    private val CAP_EPISODE = Regex("(?<![a-z0-9])cap ?(\\d{1,2})(\\d{2})(?: (\\d{1,2})(\\d{2}))?(?![0-9])")
    private val SEASON_PACK = Regex("(?<![a-z0-9])s(\\d{1,2})(?: s?(\\d{1,2}))?(?![0-9e])")
    private val SEASON_WORD = Regex("(?<![a-z])(?:season|seasons|staffel|saison|temporada) ?(\\d{1,2})(?: (?:to )?(\\d{1,2}))?(?![0-9])")
}

/** Builds a few text queries for title-based sources. Capped: every query is a request. */
object QueryExpander {
    fun queries(target: DiscoveryTarget, max: Int = 3): List<String> {
        val titles = target.titles.map { it.trim() }.filter { it.isNotEmpty() && it.isMostlyLatin() }
            .distinctBy { ReleaseMatcher.normalizeTitle(it) }
        if (titles.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        if (target.isSeries) {
            val s = target.season ?: return emptyList()
            val e = target.episode ?: return emptyList()
            val code = "S%02dE%02d".format(s, e)
            out += "${titles[0]} $code"
            out += "${titles[0]} S%02d".format(s)
            titles.drop(1).firstOrNull()?.let { out += "$it $code" }
        } else {
            val year = target.year?.let { " $it" }.orEmpty()
            titles.forEach { out += "$it$year" }
        }
        return out.map { foldAccents(it).replace(Regex("[\"':!?,]"), "").replace(Regex("\\s+"), " ").trim() }.distinct().take(max)
    }

    /** Nyaa search for anime: the title alone (absolute numbering makes SxxEyy queries miss). */
    fun animeQuery(target: DiscoveryTarget): String? =
        target.titles.firstOrNull { it.isMostlyLatin() }?.let { title ->
            val e = target.episode
            val folded = foldAccents(title)
            if (target.season == 1 && e != null) "$folded %02d".format(e) else folded
        }

    /** "Amélie" -> "Amelie": torrent names and some search APIs are ASCII-only. */
    fun foldAccents(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun String.isMostlyLatin(): Boolean {
        val letters = filter { it.isLetter() }
        if (letters.isEmpty()) return false
        return letters.count { it.code < 0x250 } * 10 >= letters.length * 9
    }
}
