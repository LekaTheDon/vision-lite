package com.lekathedon.visionlite.integrations

import android.util.Base64
import android.util.Log
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * Join (2026-11-27): someone coming from Nuvio or Stremio signs in to it ONCE at first start and arrives with everything
 * that matters: their debrid (TorBox, Real-Debrid, AllDebrid, Premiumize, Offcloud), their add-ons, their library,
 * what they watched and where they stopped. Then the app signs out of that service and never calls it again.
 *
 * Where things land (what Lite already reads, so nothing new runs later):
 *  - debrid keys → Integrations (blank ones only: a key set here is never overwritten);
 *  - add-ons → the add-on list (in their order, duplicates skipped);
 *  - library → Saves (Library page), and the Trakt / Simkl watchlist;
 *  - watched → Trakt / Simkl history (Continue Watching, Coming up, "watched" marks);
 *  - where you stopped → Trakt playback (up to 50 most recent).
 * The tracker part waits (join.json in the profile folder) until Trakt or Simkl is signed in, then runs once and the file
 * is deleted. Cost: a few calls at Join, a few batched writes when the tracker signs in; nothing ever after.
 */
object Join {
    private const val TAG = "LiteJoin"
    private val JSON_T = "application/json".toMediaType()

    data class Item(val type: String, val id: String, val title: String, val poster: String? = null,
                    val season: Int? = null, val episode: Int? = null, val at: Long = 0, val pos: Long = 0, val dur: Long = 0)

    class Haul(val from: String, val addons: List<String>, val keys: Map<String, String>,
               val library: List<Item>, val watched: List<Item>, val progress: List<Item>, val restart: Boolean = false) {
        val summary get() = listOfNotNull(
            keys.size.takeIf { it > 0 }?.let { "$it debrid" },
            addons.size.takeIf { it > 0 }?.let { "$it add-on" + if (it > 1) "s" else "" },
            library.size.takeIf { it > 0 }?.let { "$it in your library" },
            watched.size.takeIf { it > 0 }?.let { "$it watched" },
            progress.size.takeIf { it > 0 }?.let { "$it to continue" }).joinToString(" · ").ifBlank { "nothing to bring over" }
    }

    private fun file() = File(App.instance.profileDir, "join.json")
    private val sp get() = App.instance.prefs.sp
    fun joinedFrom(): String? = sp.getString("joined_from", null)

    // ------------------------------------------------------------------ Nuvio

