package com.lekathedon.visionlite.recs

import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.addons.MetaPreview
import com.lekathedon.visionlite.anime.Hub
import com.lekathedon.visionlite.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Your genres (user 2026-11-23): "Because you like Police Procedural" rows, the way "Because you like <actor>" works for
 * people. Genres = IMDb's interests (its ~200 genre and subgenre tags). The engine learns them from what you watch:
 * every title it builds from (watched, rated, loved, rewatched, how many episodes) carries IMDb's interests, and each
 * interest adds up those titles' weights. Broad ones (Drama, Crime, Comedy…) count a third, so the specific things you
 * keep coming back to win. You can also like interests yourself (Settings › Your interests, like IMDb's): a liked one
 * always counts fully. Both steer Vision Picks too (RecsLogic W_INTEREST).
 * Cost: no extra request to learn (the interests ride in the engine's IMDb facts); one IMDb search per row shown, kept
 * for the app run; two rows, a different two every 12 hours.
 */
object Interests {
    /** IMDb interest number → name: genres only (no languages, franchises, news or shorts). */
    private val NAMES: Map<Int, String> = mapOf(
        1 to "Action", 2 to "Action Epic", 3 to "B-Action", 4 to "Car Action", 5 to "Disaster", 6 to "Martial Arts", 7 to "One-Person Army Action",
        8 to "Superhero", 9 to "Sword & Sandal", 10 to "War", 11 to "War Epic", 12 to "Adventure", 13 to "Desert Adventure", 14 to "Dinosaur Adventure",
        15 to "Adventure Epic", 16 to "Globetrotting Adventure", 17 to "Jungle Adventure", 18 to "Mountain Adventure", 19 to "Quest", 20 to "Road Trip",
        21 to "Sea Adventure", 22 to "Swashbuckler", 23 to "Teen Adventure", 24 to "Urban Adventure", 25 to "Adult Animation", 26 to "Animation",
        27 to "Anime", 28 to "Computer Animation", 29 to "Hand-Drawn Animation", 30 to "Stop Motion Animation", 31 to "Body Swap Comedy", 32 to "Buddy Comedy",
        33 to "Buddy Cop", 34 to "Comedy", 35 to "Dark Comedy", 36 to "Farce", 37 to "High-Concept Comedy", 38 to "Mockumentary", 39 to "Parody",
        40 to "Quirky Comedy", 41 to "Raunchy Comedy", 42 to "Satire", 43 to "Screwball Comedy", 44 to "Sitcom", 45 to "Sketch Comedy", 46 to "Slapstick",
        47 to "Stand-Up", 48 to "Stoner Comedy", 49 to "Teen Comedy", 50 to "Caper", 51 to "Cop Drama", 52 to "Crime", 53 to "Drug Crime", 54 to "Film Noir",
        55 to "Gangster", 56 to "Heist", 57 to "Police Procedural", 58 to "True Crime", 59 to "Crime Documentary", 60 to "Documentary", 61 to "Docuseries",
        62 to "Faith & Spirituality Documentary", 63 to "Food Documentary", 64 to "History Documentary", 65 to "Military Documentary", 66 to "Music Documentary",
        67 to "Nature Documentary", 68 to "Political Documentary", 69 to "Science & Technology Documentary", 70 to "Sports Documentary", 71 to "Travel Documentary",
        72 to "Biography", 73 to "Coming-of-Age", 74 to "Costume Drama", 75 to "Docudrama", 76 to "Drama", 77 to "Epic", 78 to "Financial Drama",
        79 to "Historical Epic", 80 to "History", 81 to "Legal Drama", 82 to "Medical Drama", 83 to "Period Drama", 84 to "Political Drama", 85 to "Prison Drama",
        86 to "Psychological Drama", 87 to "Showbiz Drama", 88 to "Soap Opera", 89 to "Teen Drama", 90 to "Tragedy", 91 to "Workplace Drama",
        92 to "Animal Adventure", 93 to "Family", 95 to "Dark Fantasy", 96 to "Fantasy Epic", 97 to "Fairy Tale", 98 to "Fantasy", 99 to "Supernatural Fantasy",
        100 to "Sword & Sorcery", 101 to "Teen Fantasy", 104 to "Quiz Show", 105 to "Game Show", 106 to "Survival Competition", 107 to "Talent Competition",
        108 to "B-Horror", 109 to "Body Horror", 110 to "Folk Horror", 111 to "Found Footage Horror", 112 to "Horror", 113 to "Monster Horror",
        114 to "Psychological Horror", 115 to "Slasher Horror", 116 to "Splatter Horror", 117 to "Supernatural Horror", 118 to "Teen Horror",
        119 to "Vampire Horror", 120 to "Werewolf Horror", 121 to "Witch Horror", 122 to "Zombie Horror", 124 to "Cooking & Food", 128 to "Travel",
        130 to "Music", 133 to "Musical", 136 to "Bumbling Detective", 137 to "Cozy Mystery", 138 to "Hard-boiled Detective", 139 to "Mystery",
        140 to "Suspense Mystery", 141 to "Whodunnit", 143 to "Crime Reality TV", 148 to "Reality TV", 149 to "Dark Romance", 150 to "Romantic Epic",
        151 to "Feel-Good Romance", 152 to "Romance", 153 to "Romantic Comedy", 154 to "Steamy Romance", 155 to "Teen Romance", 156 to "Tragic Romance",
        157 to "Alien Invasion", 158 to "Artificial Intelligence", 159 to "Cyberpunk", 160 to "Dystopian Sci-Fi", 161 to "Kaiju", 162 to "Sci-Fi",
        163 to "Sci-Fi Epic", 164 to "Space Sci-Fi", 165 to "Steampunk", 166 to "Time Travel", 174 to "Sport", 176 to "Conspiracy Thriller",
        177 to "Cyber Thriller", 178 to "Erotic Thriller", 179 to "Giallo", 180 to "Legal Thriller", 181 to "Political Thriller", 182 to "Psychological Thriller",
        183 to "Serial Killer", 184 to "Spy", 185 to "Survival", 186 to "Thriller", 187 to "Classical Western", 188 to "Contemporary Western",
        189 to "Western Epic", 190 to "Spaghetti Western", 191 to "Western", 192 to "Holiday", 197 to "Gun Fu", 198 to "Kung Fu", 199 to "Samurai",
        200 to "Wuxia", 201 to "Isekai", 202 to "Iyashikei", 203 to "Josei", 204 to "Mecha", 205 to "Seinen", 206 to "Shōnen", 207 to "Shōjo",
        208 to "Slice of Life", 209 to "Korean Drama", 210 to "Telenovela"
    )
    /** The broad ones: a third of the weight, so "Crime" doesn't crowd out "Police Procedural". */
    private val BROAD = setOf(1, 12, 26, 27, 34, 52, 60, 72, 76, 80, 93, 98, 112, 139, 152, 162, 174, 186, 191)

    /** For the Interests page: groups in IMDb's order. */
    val GROUPS: List<Pair<String, IntRange>> = listOf(
        "Action" to 1..11, "Adventure" to 12..24, "Animation" to 25..30, "Comedy" to 31..49, "Crime" to 50..58, "Documentary" to 59..71,
        "Drama" to 72..91, "Family & Fantasy" to 92..101, "Game shows" to 102..107, "Horror" to 108..122, "Lifestyle & Music" to 123..135,
        "Mystery" to 136..141, "Reality" to 142..148, "Romance" to 149..156, "Sci-Fi" to 157..166, "Sport" to 167..175, "Thriller" to 176..186,
        "Western" to 187..191, "Holiday" to 192..196, "Martial arts" to 197..200, "Anime & Asian TV" to 201..210
    )

    fun id(n: Int) = "in%07d".format(n)
    fun num(id: String) = id.removePrefix("in").toIntOrNull()
    fun name(id: String): String? = num(id)?.let { NAMES[it] }
    fun inGroup(r: IntRange): List<String> = r.filter { it in NAMES }.map(::id)

    private val sp get() = App.instance.prefs.sp
    private val _changed = MutableStateFlow(0)
    /** Bumped when you like / unlike one or the engine learns again. */
    val changed: StateFlow<Int> get() = _changed

    // ---- liked by you (per profile) ----
    fun liked(): Set<String> = sp.getStringSet("interests_liked", emptySet()).orEmpty()
    fun isLiked(id: String) = id in liked()
    fun toggle(id: String) {
        val l = liked(); sp.edit().putStringSet("interests_liked", if (id in l) l - id else l + id).apply(); _changed.value++
    }

    // ---- learned from what you watch (per profile and hub) ----
    data class Learned(val id: String, val score: Double, val titles: Int)

    /** From the engine's seeds (full facts, weight): each interest's weight, how many titles carry it, your countries. */
    fun learn(hub: Hub, items: List<Pair<Facts, Double>>) {
        val score = HashMap<String, Double>(); val count = HashMap<String, Int>(); val ctry = HashMap<String, Double>()
        var total = 0.0
        items.forEach { (f, w) ->
            f.interests.filter { name(it) != null }.forEach { i ->
                if (w > 0) { score.merge(i, w, Double::plus); count.merge(i, 1, Int::plus) } else score.merge(i, w * 0.5, Double::plus)
            }
            if (w > 0) { total += w; f.countries.forEach { ctry.merge(it, w, Double::plus) } }
        }
        if (score.isEmpty()) return
        val list = score.entries.map { (i, s) -> Learned(i, s * (if (num(i) in BROAD) 0.33 else 1.0), count[i] ?: 0) }
            .filter { it.score > 0 && it.titles >= 3 }.sortedByDescending { it.score }.take(40)
        val o = JSONObject()
        o.put("i", JSONArray(list.map { JSONObject().put("id", it.id).put("s", it.score).put("n", it.titles) }))
        o.put("c", JSONObject(ctry.entries.sortedByDescending { it.value }.take(10).associate { it.key to it.value / total.coerceAtLeast(1.0) }))
        sp.edit().putString("interests_learned${hub.suffix}", o.toString()).apply()
        _changed.value++
    }

    private fun stored(hub: Hub): JSONObject? = runCatching { JSONObject(sp.getString("interests_learned${hub.suffix}", null) ?: return null) }.getOrNull()

    /** Your genres, strongest first (learned; none until the engine has built once with this version). */
    fun learned(hub: Hub): List<Learned> = stored(hub)?.optJSONArray("i")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Learned(it.optString("id"), it.optDouble("s"), it.optInt("n")) }
    }.orEmpty()

    private fun countries(hub: Hub): Map<String, Double> = stored(hub)?.optJSONObject("c")?.let { o -> o.keys().asSequence().associateWith { o.optDouble(it) } }.orEmpty()

    /** For the engine's scoring: learned (0..1 of the strongest) with liked ones at 1. */
    fun weights(hub: Hub): Map<String, Double> {
        val l = learned(hub); val top = l.firstOrNull()?.score?.coerceAtLeast(1e-6) ?: 1.0
        return l.associate { it.id to it.score / top } + liked().associateWith { 1.0 }
    }

    /** The [n] genres Home follows now: from your liked ones and your 6 strongest, a different few every 12 hours. */
    fun now(hub: Hub, n: Int, at: Long = System.currentTimeMillis()): List<String> {
        val anime = hub == Hub.ANIME
        val mine = liked().filter { (num(it) in 201..210) == anime }
        val pool = (mine + learned(hub).map { it.id }.take(6)).distinct()
        if (pool.size <= n) return pool
        return pool.shuffled(java.util.Random(at / (12 * 3600_000L) * 37 + hub.ordinal)).take(n)
    }

    /** Each genre's titles, asked once per app run (one IMDb search each). */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<Pair<MetaPreview, List<String>>>>()

    /**
     * A genre's row: IMDb's most popular titles with that interest (over your IMDb minimum), nothing you've watched,
     * saved or turned down; titles from the countries you watch most lifted, shuffled a little every 12 hours.
     */
    suspend fun row(hub: Hub, id: String, at: Long = System.currentTimeMillis()): List<MetaPreview> = withContext(Dispatchers.IO) {
        val all = cache[id] ?: search(id, Recs.minImdb()).also { if (it.isNotEmpty()) cache[id] = it }
        val seen = Recs.seenIds()
        val c = countries(hub)
        val r = java.util.Random(at / (12 * 3600_000L) + id.hashCode())
        all.filter { it.first.id !in seen }
            .map { (m, cs) -> m to (m.imdbRating?.toDoubleOrNull() ?: 6.0) + 2.0 * (cs.maxOfOrNull { c[it] ?: 0.0 } ?: 0.0) + r.nextDouble() * 1.2 }
            .sortedByDescending { it.second }.map { it.first }.take(30)
    }

    private fun search(id: String, min: Float): List<Pair<MetaPreview, List<String>>> {
        val node = "title { id titleText { text } releaseYear { year } titleType { id } ratingsSummary { aggregateRating } primaryImage { url } countriesOfOrigin { countries { id } } }"
        val q = "{ advancedTitleSearch(first: 100, constraints: { interestConstraint: { allInterestIds: [\"$id\"] } " +
            "titleTypeConstraint: { anyTitleTypeIds: [\"movie\", \"tvSeries\", \"tvMiniSeries\"] } " +
            "userRatingsConstraint: { aggregateRatingRange: { min: ${maxOf(min, 6.0f)} }, ratingsCountRange: { min: 2000 } } }, " +
            "sort: { sortBy: POPULARITY, sortOrder: ASC }) { edges { node { $node } } } }"
        val d = runCatching {
            Net.http.newCall(Request.Builder().url("https://api.graphql.imdb.com/").post(JSONObject().put("query", q).toString().toRequestBody("application/json".toMediaType()))
                .header("x-imdb-client-name", "imdb-web-next-localized").build()).execute().use { JSONObject(it.body!!.string()) }
        }.getOrNull() ?: return emptyList()
        val edges = d.optJSONObject("data")?.optJSONObject("advancedTitleSearch")?.optJSONArray("edges") ?: return emptyList()
        return (0 until edges.length()).mapNotNull { i ->
            val t = edges.optJSONObject(i)?.optJSONObject("node")?.optJSONObject("title") ?: return@mapNotNull null
            val tid = t.optString("id").takeIf { it.startsWith("tt") } ?: return@mapNotNull null
            val kind = t.optJSONObject("titleType")?.optString("id").orEmpty()
            val img = t.optJSONObject("primaryImage")?.optString("url")?.takeIf { it.isNotBlank() }
                ?.let { u -> if ("._V1_" in u) u.substringBefore("._V1_") + "._V1_QL75_UX342_.jpg" else u }
            val cs = t.optJSONObject("countriesOfOrigin")?.optJSONArray("countries")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id") } }.orEmpty()
            MetaPreview(tid, if (kind == "movie") "movie" else "series", t.optJSONObject("titleText")?.optString("text") ?: return@mapNotNull null,
                img ?: "https://images.metahub.space/poster/medium/$tid/img", "https://images.metahub.space/background/medium/$tid/img",
                releaseInfo = t.optJSONObject("releaseYear")?.optInt("year")?.takeIf { it > 0 }?.toString(),
                imdbRating = t.optJSONObject("ratingsSummary")?.optDouble("aggregateRating")?.takeIf { !it.isNaN() && it > 0 }?.toString()) to cs
        }
    }
}
