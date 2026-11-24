// The recommendation engine's scoring half (no Android, no network): Scout's logic (core/scout/ScoutPicksLogic) as
// the base, with Vision Picks' rules on top (core/recommendations/VisionPicksScoring): IMDb and Trakt weigh most,
// titles several sources agree on are lifted, the IMDb minimum, and nothing watched, rated or turned down.
package com.lekathedon.visionlite.recs

import kotlinx.serialization.Serializable
import java.text.Normalizer
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

@Serializable data class Person(val id: String, val name: String)

/** What one IMDb title adds to the page. [full] = keywords, crew, language, countries and age are known too. */
@Serializable data class Facts(
    val id: String,
    val name: String = "",
    val tv: Boolean = false,
    val year: Int? = null,
    val rating: Float? = null,
    val votes: Int? = null,
    val poster: String? = null,
    val genres: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val crew: List<Person> = emptyList(),
    val language: String? = null,
    val countries: List<String> = emptyList(),
    val age: Int? = null,
    val full: Boolean = false,
    val at: Long = 0L,
    /** Top-billed actors (full facts from 0.28 on; [v] 1), only to find your favourite people's work. */
    val cast: List<Person> = emptyList(),
    val v: Int = 0,
    /** IMDb's interests (its genre / subgenre tags, "in0000057" = Police Procedural); full facts from [v] 2. */
    val interests: List<String> = emptyList()
)

/** One recommendation. [because] = the watched title that pulled hardest; [why] = a person or theme in common. */
@Serializable data class Pick(
    val imdb: String,
    val type: String,
    val name: String,
    val year: Int? = null,
    val rating: Float? = null,
    val poster: String? = null,
    val genres: List<String> = emptyList(),
    val because: String? = null,
    val why: String? = null,
    val stretch: Boolean = false,
    val sources: Int = 1
)

@Serializable data class Because(val seed: String, val picks: List<Pick>)

@Serializable data class Pool(
    val builtAt: Long = 0L,
    val signature: String = "",
    val picks: List<Pick> = emptyList(),
    val because: List<Because> = emptyList(),
    val seeds: Int = 0,
    val version: Int = 0
)

/** A title the user has a signal for; [weight] < 0 steers away. */
data class Seed(val id: String, val tv: Boolean, val name: String, val weight: Double)

/** IMDb first, Trakt second (the user's rule): how much each source's word counts. */
enum class Source(val weight: Double) { IMDB(1.3), TRAKT(1.15), TASTE(1.0) }

data class Signal(val id: String, val tv: Boolean, val name: String, val kind: Kind, val atMs: Long, val episodes: Int = 1)

/** Strongest first: when one title has several signals, the first one in this order counts. */
enum class Kind { LIKED, NOT_FOR_ME, LOVE, LIKE, REWATCHED, STOPPED, FINISHED, WATCHING }

object RecsLogic {
    /** 2 (0.27): six "because you watched" rows, Love / Like. */
    const val VERSION = 8  // 8: body / nudity / sex keywords never steer or explain a pick (Body.kt). 5: Because you watched scored by agreement + likeness (all sources), regional + subgenre rows strict
    const val SEEDS = 20
    const val NEGATIVE = 4
    const val MIN_SEEDS = 3
    const val POOL = 200
    const val LOOKUPS = 100
    const val REFRESH_MS = 24L * 3600_000
    const val MIN_REBUILD_GAP_MS = 3600_000L
    const val ROTATE_MS = 12L * 3600_000

    private const val DECAY = 0.97
    private const val STOPPED_AFTER_MS = 14L * 86_400_000
    private const val RANK_DEPTH = 40.0
    private const val MULTI_SOURCE_BOOST = 0.6
    private const val MIN_VOTES = 1000
    private const val MIN_RATING = 6f
    private const val KEYWORD_SCALE = 200.0
    private const val MAX_DOT_PENALTY = -0.2
    private const val W_RANK = 1.0
    private const val W_RATING = 0.15
    private const val W_KEYWORDS = 3.0
    private const val W_PEOPLE = 0.5
    /** A favourite person (Follow on their page) in the cast or crew: as strong as a theme you keep coming back to. */
    private const val W_FAV = 0.6
    private const val W_INTEREST = 0.5
    private const val W_LANGUAGE = 0.15
    private const val W_COUNTRY = 0.075
    private const val W_APPETITE = 0.12
    private const val W_AGE = 0.6
    private const val MAX_SAME_SERIES = 1
    private const val MAX_SAME_PERSON = 3
    private const val SAME_KEYWORDS = 0.45
    private const val STRETCH_EVERY = 5
    private const val STRETCH_START = 20
    private const val STRETCH_WINDOW = 120
    private const val STRETCH_RATING = 7f
    private const val STRETCH_VOTES = 5000
    private val KIDS = setOf("Family", "Animation")
    private val NOT_FOR_KIDS = setOf("Horror", "Thriller", "Crime", "War")
    private val SKIP_KEYWORDS = setOf("based on novel", "based on book", "female director", "post credits scene", "sequel", "title spoken by character")

