package com.lekathedon.visionlite.recs

import android.util.Log
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.anime.Hub
import com.lekathedon.visionlite.anime.Hubs
import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.integrations.Integrations
import com.lekathedon.visionlite.library.Saves
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The AIs as one source of the recommendation engine (no row of their own since 0.27): their finds lift and join
 * Vision Picks, IMDb Picks and the Moods. Vision's AI Picks logic (free tiers only): your taste goes to each AI you gave a key (Gemini, Groq, Mistral). Vote = each
 * AI's list counts by its weight, a title several name adds up; Boss = the others suggest, the boss makes the final
 * call. Every title is matched on IMDb (no TMDB needed), anything seen or turned down is left out, and only titles
 * IMDb rates at least the Recommendations minimum stay (an invented title never shows).
 *
 * Cost: every 12 h (or Refresh): one request per AI with a key, a few IMDb lookups. No key: nothing at all.
 */
object AiPicks {
    private const val TAG = "AiPicks"
    const val ROTATE_MS = 12 * 3600_000L
    private const val ASK = 30
    private const val ROW = 20
    private const val RANK_K = 4.0

    enum class Ai(val id: String, val label: String, val model: String) {
        GEMINI("gemini", "Gemini", "gemini-flash-lite-latest"),
        GROQ("groq", "Groq", "openai/gpt-oss-120b"),
        MISTRAL("mistral", "Mistral", "mistral-medium-latest");
        val key get() = Integrations.values(Integrations.byId(id)).firstOrNull().orEmpty()
        val on get() = Integrations.active(id) && key.isNotBlank()
    }

    @Serializable data class Saved(val at: Long = 0, val picks: List<Pick> = emptyList(), val history: List<String> = emptyList())
    private data class Sug(val title: String, val year: Int?, val series: Boolean, val imdb: String?, val reason: String?)

    private val http by lazy { Net.http.newBuilder().readTimeout(90, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build() }
    // Each hub asks for its own: Films & TV never gets anime, Anime gets only anime (own file, row and status).
    private val locks = Hub.entries.associateWith { Mutex() }
    private fun file(hub: Hub) = File(App.instance.profileDir, "ai_picks${hub.suffix}.json")
    private val _picks = Hub.entries.associateWith { h -> lazy { MutableStateFlow(read(h).picks) } }
    fun picks(hub: Hub = Hubs.now()): StateFlow<List<Pick>> = _picks.getValue(hub).value
    private val _status = Hub.entries.associateWith { MutableStateFlow<String?>(null) }
    /** Why the row is empty / what is happening (shown on its tile). */
    fun status(hub: Hub = Hubs.now()): StateFlow<String?> = _status.getValue(hub)

    fun configured() = Ai.entries.filter { it.on }

    // Settings: Vote (weights) or Boss (one AI decides).
    private val p get() = App.instance.prefs.sp
    fun boss(): Ai? = p.getString("ai_boss", null)?.let { n -> Ai.entries.firstOrNull { it.name == n } }
    fun setBoss(a: Ai?) { p.edit().putString("ai_boss", a?.name).apply() }
    fun weight(a: Ai) = p.getFloat("ai_w_${a.name}", 1f).toDouble()
    fun setWeight(a: Ai, w: Double) { p.edit().putFloat("ai_w_${a.name}", w.toFloat()).apply() }
    val WEIGHTS = listOf(0.5, 1.0, 1.5, 2.0, 3.0)

    private fun read(hub: Hub): Saved = runCatching { Net.json.decodeFromString(Saved.serializer(), file(hub).readText()) }.getOrDefault(Saved())

    fun remove(id: String, hub: Hub = Hubs.now()) { val s = read(hub); val k = s.copy(picks = s.picks.filter { it.imdb != id }); save(hub, k); _picks.getValue(hub).value.value = k.picks }
    private fun save(hub: Hub, s: Saved) { runCatching { file(hub).writeText(Net.json.encodeToString(Saved.serializer(), s)) } }

    suspend fun refreshIfDue(hub: Hub = Hubs.now(), force: Boolean = false) = withContext(Dispatchers.IO) {
        val status = _status.getValue(hub); val picks = _picks.getValue(hub).value
        val ais = configured()
        if (ais.isEmpty()) { status.value = "Add a Gemini, Groq or Mistral key: Settings › Integrations › AI"; return@withContext }
        val lock = locks.getValue(hub)
        if (!lock.tryLock()) return@withContext
        try {
            val saved = read(hub)
            if (!force && System.currentTimeMillis() - saved.at < ROTATE_MS && saved.picks.isNotEmpty()) { picks.value = saved.picks; return@withContext }
            status.value = "Asking ${ais.joinToString(", ") { it.label }}…"
            val problems = HashMap<String, String>()
            val fresh = compute(hub, ais, saved, problems)
            if (fresh.isEmpty()) {
                status.value = if (problems.isNotEmpty()) "No answer: " + problems.entries.joinToString(", ") { "${it.key} ${it.value}" } else "No new titles this time"
                return@withContext
            }
            status.value = null
            val s = Saved(System.currentTimeMillis(), fresh, (saved.history + fresh.map { it.name }).distinct().takeLast(80))
            save(hub, s); picks.value = fresh
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "failed: ${e.message}"); status.value = "Didn't work: ${e.message?.take(60)}"
        } finally { lock.unlock() }
    }

