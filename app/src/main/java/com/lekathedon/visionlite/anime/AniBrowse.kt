package com.lekathedon.visionlite.anime

import com.lekathedon.visionlite.addons.MetaPreview
import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.home.Charts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.serialization.builtins.serializer
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Anime hub's Genres and Decades: AniList (keyless GraphQL, ~30 KB a page), so every card is anime. Cards carry
 * AniList ids and open through their IMDb id (one small lookup, as the AniList chart rows do). Asked only when a page is open.
 */
object AniBrowse {
    /** One of WuPlay's anime subgenres (its Anime hub menu, 52 of them, in its order), as AniList filters. */
    data class Sub(val name: String, val genres: List<String>? = null, val tag: String? = null, val source: String? = null, val format: String? = null)

    /** WuPlay 0.9.4's Anime subgenres (from Vision's browse_hubs.json). A [Sub.format] one (OVA, ONA) is one rail, not movies/shows. */
    val subgenres = listOf(
        Sub("Based on Manga", source = "MANGA"),
        Sub("Shounen", tag = "Shounen"),
        Sub("Slice of Life", genres = listOf("Slice of Life")),
        Sub("Magic", tag = "Magic"),
        Sub("Based on Light Novel", source = "LIGHT_NOVEL"),
        Sub("Supernatural", genres = listOf("Supernatural")),
        Sub("School", tag = "School"),
        Sub("Seinen", tag = "Seinen"),
        Sub("Ecchi", genres = listOf("Ecchi")),
        Sub("Harem", tag = "Male Harem"),
        Sub("Super Power", tag = "Super Power"),
        Sub("Friendship", tag = "Found Family"),
        Sub("Demon", tag = "Demons"),
        Sub("Isekai", tag = "Isekai"),
        Sub("Coming-of-age", tag = "Coming of Age"),
        Sub("Tragedy", tag = "Tragedy"),
        Sub("Mecha", genres = listOf("Mecha")),
        Sub("Martial Arts", tag = "Martial Arts"),
        Sub("Fantasy World", genres = listOf("Fantasy")),
        Sub("Gore", tag = "Gore"),
        Sub("Detective", tag = "Detective"),
        Sub("Survival", tag = "Survival"),
        Sub("Military", tag = "Military"),
        Sub("Rom-com", genres = listOf("Romance", "Comedy")),
        Sub("Monster", tag = "Kaiju"),
        Sub("Shoujo", tag = "Shoujo"),
        Sub("Dark Fantasy", genres = listOf("Fantasy"), tag = "Tragedy"),
        Sub("Dystopia", tag = "Dystopian"),
        Sub("Time Travel", tag = "Time Manipulation"),
        Sub("Post-apocalyptic Future", tag = "Post-Apocalyptic"),
        Sub("Reincarnation", tag = "Reincarnation"),
        Sub("Cyberpunk", tag = "Cyberpunk"),
        Sub("Alien", tag = "Aliens"),
        Sub("Parody", tag = "Parody"),
        Sub("Conspiracy", tag = "Conspiracy"),
        Sub("Parallel World", tag = "Alternate Universe"),
        Sub("God", tag = "Gods"),
        Sub("Vampire", tag = "Vampire"),
        Sub("Urban Fantasy", tag = "Urban Fantasy"),
        Sub("School Club", tag = "School Club"),
        Sub("Robot", tag = "Robots"),
        Sub("Swordplay", tag = "Swordplay"),
        Sub("Dragon", tag = "Dragons"),
        Sub("OVA", format = "OVA"),
        Sub("ONA", format = "ONA"),
        Sub("Witch", tag = "Witch"),
        Sub("Travel", tag = "Travel"),
        Sub("Pirate", tag = "Pirates"),
        Sub("Cyborg", tag = "Cyborg"),
        Sub("Female Protagonist", tag = "Female Protagonist"),
        Sub("Battle", tag = "Battle Royale"),
        Sub("Sports", genres = listOf("Sports"))
    )

    private const val PER_PAGE = 30
    private const val Q = "query (\$page: Int, \$genres: [String], \$tag: String, \$rank: Int, \$source: MediaSource, \$year: Int, " +
        "\$after: FuzzyDateInt, \$before: FuzzyDateInt, \$format: [MediaFormat], \$sort: [MediaSort]) { " +
        "list: Page(page: \$page, perPage: $PER_PAGE) { media(type: ANIME, isAdult: false, genre_in: \$genres, tag: \$tag, minimumTagRank: \$rank, " +
        "source: \$source, seasonYear: \$year, startDate_greater: \$after, startDate_lesser: \$before, format_in: \$format, sort: \$sort) { " +
        "id format title { english userPreferred } coverImage { large extraLarge } bannerImage seasonYear averageScore genres } } }"

    /** One rail's first-page ask: [type] "movie"/"series", [sort] an AniList sort, [sub] a subgenre, [from]..[to] years. */
    data class Ask(val type: String, val sort: String, val sub: Sub? = null, val from: Int? = null, val to: Int? = null) {
        val key get() = "$type|$sort|${sub?.name}|$from|$to"
    }