    /** Reads the signed-in Nuvio account's [profile] whole, then signs out of Nuvio for good. */
    suspend fun fromNuvio(profile: Int): Haul = withContext(Dispatchers.IO) {
        val keys = LinkedHashMap<String, String>()
        runCatching {
            val (c, r) = SignIns.nuvioCall("/rest/v1/rpc/sync_pull_provider_credentials", JSONObject().put("p_profile_id", profile).toString())
            if (c in 200..299) JSONArray(r).objs().forEach { row ->
                val id = when (row.optString("provider").lowercase()) {
                    "debrid:torbox" -> "torbox"; "debrid:realdebrid", "debrid:real-debrid" -> "realdebrid"
                    "debrid:alldebrid" -> "alldebrid"; "debrid:premiumize" -> "premiumize"; "debrid:offcloud" -> "offcloud"; else -> null
                } ?: return@forEach
                row.optJSONObject("credential_json")?.optString("api_key")?.trim()?.takeIf { it.isNotBlank() }?.let { keys.putIfAbsent(id, it) }
            }
        }
        val addons = runCatching {
            val (c, r) = SignIns.nuvioGet("/rest/v1/addons?select=url,enabled,sort_order&profile_id=eq.$profile&order=sort_order.asc")
            if (c !in 200..299) emptyList() else JSONArray(r).objs().filter { it.optBoolean("enabled", true) }.map { it.optString("url") }.filter { it.startsWith("http") }
        }.getOrDefault(emptyList())
        addons.forEach { u -> keysIn(u).forEach { (k, v) -> keys.putIfAbsent(k, v) } }

        val library = ArrayList<Item>()
        var offset = 0
        while (offset < 5000) {
            val (c, r) = SignIns.nuvioCall("/rest/v1/rpc/sync_pull_library",
                JSONObject().put("p_profile_id", profile).put("p_limit", 500).put("p_offset", offset).toString())
            val a = runCatching { JSONArray(r) }.getOrNull()
            if (c !in 200..299 || a == null) break
            a.objs().forEach { o -> item(o.optString("content_id"), o.optString("content_type"), o.optString("name"), o.optString("poster"), at = o.optLong("added_at"))?.let(library::add) }
            if (a.length() < 500) break; offset += 500
        }
        val watched = ArrayList<Item>()
        var page = 1
        while (page <= 20) {
            val (c, r) = SignIns.nuvioCall("/rest/v1/rpc/sync_pull_watched_items",
                JSONObject().put("p_profile_id", profile).put("p_page", page).put("p_page_size", 900).toString())
            val a = runCatching { JSONArray(r) }.getOrNull()
            if (c !in 200..299 || a == null) break
            a.objs().forEach { o ->
                item(o.optString("content_id"), o.optString("content_type"), o.optString("title"), null,
                    o.optInt("season").takeIf { o.has("season") && !o.isNull("season") }, o.optInt("episode").takeIf { o.has("episode") && !o.isNull("episode") },
                    o.optLong("watched_at"))?.let(watched::add)
            }
            if (a.length() < 900) break; page++
        }
        val progress = runCatching {
            val (c, r) = SignIns.nuvioCall("/rest/v1/rpc/sync_pull_watch_progress", JSONObject().put("p_profile_id", profile).put("p_limit", 200).toString())
            if (c !in 200..299) emptyList() else JSONArray(r).objs().mapNotNull { o ->
                item(o.optString("content_id"), o.optString("content_type"), "", null,
                    o.optInt("season").takeIf { o.has("season") && !o.isNull("season") }, o.optInt("episode").takeIf { o.has("episode") && !o.isNull("episode") },
                    o.optLong("last_watched"), o.optLong("position"), o.optLong("duration"))
            }
        }.getOrDefault(emptyList())
        Haul("Nuvio", addons, keys, library, watched, progress).also { forgetNuvio() }
    }

    /** Signed out of Nuvio and its saved account bits wiped: nothing here can call it again. */
    private fun forgetNuvio() {
        runCatching { Integrations.clear(Integrations.byId("nuvio")) }
        sp.edit().apply { sp.all.keys.filter { it.startsWith("si_nuvio") }.forEach { remove(it) } }.putBoolean("cloud_setup", false).apply()
    }

    // ------------------------------------------------------------------ Stremio

    private const val LINK = "https://link.stremio.com/api/v2"
    private const val API = "https://api.strem.io/api"

    /** Stremio's own scan-and-approve sign-in: (url to open, code). */
    suspend fun stremioStart(): SignIns.Code = withContext(Dispatchers.IO) {
        val j = JSONObject(Net.get("$LINK/create?type=Create", 10)).let { it.optJSONObject("result") ?: it }
        val code = j.optString("code").ifBlank { throw java.io.IOException("Stremio") }
        SignIns.Code(j.optString("link").ifBlank { "https://link.stremio.com/$code" }, code, 3, 600, code)
    }

    /** Waits for the approval; the account's auth key, null when it expired. */
    suspend fun stremioWait(c: SignIns.Code): String? = withContext(Dispatchers.IO) {
        val until = System.currentTimeMillis() + c.expiresIn * 1000L
        while (System.currentTimeMillis() < until) {
            delay(3000)
            val j = runCatching { JSONObject(Net.http.newCall(Request.Builder().url("$LINK/read?type=Read&code=${c.userCode}").build()).execute().use { it.body?.string().orEmpty() }) }.getOrNull() ?: continue
            j.optJSONObject("result")?.optString("authKey")?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        }
        null
    }

    private fun stremio(method: String, body: JSONObject): JSONObject? = runCatching {
        Net.http.newCall(Request.Builder().url("$API/$method").post(body.toString().toRequestBody(JSON_T)).build()).execute()
            .use { JSONObject(it.body?.string().orEmpty()) }
    }.getOrNull()

