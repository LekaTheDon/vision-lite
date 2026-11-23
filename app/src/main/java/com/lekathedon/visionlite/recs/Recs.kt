package com.lekathedon.visionlite.recs

import android.util.Log
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.anime.Hub
import com.lekathedon.visionlite.anime.Hubs
import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.library.Saves
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The app's one recommendation engine (Vision's Scout + Vision Picks in one). Learns from Trakt (watched, rated,
 * paused), the library and the user's "More like this" / "Not for me"; candidates come from IMDb's "More like this"
 * (keyless, weighs most) and Trakt's related lists; Scout's taste (themes, people, language, country, age, how
 * popular) re-ranks them; anything watched, saved, rated or turned down never comes out, nor anything under the IMDb minimum.
 *
 * Cost: nothing until a screen asks ([refreshIfDue]); then at most once a day, or after an hour when what it learns
 * from changed: Trakt 4 calls + up to 10 related lists (kept 30 days), IMDb about 4 seed requests + up to 10 fact
 * requests the first time (facts kept 30 days, so later days ask far fewer). Nothing to learn from: no requests.
 * The result is a small file shown at once next time.
 */
object Recs {
    private const val TAG = "Recs"
    private const val IMDB = "https://api.graphql.imdb.com/"
    private const val SEED_BATCH = 6
    private const val FACT_BATCH = 20
    private const val SIMILAR = 20
    private const val TRAKT_SEEDS = 10
    /** "Because you watched" rows built (Home shows a few, a different few every 12 h). */
    private const val BECAUSE_ROWS = 6
    private const val BECAUSE_ITEMS = 30
    private const val FACTS_MAX_AGE = 30L * 86_400_000
    private const val FACTS_KEEP = 1500
    private const val RELATED_MAX_AGE = 30L * 86_400_000

    private val dir get() = File(App.instance.profileDir, "recs").apply { mkdirs() }
    private val lock = Mutex()
    private val gate = Semaphore(2)
    private val p get() = App.instance.prefs
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** Rebuild in the background (keeps going when the screen that asked is left). */
    fun rebuildNow(hub: Hub = Hubs.now()) { scope.launch { refreshIfDue(hub, force = true) } }

    // Each hub has its own picks, learnt only from that hub's titles (Films & TV never from anime, Anime only from anime).
    private fun poolFile(hub: Hub) = "pool${hub.suffix}.json"
    private val pools = Hub.entries.associateWith { h -> lazy { MutableStateFlow(read(poolFile(h), Pool.serializer())) } }
    private val buildings = Hub.entries.associateWith { MutableStateFlow(false) }
    /** The hub's last built picks (or null before its first build). */
    fun pool(hub: Hub = Hubs.now()): StateFlow<Pool?> = pools.getValue(hub).value
    fun building(hub: Hub = Hubs.now()): StateFlow<Boolean> = buildings.getValue(hub)
    private val _changed = MutableStateFlow(0)
    /** Bumps when More like this / Not for me changes, so rows redraw. */
    val changed: StateFlow<Int> get() = _changed

    /** Settings → Recommendations → IMDb minimum; 0 = off. */
    fun minImdb(): Float = p.recMinImdb.value.toFloatOrNull() ?: 0f

    // ---- what screens use ----

    /** [count] picks for now (a different slice every 12 h), without anything turned down since the build. */
    fun forYou(count: Int = 30, now: Long = System.currentTimeMillis(), hub: Hub = Hubs.now()): List<Pick> {
        val nope = nope()
        val min = minImdb()
        val all = imdbLift(withAi(pool(hub).value?.picks.orEmpty(), hub).filter { Hubs.keepsId(hub, it.imdb) }
            .filter { it.imdb !in nope && !Adult.hidesId(it.imdb) && (it.rating == null || it.rating >= min) })
        val slice = RecsLogic.rotate(all, count, now)
        // What was the IMDb Picks row (0.28: no row of its own): the engine's IMDb-best take every fifth place.
        val inSlice = slice.mapTo(HashSet()) { it.imdb }
        val best = all.filter { it.imdb !in inSlice && (it.rating ?: 0f) >= maxOf(min, 7.5f) }
            .sortedWith(compareByDescending<Pick> { it.rating ?: 0f }.thenByDescending { it.sources }).iterator()
        val out = ArrayList<Pick>(count)
        slice.forEach { x -> if (out.size < count && (out.size + 1) % 5 == 0 && best.hasNext()) out += best.next(); if (out.size < count) out += x }
        return out
    }

    /**
     * IMDb's rating steers every engine list (Vision Picks, Because you watched, Moods): a title IMDb rates 8+ moves up
     * four places, 7.5+ two, so the engine's IMDb-best show sooner without crowding out taste.
     */
    internal fun imdbLift(list: List<Pick>): List<Pick> = list.withIndex()
        .sortedBy { (i, p) -> i - when { (p.rating ?: 0f) >= 8f -> 4.5; (p.rating ?: 0f) >= 7.5f -> 2.5; else -> 0.0 } }.map { it.value }