    // First pages, kept 7 days on disk (user 2026-11-23: genre and decade pages took ages; AniList allows 30 requests a
    // minute and a decade page has 54 rails, each its own request). Later pages are asked one by one as you scroll.
    private const val KEEP_MS = 7 * 24 * 3600_000L
    @kotlinx.serialization.Serializable private data class Kept(val at: Long, val items: List<MetaPreview>)
    private val keptSer = kotlinx.serialization.builtins.MapSerializer(String.serializer(), Kept.serializer())
    private val keptFile get() = java.io.File(com.lekathedon.visionlite.App.instance.filesDir, "anime_pages.json")
    @Volatile private var kept: MutableMap<String, Kept>? = null
    private val keptLock = Any()
    private fun keptMap(): MutableMap<String, Kept> = kept ?: synchronized(keptLock) {
        kept ?: (runCatching { Net.json.decodeFromString(keptSer, keptFile.readText()) }.getOrNull().orEmpty()
            .filterValues { System.currentTimeMillis() - it.at < KEEP_MS }.toMutableMap()).also { kept = it }
    }
    private fun keep(add: Map<String, List<MetaPreview>>) = synchronized(keptLock) {
        val m = keptMap(); val now = System.currentTimeMillis()
        add.forEach { (k, v) -> if (v.isNotEmpty()) m[k] = Kept(now, v) }
        runCatching { java.io.File(keptFile.path + ".tmp").apply { writeText(Net.json.encodeToString(keptSer, m)) }.renameTo(keptFile) }
    }
    private val inflight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Unit>>()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    private fun args(a: Ask, page: Int): String {
        fun q(v: String) = JSONObject.quote(v)
        val formats = a.sub?.format ?: if (a.type == "movie") "MOVIE" else "TV, TV_SHORT, ONA"
        return buildString {
            append("page: $page, perPage: $PER_PAGE) { media(type: ANIME, isAdult: false, sort: [${a.sort}], format_in: [$formats]")
            a.sub?.genres?.let { g -> append(", genre_in: [${g.joinToString { q(it) }}]") }
            // A tag must matter to the show (rank 60+), not be a passing scene.
            a.sub?.tag?.let { append(", tag: ${q(it)}, minimumTagRank: 60") }
            a.sub?.source?.let { append(", source: $it") }
            if (a.from != null && a.to != null) append(", startDate_greater: ${a.from * 10000 - 1}, startDate_lesser: ${(a.to + 1) * 10000}")
            append(") { id format title { english userPreferred } coverImage { large extraLarge } bannerImage seasonYear averageScore genres } }")
        }
    }

    private fun post(query: String): JSONObject? {
        val body = JSONObject().put("query", query).toString().toRequestBody("application/json".toMediaType())
        return Net.http.newCall(Request.Builder().url("https://graphql.anilist.co").post(body).header("Accept", "application/json").build()).execute().use { r ->
            if (!r.isSuccessful) null else JSONObject(r.body!!.string()).optJSONObject("data")
        }
    }

    /**
     * A page's rails, first pages only, in a few requests (8 rails each, aliases) instead of one each; kept 7 days.
     * Called when a genre / decade page opens; the rails then take theirs from here.
     */
    fun warm(asks: List<Ask>) {
        val todo = asks.distinctBy { it.key }.filter { keptMap()[it.key] == null && inflight[it.key]?.isActive != true }
        todo.chunked(8).forEach { chunk ->
            val job = scope.async {
                runCatching {
                    val d = post("{ " + chunk.mapIndexed { i, a -> "t$i: Page(${args(a, 1)}" }.joinToString(" ") + " }") ?: return@runCatching
                    keep(chunk.indices.associate { i -> chunk[i].key to Charts.ani(d, "t$i") })
                }
                Unit
            }
            chunk.forEach { inflight[it.key] = job }
        }
    }

    suspend fun page(type: String, sort: String, sub: Sub? = null, from: Int? = null, to: Int? = null, skip: Int = 0): List<MetaPreview> = withContext(Dispatchers.IO) {
        if (skip % PER_PAGE != 0) return@withContext emptyList()
        val a = Ask(type, sort, sub, from, to)
        if (skip == 0) {
            keptMap()[a.key]?.let { return@withContext it.items }
            inflight[a.key]?.let { runCatching { it.await() }; keptMap()[a.key]?.let { k -> return@withContext k.items } }
        }
        runCatching { post("{ list: Page(${args(a, skip / PER_PAGE + 1)} }")?.let { Charts.ani(it, "list") }.orEmpty() }
            .getOrDefault(emptyList()).also { if (skip == 0) keep(mapOf(a.key to it)) }
    }

    // ---- tile pictures: each subgenre's / decade's highest-rated anime (AniList score, 20k+ members), no repeats ----