    /** Reads the Stremio account (add-ons, library with progress), then logs that session out. */
    suspend fun fromStremio(authKey: String): Haul = withContext(Dispatchers.IO) {
        val addons = stremio("addonCollectionGet", JSONObject().put("type", "AddonCollectionGet").put("authKey", authKey).put("update", true))
            ?.optJSONObject("result")?.optJSONArray("addons").objs().map { it.optString("transportUrl") }
            .filter { it.startsWith("http") && !it.contains("v3-cinemeta.strem.io") && !it.contains("/local") && !it.contains("watchhub") }
        val keys = LinkedHashMap<String, String>()
        addons.forEach { u -> keysIn(u).forEach { (k, v) -> keys.putIfAbsent(k, v) } }
        val items = stremio("datastoreGet", JSONObject().put("authKey", authKey).put("collection", "libraryItem").put("ids", JSONArray()).put("all", true))
            ?.optJSONArray("result").objs()
        val library = ArrayList<Item>(); val watched = ArrayList<Item>(); val progress = ArrayList<Item>()
        for (o in items) {
            val type = o.optString("type").let { if (it == "series") "series" else if (it == "movie") "movie" else return@let null } ?: continue
            val base = item(o.optString("_id"), type, o.optString("name"), o.optString("poster")) ?: continue
            val st = o.optJSONObject("state") ?: JSONObject()
            val last = runCatching { Instant.parse(st.optString("lastWatched")).toEpochMilli() }.getOrDefault(0L)
            // In the library = not removed and not a "temp" entry (Stremio keeps everything you opened as temp).
            if (!o.optBoolean("removed") && !o.optBoolean("temp")) library += base
            val vid = st.optString("video_id").ifBlank { null }
            val parts = vid?.split(':')
            val s = parts?.getOrNull(1)?.toIntOrNull(); val e = parts?.getOrNull(2)?.toIntOrNull()
            val pos = st.optLong("timeOffset"); val dur = st.optLong("duration")
            val done = dur > 0 && pos >= dur * 0.9
            if (type == "movie" && (st.optInt("timesWatched") > 0 || st.optInt("flaggedWatched") > 0 || done)) watched += base.copy(at = last)
            if (type == "series" && s != null && e != null && done) watched += base.copy(season = s, episode = e, at = last)
            if (pos > 0 && dur > 0 && !done && last > 0) progress += base.copy(season = s, episode = e, at = last, pos = pos, dur = dur)
        }
        runCatching { stremio("logout", JSONObject().put("type", "Logout").put("authKey", authKey)) }
        Haul("Stremio", addons, keys, library, watched, progress)
    }

    // ------------------------------------------------------------------ debrid keys inside add-on links

    private val SERVICES = mapOf("torbox" to "torbox", "realdebrid" to "realdebrid", "real-debrid" to "realdebrid", "rd" to "realdebrid",
        "alldebrid" to "alldebrid", "ad" to "alldebrid", "premiumize" to "premiumize", "pm" to "premiumize", "offcloud" to "offcloud", "oc" to "offcloud",
        "tb" to "torbox")

    /** "realdebrid=KEY|…" (Torrentio-style) and base64 JSON configs ({debridService, debridApiKey}, services lists…). */
    fun keysIn(url: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val path = runCatching { java.net.URI(url).path }.getOrNull() ?: return out
        for (seg in path.split('/').filter { it.isNotBlank() && it != "manifest.json" }) {
            val dec = runCatching { java.net.URLDecoder.decode(seg, "UTF-8") }.getOrDefault(seg)
            Regex("""(?i)\b(torbox|realdebrid|alldebrid|premiumize|offcloud)=([A-Za-z0-9_\-]{12,})""").findAll(dec).forEach { m ->
                out.putIfAbsent(m.groupValues[1].lowercase(), m.groupValues[2])
            }
            val json = runCatching { String(Base64.decode(dec.replace('-', '+').replace('_', '/'), Base64.DEFAULT)) }.getOrNull()
                ?.takeIf { it.trimStart().startsWith("{") } ?: continue
            runCatching { walk(JSONObject(json), out) }
        }
        return out
    }

    private fun walk(o: JSONObject, out: MutableMap<String, String>) {
        val svc = (o.optString("debridService").ifBlank { o.optString("service").ifBlank { o.optString("id") } }).lowercase()
        val key = o.optString("debridApiKey").ifBlank { o.optString("apiKey").ifBlank { o.optString("key").ifBlank { o.optString("token") } } }
        SERVICES[svc]?.let { s -> if (key.length >= 12) out.putIfAbsent(s, key) }
        for (k in o.keys()) {
            SERVICES[k.lowercase()]?.let { s -> o.optString(k).takeIf { it.length >= 12 && !it.startsWith("{") }?.let { out.putIfAbsent(s, it) } }
            when (val v = o.opt(k)) {
                is JSONObject -> walk(v, out)
                is JSONArray -> v.objs().forEach { walk(it, out) }
            }
        }
    }

