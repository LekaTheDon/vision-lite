package com.lekathedon.visionlite.detail

import android.util.Log
import com.lekathedon.visionlite.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The details page's IMDb parts, from the GraphQL API imdb.com itself uses (Vision's ImdbRelatedService,
 * ImdbEpisodesService and ImdbReviewsService, same queries): keyless, not public, can change at any time.
 *
 * Cost per details page: ONE request for the title (rating + votes, Metacritic, certificate, parental guide, cast with
 * photos, directors / creators, seasons, Sequel/Prequel + Connections, More like this); a series adds its episode list
 * (250 per request); reviews only when the Reviews row is reached. All kept 6 h in memory (30 min for reviews).
 */
object Imdb {
    private const val TAG = "LiteImdb"
    private const val ENDPOINT = "https://api.graphql.imdb.com/"
    private const val CACHE_MS = 6 * 3600_000L

    enum class Link(val category: String, val heading: String) {
        PREQUEL("follows", "Follows"), SEQUEL("followed_by", "Followed by"),
        SPIN_OFF_FROM("spin_off_from", "Spin-off from"), SPIN_OFF("spin_off", "Spin-offs"),
        REMAKE_OF("remake_of", "Remake of"), REMADE_AS("remade_as", "Remade as"), VERSION_OF("version_of", "Version of");
        val sequelPrequel get() = this == PREQUEL || this == SEQUEL
    }

    data class Ref(val imdb: String, val type: String, val title: String, val year: Int?, val rating: Float?, val votes: Int?,
                   val poster: String?, val link: Link? = null, val isVideo: Boolean = false)
    data class Person(val id: String, val name: String, val photo: String?, val role: String?)
    data class Guide(val category: String, val severity: String)
    /** A production company (IMDb co id); its page lists what else it made. */
    data class Company(val id: String, val name: String)
    /** IMDb's award summary: the big prize (Oscar, Emmy…) won / nominated, and all wins / nominations. */
    data class Awards(val prize: String?, val prizeWins: Int, val prizeNoms: Int, val wins: Int, val noms: Int) {
        /** "Won 1 Oscar", "Nominated for 7 Oscars", or null. */
        val headline: String? get() = prize?.let { p ->
            when { prizeWins > 0 -> "Won $prizeWins $p" + (if (prizeWins > 1) "s" else ""); prizeNoms > 0 -> "Nominated for $prizeNoms $p" + (if (prizeNoms > 1) "s" else ""); else -> null }
        }
        val counts: String? get() = listOfNotNull(wins.takeIf { it > 0 }?.let { "$it win" + if (it > 1) "s" else "" },
            noms.takeIf { it > 0 }?.let { "$it nomination" + if (it > 1) "s" else "" }).joinToString(" · ").ifBlank { null }
        val any get() = headline != null || counts != null
    }
    data class Title(
        val id: String, val name: String, val type: String, val year: Int?, val endYear: Int?, val rating: Float?, val votes: Int?,
        val metascore: Int?, val certificate: String?, val runtimeMin: Int?, val plot: String?, val genres: List<String>, val poster: String?,
        val seasons: List<Int>, val directors: List<Person>, val creators: List<Person>, val cast: List<Person>, val guide: List<Guide>,
        val sequels: List<Ref>, val connections: List<Ref>, val similar: List<Ref>,
        /** Writers (anime: often the original manga / novel author). */
        val writers: List<Person> = emptyList(),
        val companies: List<Company> = emptyList(),
        val awards: Awards? = null
    ) {
        val follows get() = sequels.filter { it.link == Link.PREQUEL }
        val followedBy get() = sequels.filter { it.link == Link.SEQUEL }
    }
    data class Episode(val imdb: String, val season: Int, val episode: Int, val title: String?, val plot: String?, val image: String?,
                       val airedMs: Long?, val rating: Float?, val votes: Int?)
    data class Review(val author: String, val rating: Int?, val summary: String, val text: String, val spoiler: Boolean, val likes: Int, val date: String?)