    private fun Kind.weight() = when (this) {
        Kind.LIKED -> 8.0; Kind.LOVE -> 6.0; Kind.REWATCHED -> 4.0; Kind.LIKE -> 3.0; Kind.FINISHED -> 1.0
        Kind.WATCHING -> 0.5; Kind.STOPPED -> -1.0; Kind.NOT_FOR_ME -> -3.0
    }

    /** A show with 1-2 episodes seen and nothing for two weeks was dropped: a mild minus. */
    fun finishedKind(tv: Boolean, episodes: Int, atMs: Long, now: Long) =
        if (tv && episodes in 1..2 && atMs > 0 && now - atMs > STOPPED_AFTER_MS) Kind.STOPPED else Kind.FINISHED

    /** Every signal newest first, each a little lighter than the one before; Watching only fills in when there's too little else. */
    fun seeds(signals: List<Signal>): List<Seed> {
        val best = signals.groupBy { it.id }.map { (_, s) -> s.minBy { it.kind.ordinal }.copy(atMs = s.maxOf { it.atMs }) }
        val (watching, rest) = best.sortedByDescending { it.atMs }.partition { it.kind == Kind.WATCHING }
        val weighted = rest.mapIndexed { i, s -> Seed(s.id, s.tv, s.name, s.kind.weight() * DECAY.pow(i)) }
        val positive = weighted.filter { it.weight > 0 }.sortedByDescending { it.weight }.take(SEEDS)
        val filler = if (positive.size < MIN_SEEDS) watching.mapIndexed { i, s -> Seed(s.id, s.tv, s.name, Kind.WATCHING.weight() * DECAY.pow(i)) }
            .take(SEEDS - positive.size) else emptyList()
        val negative = weighted.filter { it.weight < 0 }.sortedBy { it.weight }.take(NEGATIVE)
        return positive + filler + negative
    }

    fun signature(seeds: List<Seed>, minImdb: Float) =
        (seeds.map { it.id + ":" + it.weight.toInt() } + "imdb$minImdb").joinToString(",")

    fun shouldRefresh(pool: Pool?, signature: String, now: Long): Boolean {
        if (pool == null || pool.builtAt <= 0 || pool.version < VERSION) return true
        val age = now - pool.builtAt
        return age < 0 || age >= REFRESH_MS || (signature != pool.signature && age >= MIN_REBUILD_GAP_MS)
    }

    // ---- exclusions: anything watched, started, rated or turned down, also under another id (same name and year) ----

    private val MARKS = Regex("\\p{M}+")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
    fun normal(name: String) = Normalizer.normalize(name, Normalizer.Form.NFD).replace(MARKS, "").lowercase().replace(NOT_WORD, " ").trim()

    class Exclusions(val ids: Set<String>, val names: Set<String>) {
        fun blocks(f: Facts): Boolean {
            if (f.id in ids) return true
            val n = normal(f.name)
            if (n in names) return true
            val y = f.year ?: return false
            return (y - 1..y + 1).any { "$n|$it" in names }
        }
    }

    fun exclusionName(name: String, year: Int?) = normal(name) + (year?.let { "|$it" } ?: "")

    // ---- fusing the sources (Vision) over Scout's rank shares ----

    class Tallied(val facts: Facts, val score: Double, val because: String?, val sources: Int)

