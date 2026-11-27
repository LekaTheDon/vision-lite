package com.lekathedon.visionlite.sport

import com.lekathedon.visionlite.iptv.Channel
import com.lekathedon.visionlite.iptv.Iptv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer

/**
 * Which of your channels shows a game. Order of trust: your own picks (game, team, league network, network, league),
 * then the guide (a show at that time naming both sides, or one side plus the league), then the networks ESPN lists
 * matched by channel name. All on IO, cached per game until the channels or the guide change.
 */
object Matcher {
    data class Hit(val channel: Channel, val why: Why, val score: Int)
    enum class Why(val label: String) { YOURS("Your pick"), GUIDE("In the guide"), AI("Found by Gemini"), NETWORK("Network"), LEAGUE("League channel") }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- channel index: name words per channel, rebuilt when the playlists change ----

    private class Entry(val key: String, val pl: String, val tvg: String?, val name: String, val group: String, val words: String)
    @Volatile private var index: Pair<Int, List<Entry>>? = null

    private fun entries(): List<Entry> {
        val v = Iptv.version.value
        index?.takeIf { it.first == v }?.let { return it.second }
        val list = ArrayList<Entry>()
        runCatching {
            Iptv.guideDb().rawQuery("SELECT pl, url, tvg, name, grp FROM ch", null).use { q ->
                while (q.moveToNext()) {
                    val name = q.getString(3).orEmpty()
                    list += Entry("${q.getString(0)}|${q.getString(1)}", q.getString(0), q.getString(2)?.ifBlank { null }, name, q.getString(4).orEmpty(), norm(name))
                }
            }
        }
        index = v to list
        return list
    }

    fun norm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        .replace(Regex("\\b(hd|fhd|uhd|4k|sd|hevc|h265|raw|backup|vip|\\+1)\\b"), " ").replace(Regex("[^a-z0-9]+"), " ").trim()

    /** "usa net" → "usa network" etc. Networks named differently on ESPN and in playlists. */
    private val aliases = mapOf(
        "usa net" to "usa network", "fs1" to "fox sports 1", "fs2" to "fox sports 2", "nbcsn" to "nbc sports",
        "cbssn" to "cbs sports network", "espn deportes" to "espn deportes", "tnt sports" to "tnt sports", "peacock" to "peacock",
        "bein" to "bein sports", "skysports" to "sky sports", "sky sports pl" to "sky sports premier league",
    )

    // ---- cache ----

    private val cache = HashMap<String, Pair<String, List<Hit>>>()
    val changed = MutableStateFlow(0)
    private fun stamp() = "${Iptv.version.value}:${runCatching { Iptv.guideRevision() }.getOrNull()}:${Scores.settings.value.hashCode()}"

    fun cached(g: Game): List<Hit>? = synchronized(cache) { cache[g.key] }?.takeIf { it.first == stamp() }?.second

    suspend fun channels(g: Game): List<Hit> = cached(g) ?: withContext(Dispatchers.IO) {
        var hits = runCatching { find(g) }.getOrDefault(emptyList())
        // Part of the same search: when the guide and your picks found nothing for a game on now or soon, and you
        // shared your own Gemini key, Gemini reads the same guide + channel names and the search runs again with it.
        val soon = g.state == State.LIVE || g.state == State.PRE && g.startMs < System.currentTimeMillis() + 2 * 3_600_000L
        if (soon && hits.none { it.why == Why.GUIDE || it.why == Why.YOURS } && AiMatch.available() && AiMatch.cached(g) == null &&
            AiMatch.find(g).isNotEmpty()) hits = runCatching { find(g) }.getOrDefault(hits)
        synchronized(cache) { if (cache.size > 400) cache.clear(); cache[g.key] = stamp() to hits }
        hits
    }

    /** Fill the cache for a list (Live now, a day's cards) in the background; screens read [cached] and [changed]. */
    fun warm(games: List<Game>) = scope.launch {
        var any = false
        games.take(80).forEach { if (cached(it) == null) { channels(it); any = true } }
        if (any) changed.value++
    }

