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

    /** The file's numbers first, then the show's own episode titles (this one's settles it; another's rules it out). */
    fun read(fileName: String?, labels: List<String?>, target: Target): Verdict {
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

    private class Show(val episodes: Map<Pair<Int, Int>, Video>, val titles: Map<Pair<Int, Int>, String>, val notAnimated: Boolean)

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
            synchronized(shows) { shows[key] = Show(episodes, titles, notAnimated) }
        }
    }

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

object AutoPick {
    const val TRUST_VERIFIED = 3

    private val Link.labels get() = listOf(stream.name, stream.title, stream.description)
    private val Link.fileName get() = tb?.fileName ?: stream.behaviorHints?.filename

    fun verdict(l: Link, t: EpisodeCheck.Target?): EpisodeCheck.Verdict =
        if (t == null) EpisodeCheck.Verdict.UNKNOWN else EpisodeCheck.read(l.fileName, l.labels, t)

    /** 3 = verified (own title, Torrentio filed it under this episode, or numbers on a show where they can be trusted); 2 = numbers; 1 = can't tell; 0 = wrong. */
    fun trust(l: Link, t: EpisodeCheck.Target?): Int {
        if (t == null) return 0
        val vouched = l.source.startsWith("Torrentio", true) || EpisodeVouches.has(t.season, t.episode, l.tb?.hash ?: l.stream.infoHash)
        return when (verdict(l, t)) {
            EpisodeCheck.Verdict.WRONG -> 0
            EpisodeCheck.Verdict.CONFIRMED -> TRUST_VERIFIED
            EpisodeCheck.Verdict.NAMED -> if (vouched || EpisodeFacts.numbersAreEnough(t)) TRUST_VERIFIED else 2
            EpisodeCheck.Verdict.UNKNOWN -> if (vouched) TRUST_VERIFIED else 1
        }
    }

    /** Wrong rows out, then only the surest rows there are; empty = open the list. */
    fun pool(links: List<Link>, t: EpisodeCheck.Target?): List<Link> {
        val playable = links.filter { it.playable }
        if (t == null) return playable
        val trusts = playable.map { trust(it, t) }
        val top = trusts.maxOrNull() ?: return emptyList()
        if (top <= 0) return emptyList()
        return playable.filterIndexed { i, _ -> trusts[i] == top }
    }

    /** Best link: no CAM, 4K first, then the bigger file always; SeaDex best, quality and Vision TB break ties. */
    fun rank(pool: List<Link>): List<Link> = pool.map { l ->
        val q = ReleaseQuality.parse(listOfNotNull(l.stream.name, l.stream.title, l.stream.description, l.fileName).joinToString(" "))
        l to q
    }.sortedWith(
        compareBy<Pair<Link, ReleaseQuality>> { it.second.lowQuality }
            .thenByDescending { it.second.resolution == 2160 || it.first.height == 2160 }
            .thenByDescending { it.first.bytes }
            .thenByDescending { SeaDexBest.isBest(it.first.tb?.hash ?: it.first.stream.infoHash) }
            .thenByDescending { it.second.score() }
            .thenByDescending { it.first.tb != null }
    ).map { it.first }

    /** The order autoplay tries links in (the manual list keeps every row). */
    fun order(links: List<Link>, videoId: String): List<Link> = rank(pool(links, EpisodeCheck.targetOf(videoId)))

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
