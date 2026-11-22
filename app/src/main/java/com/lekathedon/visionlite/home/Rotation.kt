package com.lekathedon.visionlite.home

import android.util.Log
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.addons.MetaPreview
import com.lekathedon.visionlite.anime.Hub
import com.lekathedon.visionlite.anime.Hubs
import com.lekathedon.visionlite.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Rotating rows (the DebridStream idea, our own pool): under your saved rows, a fresh set of themed rows
 * ("Top Bank Robbery Movies", "Stuck in a Time Loop") every time the app starts. Each start shows the set built last
 * time (instant, from disk) and builds the next one in the background a minute after Home shows: one request per row,
 * one at a time. Films & TV from IMDb's interests/keywords (keyless), Anime from AniList's tags. IMDb 7+ / AniList 70+,
 * watched titles left out. Off = no calls at all.
 */
object Rotation {
    private const val TAG = "Rotation"
    /** How many rotating rows a Home shows. */
    private const val ROWS = 15
    private const val ITEMS = 30

    /** A row idea. Films: IMDb interests (all of them) and/or keywords, type movie/tv; Anime: AniList tags/genres + extras. */
    data class Idea(
        val id: String, val title: String, val hub: Hub,
        val interests: List<String> = emptyList(), val keywords: List<String> = emptyList(), val type: String = "movie",
        val years: IntRange? = null, val maxMinutes: Int? = null, val minMinutes: Int? = null, val hidden: Boolean = false,
        val tags: List<String> = emptyList(), val genres: List<String> = emptyList(), val ani: String = ""
    )

    @Serializable data class SetRow(val id: String, val title: String, val items: List<MetaPreview>)
    @Serializable private data class Disk(val at: Long, val rows: List<SetRow>)

    private fun f(hub: Hub, slot: String) = File(App.instance.profileDir, "rotation/${hub.key}-$slot.json").apply { parentFile?.mkdirs() }
    private val locks = Hub.entries.associateWith { Mutex() }
    private val states = Hub.entries.associateWith { MutableStateFlow<List<SetRow>>(emptyList()) }
    private val promoted = HashSet<Hub>()
    private val building = HashSet<Hub>()

    fun on() = App.instance.prefs.rotation.value
    fun rows(hub: Hub): StateFlow<List<SetRow>> = states.getValue(hub)
    fun key(id: String) = "rot/$id"
    fun ideas(hub: Hub) = POOL.filter { it.hub == hub }

    /** Once per app start: the set built last time becomes the one shown (DebridStream's next → active), then shows it. */
    suspend fun load(hub: Hub): Unit = withContext(Dispatchers.IO) {
        if (!on()) { states.getValue(hub).value = emptyList(); return@withContext }
        locks.getValue(hub).withLock {
            if (promoted.add(hub)) {
                val next = f(hub, "next")
                if (next.length() > 2) { f(hub, "active").delete(); next.renameTo(f(hub, "active")) }
            }
            states.getValue(hub).value = read(f(hub, "active"))?.rows.orEmpty()
        }
    }

    private fun read(file: File) = runCatching { Net.json.decodeFromString(Disk.serializer(), file.readText()) }.getOrNull()

    /**
     * The next set, in the background. [now] = Shuffle now / first switch-on: the new set is shown at once instead of
     * waiting for the next start. Rows hidden by you, and the rows showing now, are left out of the pick.
     */
    suspend fun buildNext(hub: Hub, now: Boolean = false): Unit = withContext(Dispatchers.IO) {
        if (!on()) return@withContext
        synchronized(building) { if (!building.add(hub)) return@withContext }
        try {
            val hidden = Hubs.rowsHidden(hub).value.split('\n').filter { it.isNotEmpty() }.toSet()
            val showing = states.getValue(hub).value.map { it.id }.toSet()
            val pool = ideas(hub).filter { key(it.id) !in hidden }
            val fresh = pool.filter { it.id !in showing }.shuffled()
            val pick = (fresh + pool.filter { it.id in showing }.shuffled()).take(ROWS + 5)
            val watched = runCatching { if (hub == Hub.FILMS) App.instance.trakt.watchedIds() else null }.getOrNull().orEmpty()
            val out = ArrayList<SetRow>()
            for (idea in pick) {
                if (!on() || out.size >= ROWS) break
                val items = runCatching { if (hub == Hub.ANIME) anime(idea) else films(idea) }
                    .onFailure { Log.w(TAG, "${idea.id}: ${it.message}") }.getOrNull().orEmpty()
                    .filter { it.id !in watched && Hubs.keeps(hub, it) }
                if (items.size >= 8) out += SetRow(idea.id, idea.title, items.shuffled().take(ITEMS))
                // AniList allows ~30 requests a minute right now; IMDb is relaxed.
                delay(if (hub == Hub.ANIME) 2_500 else 600)
            }
            if (out.isEmpty()) return@withContext
            val disk = Disk(System.currentTimeMillis(), out)
            locks.getValue(hub).withLock {
                val text = Net.json.encodeToString(Disk.serializer(), disk)
                if (now) { f(hub, "active").writeText(text); f(hub, "next").delete(); states.getValue(hub).value = out }
                else f(hub, "next").writeText(text)
            }
        } finally { synchronized(building) { building.remove(hub) } }
    }