    private suspend fun compute(hub: Hub, ais: List<Ai>, saved: Saved, problems: MutableMap<String, String>): List<Pick> = coroutineScope {
        val prompt = prompt(hub, taste(hub) ?: return@coroutineScope emptyList(), Recs.minImdb().takeIf { it > 0 } ?: 7f, saved.history)
        val boss = boss()?.takeIf { it in ais }
        class Cand(val s: Sug, var points: Double, val by: MutableList<String>)
        val merged = LinkedHashMap<String, Cand>()
        fun keyOf(s: Sug) = s.imdb ?: "${norm(s.title)}:${s.year ?: ""}"
        fun add(label: String, list: List<Sug>, w: Double) = list.forEachIndexed { rank, s ->
            val c = merged[keyOf(s)]
            if (c == null) merged[keyOf(s)] = Cand(s, w / (RANK_K + rank), mutableListOf(label)) else { c.points += w / (RANK_K + rank); if (label !in c.by) c.by += label }
        }
        val workers = if (boss != null && ais.size > 1) ais - boss else ais
        val answers = workers.map { a -> async { a to runCatching { ask(a, prompt) }.onFailure { problems[a.label] = reason(it) }.getOrDefault(emptyList()) } }.awaitAll()
        if (boss == null || ais.size == 1) answers.forEach { (a, l) -> add(a.label, l, weight(a)) }
        else {
            val final = runCatching { ask(boss, bossPrompt(prompt, answers)) }.onFailure { problems[boss.label] = reason(it) }.getOrDefault(emptyList())
            if (final.isEmpty()) answers.forEach { (a, l) -> add(a.label, l, weight(a)) }
            else final.forEachIndexed { rank, s -> merged.getOrPut(keyOf(s)) { Cand(s, 0.0, mutableListOf(boss.label)) }.points += 1.0 / (RANK_K + rank) }
        }
        val ranked = merged.values.sortedByDescending { it.points }.take(45)
        val gate = Semaphore(4)
        val resolved = ranked.map { c -> async { gate.withPermit { resolve(c.s)?.let { it to c } } } }.awaitAll().filterNotNull().distinctBy { it.first }
        val seen = Recs.seenIds()
        val ids = resolved.map { it.first }.filter { it !in seen && Hubs.keepsId(hub, it) }
        val facts = Recs.factsFor(ids)
        val min = Recs.minImdb().takeIf { it > 0 } ?: 7f
        resolved.mapNotNull { (id, c) ->
            val f = facts[id] ?: return@mapNotNull null
            if (id in seen || !Hubs.keepsId(hub, id) || (f.rating ?: 0f) < min || Adult.hidesId(id) || LeaveOut.out(id, f.genres)) return@mapNotNull null
            Pick(id, if (f.tv) "series" else "movie", f.name, f.year, f.rating, f.poster, f.genres, null,
                listOfNotNull(c.s.reason, "Suggested by ${c.by.joinToString(" + ")}.").joinToString(" "), false, c.by.size)
        }.take(ROW)
    }

    /** IMDb id for a suggestion: its own (if IMDb knows it) or IMDb's search by title + year. Keyless. */
    private fun resolve(s: Sug): String? {
        s.imdb?.let { return it }
        val q = java.net.URLEncoder.encode(s.title.lowercase(), "UTF-8").replace("+", "%20")
        return runCatching {
            val o = JSONObject(Net.http.newCall(Request.Builder().url("${com.lekathedon.visionlite.data.Our.suggest}$q.json").build()).execute().use { it.body!!.string() })
            val d = o.optJSONArray("d") ?: return null
            (0 until d.length()).mapNotNull { d.optJSONObject(it) }.filter { it.optString("id").startsWith("tt") }
                .firstOrNull { e ->
                    val tv = e.optString("qid").contains("tv", true)
                    val y = e.optInt("y", 0)
                    tv == s.series && (s.year == null || y == 0 || kotlin.math.abs(y - s.year) <= 1)
                }?.optString("id")
        }.getOrNull()
    }