    private class Cached<T>(val v: T, val at: Long)
    private val titles = ConcurrentHashMap<String, Cached<Title>>()
    private val episodes = ConcurrentHashMap<String, Cached<List<Episode>>>()
    private val reviews = ConcurrentHashMap<String, Cached<Pair<List<Review>, Int>>>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun isId(id: String?) = id != null && id.startsWith("tt") && id.drop(2).all(Char::isDigit)

    private const val NODE = "id titleText { text } titleType { id } releaseYear { year } ratingsSummary { aggregateRating voteCount } primaryImage { url }"

    suspend fun title(id: String): Title? {
        if (!isId(id)) return null
        titles[id]?.takeIf { now() - it.at < CACHE_MS }?.let { return it.v }
        return locks.getOrPut(id) { Mutex() }.withLock {
            titles[id]?.takeIf { now() - it.at < CACHE_MS }?.v ?: withContext(Dispatchers.IO) {
                // Lite's own server (cast + sequels) asked alongside IMDb; either alone still makes the page (Meta.kt).
                kotlinx.coroutines.coroutineScope {
                    val srv = async { Meta.title(id) }
                    val imdb = runCatching { fetchTitle(id) }.onFailure { Log.w(TAG, "$id: ${it.message}") }.getOrNull()
                    Meta.merge(id, imdb, srv.await())
                }?.also { titles[id] = Cached(it, now()) }
            }
        }
    }