    private fun find(g: Game): List<Hit> {
        val s = Scores.settings.value
        val all = entries(); if (all.isEmpty()) return emptyList()
        val byKey = all.associateBy { it.key }
        val out = LinkedHashMap<String, Pair<Why, Int>>()
        fun add(key: String?, why: Why, score: Int) {
            if (key == null || key in s.blocked || key !in byKey) return
            val e = byKey[key]!!
            val bonus = if (e.group in s.preferredGroups) 15 else 0
            val prev = out[key]
            if (prev == null || prev.second < score + bonus) out[key] = why to score + bonus
        }
        // 1. Your picks.
        add(s.eventChannels[g.key] ?: s.eventChannels[g.id], Why.YOURS, 1000)
        g.sides.forEach { add(s.teamChannels["${g.league}:${it.id}"] ?: s.teamChannels[it.id], Why.YOURS, 900) }
        g.networks.forEach { n -> add(s.leagueNetworkChannels["${g.league}|$n"], Why.YOURS, 850); add(s.networkChannels[n], Why.YOURS, 800) }
        add(s.leagueChannels[g.league], Why.YOURS, 700)
        // 2. The guide.
        guide(g, all, s.matchCategories).forEach { (k, sc) -> add(k, Why.GUIDE, sc) }
        // 2b. Gemini: what it found for this game, and what it taught for this league + network.
        AiMatch.cached(g)?.forEach { add(it.key, Why.AI, 330 + it.sure) }
        val taught = AiMatch.learned()
        (g.networks.ifEmpty { listOf("") }).forEach { n -> add(taught["${g.league}|$n"], Why.AI, 420) }
        // 3. Networks by name; then the league's own channel.
        val league = Leagues.byKey(g.league)
        (g.networks + listOfNotNull(league?.network)).forEach { net ->
            val want = norm(aliases[net.lowercase()] ?: net); if (want.length < 2) return@forEach
            all.filter { e -> e.words == want || e.words.startsWith("$want ") || e.words.contains(" $want ") || e.words.endsWith(" $want") }
                .take(12).forEach { add(it.key, if (net == league?.network) Why.LEAGUE else Why.NETWORK, 300 - it.words.length.coerceAtMost(60)) }
        }
        val keys = out.entries.sortedByDescending { it.value.second }.take(20).map { it.key }
        val chans = Iptv.channelsByKey(keys).associateBy { it.key }
        return keys.mapNotNull { k -> chans[k]?.let { Hit(it, out[k]!!.first, out[k]!!.second) } }
            .let { if (s.bestChannelFirst) it else it.sortedBy { h -> h.channel.n } }
    }

    private val sportWords = listOf("sport", "football", "soccer", "basket", "hockey", "baseball", "racing", "golf", "tennis", "rugby", "cricket", "fight", "boxing", "ufc", "live")

    /** Shows airing at kick-off whose title names the game. */
    private fun guide(g: Game, all: List<Entry>, categories: Boolean): List<Pair<String, Int>> {
        val names = g.sides.flatMap { listOf(it.short, it.name, it.abbr.takeIf { a -> a.length >= 3 }) }.filterNotNull().map(::norm).filter { it.length >= 3 }.distinct()
        val league = Leagues.byKey(g.league)
        val leagueWords = (league?.words.orEmpty() + listOfNotNull(league?.name)).map(::norm)
        val probes = (if (g.versus) g.sides.map { norm(it.short) } else listOf(norm(g.title).split(' ').filter { it.length > 3 }.take(3).joinToString(" "))).filter { it.length >= 3 }
        if (probes.isEmpty()) return emptyList()
        val at = g.startMs + 10 * 60_000L
        val rows = ArrayList<Triple<String, String, String?>>()   // pl|tvg, title, cat
        runCatching {
            val like = probes.joinToString(" OR ") { "title LIKE ?" }
            // Not a broadcast: blocks of 6 h or more (24/7 channels, "Live sport" fillers).
            Iptv.guideDb().rawQuery("SELECT pl, tvg, title, cat FROM prog WHERE start <= ? AND stop > ? AND stop - start < 21600000 AND ($like) LIMIT 300",
                arrayOf(at.toString(), at.toString()) + probes.map { "%${it.substringBefore(' ')}%" }).use { q ->
                while (q.moveToNext()) rows += Triple("${q.getString(0)}|${q.getString(1)}", q.getString(2).orEmpty(), q.getString(3))
            }
        }
        if (rows.isEmpty()) return emptyList()
        val byTvg = all.filter { it.tvg != null }.groupBy { "${it.pl}|${it.tvg}" }
        val others = g.versus && rows.isNotEmpty()
        return rows.flatMap { (tvg, title, cat) ->
            val t = norm(title)
            // A listing that is just the channel's own name isn't the game.
            if (byTvg[tvg].orEmpty().any { it.words == t }) return@flatMap emptyList()
            // "Bills at Raiders" names a different game: two sides, neither ours.
            val pairing = others && Regex("\\b(v|vs|at)\\b").containsMatchIn(t)
            val sides = if (g.versus) g.sides.count { s -> listOf(s.short, s.name, s.abbr).map(::norm).any { it.length >= 3 && Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(t) } } else 1
            val hasLeague = leagueWords.any { it.isNotEmpty() && it in t }
            val sporty = cat != null && sportWords.any { it in cat.lowercase() }
            val score = when {
                g.versus && sides == 2 -> 600
                // One of ours against someone else ("Bills at Raiders") is a different game.
                g.versus && sides == 1 && hasLeague && !pairing -> 450
                !g.versus && names.isEmpty() && hasLeague -> 400
                else -> return@flatMap emptyList()
            } + (if (categories && sporty) 40 else 0) - (if (Regex("\\b(highlights|replay|preview|review|classic)\\b").containsMatchIn(t)) 300 else 0)
            if (score < 300) emptyList() else byTvg[tvg].orEmpty().map { it.key to score }
        }
    }