    /**
     * The AIs (Gemini, Groq, Mistral, when keyed) as one more source of the engine, not a row of their own: a title the
     * engine found that an AI also named is lifted (one more source agreeing); the AI's own finds join every few places.
     */
    private fun withAi(picks: List<Pick>, hub: Hub): List<Pick> {
        val ai = AiPicks.picks(hub).value
        if (ai.isEmpty()) return picks
        val byId = ai.associateBy { it.imdb }
        val lifted = picks.map { p -> byId[p.imdb]?.let { a -> p.copy(sources = p.sources + 1, why = p.why ?: a.why) } ?: p }
            .sortedByDescending { if (it.imdb in byId) 1 else 0 }.let { l -> l.filter { it.imdb in byId } + l.filter { it.imdb !in byId } }
        val have = picks.mapTo(HashSet()) { it.imdb }
        val extra = ai.filter { it.imdb !in have }.toMutableList()
        if (extra.isEmpty()) return lifted
        val out = ArrayList<Pick>(lifted.size + extra.size)
        lifted.forEachIndexed { i, p -> out += p; if (i % 3 == 2 && extra.isNotEmpty()) out += extra.removeAt(0) }
        out += extra
        return out
    }

    /** The engine's finds IMDb rates highest, without what Vision Picks shows now (a phone-added "IMDb Picks" list row). */
    fun imdbPicks(count: Int = 30, now: Long = System.currentTimeMillis(), hub: Hub = Hubs.now()): List<Pick> {
        val nope = nope()
        val shown = forYou(30, now, hub).map { it.imdb }.toSet()
        val min = maxOf(minImdb(), 7.5f)
        return withAi(pool(hub).value?.picks.orEmpty(), hub).filter { Hubs.keepsId(hub, it.imdb) && it.imdb !in nope && !Adult.hidesId(it.imdb) && it.imdb !in shown && (it.rating ?: 0f) >= min }
            .sortedWith(compareByDescending<Pick> { it.rating ?: 0f }.thenByDescending { it.sources }).take(count)
    }

    /** "Because you watched X" rows, strongest first. */
    fun because(hub: Hub = Hubs.now()): List<Because> {
        val nope = nope()
        return pool(hub).value?.because.orEmpty().map { b -> b.copy(picks = imdbLift(b.picks.filter { it.imdb !in nope && !Adult.hidesId(it.imdb) && Hubs.keepsId(hub, it.imdb) })) }
    }

    /** The "because you watched" rows Home shows now: [n] of them, a different set every 12 hours. */
    fun becauseNow(n: Int, hub: Hub = Hubs.now(), now: Long = System.currentTimeMillis()): List<Because> {
        val all = because(hub).filter { it.picks.size >= 8 }
        if (all.size <= n) return all
        val slot = now / (12 * 3600_000L)
        return all.shuffled(java.util.Random(slot * 31 + hub.ordinal)).take(n)
    }

    /** Your word on a title: [love] (steers hardest), like, or Not for me (never recommended again). [alt] = the card's other id (TMDB). */
    @Serializable data class Fb(val id: String, val tv: Boolean, val name: String, val like: Boolean, val at: Long,
                                val love: Boolean = false, val alt: String? = null,
                                /** "Watched": a tick, then never recommended again (counts as finished, no opinion). */
                                val watched: Boolean = false) {
        /** Never recommended again: turned down or already watched. */
        val gone get() = !like || watched
    }

    private val fbSer = ListSerializer(Fb.serializer())
    fun feedback(): List<Fb> = runCatching { Net.json.decodeFromString(fbSer, p.recFeedback.value) }.getOrDefault(emptyList())

    /** Hold OK → "Love it": steers hardest (like a 9-10 rating), from the next build. */
    fun love(id: String, tv: Boolean, name: String) = mark(Fb(id, tv, name, true, System.currentTimeMillis(), love = true))
    /** "Like": more like this, from the next build. */
    fun moreLikeThis(id: String, tv: Boolean, name: String) = mark(Fb(id, tv, name, true, System.currentTimeMillis()))
    /** "Not for me": gone at once from every recommendation (Picks, Because, Moods, AI), for good, and steers away. */
    fun notForMe(id: String, tv: Boolean, name: String) = mark(Fb(id, tv, name, false, System.currentTimeMillis()))
    /** "Watched": ticked, then gone from every recommendation for good (Picks, Because, Moods, AI, More like this). */
    fun watched(id: String, tv: Boolean, name: String) = mark(Fb(id, tv, name, true, System.currentTimeMillis(), watched = true))
    fun forget(id: String) { p.recFeedback.value = Net.json.encodeToString(fbSer, feedback().filter { it.id != id && it.alt != id }); _changed.value++ }

