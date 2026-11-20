package com.lekathedon.visionlite.anime

import com.lekathedon.visionlite.addons.MetaPreview
import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.home.Charts
import kotlinx.coroutines.Dispatchers
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

    /**
     * [type] "movie" or "series" (ignored by a format subgenre); [sort] "POPULARITY_DESC" or "SCORE_DESC"; [sub] a
     * subgenre; [from]..[to] years (a decade); [skip] = cards already shown.
     */
    suspend fun page(type: String, sort: String, sub: Sub? = null, from: Int? = null, to: Int? = null, skip: Int = 0): List<MetaPreview> = withContext(Dispatchers.IO) {
        if (skip % PER_PAGE != 0) return@withContext emptyList()
        val vars = JSONObject().put("page", skip / PER_PAGE + 1).put("sort", JSONArray().put(sort))
            .put("format", JSONArray().apply { when { sub?.format != null -> put(sub.format); type == "movie" -> put("MOVIE"); else -> { put("TV"); put("TV_SHORT"); put("ONA") } } })
        sub?.genres?.let { vars.put("genres", JSONArray(it)) }
        // A tag must matter to the show (rank 60+), not be a passing scene.
        sub?.tag?.let { vars.put("tag", it).put("rank", 60) }
        sub?.source?.let { vars.put("source", it) }
        if (from != null && to != null) vars.put("after", from * 10000 - 1).put("before", (to + 1) * 10000)
        runCatching {
            val body = JSONObject().put("query", Q).put("variables", vars).toString().toRequestBody("application/json".toMediaType())
            Net.http.newCall(Request.Builder().url("https://graphql.anilist.co").post(body).header("Accept", "application/json").build()).execute().use { r ->
                if (!r.isSuccessful) return@use emptyList()
                JSONObject(r.body!!.string()).optJSONObject("data")?.let { Charts.ani(it, "list") }.orEmpty()
            }
        }.getOrDefault(emptyList())
    }
}