    /** [results]: each source's list for each seed, best first. */
    fun tally(results: List<Triple<Source, Seed, List<Facts>>>, ex: Exclusions, minImdb: Float): Map<String, Tallied> {
        val score = HashMap<String, Double>()
        val facts = HashMap<String, Facts>()
        val perSeed = HashMap<String, HashMap<String, Double>>()
        val sources = HashMap<String, HashSet<Source>>()
        val seedIds = results.map { it.second.id }.toSet()
        results.forEach { (source, seed, list) ->
            list.distinctBy { it.id }.forEachIndexed { rank, f ->
                val share = seed.weight * source.weight * max(0.05, 1.0 - rank / RANK_DEPTH)
                score.merge(f.id, share, Double::plus)
                facts.merge(f.id, f) { a, b -> if (b.full || b.rating != null && a.rating == null) b else a }
                if (seed.weight > 0 && seed.name.isNotBlank()) perSeed.getOrPut(f.id) { HashMap() }.merge(seed.name, share, Double::plus)
                sources.getOrPut(f.id) { HashSet() } += source
            }
        }
        return score.mapNotNull { (id, s) ->
            val f = facts.getValue(id)
            val n = sources[id]?.size ?: 1
            val boosted = s * (1 + MULTI_SOURCE_BOOST * (n - 1))
            if (boosted <= 0 || id in seedIds || ex.blocks(f) || !passes(f, minImdb)) null
            else id to Tallied(f, boosted, perSeed[id]?.maxByOrNull { it.value }?.key, n)
        }.toMap()
    }

    /** Enough votes, Scout's quality line, and the IMDb minimum. A title IMDb has no rating for stays. */
    fun passes(f: Facts, minImdb: Float): Boolean {
        val r = f.rating ?: return f.votes == null || f.votes >= MIN_VOTES
        return (f.votes ?: 0) >= MIN_VOTES && r >= MIN_RATING && r >= minImdb
    }

    // ---- taste (Scout) ----

    class Taste(
        val keywords: Map<String, Double>, val people: Map<String, Double>, val names: Map<String, String>,
        val loved: Set<String>, val languages: Map<String, Double>, val countries: Map<String, Double>, val total: Double,
        val logMean: Double, val logSd: Double, val ageFloor: Int?, val ageCeiling: Int?, val avoided: Set<String>,
        /** Your favourite people: IMDb name id to name. */
        val favs: Map<String, String> = emptyMap(),
        /** Your genres (IMDb interest id → 0..1): learned from what you watch and rate, liked ones at 1. */
        val interests: Map<String, Double> = emptyMap()
    )

    private fun Facts.words() = keywords.filter { it !in SKIP_KEYWORDS && !Body.word(it) }
    private fun rarity(k: String, freq: Map<String, Int>) = ln(1 + KEYWORD_SCALE / (1 + (freq[k] ?: 0)))

    fun ageLevel(cert: String?): Int? = when (cert) {
        "TV-Y", "TV-Y7", "TV-G", "G" -> 0
        "TV-PG", "PG" -> 1
        "TV-14", "PG-13" -> 2
        "TV-MA", "R", "NC-17" -> 3
        else -> null
    }

    fun taste(items: List<Pair<Facts, Double>>, loved: Set<String>, favs: Map<String, String> = emptyMap(), interests: Map<String, Double> = emptyMap()): Taste {
        val kw = HashMap<String, Double>(); val ppl = HashMap<String, Double>(); val names = HashMap<String, String>()
        val lovedPeople = HashSet<String>(); val lang = HashMap<String, Double>(); val ctry = HashMap<String, Double>()
        val nope = HashMap<String, Double>(); val nopeCount = HashMap<String, Int>(); val logs = ArrayList<Double>()
        var total = 0.0
        items.forEach { (f, w) ->
            if (w > 0) {
                total += w
                f.words().forEach { kw.merge(it, w, Double::plus) }
                f.crew.distinctBy { it.id }.forEach { ppl.merge(it.id, w, Double::plus); names[it.id] = it.name; if (f.id in loved) lovedPeople += it.id }
                f.language?.let { lang.merge(it, w, Double::plus) }
                f.countries.forEach { ctry.merge(it, w, Double::plus) }
                f.votes?.let { logs += log10(it + 10.0) }
            } else if (w < 0) {
                f.words().forEach { kw.merge(it, w * 0.5, Double::plus) }
                f.language?.let { nope.merge(it, -w, Double::plus); nopeCount.merge(it, 1, Int::plus) }
            }
        }
        val mean = if (logs.isEmpty()) 4.5 else logs.average()
        val sd = if (logs.size > 1) max(0.4, sqrt(logs.sumOf { (it - mean).pow(2) } / logs.size)) else 0.8
        val ages = items.filter { it.second > 0 }.mapNotNull { (f, w) -> f.age?.let { it to w } }
        val avoided = nopeCount.filter { (l, c) -> c >= 2 && (lang[l] ?: 0.0) < nope.getValue(l) }.keys
        return Taste(kw, ppl, names, lovedPeople, lang, ctry, total.coerceAtLeast(1.0), mean, sd, ageFloor(ages), ageCeiling(ages), avoided, favs, interests)
    }