    private fun fetchTitle(id: String): Title? {
        fun cats(sp: Boolean) = Link.entries.filter { it.sequelPrequel == sp }.joinToString(", ") { "\"${it.category}\"" }
        val person = "name { id nameText { text } primaryImage { url } }"
        val q = """query { title(id: "$id") {
            titleText { text } titleType { id } releaseYear { year endYear } ratingsSummary { aggregateRating voteCount }
            runtime { seconds } certificate { rating } metacritic { metascore { score } } plot { plotText { plainText } }
            genres { genres { text } } primaryImage { url } episodes { seasons { number } }
            directors: credits(first: 4, filter: { categories: ["director"] }) { edges { node { $person } } }
            creators: credits(first: 4, filter: { categories: ["creator"] }) { edges { node { $person } } }
            writers: credits(first: 4, filter: { categories: ["writer"] }) { edges { node { $person } } }
            cast: credits(first: 20, filter: { categories: ["actor", "actress", "self"] }) { edges { node { $person ... on Cast { characters { name } } } } }
            parentsGuide { categories { category { text } severity { text } } }
            connections(first: 25, filter: { categories: [${cats(true)}] }) { edges { node { category { id } associatedTitle { $NODE } } } }
            others: connections(first: 60, filter: { categories: [${cats(false)}] }) { edges { node { category { id } associatedTitle { $NODE } } } }
            moreLikeThisTitles(first: 20) { edges { node { $NODE } } }
            prestigiousAwardSummary { wins nominations award { text } }
            awardWins: awardNominations(first: 0, filter: { wins: WINS_ONLY }) { total }
            awardNoms: awardNominations(first: 0) { total }
            companyCredits(first: 8, filter: { categories: ["production"] }) { edges { node { company { id companyText { text } } } } }
        } }"""
        val t = post(q, null).optJSONObject("title") ?: return null
        fun people(key: String) = t.optJSONObject(key)?.optJSONArray("edges").objects().mapNotNull { e ->
            val n = e.optJSONObject("node") ?: return@mapNotNull null
            val nm = n.optJSONObject("name") ?: return@mapNotNull null
            Person(nm.optString("id").takeIf { it.startsWith("nm") } ?: return@mapNotNull null,
                nm.optJSONObject("nameText")?.optString("text").orEmpty().ifBlank { return@mapNotNull null },
                nm.optJSONObject("primaryImage")?.optString("url")?.takeIf { it.startsWith("http") }?.let { sized(it, 200) },
                n.optJSONArray("characters").objects().firstOrNull()?.optString("name")?.ifBlank { null })
        }.distinctBy { it.id }
        val sequels = t.optJSONObject("connections")?.optJSONArray("edges").objects().mapNotNull { link(it, true) }
            .filter { it.imdb != id }.distinctBy { it.imdb }.sortedWith(compareBy<Ref> { it.link?.ordinal ?: 0 }.thenBy { it.year ?: Int.MAX_VALUE })
        val thisYear = java.time.Year.now().value
        val connections = t.optJSONObject("others")?.optJSONArray("edges").objects().mapNotNull { link(it, false) }
            // IMDb lists promo minisodes as spin-offs: kept if 1000+ ratings, or not out yet.
            .filter { it.imdb != id && ((it.votes ?: 0) >= 1000 || ((it.votes ?: 0) == 0 && (it.year == null || it.year >= thisYear))) }
            .distinctBy { it.imdb }.sortedWith(compareBy<Ref> { it.link?.ordinal ?: 0 }.thenBy { it.year ?: Int.MAX_VALUE })
        val similar = t.optJSONObject("moreLikeThisTitles")?.optJSONArray("edges").objects()
            .mapNotNull { it.optJSONObject("node")?.let { n -> ref(n, null, false) } }.filter { it.imdb != id }.distinctBy { it.imdb }
        val type = when (t.optJSONObject("titleType")?.optString("id")) { "tvSeries", "tvMiniSeries" -> "series"; else -> "movie" }
        return Title(
            id, t.optJSONObject("titleText")?.optString("text").orEmpty(), type,
            t.optJSONObject("releaseYear")?.optInt("year")?.takeIf { it > 0 }, t.optJSONObject("releaseYear")?.optInt("endYear")?.takeIf { it > 0 },
            t.optJSONObject("ratingsSummary")?.optDouble("aggregateRating")?.takeIf { !it.isNaN() && it > 0 }?.toFloat(),
            t.optJSONObject("ratingsSummary")?.optInt("voteCount")?.takeIf { it > 0 },
            t.optJSONObject("metacritic")?.optJSONObject("metascore")?.optInt("score")?.takeIf { it > 0 },
            t.optJSONObject("certificate")?.optString("rating")?.ifBlank { null },
            t.optJSONObject("runtime")?.optInt("seconds")?.takeIf { it > 0 }?.let { it / 60 },
            t.optJSONObject("plot")?.optJSONObject("plotText")?.optString("plainText")?.ifBlank { null },
            t.optJSONObject("genres")?.optJSONArray("genres").objects().mapNotNull { it.optString("text").ifBlank { null } },
            t.optJSONObject("primaryImage")?.optString("url")?.takeIf { it.startsWith("http") }?.let { sized(it, 500) },
            t.optJSONObject("episodes")?.optJSONArray("seasons").objects().mapNotNull { it.optString("number").toIntOrNull() }.distinct().sorted(),
            people("directors"), people("creators"), people("cast"),
            t.optJSONObject("parentsGuide")?.optJSONArray("categories").objects().mapNotNull { c ->
                val cat = c.optJSONObject("category")?.optString("text")?.ifBlank { null } ?: return@mapNotNull null
                val sev = c.optJSONObject("severity")?.optString("text")?.ifBlank { null } ?: return@mapNotNull null
                Guide(cat, sev)
            },
            sequels, connections, similar, people("writers"),
            t.optJSONObject("companyCredits")?.optJSONArray("edges").objects().mapNotNull { e ->
                val c = e.optJSONObject("node")?.optJSONObject("company") ?: return@mapNotNull null
                Company(c.optString("id").takeIf { it.startsWith("co") } ?: return@mapNotNull null,
                    c.optJSONObject("companyText")?.optString("text")?.ifBlank { null } ?: return@mapNotNull null)
            }.distinctBy { it.id },
            run {
                val pa = t.optJSONObject("prestigiousAwardSummary")
                Awards(pa?.optJSONObject("award")?.optString("text")?.ifBlank { null }, pa?.optInt("wins") ?: 0, pa?.optInt("nominations") ?: 0,
                    t.optJSONObject("awardWins")?.optInt("total") ?: 0, t.optJSONObject("awardNoms")?.optInt("total") ?: 0).takeIf { it.any }
            }
        )
    }

