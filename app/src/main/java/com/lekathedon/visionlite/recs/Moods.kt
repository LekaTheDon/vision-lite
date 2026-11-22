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
    /** Scout's TMDB filters: movie, then TV (genre and keyword ids as in ScoutSlices). */
    val tmdbMovie: String,
    val tmdbTv: String,
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
        tmdbTv = "with_keywords=9887|3307|210710|312814|362252&without_genres=10751,10762&vote_average.gte=7&vote_count.gte=30");

    /** Does the engine's title fit? Genres decide; keywords add a title the genres miss ([WEIRD] is keywords only). */
    fun fits(genres: List<String>, keywords: List<String>, rating: Float?): Boolean {
        if (genres.any { it in none }) return false
        if (minRating > 0 && (rating ?: 0f) < minRating) return false
        val kw = keywords.map { it.lowercase() }
        return genres.any { it in any } || (words.isNotEmpty() && kw.any { k -> words.any { k.contains(it) } })
    }
}

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
        return all.filter { mood.fits(it.genres, kw[it.imdb].orEmpty(), it.rating) }
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
                val m = async { Tmdb.items(Tmdb.get("discover/movie?include_adult=false&sort_by=popularity.desc&${mood.tmdbMovie}$kw$adult"), false) }
                val t = async { Tmdb.items(Tmdb.get("discover/tv?include_adult=false&sort_by=popularity.desc&${notTalk(mood.tmdbTv)}$kw$adult"), true) }
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