    /** What you said about [id] ("love", "like", "nope"), or null. */
    fun verdict(id: String): String? = feedback().firstOrNull { it.id == id || it.alt == id }?.let { if (it.watched) "watched" else if (!it.like) "nope" else if (it.love) "love" else "like" }

    /** Titles you marked Watched, by each id they have. */
    fun watchedIds(): Set<String> = feedback().filter { it.watched }.flatMap { listOfNotNull(it.id, it.alt) }.toSet()

    @Volatile private var nopeCache: Pair<String, Set<String>>? = null
    /** Every title turned down, by each id it has (IMDb and TMDB): never recommended again. */
    fun nope(): Set<String> {
        val raw = p.recFeedback.value
        nopeCache?.takeIf { it.first == raw }?.let { return it.second }
        return feedback().filter { it.gone }.flatMap { listOfNotNull(it.id, it.alt) }.toSet().also { nopeCache = raw to it }
    }

    private fun mark(f: Fb) {
        // A TMDB / AniList card: its IMDb id is what the engine knows titles by (the card's id is kept too).
        val drop = setOf(f.id)
        p.recFeedback.value = Net.json.encodeToString(fbSer, (listOf(f) + feedback().filter { it.id !in drop && it.alt !in drop }).take(5000))
        _changed.value++
        if (!f.id.startsWith("tt")) scope.launch {
            val imdb = runCatching { com.lekathedon.visionlite.home.imdbIdOf(com.lekathedon.visionlite.addons.MetaPreview(f.id, if (f.tv) "series" else "movie", f.name)) }
                .getOrNull()?.takeIf { it.startsWith("tt") } ?: return@launch
            val list = feedback().map { if (it.id == f.id) it.copy(id = imdb, alt = f.id) else it }.filter { !(it.id == imdb && it.alt == null && it.at < f.at) }
            p.recFeedback.value = Net.json.encodeToString(fbSer, list)
            if (f.gone) AiPicks.remove(imdb, Hubs.hubOf(imdb))
            _changed.value++
        }
        if (f.gone) AiPicks.remove(f.id, Hubs.hubOf(f.id))
    }

    /** One title's "more like this", on demand (a details page): IMDb + Trakt for it alone, same rules. */
    suspend fun moreLike(id: String, tv: Boolean, name: String): List<Pick> = withContext(Dispatchers.IO) {
        val facts = readFacts()
        val seed = Seed(id, tv, name, 1.0)
        val imdb = seedLookup(listOf(seed), facts)[id].orEmpty()
        val trakt = traktRelated(listOf(seed), facts, System.currentTimeMillis())
        val ex = RecsLogic.Exclusions(seen() + exclusionIds(null), emptySet())
        val hub = Hubs.hubOf(id)
        val tally = RecsLogic.tally((listOf(Triple(Source.IMDB, seed, imdb)) + trakt).map { (s, sd, l) -> Triple(s, sd, l.filter { Hubs.keepsId(hub, it.id) }) }, ex, minImdb())
        val nope = nope()
        tally.values.sortedByDescending { it.score }.map { simplePick(it) }.filter { it.imdb !in nope && !Adult.hidesId(it.imdb) }.take(BECAUSE_ITEMS)
    }

    // ---- building ----