    /** Your taste in words, from Trakt (ratings, history), the Library and your verdicts. Null = nothing to learn from. */
    private suspend fun taste(hub: Hub): String? {
        val all = App.instance.trakt.taste()
        // Only this hub's titles describe the taste it asks about.
        fun keeps(id: String?) = id != null && Hubs.keepsId(hub, id)
        val t = all?.let { a -> com.lekathedon.visionlite.trakt.TraktTaste(
            movies = a.movies.filter { keeps(it.movie.ids.imdb) },
            shows = a.shows.filter { keeps(it.show.ids.imdb) },
            ratings = a.ratings.filter { keeps(it.movie?.ids?.imdb ?: it.show?.ids?.imdb) },
            paused = a.paused) }
        fun name(m: com.lekathedon.visionlite.trakt.TMovie?, s: com.lekathedon.visionlite.trakt.TShow?) =
            m?.let { "${it.title}${it.year?.let { y -> " ($y)" } ?: ""}" } ?: s?.let { "${it.title}${it.year?.let { y -> " ($y)" } ?: ""} [series]" }
        val ratings = t?.ratings.orEmpty().sortedByDescending { it.ratedAt }
        val loved = ratings.filter { it.rating >= 9 }.mapNotNull { name(it.movie, it.show) }.take(25)
        val liked = ratings.filter { it.rating in 7..8 }.mapNotNull { name(it.movie, it.show) }.take(25)
        val disliked = ratings.filter { it.rating in 1..4 }.mapNotNull { name(it.movie, it.show) }.take(15)
        val recent = (t?.movies.orEmpty().map { it.lastWatchedAt to name(it.movie, null) } + t?.shows.orEmpty().map { it.lastWatchedAt to name(null, it.show) })
            .sortedByDescending { it.first }.mapNotNull { it.second }.take(30)
        val fb = Recs.feedback().filter { Hubs.keepsId(hub, it.id) }
        val more = fb.filter { it.like && !it.watched }.map { it.name }.take(15)
        val less = fb.filter { !it.like }.map { it.name }.take(15)
        val saved = Saves.all().filter { Hubs.keepsId(hub, it.id) }.map { it.name }.take(20)
        if (loved.isEmpty() && liked.isEmpty() && recent.isEmpty() && more.isEmpty() && saved.isEmpty()) return null
        return buildString {
            if (loved.isNotEmpty() || more.isNotEmpty()) appendLine("Loved (rated 9-10, or asked for more like it): ${(loved + more).joinToString("; ")}.")
            if (liked.isNotEmpty()) appendLine("Liked (rated 7-8): ${liked.joinToString("; ")}.")
            if (recent.isNotEmpty()) appendLine("Watched recently, newest first: ${recent.joinToString("; ")}.")
            if (saved.isNotEmpty()) appendLine("Saved in their library (they know these already): ${saved.joinToString("; ")}.")
            if (disliked.isNotEmpty() || less.isNotEmpty()) appendLine("Disliked or turned down: ${(disliked + less).joinToString("; ")}. Avoid titles like these.")
        }
    }

    private fun prompt(hub: Hub, taste: String, min: Float, shown: List<String>) = buildString {
        if (hub == Hub.ANIME) {
            appendLine("You recommend anime (Japanese anime, and Chinese or Korean animation listed on AniList/MyAnimeList) to one person, from the anime they watch and rate on Trakt and their media app.")
            appendLine("Suggest anime only: never live-action titles, never Western animation (no Pixar, Disney, Arcane, Avatar: The Last Airbender, Castlevania).")
        } else {
            appendLine("You recommend movies and TV series to one person, from what they watch and rate on Trakt and their media app.")
            appendLine("Never suggest anime: this person keeps anime in a separate section of their app.")
        }
        append(taste)
        if (shown.isNotEmpty()) appendLine("Already suggested to them before, do not repeat: ${shown.takeLast(60).joinToString("; ")}.")
        appendLine()
        appendLine("Suggest $ASK titles they have not seen and would enjoy most next, best first. Mix movies and series when their taste covers both.")
        appendLine("Only real, released titles that IMDb rates ${"%.1f".format(min)} or higher. Do not list anything named above.")
        appendLine("Prefer close matches in tone, themes and audience over popularity; include some lesser-known titles.")
        append(LeaveOut.aiLines())
        if (Adult.on()) appendLine("Never suggest explicit or erotic films (unsimulated sex, softcore, porn); R-rated is fine.")
        append("Reply with only JSON: {\"picks\": [{\"title\": string, \"year\": number, \"type\": \"movie\" or \"series\", \"imdb_id\": string (tt…, empty if unsure), \"reason\": string (one short sentence naming what they watched)}]}")
    }