    // ---- games only the guide knows (darts, snooker, wrestling…: names you follow) ----

    @Volatile private var guideMem: Pair<String, List<Game>>? = null

    /** Today's and tomorrow's shows naming a followed word, one game per title and start, its channels attached. */
    suspend fun guideGames(words: List<String>): List<Game> = withContext(Dispatchers.IO) {
        if (words.isEmpty()) return@withContext emptyList()
        val st = "${stamp()}:${words.joinToString()}"
        guideMem?.takeIf { it.first == st }?.let { return@withContext it.second }
        val now = System.currentTimeMillis(); val byTvg = entries().filter { it.tvg != null }.groupBy { "${it.pl}|${it.tvg}" }
        val found = LinkedHashMap<String, Pair<Game, MutableList<String>>>()
        runCatching {
            Iptv.guideDb().rawQuery("SELECT pl, tvg, title, start, stop FROM prog WHERE stop > ? AND start < ? AND stop - start < 21600000 AND (" +
                words.joinToString(" OR ") { "title LIKE ?" } + ") ORDER BY start LIMIT 400",
                arrayOf(now.toString(), (now + 2 * 86_400_000L).toString()) + words.map { "%$it%" }).use { q ->
                while (q.moveToNext()) {
                    val title = q.getString(2).orEmpty(); val t = norm(title)
                    if (Regex("\\b(highlights|replay|preview|review|classic|classics|condensed)\\b").containsMatchIn(t)) continue
                    val start = q.getLong(3); val stop = q.getLong(4); val id = "${t.hashCode()}_${start / 60_000}"
                    val chans = byTvg["${q.getString(0)}|${q.getString(1)}"].orEmpty().map { it.key }
                    if (chans.isEmpty()) continue
                    val state = if (now in start until stop) State.LIVE else State.PRE
                    found.getOrPut(id) { Game(id, "guide", start, state, if (state == State.LIVE) "On now" else "", emptyList(), title, emptyList()) to mutableListOf() }.second += chans
                }
            }
        }
        synchronized(cache) { found.values.forEach { (g, keys) ->
            val ch = Iptv.channelsByKey(keys.distinct().take(20)); cache[g.key] = stamp() to ch.map { Hit(it, Why.GUIDE, 500) } } }
        val list = found.values.map { it.first }
        guideMem = st to list
        Scores.looked.value = Scores.looked.value + list.associateBy { it.key }   // their game pages
        list
    }

    // ---- your corrections ----

    fun pick(g: Game, c: Channel, scope: String) {
        val s = Scores.settings.value
        Scores.save(when (scope) {
            "team" -> s.copy(teamChannels = s.teamChannels + g.sides.associate { "${g.league}:${it.id}" to c.key })
            "network" -> g.networks.firstOrNull()?.let { s.copy(networkChannels = s.networkChannels + (it to c.key)) } ?: s
            "league" -> s.copy(leagueChannels = s.leagueChannels + (g.league to c.key))
            else -> s.copy(eventChannels = s.eventChannels + (g.key to c.key))
        })
    }
    fun block(c: Channel) { AiMatch.forget(c.key); val s = Scores.settings.value; Scores.save(s.copy(blocked = s.blocked + c.key)) }

    // ---- Refresh channels: one shared job for the Sports page, game pages and the clock ----

    private var refreshJob: Job? = null
    val refreshing = MutableStateFlow(false)

    /** Reload the playlists and guides, then every board; a second press while it runs joins the first. */
    @Synchronized fun refresh(): Job = refreshJob?.takeIf { it.isActive } ?: scope.launch {
        refreshing.value = true
        try {
            runCatching { Iptv.refreshAll() }
            index = null; synchronized(cache) { cache.clear() }
            Scores.pass(Scores.today(), force = true)
            changed.value++
        } finally { refreshing.value = false }
    }.also { refreshJob = it }

    /** First visit with playlists never loaded: load them (Sports counts as asking for IPTV). */
    fun ensure() = scope.launch { if (Iptv.playlists().isNotEmpty() && Iptv.neverLoaded()) { Iptv.ensureLoaded(); index = null; changed.value++ } }
}
