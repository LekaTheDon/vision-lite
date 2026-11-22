// Moods, part of the engine (user 2026-11-18): Scout's twelve moods, filled first from the engine's own finds (new to
// you, IMDb minimum, Not for me left out) read by their IMDb genres and keywords; then, with a TMDB key, topped up from
// TMDB with Scout's own mood filters (ScoutSlices), without anything watched on Trakt, saved or turned down.
// Cost: nothing until a mood is opened; then the engine part is in memory (no requests) and the top-up is 2 TMDB
// requests, kept for the app run.
package com.lekathedon.visionlite.recs

import com.lekathedon.visionlite.addons.MetaPreview
import com.lekathedon.visionlite.home.Tmdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

enum class Mood(
    val label: String,
    val why: String,
    /** Any of these IMDb genres… */
    val any: Set<String>,
    /** …and none of these. */
    val none: Set<String> = emptySet(),
    /** IMDb keywords that place a title here even without the genre (read where the engine has them). */
    val words: Set<String> = emptySet(),
    val minRating: Float = 0f,
    /** Scout's TMDB filters: movie, then TV (genre and keyword ids as in ScoutSlices). Vision's moods build theirs. */
    val tmdbMovie: String = "",
    val tmdbTv: String = "",
    /** Vision's moods (ExploreRules): KW = its TMDB/IMDb keywords decide, GEN = genres only, BOTH = genre and keyword. */
    val vision: VMode? = null,
    /** Vision: the genre that must also be there when a title's keywords aren't known (Drama for "Food for thought"). */
    val alsoGenre: String? = null,
    val seriesOnly: Boolean = false,
) {
    FEEL_GOOD("Feel-good", "Warm and easy to watch.", setOf("Comedy", "Family", "Animation", "Romance"),
        setOf("Horror", "Thriller", "Crime", "Mystery", "War"), setOf("feel good", "heartwarming", "friendship", "uplifting"),
        tmdbMovie = "with_genres=35|10751|16|10749&with_keywords=383896|319357|334465|325782|325784|335803|288816&without_genres=27,53,80,9648,10752&vote_average.gte=6.5&vote_count.gte=100",
        tmdbTv = "with_genres=35|10751&with_keywords=383896|319357|334465|325782|325784|335803|288816&without_genres=80,9648,10759,10768&vote_average.gte=6.5&vote_count.gte=30"),
    LAUGH("Laugh out loud", "Plenty of laughs.", setOf("Comedy"), setOf("Drama", "Horror"), setOf("slapstick", "parody", "satire", "spoof"),
        tmdbMovie = "with_genres=35&with_keywords=320420|386408|319397&without_genres=18,27&vote_average.gte=6.5&vote_count.gte=100",
        tmdbTv = "with_genres=35&with_keywords=320420|386408|319397&without_genres=18&vote_average.gte=6.5&vote_count.gte=30"),
    ACTION("Action-packed", "Plenty of action.", setOf("Action"), setOf("Animation", "Family"), minRating = 6.8f,
        tmdbMovie = "with_genres=28&vote_average.gte=6.8&vote_count.gte=500",
        tmdbTv = "with_genres=10759&without_genres=16,10751&vote_average.gte=7.2&vote_count.gte=100"),
    EDGE("Edge of your seat", "Keeps you guessing.", setOf("Thriller", "Mystery"), setOf("Horror", "Comedy", "Family", "Animation"),
        setOf("suspense", "plot twist", "cat and mouse", "conspiracy"),
        tmdbMovie = "with_genres=53|80|9648&with_keywords=314730|321464|316832|353406|288394&without_genres=27,35,10751,16&vote_average.gte=6.5&vote_count.gte=100",
        tmdbTv = "with_genres=80|9648&with_keywords=314730|321464|316832|353406|288394&without_genres=35,16,10751,10765&vote_average.gte=6.5&vote_count.gte=30"),
    MIND("Mind-bending", "Makes you think.", setOf("Sci-Fi", "Mystery"), setOf("Horror", "Animation", "Family", "Comedy"),
        setOf("time travel", "alternate reality", "mind bending", "nonlinear timeline", "dream", "simulation", "parallel universe"), 7.0f,
        tmdbMovie = "with_genres=878|9648|53&with_keywords=362567|9887|33465|170351|334469|3972|10854&without_genres=27,16,10751&vote_average.gte=7&vote_count.gte=100",
        tmdbTv = "with_genres=10765|9648&with_keywords=362567|9887|33465|170351|334469|3972|10854&without_genres=16,10751,35,80,10759&vote_average.gte=7&vote_count.gte=30"),
    DARK("Dark and gritty", "Dark and gripping.", setOf("Crime"), setOf("Comedy", "Family", "Animation", "Romance", "Sci-Fi", "Fantasy"),
        setOf("neo noir", "gritty", "organized crime", "corruption"), 7.0f,
        tmdbMovie = "with_genres=80&without_genres=35,10751,16,10749,878,14,12&vote_average.gte=7&vote_count.gte=500",
        tmdbTv = "with_genres=80|18&with_keywords=259094|230747|286125|361070&without_genres=35,10751,16,10765&vote_average.gte=6.5&vote_count.gte=30"),
    SCARY("Scary", "One for the brave.", setOf("Horror"), setOf("Comedy", "Family", "Animation"),
        tmdbMovie = "with_genres=27&vote_average.gte=6.3&vote_count.gte=200",
        tmdbTv = "with_keywords=315058|256183|3358|12339|295907|283085|385522&without_genres=35,16,10751&without_keywords=4565&vote_average.gte=7&vote_count.gte=30"),
    ROMANTIC("Romantic", "A love story.", setOf("Romance"), setOf("Horror", "Crime"),
        tmdbMovie = "with_genres=10749&with_keywords=324429|9840&vote_average.gte=6.5&vote_count.gte=100",
        tmdbTv = "with_keywords=324429|9840|386551|304976&without_genres=16,80,9648,10759&vote_average.gte=7&vote_count.gte=30"),
    EPIC("Epic", "Big and sweeping.", setOf("Adventure", "Fantasy", "History", "War"), setOf("Animation", "Family", "Comedy"),
        setOf("epic", "kingdom", "battle", "quest"), 7.2f,
        tmdbMovie = "with_genres=12|14|36|10752&with_keywords=377433|211227|161257|161176|192772|2035|14643|234213&without_genres=16,10751,35&vote_average.gte=7.2&vote_count.gte=500",
        tmdbTv = "with_genres=10765|10768|10759&with_keywords=377433|211227|161257|161176|192772|2035|14643|234213&without_genres=16,35,9648,80&vote_average.gte=7.5&vote_count.gte=100"),
    TEARS("Tear-jerker", "Have the tissues ready.", setOf("Drama"), setOf("Comedy", "Thriller", "Horror", "Action", "Crime", "Sci-Fi", "Fantasy"),
        setOf("grief", "terminal illness", "tragedy", "loss of loved one"), 7.0f,
        tmdbMovie = "with_genres=18&with_keywords=276222|156924|370245|9872|6564|253411&without_genres=35,53,27,28,80,878,14&vote_average.gte=7&vote_count.gte=100",
        tmdbTv = "with_genres=18&with_keywords=276222|156924|370245|9872|6564|253411&without_genres=35,16,80,9648,10759,10768,10765&vote_average.gte=6.5&vote_count.gte=30"),
    SLOW("Slow burn", "Slow, but worth it.", setOf("Drama"), setOf("Action", "Comedy", "Animation", "Family", "Thriller", "Horror"),
        setOf("slow burn", "character study", "meditative"), 7.2f,
        tmdbMovie = "with_genres=18&with_keywords=277551|228988|197056|343508|155800&without_genres=28,35,16,10751&vote_average.gte=7.2&vote_count.gte=100",
        tmdbTv = "with_genres=18&with_keywords=277551|228988|343508|155800&without_genres=35,16,10751,10759,10768&vote_average.gte=7&vote_count.gte=30"),
    WEIRD("Weird and wonderful", "Unusual in a good way.", emptySet(), setOf("Horror", "Family", "Animation"),
        setOf("surrealism", "absurdism", "dark comedy", "quirky", "bizarre", "cult film"), 6.8f,
        tmdbMovie = "with_genres=35|878|14|18&with_keywords=9887|3307|210710|312814|362252&without_genres=27,10751,16&vote_average.gte=6.8&vote_count.gte=100",
        tmdbTv = "with_keywords=9887|3307|210710|312814|362252&without_genres=10751,10762&vote_average.gte=7&vote_count.gte=30"),

    // ---- Vision's eleven (ExploreRules.MOODS); none shares a name with Scout's twelve ----
    FOOD_FOR_THOUGHT("Food for thought", "Endings that stay with you", setOf("Mystery", "Thriller"), words = setOf("plot twist", "twist ending", "memory", "obsession"),
        vision = VMode.KW, alsoGenre = "Drama"),
    TANGLED_TIME("Tangled time", "Past and future blurring together", setOf("Sci-Fi"), words = setOf("time travel", "time loop", "parallel world"),
        vision = VMode.KW, alsoGenre = "Mystery"),
    FAR_FROM_EARTH("Far from Earth", "Space, solitude and first contact", setOf("Sci-Fi"), words = setOf("space", "space travel", "first contact", "astronaut"),
        vision = VMode.KW, alsoGenre = "Adventure"),
    SURVIVING("Surviving together", "When the group is the only way out", setOf("Adventure", "Action"), words = setOf("survival", "survivor", "zombie apocalypse"),
        vision = VMode.KW, alsoGenre = "Drama"),
    SMALL_TOWN("Small town, big secret", "Everyone knows everyone, nobody knows anything", setOf("Mystery"), words = setOf("small town", "family secrets"),
        vision = VMode.KW, alsoGenre = "Drama"),
    FAMILIES("Complicated families", "Bonds, grief and what was left unsaid", setOf("Family"),
        words = setOf("dysfunctional family", "father son relationship", "grief", "sibling relationship"), vision = VMode.KW, alsoGenre = "Drama"),
    BINGE("Mysteries to binge", "Series with a why that won't let go", setOf("Mystery"), vision = VMode.GEN, seriesOnly = true),
    CRIME_GUILT("Crime and guilt", "Who does what, and at what cost", setOf("Crime"), vision = VMode.GEN),
    NERVOUS("Nervous laughter", "Comedy with one foot in the weird", setOf("Comedy"), words = setOf("dark comedy", "satire", "absurd"), vision = VMode.BOTH),
    QUIET_HORROR("Understated horror", "Fear that comes from the atmosphere", setOf("Horror"),
        words = setOf("supernatural", "haunted house", "psychological horror"), vision = VMode.BOTH),
    WORLD_AFTER("The world after", "What's left when something goes wrong", setOf("Sci-Fi"),
        words = setOf("post-apocalyptic future", "dystopia", "climate change"), vision = VMode.KW, alsoGenre = "Drama");

    val key get() = name.lowercase()

    /** Does the engine's title fit? Genres decide; keywords add a title the genres miss ([WEIRD] is keywords only). */
    fun fits(genres: List<String>, keywords: List<String>, rating: Float?, tv: Boolean = false): Boolean {
        if (vision != null) {
            if (seriesOnly && !tv) return false
            val g = genres.any { it in any }
            val kw = keywords.map { it.lowercase() }
            val hasKw = kw.any { it in words }
            return when (vision) {
                VMode.GEN -> g
                VMode.BOTH -> if (kw.isEmpty()) g else g && hasKw
                VMode.KW -> if (kw.isEmpty()) g && (alsoGenre == null || alsoGenre in genres) else hasKw
            }
        }
        if (genres.any { it in none }) return false
        if (minRating > 0 && (rating ?: 0f) < minRating) return false
        val kw = keywords.map { it.lowercase() }
        return genres.any { it in any } || (words.isNotEmpty() && kw.any { k -> words.any { k.contains(it) } })
    }
}

