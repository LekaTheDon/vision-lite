// Vision's smart autoplay (AutoPlayEpisodeCheck, AutoPlayEpisodeFacts, StreamEpisodeTrust, BestLinkRank,
// AutoPlayLinkCheck), on Lite's links: the right episode first, then playable now, no CAM, 4K, the biggest file.
// Text only until a finished add-on link wins; then one 1-byte request (3 s at most) asks what file it really serves.
package com.lekathedon.visionlite.streams

import com.lekathedon.visionlite.addons.Meta
import com.lekathedon.visionlite.addons.Video
import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.tb.DiscoveryTarget
import com.lekathedon.visionlite.tb.EpisodeVouches
import com.lekathedon.visionlite.tb.ReleaseMatch
import com.lekathedon.visionlite.tb.ReleaseMatcher
import com.lekathedon.visionlite.tb.ReleaseQuality
import com.lekathedon.visionlite.tb.SeaDexBest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.Response
import java.net.URLDecoder
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Does a name say it is the episode autoplay was asked for? A name with no numbers is UNKNOWN, not wrong. */
object EpisodeCheck {
    /** CONFIRMED: the name carries this episode's own title. NAMED: only its numbers. */
    enum class Verdict { CONFIRMED, NAMED, UNKNOWN, WRONG }

    data class Target(val season: Int, val episode: Int, val showKey: String? = null)

    private val NUMBERED_ID = Regex("^(tt\\d+|tmdb:\\d+):(\\d+):(\\d+)$")
    private val WRONG_REASONS = setOf("season", "episode")
    private val EPISODE_CODE = Regex(
        "(?<![a-z0-9])s\\d{1,2}[ ._-]*ep?[ ._-]*(\\d{1,4})(?:[ ._]*(?:-|~|to|ep?)[ ._]*(?:ep?)?[ ._]*(\\d{1,4})(?![0-9a-z]))?"
    )
    private val SOLID_SEASON = listOf(
        EPISODE_CODE,
        Regex("(?<![a-z0-9])s\\d{1,2}(?![a-z0-9])"),
        Regex("(?<![a-z0-9])\\d{1,2}x\\d{2,3}(?![0-9])"),
        Regex("(?<![a-z])(?:season|seasons|staffel|saison|temporada)[ ._-]*\\d"),
        Regex("(?<![a-z0-9])cap[ ._]*\\d")
    )

    /** Only ids numbered by season ("tt2359704:6:5"); anime ids and specials number differently. */
    fun targetOf(videoId: String?): Target? {
        val m = videoId?.let { NUMBERED_ID.matchEntire(it.trim()) } ?: return null
        val season = m.groupValues[2].toIntOrNull() ?: return null
        val episode = m.groupValues[3].toIntOrNull() ?: return null
        return if (season >= 1 && episode >= 1) Target(season, episode, m.groupValues[1]) else null
    }

    fun showKeyOf(videoId: String?): String? = videoId?.let { NUMBERED_ID.matchEntire(it.trim()) }?.groupValues?.get(1)

    fun readName(name: String?, target: Target): Verdict {
        if (name.isNullOrBlank()) return Verdict.UNKNOWN
        val match = ReleaseMatcher.matchNumbersOnly(name, DiscoveryTarget(null, true, emptyList(), null, target.season, target.episode))
        val lower = name.lowercase()
        return when {
            match.kind == ReleaseMatch.Kind.EPISODE -> readEpisodeRange(lower, target)
            match.kind != ReleaseMatch.Kind.REJECT || match.reason !in WRONG_REASONS -> Verdict.UNKNOWN
            match.reason == "season" && SOLID_SEASON.none { it.containsMatchIn(lower) } -> Verdict.UNKNOWN // "S4C", "s1mple"
            else -> Verdict.WRONG
        }
    }

    /** "S06E02.5.1" is episode 2; only a written range ("E01-E12") holds other episodes. */
    private fun readEpisodeRange(lower: String, target: Target): Verdict {
        val code = EPISODE_CODE.find(lower) ?: return Verdict.NAMED
        val first = code.groupValues[1].toInt()
        val last = code.groupValues[2].toIntOrNull()?.takeIf { it >= first } ?: first
        return if (target.episode in first..last) Verdict.NAMED else Verdict.WRONG
    }

