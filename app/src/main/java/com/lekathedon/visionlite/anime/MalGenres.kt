package com.lekathedon.visionlite.anime

import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.addons.MetaPreview
import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.home.Charts
import com.lekathedon.visionlite.recs.Interests
import com.lekathedon.visionlite.recs.Recs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The Anime hub's genres are MyAnimeList's own (user 2026-11-23: "anime has its own genres, no need to follow films and
 * TV genres"): its Genres, Themes (Isekai, Iyashikei, Mecha…) and Demographics (Shounen, Seinen…), not IMDb's.
 * Learned from the anime you watch: each one's IMDb id is matched to MAL through the Fribb anime list (MAL ↔ IMDb ↔
 * TMDB, the same weekly download the hubs use) and MAL's genres are added up by how much you watched it.
 * Rows come from MAL's own API (official, Lite's MAL app id, no sign-in): its 3,000 most popular anime with their
 * genres, kept 7 days, filtered by the genre; seen titles left out by their IMDb / TMDB id.
 * Cost: 6 MAL requests a week (~2 MB, only once a genre row or an anime Genres / Decades page shows), up to 25 small ones per engine build for anime
 * outside that list (kept for good); ~900 KB on disk.
 */
object MalGenres {
    // MAL's lists (myanimelist.net/anime.php, Genres / Themes / Demographics); the explicit ones left out.
    private val GENRES = listOf("Action", "Adventure", "Avant Garde", "Boys Love", "Comedy", "Drama", "Fantasy", "Girls Love", "Gourmet",
        "Horror", "Mystery", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Suspense")
    private val THEMES = listOf("Adult Cast", "Anthropomorphic", "CGDCT", "Childcare", "Combat Sports", "Crossdressing", "Delinquents", "Detective",
        "Educational", "Gag Humor", "Gore", "Harem", "High Stakes Game", "Historical", "Idols (Female)", "Idols (Male)", "Isekai", "Iyashikei",
        "Love Polygon", "Love Status Quo", "Magical Sex Shift", "Mahou Shoujo", "Martial Arts", "Mecha", "Medical", "Military", "Music", "Mythology",
        "Organized Crime", "Otaku Culture", "Parody", "Performing Arts", "Pets", "Psychological", "Racing", "Reincarnation", "Reverse Harem",
        "Samurai", "School", "Showbiz", "Space", "Strategy Game", "Super Power", "Survival", "Team Sports", "Time Travel", "Urban Fantasy",
        "Vampire", "Video Game", "Villainess", "Visual Arts", "Workplace")
    private val DEMOGRAPHICS = listOf("Shounen", "Seinen", "Shoujo", "Josei", "Kids")
    /** For the Interests page (Anime hub). */
    val GROUPS: List<Pair<String, List<String>>> = listOf("Genres" to GENRES, "Themes" to THEMES, "Demographics" to DEMOGRAPHICS)
        .map { (g, l) -> g to l.map { id(it) } }
    private val KNOWN = (GENRES + THEMES + DEMOGRAPHICS).toSet()

    fun id(name: String) = "mg:$name"
    fun isMal(id: String) = id.startsWith("mg:")
    fun name(id: String): String? = id.removePrefix("mg:").takeIf { isMal(id) && it in KNOWN }

    /** A broad genre (Action) or a demographic (Shounen) is on half the anime you watch: half weight, so themes win. */
    private fun factor(g: String) = if (g in THEMES) 1.0 else 0.5

    // ---- MAL ↔ IMDb / TMDB (from the Fribb list): "mal imdb|- tv:N|movie:N|-" ----
    private class Ids(val mal: Int, val imdb: String?, val tmdb: String?)
    @Volatile private var ids: Map<Int, Ids>? = null
    @Volatile private var byImdb: Map<String, List<Int>>? = null
    private suspend fun ids(): Map<Int, Ids> {
        ids?.let { return it }
        if (!AnimeIds.malFile.isFile) AnimeIds.refreshIfDue(force = true)
        val m = runCatching {
            AnimeIds.malFile.readLines().mapNotNull { l ->
                val p = l.split(' '); val mal = p.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                Ids(mal, p.getOrNull(1)?.takeIf { it.startsWith("tt") }, p.getOrNull(2)?.takeIf { it != "-" })
            }.associateBy { it.mal }
        }.getOrDefault(emptyMap())
        ids = m; byImdb = m.values.filter { it.imdb != null }.groupBy({ it.imdb!! }, { it.mal })
        return m
    }

    // ---- the pool: MAL's 1,500 most popular anime with their genres, 7 days ----
    private const val KEEP_MS = 7 * 24 * 3600_000L
    private val poolSer = kotlinx.serialization.builtins.ListSerializer(MetaPreview.serializer())
    private val poolFile get() = File(App.instance.filesDir, "mal_pool.json")
    @Volatile private var pool: List<MetaPreview>? = null
    private val poolLock = kotlinx.coroutines.sync.Mutex()
    private suspend fun pool(): List<MetaPreview> = withContext(Dispatchers.IO) {
        pool?.takeIf { System.currentTimeMillis() - poolFile.lastModified() < KEEP_MS }?.let { return@withContext it }
        poolLock.lock()
        try {
            pool?.takeIf { System.currentTimeMillis() - poolFile.lastModified() < KEEP_MS }?.let { return@withContext it }
            val disk = runCatching { Net.json.decodeFromString(poolSer, poolFile.readText()) }.getOrNull()
            if (disk != null && System.currentTimeMillis() - poolFile.lastModified() < KEEP_MS) return@withContext disk.also { pool = it }
            val fresh = runCatching {
                (0 until 6).flatMap { pg ->
                    Charts.mal("anime/ranking?ranking_type=bypopularity&limit=500&offset=${pg * 500}" +
                        "&fields=main_picture,alternative_titles,mean,media_type,start_season,genres&nsfw=false")
                }
            }.getOrDefault(emptyList())
            if (fresh.size >= 2000) {
                runCatching { File(poolFile.path + ".tmp").apply { writeText(Net.json.encodeToString(poolSer, fresh)) }.renameTo(poolFile) }
                fresh.also { pool = it }
            } else (disk ?: fresh).also { pool = it }
        } finally { poolLock.unlock() }
    }

    // ---- genres of anime outside the pool, kept for good ----
    private val extraFile get() = File(App.instance.filesDir, "mal_genres.json")
    private fun extra(): MutableMap<String, List<String>> = runCatching {
        val o = JSONObject(extraFile.readText())
        o.keys().asSequence().associateWith { k -> o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty() }.toMutableMap()
    }.getOrDefault(HashMap())

    /**
     * From the engine's anime seeds (IMDb id, weight): each MAL genre's weight and how many titles carry it, saved as
     * the Anime hub's learned interests (the Interests page and the "Because you like" rows read them).
     */
    suspend fun learn(seeds: List<Pair<String, Double>>) = withContext(Dispatchers.IO) {
        ids()
        val map = byImdb.orEmpty()
        val poolGenres = pool().associate { it.id.removePrefix("mal:").toIntOrNull() to it.genres }
        val extra = extra(); var asked = 0; var added = false
        fun genresOf(mal: Int): List<String>? = poolGenres[mal] ?: extra["$mal"] ?: if (asked >= 25) null else {
            asked++
            runCatching {
                val o = JSONObject(Net.http.newCall(okhttp3.Request.Builder().url("https://api.myanimelist.net/v2/anime/$mal?fields=genres")
                    .header("X-MAL-CLIENT-ID", com.lekathedon.visionlite.BuildConfig.MAL_CLIENT_ID).build()).execute().use { it.body?.string().orEmpty() })
                o.optJSONArray("genres")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") } }
            }.getOrNull()?.also { extra["$mal"] = it; added = true }
        }
        val score = HashMap<String, Double>(); val count = HashMap<String, Int>()
        seeds.sortedByDescending { kotlin.math.abs(it.second) }.forEach { (imdb, w) ->
            // Seasons share one IMDb id: their MAL genres together.
            val gs = map[imdb.substringBefore(':')].orEmpty().flatMap { genresOf(it).orEmpty() }.filter { it in KNOWN }.toSet()
            gs.forEach { g ->
                val k = id(g)
                if (w > 0) { score.merge(k, w * factor(g), Double::plus); count.merge(k, 1, Int::plus) } else score.merge(k, w * 0.5 * factor(g), Double::plus)
            }
        }
        if (added) runCatching { extraFile.writeText(JSONObject(extra.mapValues { JSONArray(it.value) }).toString()) }
        if (score.isEmpty()) return@withContext
        Interests.store(Hub.ANIME, score.map { (k, s) -> Interests.Learned(k, s, count[k] ?: 0) }.filter { it.score > 0 && it.titles >= 2 }
            .sortedByDescending { it.score }.take(40))
    }

    /**
     * A genre's row: MAL's popular anime with it (MAL score 7+), nothing you've watched, saved or turned down; the
     * better rated first, shuffled a little every 12 hours. Cards carry the IMDb id when the list knows it.
     */
    suspend fun row(id: String, at: Long = System.currentTimeMillis()): List<MetaPreview> = withContext(Dispatchers.IO) {
        val g = name(id) ?: return@withContext emptyList()
        val ids = ids()
        val seen = Recs.seenIds(); val seenTmdb = Recs.seenTmdb()
        val r = java.util.Random(at / (12 * 3600_000L) + id.hashCode())
        val all = pool()
        all.withIndex().filter { (_, m) -> m.genres.any { it.equals(g, true) } && (m.imdbRating?.toDoubleOrNull() ?: 0.0) >= 7.0 }
            .mapNotNull { (i, m) ->
                val x = m.id.removePrefix("mal:").toIntOrNull()?.let { ids[it] }
                if (x?.imdb != null && x.imdb in seen) return@mapNotNull null
                if (x?.tmdb != null && "tmdb:${x.tmdb}" in seenTmdb) return@mapNotNull null
                if (Recs.seenName(m.name, m.releaseInfo) in seenTmdb) return@mapNotNull null
                val card = if (x?.imdb != null) m.copy(id = x.imdb) else m
                card to (m.imdbRating!!.toDouble() + 1.0 * (1 - i.toDouble() / all.size) + r.nextDouble() * 0.6)
            }
            // A show's seasons share an IMDb id: one card each.
            .distinctBy { it.first.id }.sortedByDescending { it.second }.map { it.first }.take(30)
    }

    /** All MAL names: genres, themes, demographics. */
    val ALL: List<String> get() = GENRES + THEMES + DEMOGRAPHICS
    /**
     * The Anime hub's Genres tiles: MAL's main genres only (user 2026-11-24: the row was a mix of genres and subgenres).
     * Themes and demographics stay for "Because you like", inside each genre's page and on Build your own row.
     */
    val MAIN: List<String> get() = GENRES
    /** MAL's themes and demographics (the anime subgenres). */
    val SUBS: List<String> get() = THEMES + DEMOGRAPHICS
    val THEME_NAMES: List<String> get() = THEMES
    val DEMOGRAPHIC_NAMES: List<String> get() = DEMOGRAPHICS
    fun isSub(name: String) = name in THEMES || name in DEMOGRAPHICS

    /** How many pool titles each subgenre has within [genre] (for its page's rows; no request after the weekly pool). */
    suspend fun subsWithin(genre: String, min: Int = 8): List<String> {
        val within = pool().filter { m -> m.genres.any { it.equals(genre, true) } }
        return SUBS.map { s -> s to within.count { m -> m.genres.any { it.equals(s, true) } } }
            .filter { it.second >= min }.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * The Anime hub's Genres / Decades pages (user 2026-11-24: they took ages: AniList now allows ~24 rows a minute and a
     * decade page has 50+). Filled from the pool on the box, so a page opens at once and makes no request after the
     * weekly 6. [sort]: "popular", "top" or "latest"; [type] "movie" / "series" / null for both; [from]..[to] years.
     */
    suspend fun browse(genre: String?, type: String?, sort: String, from: Int? = null, to: Int? = null, skip: Int = 0): List<MetaPreview> =
        browseAll(listOfNotNull(genre), type, sort, from, to, skip)

    /** As [browse], with every one of [genres] on the title (Action + Isekai + Shounen). */
    suspend fun browseAll(genres: List<String>, type: String?, sort: String, from: Int? = null, to: Int? = null, skip: Int = 0): List<MetaPreview> {
        val all = pool()
        val list = all.filter { m ->
            genres.all { g -> m.genres.any { it.equals(g, true) } } && (type == null || m.type == type) &&
                (from == null || m.releaseInfo?.take(4)?.toIntOrNull()?.let { it in from..(to ?: from + 9) } == true)
        }
        val sorted = when (sort) {
            "top" -> list.sortedByDescending { it.imdbRating?.toDoubleOrNull() ?: 0.0 }
            "latest" -> list.sortedByDescending { it.releaseInfo?.take(4)?.toIntOrNull() ?: 0 }
            else -> list
        }
        return sorted.drop(skip).take(30)
    }

    /** A tile picture for a MAL genre from the pool: its best-rated title not already behind another tile. */
    suspend fun art(names: List<String>, used: MutableSet<String>): Map<String, String> {
        val byScore = pool().sortedByDescending { it.imdbRating?.toDoubleOrNull() ?: 0.0 }
        return names.mapNotNull { n ->
            val l = byScore.filter { m -> m.poster != null && m.genres.any { it.equals(n, true) } }
            (l.firstOrNull { used.add(it.id) } ?: l.firstOrNull())?.let { n to (it.background ?: it.poster!!) }
        }.toMap()
    }
}