    private fun bossPrompt(base: String, answers: List<Pair<Ai, List<Sug>>>) = buildString {
        appendLine(base); appendLine()
        val all = answers.flatMap { (a, l) -> l.map { it to a.label } }.distinctBy { "${norm(it.first.title)}:${it.first.year}" }
        if (all.isEmpty()) appendLine("You decide alone this time.") else {
            appendLine("You are the final judge. Other AIs proposed these candidates (title (year) [type] - who):")
            all.forEach { (s, who) -> appendLine("- ${s.title}${s.year?.let { " ($it)" } ?: ""} [${if (s.series) "series" else "movie"}] - $who") }
            appendLine("Choose the best $ASK for this person, best first. Keep candidates you agree with, drop weak ones, and add your own where you know better ones.")
        }
        append("Use the same JSON reply format.")
    }

    /**
     * "Because you watched" helper: for each seed, titles closely like it (same kind of story, tone and audience), from
     * ONE request to your boss AI (else the first with a key). IMDb ids, matched like the picks. Empty when no AI is set.
     */
    internal fun alike(hub: Hub, seeds: List<Seed>, per: Int = 12): Map<String, List<String>> {
        val a = boss()?.takeIf { it.on } ?: configured().firstOrNull() ?: return emptyMap()
        val prompt = buildString {
            appendLine("For each title below, list $per real, released titles that are genuinely like it: the same kind of story, " +
                "genre, tone and audience (not just popular, not just the same actor or country). Prefer the same type (movie or series).")
            if (hub == Hub.ANIME) appendLine("Anime only.") else appendLine("No anime.")
            seeds.forEach { appendLine("- ${it.id}: ${it.name} [${if (it.tv) "series" else "movie"}]") }
            append("Reply with only JSON: {\"picks\": [{\"for\": \"tt… of the title above\", \"title\": string, \"year\": number, " +
                "\"type\": \"movie\" or \"series\", \"imdb_id\": string (tt…, empty if unsure), \"reason\": \"\"}]}")
        }
        val out = runCatching { askFor(a, prompt) }.onFailure { Log.w(TAG, "alike: ${it.message}") }.getOrDefault(emptyList())
        return out.groupBy({ it.first }, { it.second }).mapValues { (_, l) -> l.mapNotNull { runCatching { resolve(it) }.getOrNull() }.distinct() }
    }

    /** [ask] keeping each suggestion's "for" (the seed it answers). */
    private fun askFor(a: Ai, prompt: String): List<Pair<String, Sug>> {
        val fors = ArrayList<String>()
        val l = ask(a, prompt, fors)
        return l.mapIndexedNotNull { i, s -> fors.getOrNull(i)?.takeIf { it.startsWith("tt") }?.let { it to s } }
    }

    private fun ask(a: Ai, prompt: String, fors: MutableList<String>? = null): List<Sug> {
        val json = "application/json".toMediaType()
        val (req, read) = when (a) {
            Ai.GEMINI -> Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/${a.model}:generateContent").header("x-goog-api-key", a.key)
                .post(JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                    .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.9)).toString().toRequestBody(json)).build() to
                { o: JSONObject -> o.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text") }
            else -> Request.Builder().url(if (a == Ai.GROQ) "https://api.groq.com/openai/v1/chat/completions" else "https://api.mistral.ai/v1/chat/completions")
                .header("Authorization", "Bearer ${a.key}")
                .post(JSONObject().put("model", a.model).put("response_format", JSONObject().put("type", "json_object"))
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt))).toString().toRequestBody(json)).build() to
                { o: JSONObject -> o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content") }
        }
        val text = http.newCall(req).execute().use { r ->
            val b = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}: ${b.take(120)}")
            read(JSONObject(b))
        } ?: return emptyList()
        val t = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val arr = runCatching { JSONObject(t).optJSONArray("picks") }.getOrNull() ?: runCatching { JSONArray(t) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
            val title = o.optString("title").trim().ifBlank { return@mapNotNull null }
            fors?.add(o.optString("for").trim())
            Sug(title, o.optInt("year").takeIf { it in 1900..2100 },
                o.optString("type").lowercase().let { it == "series" || it == "tv" || it == "show" },
                o.optString("imdb_id").trim().takeIf { Regex("tt\\d{5,10}").matches(it) }, o.optString("reason").trim().ifBlank { null })
        }
    }

    private fun reason(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            e is java.io.InterruptedIOException || m.contains("timeout", true) -> "took too long"
            "HTTP 401" in m || "HTTP 403" in m || "API key not valid" in m -> "didn't accept the key"
            "HTTP 429" in m -> "is over its limit for now"
            "HTTP 404" in m -> "doesn't know that model"
            e is java.net.UnknownHostException -> "couldn't be reached"
            else -> m.substringBefore('\n').take(50)
        }
    }

    private fun norm(t: String) = t.lowercase().filter { it.isLetterOrDigit() }
}
