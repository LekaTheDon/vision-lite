package com.lekathedon.visionlite.anime

import com.lekathedon.visionlite.addons.MetaPreview
import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.home.Charts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * The Anime hub's Genres and Decades: AniList (keyless GraphQL, ~30 KB a page), so every card is anime. Cards carry
 * AniList ids and open through their IMDb id (one small lookup, as the AniList chart rows do). Asked only when a page is open.
 */
object AniBrowse {
    /** AniList's genres (adult ones left out). */
    val genres = listOf("Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror", "Mahou Shoujo", "Mecha", "Music",
        "Mystery", "Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller")

    private const val PER_PAGE = 30
    private const val Q = "query (\$page: Int, \$genre: String, \$year: Int, \$format: [MediaFormat], \$sort: [MediaSort]) { " +
        "list: Page(page: \$page, perPage: $PER_PAGE) { media(type: ANIME, isAdult: false, genre: \$genre, seasonYear: \$year, format_in: \$format, sort: \$sort) { " +
        "id format title { english userPreferred } coverImage { large extraLarge } bannerImage seasonYear averageScore genres } } }"

    /** [type] "movie" or "series"; [sort] "POPULARITY_DESC" or "SCORE_DESC"; [skip] = cards already shown. */
    suspend fun page(type: String, sort: String, genre: String? = null, year: Int? = null, skip: Int = 0): List<MetaPreview> = withContext(Dispatchers.IO) {
        if (skip % PER_PAGE != 0) return@withContext emptyList()
        val vars = JSONObject().put("page", skip / PER_PAGE + 1).put("sort", org.json.JSONArray().put(sort))
            .put("format", org.json.JSONArray().apply { if (type == "movie") put("MOVIE") else { put("TV"); put("TV_SHORT"); put("ONA") } })
        genre?.let { vars.put("genre", it) }
        year?.let { vars.put("year", it) }
        runCatching {
            val body = JSONObject().put("query", Q).put("variables", vars).toString().toRequestBody("application/json".toMediaType())
            Net.http.newCall(Request.Builder().url("https://graphql.anilist.co").post(body).header("Accept", "application/json").build()).execute().use { r ->
                if (!r.isSuccessful) return@use emptyList()
                JSONObject(r.body!!.string()).optJSONObject("data")?.let { Charts.ani(it, "list") }.orEmpty()
            }
        }.getOrDefault(emptyList())
    }
}