enum class VMode { KW, GEN, BOTH }

object Moods {
    private val topUp = HashMap<Pair<Mood, com.lekathedon.visionlite.anime.Hub>, List<MetaPreview>>()

    /** The engine's own finds for [mood], best first (as the engine ranked them). */
    fun fromEngine(mood: Mood, hub: com.lekathedon.visionlite.anime.Hub = com.lekathedon.visionlite.anime.Hubs.now()): List<Pick> {
        val pool = Recs.pool(hub).value
        val ai = AiPicks.picks(hub).value
        if (pool == null && ai.isEmpty()) return emptyList()
        val nope = Recs.nope()
        val min = Recs.minImdb()
        // Everything the engine has: Vision Picks / Scout's finds, every "because you watched", and the AIs' picks.
        val all = (pool?.picks.orEmpty() + ai + pool?.because.orEmpty().flatMap { it.picks }).distinctBy { it.imdb }
            .filter { it.imdb !in nope && !Adult.hidesId(it.imdb) && (it.rating == null || it.rating >= min) && com.lekathedon.visionlite.anime.Hubs.keepsId(hub, it.imdb) }
        val kw = Recs.keywordsOf(all.map { it.imdb })
        return Recs.imdbLift(all.filter { mood.fits(it.genres, kw[it.imdb].orEmpty(), it.rating, it.type == "series") })
    }