    private fun link(edge: JSONObject, sp: Boolean): Ref? {
        val n = edge.optJSONObject("node") ?: return null
        val l = Link.entries.firstOrNull { it.category == n.optJSONObject("category")?.optString("id") } ?: return null
        if (l.sequelPrequel != sp) return null
        return ref(n.optJSONObject("associatedTitle") ?: return null, l, sp)
    }

    private fun ref(o: JSONObject, link: Link?, withVideos: Boolean): Ref? {
        val id = o.optString("id").takeIf { it.startsWith("tt") } ?: return null
        val tt = o.optJSONObject("titleType")?.optString("id")
        val type = when (tt) { "movie", "tvMovie" -> "movie"; "video" -> if (withVideos) "movie" else return null; "tvSeries", "tvMiniSeries" -> "series"; else -> return null }
        val r = o.optJSONObject("ratingsSummary")
        return Ref(id, type, o.optJSONObject("titleText")?.optString("text")?.ifBlank { null } ?: return null,
            o.optJSONObject("releaseYear")?.optInt("year")?.takeIf { it > 0 },
            r?.optDouble("aggregateRating")?.takeIf { !it.isNaN() && it > 0 }?.toFloat(), r?.optInt("voteCount")?.takeIf { it > 0 },
            o.optJSONObject("primaryImage")?.optString("url")?.takeIf { it.startsWith("http") }?.let { sized(it, 342) }, link, tt == "video")
    }

    /** Every episode of a series (Vision's ImdbEpisodesService, plus titles, plots, stills and air dates). */
    suspend fun episodes(seriesId: String): List<Episode> {
        if (!isId(seriesId)) return emptyList()
        episodes[seriesId]?.takeIf { now() - it.at < CACHE_MS }?.let { return it.v }
        return locks.getOrPut("e$seriesId") { Mutex() }.withLock {
            episodes[seriesId]?.takeIf { now() - it.at < CACHE_MS }?.v ?: withContext(Dispatchers.IO) {
                val out = ArrayList<Episode>()
                var after: String? = null
                runCatching {
                    repeat(8) {
                        val (page, next) = episodePage(seriesId, after)
                        out += page
                        after = next ?: return@runCatching
                    }
                }.onFailure { Log.w(TAG, "$seriesId episodes after ${out.size}: ${it.message}") }
                out.distinctBy { it.season to it.episode }.sortedWith(compareBy({ it.season }, { it.episode }))
                    .also { if (it.isNotEmpty()) episodes[seriesId] = Cached(it, now()) }
            }
        }
    }