    private val CODE = Regex("(?i)(?<![a-z0-9])(?:s\\d{1,2}[ ._-]?e\\d{1,3}|\\d{1,2}x\\d{2,3})(?![0-9])")
    private val LEAD_JUNK = Regex("^\\s*(?:\\[[^\\]]*]|\\([^)]*\\)|(?:www\\.)?[a-z0-9-]+\\.(?:org|com|net|to|me|cc|xyz|io|tv)\\b)\\s*[-–:|]*\\s*", RegexOption.IGNORE_CASE)
    private val TAIL_OK = Regex("^(?:(?:19|20)\\d{2}|us|uk|au|nz|ca)$")

    /**
     * A line naming its show before the episode code ("Castlevania Nocturne S01E05" when the show is "Nocturne"):
     * true when that name is another show. Names without a show name before the code, or shows we have no title
     * for, are never ruled out here. The show's name may be the end of its full title ("Marvel's Daredevil").
     */
    fun otherShow(line: String?, titles: List<String>): Boolean {
        if (line.isNullOrBlank() || titles.isEmpty()) return false
        val at = CODE.find(line) ?: return false
        var lead = line.substring(0, at.range.first)
        while (true) { val m = LEAD_JUNK.find(lead) ?: break; if (m.value.isEmpty()) break; lead = lead.substring(m.value.length) }
        val words = ReleaseMatcher.normalizeTitle(lead).split(' ').filter { it.isNotEmpty() }.toMutableList()
        while (words.isNotEmpty() && TAIL_OK.matches(words.last())) words.removeAt(words.size - 1)
        if (words.isEmpty()) return false
        val name = words.joinToString(" ")
        return titles.map { ReleaseMatcher.normalizeTitle(it) }.filter { it.isNotEmpty() }.none { t ->
            val noThe = t.removePrefix("the ")
            name == t || name == noThe || name.removePrefix("the ") == noThe || t.endsWith(" $name")
        }
    }

    /** Any line of the file name or labels that names another show. */
    fun namesOtherShow(fileName: String?, labels: List<String?>, target: Target): Boolean {
        val titles = EpisodeFacts.titlesOf(target)
        return (listOf(fileName?.substringAfterLast('/')) + labels.flatMap { it?.lines().orEmpty() }).any { otherShow(it, titles) }
    }

    /** The file's numbers first, then the show's own episode titles (this one's settles it; another's rules it out). */
    fun read(fileName: String?, labels: List<String?>, target: Target): Verdict {
        if (namesOtherShow(fileName, labels, target)) return Verdict.WRONG
        val numbers = readName(fileName, target).takeIf { it != Verdict.UNKNOWN }
            ?: readName(labels.filterNot { it.isNullOrBlank() }.joinToString(" "), target)
        if (numbers == Verdict.WRONG) return numbers
        val facts = EpisodeFacts.of(target) ?: return numbers
        val file = fileName?.takeIf { it.isNotBlank() }?.substringAfterLast('/')
        val labelText = labels.filterNot { it.isNullOrBlank() }.joinToString(" ")
        val all = listOfNotNull(file, labelText).joinToString(" ")
        if (facts.namedIn(spaced(all), numbersAgree = numbers == Verdict.NAMED)) return Verdict.CONFIRMED
        if (facts.otherTitleIn(spaced(file ?: labelText))) return Verdict.WRONG
        if (facts.tooNewForDisc(LocalDate.now()) && EpisodeFacts.isDisc(all)) return Verdict.WRONG
        return numbers
    }

    private fun spaced(text: String) = " " + ReleaseMatcher.normalizeTitle(text) + " "

    private val DISPOSITION_EXTENDED = Regex("filename\\*\\s*=\\s*[^']*'[^']*'([^;]+)", RegexOption.IGNORE_CASE)
    private val DISPOSITION_PLAIN = Regex("filename\\s*=\\s*(?:\"([^\"]+)\"|([^;]+))", RegexOption.IGNORE_CASE)

