// Scout's engine, all of it (Nuvio Arc core/scout, user 2026-11-24: "the Scout engine plus our engine as one driving
// force"): every slice (moods, decades, countries, regions, languages, lengths, themes, acclaim, awards, genres), the
// region rule (made there only; co-productions that name the region first only when too few), Ask Scout (watch ·
// mood · length · family · tune), Build your own (one choice per group, ONE combined TMDB search), ready-made mixes
// and the occasion episode lists. Scout FINDS (its TMDB filters), Lite's engine RANKS (your taste: the engine's own
// picks first, then your genres and countries) and KEEPS OUT (watched on Trakt, saved, turned down, Leave out, adult,
// your IMDb minimum, the hub).
// Cost: nothing at app start; a page asks TMDB only when its row opens (1 request a row-page). Awards: 57 KB bundled,
// read once on first use; each award title is one TMDB details request, 20 at a time as you scroll.
package com.lekathedon.visionlite.recs

import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.addons.MetaPreview
import com.lekathedon.visionlite.anime.Hub
import com.lekathedon.visionlite.anime.Hubs
import com.lekathedon.visionlite.home.Tmdb
import com.lekathedon.visionlite.home.objects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** One TMDB discover search (Scout's TmdbCollectionFilters). Null = not asked. */
data class ScoutQ(
    val genres: String? = null, val without: String? = null, val keywords: String? = null, val withoutKeywords: String? = null,
    val average: Double? = null, val votes: Int? = null, val votesMax: Int? = null, val from: String? = null, val to: String? = null,
    val country: String? = null, val language: String? = null, val runtimeMax: Int? = null, val runtimeMin: Int? = null,
    val type: String? = null, val status: String? = null, val certCountry: String? = null, val certLte: String? = null,
) {
    fun query(tv: Boolean): String = buildList {
        fun enc(s: String) = s.replace("|", "%7C")
        genres?.let { add("with_genres=${enc(it)}") }; without?.let { add("without_genres=${enc(it)}") }
        keywords?.let { add("with_keywords=${enc(it)}") }; withoutKeywords?.let { add("without_keywords=${enc(it)}") }
        average?.let { add("vote_average.gte=$it") }; votes?.let { add("vote_count.gte=$it") }; votesMax?.let { add("vote_count.lte=$it") }
        val d = if (tv) "first_air_date" else "primary_release_date"
        from?.let { add("$d.gte=$it") }; to?.let { add("$d.lte=$it") }
        country?.let { add("with_origin_country=${enc(it)}") }; language?.let { add("with_original_language=${enc(it)}") }
        runtimeMax?.let { add("with_runtime.lte=$it") }; runtimeMin?.let { add("with_runtime.gte=$it") }
        if (tv) { type?.let { add("with_type=$it") }; status?.let { add("with_status=$it") } }
        certCountry?.let { add("certification_country=$it") }; certLte?.let { add("certification.lte=$it") }
    }.joinToString("&")

    /**
     * Two choices at once (Build your own): both must hold. Genres and keywords AND together (TMDB: "," = all of);
     * a choice of genres inside one ("a|b") narrows to its first, as Scout does (TMDB can't say "x and (a or b)").
     * The looser vote floor and rating win, so a narrow combination still finds titles.
     */
    fun and(o: ScoutQ): ScoutQ {
        fun all(a: String?, b: String?): String? = when {
            a == null -> b; b == null -> a
            else -> (a.split(',').map { it.substringBefore('|') } + b.split(',').map { it.substringBefore('|') }).filter { it.isNotBlank() }.distinct().joinToString(",")
        }
        fun anyOf(a: String?, b: String?) = listOfNotNull(a, b).flatMap { it.split(',', '|') }.filter { it.isNotBlank() }.distinct().joinToString(",").ifBlank { null }
        fun anyWords(a: String?, b: String?) = listOfNotNull(a, b).flatMap { it.split(',', '|') }.filter { it.isNotBlank() }.distinct().joinToString("|").ifBlank { null }
        val g = all(genres, o.genres)
        val out = anyOf(without, o.without)?.split(',')?.filter { it !in g.orEmpty().split(',') }?.joinToString(",")?.ifBlank { null }
        return ScoutQ(
            genres = g, without = out, keywords = all(keywords, o.keywords), withoutKeywords = anyWords(withoutKeywords, o.withoutKeywords),
            average = listOfNotNull(average, o.average).minOrNull(), votes = listOfNotNull(votes, o.votes).minOrNull(),
            votesMax = listOfNotNull(votesMax, o.votesMax).minOrNull(),
            from = listOfNotNull(from, o.from).maxOrNull(), to = listOfNotNull(to, o.to).minOrNull(),
            country = country ?: o.country, language = language ?: o.language,
            runtimeMax = listOfNotNull(runtimeMax, o.runtimeMax).minOrNull(), runtimeMin = listOfNotNull(runtimeMin, o.runtimeMin).maxOrNull(),
            type = type ?: o.type, status = status ?: o.status, certCountry = certCountry ?: o.certCountry, certLte = certLte ?: o.certLte,
        )
    }

    /** When too little fits: the thresholds give way before the choices do (Scout's scoutRelaxed). */
    fun relaxed() = copy(average = average?.let { minOf(it, 6.0) }, votes = votes?.let { maxOf(10, it / 5) })
}

enum class ScoutGroup(val label: String) {
    MOOD("Mood"), DECADE("Decade"), COUNTRY("Country"), REGION("Region"), LANGUAGE("Language"), LENGTH("Length"),
    THEME("Theme"), ACCLAIM("Acclaim"), GENRE("Genre")
}

/** An award page's category (Best Picture, Director…): the bundled lists it reads. */
data class ScoutAwardTab(val label: String, val awards: List<String>)
data class ScoutAward(val awards: List<String>, val winners: Boolean = true, val tabs: List<ScoutAwardTab> = emptyList())

/** One way to slice the catalogue. A side left null = that slice has only the other kind. */
data class ScoutSlice(
    val id: String, val group: ScoutGroup, val title: String,
    val movie: ScoutQ? = null, val tv: ScoutQ? = null, val sort: String = POPULAR, val award: ScoutAward? = null,
    /** Region slices: titles made there only (Scout's scoutFromRegion). */
    val regionOnly: Set<String>? = null,
)

private const val POPULAR = "popularity.desc"
private const val TOP = "vote_average.desc"
private const val NOT_SHOWS_PEOPLE_MEAN = "10763,10764,10766,10767"
private const val NOT_DOCUMENTARY = "35"
private const val MIN_VOTES_MOVIE = 300
private const val MIN_VOTES_TV = 100
private const val GEM_MAX = 5000
private const val GEM_MAX_TV = 2000
private const val RECAPS = "12197|194008"
/** Below this many region-only titles in a page, co-productions that list the region first are let back in. */
private const val REGION_FEWEST = 8

private const val FEEL_GOOD_WORDS = "383896|319357|334465|325782|325784|335803|288816"
private const val LAUGH_WORDS = "320420|386408|319397"
private const val EDGE_WORDS = "314730|321464|316832|353406|288394"
private const val MIND_WORDS = "362567|9887|33465|170351|334469|3972|10854"
private const val DARK_WORDS = "259094|230747|286125|361070"
private const val SCARY_WORDS = "315058|256183|3358|12339|295907|283085|385522"
private const val ROMANTIC_WORDS = "324429|9840"
private const val ROMANTIC_SERIES_WORDS = "324429|9840|386551|304976"
private const val EPIC_WORDS = "377433|211227|161257|161176|192772|2035|14643|234213"
private const val TEARS_WORDS = "276222|156924|370245|9872|6564|253411"
private const val SLOW_WORDS = "277551|228988|197056|343508|155800"
private const val SLOW_SERIES_WORDS = "277551|228988|343508|155800"
private const val WEIRD_WORDS = "9887|3307|210710|312814|362252"

private fun q(genres: String? = null, without: String? = null, keywords: String? = null, withoutKeywords: String? = null,
              average: Double = 6.5, votes: Int = MIN_VOTES_MOVIE, votesMax: Int? = null, from: String? = null, to: String? = null,
              country: String? = null, language: String? = null, runtimeMax: Int? = null, runtimeMin: Int? = null) =
    ScoutQ(genres, without, keywords, withoutKeywords, average, votes, votesMax, from, to, country, language, runtimeMax, runtimeMin)

object ScoutSlices {
    private fun s(id: String, g: ScoutGroup, title: String, m: ScoutQ?, t: ScoutQ?, sort: String = POPULAR) = ScoutSlice(id, g, title, m, t, sort)
    private fun both(words: String, avg: Double = 6.3) = q(keywords = words, average = avg, votes = 100) to q(keywords = words, average = avg, votes = 30)
    private fun decade(y: Int) = ScoutSlice("decade_$y", ScoutGroup.DECADE, "${y}s",
        q(from = "$y-01-01", to = "${y + 9}-12-31", average = 6.8), q(from = "$y-01-01", to = "${y + 9}-12-31", average = 6.8, votes = MIN_VOTES_TV))
    private fun country(code: String, name: String = Locale("", code).getDisplayCountry(Locale.ENGLISH)) =
        ScoutSlice("country_$code", ScoutGroup.COUNTRY, name, q(country = code, votes = 150), q(country = code, votes = 50), regionOnly = setOf(code))
    private fun region(key: String, name: String, codes: String) =
        ScoutSlice("region_$key", ScoutGroup.REGION, name, q(country = codes, votes = 100), q(country = codes, votes = 20), regionOnly = codes.split('|').toSet())
    private fun language(code: String) = ScoutSlice("language_$code", ScoutGroup.LANGUAGE,
        Locale(code).getDisplayLanguage(Locale.ENGLISH).replaceFirstChar { it.titlecase() },
        q(language = code, votes = 150, withoutKeywords = if (code == "ja") RECAPS else null), q(language = code, votes = 50))
    private fun genre(id: Int, name: String, tv: String?) = ScoutSlice("genre_$id", ScoutGroup.GENRE, name,
        q(genres = id.toString(), average = 6.8), tv?.let { q(genres = it, average = 7.0, votes = MIN_VOTES_TV) })
    private fun award(id: String, name: String, vararg awards: String, winners: Boolean = true, tabs: List<ScoutAwardTab> = emptyList()) =
        ScoutSlice("award_$id", ScoutGroup.ACCLAIM, name, award = ScoutAward(awards.toList(), winners, tabs))
    private fun tab(label: String, vararg a: String) = ScoutAwardTab(label, a.toList())

    private val OSCAR = listOf(tab("Best Picture", "oscar_best_picture"), tab("Director", "oscar_director"), tab("Actor", "oscar_actor"),
        tab("Actress", "oscar_actress"), tab("Animation", "oscar_animated"), tab("International", "oscar_international"), tab("Documentary", "oscar_documentary"))
    private val BAFTA = listOf(tab("Best Film", "bafta_best_film"), tab("Director", "bafta_director"), tab("Actor", "bafta_actor"),
        tab("Actress", "bafta_actress"), tab("Animation", "bafta_animated"), tab("International", "bafta_international"), tab("Documentary", "bafta_documentary"))
    private val GLOBE = listOf(tab("Best Picture", "golden_globe_drama", "golden_globe_comedy"), tab("Director", "globe_director"),
        tab("Actor", "globe_actor_drama", "globe_actor_comedy"), tab("Actress", "globe_actress_drama", "globe_actress_comedy"),
        tab("Animation", "globe_animated"), tab("International", "globe_international"))
    private val EMMY = listOf(tab("Drama", "emmy_drama"), tab("Comedy", "emmy_comedy"), tab("Limited Series", "emmy_limited"))

    val all: List<ScoutSlice> = listOf(
        s("mood_feel_good", ScoutGroup.MOOD, "Feel-good", q(genres = "35|10751|16|10749", keywords = FEEL_GOOD_WORDS, without = "27,53,80,9648,10752", votes = 100), q(genres = "35|10751", keywords = FEEL_GOOD_WORDS, without = "80,9648,10759,10768", votes = 30)),
        s("mood_laugh", ScoutGroup.MOOD, "Laugh out loud", q(genres = "35", keywords = LAUGH_WORDS, without = "18,27", votes = 100), q(genres = "35", keywords = LAUGH_WORDS, without = "18", votes = 30)),
        s("mood_action", ScoutGroup.MOOD, "Action-packed", q(genres = "28", average = 6.8, votes = 500), q(genres = "10759", without = "16,10751", average = 7.2, votes = 100)),
        s("mood_edge", ScoutGroup.MOOD, "Edge of your seat", q(genres = "53|80|9648", keywords = EDGE_WORDS, without = "27,35,10751,16", votes = 100), q(genres = "80|9648", keywords = EDGE_WORDS, without = "35,16,10751,10765", votes = 30)),
        s("mood_mind", ScoutGroup.MOOD, "Mind-bending", q(genres = "878|9648|53", keywords = MIND_WORDS, without = "27,16,10751", average = 7.0, votes = 100), q(genres = "10765|9648", keywords = MIND_WORDS, without = "16,10751,35,80,10759", average = 7.0, votes = 30)),
        s("mood_dark", ScoutGroup.MOOD, "Dark and gritty", q(genres = "80", without = "35,10751,16,10749,878,14,12", average = 7.0, votes = 500), q(genres = "80|18", keywords = DARK_WORDS, without = "35,10751,16,10765", votes = 30)),
        s("mood_scary", ScoutGroup.MOOD, "Scary", q(genres = "27", average = 6.3, votes = 200), q(keywords = SCARY_WORDS, without = "35,16,10751", withoutKeywords = "4565", average = 7.0, votes = 30)),
        s("mood_romantic", ScoutGroup.MOOD, "Romantic", q(genres = "10749", keywords = ROMANTIC_WORDS, votes = 100), q(keywords = ROMANTIC_SERIES_WORDS, without = "16,80,9648,10759", average = 7.0, votes = 30)),
        s("mood_epic", ScoutGroup.MOOD, "Epic", q(genres = "12|14|36|10752", keywords = EPIC_WORDS, without = "16,10751,35", average = 7.2, votes = 500), q(genres = "10765|10768|10759", keywords = EPIC_WORDS, without = "16,35,9648,80", average = 7.5, votes = 100)),
        s("mood_tears", ScoutGroup.MOOD, "Tear-jerker", q(genres = "18", keywords = TEARS_WORDS, without = "35,53,27,28,80,878,14", average = 7.0, votes = 100), q(genres = "18", keywords = TEARS_WORDS, without = "35,16,80,9648,10759,10768,10765", votes = 30)),
        s("mood_slow", ScoutGroup.MOOD, "Slow burn", q(genres = "18", keywords = SLOW_WORDS, without = "28,35,16,10751", average = 7.2, votes = 100), q(genres = "18", keywords = SLOW_SERIES_WORDS, without = "35,16,10751,10759,10768", average = 7.0, votes = 30)),
        s("mood_weird", ScoutGroup.MOOD, "Weird and wonderful", q(genres = "35|878|14|18", keywords = WEIRD_WORDS, without = "27,10751,16", average = 6.8, votes = 100), q(keywords = WEIRD_WORDS, without = "10751,10762", average = 7.0, votes = 30)),
        decade(2020), decade(2010), decade(2000), decade(1990), decade(1980), decade(1970), decade(1960), decade(1950),
        country("US", "United States"), country("GB", "United Kingdom"), country("AU"), country("KR", "South Korea"), country("JP"), country("FR"),
        country("DE"), country("IT"), country("ES"), country("IN"), country("CN"), country("SE"), country("DK"), country("MX"), country("CA"),
        country("HK", "Hong Kong"), country("TR"), country("EG"),
        region("uk_ireland", "UK and Ireland", "GB|IE"),
        region("europe", "Europe", "FR|DE|IT|ES|PT|NL|BE|AT|CH|SE|NO|DK|FI|IS|PL|CZ|HU|RO|GR"),
        region("nordic", "Nordic", "SE|NO|DK|FI|IS"),
        region("east_asia", "East Asia", "JP|KR|CN|TW|HK"),
        region("south_asia", "South Asia", "IN|PK|BD|LK|NP|BT|MV|AF"),
        region("south_east_asia", "South-East Asia", "TH|ID|PH|MY|SG|VN"),
        region("middle_east", "Middle East", "TR|IL|IR|SA|AE|EG|LB|JO"),
        region("arab", "Arab world", "EG|SA|AE|LB|SY|JO|KW|QA|MA|TN|IQ|BH|OM"),
        region("africa", "Africa", "NG|ZA|KE|GH|EG|MA|SN"),
        region("latin_america", "Latin America", "MX|BR|AR|CO|CL|PE|CU"),
        region("australia_nz", "Australia and NZ", "AU|NZ"),
        language("en"), language("ko"), language("ja"), language("fr"), language("es"), language("de"), language("it"),
        language("hi"), language("zh"), language("pt"), language("ru"), language("sv"), language("da"), language("tr"), language("ar"),
        s("length_90", ScoutGroup.LENGTH, "Under 90 minutes", q(runtimeMax = 90, average = 6.5), null),
        s("length_120", ScoutGroup.LENGTH, "Under 2 hours", q(runtimeMax = 120, runtimeMin = 60, average = 7.0), null),
        s("length_long", ScoutGroup.LENGTH, "Long films", q(runtimeMin = 150, average = 7.0), null),
        s("length_quick_episode", ScoutGroup.LENGTH, "Quick episodes", null, q(runtimeMax = 30, average = 7.0, votes = MIN_VOTES_TV)),
        ScoutSlice("complete_series", ScoutGroup.LENGTH, "Complete series", null, ScoutQ(average = 7.0, votes = MIN_VOTES_TV, status = "3")),
        s("theme_true", ScoutGroup.THEME, "True stories", both("9672").first, both("9672").second),
        s("theme_book", ScoutGroup.THEME, "Based on a book", both("818", 7.0).first, both("818", 7.0).second),
        s("theme_time", ScoutGroup.THEME, "Time travel", both("4379").first, both("4379").second),
        s("theme_heist", ScoutGroup.THEME, "Heists", both("10051").first, both("10051").second),
        s("theme_survival", ScoutGroup.THEME, "Survival", both("10349").first, both("10349").second),
        s("theme_court", ScoutGroup.THEME, "Courtroom", both("33519|214780").first, both("33519|214780").second),
        s("theme_whodunit", ScoutGroup.THEME, "Whodunnits", both("12570").first, both("12570").second),
        s("theme_dystopia", ScoutGroup.THEME, "Dystopia", both("4565|4458").first, both("4565|4458").second),
        s("theme_ai", ScoutGroup.THEME, "Artificial intelligence", both("310").first, both("310").second),
        s("theme_space", ScoutGroup.THEME, "Space", both("3801").first, both("3801").second),
        s("theme_coming_of_age", ScoutGroup.THEME, "Coming of age", both("10683").first, both("10683").second),
        s("theme_road", ScoutGroup.THEME, "Road trips", both("7312").first, both("7312").second),
        s("theme_superhero", ScoutGroup.THEME, "Superheroes", both("9715").first, both("9715").second),
        s("theme_zombie", ScoutGroup.THEME, "Zombies", both("12377").first, both("12377").second),
        s("theme_spy", ScoutGroup.THEME, "Spies", both("470").first, both("470").second),
        s("theme_miniseries", ScoutGroup.THEME, "Miniseries", null, q(keywords = "11162", average = 7.2, votes = 50)),
        s("theme_sitcom", ScoutGroup.THEME, "Sitcoms", null, q(keywords = "193171", average = 7.0, votes = 50)),
        genre(28, "Action", "10759"), genre(12, "Adventure", "10759"), genre(16, "Animation", "16"), genre(35, "Comedy", "35"),
        genre(80, "Crime", "80"), genre(99, "Documentary", "99").let { it.copy(movie = it.movie?.copy(without = NOT_DOCUMENTARY), tv = it.tv?.copy(without = NOT_DOCUMENTARY)) },
        genre(18, "Drama", "18"), genre(10751, "Family", "10751"), genre(14, "Fantasy", "10765"), genre(36, "History", null),
        genre(27, "Horror", null), genre(10402, "Music", null), genre(9648, "Mystery", "9648"), genre(10749, "Romance", null),
        genre(878, "Sci-fi", "10765"), genre(53, "Thriller", "80|9648"), genre(10752, "War", "10768"), genre(37, "Western", "37"),
        award("oscar_winners", "Oscar winners", "oscar_best_picture", tabs = OSCAR),
        award("oscar_nominees", "Oscar nominees", "oscar_best_picture", winners = false, tabs = OSCAR),
        award("palme", "Palme d'Or", "palme_dor"), award("lion", "Golden Lion", "golden_lion"),
        award("bafta", "BAFTA", "bafta_best_film", tabs = BAFTA), award("bafta_nominees", "BAFTA nominees", "bafta_best_film", winners = false, tabs = BAFTA),
        award("globes", "Golden Globes", "golden_globe_drama", "golden_globe_comedy", tabs = GLOBE),
        award("globes_nominees", "Golden Globe nominees", "golden_globe_drama", "golden_globe_comedy", winners = false, tabs = GLOBE),
        award("emmy", "Emmys", "emmy_drama", "emmy_comedy", tabs = EMMY), award("emmy_nominees", "Emmy nominees", "emmy_drama", "emmy_comedy", winners = false, tabs = EMMY),
        award("actor", "Actor Awards", "actor_award_cast"), award("criterion", "Criterion Collection", "criterion_collection"),
        s("acclaim_top", ScoutGroup.ACCLAIM, "Top rated", q(average = 8.0, votes = 5000), q(average = 8.3, votes = 1000), TOP),
        s("acclaim_gems", ScoutGroup.ACCLAIM, "Hidden gems", q(average = 7.5, votes = 200, votesMax = GEM_MAX), q(average = 8.0, votes = 80, votesMax = GEM_MAX_TV), TOP),
        s("acclaim_cult", ScoutGroup.ACCLAIM, "Cult classics", q(average = 7.2, votes = 1000, to = "1999-12-31"), q(average = 7.8, votes = 200, to = "2005-12-31"), TOP),
        s("acclaim_crowd", ScoutGroup.ACCLAIM, "Crowd favourites", q(average = 7.0, votes = 10000), q(average = 7.5, votes = 3000), "vote_count.desc"),
    ).map { sl -> sl.copy(tv = sl.tv?.let { f -> f.copy(without = listOfNotNull(f.without, NOT_SHOWS_PEOPLE_MEAN).joinToString(",")) }) }

    private val byId = all.associateBy { it.id }
    fun byId(id: String): ScoutSlice? = byId[id] ?: ScoutAsk.decode(id)?.slice() ?: ScoutBuild.decode(id)?.slice()
    fun inGroup(g: ScoutGroup) = all.filter { it.group == g }

    /** Scout's genre ids mapped from IMDb's genre names (for your taste). */
    val TMDB_GENRE = mapOf("Action" to 28, "Adventure" to 12, "Animation" to 16, "Comedy" to 35, "Crime" to 80, "Documentary" to 99,
        "Drama" to 18, "Family" to 10751, "Fantasy" to 14, "History" to 36, "Horror" to 27, "Music" to 10402, "Mystery" to 9648,
        "Romance" to 10749, "Sci-Fi" to 878, "Thriller" to 53, "War" to 10752, "Western" to 37)
}

// ------------------------------------------------------------------------------------------ Ask Scout

enum class AskWatch(val code: String, val label: String) { ANYTHING("a", "Anything"), MOVIE("m", "Movie"), SERIES("s", "Series"), ANIME("n", "Anime"), DOCS("d", "Documentary") }

enum class AskMood(val code: String, val label: String, val sliceId: String? = null, val keywords: String? = null) {
    FEEL_GOOD("fg", "Feel-good", "mood_feel_good"), LAUGH("la", "Laugh out loud", "mood_laugh"), ACTION("ac", "Action-packed", "mood_action"),
    EDGE("ed", "Edge of your seat", "mood_edge"), MIND("mi", "Mind-bending", "mood_mind"), DARK("da", "Dark and gritty", "mood_dark"),
    SCARY("sc", "Scary", "mood_scary"), ROMANTIC("ro", "Romantic", "mood_romantic"), EPIC("ep", "Epic", "mood_epic"),
    TEARS("te", "Tear-jerker", "mood_tears"), SLOW("sl", "Slow burn", "mood_slow"), WEIRD("we", "Weird and wonderful", "mood_weird"),
    SHOUNEN("as", "Shounen action", keywords = "207826"), ISEKAI("ai", "Isekai", keywords = "237451"), SLICE_OF_LIFE("al", "Slice of life", keywords = "9914"),
    MECHA("am", "Mecha", keywords = "10046"), PSYCHOLOGICAL("ap", "Psychological", keywords = "272553|12565"), MAGICAL_GIRL("ag", "Magical girl", keywords = "292887"),
    SPORT_ANIME("ab", "Sport", keywords = "6075"), DARK_FANTASY("ad", "Dark fantasy", keywords = "177895"), SAMURAI("au", "Samurai", keywords = "1462"),
    TRUE_CRIME("dc", "True crime", keywords = "33722"), NATURE("dn", "Nature", keywords = "18330|221355|9902"), HISTORY("dh", "History", keywords = "282633|5565"),
    SPACE("ds", "Space and science", keywords = "9882|3801"), FOOD("df", "Food", keywords = "10637"), MUSIC("dm", "Music", keywords = "283297"),
    SPORT("dp", "Sport", keywords = "333328"), WAR("dw", "War", keywords = "273967"), SCIENCE("dx", "Science", keywords = "287067|1576"),
    TRAVEL("dt", "Travel", keywords = "9935"), ART("dr", "Art", keywords = "14893"), POLITICS("dl", "Politics", keywords = "6078"),
    MAGICAL("fm", "Magical", keywords = "2343");

    companion object {
        private val GENERAL = listOf(FEEL_GOOD, LAUGH, ACTION, EDGE, MIND, DARK, SCARY, ROMANTIC, EPIC, TEARS, SLOW, WEIRD)
        private val FAMILY = listOf(FEEL_GOOD, LAUGH, ACTION, EDGE, MIND, DARK, MAGICAL, ROMANTIC, EPIC, TEARS, SLOW, WEIRD)
        private val ANIME = listOf(FEEL_GOOD, LAUGH, SHOUNEN, ISEKAI, SLICE_OF_LIFE, MECHA, PSYCHOLOGICAL, ROMANTIC, MAGICAL_GIRL, SPORT_ANIME, DARK_FANTASY, SAMURAI)
        private val TOPICS = listOf(TRUE_CRIME, NATURE, HISTORY, SPACE, FOOD, MUSIC, SPORT, WAR, SCIENCE, TRAVEL, ART, POLITICS)
        fun choicesFor(w: AskWatch, family: Boolean) = when (w) { AskWatch.ANIME -> ANIME; AskWatch.DOCS -> TOPICS; else -> if (family) FAMILY else GENERAL }
    }
}

enum class AskTime(val code: String, val label: String, val only: Boolean? = null /* true = films only, false = series only */) {
    ANY("a", "Any length"), SHORT("s", "Short"), LONG("l", "Long"), UNDER_90("u", "Under 90 min", true), UNDER_120("v", "Under 2 hours", true),
    LONG_FILM("g", "Long film", true), QUICK("q", "Quick episodes", false), HOUR("h", "About an hour", false), WEEKEND("w", "Finish in a weekend", false),
    COMPLETE("c", "Complete series", false), ANIME_SERIES("n", "Anime series", false), ANIME_FILM("f", "Anime film", true),
    SHORT_SERIES("r", "Short series", false), FEATURE("t", "Feature-length", true), DOC_SERIES("d", "Doc series", false), MINISERIES("m", "Miniseries", false);

    companion object {
        fun choicesFor(w: AskWatch) = when (w) {
            AskWatch.ANYTHING -> listOf(ANY, SHORT, LONG); AskWatch.MOVIE -> listOf(ANY, UNDER_90, UNDER_120, LONG_FILM)
            AskWatch.SERIES -> listOf(ANY, QUICK, HOUR, WEEKEND, COMPLETE); AskWatch.ANIME -> listOf(ANY, ANIME_SERIES, ANIME_FILM, SHORT_SERIES, COMPLETE)
            AskWatch.DOCS -> listOf(ANY, UNDER_90, FEATURE, DOC_SERIES, MINISERIES)
        }
    }
}

enum class AskTune(val code: String, val label: String) {
    LIGHTER("l", "Lighter"), DARKER("r", "Darker"), NEWER("n", "Newer"), OLDER("o", "Older"), BETTER("b", "Better rated"),
    GEMS("k", "Hidden gems"), INTERNATIONAL("a", "International"), FROM_COUNTRY("c", "From my country")
}

/** Ask Scout: what to watch · mood · length · family night · tune it. Scout's ScoutAnswers, as is. */
data class ScoutAsk(val watch: AskWatch = AskWatch.ANYTHING, val mood: AskMood? = null, val time: AskTime = AskTime.ANY,
                    val family: Boolean = false, val tunes: Set<AskTune> = emptySet()) {
    fun encode() = listOf("q", watch.code, mood?.code ?: "x", time.code, if (family) "f" else "x", tunes.map { it.code }.sorted().joinToString("")).joinToString(".")
    fun title() = buildList {
        mood?.let { add(it.label) }
        if (watch != AskWatch.ANYTHING) add(watch.label)
        if (time != AskTime.ANY) add(time.label)
        if (family) add("Family night")
        AskTune.entries.filter { it in tunes }.forEach { add(it.label) }
        if (isEmpty()) add("Anything")
    }.joinToString(" • ")

    fun slice(today: LocalDate = LocalDate.now(), country: String = Locale.getDefault().country): ScoutSlice {
        val base = mood?.sliceId?.let { ScoutSlices.byId(it) }
        val movie = if (watch == AskWatch.SERIES || time.only == false) null else side(true, base?.movie ?: ScoutQ(average = 6.8, votes = 300), today, country)
        val tv = if (watch == AskWatch.MOVIE || time.only == true) null else side(false, base?.tv ?: ScoutQ(average = 7.0, votes = 100), today, country)
        return ScoutSlice(encode(), ScoutGroup.MOOD, title(), movie, tv)
    }

    private fun side(movie: Boolean, start: ScoutQ, today: LocalDate, country: String): ScoutQ {
        var f = start
        fun votes(n: Int) { f = f.copy(votes = minOf(f.votes ?: n, n)) }
        fun average(a: Double) { f = f.copy(average = minOf(f.average ?: a, a)) }
        fun leaveOut(g: String) {
            val wanted = f.genres.orEmpty().split(',', '|').toSet()
            f = f.copy(without = (f.without.orEmpty().split(',') + g.split(',')).filter { it.isNotBlank() && it !in wanted }.distinct().joinToString(",").ifBlank { null })
        }
        when (watch) {
            AskWatch.ANIME -> {
                f = f.copy(genres = allGenres("16", f.genres), without = f.without?.split(',')?.filter { it != "16" }?.joinToString(",")?.ifBlank { null },
                    country = "JP", keywords = mood?.keywords ?: f.keywords?.takeIf { start.genres == null })
                if (mood?.keywords != null) { votes(if (movie) 10 else 5); average(6.0) } else votes(if (movie) 100 else 50)
            }
            AskWatch.DOCS -> {
                f = f.copy(genres = "99", without = NOT_DOCUMENTARY, keywords = mood?.keywords, withoutKeywords = null)
                if (mood != null) { votes(if (movie) 20 else 5); average(6.5) } else votes(if (movie) 100 else 30)
            }
            else -> if (mood?.keywords != null) { f = f.copy(keywords = mood.keywords); votes(if (movie) 50 else 10); average(6.0) }
        }
        when (time) {
            AskTime.SHORT -> f = f.copy(runtimeMax = if (movie) 90 else 30)
            AskTime.LONG -> f = f.copy(runtimeMin = if (movie) 150 else 40)
            AskTime.UNDER_90 -> f = f.copy(runtimeMax = 90)
            AskTime.UNDER_120 -> f = f.copy(runtimeMax = 120, runtimeMin = 60)
            AskTime.LONG_FILM -> f = f.copy(runtimeMin = 150)
            AskTime.QUICK -> f = f.copy(runtimeMax = 30)
            AskTime.HOUR -> f = f.copy(runtimeMin = 40, runtimeMax = 70)
            AskTime.WEEKEND, AskTime.MINISERIES -> { f = f.copy(type = "2"); votes(50) }
            AskTime.SHORT_SERIES -> { f = f.copy(type = "2"); votes(10); average(6.0) }
            AskTime.COMPLETE -> f = f.copy(status = "3")
            AskTime.FEATURE -> f = f.copy(runtimeMin = 70)
            else -> Unit
        }
        if (family) {
            val c = country.uppercase(Locale.ROOT)
            val table = if (movie) mapOf("US" to "PG", "AU" to "PG", "GB" to "PG", "DE" to "6", "NL" to "9", "IE" to "PG")
                else mapOf("US" to "TV-PG", "AU" to "PG", "GB" to "PG", "DE" to "6", "NL" to "9")
            val (rc, r) = table[c]?.let { c to it } ?: ("US" to table.getValue("US"))
            f = f.copy(certCountry = rc, certLte = r); votes(if (movie) 200 else 50); average(if (movie) 6.3 else 6.5); leaveOut("27")
        }
        if (AskTune.LIGHTER in tunes) leaveOut(if (movie) "27,53,80,10752" else "80,9648,10768")
        if (AskTune.DARKER in tunes) {
            if (f.genres == null) { val out = f.without.orEmpty().split(','); f = f.copy(genres = (if (movie) "53|80|27" else "80|9648").split('|').filter { it !in out }.joinToString("|").ifBlank { null }) }
            if (start.genres != null || watch == AskWatch.ANIME || watch == AskWatch.DOCS) leaveOut("35,10751,16")
        }
        if (AskTune.NEWER in tunes) f = f.copy(from = today.minusYears(5).toString())
        if (AskTune.OLDER in tunes) f = f.copy(to = today.minusYears(20).toString())
        if (AskTune.BETTER in tunes) f = f.copy(average = maxOf(f.average ?: 0.0, 7.8))
        if (AskTune.GEMS in tunes) { f = f.copy(votesMax = if (movie) GEM_MAX else GEM_MAX_TV, average = maxOf(f.average ?: 0.0, 7.5)); votes(if (movie) 100 else 30) }
        if (AskTune.INTERNATIONAL in tunes && f.country == null) f = f.copy(country = "GB|AU|CA|IE|NZ|FR|DE|IT|ES|SE|DK|NO|NL|BE|KR|JP|IN|MX|BR|CN|HK|TR")
        if (AskTune.FROM_COUNTRY in tunes && watch != AskWatch.ANIME && country.isNotBlank()) {
            val c = country.uppercase(Locale.ROOT)
            f = f.copy(country = c, language = COUNTRY_LANGUAGE[c] ?: f.language, votes = minOf(f.votes ?: Int.MAX_VALUE, if (movie) 20 else 5), average = minOf(f.average ?: 6.0, 6.0))
        }
        if (!movie) leaveOut(NOT_SHOWS_PEOPLE_MEAN)
        return f
    }

    companion object {
        private val COUNTRY_LANGUAGE = mapOf("FR" to "fr", "DE" to "de", "IT" to "it", "ES" to "es", "JP" to "ja", "KR" to "ko", "BR" to "pt", "PT" to "pt",
            "TR" to "tr", "SE" to "sv", "NO" to "no", "DK" to "da", "FI" to "fi", "NL" to "nl", "PL" to "pl", "RU" to "ru", "GR" to "el", "TH" to "th",
            "ID" to "id", "CN" to "zh", "EG" to "ar", "SA" to "ar", "AE" to "ar", "QA" to "ar")
        fun decode(id: String): ScoutAsk? {
            val p = id.split('.'); if (p.size != 6 || p[0] != "q") return null
            val w = AskWatch.entries.firstOrNull { it.code == p[1] } ?: return null
            val m = if (p[2] == "x") null else AskMood.entries.firstOrNull { it.code == p[2] } ?: return null
            val t = AskTime.entries.firstOrNull { it.code == p[3] } ?: return null
            val tunes = p[5].map { c -> AskTune.entries.firstOrNull { it.code == c.toString() } ?: return null }.toSet()
            return ScoutAsk(w, m, t, p[4] == "f", tunes)
        }
    }
}

internal fun allGenres(required: String, others: String?): String =
    (listOf(required) + others.orEmpty().split(',').filter { it.isNotBlank() }.map { it.substringBefore('|') }).distinct().joinToString(",")

// ------------------------------------------------------------------------------------------ Build your own

/**
 * The smarter Scout (user 2026-11-24): one choice in any group (mood, decade, country or region, language, length,
 * theme, acclaim, genre), ALL kept in one TMDB search, so "UK and Ireland + Drama + 1990s" only ever asks for that.
 */
data class ScoutBuild(val picks: Map<ScoutGroup, String> = emptyMap()) {
    fun press(g: ScoutGroup, sliceId: String): ScoutBuild {
        // Country and Region are one place: picking one clears the other.
        val m = picks.toMutableMap()
        if (m[g] == sliceId) m.remove(g) else { m[g] = sliceId; if (g == ScoutGroup.COUNTRY) m.remove(ScoutGroup.REGION); if (g == ScoutGroup.REGION) m.remove(ScoutGroup.COUNTRY) }
        return ScoutBuild(m)
    }
    val empty get() = picks.isEmpty()
    fun encode() = "b." + ScoutGroup.entries.mapNotNull { g -> picks[g]?.let { "${g.ordinal}~$it" } }.joinToString(".")
    fun slices() = ScoutGroup.entries.mapNotNull { g -> picks[g]?.let { ScoutSlices.byId(it) }?.takeIf { it.award == null } }

    fun slice(): ScoutSlice? {
        val ss = slices().ifEmpty { return null }
        fun side(pick: (ScoutSlice) -> ScoutQ?): ScoutQ? {
            if (ss.any { pick(it) == null }) return null   // one choice has none of this kind (Quick episodes = series only)
            return ss.map { pick(it)!! }.reduce { a, b -> a.and(b) }
        }
        val sort = ss.firstOrNull { it.group == ScoutGroup.ACCLAIM }?.sort ?: POPULAR
        val region = ss.mapNotNull { it.regionOnly }.firstOrNull()
        return ScoutSlice(encode(), ScoutGroup.MOOD, ss.joinToString(" • ") { it.title }, side { it.movie }, side { it.tv }, sort, regionOnly = region)
    }

    companion object {
        fun decode(id: String): ScoutBuild? {
            if (!id.startsWith("b.")) return null
            val m = id.removePrefix("b.").split('.').mapNotNull { p ->
                val (g, s) = p.split('~', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                ScoutGroup.entries.getOrNull(g.toIntOrNull() ?: -1)?.let { it to s }
            }.toMap()
            return ScoutBuild(m).takeIf { !it.empty }
        }
    }
}

// ------------------------------------------------------------------------------------------ Ready-made mixes

/** Scout's ready-made mixes and Mix your own: shows to shuffle episodes from. */
enum class MixDecade(val code: String, val label: String, val from: Int?, val to: Int?) {
    ANY("x", "Any", null, null), D70("70", "70s", 1970, 1979), D80("80", "80s", 1980, 1989), D90("90", "90s", 1990, 1999),
    D00("00", "00s", 2000, 2009), D10("10", "2010s", 2010, 2019), D20("20", "2020s", 2020, null)
}
enum class MixKind(val code: String, val label: String, val genres: String?, val leaveOut: String? = null, val countries: String? = null,
                   val keywords: String? = null, val leaveOutWords: String? = null) {
    ANY("x", "Any", null), SITCOMS("s", "Sitcoms", "35", "16", keywords = "193171|381587|210605"),
    CARTOONS("c", "Cartoons", "16", countries = "US|GB|CA|AU|FR|IE", leaveOutWords = "161919|315444"),
    CRIME("k", "Crime", "80", "16,10765", keywords = "268067|15167|6149|703"), SCI_FI("f", "Sci-fi", "10765", "16", keywords = "281358|9882|3801"),
    ANIME("a", "Anime", "16", countries = "JP"), DOCS("d", "Docs", "99")
}
enum class MixFeel(val code: String, val label: String, val ask: AskMood?) {
    ANY("x", "Any", null), FEEL_GOOD("g", "Feel-good", AskMood.FEEL_GOOD), LAUGH("l", "Laugh out loud", AskMood.LAUGH),
    EDGE("e", "Edge of your seat", AskMood.EDGE), WEIRD("w", "Weird and wonderful", AskMood.WEIRD), DARK("d", "Dark and gritty", AskMood.DARK)
}
enum class MixFrom(val code: String, val label: String, val countries: String?) {
    ANYWHERE("x", "Anywhere", null), US("us", "American", "US"), UK("uk", "UK and Ireland", "GB|IE"), CANADA("ca", "Canadian", "CA"),
    AUSNZ("au", "Australian and NZ", "AU|NZ"), KOREA("kr", "Korean", "KR"), JAPAN("jp", "Japanese", "JP"), CHINESE("zh", "Chinese-language", "CN|TW|HK"),
    SPAIN("es", "Spanish", "ES"), FRANCE("fr", "French", "FR"), GERMANY("de", "German", "DE"), ITALY("it", "Italian", "IT"),
    NORDIC("no", "Nordic", "DK|SE|NO|FI|IS"), LATAM("la", "Latin American", "MX|CO|AR|CL|BR"), TURKEY("tr", "Turkish", "TR"),
    INDIA("in", "Indian", "IN"), THAILAND("th", "Thai", "TH"), ARAB("ar", "Arabic", "EG|SA|AE|LB|SY|JO|KW|QA|MA|TN")
}

data class ScoutMix(val decade: MixDecade = MixDecade.ANY, val kind: MixKind = MixKind.ANY, val feel: MixFeel = MixFeel.ANY, val from: MixFrom = MixFrom.ANYWHERE) {
    fun encode() = listOf("r", decade.code, kind.code, feel.code, from.code).joinToString(".")
    fun title() = listOfNotNull(feel.takeIf { it != MixFeel.ANY }?.label, from.takeIf { it != MixFrom.ANYWHERE }?.label,
        decade.takeIf { it != MixDecade.ANY }?.label, kind.takeIf { it != MixKind.ANY }?.label).joinToString(" • ").ifEmpty { "Anything" }

    /** The shows' TMDB search (Scout's ScoutMixRecipe.filters, strict first; [loose] widens the decade and votes). */
    fun query(loose: Boolean = false, today: LocalDate = LocalDate.now()): ScoutQ {
        var f = feel.ask?.let { ScoutAsk(AskWatch.SERIES, it).slice(today).tv } ?: ScoutQ()
        val g = kind.genres
        f = when {
            g == null && f.genres == null && f.keywords == null -> f.copy(genres = "35|16|80|10765|9648|10759|10751|10762")
            g == null -> f
            f.genres == null -> f.copy(genres = g)
            else -> f.copy(genres = allGenres(g.substringBefore('|'), f.genres))
        }
        if (kind.keywords != null && f.keywords == null && !loose) f = f.copy(keywords = kind.keywords)
        val out = listOfNotNull(f.without, kind.leaveOut, NOT_SHOWS_PEOPLE_MEAN).flatMap { it.split(',') }
            .filter { it.isNotBlank() && it !in f.genres.orEmpty().split(',', '|') }.distinct()
        f = f.copy(without = out.joinToString(",").ifBlank { null })
        kind.leaveOutWords?.let { w -> f = f.copy(withoutKeywords = listOfNotNull(f.withoutKeywords, w).joinToString("|")) }
        val years = if (loose) 3 else 0
        decade.from?.let { f = f.copy(from = "${it - years}-01-01", to = "${minOf((decade.to ?: today.year) + years, today.year)}-12-31") }
        (from.countries ?: kind.countries)?.let { f = f.copy(country = it) }
        val floor = if (loose) 10 else if (from.countries != null || decade == MixDecade.D70) 20 else 50
        return f.copy(votes = minOf(f.votes ?: floor, floor))
    }

    companion object {
        fun decode(id: String): ScoutMix? {
            val p = id.split('.'); if (p.size != 5 || p[0] != "r") return null
            return ScoutMix(MixDecade.entries.firstOrNull { it.code == p[1] } ?: return null, MixKind.entries.firstOrNull { it.code == p[2] } ?: return null,
                MixFeel.entries.firstOrNull { it.code == p[3] } ?: return null, MixFrom.entries.firstOrNull { it.code == p[4] } ?: return null)
        }
        /** Scout's ready-made tiles: decades, classic TV, moods, around the world. */
        val READY: List<Pair<String, ScoutMix>> = buildList {
            listOf(MixDecade.D70, MixDecade.D80, MixDecade.D90, MixDecade.D00, MixDecade.D10, MixDecade.D20).forEach { add("${it.label} TV" to ScoutMix(decade = it)) }
            add("Sitcoms" to ScoutMix(kind = MixKind.SITCOMS)); add("Saturday morning cartoons" to ScoutMix(MixDecade.D80, MixKind.CARTOONS, from = MixFrom.US))
            add("Cop shows" to ScoutMix(kind = MixKind.CRIME)); add("Sci-fi adventures" to ScoutMix(kind = MixKind.SCI_FI))
            MixFeel.entries.filter { it != MixFeel.ANY }.forEach { add(it.label to ScoutMix(feel = it)) }
            MixFrom.entries.filter { it != MixFrom.ANYWHERE }.forEach { add(it.label to ScoutMix(from = it)) }
        }
    }
}

/** The bundled occasion episodes (Christmas, Halloween, Valentine's, Thanksgiving, Easter): Scout's scout_occasions.json. */
object ScoutOccasions {
    data class Ep(val imdb: String, val name: String, val season: Int, val episode: Int, val poster: String?, val backdrop: String?)
    enum class Occasion(val code: String, val label: String) { CHRISTMAS("christmas", "Christmas"), HALLOWEEN("halloween", "Halloween"),
        VALENTINES("valentines", "Valentine's Day"), THANKSGIVING("thanksgiving", "Thanksgiving"), EASTER("easter", "Easter") }
    @Volatile private var data: Map<String, List<Ep>>? = null
    fun episodes(o: Occasion): List<Ep> = load()[o.code].orEmpty()
    private fun load(): Map<String, List<Ep>> = data ?: runCatching {
        val root = JSONObject(App.instance.assets.open("scout_occasions.json").bufferedReader().use { it.readText() }).getJSONObject("occasions")
        root.keys().asSequence().associateWith { k -> root.getJSONObject(k).optJSONArray("episodes").objects().map { e ->
            Ep(e.optString("imdb"), e.optString("name"), e.optInt("s"), e.optInt("e"), Tmdb.img(e.optString("po"), "w342"), Tmdb.img(e.optString("bd"), "w780")) } }
    }.getOrDefault(emptyMap()).also { data = it }

    /** The one in season first (Christmas from 1 December to New Year's Day, Halloween all October…). */
    fun ordered(today: LocalDate = LocalDate.now()): List<Occasion> = Occasion.entries.sortedByDescending { inSeason(it, today) }
    fun inSeason(o: Occasion, d: LocalDate): Boolean = when (o) {
        Occasion.CHRISTMAS -> d.monthValue == 12 || (d.monthValue == 1 && d.dayOfMonth == 1)
        Occasion.HALLOWEEN -> d.monthValue == 10
        Occasion.VALENTINES -> d.monthValue == 2 && d.dayOfMonth <= 14
        Occasion.THANKSGIVING -> d.monthValue == 11
        Occasion.EASTER -> easter(d.year).let { e -> !d.isBefore(e.minusDays(21)) && !d.isAfter(e.plusDays(1)) }
    }
    private fun easter(y: Int): LocalDate {
        val a = y % 19; val b = y / 100; val c = y % 100; val d = b / 4; val e = b % 4; val f = (b + 8) / 25; val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30; val i = c / 4; val k = c % 4; val l = (32 + 2 * e + 2 * i - h - k) % 7; val m = (a + 11 * h + 22 * l) / 451
        return LocalDate.of(y, (h + l - 7 * m + 114) / 31, (h + l - 7 * m + 114) % 31 + 1)
    }
}

// ------------------------------------------------------------------------------------------ the engine: find, keep, rank

object Scout {
    private val awardLists: Map<String, JSONObject> by lazy {
        runCatching {
            val a = JSONObject(App.instance.assets.open("scout_awards.json").bufferedReader().use { it.readText() }).getJSONObject("awards")
            a.keys().asSequence().associateWith { a.getJSONObject(it) }
        }.getOrDefault(emptyMap())
    }

    /** Award titles: winners, or nominees who didn't win, newest first; a side with under 5 is left out (Scout's rule). */
    fun awardIds(award: ScoutAward, tv: Boolean): List<Int> {
        val chosen = award.awards.mapNotNull { awardLists[it] }
        fun ids(o: JSONObject, side: String) = o.optJSONObject(side)?.optJSONArray(if (tv) "tv" else "movie")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty()
        val won = chosen.flatMap { ids(it, "won") }.distinct()
        val list = if (award.winners) won else chosen.flatMap { ids(it, "nominated") }.distinct() - won.toSet()
        return list.takeIf { it.size >= 5 }.orEmpty()
    }
    /** The award page's categories that have titles. */
    fun awardTabs(award: ScoutAward): List<Pair<ScoutAwardTab, Boolean>> = award.tabs.mapNotNull { t ->
        val a = ScoutAward(t.awards, award.winners)
        when { awardIds(a, false).isNotEmpty() -> t to false; awardIds(a, true).isNotEmpty() -> t to true; else -> null }
    }

    /** Your taste, the engine's view: titles it picked for you (by TMDB-free name key), your genres, your countries. */
    private class Taste(val picked: Map<String, Int>, val genres: Map<Int, Double>, val countries: Map<String, Double>)
    private fun taste(hub: Hub): Taste {
        val pool = Recs.pool(hub).value
        val picks = pool?.picks.orEmpty() + pool?.because.orEmpty().flatMap { it.picks }
        val picked = HashMap<String, Int>()
        picks.forEachIndexed { i, p -> picked.putIfAbsent(Recs.seenName(p.name, p.year?.toString()), i) }
        val g = HashMap<Int, Double>()
        picks.take(120).forEach { p -> p.genres.mapNotNull { ScoutSlices.TMDB_GENRE[it] }.forEach { g.merge(it, 1.0, Double::plus) } }
        val top = g.values.maxOrNull() ?: 1.0
        return Taste(picked, g.mapValues { it.value / top }, Interests.countryWeights(hub))
    }

    private val pages = ConcurrentHashMap<String, List<Pair<MetaPreview, JSONObject>>>()

    /**
     * Page [page] of [slice]'s films ([tv] false) or shows: Scout's search, then Lite's engine: nothing watched,
     * saved, turned down, left out or adult; this hub only; your IMDb minimum; then ranked by your taste (the engine's
     * own picks first, your genres and countries lifted), with TMDB's order breaking ties.
     */
    suspend fun page(slice: ScoutSlice, tv: Boolean, page: Int, hub: Hub = Hubs.now()): List<MetaPreview> = withContext(Dispatchers.IO) {
        if (Tmdb.key() == null) return@withContext emptyList()
        val raw = if (slice.award != null) awardPage(slice.award, tv, page) else discover(slice, tv, page)
        keepAndRank(raw, hub)
    }

    private fun discover(slice: ScoutSlice, tv: Boolean, page: Int): List<Pair<MetaPreview, JSONObject>> {
        val f = (if (tv) slice.tv else slice.movie) ?: return emptyList()
        val key = "${slice.id}|$tv|$page"
        pages[key]?.let { return it }
        val kind = if (tv) "tv" else "movie"
        fun ask(q: ScoutQ) = Tmdb.get("discover/$kind?include_adult=false&sort_by=${slice.sort}&page=$page&${q.query(tv)}${Adult.tmdbWithout()}")
            ?.optJSONArray("results").objects().mapNotNull { r -> Tmdb.items(JSONObject().put("results", org.json.JSONArray().put(r)), tv).firstOrNull()?.let { it to r } }
        var list = ask(f)
        // Too little on the first page of a narrow combination: thresholds give way, the choices stay.
        if (page == 1 && list.size < 6) list = (list + ask(f.relaxed())).distinctBy { it.first.id }
        val region = slice.regionOnly
        if (region != null && tv) {
            // Made there only (Clickbait = AU and US stays out of Australian); too few = co-productions led by the region.
            val only = list.filter { (_, r) -> r.origin().let { o -> o.isNotEmpty() && o.all { it in region } } }
            list = if (only.size >= REGION_FEWEST) only else only + list.filter { (m, r) -> m !in only.map { it.first } && r.origin().firstOrNull() in region }
        }
        if (list.isNotEmpty()) pages[key] = list
        return list
    }

    private fun JSONObject.origin(): List<String> = optJSONArray("origin_country")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()

    private suspend fun awardPage(award: ScoutAward, tv: Boolean, page: Int): List<Pair<MetaPreview, JSONObject>> = coroutineScope {
        val ids = awardIds(award, tv).drop((page - 1) * 20).take(20)
        ids.map { id ->
            async {
                val key = "award|$tv|$id"
                pages[key]?.firstOrNull() ?: Tmdb.get("${if (tv) "tv" else "movie"}/$id")?.let { r ->
                    Tmdb.items(JSONObject().put("results", org.json.JSONArray().put(r)), tv).firstOrNull()?.let { (it to r).also { p -> pages[key] = listOf(p) } }
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun keepAndRank(raw: List<Pair<MetaPreview, JSONObject>>, hub: Hub): List<MetaPreview> {
        if (raw.isEmpty()) return emptyList()
        val seen = Recs.seenTmdb(); val nope = Recs.nope(); val min = Recs.minImdb()
        val kept = raw.filter { (m, _) ->
            Hubs.keeps(hub, m) && m.id !in seen && m.id !in nope && Recs.seenName(m.name, m.releaseInfo) !in seen &&
                !Adult.hides(m) && !LeaveOut.out(m.id, m.genres) && (min <= 0f || (m.imdbRating?.toFloatOrNull() ?: 10f) >= min - 0.5f)
        }
        val t = taste(hub)
        return kept.withIndex().sortedByDescending { (i, pr) ->
            val (m, r) = pr
            var s = -i * 0.08   // TMDB's (Scout's) order
            t.picked[Recs.seenName(m.name, m.releaseInfo)]?.let { s += 3.0 - minOf(it, 100) * 0.02 }
            val gs = r.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty()
            s += 1.2 * (gs.maxOfOrNull { t.genres[it] ?: 0.0 } ?: 0.0)
            s += 1.5 * (r.origin().maxOfOrNull { t.countries[it] ?: 0.0 } ?: 0.0)
            s
        }.map { (_, pr) ->
            val (m, _) = pr
            if (t.picked.containsKey(Recs.seenName(m.name, m.releaseInfo))) m.copy(description = "For you · " + (m.description ?: "")) else m
        }
    }

    /** Home's Scout tiles: Build your own and Ask first, then the places and kinds you watch most, then Scout's set. */
    fun tiles(hub: Hub): List<MetaPreview> {
        val c = Interests.countryWeights(hub)
        val places = (ScoutSlices.inGroup(ScoutGroup.REGION) + ScoutSlices.inGroup(ScoutGroup.COUNTRY))
            .map { it to (it.regionOnly.orEmpty().maxOfOrNull { k -> c[k] ?: 0.0 } ?: 0.0) }.filter { it.second > 0.05 && it.first.regionOnly?.contains("US") != true }
            .sortedByDescending { it.second }.map { it.first }.distinctBy { it.regionOnly }.take(3)
        val fixed = listOf("mood_feel_good", "mood_mind", "decade_1990", "acclaim_top", "acclaim_gems", "length_90", "theme_true", "award_oscar_winners")
            .mapNotNull { ScoutSlices.byId(it) }
        return listOf(MetaPreview("scout:build", "scout", "Build your own", description = "Pick from every Scout filter, all at once"),
            MetaPreview("scout:ask", "scout", "Ask Scout", description = "What, mood, length, family night"),
            MetaPreview("scout:mixes", "scout", "Mixes", description = "Random episodes from shows picked for you")) +
            (places + fixed).distinctBy { it.id }.map { MetaPreview("scout:${it.id}", "scout", it.title, description = it.group.label) }
    }
}

/** Mixes: the shows (Scout finds, the engine keeps and ranks) and Shuffle (a random episode, autoplay picks the link). */
object ScoutShuffle {
    data class Ep(val imdb: String, val season: Int, val episode: Int, val label: String) { val streamId get() = "$imdb:$season:$episode" }

    suspend fun mixShows(mix: ScoutMix, page: Int, hub: Hub = Hubs.now()): List<MetaPreview> = withContext(Dispatchers.IO) {
        if (Tmdb.key() == null) return@withContext emptyList()
        val strict = ScoutSlice(mix.encode(), ScoutGroup.MOOD, mix.title(), tv = mix.query(), sort = "vote_count.desc",
            regionOnly = mix.from.countries?.split('|')?.toSet())
        var list = Scout.page(strict, true, page, hub)
        // Only a few fit: Scout widens the decade and lets less-known shows in (its "widened" step).
        if (page == 1 && list.size < 8) list = (list + Scout.page(strict.copy(id = strict.id + "~", tv = mix.query(loose = true)), true, 1, hub)).distinctBy { it.id }
        list
    }

    suspend fun fromShows(shows: List<MetaPreview>): Ep? = withContext(Dispatchers.IO) {
        repeat(4) {
            val show = shows.filter { it.id.startsWith("tmdb:tv:") }.randomOrNull() ?: return@withContext null
            val d = Tmdb.get("tv/${show.id.substringAfterLast(':')}?append_to_response=external_ids") ?: return@repeat
            val imdb = d.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.startsWith("tt") } ?: return@repeat
            val seasons = d.optJSONArray("seasons").objects().filter { it.optInt("season_number") > 0 && it.optInt("episode_count") > 0 }
            val s = seasons.randomOrNull() ?: return@repeat
            val e = (1..s.optInt("episode_count")).random()
            return@withContext Ep(imdb, s.optInt("season_number"), e, "${show.name} S${s.optInt("season_number")} E$e")
        }
        null
    }

    fun occasion(o: ScoutOccasions.Occasion): Ep? {
        val nope = Recs.nope()
        val e = ScoutOccasions.episodes(o).filter { it.imdb !in nope && !LeaveOut.out(it.imdb) }.randomOrNull() ?: return null
        return Ep(e.imdb, e.season, e.episode, "${e.name} S${e.season} E${e.episode}")
    }

    /** The occasion's episodes as cards (at most 4 per show, a fresh choice each week, as Scout does). */
    fun occasionCards(o: ScoutOccasions.Occasion): List<MetaPreview> {
        val nope = Recs.nope()
        val week = LocalDate.now().toEpochDay() / 7
        return ScoutOccasions.episodes(o).filter { it.imdb !in nope && !LeaveOut.out(it.imdb) }.groupBy { it.imdb }.values
            .flatMap { it.shuffled(java.util.Random(week)).take(4) }.shuffled(java.util.Random(week))
            .map { MetaPreview(it.imdb, "series", it.name, it.poster, it.backdrop, releaseInfo = "S${it.season} E${it.episode}") }
    }
}