    private fun episodePage(id: String, after: String?): Pair<List<Episode>, String?> {
        val q = """query (${'$'}id: ID!, ${'$'}after: ID) { title(id: ${'$'}id) { episodes { episodes(first: 250, after: ${'$'}after) {
            pageInfo { hasNextPage endCursor }
            edges { node { id titleText { text } plot { plotText { plainText } } primaryImage { url } releaseDate { year month day }
              series { episodeNumber { seasonNumber episodeNumber } } ratingsSummary { aggregateRating voteCount } } } } } } }"""
        val list = post(q, JSONObject().put("id", id).put("after", after ?: JSONObject.NULL))
            .optJSONObject("title")?.optJSONObject("episodes")?.optJSONObject("episodes") ?: throw IllegalStateException("no episodes")
        val eps = list.optJSONArray("edges").objects().mapNotNull { e ->
            val n = e.optJSONObject("node") ?: return@mapNotNull null
            val num = n.optJSONObject("series")?.optJSONObject("episodeNumber") ?: return@mapNotNull null
            val s = num.optInt("seasonNumber", -1).takeIf { it >= 0 } ?: return@mapNotNull null
            val ep = num.optInt("episodeNumber", -1).takeIf { it >= 0 } ?: return@mapNotNull null
            val d = n.optJSONObject("releaseDate")
            val aired = d?.optInt("year")?.takeIf { it > 0 }?.let { y ->
                runCatching { java.time.LocalDate.of(y, d.optInt("month", 1).coerceIn(1, 12), d.optInt("day", 1).coerceIn(1, 31))
                    .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
            }
            val title = n.optJSONObject("titleText")?.optString("text")?.takeIf { it.isNotBlank() && !it.startsWith("Episode #") }
            val r = n.optJSONObject("ratingsSummary")
            Episode(n.optString("id"), s, ep, title, n.optJSONObject("plot")?.optJSONObject("plotText")?.optString("plainText")?.ifBlank { null },
                n.optJSONObject("primaryImage")?.optString("url")?.takeIf { it.startsWith("http") }?.let { sizedWide(it) }, aired,
                r?.optDouble("aggregateRating")?.takeIf { !it.isNaN() && it > 0 }?.toFloat(), r?.optInt("voteCount")?.takeIf { it > 0 })
        }
        val info = list.optJSONObject("pageInfo")
        return eps to info?.optString("endCursor")?.takeIf { info.optBoolean("hasNextPage") && it.isNotBlank() }
    }

    /** IMDb user reviews (most helpful first, 25), of a title or of one episode (its own IMDb id). Total count second. */
    suspend fun reviews(id: String): Pair<List<Review>, Int> {
        if (!isId(id)) return emptyList<Review>() to 0
        reviews[id]?.takeIf { now() - it.at < 30 * 60_000L }?.let { return it.v }
        return withContext(Dispatchers.IO) {
            runCatching {
                val q = """query (${'$'}id: ID!) { title(id: ${'$'}id) { reviews(first: 25) { total
                    edges { node { author { nickName } authorRating submissionDate spoiler helpfulness { upVotes }
                      summary { originalText } text { originalText { plainText } } } } } } }"""
                val r = post(q, JSONObject().put("id", id)).optJSONObject("title")?.optJSONObject("reviews") ?: throw IllegalStateException("no reviews")
                val items = r.optJSONArray("edges").objects().mapNotNull { e ->
                    val n = e.optJSONObject("node") ?: return@mapNotNull null
                    val sum = n.optJSONObject("summary")?.optString("originalText").orEmpty().trim()
                    val text = n.optJSONObject("text")?.optJSONObject("originalText")?.optString("plainText").orEmpty().trim()
                    if (sum.isEmpty() && text.isEmpty()) return@mapNotNull null
                    Review(n.optJSONObject("author")?.optString("nickName").orEmpty().ifBlank { "IMDb user" }, n.optInt("authorRating").takeIf { it in 1..10 },
                        sum, text, n.optBoolean("spoiler"), n.optJSONObject("helpfulness")?.optInt("upVotes") ?: 0, n.optString("submissionDate").ifBlank { null })
                }
                (items to r.optInt("total", items.size)).also { reviews[id] = Cached(it, now()) }
            }.onFailure { Log.w(TAG, "$id reviews: ${it.message}") }.getOrDefault(emptyList<Review>() to 0)
        }
    }

    private fun post(query: String, vars: JSONObject?): JSONObject {
        val body = JSONObject().put("query", query).apply { vars?.let { put("variables", it) } }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(ENDPOINT).post(body).header("x-imdb-client-name", "imdb-web-next").header("Accept", "application/json").build()
        val json = Net.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("IMDb answered ${r.code}")
            JSONObject(r.body?.string().orEmpty())
        }
        return json.optJSONObject("data") ?: throw IllegalStateException("IMDb sent no data")
    }

    private val SIZE = Regex("""\._V1_[^/]*\.jpg$""")
    /** IMDb's full-size image (often 400 KB+) asked at the size shown. */
    fun sized(url: String, w: Int): String = SIZE.find(url)?.let { url.replaceRange(it.range, "._V1_QL75_UX${w}_.jpg") } ?: url.replace("._V1_.jpg", "._V1_QL75_UX${w}_.jpg")
    private fun sizedWide(url: String) = sized(url, 480)

    private fun now() = System.currentTimeMillis()
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    /** "33K", "1.2M". */
    fun votes(n: Int?): String? = when {
        n == null -> null
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "${n / 1000}K"
        else -> n.toString()
    }
}