    fun fileNameFromDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        DISPOSITION_EXTENDED.find(header)?.let { return decode(it.groupValues[1].trim()).takeIf(String::isNotBlank) }
        val plain = DISPOSITION_PLAIN.find(header) ?: return null
        return plain.groupValues[1].ifEmpty { plain.groupValues[2] }.trim().takeIf(String::isNotBlank)
    }

    fun fileNameFromUrl(url: String?): String? {
        val path = url?.substringBefore('?')?.substringBefore('#')?.substringAfter("://", "")?.substringAfter('/', "")
        return path?.trimEnd('/')?.substringAfterLast('/')?.takeIf(String::isNotBlank)?.let(::decode)
    }

    private fun decode(v: String): String = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
}

/** The show's own episode list (titles, air dates), kept in memory for the last 40 shows; no network here. */
object EpisodeFacts {
    private const val DISC_MIN_DAYS = 60L

    class Episode internal constructor(val title: String?, val released: LocalDate?, internal val otherTitles: List<String>) {
        /** Two-word titles can name an arc ("Stone Ocean"), so they count only beside this episode's numbers. */
        fun namedIn(text: String, numbersAgree: Boolean): Boolean =
            title != null && (numbersAgree || title.count { it == ' ' } >= 2) && text.contains(" $title ")
        fun otherTitleIn(text: String): Boolean = otherTitles.any { text.contains(" $it ") }
        fun tooNewForDisc(today: LocalDate): Boolean = released != null && today.isBefore(released.plusDays(DISC_MIN_DAYS))
    }

    private class Show(val episodes: Map<Pair<Int, Int>, Video>, val titles: Map<Pair<Int, Int>, String>, val notAnimated: Boolean, val name: String)

    /** The show's names: the details add-on's, plus TMDB's / the anime lists' when Vision TB read them. */
    fun titlesOf(t: EpisodeCheck.Target): List<String> {
        val key = t.showKey ?: return emptyList()
        val own = synchronized(shows) { shows[key]?.name }?.takeIf { it.isNotBlank() } ?: return emptyList()
        return (listOf(own) + com.lekathedon.visionlite.tb.TbMeta.knownTitles(key)).distinct()
    }

