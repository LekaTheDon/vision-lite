package com.lekathedon.visionlite.sport

import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.iptv.Iptv
import com.lekathedon.visionlite.recs.AiPicks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Gemini as a second pair of eyes on YOUR playlists and guide (no web search): it gets the game, the networks ESPN
 * lists and a short list of your channels with what they show at kick-off, and says which ones carry it. What it finds
 * is remembered as "this league on this network = this channel" ([learned]), so the next game of that league needs no
 * call at all: the AI teaches the matcher instead of being asked every time.
 *
 * When: a game page with no channels found ("Find with Gemini"), or by itself for games on screen when
 * "Gemini helps find channels" is on. Uses your Gemini key, else our server's. At most 1 call at a time, 30 an hour,
 * each answer kept per game for the day. Cost: one small request (~3 KB) per game it's asked about.
 */
object AiMatch {
    data class Pick(val key: String, val sure: Int)

    private val file by lazy { File(App.instance.filesDir, "sport/ai_learned.json") }
    private val one = Mutex()
    private val asked = HashMap<String, Pair<Long, List<Pick>>>()
    private val calls = ArrayDeque<Long>()

    fun available() = AiPicks.Ai.GEMINI.on && Iptv.playlists().isNotEmpty()

    // ---- what it taught us: "league|network" (or "league|" for no network) → channel key ----

    @Volatile private var mem: Map<String, String>? = null
    fun learned(): Map<String, String> = mem ?: runCatching {
        JSONObject(file.readText()).let { o -> o.keys().asSequence().associateWith { o.optString(it) } }
    }.getOrDefault(emptyMap()).also { mem = it }

    private fun learn(pairs: Map<String, String>) {
        if (pairs.isEmpty()) return
        val m = (learned() + pairs).entries.toList().takeLast(400).associate { it.key to it.value }
        mem = m
        runCatching { file.parentFile?.mkdirs(); file.writeText(JSONObject(m).toString()) }
    }
    /** "Wrong channel" on a channel it taught: unlearned. */
    fun forget(channelKey: String) {
        val m = learned().filterValues { it != channelKey }
        mem = m; runCatching { file.writeText(JSONObject(m).toString()) }
        synchronized(asked) { asked.replaceAll { _, v -> v.first to v.second.filter { it.key != channelKey } } }
    }

    /** Already asked today for this game: its answer (no call). */
    fun cached(g: Game): List<Pick>? = synchronized(asked) { asked[g.key] }?.takeIf { System.currentTimeMillis() - it.first < 12 * 3_600_000L }?.second