    /** More for [mood] from TMDB with Scout's filters (needs a TMDB key); nothing the engine already shows or you've seen. */
    suspend fun discover(mood: Mood, shown: Set<String>, hub: com.lekathedon.visionlite.anime.Hub = com.lekathedon.visionlite.anime.Hubs.now()): List<MetaPreview> {
        if (Tmdb.key() == null) return emptyList()
        synchronized(topUp) { topUp[mood to hub] }?.let { c -> val n = Recs.nope(); return c.filter { it.id !in n && !Adult.hides(it) } }
        // TMDB's "anime" keyword (210024) steers the ask; the anime list still decides what shows.
        val kw = if (hub == com.lekathedon.visionlite.anime.Hub.ANIME) "&with_keywords=210024" else "&without_keywords=210024"
        val seen = Recs.seenTmdb()
        val min = Recs.minImdb()
        val list = withContext(Dispatchers.IO) {
            coroutineScope {
                val adult = Adult.tmdbWithout()
                val m = async { query(mood, false)?.let { q -> Tmdb.items(Tmdb.get("discover/movie?include_adult=false&sort_by=popularity.desc&$q$kw$adult"), false) }.orEmpty() }
                val t = async { query(mood, true)?.let { q -> Tmdb.items(Tmdb.get("discover/tv?include_adult=false&sort_by=popularity.desc&$q$kw$adult"), true) }.orEmpty() }
                val a = m.await(); val b = t.await()
                if (adult.isNotEmpty()) Adult.clean((a + b).map { it.id })
                // Films and shows taken in turn, so neither side fills the row.
                (0 until maxOf(a.size, b.size)).flatMap { i -> listOfNotNull(a.getOrNull(i), b.getOrNull(i)) }
            }
        }.filter { com.lekathedon.visionlite.anime.Hubs.keeps(hub, it) && it.id !in seen && it.id !in Recs.nope() && Recs.seenName(it.name, it.releaseInfo) !in seen && it.name !in shown &&
            (min <= 0f || (it.imdbRating?.toFloatOrNull() ?: 10f) >= min - 0.5f) }
        synchronized(topUp) { topUp[mood to hub] = list }
        return list
    }