    /** Home asks: nothing on screen yet (first switch-on) → build and show now; else build the next set once per start. */
    suspend fun onHome(hub: Hub) {
        if (!on()) return
        load(hub)
        if (states.getValue(hub).value.isEmpty()) { delay(3_000); buildNext(hub, now = true); return }
        if (f(hub, "next").exists()) return
        delay(60_000)
        buildNext(hub)
    }

    /** Settings › Home rows › Shuffle now. */
    suspend fun shuffleNow(hub: Hub): Unit = buildNext(hub, now = true)

    /** Switched off: the sets are forgotten (switching on again builds a new one). */
    fun clear() {
        Hub.entries.forEach { h -> states.getValue(h).value = emptyList(); f(h, "active").delete(); f(h, "next").delete() }
    }

    // ---- Films & TV: IMDb advanced search (keyless) ----

    private fun films(i: Idea): List<MetaPreview> {
        val c = StringBuilder()
        if (i.interests.isNotEmpty()) c.append("interestConstraint: {allInterestIds: ${JSONArray(i.interests)}}, ")
        if (i.keywords.isNotEmpty()) c.append("keywordConstraint: {anyKeywords: ${JSONArray(i.keywords)}}, ")
        val types = if (i.type == "tv") "[\"tvSeries\", \"tvMiniSeries\"]" else "[\"movie\"]"
        c.append("titleTypeConstraint: {anyTitleTypeIds: $types}, ")
        val votes = if (i.hidden) "min: 5000, max: 40000" else if (i.type == "tv") "min: 5000" else "min: 15000"
        val minRating = if (i.hidden) 7.5 else 7.0
        c.append("userRatingsConstraint: {aggregateRatingRange: {min: $minRating}, ratingsCountRange: {$votes}}, ")
        val end = i.years?.let { "${it.last}-12-31" } ?: LocalDate.now().toString()
        val start = i.years?.let { "start: \"${it.first}-01-01\", " }.orEmpty()
        c.append("releaseDateConstraint: {releaseDateRange: {${start}end: \"$end\"}}, ")
        if (i.maxMinutes != null || i.minMinutes != null)
            c.append("runtimeConstraint: {runtimeRangeMinutes: {${listOfNotNull(i.minMinutes?.let { "min: $it" }, i.maxMinutes?.let { "max: $it" }).joinToString(", ")}}}, ")
        val q = "{ s: advancedTitleSearch(first: 120, constraints: {${c.toString().trimEnd(',', ' ')}}, sort: {sortBy: POPULARITY, sortOrder: ASC}) { $FIELDS } }"
        val d = Charts.post(Charts.IMDB_URL, JSONObject().put("query", q)) ?: return emptyList()
        val edges = d.optJSONObject("s")?.optJSONArray("edges") ?: return emptyList()
        val flat = JSONArray().apply { (0 until edges.length()).forEach { n -> edges.optJSONObject(n)?.optJSONObject("node")?.optJSONObject("title")?.let { put(JSONObject().put("node", it)) } } }
        return Charts.imdb(JSONObject().put("edges", flat), if (i.type == "tv") "series" else "movie")
    }

    private const val FIELDS = "edges { node { title { id titleText { text } releaseYear { year } ratingsSummary { aggregateRating } primaryImage { url } titleGenres { genres { genre { text } } } } } }"

