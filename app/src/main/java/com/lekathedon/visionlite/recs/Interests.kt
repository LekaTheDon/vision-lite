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

    /**
     * Regional subgenres (user 2026-11-23: "I like crime, but it's too broad: Nordic Noir is what I mean"): a genre
     * family as made in one part of the world, learned when you keep watching it ("rg:NORDIC:Crime" = Nordic Noir).
     */
    class Region(val adj: String, val countries: List<String>)
    val REGIONS: Map<String, Region> = linkedMapOf(
        "NORDIC" to Region("Nordic", listOf("SE", "DK", "NO", "FI", "IS")), "BRITISH" to Region("British", listOf("GB", "IE")),
        "KOREAN" to Region("Korean", listOf("KR")), "JAPANESE" to Region("Japanese", listOf("JP")), "SPANISH" to Region("Spanish", listOf("ES")),
        "LATAM" to Region("Latin American", listOf("MX", "AR", "CO", "BR", "CL")), "FRENCH" to Region("French", listOf("FR", "BE")),
        "ITALIAN" to Region("Italian", listOf("IT")), "GERMAN" to Region("German", listOf("DE", "AT")), "TURKISH" to Region("Turkish", listOf("TR")),
        "ARABIC" to Region("Arabic", listOf("EG", "SA", "AE", "LB", "SY", "JO", "KW", "QA", "MA", "TN", "IQ", "BH", "OM")),
        "INDIAN" to Region("Indian", listOf("IN")), "AUSSIE" to Region("Australian", listOf("AU", "NZ"))
    )
    /** Genre families: the interests that make each (crime takes mystery and serial killers along). */
    val FAMILIES: Map<String, List<Int>> = linkedMapOf(
        "Crime" to (50..58).toList() + (136..141).toList() + 183, "Thriller" to (176..186).toList(), "Drama" to (72..91).toList(),
        "Comedy" to (31..49).toList(), "Horror" to (108..122).toList(), "Romance" to (149..156).toList(), "Action" to (1..11).toList(),
        "Sci-Fi" to (157..166).toList()
    )
    /**
     * What makes a title belong to a regional family (user 2026-11-23: "British Drama" showed Vikings, Band of Brothers,
     * 2001 and One Piece): IMDb's own genre must be the family's, none of the genres that turn it into something else
     * (a fantasy epic, a sci-fi blockbuster, an animation) may be there, and the FIRST country of origin must be in the
     * region, so a Hollywood film with a British co-producer isn't "British".
     */
    /** [anime]: the Anime hub, where every title is an animation, so Animation is required, never left out. */
    private fun excluded(family: String, anime: Boolean = false): Set<String> = excludedAny(family) - (if (anime) setOf("Animation") else emptySet())
    private fun excludedAny(family: String): Set<String> =
        if (family in setOf("Action", "Sci-Fi", "Adventure", "Fantasy", "Animation")) setOf("Animation") - family
        else setOf("Animation", "Fantasy", "Sci-Fi", "Action", "Adventure") - family

    /** A subgenre's own IMDb genre (its group), so "Psychological Thriller" is a thriller first; null = no check. */
    private fun parentGenre(n: Int): String? = GROUPS.firstOrNull { n in it.second }?.first?.let {
        when (it) { "Martial arts" -> "Action"; "Family & Fantasy", "Game shows", "Lifestyle & Music", "Holiday", "Anime & Asian TV" -> null; else -> it }
    }
    fun fits(family: String, genres: List<String>, anime: Boolean = false) =
        family in genres && genres.none { it in excluded(family, anime) } && (!anime || "Animation" in genres)
    fun madeIn(region: Region, countries: List<String>) = countries.firstOrNull() in region.countries

    private val SPECIAL = mapOf("NORDIC:Crime" to "Nordic Noir", "BRITISH:Crime" to "British Crime Drama", "SPANISH:Crime" to "Spanish Crime",
        "KOREAN:Thriller" to "Korean Thriller", "ITALIAN:Crime" to "Italian Crime", "FRENCH:Crime" to "French Polar", "JAPANESE:Horror" to "J-Horror",
        "KOREAN:Drama" to "K-Drama", "TURKISH:Drama" to "Turkish Drama", "ARABIC:Drama" to "Arabic Drama", "ARABIC:Comedy" to "Arabic Comedy")
    fun regional(region: String, family: String) = "rg:$region:$family"

    fun id(n: Int) = "in%07d".format(n)
    fun num(id: String) = if (id.startsWith("in")) id.removePrefix("in").toIntOrNull() else null
    fun name(id: String): String? {
        if (id.startsWith("rg:")) {
            val (_, r, f) = id.split(':').takeIf { it.size == 3 } ?: return null
            val reg = REGIONS[r] ?: return null
            if (f !in FAMILIES) return null
            return SPECIAL["$r:$f"] ?: "${reg.adj} $f"
        }
        return num(id)?.let { NAMES[it] }
    }
    /** Broad genres (Crime, Drama…) never get a row: Home has their tiles. A liked one asks for your subgenres of it. */
    fun isBroad(id: String) = num(id) in BROAD
    private fun familyOf(id: String): String? = when {
        id.startsWith("rg:") -> id.split(':').getOrNull(2)
        else -> num(id)?.let { n -> FAMILIES.entries.firstOrNull { n in it.value }?.key }
    }
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
        // Regional subgenres: titles of a family (any of its interests) made in one region.
        val rScore = HashMap<String, Double>(); val rCount = HashMap<String, Int>()
        items.filter { it.second > 0 }.forEach { (f, w) ->
            REGIONS.forEach { (rk, r) ->
                if (!madeIn(r, f.countries)) return@forEach
                FAMILIES.keys.forEach { fk -> if (fits(fk, f.genres, hub == Hub.ANIME)) { val k = regional(rk, fk); rScore.merge(k, w, Double::plus); rCount.merge(k, 1, Int::plus) } }
            }
        }
        // Broad genres left out (Home has their tiles); a subgenre or a regional one is what gets a row.
        val list = (score.entries.filter { num(it.key) !in BROAD }.map { (i, s) -> Learned(i, s, count[i] ?: 0) } +
            rScore.map { (k, s) -> Learned(k, s * 1.2, rCount[k] ?: 0) })
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
        return l.filter { it.id.startsWith("in") }.associate { it.id to it.score / top } + liked().filter { it.startsWith("in") }.associateWith { 1.0 }
    }

    /** The [n] genres Home follows now: from your liked ones and your 6 strongest, a different few every 12 hours. */
    fun now(hub: Hub, n: Int, at: Long = System.currentTimeMillis()): List<String> {
        val anime = hub == Hub.ANIME
        val learned = learned(hub)
        val mine = liked().filter { (num(it) in 201..210) == anime }.flatMap { l ->
            // A liked broad genre (Crime): your strongest subgenres of it instead (Police Procedural, Nordic Noir…).
            if (!isBroad(l)) listOf(l) else learned.filter { familyOf(it.id) == familyOf(l) }.take(3).map { it.id }
        }
        val pool = (mine + learned.map { it.id }.take(6)).filter { !isBroad(it) }.distinct()
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
        val ck = "${hub.name}|$id"
        val all = cache[ck] ?: search(id, Recs.minImdb(), hub == Hub.ANIME).also { if (it.isNotEmpty()) cache[ck] = it }
        val seen = Recs.seenIds()
        val c = countries(hub)
        val r = java.util.Random(at / (12 * 3600_000L) + id.hashCode())
        // This hub's titles only (Anime gets anime, Films & TV never does).
        all.filter { it.first.id !in seen && com.lekathedon.visionlite.anime.Hubs.keepsId(hub, it.first.id) }
            .map { (m, cs) -> m to (m.imdbRating?.toDoubleOrNull() ?: 6.0) + 2.0 * (cs.maxOfOrNull { c[it] ?: 0.0 } ?: 0.0) + r.nextDouble() * 1.2 }
            .sortedByDescending { it.second }.map { it.first }.take(30)
    }

    private fun search(id: String, min: Float, anime: Boolean): List<Pair<MetaPreview, List<String>>> {
        val constraint = if (id.startsWith("rg:")) {
            val (_, r, f) = id.split(':')
            "genreConstraint: { allGenreIds: [\"$f\"${if (anime) ", \"Animation\"" else ""}], excludeGenreIds: [${excluded(f, anime).joinToString(",") { "\"$it\"" }}] } " +
                "originCountryConstraint: { anyCountries: [${REGIONS.getValue(r).countries.joinToString(",") { "\"$it\"" }}] }"
        } else "interestConstraint: { allInterestIds: [\"$id\"] }"
        val node = "title { id titleText { text } releaseYear { year } titleType { id } ratingsSummary { aggregateRating } primaryImage { url } countriesOfOrigin { countries { id } } titleGenres { genres { genre { text } } } }"
        val q = "{ advancedTitleSearch(first: 250, constraints: { $constraint " +
            "titleTypeConstraint: { anyTitleTypeIds: [\"movie\", \"tvSeries\", \"tvMiniSeries\"] } " +
            "userRatingsConstraint: { aggregateRatingRange: { min: ${maxOf(min, 6.0f)} }, ratingsCountRange: { min: 2000 } } }, " +
            "sort: { sortBy: POPULARITY, sortOrder: ASC }) { edges { node { $node } } } }"
        val d = runCatching {
            Net.http.newCall(Request.Builder().url("https://api.graphql.imdb.com/").post(JSONObject().put("query", q).toString().toRequestBody("application/json".toMediaType()))
                .header("x-imdb-client-name", "imdb-web-next-localized").build()).execute().use { JSONObject(it.body!!.string()) }
        }.getOrNull() ?: return emptyList()
        val edges = d.optJSONObject("data")?.optJSONObject("advancedTitleSearch")?.optJSONArray("edges") ?: return emptyList()
        val region = if (id.startsWith("rg:")) REGIONS[id.split(':')[1]] else null
        val parent = if (region == null) num(id)?.let(::parentGenre) else null
        return (0 until edges.length()).mapNotNull { i ->
            val t = edges.optJSONObject(i)?.optJSONObject("node")?.optJSONObject("title") ?: return@mapNotNull null
            val tid = t.optString("id").takeIf { it.startsWith("tt") } ?: return@mapNotNull null
            val kind = t.optJSONObject("titleType")?.optString("id").orEmpty()
            val img = t.optJSONObject("primaryImage")?.optString("url")?.takeIf { it.isNotBlank() }
                ?.let { u -> if ("._V1_" in u) u.substringBefore("._V1_") + "._V1_QL75_UX342_.jpg" else u }
            val cs = t.optJSONObject("countriesOfOrigin")?.optJSONArray("countries")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id") } }.orEmpty()
            // Regional rows: made there first, not just co-produced there.
            if (region != null && !madeIn(region, cs)) return@mapNotNull null
            // Subgenre rows: IMDb tags loosely (The Dark Knight is a "Psychological Drama"), so the title must also be
            // of the subgenre's own genre and not something else on top (an action blockbuster, a fantasy epic).
            val gs = t.optJSONObject("titleGenres")?.optJSONArray("genres")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optJSONObject("genre")?.optString("text") } }.orEmpty()
            if (parent != null && gs.isNotEmpty() && !fits(parent, gs, anime)) return@mapNotNull null
            MetaPreview(tid, if (kind == "movie") "movie" else "series", t.optJSONObject("titleText")?.optString("text") ?: return@mapNotNull null,
                img ?: "https://images.metahub.space/poster/medium/$tid/img", "https://images.metahub.space/background/medium/$tid/img",
                releaseInfo = t.optJSONObject("releaseYear")?.optInt("year")?.takeIf { it > 0 }?.toString(),
                imdbRating = t.optJSONObject("ratingsSummary")?.optDouble("aggregateRating")?.takeIf { !it.isNaN() && it > 0 }?.toString()) to cs
        }
    }
}