    /** Builds when due (daily, or an hour after what it learns from changed). Returns the pool now in use. */
    suspend fun refreshIfDue(hub: Hub = Hubs.now(), force: Boolean = false): Pool? = lock.withLock {
        withContext(Dispatchers.IO) {
            buildings.getValue(hub).value = true
            // The AIs are one of the engine's sources (their own 12-hour clock; nothing without a key).
            if (AiPicks.configured().isNotEmpty()) scope.launch { runCatching { AiPicks.refreshIfDue(hub, force) } }
            try { build(hub, force) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "build failed: ${e.message}"); pool(hub).value
            } finally { buildings.getValue(hub).value = false }
        }
    }

    private suspend fun build(hub: Hub, force: Boolean): Pool? {
        val now = System.currentTimeMillis()
        val trakt = App.instance.trakt.taste()
        val signals = signals(trakt, now).filter { Hubs.keepsId(hub, it.id) }
        val seeds = RecsLogic.seeds(signals)
        val min = minImdb()
        // Favourite people steer the picks (Follow / Unfollow rebuilds within the hour, like any change of taste).
        val favs = com.lekathedon.visionlite.home.People.list.value.filter { it.hub == hub.key }.associate { it.id to it.name }
        val sig = RecsLogic.signature(seeds, min) + favs.keys.sorted().joinToString(",", ",fav:") + Interests.liked().sorted().joinToString(",", ",int:")
        if (seeds.none { it.weight > 0 }) return Pool(now, sig, version = RecsLogic.VERSION).also { save(hub, it) }
        if (!force && !RecsLogic.shouldRefresh(pool(hub).value, sig, now)) return pool(hub).value

        val facts = readFacts()
        val similar = seedLookup(seeds, facts)
        val related = traktRelated(seeds.filter { it.weight > 0 }.take(TRAKT_SEEDS), facts, now)
        // Only this hub's titles can come out.
        val results = (seeds.map { Triple(Source.IMDB, it, similar[it.id].orEmpty()) } + related)
            .map { (s, seed, list) -> Triple(s, seed, list.filter { Hubs.keepsId(hub, it.id) }) }

        val names = HashSet<String>()
        trakt?.movies?.forEach { names += RecsLogic.exclusionName(it.movie.title, it.movie.year) }
        trakt?.shows?.forEach { names += RecsLogic.exclusionName(it.show.title, it.show.year) }
        val ex = RecsLogic.Exclusions(exclusionIds(trakt), names)
        write("seen.json", seenSer, ex.ids.toList())
        // For Moods' TMDB top-up: the same titles by TMDB id and by name + year.
        write("seen_tmdb.json", seenSer, buildList {
            trakt?.movies?.forEach { m -> m.movie.ids.tmdb?.let { add("tmdb:movie:$it") } }
            trakt?.shows?.forEach { sh -> sh.show.ids.tmdb?.let { add("tmdb:tv:$it") } }
            trakt?.ratings?.forEach { r -> r.movie?.ids?.tmdb?.let { add("tmdb:movie:$it") }; r.show?.ids?.tmdb?.let { add("tmdb:tv:$it") } }
            addAll(names)
        })
        var tally = RecsLogic.tally(results, ex, min)
        // Full facts for the best candidates, so taste can judge them.
        val head = tally.values.sortedByDescending { it.score }.take(RecsLogic.LOOKUPS).map { it.facts.id }
        lookUp(head.filter { facts[it]?.let { f -> f.full && f.v >= 2 } != true }, facts)
        tally = RecsLogic.tally(results.map { (s, seed, list) -> Triple(s, seed, list.map { facts[it.id] ?: it }) }, ex, min)

        val loved = signals.filter { it.kind == Kind.LOVE || it.kind == Kind.LIKED }.map { it.id }.toSet()
        // Your genres (IMDb interests): learned from these seeds, plus the ones you liked yourself (Interests page).
        val learned = seeds.mapNotNull { s -> facts[s.id]?.takeIf { it.full }?.let { it to s.weight } }
        Interests.learn(hub, learned)
        val taste = RecsLogic.taste(learned, loved, favs, Interests.weights(hub))
        val picks = RecsLogic.rank(tally, taste, facts)
        val top = picks.take(20).map { it.imdb }.toSet()
        val because = becauseRows(hub, seeds.filter { it.weight > 0 }.take(BECAUSE_ROWS), results, tally, facts, top, now)
        saveFacts(facts, now)
        return Pool(now, sig, picks, because, seeds.count { it.weight > 0 }, RecsLogic.VERSION).also {
            save(hub, it); Log.i(TAG, "${hub.label}: ${seeds.size} seeds → ${picks.size} picks, ${because.size} because rows")
        }
    }

    /**
     * "Because you watched X" (user 2026-11-23: must be accurate): every source that knows X is asked what is like it
     * (IMDb's More like this, Trakt related, TMDB recommendations, your AI), and each candidate is scored on how many
     * agree plus how much it shares with X (IMDb genres, subgenres, keywords). A title that shares no genre with X, or
     * turns it into something else (an animation, fantasy, sci-fi or horror X isn't) with one source behind it, is out.
     * Cost per rebuild: TMDB 2 + 8 small calls per seed (kept 30 days), ONE AI request for all seeds (kept 7 days), IMDb
     * facts for the new candidates.
     */
    private suspend fun becauseRows(hub: Hub, seeds: List<Seed>, results: List<Triple<Source, Seed, List<Facts>>>,
                                    tally: Map<String, RecsLogic.Tallied>, facts: MutableMap<String, Facts>, top: Set<String>, now: Long): List<Because> {
        if (seeds.isEmpty()) return emptyList()
        val cache = (read("related.json", relSer) ?: emptyMap()).filterValues { now - it.at in 0 until RELATED_MAX_AGE }.toMutableMap()
        // TMDB recommendations (with your TMDB key).
        val tmdb: Map<String, List<String>> = if (com.lekathedon.visionlite.home.Tmdb.key() == null) emptyMap() else coroutineScope {
            seeds.map { s -> async { s.id to (synchronized(cache) { cache["tmdb:${s.id}"] }?.ids
                ?: gate.withPermit { tmdbAlike(s) }?.also { synchronized(cache) { cache["tmdb:${s.id}"] = RelatedIds(it, now) } }.orEmpty()) } }
                .awaitAll().toMap()
        }
        // Your AI, one request for every seed not asked in the last 7 days.
        val aiFresh = seeds.filter { cache["ai:${it.id}"]?.takeIf { c -> now - c.at < 7 * 86_400_000L } == null }
        if (aiFresh.isNotEmpty()) runCatching { AiPicks.alike(hub, aiFresh) }.getOrNull()?.let { m ->
            if (m.isNotEmpty()) aiFresh.forEach { s -> cache["ai:${s.id}"] = RelatedIds(m[s.id].orEmpty(), now) }
        }
        write("related.json", relSer, cache)
        val ai = seeds.associate { it.id to cache["ai:${it.id}"]?.ids.orEmpty() }

        // Facts (genres, subgenres, keywords) for every candidate not known well enough yet.
        val bySeed = seeds.associate { s ->
            val imdb = results.filter { it.first == Source.IMDB && it.second.id == s.id }.flatMap { it.third }.map { it.id }
            val trakt = results.filter { it.first == Source.TRAKT && it.second.id == s.id }.flatMap { it.third }.map { it.id }
            s.id to listOf(imdb, trakt, tmdb[s.id].orEmpty(), ai[s.id].orEmpty())
        }
        lookUp(bySeed.values.flatten().flatten().distinct().filter { id -> facts[id]?.let { it.full && it.v >= 2 } != true }, facts)

        val ex = seenIds()
        val min = minImdb()
        val turns = setOf("Animation", "Fantasy", "Sci-Fi", "Horror", "Documentary", "Family", "Musical")
        return seeds.mapNotNull { seed ->
            val sf = facts[seed.id] ?: return@mapNotNull null
            val (imdb, trakt, tm, aiL) = bySeed.getValue(seed.id)
            val weights = doubleArrayOf(2.0, 1.0, 1.0, 1.5)
            val score = HashMap<String, Double>(); val votes = HashMap<String, Int>()
            listOf(imdb, trakt, tm, aiL).forEachIndexed { src, list ->
                list.distinct().forEachIndexed { rank, id ->
                    score.merge(id, weights[src] * (1.0 - rank / (list.size + 5.0)), Double::plus); votes.merge(id, 1, Int::plus)
                }
            }
            val sGenres = sf.genres.toSet(); val sInt = sf.interests.filter { Interests.name(it) != null && !Interests.isBroad(it) }.toSet()
            val sKw = sf.keywords.map { it.lowercase() }.toSet()
            val picks = score.keys.mapNotNull { id ->
                if (id == seed.id || id in ex || id in top) return@mapNotNull null
                val f = facts[id] ?: return@mapNotNull null
                if (!Hubs.keepsId(hub, id) || Adult.hidesId(id)) return@mapNotNull null
                if (min > 0f && (f.rating ?: 0f) < min) return@mapNotNull null
                val v = votes[id] ?: 0
                val shared = f.genres.count { it in sGenres }
                if (sGenres.isNotEmpty() && f.genres.isNotEmpty() && shared == 0) return@mapNotNull null
                if (v < 2 && f.genres.any { it in turns && it !in sGenres }) return@mapNotNull null
                val sim = 2.0 * shared / (sGenres.size + f.genres.size - shared).coerceAtLeast(1) +
                    0.8 * minOf(3, f.interests.count { it in sInt }) + 0.3 * minOf(5, f.keywords.count { it.lowercase() in sKw }) +
                    (if (f.tv == sf.tv) 0.4 else 0.0)
                // One source alone must also look like the seed.
                if (v < 2 && sim < 1.2) return@mapNotNull null
                val t = tally[id]
                Pick(f.id, if (f.tv) "series" else "movie", f.name, f.year, f.rating, f.poster, f.genres, seed.name, null, false, maxOf(v, t?.sources ?: 0)) to
                    (score.getValue(id) + 1.5 * (v - 1) + sim)
            }.sortedByDescending { it.second }.map { it.first }.take(BECAUSE_ITEMS)
            Because(seed.name, picks).takeIf { picks.size >= 8 }
        }
    }

    /** TMDB's recommendations for an IMDb title, as IMDb ids (find + recommendations + 8 external ids). */
    private fun tmdbAlike(s: Seed): List<String>? {
        val T = com.lekathedon.visionlite.home.Tmdb
        val f = T.get("find/${s.id}?external_source=imdb_id") ?: return null
        val (kind, id) = f.optJSONArray("tv_results")?.optJSONObject(0)?.let { "tv" to it.optInt("id") }
            ?: f.optJSONArray("movie_results")?.optJSONObject(0)?.let { "movie" to it.optInt("id") } ?: return emptyList()
        val recs = T.get("$kind/$id/recommendations")?.optJSONArray("results") ?: return emptyList()
        return (0 until minOf(8, recs.length())).mapNotNull { i ->
            val r = recs.optJSONObject(i) ?: return@mapNotNull null
            T.get("${r.optString("media_type").ifBlank { kind }}/${r.optInt("id")}/external_ids")?.optString("imdb_id")?.takeIf { it.startsWith("tt") }
        }
    }

    private fun simplePick(x: RecsLogic.Tallied) = x.facts.let {
        Pick(it.id, if (it.tv) "series" else "movie", it.name, it.year, it.rating, it.poster, it.genres, x.because, null, false, x.sources)
    }

    private fun signals(t: com.lekathedon.visionlite.trakt.TraktTaste?, now: Long): List<Signal> {
        val out = ArrayList<Signal>()
        t?.ratings?.forEach { r ->
            val (id, tv, name) = (r.movie?.let { Triple(it.ids.imdb, false, it.title) } ?: r.show?.let { Triple(it.ids.imdb, true, it.title) }) ?: return@forEach
            id ?: return@forEach
            val kind = when { r.rating >= 9 -> Kind.LOVE; r.rating >= 7 -> Kind.LIKE; r.rating in 1..4 -> Kind.NOT_FOR_ME; else -> Kind.FINISHED }
            out += Signal(id, tv, name, kind, ms(r.ratedAt))
        }
        t?.movies?.forEach { m -> m.movie.ids.imdb?.let { out += Signal(it, false, m.movie.title, if (m.plays > 1) Kind.REWATCHED else Kind.FINISHED, ms(m.lastWatchedAt)) } }
        t?.shows?.forEach { s -> s.show.ids.imdb?.let { val at = ms(s.lastWatchedAt); out += Signal(it, true, s.show.title, RecsLogic.finishedKind(true, s.plays, at, now), at, s.plays) } }
        t?.paused?.forEach { pb ->
            pb.movie?.ids?.imdb?.let { out += Signal(it, false, pb.movie.title, Kind.WATCHING, ms(pb.pausedAt)) }
            pb.show?.ids?.imdb?.let { out += Signal(it, true, pb.show.title, Kind.WATCHING, ms(pb.pausedAt)) }
        }
        Saves.all().filter { it.id.startsWith("tt") }.forEach { out += Signal(it.id.substringBefore(':'), it.type != "movie", it.name, Kind.WATCHING, 0) }
        // Your word weighs most: Love = the strongest signal there is (8), Like = a 9-10 rating's (6).
        feedback().filter { it.id.startsWith("tt") }.forEach { out += Signal(it.id, it.tv, it.name, if (it.watched) Kind.FINISHED else if (!it.like) Kind.NOT_FOR_ME else if (it.love) Kind.LIKED else Kind.LOVE, it.at) }
        return out
    }

    /** Watched, rated, paused, saved and turned down: never recommended. */
    private fun exclusionIds(t: com.lekathedon.visionlite.trakt.TraktTaste?): Set<String> = buildSet {
        t?.movies?.forEach { it.movie.ids.imdb?.let(::add) }
        t?.shows?.forEach { it.show.ids.imdb?.let(::add) }
        t?.ratings?.forEach { (it.movie?.ids?.imdb ?: it.show?.ids?.imdb)?.let(::add) }
        t?.paused?.forEach { (it.movie?.ids?.imdb ?: it.show?.ids?.imdb)?.let(::add) }
        Saves.all().forEach { add(it.id.substringBefore(':')) }
        feedback().forEach { add(it.id); it.alt?.let(::add) }
    }

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun ms(s: String) = runCatching { synchronized(iso) { iso.parse(s.take(19))!!.time } }.getOrDefault(0L)

    // ---- IMDb (keyless GraphQL) ----

    private const val LIGHT = "id titleText { text } titleType { id } releaseYear { year } ratingsSummary { aggregateRating voteCount } " +
        "primaryImage { url } titleGenres { genres { genre { text } } }"
    private const val FULL = "$LIGHT keywords(first: 15) { edges { node { keyword { text { text } } } } } " +
        "spokenLanguages { spokenLanguages { id } } countriesOfOrigin { countries { id } } certificate { rating } " +
        "directors: credits(first: 2, filter: { categories: [\"director\"] }) { edges { node { name { id nameText { text } } } } } " +
        "writers: credits(first: 2, filter: { categories: [\"writer\"] }) { edges { node { name { id nameText { text } } } } } " +
        "creators: credits(first: 2, filter: { categories: [\"creator\"] }) { edges { node { name { id nameText { text } } } } } " +
        "cast: credits(first: 5, filter: { categories: [\"actor\", \"actress\"] }) { edges { node { name { id nameText { text } } } } } " +
        "interests(first: 15) { edges { node { id } } }"
    private val KINDS = setOf("movie", "tvMovie", "tvSeries", "tvMiniSeries")

    /** Each seed's own facts and IMDb's "More like this" for it. */
    private suspend fun seedLookup(seeds: List<Seed>, facts: MutableMap<String, Facts>): Map<String, List<Facts>> = coroutineScope<Map<String, List<Facts>>> {
        val now = System.currentTimeMillis()
        seeds.chunked(SEED_BATCH).map { chunk ->
            async {
                gate.withPermit<List<Pair<String, List<Facts>>>> {
                    val q = chunk.mapIndexed { i, s -> "t$i: title(id: \"${s.id}\") { $FULL moreLikeThisTitles(first: $SIMILAR) { edges { node { $LIGHT } } } }" }
                    val data = imdb(q) ?: return@withPermit emptyList()
                    chunk.mapIndexedNotNull { i, s ->
                        val t = data.optJSONObject("t$i") ?: return@mapIndexedNotNull null
                        parse(t, true, now)?.let { synchronized(facts) { facts[it.id] = it } }
                        val list = t.optJSONObject("moreLikeThisTitles")?.optJSONArray("edges").objects()
                            .mapNotNull { e -> e.optJSONObject("node")?.let { n -> parse(n, false, now) } }
                            .map { f -> synchronized(facts) { facts[f.id]?.takeIf { it.full } ?: f.also { facts[f.id] = it } } }
                        s.id to list
                    }
                }
            }
        }.awaitAll().flatten().toMap()
    }

    /** IMDb facts (name, year, rating, poster, genres) for [ids], from the saved ones first. */
    suspend fun factsFor(ids: List<String>): Map<String, Facts> = withContext(Dispatchers.IO) {
        val have = readFacts()
        val missing = ids.filter { have[it]?.rating == null }
        if (missing.isNotEmpty()) lookUp(missing, have)
        ids.mapNotNull { id -> have[id]?.let { id to it } }.toMap()
    }

    /** What is never recommended (watched, rated, paused, saved, turned down), as last worked out. */
    fun seenIds(): Set<String> = (read("seen.json", seenSer)?.toSet() ?: emptySet()) + nope() + Saves.all().map { it.id.substringBefore(':') }

    /** Full facts for [ids], [FACT_BATCH] per request. */
    private suspend fun lookUp(ids: List<String>, facts: MutableMap<String, Facts>) = coroutineScope {
        val now = System.currentTimeMillis()
        ids.distinct().chunked(FACT_BATCH).map { chunk ->
            async {
                gate.withPermit {
                    val data = imdb(chunk.mapIndexed { i, id -> "t$i: title(id: \"$id\") { $FULL }" }) ?: return@withPermit
                    chunk.indices.forEach { i -> data.optJSONObject("t$i")?.let { parse(it, true, now) }?.let { synchronized(facts) { facts[it.id] = it } } }
                }
            }
        }.awaitAll()
    }

    private fun parse(t: JSONObject, full: Boolean, now: Long): Facts? {
        val id = t.optString("id").takeIf { it.startsWith("tt") } ?: return null
        val type = t.optJSONObject("titleType")?.optString("id") ?: return null
        if (type !in KINDS) return null
        val rs = t.optJSONObject("ratingsSummary")
        fun people(key: String) = t.optJSONObject(key)?.optJSONArray("edges").objects().mapNotNull { e ->
            e.optJSONObject("node")?.optJSONObject("name")?.let { n -> Person(n.optString("id"), n.optJSONObject("nameText")?.optString("text").orEmpty()) }
        }
        return Facts(
            id = id,
            name = t.optJSONObject("titleText")?.optString("text").orEmpty().ifBlank { return null },
            tv = type == "tvSeries" || type == "tvMiniSeries",
            year = t.optJSONObject("releaseYear")?.optInt("year")?.takeIf { it > 0 },
            rating = rs?.optDouble("aggregateRating")?.takeIf { !it.isNaN() && it > 0 }?.toFloat(),
            votes = rs?.optInt("voteCount")?.takeIf { it > 0 },
            poster = t.optJSONObject("primaryImage")?.optString("url")?.takeIf { it.isNotBlank() }?.let(::sized),
            genres = t.optJSONObject("titleGenres")?.optJSONArray("genres").objects().mapNotNull { it.optJSONObject("genre")?.optString("text") },
            keywords = if (!full) emptyList() else t.optJSONObject("keywords")?.optJSONArray("edges").objects()
                .mapNotNull { it.optJSONObject("node")?.optJSONObject("keyword")?.optJSONObject("text")?.optString("text")?.lowercase() },
            crew = if (!full) emptyList() else (people("creators") + people("directors") + people("writers")).filter { it.id.isNotBlank() }.distinctBy { it.id },
            language = t.optJSONObject("spokenLanguages")?.optJSONArray("spokenLanguages").objects().firstOrNull()?.optString("id"),
            countries = t.optJSONObject("countriesOfOrigin")?.optJSONArray("countries").objects().map { it.optString("id") },
            age = RecsLogic.ageLevel(t.optJSONObject("certificate")?.optString("rating")),
            full = full,
            at = now,
            cast = if (!full) emptyList() else people("cast").filter { it.id.isNotBlank() }.distinctBy { it.id },
            v = if (full) 2 else 0,
            interests = if (!full) emptyList() else t.optJSONObject("interests")?.optJSONArray("edges").objects().mapNotNull { it.optJSONObject("node")?.optString("id")?.takeIf { s -> s.startsWith("in") } }
        )
    }

    /** IMDb's full-size poster asked at card size (about 40 KB instead of 400 KB+). */
    private fun sized(url: String) = Regex("""\._V1_[^/]*\.jpg$""").find(url)?.let { url.replaceRange(it.range, "._V1_QL75_UX342_.jpg") } ?: url

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun imdb(aliases: List<String>): JSONObject? {
        val body = JSONObject().put("query", "query { ${aliases.joinToString(" ")} }").toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(IMDB).post(body).header("x-imdb-client-name", "imdb-web-next").header("Accept", "application/json").build()
        return runCatching { Net.http.newCall(req).execute().use { r -> if (r.isSuccessful) JSONObject(r.body!!.string()).optJSONObject("data") else null } }
            .onFailure { Log.w(TAG, "IMDb: ${it.message}") }.getOrNull()
    }

    // ---- Trakt related (only while Trakt is on) ----

    @Serializable data class RelatedIds(val ids: List<String>, val at: Long)
    private val relSer = MapSerializer(String.serializer(), RelatedIds.serializer())

    private suspend fun traktRelated(seeds: List<Seed>, facts: MutableMap<String, Facts>, now: Long): List<Triple<Source, Seed, List<Facts>>> {
        if (App.instance.trakt.connected.value == null) return emptyList()
        val cache = (read("related.json", relSer) ?: emptyMap()).filterValues { now - it.at in 0 until RELATED_MAX_AGE }.toMutableMap()
        val lists = coroutineScope {
            seeds.map { s ->
                async {
                    val key = (if (s.tv) "tv:" else "movie:") + s.id
                    val ids = synchronized(cache) { cache[key] }?.ids ?: gate.withPermit { App.instance.trakt.related(s.id, s.tv) }
                        ?.mapNotNull { it.ids.imdb }?.also { synchronized(cache) { cache[key] = RelatedIds(it, now) } } ?: return@async null
                    s to ids
                }
            }.awaitAll().filterNotNull()
        }
        write("related.json", relSer, cache)
        lookUp(lists.flatMap { it.second }.filter { facts[it] == null }, facts)
        return lists.map { (s, ids) -> Triple(Source.TRAKT, s, ids.mapNotNull { facts[it] }) }
    }

    // ---- files ----

    private val seenSer = ListSerializer(String.serializer())
    /** Moods: TMDB ids and name|year keys of what you've seen (written by the last build), plus saved / turned down names. */
    fun seenTmdb(): Set<String> = (read("seen_tmdb.json", seenSer)?.toSet() ?: emptySet()) +
        nope() + feedback().filter { it.gone }.map { RecsLogic.exclusionName(it.name, null) } + Saves.all().map { RecsLogic.exclusionName(it.name, null) }
    fun seenName(name: String, year: String?) = RecsLogic.exclusionName(name, year?.take(4)?.toIntOrNull())

    /** IMDb keywords the engine already holds for these titles (facts file, no requests). */
    fun keywordsOf(ids: List<String>): Map<String, List<String>> {
        val f = readFacts()
        return ids.mapNotNull { id -> f[id]?.keywords?.takeIf { it.isNotEmpty() }?.let { id to it } }.toMap()
    }

    /** What the last build left out, so a details page's "more like this" needs no Trakt calls. */
    private fun seen(): Set<String> = read("seen.json", seenSer)?.toSet() ?: emptySet()

    private val factsSer = ListSerializer(Facts.serializer())

    private fun readFacts(): MutableMap<String, Facts> {
        val now = System.currentTimeMillis()
        return (read("facts.json", factsSer) ?: emptyList()).filter { now - it.at in 0 until FACTS_MAX_AGE }.associateBy { it.id }.toMutableMap()
    }

    private fun saveFacts(facts: Map<String, Facts>, now: Long) =
        write("facts.json", factsSer, facts.values.filter { now - it.at < FACTS_MAX_AGE }.sortedByDescending { it.at }.take(FACTS_KEEP))

    private fun save(hub: Hub, pool: Pool) { pools.getValue(hub).value.value = pool; write(poolFile(hub), Pool.serializer(), pool) }

    private fun <T> read(name: String, ser: kotlinx.serialization.KSerializer<T>): T? =
        runCatching { Net.json.decodeFromString(ser, File(dir, name).readText()) }.getOrNull()

    private fun <T> write(name: String, ser: kotlinx.serialization.KSerializer<T>, v: T) {
        runCatching { File(dir, "$name.tmp").apply { writeText(Net.json.encodeToString(ser, v)) }.renameTo(File(dir, name)) }
    }

    /** Settings → Recommendations → Start over: forgets the picks and the cached facts (keeps More like this / Not for me). */
    fun reset() {
        (Hub.entries.map(::poolFile) + listOf("facts.json", "related.json", "seen.json")).forEach { File(dir, it).delete() }
        Hub.entries.forEach { pools.getValue(it).value.value = null }
    }
}