    /** The lowest age level really watched; below it a pick is pushed down, never removed. */
    private fun ageFloor(ages: List<Pair<Int, Double>>): Int? {
        if (ages.size < 5) return null
        val total = ages.sumOf { it.second }; var seen = 0.0
        for (level in 0 until 2) { seen += ages.filter { it.first == level }.sumOf { it.second }; if (seen > 0.2 * total) return level }
        return 2
    }

    /** A profile that only watches young titles: above its level a pick is removed. */
    private fun ageCeiling(ages: List<Pair<Int, Double>>): Int? {
        if (ages.size < 3) return null
        val total = ages.sumOf { it.second }
        if (ages.filter { it.first > 1 }.sumOf { it.second } > 0.05 * total) return null
        return if (ages.filter { it.first == 0 }.sumOf { it.second } >= 0.95 * total) 0 else 1
    }

    fun withinCeiling(f: Facts, ceiling: Int?): Boolean = when {
        ceiling == null -> true
        f.age != null -> f.age <= ceiling
        else -> f.genres.any { it in KIDS } && f.genres.none { it in NOT_FOR_KIDS }
    }

    private fun keywordNorm(t: Taste, freq: Map<String, Int>) =
        sqrt(t.keywords.entries.filter { it.value > 0 }.sumOf { (it.value * rarity(it.key, freq)).pow(2) }).coerceAtLeast(1.0)

    private fun score(f: Facts, rank: Double, t: Taste, freq: Map<String, Int>, norm: Double): Double {
        var s = W_RANK * sqrt(rank) + W_RATING * (f.rating ?: 0f) / 10.0
        if (f.full) {
            val own = f.words()
            if (own.isNotEmpty()) {
                val dot = own.filter { it in t.keywords }.sumOf { t.keywords.getValue(it) * rarity(it, freq).pow(2) }
                val size = sqrt(own.sumOf { rarity(it, freq).pow(2) }).coerceAtLeast(1.0)
                s += W_KEYWORDS * max(MAX_DOT_PENALTY, dot / (norm * size))
            }
            if (f.crew.isNotEmpty() && t.people.isNotEmpty()) {
                val top = t.people.values.max().coerceAtLeast(1e-6)
                s += W_PEOPLE * min(1.0, f.crew.distinctBy { it.id }.sumOf { max(0.0, t.people[it.id] ?: 0.0) } / top)
            }
            if (t.favs.isNotEmpty() && (f.crew.any { it.id in t.favs } || f.cast.any { it.id in t.favs })) s += W_FAV
            // Your genres have a say: the strongest of yours this title carries.
            if (t.interests.isNotEmpty()) s += W_INTEREST * (f.interests.maxOfOrNull { t.interests[it] ?: 0.0 } ?: 0.0)
            f.language?.let { s += W_LANGUAGE * (t.languages[it] ?: 0.0) / t.total }
            s += W_COUNTRY * (f.countries.maxOfOrNull { t.countries[it] ?: 0.0 } ?: 0.0) / t.total
            if (f.age != null && t.ageFloor != null && f.age < t.ageFloor) s -= W_AGE * (t.ageFloor - f.age)
        }
        val z = (log10((f.votes ?: 0) + 10.0) - t.logMean) / t.logSd
        return s - W_APPETITE * z * z
    }

    /** The fused list re-sorted by taste (the [LOOKUPS] best have full facts), then series, people and near-copies spread out, a stretch every fifth place. */
    fun rank(tally: Map<String, Tallied>, t: Taste, facts: Map<String, Facts>): List<Pick> {
        val freq = HashMap<String, Int>()
        facts.values.forEach { f -> f.words().forEach { freq.merge(it, 1, Int::plus) } }
        val ranked = tally.values.filter { withinCeiling(facts[it.facts.id] ?: it.facts, t.ageCeiling) }
            .sortedByDescending { it.score + 0.3 * (it.facts.rating ?: 0f) / 10.0 }
        val head = ranked.take(LOOKUPS)
        val top = head.maxOfOrNull { it.score }?.coerceAtLeast(1e-6) ?: 1.0
        val norm = keywordNorm(t, freq)
        val ordered = head.sortedByDescending { score(facts[it.facts.id] ?: it.facts, it.score / top, t, freq, norm) } + ranked.drop(LOOKUPS)
        val spread = limitDuplicates(ordered.map { it to (facts[it.facts.id] ?: it.facts) })
        return stretch(spread, t).take(POOL).map { (x, f, stretched) -> toPick(x, f, t, freq, stretched) }
    }

