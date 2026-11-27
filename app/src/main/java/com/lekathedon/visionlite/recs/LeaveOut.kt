// Leave out (Settings › Recommendations › Leave out; user 2026-11-24, Scout's idea done strictly): genres, languages
// and countries you never want to see. A left-out title shows NOWHERE: no row, tile, rotating row, page, "More like
// this", recommendation or AI pick, and a row or tile that is about it ("Korean Thrillers", "K-Drama", a Horror tile)
// goes too. Only Search still finds it (by name), marked "Left out: Korean" so you know why it's missing elsewhere.
// Continue watching and your Library are yours and stay as they are.
//
// What a title is: its genres (every card carries them) plus its language (IMDb's first spoken language / TMDB's
// original language) and its country (the FIRST country of origin, so a Hollywood film with a Korean co-producer isn't
// Korean). Those two come free with the IMDb / TMDB / AniList requests the rows already make; a title that came
// without them (add-on rows) is held back until one small check (25 per keyless IMDb request) says what it is.
// Cost: nothing while the lists are empty; then one map lookup per card and ~1 request per 25 unknown titles.
package com.lekathedon.visionlite.recs

import android.util.Log
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.addons.MetaPreview
import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.home.Tmdb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object LeaveOut {
    private const val TAG = "LeaveOut"
    private const val KEEP = 40_000

    enum class Kind(val key: String, val label: String) { GENRE("g", "Genres"), LANG("l", "Languages"), REGION("r", "Countries") }

    /** IMDb's genre names (TMDB's / AniList's / add-ons' spellings are turned into these). */
    val GENRES = listOf("Action", "Adventure", "Animation", "Biography", "Comedy", "Crime", "Documentary", "Drama", "Family",
        "Fantasy", "Game-Show", "History", "Horror", "Music", "Musical", "Mystery", "News", "Reality-TV", "Romance", "Sci-Fi",
        "Sport", "Talk-Show", "Thriller", "War", "Western")

    /** Original languages (ISO 639-1; Chinese covers Mandarin and Cantonese). */
    val LANGS = listOf("en" to "English", "ar" to "Arabic", "bn" to "Bengali", "zh" to "Chinese", "cs" to "Czech", "da" to "Danish",
        "nl" to "Dutch", "fi" to "Finnish", "fr" to "French", "de" to "German", "el" to "Greek", "he" to "Hebrew", "hi" to "Hindi",
        "hu" to "Hungarian", "id" to "Indonesian", "it" to "Italian", "ja" to "Japanese", "kn" to "Kannada", "ko" to "Korean",
        "ms" to "Malay", "ml" to "Malayalam", "mr" to "Marathi", "no" to "Norwegian", "fa" to "Persian", "pl" to "Polish",
        "pt" to "Portuguese", "pa" to "Punjabi", "ro" to "Romanian", "ru" to "Russian", "sr" to "Serbian", "es" to "Spanish",
        "sv" to "Swedish", "tl" to "Tagalog", "ta" to "Tamil", "te" to "Telugu", "th" to "Thai", "tr" to "Turkish",
        "uk" to "Ukrainian", "ur" to "Urdu", "vi" to "Vietnamese")

    /** Countries of origin (ISO 3166-1). */
    val REGIONS = listOf("AR" to "Argentina", "AU" to "Australia", "AT" to "Austria", "BE" to "Belgium", "BR" to "Brazil",
        "CA" to "Canada", "CL" to "Chile", "CN" to "China", "CO" to "Colombia", "CZ" to "Czechia", "DK" to "Denmark", "EG" to "Egypt",
        "FI" to "Finland", "FR" to "France", "DE" to "Germany", "GR" to "Greece", "HK" to "Hong Kong", "HU" to "Hungary",
        "IS" to "Iceland", "IN" to "India", "ID" to "Indonesia", "IR" to "Iran", "IE" to "Ireland", "IL" to "Israel", "IT" to "Italy",
        "JP" to "Japan", "JO" to "Jordan", "KW" to "Kuwait", "LB" to "Lebanon", "MY" to "Malaysia", "MX" to "Mexico", "MA" to "Morocco",
        "NL" to "Netherlands", "NZ" to "New Zealand", "NG" to "Nigeria", "NO" to "Norway", "PK" to "Pakistan", "PH" to "Philippines",
        "PL" to "Poland", "PT" to "Portugal", "QA" to "Qatar", "RO" to "Romania", "RU" to "Russia", "SA" to "Saudi Arabia",
        "RS" to "Serbia", "ZA" to "South Africa", "KR" to "South Korea", "ES" to "Spain", "SE" to "Sweden", "CH" to "Switzerland",
        "SY" to "Syria", "TW" to "Taiwan", "TH" to "Thailand", "TR" to "Turkey", "AE" to "United Arab Emirates", "UA" to "Ukraine",
        "GB" to "United Kingdom", "US" to "United States", "VN" to "Vietnam")

    /** Words a row or tile title uses for a language / country ("Korean Thrillers", "K-Drama", "Bollywood Hits"). */
    private val LANG_WORDS = mapOf(
        "en" to listOf("English"), "ar" to listOf("Arabic", "Arab"), "zh" to listOf("Chinese", "Mandarin", "Cantonese", "C-Drama", "Wuxia"),
        "fr" to listOf("French"), "de" to listOf("German"), "hi" to listOf("Hindi", "Bollywood"), "it" to listOf("Italian"),
        "ja" to listOf("Japanese", "J-Horror", "J-Drama"), "ko" to listOf("Korean", "K-Drama", "K-Dramas", "K-Horror"),
        "es" to listOf("Spanish", "Latin American", "Telenovela", "Telenovelas"), "pt" to listOf("Portuguese", "Brazilian"),
        "ru" to listOf("Russian"), "tr" to listOf("Turkish"), "ta" to listOf("Tamil", "Kollywood"), "te" to listOf("Telugu", "Tollywood"),
        "ml" to listOf("Malayalam"), "th" to listOf("Thai"), "sv" to listOf("Swedish"), "da" to listOf("Danish"), "no" to listOf("Norwegian"),
        "fi" to listOf("Finnish"), "pl" to listOf("Polish"), "nl" to listOf("Dutch"), "fa" to listOf("Persian", "Iranian"),
        "id" to listOf("Indonesian"), "tl" to listOf("Filipino", "Tagalog"), "he" to listOf("Hebrew", "Israeli"), "el" to listOf("Greek"),
        "vi" to listOf("Vietnamese"), "ur" to listOf("Urdu"), "pa" to listOf("Punjabi"), "bn" to listOf("Bengali"), "uk" to listOf("Ukrainian")
    )
    private val REGION_WORDS = mapOf(
        "KR" to listOf("Korea", "Korean", "K-Drama", "K-Dramas", "K-Horror"), "JP" to listOf("Japan", "Japanese", "J-Horror", "J-Drama"),
        "CN" to listOf("China", "Chinese", "C-Drama"), "HK" to listOf("Hong Kong"), "TW" to listOf("Taiwan", "Taiwanese"),
        "IN" to listOf("India", "Indian", "Bollywood", "Tollywood", "Kollywood"), "GB" to listOf("British", "UK", "England"),
        "US" to listOf("American", "Hollywood", "USA"), "FR" to listOf("France", "French"), "DE" to listOf("Germany", "German"),
        "IT" to listOf("Italy", "Italian"), "ES" to listOf("Spain", "Spanish"), "MX" to listOf("Mexico", "Mexican"),
        "TR" to listOf("Turkey", "Turkish"), "TH" to listOf("Thailand", "Thai"), "RU" to listOf("Russia", "Russian"),
        "SE" to listOf("Sweden", "Swedish"), "DK" to listOf("Denmark", "Danish"), "NO" to listOf("Norway", "Norwegian"),
        "AU" to listOf("Australia", "Australian", "Aussie"), "BR" to listOf("Brazil", "Brazilian"), "EG" to listOf("Egypt", "Egyptian"),
        "PH" to listOf("Philippines", "Filipino"), "IR" to listOf("Iran", "Iranian"), "NG" to listOf("Nigeria", "Nigerian", "Nollywood"),
        "IE" to listOf("Ireland", "Irish"), "CA" to listOf("Canada", "Canadian"), "PL" to listOf("Poland", "Polish")
    )
    private val GENRE_WORDS = mapOf(
        "Sci-Fi" to listOf("Sci-Fi", "Science Fiction", "Sci Fi"), "Romance" to listOf("Romance", "Romantic", "Rom-Com", "Rom-Coms", "Love Stories"),
        "Horror" to listOf("Horror", "Horrors", "Slasher", "Slashers", "Zombie", "Zombies"), "Animation" to listOf("Animation", "Animated", "Cartoon", "Cartoons"),
        "Documentary" to listOf("Documentary", "Documentaries", "Docuseries"), "Comedy" to listOf("Comedy", "Comedies", "Sitcom", "Sitcoms"),
        "Musical" to listOf("Musical", "Musicals"), "Western" to listOf("Western", "Westerns"), "War" to listOf("War"),
        "Sport" to listOf("Sport", "Sports"), "Reality-TV" to listOf("Reality"), "Talk-Show" to listOf("Talk Show", "Talk Shows"),
        "Biography" to listOf("Biography", "Biopic", "Biopics"), "Family" to listOf("Family", "Kids"), "Thriller" to listOf("Thriller", "Thrillers"),
        "Crime" to listOf("Crime"), "Drama" to listOf("Drama", "Dramas"), "Action" to listOf("Action"), "Mystery" to listOf("Mystery", "Mysteries", "Whodunit"),
        "Fantasy" to listOf("Fantasy"), "Adventure" to listOf("Adventure", "Adventures"), "History" to listOf("History", "Historical"),
        "Music" to listOf("Music", "Concert", "Concerts"), "News" to listOf("News"), "Game-Show" to listOf("Game Show", "Game Shows")
    )

    /** TMDB genre ids (films and TV) for the server-side "without_genres" (only exact matches; the rest is filtered here). */
    private val TMDB_IDS = mapOf("Action" to listOf(28), "Adventure" to listOf(12), "Animation" to listOf(16), "Comedy" to listOf(35),
        "Crime" to listOf(80), "Documentary" to listOf(99), "Drama" to listOf(18), "Family" to listOf(10751, 10762), "Fantasy" to listOf(14),
        "History" to listOf(36), "Horror" to listOf(27), "Music" to listOf(10402), "Mystery" to listOf(9648), "News" to listOf(10763),
        "Reality-TV" to listOf(10764), "Romance" to listOf(10749), "Sci-Fi" to listOf(878), "Talk-Show" to listOf(10767),
        "Thriller" to listOf(53), "War" to listOf(10752), "Western" to listOf(37))
    private val TMDB_NAMES = mapOf(28 to listOf("Action"), 12 to listOf("Adventure"), 16 to listOf("Animation"), 35 to listOf("Comedy"),
        80 to listOf("Crime"), 99 to listOf("Documentary"), 18 to listOf("Drama"), 10751 to listOf("Family"), 14 to listOf("Fantasy"),
        36 to listOf("History"), 27 to listOf("Horror"), 10402 to listOf("Music"), 9648 to listOf("Mystery"), 10749 to listOf("Romance"),
        878 to listOf("Sci-Fi"), 53 to listOf("Thriller"), 10752 to listOf("War"), 37 to listOf("Western"), 10759 to listOf("Action", "Adventure"),
        10762 to listOf("Family"), 10763 to listOf("News"), 10764 to listOf("Reality-TV"), 10765 to listOf("Sci-Fi", "Fantasy"),
        10767 to listOf("Talk-Show"), 10768 to listOf("War"))

    data class Lists(val genres: Set<String> = emptySet(), val langs: Set<String> = emptySet(), val regions: Set<String> = emptySet()) {
        val empty get() = genres.isEmpty() && langs.isEmpty() && regions.isEmpty()
        val needFacts get() = langs.isNotEmpty() || regions.isNotEmpty()
        fun of(k: Kind) = when (k) { Kind.GENRE -> genres; Kind.LANG -> langs; Kind.REGION -> regions }
    }

    @Volatile private var parsedFrom: String? = null
    @Volatile private var parsed = Lists()

    /** This profile's lists (pref "leave_out", JSON {"g":[…],"l":[…],"r":[…]}). */
    fun lists(): Lists {
        val s = App.instance.prefs.leaveOut.value
        if (s == parsedFrom) return parsed
        val l = runCatching {
            val o = JSONObject(s)
            fun set(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.toSet() }.orEmpty()
            Lists(set("g"), set("l"), set("r"))
        }.getOrDefault(Lists())
        parsed = l; parsedFrom = s
        return l
    }

    fun on(): Boolean = !lists().empty

    fun toggle(k: Kind, v: String) {
        val l = lists()
        val now = l.of(k).let { if (v in it) it - v else it + v }
        val n = when (k) { Kind.GENRE -> l.copy(genres = now); Kind.LANG -> l.copy(langs = now); Kind.REGION -> l.copy(regions = now) }
        App.instance.prefs.leaveOut.value = JSONObject().put("g", JSONArray(n.genres.sorted())).put("l", JSONArray(n.langs.sorted()))
            .put("r", JSONArray(n.regions.sorted())).toString()
        _revision.value++
        if (n.needFacts) scope.launch { load() }
    }

    fun clearAll() { App.instance.prefs.leaveOut.value = ""; _revision.value++ }

    fun name(k: Kind, v: String): String = when (k) {
        Kind.GENRE -> v
        Kind.LANG -> LANGS.firstOrNull { it.first == v }?.second ?: v
        Kind.REGION -> REGIONS.firstOrNull { it.first == v }?.second ?: v
    }

    // ---------------------------------------------------------------- what each title is

    /** [lang] ISO 639-1, [country] the first country of origin, [genres] IMDb names; null = not known. */
    private class F(val lang: String?, val country: String?, val genres: List<String>?)

    private val known = ConcurrentHashMap<String, F>()
    /** Titles a check couldn't answer (not on IMDb / TMDB): shown, not held back forever. */
    private val unanswerable = ConcurrentHashMap.newKeySet<String>()
    private val _revision = MutableStateFlow(0)
    /** Bumps when the lists change or new facts arrive, so every row filters again. */
    val revision: StateFlow<Int> get() = _revision
    private val file get() = File(App.instance.filesDir, "title_facts.json")
    @Volatile private var loaded = false
    @Volatile private var dirty = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var queued = false
    private val gate = Semaphore(3)

    private fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                val o = JSONObject(file.readText())
                o.keys().forEach { k ->
                    val p = o.getString(k).split('|')
                    known[k] = F(p.getOrNull(0)?.ifBlank { null }, p.getOrNull(1)?.ifBlank { null },
                        p.getOrNull(2)?.let { g -> if (g == "?") null else g.split(',').filter { it.isNotBlank() } })
                }
            }
            loaded = true
        }
    }

    private fun save() {
        if (!dirty) return
        dirty = false
        runCatching {
            val o = JSONObject()
            known.entries.toList().takeLast(KEEP).forEach { (k, f) -> o.put(k, "${f.lang.orEmpty()}|${f.country.orEmpty()}|${f.genres?.joinToString(",") ?: "?"}") }
            File(file.path + ".tmp").apply { writeText(o.toString()) }.renameTo(file)
        }
    }

    @Volatile private var saveQueued = false
    private fun saveSoon() {
        dirty = true
        if (saveQueued) return
        saveQueued = true
        scope.launch { delay(5_000); saveQueued = false; save() }
    }

    private fun lang(code: String?): String? = when (val c = code?.lowercase()?.trim()) {
        null, "", "xx", "none", "zxx" -> null
        "cmn", "yue", "cn", "zh-cn", "zh-tw", "zh-hk", "nan", "wuu" -> "zh"
        "nb", "nn" -> "no"
        "fil" -> "tl"
        "iw" -> "he"
        "in" -> "id"
        else -> c.substringBefore('-')
    }

    /** Turns any source's genre name into IMDb's ("Science Fiction" → Sci-Fi, "Action & Adventure" → both). */
    fun genres(names: Collection<String>): List<String> = names.flatMap { n ->
        when (val g = n.trim().lowercase()) {
            "science fiction", "sci-fi", "scifi", "sci fi" -> listOf("Sci-Fi")
            "action & adventure" -> listOf("Action", "Adventure")
            "sci-fi & fantasy" -> listOf("Sci-Fi", "Fantasy")
            "war & politics" -> listOf("War")
            "reality", "reality-tv", "reality tv" -> listOf("Reality-TV")
            "talk", "talk-show", "talk show" -> listOf("Talk-Show")
            "game-show", "game show" -> listOf("Game-Show")
            "kids", "children" -> listOf("Family")
            "sports", "sport" -> listOf("Sport")
            "biography", "biopic" -> listOf("Biography")
            "anime", "animation", "animated" -> listOf("Animation")
            else -> GENRES.firstOrNull { it.equals(g, true) }?.let { listOf(it) } ?: emptyList()
        }
    }.distinct()

    /** A source said what [id] is (any field may be missing; known fields aren't overwritten by unknown ones). */
    fun note(id: String, lang: String?, countries: List<String>?, genres: List<String>?) {
        if (id.isBlank()) return
        load()
        val old = known[id]
        val l = lang(lang) ?: old?.lang
        val c = countries?.firstOrNull { it.isNotBlank() }?.uppercase() ?: old?.country
        val g = genres?.let { genres(it) } ?: old?.genres
        if (old != null && old.lang == l && old.country == c && old.genres == g) return
        known[id] = F(l, c, g)
        saveSoon()
    }

    /** The IMDb fields every row query adds (empty while nothing is left out, so no extra bytes then). */
    fun imdbFields(): String = if (lists().needFacts) " spokenLanguages { spokenLanguages { id } } countriesOfOrigin { countries { id } }" else ""
    /** AniList's field for the same. */
    fun aniFields(): String = if (lists().needFacts) " countryOfOrigin" else ""

    /** An IMDb title node (with [imdbFields]) seen by a row parser. */
    fun fromImdb(id: String, n: JSONObject) {
        val langs = n.optJSONObject("spokenLanguages")?.optJSONArray("spokenLanguages")
        val cs = n.optJSONObject("countriesOfOrigin")?.optJSONArray("countries")
        val gs = n.optJSONObject("titleGenres")?.optJSONArray("genres")
        if (langs == null && cs == null && gs == null) return
        note(id, langs?.optJSONObject(0)?.optString("id"), cs?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id") } },
            gs?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optJSONObject("genre")?.optString("text") } })
    }

    /** A TMDB result (discover / search / details). */
    fun fromTmdb(id: String, r: JSONObject) {
        val gids = r.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).flatMap { TMDB_NAMES[a.optInt(it)].orEmpty() } }
            ?: r.optJSONArray("genres")?.let { a -> genres((0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") }) }
        val oc = r.optJSONArray("origin_country")?.let { a -> (0 until a.length()).map { a.optString(it) } }
            ?: r.optJSONArray("production_countries")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("iso_3166_1") } }
        note(id, r.optString("original_language").ifBlank { null }, oc?.takeIf { it.isNotEmpty() }, gids)
    }

    /** An AniList media (with [aniFields]): Japanese / Korean / Chinese animation by its country. */
    fun fromAni(id: String, m: JSONObject) {
        val c = m.optString("countryOfOrigin").takeIf { it.length == 2 } ?: return
        val l = when (c) { "JP" -> "ja"; "KR" -> "ko"; "CN", "TW", "HK" -> "zh"; else -> null }
        note(id, l, listOf(c), m.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.optString(it) } })
    }

    // ---------------------------------------------------------------- deciding

    /** Why [m] is left out ("Korean", "Horror"), or null when it isn't (or isn't known yet). */
    fun why(m: MetaPreview): String? = why(m.id, m.genres)

    fun why(id: String, cardGenres: List<String> = emptyList()): String? {
        val l = lists()
        if (l.empty) return null
        load()
        val f = known[id]
        if (l.genres.isNotEmpty()) {
            val g = (f?.genres.orEmpty() + genres(cardGenres))
            g.firstOrNull { it in l.genres }?.let { return it }
        }
        if (f != null) {
            f.lang?.takeIf { it in l.langs }?.let { return name(Kind.LANG, it) }
            f.country?.takeIf { it in l.regions }?.let { return name(Kind.REGION, it) }
        }
        return null
    }

    /** Whether [id] still needs a check before it can show (asks for one). */
    private fun unknown(id: String, cardGenres: List<String>): Boolean {
        val l = lists()
        val f = known[id]
        val needs = l.needFacts && (f == null || (f.lang == null && f.country == null)) ||
            l.genres.isNotEmpty() && cardGenres.isEmpty() && f?.genres == null
        if (!needs || id in unanswerable) return false
        if (!checkable(id)) return false
        ask(id)
        return true
    }

    private fun checkable(id: String) = id.startsWith("tt") || id.startsWith("tmdb:") || id.startsWith("anilist:")

    /** True when [m] mustn't show: left out, or not known yet (held back until its check, a second or so). */
    fun hides(m: MetaPreview): Boolean {
        if (!on()) return false
        if (m.type != "movie" && m.type != "series") return false
        if (why(m) != null) return true
        return unknown(m.id, m.genres)
    }

    fun hidesId(id: String, genres: List<String> = emptyList()): Boolean {
        if (!on()) return false
        if (why(id, genres) != null) return true
        return unknown(id, genres)
    }

    /** Known to be left out (no hold-back for unknown titles): for lists that are filtered again when they show. */
    fun out(id: String, genres: List<String> = emptyList()): Boolean = on() && why(id, genres) != null

    fun visible(list: List<MetaPreview>): List<MetaPreview> = if (!on()) list else list.filterNot { hides(it) }

    /** A row or tile whose title is about something left out ("Korean Thrillers", "Because you like K-Drama", "Horror"). */
    fun rowHidden(title: String, key: String = ""): Boolean {
        val l = lists()
        if (l.empty || title.isBlank() && key.isBlank()) return false
        // Regional rows / tiles by key ("rg:KOREAN:Thriller"): out when all their countries or all their languages are.
        Regex("""rg:([A-Z]+)(?::(\w[\w-]*))?""").find(key)?.let { mt ->
            val r = Interests.REGIONS[mt.groupValues[1]]
            if (r != null && (r.countries.all { it in l.regions } || REGION_LANGS[mt.groupValues[1]]?.all { it in l.langs } == true)) return true
            mt.groupValues.getOrNull(2)?.let { fam -> if (fam in l.genres) return true }
        }
        return titleWords(l)?.containsMatchIn(title) == true
    }

    @Volatile private var wordsFor: Lists? = null
    @Volatile private var wordsRe: Regex? = null
    /** One pattern for every word of the lists, built once per change of the lists. */
    private fun titleWords(l: Lists): Regex? {
        if (wordsFor == l) return wordsRe
        val words = (l.langs.flatMap { LANG_WORDS[it].orEmpty() + name(Kind.LANG, it) } +
            l.regions.flatMap { REGION_WORDS[it].orEmpty() + name(Kind.REGION, it) } +
            l.genres.flatMap { GENRE_WORDS[it] ?: listOf(it) }).distinct()
        val re = if (words.isEmpty()) null else Regex("""(?i)(?<![\p{L}])(?:${words.joinToString("|") { Regex.escape(it) }})(?![\p{L}])""")
        wordsRe = re; wordsFor = l
        return re
    }

    private val REGION_LANGS = mapOf("NORDIC" to listOf("sv", "da", "no", "fi", "is"), "BRITISH" to listOf("en"), "KOREAN" to listOf("ko"),
        "JAPANESE" to listOf("ja"), "SPANISH" to listOf("es"), "LATAM" to listOf("es", "pt"), "FRENCH" to listOf("fr"), "ITALIAN" to listOf("it"),
        "GERMAN" to listOf("de"), "TURKISH" to listOf("tr"), "ARABIC" to listOf("ar"), "INDIAN" to listOf("hi", "ta", "te", "ml", "kn", "bn", "mr", "pa"),
        "AUSSIE" to listOf("en"))

    /** TMDB discover: "&without_genres=…" for left-out genres, so a page isn't emptied by the filter here. */
    fun tmdb(): String {
        val g = lists().genres.flatMap { TMDB_IDS[it].orEmpty() }
        return if (g.isEmpty()) "" else "&without_genres=" + g.joinToString("%7C")
    }

    /** Lines for the AI's prompt. */
    fun aiLines(): String {
        val l = lists()
        if (l.empty) return ""
        return buildString {
            if (l.genres.isNotEmpty()) appendLine("Never suggest these genres: ${l.genres.joinToString(", ")}.")
            if (l.langs.isNotEmpty()) appendLine("Never suggest titles whose original language is: ${l.langs.joinToString(", ") { name(Kind.LANG, it) }}.")
            if (l.regions.isNotEmpty()) appendLine("Never suggest titles made (first country of origin) in: ${l.regions.joinToString(", ") { name(Kind.REGION, it) }}.")
        }
    }

    // ---------------------------------------------------------------- checks

    private fun ask(id: String) {
        if (!pending.add(id)) return
        synchronized(this) { if (queued) return; queued = true }
        scope.launch {
            try {
                while (pending.isNotEmpty()) {
                    delay(250)
                    val batch = pending.toList().take(200).also { pending.removeAll(it.toSet()) }
                    val lost = runCatching { check(batch) }.onFailure { Log.w(TAG, "check: ${it.message}") }.getOrDefault(batch.toSet())
                    // A request that failed (network, IMDb busy) is asked again up to twice, a few seconds later,
                    // instead of letting its titles show for the whole run (user 2026-11-27: a Korean row showed).
                    val retry = lost.filter { (tries.merge(it, 1) { a, b -> a + b } ?: 1) <= 2 }
                    if (retry.isNotEmpty()) { delay(3_000); pending += retry }
                    // Whatever came back without an answer shows from now on (this run).
                    batch.filter { it !in retry && known[it]?.let { f -> f.lang == null && f.country == null } != false }.forEach { unanswerable += it }
                    _revision.value++
                    saveSoon()
                }
            } finally { synchronized(this@LeaveOut) { queued = false } }
        }
    }

    /** The ids whose request failed (to ask again). */
    private suspend fun check(ids: List<String>): Set<String> = kotlinx.coroutines.coroutineScope {
        val imdb = ids.filter { it.startsWith("tt") }
        val tmdb = ids.filter { it.startsWith("tmdb:") }
        val ani = ids.filter { it.startsWith("anilist:") }
        val lost = ConcurrentHashMap.newKeySet<String>()
        val jobs = imdb.chunked(25).map { c -> async { gate.withPermit { if (!runCatching { imdbCheck(c) }.getOrDefault(false)) lost += c } } } +
            tmdb.map { id -> async { gate.withPermit { runCatching { tmdbCheck(id) } } } } +
            ani.chunked(50).map { c -> async { gate.withPermit { runCatching { aniCheck(c) } } } }
        jobs.awaitAll()
        lost
    }

    private val tries = ConcurrentHashMap<String, Int>()

    /** Known to be from a left-out country or language (genres not counted): a row mostly of these goes whole. */
    fun placeOut(id: String): Boolean {
        val l = lists()
        if (!l.needFacts) return false
        load()
        val f = known[id] ?: return false
        return (f.lang != null && f.lang in l.langs) || (f.country != null && f.country in l.regions)
    }

    private fun imdbCheck(ids: List<String>): Boolean {
        val q = ids.mapIndexed { i, id -> "t$i: title(id: \"$id\") { titleGenres { genres { genre { text } } } " +
            "spokenLanguages { spokenLanguages { id } } countriesOfOrigin { countries { id } } }" }
        val body = JSONObject().put("query", "query { ${q.joinToString(" ")} }").toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("https://api.graphql.imdb.com/").post(body)
            .header("x-imdb-client-name", "imdb-web-next").header("Accept", "application/json").build()
        val data = Net.http.newCall(req).execute().use { r -> if (r.isSuccessful) JSONObject(r.body!!.string()).optJSONObject("data") else null } ?: return false
        ids.forEachIndexed { i, id -> data.optJSONObject("t$i")?.let { fromImdb(id, it) } }
        return true
    }

    private fun tmdbCheck(id: String) {
        val (kind, n) = id.removePrefix("tmdb:").split(':').takeIf { it.size == 2 } ?: return
        val o = Tmdb.get("$kind/$n") ?: return
        fromTmdb(id, o)
    }

    private fun aniCheck(ids: List<String>) {
        val nums = ids.mapNotNull { it.removePrefix("anilist:").toLongOrNull() }
        if (nums.isEmpty()) return
        val q = "{ p: Page(perPage: 50) { media(id_in: ${JSONArray(nums)}) { id countryOfOrigin genres } } }"
        val req = Request.Builder().url("https://graphql.anilist.co").header("Accept", "application/json")
            .post(JSONObject().put("query", q).toString().toRequestBody("application/json".toMediaType())).build()
        val d = Net.http.newCall(req).execute().use { r -> if (r.isSuccessful) JSONObject(r.body!!.string()).optJSONObject("data") else null } ?: return
        val a = d.optJSONObject("p")?.optJSONArray("media") ?: return
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.forEach { m -> fromAni("anilist:${m.optLong("id")}", m) }
    }
}
