package com.lekathedon.visionlite.browse

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.addons.MetaPreview
import com.lekathedon.visionlite.home.Charts
import com.lekathedon.visionlite.ui.DisplayText
import com.lekathedon.visionlite.ui.Eyebrow
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.skinPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * A production company (opened from a title's Production button): its popular films, popular shows, best rated and
 * newest work, from IMDb's keyless search (production credits only). One request per page opened, kept for the session.
 */
object Companies {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<Pair<String, List<MetaPreview>>>>()
    private const val F = "edges { node { title { id titleText { text } titleType { id } releaseYear { year } ratingsSummary { aggregateRating } primaryImage { url } titleGenres { genres { genre { text } } } } } }"

    suspend fun rows(id: String): List<Pair<String, List<MetaPreview>>> = cache[id] ?: withContext(Dispatchers.IO) {
        val c = "creditedCompanyConstraint: {anyCompanyIds: [\"$id\"], anyCompanyCategories: [\"production\"]}"
        val all = "titleTypeConstraint: {anyTitleTypeIds: [\"movie\", \"tvSeries\", \"tvMiniSeries\"]}"
        val today = java.time.LocalDate.now().toString()
        val q = "{ films: advancedTitleSearch(first: 40, constraints: {$c, titleTypeConstraint: {anyTitleTypeIds: [\"movie\"]}}, sort: {sortBy: POPULARITY, sortOrder: ASC}) { $F } " +
            "shows: advancedTitleSearch(first: 40, constraints: {$c, titleTypeConstraint: {anyTitleTypeIds: [\"tvSeries\", \"tvMiniSeries\"]}}, sort: {sortBy: POPULARITY, sortOrder: ASC}) { $F } " +
            "top: advancedTitleSearch(first: 40, constraints: {$c, $all, userRatingsConstraint: {ratingsCountRange: {min: 3000}}}, sort: {sortBy: USER_RATING, sortOrder: DESC}) { $F } " +
            "newest: advancedTitleSearch(first: 40, constraints: {$c, $all, releaseDateConstraint: {releaseDateRange: {end: \"$today\"}}}, sort: {sortBy: RELEASE_DATE, sortOrder: DESC}) { $F } }"
        val d = runCatching { Charts.post(Charts.IMDB_URL, JSONObject().put("query", q)) }.getOrNull() ?: return@withContext emptyList()
        fun list(alias: String): List<MetaPreview> {
            val edges = d.optJSONObject(alias)?.optJSONArray("edges") ?: return emptyList()
            return (0 until edges.length()).flatMap { i ->
                val t = edges.optJSONObject(i)?.optJSONObject("node")?.optJSONObject("title") ?: return@flatMap emptyList()
                val type = if (t.optJSONObject("titleType")?.optString("id") == "movie") "movie" else "series"
                Charts.imdb(JSONObject().put("edges", JSONArray().put(JSONObject().put("node", t))), type)
            }
        }
        listOf("Popular films" to list("films"), "Popular shows" to list("shows"), "Best rated" to list("top"), "Newest" to list("newest"))
            .filter { it.second.isNotEmpty() }.also { if (it.isNotEmpty()) cache[id] = it }
    }
}

@Composable
fun CompanyPage(nav: Nav, id: String, name: String) {
    var rows by remember(id) { mutableStateOf<List<Pair<String, List<MetaPreview>>>?>(null) }
    LaunchedEffect(id) { rows = Companies.rows(id) }
    val subOf: (MetaPreview) -> String = { listOfNotNull(it.releaseInfo, it.imdbRating?.let { r -> "★ $r" }).joinToString(" · ") }
    Column(Modifier.fillMaxSize().skinPage(LocalLook.current.skin).padding(start = 40.dp, end = 24.dp, top = 24.dp)) {
        Eyebrow("Production")
        DisplayText(name, 34.sp, Modifier.padding(top = 4.dp, bottom = 6.dp), maxLines = 1)
        val r = rows
        when {
            r == null -> Text("Loading…", color = Palette.TextDim, fontSize = 14.sp)
            r.isEmpty() -> Text("Nothing found", color = Palette.TextDim, fontSize = 14.sp)
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 60.dp)) {
                r.forEach { (title, items) -> item(key = title) { Rail(title, items, nav, subOf = subOf, key = "co-$id-$title") } }
            }
        }
    }
}