    // ------------------------------------------------------------------ apply

    /** Keys, add-ons and library now; the tracker part when Trakt / Simkl is signed in (now or later, once). */
    suspend fun apply(h: Haul): String = withContext(Dispatchers.IO) {
        var keys = 0
        for ((id, key) in h.keys) when (id) {
            "realdebrid" -> if (!com.lekathedon.visionlite.streams.DebridSignIn.signedIn(id)) { sp.edit().putString("int_realdebrid_0", key).apply(); keys++ }
            "alldebrid" -> if (!com.lekathedon.visionlite.streams.DebridSignIn.signedIn(id)) { sp.edit().putString("si_alldebrid_token", key).apply(); keys++ }
            else -> runCatching { Integrations.byId(id) }.getOrNull()?.let { s -> if (!Integrations.isSet(s)) { Integrations.save(s, listOf(key)); keys++ } }
        }
        val store = App.instance.addons
        var added = 0
        for (u in h.addons) if (runCatching { store.install(u) }.isSuccess) added++
        val saved = com.lekathedon.visionlite.library.Saves.all().map { it.id }.toSet()
        h.library.filter { it.id !in saved }.reversed().forEach { com.lekathedon.visionlite.library.Saves.toggle(
            com.lekathedon.visionlite.library.Saved(it.type, it.id, it.title, it.poster?.takeIf { p -> p.startsWith("http") })) }
        file().writeText(JSONObject().put("from", h.from).put("library", items(h.library)).put("watched", items(h.watched)).put("progress", items(h.progress)).toString())
        sp.edit().putString("joined_from", h.from).putLong("joined_at", System.currentTimeMillis()).apply()
        Integrations.touch()
        runCatching { toTracker() }
        Log.i(TAG, "${h.from}: $keys keys, $added add-ons, ${h.library.size} library, ${h.watched.size} watched, ${h.progress.size} progress")
        h.summary
    }

    private val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    /**
     * A box that was signed in to Nuvio before Join existed: the same one-time import (its chosen profile), then Nuvio
     * is signed out for good. Once; nothing when not signed in.
     */
    fun migrate() {
        if (joinedFrom() != null || !SignIns.signedIn("nuvio")) return
        bg.launch { delay(30_000); runCatching { apply(fromNuvio(SignIns.nuvioProfile())) }.onFailure { Log.w(TAG, "migrate: ${it.message}") } }
    }

    /** [toTracker] in the background, only when an import is waiting (no file = no work, no calls). */
    fun later() { if (file().exists()) bg.launch { delay(20_000); runCatching { toTracker() } } }

    /** Runs the waiting tracker part once a tracker is signed in (called at Join, after a Trakt / Simkl sign-in, and at start). */
    suspend fun toTracker(): Boolean = withContext(Dispatchers.IO) {
        val f = file(); if (!f.exists()) return@withContext false
        val trakt = App.instance.trakt.signedIn(); val simkl = SignIns.signedIn("simkl")
        if (!trakt && !simkl) return@withContext false
        val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: run { f.delete(); return@withContext false }
        val library = back(o.optJSONArray("library")); val watched = back(o.optJSONArray("watched")); val progress = back(o.optJSONArray("progress"))
        // Trakt and Simkl know titles by IMDb or TMDB id (anime ids from Kitsu / MAL stay in Saves only).
        val tt = { i: Item -> i.id.startsWith("tt") || i.id.startsWith("tmdb:") }
        // History, in batches of 400. Trakt gets only what it doesn't already count (it adds a play each time);
        // Simkl just marks watched, so it gets everything.
        val have = if (trakt) App.instance.trakt.watchedKeys() else null
        if (trakt && have == null) return@withContext false   // not reachable: try again next time, nothing doubled
        watched.filter(tt).chunked(400).forEach { part ->
            if (trakt) {
                val fresh = part.filter { i -> (if (i.type == "movie") i.id else "${i.id}:${i.season}:${i.episode}") !in have!! }
                if (fresh.isNotEmpty()) App.instance.trakt.post("sync/history", Json.parseToJsonElement(history(fresh).toString()).jsonObject)
            }
            if (simkl) SignIns.simklPost("sync/history", history(part))
        }
        library.filter(tt).chunked(400).forEach { part ->
            val movies = JSONArray(); val shows = JSONArray()
            part.forEach { i -> (if (i.type == "movie") movies else shows).put(JSONObject().put("ids", ids(i.id)).apply { if (simkl) put("to", "plantowatch") }) }
            val body = JSONObject().put("movies", movies).put("shows", shows)
            if (trakt) App.instance.trakt.post("sync/watchlist", Json.parseToJsonElement(body.toString()).jsonObject)
            if (simkl) SignIns.simklPost("sync/add-to-list", body)
        }
        // Where you stopped: Trakt keeps it as a paused playback (Simkl has no such thing).
        if (trakt) progress.filter { it.id.startsWith("tt") }.sortedByDescending { it.at }.take(50).forEach { i ->
            App.instance.trakt.scrobble("pause", i.id, i.season, i.episode, (i.pos * 100f / i.dur).coerceIn(1f, 89f))
            delay(350)
        }
        f.delete()
        true
    }