    /** "Name 2", "Name: Part Two"… share a series key; at most one of a series, three by one person, nothing nearly the same as a pick above. */
    private fun seriesKey(name: String) = normal(name).substringBefore(" part ").replace(Regex("\\s+(\\d+|ii|iii|iv|v)$"), "").split(' ').take(3).joinToString(" ")

    private fun limitDuplicates(list: List<Pair<Tallied, Facts>>): List<Pair<Tallied, Facts>> {
        val kept = ArrayList<Pair<Tallied, Facts>>(); val later = ArrayList<Pair<Tallied, Facts>>()
        val series = HashMap<String, Int>(); val person = HashMap<String, Int>(); val keptWords = ArrayList<Set<String>>()
        list.forEach { p ->
            val f = p.second
            val words = f.words().toSet()
            val key = seriesKey(f.name)
            val repeat = (series[key] ?: 0) >= MAX_SAME_SERIES || f.crew.distinctBy { it.id }.any { (person[it.id] ?: 0) >= MAX_SAME_PERSON } ||
                (words.isNotEmpty() && keptWords.takeLast(30).any { o -> val u = (words + o).size; u > 0 && (words intersect o).size.toDouble() / u >= SAME_KEYWORDS })
            if (repeat) later += p else {
                kept += p; keptWords += words; series.merge(key, 1, Int::plus)
                f.crew.distinctBy { it.id }.forEach { person.merge(it.id, 1, Int::plus) }
            }
        }
        return kept + later
    }

    /** Every fifth place: a well rated pick outside the main language or main genres, never in a language often turned down. */
    private fun stretch(list: List<Pair<Tallied, Facts>>, t: Taste): List<Triple<Tallied, Facts, Boolean>> {
        val mainLang = t.languages.maxByOrNull { it.value }?.key
        val common = list.take(STRETCH_START + 10).flatMap { it.second.genres }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(2).map { it.key }.toSet()
        val extras = list.take(STRETCH_WINDOW).drop(STRETCH_START).filter { (_, f) ->
            (f.rating ?: 0f) >= STRETCH_RATING && (f.votes ?: 0) >= STRETCH_VOTES && f.language !in t.avoided &&
                (f.language != mainLang || f.genres.none { it in common })
        }.iterator()
        val used = HashSet<String>(); val out = ArrayList<Triple<Tallied, Facts, Boolean>>(list.size)
        list.forEach { p ->
            if (p.second.id in used) return@forEach
            if ((out.size + 1) % STRETCH_EVERY == 0 && extras.hasNext()) { val e = extras.next(); if (used.add(e.second.id)) out += Triple(e.first, e.second, true) }
            if (used.add(p.second.id)) out += Triple(p.first, p.second, false)
        }
        return out
    }

    /** The plainest true reason: a loved or often watched person, else a theme the user keeps coming back to. */
    private fun toPick(x: Tallied, f: Facts, t: Taste, freq: Map<String, Int>, stretched: Boolean): Pick {
        val person = f.crew.distinctBy { it.id }.filter { (t.people[it.id] ?: 0.0) > 0 }.maxByOrNull { t.people.getValue(it.id) }
        val word = f.words().filter { (t.keywords[it] ?: 0.0) > 0 && (freq[it] ?: 0) >= 2 }.maxByOrNull { t.keywords.getValue(it) * rarity(it, freq) }
        val fav = f.cast.firstOrNull { it.id in t.favs }?.let { "With ${it.name}, a favourite of yours" }
            ?: f.crew.firstOrNull { it.id in t.favs }?.let { "From ${it.name}, a favourite of yours" }
        val why = when {
            fav != null -> fav
            person != null && person.id in t.loved -> "From ${person.name}, whose work you love"
            person != null -> "From ${person.name}"
            word != null -> "More $word"
            else -> null
        }
        return Pick(f.id, if (f.tv) "series" else "movie", f.name, f.year, f.rating, f.poster, f.genres, x.because, why, stretched, x.sources)
    }

    /** The same view for half a day, then a different slice of the pool, weighted to the best picks. */
    fun rotate(picks: List<Pick>, count: Int, now: Long): List<Pick> {
        if (picks.size <= count) return picks
        val r = java.util.Random(now / ROTATE_MS)
        return picks.mapIndexed { i, p -> p to r.nextDouble() * (picks.size - i) }.sortedByDescending { it.second }.take(count).map { it.first }
    }
}