    private val _art = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    /** Tile id ("genre:anime:Name" / "decade:anime:1990") to its picture (a wide AniList banner). */
    val art: kotlinx.coroutines.flow.StateFlow<Map<String, String>> get() = _art
    private val artLock = kotlinx.coroutines.sync.Mutex()
    private val artFile get() = java.io.File(com.lekathedon.visionlite.App.instance.filesDir, "anime_art.json")
    private const val ART_FRESH_MS = 7 * 24 * 3600_000L

    /**
     * Loads the Anime hub's tile pictures: disk first, asked again weekly. Cost: ~6 AniList requests (10 tiles each,
     * 8 titles a tile, ~15 KB each), 2.5 s apart for AniList's rate limit. Keyless.
     */
    suspend fun loadArt() = withContext(Dispatchers.IO) {
        artLock.lock()
        try {
            val disk = runCatching { JSONObject(artFile.readText()) }.getOrNull()
            var have = disk?.optJSONObject("art")?.let { a -> a.keys().asSequence().associateWith { a.getString(it) } }.orEmpty()
            if (have.isNotEmpty()) _art.value = have
            // Genre tiles are MAL's names now: their pictures come from the MAL pool (no AniList); decades still ask AniList.
            val malTiles = com.lekathedon.visionlite.browse.BrowseTiles.genres(true).map { it.first }
            if (malTiles.any { it !in have }) runCatching {
                val m = MalGenres.art(malTiles.filter { it !in have }.map { it.removePrefix("genre:anime:") }, HashSet(have.values))
                    .mapKeys { "genre:anime:${it.key}" }
                if (m.isNotEmpty()) { val all = have + m; _art.value = all; have = all
                    artFile.writeText(JSONObject().put("at", disk?.optLong("at") ?: 0L).put("art", JSONObject(all)).toString()) }
            }
            val tiles = com.lekathedon.visionlite.browse.BrowseTiles.decades(true).map { it.first }
            if (have.keys.containsAll(tiles) && System.currentTimeMillis() - (disk?.optLong("at") ?: 0L) < ART_FRESH_MS) return@withContext
            fun q(v: String) = JSONObject.quote(v)
            fun filter(id: String): String? {
                if (id.startsWith("decade:anime:")) {
                    val y = id.removePrefix("decade:anime:").toIntOrNull() ?: return null
                    return "startDate_greater: ${y * 10000 - 1}, startDate_lesser: ${(y + 10) * 10000}"
                }
                val sub = subgenres.firstOrNull { "genre:anime:${it.name}" == id } ?: return null
                return listOfNotNull(sub.genres?.let { g -> "genre_in: [${g.joinToString(",") { q(it) }}]" },
                    sub.tag?.let { "tag: ${q(it)}, minimumTagRank: 60" }, sub.source?.let { "source: $it" },
                    sub.format?.let { "format_in: [$it]" }).joinToString(", ")
            }
            val found = LinkedHashMap<String, List<MetaPreview>>()
            tiles.chunked(10).forEachIndexed { n, chunk ->
                if (n > 0) kotlinx.coroutines.delay(2500)
                val parts = chunk.mapIndexedNotNull { i, id -> filter(id)?.let { f ->
                    "t$i: Page(perPage: ${if (id.startsWith("decade:")) 30 else 8}) { media(type: ANIME, isAdult: false, sort: [SCORE_DESC], popularity_greater: 20000${if (f.isEmpty()) "" else ", $f"}) " +
                        "{ id format title { english userPreferred } coverImage { large extraLarge } bannerImage seasonYear averageScore genres } }" } }
                val body = JSONObject().put("query", "{ ${parts.joinToString(" ")} }").toString().toRequestBody("application/json".toMediaType())
                val d = runCatching {
                    Net.http.newCall(Request.Builder().url("https://graphql.anilist.co").post(body).header("Accept", "application/json").build()).execute()
                        .use { r -> if (r.isSuccessful) JSONObject(r.body!!.string()).optJSONObject("data") else null }
                }.getOrNull() ?: return@forEachIndexed
                chunk.forEachIndexed { i, id -> found[id] = Charts.ani(d, "t$i") }
            }
            // In tile order: a title already behind a tile goes to the next in line (a wide banner, else its cover).
            val used = HashSet<String>()
            val fresh = HashMap(have)
            tiles.forEach { id ->
                val list = found[id] ?: return@forEach
                // The 2010s / 2020s top titles are all behind genre tiles already: their decade looks further down
                // (30 asked), and never stays blank (the best one is reused when every one is taken).
                (list.firstOrNull { (it.background ?: it.poster) != null && used.add(it.id) } ?: list.firstOrNull { (it.background ?: it.poster) != null })
                    ?.let { fresh[id] = it.background ?: it.poster!! }
            }
            _art.value = fresh
            if (found.isNotEmpty()) runCatching { artFile.writeText(JSONObject().put("at", System.currentTimeMillis()).put("art", JSONObject(fresh as Map<*, *>)).toString()) }
        } finally { artLock.unlock() }
    }
}