    private fun history(part: List<Item>): JSONObject {
        fun iso(ms: Long) = Instant.ofEpochMilli(if (ms > 0) ms else System.currentTimeMillis()).toString()
        val movies = JSONArray()
        part.filter { it.type == "movie" }.forEach { movies.put(JSONObject().put("ids", ids(it.id)).put("watched_at", iso(it.at))) }
        val shows = JSONArray()
        part.filter { it.type != "movie" && it.season != null && it.episode != null }.groupBy { it.id }.forEach { (id, eps) ->
            val seasons = JSONArray()
            eps.groupBy { it.season!! }.forEach { (s, l) ->
                seasons.put(JSONObject().put("number", s).put("episodes", JSONArray().apply {
                    l.distinctBy { it.episode }.forEach { put(JSONObject().put("number", it.episode).put("watched_at", iso(it.at))) } }))
            }
            shows.put(JSONObject().put("ids", ids(id)).put("seasons", seasons))
        }
        return JSONObject().put("movies", movies).put("shows", shows)
    }

    // ------------------------------------------------------------------ helpers

    private fun ids(id: String) = if (id.startsWith("tmdb:")) JSONObject().put("tmdb", id.substringAfter(':').toIntOrNull() ?: 0) else JSONObject().put("imdb", id)

    /** An item from "tt123", "tt123:1:2" or "tmdb:…" (types: movie / series / tv / anime). */
    private fun item(raw: String, type0: String, title: String, poster: String?, season: Int? = null, episode: Int? = null,
                     at: Long = 0, pos: Long = 0, dur: Long = 0): Item? {
        val type = when (type0.lowercase()) { "movie" -> "movie"; "series", "tv", "anime", "show" -> "series"; else -> return null }
        val parts = raw.split(':')
        val id = when {
            parts[0].startsWith("tt") -> parts[0]
            parts[0] in setOf("tmdb", "kitsu", "mal", "anilist") && parts.size > 1 -> parts[0] + ":" + parts[1]
            else -> return null
        }
        val s = season ?: parts.getOrNull(1)?.toIntOrNull()?.takeIf { parts[0].startsWith("tt") }
        val e = episode ?: parts.getOrNull(2)?.toIntOrNull()?.takeIf { parts[0].startsWith("tt") }
        return Item(type, id, title, poster?.ifBlank { null }?.takeIf { it != "null" }, s, e, at, pos, dur)
    }

    private fun items(l: List<Item>) = JSONArray().apply {
        l.forEach { put(JSONObject().put("t", it.type).put("id", it.id).put("n", it.title).put("s", it.season ?: -1).put("e", it.episode ?: -1)
            .put("at", it.at).put("p", it.pos).put("d", it.dur)) }
    }
    private fun back(a: JSONArray?) = a.objs().map { Item(it.optString("t"), it.optString("id"), it.optString("n"), null,
        it.optInt("s", -1).takeIf { s -> s >= 0 }, it.optInt("e", -1).takeIf { e -> e >= 0 }, it.optLong("at"), it.optLong("p"), it.optLong("d")) }

    private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