    /** News, reality, soaps and talk shows are never a mood (Scout's NOT_SHOWS_PEOPLE_MEAN). */
    private fun notTalk(q: String): String = if ("without_genres=" in q) q.replace("without_genres=", "without_genres=10763,10764,10766,10767,")
        else "$q&without_genres=10763,10764,10766,10767"

    // ---- a mood's page: films and shows from TMDB, page by page (Scout's filters; Vision's genre + keyword rules) ----

    /** TMDB genre ids of the genres Vision's moods use: movie, then TV (TV has no Horror or Thriller). */
    private val GEN = mapOf("Mystery" to (9648 to 9648), "Thriller" to (53 to 0), "Drama" to (18 to 18), "Sci-Fi" to (878 to 10765),
        "Adventure" to (12 to 10759), "Action" to (28 to 10759), "Family" to (10751 to 10751), "Crime" to (80 to 80),
        "Comedy" to (35 to 35), "Horror" to (27 to 0))

    /** A Vision mood's keywords as TMDB ids (looked up once, kept on the TV). */
    private fun keywordIds(words: Collection<String>): List<Int> {
        val sp = com.lekathedon.visionlite.App.instance.prefs.sp
        return words.mapNotNull { w ->
            val k = "mood_kw_" + w
            sp.getInt(k, -1).takeIf { it != -1 }?.let { return@mapNotNull it.takeIf { id -> id > 0 } }
            val r = Tmdb.get("search/keyword?query=" + java.net.URLEncoder.encode(w, "UTF-8")) ?: return@mapNotNull null
            val id = r.optJSONArray("results")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }
                .firstOrNull { it.optString("name").equals(w, true) }?.optInt("id") } ?: 0
            sp.edit().putInt(k, id).apply()
            id.takeIf { it > 0 }
        }
    }

    /** The mood's TMDB filters for films ([tv] false) or shows; null = the mood has none for that kind. */
    private fun query(mood: Mood, tv: Boolean): String? {
        val v = mood.vision ?: return if (tv) notTalk(mood.tmdbTv) else mood.tmdbMovie
        if (mood.seriesOnly && !tv) return null
        val gen = GEN[mood.any.first()]?.let { if (tv) it.second else it.first } ?: 0
        val parts = ArrayList<String>()
        if (v != VMode.KW) { if (gen == 0 && v == VMode.GEN) return null; if (gen != 0) parts += "with_genres=$gen" }
        if (v != VMode.GEN) {
            val ids = keywordIds(mood.words)
            if (ids.isEmpty()) return null
            parts += "with_keywords=" + ids.joinToString("%7C")
        }
        parts += "vote_count.gte=" + if (tv) "30" else "100"
        return if (tv) notTalk(parts.joinToString("&")) else parts.joinToString("&")
    }

    /** Page [page] of the mood's films or shows (needs a TMDB key): nothing seen, saved, watched or turned down. */
    suspend fun page(mood: Mood, tv: Boolean, page: Int, hub: com.lekathedon.visionlite.anime.Hub): List<MetaPreview> = withContext(Dispatchers.IO) {
        if (Tmdb.key() == null) return@withContext emptyList()
        val q = query(mood, tv) ?: return@withContext emptyList()
        val kw = if (hub == com.lekathedon.visionlite.anime.Hub.ANIME) "&with_keywords=210024" else "&without_keywords=210024"
        val adult = Adult.tmdbWithout()
        val list = Tmdb.items(Tmdb.get("discover/${if (tv) "tv" else "movie"}?include_adult=false&sort_by=popularity.desc&page=$page&$q$kw$adult"), tv)
        if (adult.isNotEmpty()) Adult.clean(list.map { it.id })
        val seen = Recs.seenTmdb(); val nope = Recs.nope()
        list.filter { com.lekathedon.visionlite.anime.Hubs.keeps(hub, it) && it.id !in seen && it.id !in nope && Recs.seenName(it.name, it.releaseInfo) !in seen }
    }

    /** Home's Moods tiles: every mood, each with a picture from its first title the engine has (none = its colour). */
    fun tiles(hub: com.lekathedon.visionlite.anime.Hub): List<MetaPreview> {
        val used = HashSet<String>()
        return Mood.entries.map { m ->
            // Each tile its own picture: the first title no earlier tile took.
            val art = fromEngine(m, hub).firstOrNull { used.add(it.imdb) }?.imdb?.let { com.lekathedon.visionlite.library.backdropOf(it) }
            MetaPreview("mood:${m.key}", "mood", m.label, null, art, description = m.why)
        }
    }

    fun byKey(key: String) = Mood.entries.firstOrNull { it.key == key }

    /** Home's mood rows now: [n] moods with enough to show, a different set every 12 hours. */
    fun forHomeNow(n: Int, hub: com.lekathedon.visionlite.anime.Hub, now: Long = System.currentTimeMillis()): List<Mood> {
        val tmdb = Tmdb.key() != null
        val ok = Mood.entries.filter { tmdb || fromEngine(it, hub).size >= 8 }
        val slot = now / (12 * 3600_000L)
        return ok.shuffled(java.util.Random(slot * 17 + hub.ordinal)).take(n)
    }

    /** A mood row's titles: the engine's finds first, then (with a TMDB key) more from TMDB's mood filters. */
    fun engineMetas(mood: Mood, hub: com.lekathedon.visionlite.anime.Hub): List<MetaPreview> = fromEngine(mood, hub).map { p ->
        MetaPreview(p.imdb, p.type, p.name, p.poster ?: com.lekathedon.visionlite.library.posterOf(p.imdb), com.lekathedon.visionlite.library.backdropOf(p.imdb),
            description = p.why, releaseInfo = p.year?.toString(), imdbRating = p.rating?.let { "%.1f".format(it) }, genres = p.genres)
    }

    /** Moods that have something to show (engine finds first); empty before the engine's first build. */
    fun available(hub: com.lekathedon.visionlite.anime.Hub = com.lekathedon.visionlite.anime.Hubs.now()): List<Mood> =
        Mood.entries.filter { fromEngine(it, hub).size >= 4 || Tmdb.key() != null }
}