    // ---- Anime: AniList (keyless; with your AniList sign-in, shows on your list are left out) ----

    private fun anime(i: Idea): List<MetaPreview> {
        val args = mutableListOf("type: ANIME", "isAdult: false", "averageScore_greater: ${if (i.hidden) 77 else 69}", "sort: POPULARITY_DESC")
        if (i.tags.isNotEmpty()) args += "tag_in: ${JSONArray(i.tags)}, minimumTagRank: 70"
        if (i.genres.isNotEmpty()) args += "genre_in: ${JSONArray(i.genres)}"
        if (i.hidden) args += "popularity_lesser: 40000, popularity_greater: 3000"
        if (i.ani.isNotEmpty()) args += i.ani
        val tk = App.instance.prefs.sp.getString("si_anilist_token", "").orEmpty()
        if (tk.isNotBlank()) args += "onList: false"
        val q = "{ p: Page(perPage: 50) { media(${args.joinToString(", ")}) { id format title { english userPreferred } coverImage { extraLarge large } " +
            "bannerImage description seasonYear averageScore genres } } }"
        val req = okhttp3.Request.Builder().url("https://graphql.anilist.co").header("Accept", "application/json")
            .apply { if (tk.isNotBlank()) header("Authorization", "Bearer $tk") }
            .post(JSONObject().put("query", q).toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = Net.http.newCall(req).execute().use { it.body?.string().orEmpty() }
        val d = JSONObject(text).optJSONObject("data") ?: return emptyList()
        return Charts.ani(d, "p")
    }

    // ---- The pool ----

    private fun I(vararg n: Int) = n.map { "in%07d".format(it) }
    private fun fl(id: String, title: String, vararg interests: Int, type: String = "movie", keywords: List<String> = emptyList(),
                   years: IntRange? = null, maxMinutes: Int? = null, minMinutes: Int? = null, hidden: Boolean = false) =
        Idea("f-$id", title, Hub.FILMS, I(*interests), keywords, type, years, maxMinutes, minMinutes, hidden)
    private fun an(id: String, title: String, tags: List<String> = emptyList(), genres: List<String> = emptyList(), ani: String = "", hidden: Boolean = false) =
        Idea("a-$id", title, Hub.ANIME, tags = tags, genres = genres, ani = ani, hidden = hidden)

    val POOL: List<Idea> = listOf(
        // Films
        fl("bank", "Top Bank Robbery Movies", keywords = listOf("bank-robbery")),
        fl("heist", "Pulling Off the Perfect Heist", 56),
        fl("caper", "Clever Capers", 50),
        fl("timeloop", "Stuck in a Time Loop", keywords = listOf("time-loop")),
        fl("timetravel", "Trips Through Time", 166),
        fl("twists", "Crazy Plot Twists", keywords = listOf("plot-twist")),
        fl("oneroom", "All in One Room", keywords = listOf("single-location")),
        fl("truestory", "Based on a True Story", keywords = listOf("based-on-true-story")),
        fl("survival", "Survival Against the Odds", 185),
        fl("vacation", "Vacations Gone Wrong", keywords = listOf("vacation")),
        fl("whodunnit", "Who Did It?", 141),
        fl("cozy", "Cozy Mysteries", 137),
        fl("hardboiled", "Hard-Boiled Detectives", 138),
        fl("psych", "Mind-Bending Thrillers", 182),
        fl("conspiracy", "Conspiracy Theories", 176),
        fl("spy", "Spies & Double Agents", 184),
        fl("serial", "On the Trail of a Killer", 183),
        fl("legal", "Courtroom Showdowns", 81),
        fl("prison", "Behind Bars", 85),
        fl("money", "Wall Street & Big Money", 78),
        fl("cyber", "Hackers & Cyber Thrillers", 177),
        fl("cyberpunk", "Neon Cyberpunk Futures", 159),
        fl("ai", "When the Machines Wake Up", 158),
        fl("aliens", "They Came From Outer Space", 157),
        fl("space", "Lost in Space", 164),
        fl("dystopia", "Dystopias", 160),
        fl("kaiju", "Giant Monsters Attack", 161),
        fl("disaster", "Disaster Strikes", 5),
        fl("onearmy", "One-Person Army", 7),
        fl("martial", "Martial Arts Mayhem", 6),
        fl("gunfu", "Gun Fu", 197),
        fl("cars", "Pedal to the Metal", 4),
        fl("warepic", "War Epics", 11),
        fl("roadtrip", "Road Trips", 20),
        fl("jungle", "Into the Jungle", 17),
        fl("sea", "Adventures at Sea", 21),
        fl("mountain", "Peaks & Summits", 18),
        fl("quest", "Epic Quests", 19),
        fl("buddycop", "Buddy Cop Comedies", 33),
        fl("buddy", "Buddy Comedies", 32),
        fl("darkcomedy", "Darkly Funny", 35),
        fl("mock", "Mockumentaries", 38),
        fl("quirky", "Quirky Indies", 40),
        fl("satire", "Sharp Satire", 42),
        fl("gangster", "Gangsters & Mobsters", 55),
        fl("drugs", "Cartels & Kingpins", 53),
        fl("noir", "Film Noir Nights", 54),
        fl("coming", "Coming of Age", 73),
        fl("period", "Period Pieces", 83),
        fl("biopic", "Remarkable Lives", 72),
        fl("feelgoodrom", "Feel-Good Romance", 151),
        fl("tragicrom", "Bring Tissues", 156),
        fl("romcom", "Romantic Comedies", 153),
        fl("western", "Saddle Up: Westerns", 191),
        fl("modernwest", "Modern Westerns", 188),
        fl("samurai", "Samurai Cinema", 199),
        fl("wuxia", "Wuxia Legends", 200),
        fl("sports", "Underdog Sports Stories", 174),
        fl("boxing", "In the Ring", 169),
        fl("folk", "Folk Horror", 110),
        fl("monster", "Creature Features", 113),
        fl("zombie", "Zombie Apocalypse", 122),
        fl("slasher", "Slasher Nights", 115),
        fl("supernatural", "Things That Go Bump", 117),
        fl("psychhorror", "Psychological Horror", 114),
        fl("darkfantasy", "Dark Fantasy", 95),
        fl("fairytale", "Fairy Tales Retold", 97),
        fl("korean", "Korean Thrillers", 225, 186),
        fl("french", "French Cinema", 219),
        fl("spanish", "Spanish-Language Gems", 233),
        fl("hindi", "Bollywood Favourites", 222),
        fl("japanese", "Japanese Cinema", 224, 76),
        fl("90s", "90s Classics", years = 1990..1999),
        fl("80s", "Totally 80s", years = 1980..1989),
        fl("2000s", "Old But Fresh", years = 2000..2009),
        fl("classics", "Golden Age Classics", years = 1930..1969),
        fl("short", "Under 90 Minutes", maxMinutes = 90),
        fl("epic", "Epic Watches (3 Hours+)", minMinutes = 175),
        fl("gems", "Hidden Gems", hidden = true),
        fl("naturedoc", "Planet Earth & Beyond", 67),
        fl("musicdoc", "Music Documentaries", 66),
        fl("sportsdoc", "Sports Documentaries", 70),
        fl("crimedoc", "True Crime Documentaries", 59),
        // TV
        fl("tv-cop", "Bingeworthy Cop Shows", 51, type = "tv"),
        fl("tv-medical", "Medical Dramas", 82, type = "tv"),
        fl("tv-legal", "Legal Dramas", 81, type = "tv"),
        fl("tv-sitcom", "Sitcoms to Binge", 44, type = "tv"),
        fl("tv-workplace", "Workplace Dramas", 91, type = "tv"),
        fl("tv-kdrama", "K-Dramas", 209, type = "tv"),
        fl("tv-truecrime", "True Crime Series", 58, type = "tv"),
        fl("tv-docu", "Docuseries", 61, type = "tv"),
        fl("tv-scifi", "Sci-Fi Series", 162, type = "tv"),
        fl("tv-fantasy", "Fantasy Series", 98, type = "tv"),
        fl("tv-political", "Power & Politics", 84, type = "tv"),
        fl("tv-spy", "Spy Series", 184, type = "tv"),
        fl("tv-whodunnit", "Mystery Series", 141, type = "tv"),
        fl("tv-teen", "Teen Dramas", 89, type = "tv"),
        fl("tv-period", "Period Dramas", 83, type = "tv"),
        fl("tv-horror", "Horror Series", 112, type = "tv"),
        fl("tv-dark", "Dark Comedy Series", 35, type = "tv"),
        fl("tv-cooking", "Cooking Competitions", 103, type = "tv"),
        fl("tv-gems", "Hidden Gem Series", type = "tv", hidden = true),
        // Anime
        an("isekai", "Reborn in Another World", listOf("Isekai")),
        an("reincarnation", "Second Lives", listOf("Reincarnation")),
        an("timeloop", "Stuck in a Time Loop", listOf("Time Loop", "Time Manipulation")),
        an("revenge", "Revenge Stories", listOf("Revenge")),
        an("family", "Found Family", listOf("Found Family")),
        an("deathgame", "Deadly Games", listOf("Death Game", "Battle Royale")),
        an("survival", "Survive at All Costs", listOf("Survival")),
        an("psych", "Mind Games", genres = listOf("Psychological")),
        an("iyashikei", "Healing Slice of Life", listOf("Iyashikei")),
        an("mecha", "Giant Robots", genres = listOf("Mecha")),
        an("sports", "Sports Anime", genres = listOf("Sports")),
        an("martial", "Martial Arts", listOf("Martial Arts")),
        an("swords", "Blades & Swordplay", listOf("Swordplay")),
        an("samurai", "Samurai & Ninja", listOf("Samurai", "Ninja")),
        an("vampire", "Vampires & Demons", listOf("Vampire", "Demons")),
        an("mafia", "Yakuza & Mafia", listOf("Mafia")),
        an("detective", "Anime Detectives", listOf("Detective")),
        an("cyberpunk", "Cyberpunk", listOf("Cyberpunk")),
        an("space", "Space Opera", listOf("Space Opera", "Space")),
        an("apocalypse", "After the End", listOf("Post-Apocalyptic")),
        an("vr", "Trapped in a Game", listOf("Virtual World", "Video Games")),
        an("powers", "Super Powers", listOf("Super Power")),
        an("magic", "Magic & Sorcery", listOf("Magic")),
        an("myth", "Gods & Myths", listOf("Mythology", "Gods")),
        an("dragons", "Here Be Dragons", listOf("Dragons")),
        an("kingdom", "Rulers & Kingdoms", listOf("Kingdom Management")),
        an("antihero", "Anti-Heroes", listOf("Anti-Hero")),
        an("villainess", "Villainess Reborn", listOf("Villainess")),
        an("tragedy", "Bring Tissues", listOf("Tragedy")),
        an("coming", "Coming of Age", listOf("Coming of Age")),
        an("food", "Food Anime", listOf("Food")),
        an("music", "Bands & Music", genres = listOf("Music")),
        an("idol", "Idols on Stage", listOf("Idol")),
        an("military", "War & Military", listOf("Military", "War")),
        an("spy", "Spies & Assassins", listOf("Espionage", "Assassins")),
        an("gambling", "High Stakes Gambling", listOf("Gambling")),
        an("horror", "Anime Horror", genres = listOf("Horror")),
        an("romcom", "Romcoms", genres = listOf("Comedy"), ani = "genre: \"Romance\""),
        an("triangle", "Love Triangles", listOf("Love Triangle")),
        an("adults", "Grown-Up Anime", listOf("Primarily Adult Cast")),
        an("seinen", "Seinen Essentials", listOf("Seinen")),
        an("shoujo", "Shoujo Favourites", listOf("Shoujo")),
        an("josei", "Josei Picks", listOf("Josei")),
        an("90s", "90s Classics", ani = "startDate_greater: 19900000, startDate_lesser: 19991231"),
        an("2000s", "2000s Throwbacks", ani = "startDate_greater: 20000000, startDate_lesser: 20091231"),
        an("movies", "Anime Films for Tonight", ani = "format: MOVIE"),
        an("short", "Short Binges (13 Episodes or Less)", ani = "episodes_lesser: 14, format_in: [TV, ONA]"),
        an("long", "Long Journeys (100+ Episodes)", ani = "episodes_greater: 99"),
        an("gems", "Hidden Gems", hidden = true),
        an("surreal", "Wonderfully Weird", listOf("Surreal Comedy", "Denpa")),
        an("school", "School Life", listOf("School Club", "School")),
        an("elderly", "Old Heroes", listOf("Elderly Protagonist")),
        an("cooking", "Kitchen & Restaurant", listOf("Restaurant", "Food")),
    )
}