    private val shows = object : LinkedHashMap<String, Show>(40, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Show>?) = size > 40
    }

    fun remember(meta: Meta) {
        val shown = listOf(meta.name).map(ReleaseMatcher::normalizeTitle).toSet()
        // No genres = not known, so not trusted; any cartoon stays under the strict rule.
        val notAnimated = meta.genres.isNotEmpty() && meta.genres.none { it.equals("animation", true) || it.equals("anime", true) }
        meta.videos.groupBy { EpisodeCheck.showKeyOf(it.id) }.forEach { (key, list) ->
            key ?: return@forEach
            val episodes = HashMap<Pair<Int, Int>, Video>(); val titles = HashMap<Pair<Int, Int>, String>()
            for (v in list) {
                val at = (v.season ?: continue) to (v.episode ?: continue)
                episodes[at] = v
                strongTitle(v.title ?: v.name)?.takeIf { t -> shown.none { " $it ".contains(" $t ") } }?.let { titles[at] = it }
            }
            synchronized(shows) { shows[key] = Show(episodes, titles, notAnimated, meta.name) }
        }
    }

    /** When this episode airs (the details add-on's date); null = not known. */
    fun airsAt(t: EpisodeCheck.Target): java.time.Instant? {
        val raw = synchronized(shows) { shows[t.showKey ?: return null]?.episodes?.get(t.season to t.episode)?.released }?.trim() ?: return null
        return runCatching { java.time.Instant.parse(raw) }.getOrNull()
            ?: runCatching { LocalDate.parse(raw.take(10)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant() }.getOrNull()
    }

    /** Not out yet: its air time is still ahead. Unknown dates count as out. */
    fun notOutYet(t: EpisodeCheck.Target?): java.time.Instant? = t?.let(::airsAt)?.takeIf { it.isAfter(java.time.Instant.now()) }

    fun has(t: EpisodeCheck.Target?): Boolean = t?.showKey?.let { k -> synchronized(shows) { shows.containsKey(k) } } == true

    /** Not animated, and Torrentio's files for this episode all use these numbers: the numbers alone are enough. */
    fun numbersAreEnough(t: EpisodeCheck.Target): Boolean {
        val key = t.showKey ?: return false
        return synchronized(shows) { shows[key]?.notAnimated } == true && EpisodeVouches.numberingAgrees(key, t.season, t.episode)
    }

    fun of(t: EpisodeCheck.Target): Episode? {
        val show = synchronized(shows) { shows[t.showKey ?: return null] } ?: return null
        val at = t.season to t.episode
        val video = show.episodes[at] ?: return null
        val own = show.titles[at]
        val others = show.titles.filterKeys { it != at }.values.asSequence()
            .filter { it.count { c -> c == ' ' } >= 2 }
            .filter { o -> own == null || (!own.contains(o) && !o.contains(own)) }
            .distinct().toList()
        return Episode(own, video.released?.trim()?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }, others)
    }

    private fun strongTitle(title: String?): String? {
        val n = title?.let(ReleaseMatcher::normalizeTitle)?.takeIf { it.length >= 8 && ' ' in it } ?: return null
        return n.takeUnless { GENERIC.matches(it) }
    }
    private val GENERIC = Regex("^(?:the finale|episode \\d+|chapter \\d+|part \\d+|season \\d+.*|extended cut|directors cut|final cut|dolby vision|blu ray)$")

    internal fun isDisc(text: String): Boolean =
        ReleaseQuality.parse(text).source.let { it == ReleaseQuality.Source.BLURAY || it == ReleaseQuality.Source.REMUX }

    /** Reads the episode list before autoplay decides (a list read recently is in memory); 2.5 s at most. */
    suspend fun ensure(type: String, videoId: String) {
        val t = EpisodeCheck.targetOf(videoId) ?: return
        if (has(t)) return
        val meta = withTimeoutOrNull(2_500) { runCatching { com.lekathedon.visionlite.App.instance.client.meta(type, t.showKey!!) }.getOrNull() } ?: return
        remember(meta)
    }
}

/** A film's titles and year for autoplay's title check (details add-on + TMDB's); last 40 films, no network here. */
object MovieFacts {
    private val films = object : LinkedHashMap<String, Pair<List<String>, Int?>>(40, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<List<String>, Int?>>?) = size > 40
    }

    fun of(imdb: String): Pair<List<String>, Int?>? {
        val f = synchronized(films) { films[imdb] } ?: return null
        return (f.first + com.lekathedon.visionlite.tb.TbMeta.knownTitles(imdb)).distinct() to f.second
    }

    /** Reads the film's name and year before autoplay decides (from memory when the page was open); 2.5 s at most. */
    suspend fun ensure(imdb: String) {
        if (synchronized(films) { films.containsKey(imdb) }) return
        val m = withTimeoutOrNull(2_500) { runCatching { com.lekathedon.visionlite.App.instance.client.meta("movie", imdb) }.getOrNull() } ?: return
        val year = m.releaseInfo?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
        synchronized(films) { films[imdb] = listOfNotNull(m.name.takeIf { it.isNotBlank() }) to year }
    }
}

object AutoPick {
    const val TRUST_VERIFIED = 3

    private val Link.labels get() = listOf(stream.name, stream.title, stream.description)
    private val Link.fileName get() = tb?.fileName ?: stream.behaviorHints?.filename

    fun verdict(l: Link, t: EpisodeCheck.Target?): EpisodeCheck.Verdict =
        if (t == null) EpisodeCheck.Verdict.UNKNOWN else EpisodeCheck.read(l.fileName, l.labels, t)