    /** Ask Gemini which of your channels show [g]; empty when it can't tell, it's off, or the hourly budget is used. */
    suspend fun find(g: Game): List<Pick> = cached(g) ?: one.withLock {
        cached(g) ?: withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            synchronized(calls) { while (calls.isNotEmpty() && now - calls.first() > 3_600_000L) calls.removeFirst(); if (calls.size >= 30) return@withContext emptyList<Pick>(); calls.addLast(now) }
            val cands = candidates(g)
            if (cands.isEmpty()) return@withContext emptyList<Pick>()
            val picks = runCatching { ask(g, cands) }.getOrDefault(emptyList())
            synchronized(asked) { if (asked.size > 300) asked.clear(); asked[g.key] = now to picks }
            // Teach the matcher: sure picks become this league + network's channel.
            val sure = picks.filter { it.sure >= 75 }.take(2)
            if (sure.isNotEmpty()) learn((g.networks.ifEmpty { listOf("") }).take(2).associate { n -> "${g.league}|$n" to sure.first().key })
            picks
        }
    }

    private class Cand(val key: String, val name: String, val group: String, val show: String?)

    /**
     * What Gemini sees (≤ 120 lines): channels whose guide at kick-off names a side, the league or the sport, then
     * channels named like the networks or in a sports group. Only names and show titles leave the box, never links.
     */
    private fun candidates(g: Game): List<Cand> {
        val db = runCatching { Iptv.guideDb() }.getOrNull() ?: return emptyList()
        val at = g.startMs + 10 * 60_000L
        val league = Leagues.byKey(g.league)
        val words = (g.sides.flatMap { listOf(it.short, it.name) } + listOfNotNull(league?.name, league?.short) + league?.words.orEmpty() +
            league?.sport?.words.orEmpty().take(3) + (if (g.versus) emptyList() else g.title.split(' ').filter { it.length > 3 }.take(3)))
            .map { it.trim() }.filter { it.length >= 3 }.distinct().take(14)
        val out = LinkedHashMap<String, Cand>()
        runCatching {
            db.rawQuery("SELECT c.pl, c.url, c.name, c.grp, p.title FROM prog p JOIN ch c ON c.pl = p.pl AND c.tvg = p.tvg " +
                "WHERE p.start <= ? AND p.stop > ? AND (" + words.joinToString(" OR ") { "p.title LIKE ?" } + ") LIMIT 80",
                arrayOf(at.toString(), at.toString()) + words.map { "%$it%" }).use { q ->
                while (q.moveToNext()) { val k = "${q.getString(0)}|${q.getString(1)}"; out.getOrPut(k) { Cand(k, q.getString(2).orEmpty(), q.getString(3).orEmpty(), q.getString(4)) } }
            }
        }
        val nets = (g.networks + listOfNotNull(league?.network)).map { Matcher.norm(it) }.filter { it.length >= 2 }
        val groupy = listOf("sport", "bein", "sky sports", "espn", "dazn", "tnt", "ssc", "alkass", "al kass", "arena", "eurosport", "football")
        runCatching {
            db.rawQuery("SELECT pl, url, name, grp FROM ch", null).use { q ->
                while (q.moveToNext() && out.size < 120) {
                    val name = q.getString(2).orEmpty(); val grp = q.getString(3).orEmpty()
                    val n = Matcher.norm(name); val gr = Matcher.norm(grp)
                    val hit = nets.any { it in n } || groupy.any { it in n || it in gr }
                    if (!hit) continue
                    val k = "${q.getString(0)}|${q.getString(1)}"
                    out.getOrPut(k) { Cand(k, name, grp, null) }
                }
            }
        }
        return out.values.take(120).toList()
    }

    private fun ask(g: Game, c: List<Cand>): List<Pick> {
        val ai = AiPicks.Ai.GEMINI
        val league = Leagues.byKey(g.league)
        val zone = java.time.ZoneId.systemDefault()
        val kick = java.time.Instant.ofEpochMilli(g.startMs).atZone(zone).toLocalDateTime().toString()
        val lines = c.mapIndexed { i, x -> "$i | ${x.name} | ${x.group}" + (x.show?.let { " | now: $it" } ?: "") }.joinToString("\n")
        val prompt = """You match a live sports event to the user's own IPTV channels. Use only the list below, no outside lookup.
Event: ${Host.title(g)}${league?.let { " (${it.name})" }.orEmpty()}, kick-off $kick (${zone.id}).
Broadcasters listed by the league: ${g.networks.ifEmpty { listOf("unknown") }.joinToString()}.
Viewer's region: ${Scores.region()}.
Channels (index | name | group | what its guide shows at kick-off, if known):
$lines
Pick the channels most likely showing this event live: a guide entry naming it, or a channel of a listed broadcaster or of the rights holder in the viewer's region (e.g. beIN in MENA, Sky in UK). Never pick replays, highlights or other games.
Answer JSON only: {"picks":[{"i":<index>,"sure":<0-100>}]} best first, at most 6, empty if none fit."""
        val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/${ai.model}:generateContent").header("x-goog-api-key", ai.key)
            .post(JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.1)).toString()
                .toRequestBody("application/json".toMediaType())).build()
        return Net.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return emptyList()
            val text = JSONObject(r.body!!.string()).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text") ?: return emptyList()
            JSONObject(text).optJSONArray("picks").objs().mapNotNull { p ->
                c.getOrNull(p.optInt("i", -1))?.let { Pick(it.key, p.optInt("sure").coerceIn(0, 100)) }
            }.filter { it.sure >= 40 }.distinctBy { it.key }.take(6)
        }
    }
}