    /** Every line a link names itself by: its file name and each line of its labels. */
    private fun Link.names(): List<String> =
        (listOf(fileName?.substringAfterLast('/')) + labels.flatMap { it?.lines().orEmpty() }).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }

    /** The link names the show itself: a line starts with one of its titles, then a release marker or number. */
    private fun namesShow(l: Link, titles: List<String>): Boolean =
        titles.isNotEmpty() && l.names().any { ReleaseMatcher.titleRemainder(it, titles, animeNumbers = true) != null }

    /**
     * Strict rule (user 2026-11-19: "playing something else entirely is unacceptable"): autoplay plays only a link
     * that names this show AND this episode (its numbers or its own title). Can't tell = never played; a missed
     * best link is acceptable, another title is not. 3 = also verified (own episode title, Torrentio filed it under
     * this episode, or numbers on a show where they can be trusted); 2 = show + numbers; 0 = not autoplayed.
     */
    fun trust(l: Link, t: EpisodeCheck.Target?): Int {
        if (t == null) return 0
        if (!namesShow(l, EpisodeFacts.titlesOf(t))) return 0
        val vouched = l.vod || l.source.startsWith("Torrentio", true) || EpisodeVouches.has(t.season, t.episode, l.tb?.hash ?: l.stream.infoHash)
        return when (verdict(l, t)) {
            EpisodeCheck.Verdict.WRONG, EpisodeCheck.Verdict.UNKNOWN -> 0
            EpisodeCheck.Verdict.CONFIRMED -> TRUST_VERIFIED
            EpisodeCheck.Verdict.NAMED -> if (vouched || EpisodeFacts.numbersAreEnough(t)) TRUST_VERIFIED else 2
        }
    }

    /** A film: a line of the link must name this film by its title and year (Vision TB's own matcher). */
    private fun namesMovie(l: Link, imdb: String): Boolean {
        val (titles, year) = MovieFacts.of(imdb) ?: return false
        if (titles.isEmpty()) return false
        val target = com.lekathedon.visionlite.tb.DiscoveryTarget(imdb, false, titles, year)
        return l.names().any { ReleaseMatcher.match(it, target, true, false).accepted }
    }

    /** The link names another show (the list hides these too: pressing one would play something else). */
    fun namesAnotherShow(l: Link, videoId: String): Boolean {
        val t = EpisodeCheck.targetOf(videoId) ?: return false
        return EpisodeCheck.namesOtherShow(l.fileName, l.labels, t)
    }

    /** Wrong rows out, then only the surest rows there are; empty = open the list. */
    fun pool(links: List<Link>, t: EpisodeCheck.Target?, videoId: String = ""): List<Link> {
        val playable = links.filter { it.playable }
        if (t == null) {
            val id = videoId.trim()
            return when {
                // Films by IMDb id: title + year named.
                Regex("^tt\\d+$").matches(id) -> playable.filter { namesMovie(it, id) }
                // Anime and other ids: only Vision TB rows, which its matcher took by the show's titles and numbers.
                else -> playable.filter { it.tb != null }
            }
        }
        val trusts = playable.map { trust(it, t) }
        val top = trusts.maxOrNull() ?: return emptyList()
        if (top <= 0) return emptyList()
        return playable.filterIndexed { i, _ -> trusts[i] == top }
    }

    private val FEL = Regex("(?i)(?<![a-z])fel(?![a-z])")
    private val P7 = Regex("(?i)(?<![a-z0-9])(?:p7|dv7|dvp7|profile ?7|dovi ?p7|dv ?p7|dv[ ._-]?p7)(?![0-9])")
    private val MEL = Regex("(?i)(?<![a-z])mel(?![a-z])")
    private val UHD_BD = Regex("(?i)uhd|2160|4k")

    /** On a box that plays DV7 FEL natively: 3 = names FEL, 2 = DV7 (named, or a DV UHD Blu-ray remux: those are DV7), 1 = other DV, 0 = no DV. */
    fun dvTier(text: String, q: ReleaseQuality): Int = when {
        q.dolbyVision != true && !P7.containsMatchIn(text) && !FEL.containsMatchIn(text) -> 0
        FEL.containsMatchIn(text) && !MEL.containsMatchIn(text) -> 3
        P7.containsMatchIn(text) -> 2
        q.source == ReleaseQuality.Source.REMUX && UHD_BD.containsMatchIn(text) -> 2
        else -> 1
    }

    /** True when this box shows DV7 FEL (This TV's DV7 = FEL and the native FEL path works). */
    private val felBox: Boolean get() = runCatching {
        com.lekathedon.visionlite.App.instance.prefs.dv7.value.let { it == "fel" || it == "auto" } &&
            com.lekathedon.visionlite.player.fel.FelSupport.usable()
    }.getOrDefault(false)

    private fun textFor(l: Link) = listOfNotNull(l.stream.name, l.stream.title, l.stream.description, l.fileName).joinToString(" ")

    /**
     * Best link: no CAM, then your preferred dub (when set), 4K first; on a FEL box DV7 FEL next (user 2026-11-20: Frankenstein autoplayed DV8 over FEL),
     * then the bigger file always; SeaDex best, quality and Vision TB break ties.
     */
    /**
     * Audio autoplay looks for (user 2026-11-27: Audio "Original" on, The Killing autoplayed a Spanish 🇪🇸 copy):
     * the Audio setting with "Original" swapped for the title's language; empty = no wish or not known yet.
     */
    fun audioWanted(videoId: String): Set<String> = runCatching {
        val p = com.lekathedon.visionlite.App.instance.prefs
        com.lekathedon.visionlite.player.OriginalLang.audioLangs(p.audioLang.value, p.audioLang2.value, videoId).toSet()
    }.getOrDefault(emptySet())

    /** The link names only other languages than the wanted ones (a MULTI / Dual audio or untagged link is fine). */
    fun wrongAudio(l: Link, wanted: Set<String>): Boolean {
        if (wanted.isEmpty()) return false
        val named = DubLang.named(textFor(l)) ?: return false
        return named.none { it in wanted }
    }

    /** The source says the file isn't ready (debrid not cached: "❌ Not Ready", "Uncached", "⏳"): it would have to download first. */
    private val NOT_READY = Regex("""(?i)not\s*ready|un-?cached|not\s*cached|\[(tb|rd|ad|pm|dl)\s*(download|⏳)\]|⏳|⬇️""")
    fun notReady(l: Link): Boolean = l.tb == null && NOT_READY.containsMatchIn(listOfNotNull(l.stream.name, l.stream.title, l.stream.description).joinToString(" "))

    fun rank(pool: List<Link>, minutes: Int = 0, audio: Set<String> = emptySet()): List<Link> {
        val fel = felBox
        val dub = DubLang.wanted()
        val subs = SubsIn.wanted()
        val mySubs = SubsIn.yours()
        val parsed = pool.map { l -> l to ReleaseQuality.parse(textFor(l)) }
        // This box / TV / internet (user 2026-11-24): what can't play here is out (unless nothing else is left),
        // what would struggle goes after everything that plays well.
        val fits = parsed.associate { (l, q) -> l to DeviceFit.fit(textFor(l), q, l.bytes, minutes, l.height) }
        val usable = parsed.filter { fits[it.first] != DeviceFit.NO }.ifEmpty { parsed }
        return usable.sortedWith(
        compareBy<Pair<Link, ReleaseQuality>> { it.second.lowQuality }
            // Not ready on the debrid (would download first, may never start): after every ready link.
            .thenBy { notReady(it.first) }
            .thenByDescending { fits[it.first] ?: DeviceFit.GOOD }
            // Audio "Original" / your audio language: a copy only in another language goes after the rest.
            .thenBy { wrongAudio(it.first, audio) }
            // Subtitles in your language (always, user 2026-11-27): a link that says its subtitles are only other languages goes after.
            .thenBy { SubsIn.lacksYours(textFor(it.first), mySubs) }
            // Preferred dub (user 2026-11-24): a link dubbed in that language beats 4K original audio.
            .thenByDescending { dub.isNotEmpty() && (it.first.dub ?: DubLang.of(textFor(it.first))) == dub }
            // Subtitles in your language (user 2026-11-26, when switched on): the add-on whose link has them wins.
            .thenByDescending { if (subs.isEmpty()) 0 else subs.maxOf { lang -> SubsIn.score(textFor(it.first), lang) } }
            .thenByDescending { it.second.resolution == 2160 || it.first.height == 2160 }
            .thenByDescending { if (fel) dvTier(textFor(it.first), it.second) else 0 }
            .thenByDescending { it.first.bytes }
            .thenByDescending { SeaDexBest.isBest(it.first.tb?.hash ?: it.first.stream.infoHash) }
            .thenByDescending { it.second.score() }
            .thenByDescending { it.first.tb != null }
        ).map { it.first }
    }

    /** One log line per try: what autoplay picked and why (logcat tag AutoPick). */
    fun describe(l: Link): String {
        val text = textFor(l)
        val q = ReleaseQuality.parse(text)
        val name = (l.fileName ?: l.stream.title ?: l.stream.name ?: "").lineSequence().firstOrNull().orEmpty().take(90)
        val dv = listOf("no DV", "DV", "DV7", "DV7 FEL")[dvTier(text, q)]
        return "${l.source} | $name | ${if (q.resolution == 2160 || l.height == 2160) "4K" else "${l.height}p"} $dv | ${"%.1f".format(l.bytes / 1e9)} GB | ${DeviceFit.label(DeviceFit.fit(text, q, l.bytes, 0, l.height))}"
    }

    private fun minutesFor(videoId: String, t: EpisodeCheck.Target?): Int = runCatching {
        val show = t?.showKey
        if (show != null) LinkMemory.minutesOf("series", show)
        else if (Regex("^tt\\d+$").matches(videoId.trim())) LinkMemory.minutesOf("movie", videoId.trim())
        else 0
    }.getOrDefault(0)

    /**
     * Good enough to stop waiting for slower sources: plays well here, at the best size this TV shows, from a
     * trusted name. Autoplay then gives the rest a short grace instead of the slowest add-on's full 20 s.
     */
    fun settleMs(top: Link): Long {
        val q = ReleaseQuality.parse(textFor(top))
        val fit = DeviceFit.fit(textFor(top), q, top.bytes, 0, top.height)
        val c = DeviceFit.can()
        val bestHeight = if (c == null || c.tvH == 0 || c.tvH >= 2000) 2160 else 1080
        val h = maxOf(q.resolution ?: 0, top.height)
        return when {
            fit == DeviceFit.GOOD && h >= bestHeight && !q.lowQuality -> 2_000
            fit == DeviceFit.GOOD -> 4_000
            else -> 7_000
        }
    }

    /**
     * The link's file can't be this title (wrong years, a 4K copy of an old anime): TitleFacts against its file name,
     * whichever source it came from (user 2026-11-26). Null = fine or facts unknown.
     */
    fun clashOf(l: Link, videoId: String, t: EpisodeCheck.Target?): String? {
        val imdb = videoId.substringBefore(':').takeIf { it.startsWith("tt") }
        // A hand fix's reject list works even before IMDb's facts are known.
        if (imdb != null && listOfNotNull(l.fileName, l.stream.behaviorHints?.filename, l.stream.name, l.stream.title).any { com.lekathedon.visionlite.tb.TitleFixes.rejects(imdb, it) }) return "fix_reject"
        val f = com.lekathedon.visionlite.tb.TitleFacts.get(imdb) ?: return null
        val target = com.lekathedon.visionlite.tb.DiscoveryTarget(videoId.substringBefore(':'), t != null, listOf(f.name), f.year, t?.season, t?.episode,
            isAnime = t != null && f.kind == com.lekathedon.visionlite.tb.TitleFacts.Kind.ANIME, endYear = f.endYear)
        return listOfNotNull(l.fileName, l.stream.behaviorHints?.filename, l.stream.title?.lineSequence()?.firstOrNull()).firstNotNullOfOrNull { com.lekathedon.visionlite.tb.DiscoveryPipeline.clash(it, target) }
    }

    /** The order autoplay tries links in (the manual list keeps every row). */
    /**
     * [l] is as good as [top] here: same fit, same 4K, same Dolby Vision tier (FEL on a FEL box), and at least 90 % of
     * its size. Resume keeps its saved file only then (user 2026-11-28: never locked out of the best copy).
     */
    fun asGood(l: Link, top: Link, minutes: Int = 0): Boolean {
        if (l === top) return true
        val ql = ReleaseQuality.parse(textFor(l)); val qt = ReleaseQuality.parse(textFor(top))
        val fl = DeviceFit.fit(textFor(l), ql, l.bytes, minutes, l.height); val ft = DeviceFit.fit(textFor(top), qt, top.bytes, minutes, top.height)
        val k4l = ql.resolution == 2160 || l.height == 2160; val k4t = qt.resolution == 2160 || top.height == 2160
        val dvl = if (felBox) dvTier(textFor(l), ql) else 0; val dvt = if (felBox) dvTier(textFor(top), qt) else 0
        return fl >= ft && (k4l || !k4t) && dvl >= dvt && (top.bytes <= 0 || l.bytes >= top.bytes * 0.9)
    }

    fun order(links: List<Link>, videoId: String): List<Link> {
        // Link memory: a link that failed or kept buffering this week is skipped; your pick for the show goes first.
        val t = EpisodeCheck.targetOf(videoId)
        val ranked = rank(pool(links, t, videoId), minutesFor(videoId, t), audioWanted(videoId)).filter { LinkMemory.bad(it) == null && clashOf(it, videoId, t) == null }
        // Your earlier pick only breaks ties (user 2026-11-28: a 720p played once under a wrong speed reading was put
        // above every 4K link of Ray): it goes first only when it is as good as the best copy here.
        val top = ranked.firstOrNull()
        val mine = ranked.filter { LinkMemory.pickMatch(videoId, it) > 0 && top != null && asGood(it, top) }.sortedByDescending { LinkMemory.pickMatch(videoId, it) }
        return mine.take(1) + ranked.filter { it !in mine.take(1) }
    }

    private val checkClient by lazy { Net.http.newBuilder().callTimeout(3_000, TimeUnit.MILLISECONDS).build() }

    /** A finished add-on link: what file does it really serve? One Range request, body never read; no answer = UNKNOWN. */
    suspend fun checkLink(url: String, headers: Map<String, String>, t: EpisodeCheck.Target): EpisodeCheck.Verdict = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).header("Range", "bytes=0-0").apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            checkClient.newCall(req).execute().use { answer(it, t) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { EpisodeCheck.Verdict.UNKNOWN }
    }

    private fun answer(r: Response, t: EpisodeCheck.Target): EpisodeCheck.Verdict {
        val names = buildList {
            add(EpisodeCheck.fileNameFromDisposition(r.header("Content-Disposition")))
            add(EpisodeCheck.fileNameFromUrl(r.request.url.toString()))
            var prior = r.priorResponse
            while (prior != null) { add(EpisodeCheck.fileNameFromUrl(prior.header("Location"))); prior = prior.priorResponse }
        }
        val titles = EpisodeFacts.titlesOf(t)
        if (names.any { EpisodeCheck.otherShow(it, titles) }) return EpisodeCheck.Verdict.WRONG
        names.forEach { n -> EpisodeCheck.readName(n, t).let { if (it != EpisodeCheck.Verdict.UNKNOWN) return it } }
        return EpisodeCheck.Verdict.UNKNOWN
    }

    /** Torrentio asked for this exact episode: its hashes are that episode, and how their names number it is noted. */
    fun vouch(videoId: String, hashesAndNames: List<Pair<String, String?>>) {
        val t = EpisodeCheck.targetOf(videoId) ?: return
        if (hashesAndNames.isEmpty()) return
        EpisodeVouches.record(t.season, t.episode, hashesAndNames.map { it.first })
        val asked = EpisodeCheck.Target(t.season, t.episode)
        val v = hashesAndNames.groupBy { EpisodeCheck.readName(it.second, asked) }
        EpisodeVouches.recordNumbering(t.showKey!!, t.season, t.episode,
            v[EpisodeCheck.Verdict.NAMED].orEmpty().map { it.first }, v[EpisodeCheck.Verdict.WRONG].orEmpty().map { it.first })
    }
}
