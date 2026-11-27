package com.lekathedon.visionlite.link

import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.integrations.Integrations
import com.lekathedon.visionlite.integrations.SignIns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * The whole setup from the paired phone (user 2026-11-25: "whoever downloads my app could set up the whole app from
 * the phone"): every integration (keys, website sign-ins), YouTube, IPTV playlists, the taste questions and Anime's
 * mode. The TV keeps every token: a sign-in is started HERE, the phone only shows the code and opens the website, and
 * the TV waits for the approval (the same flows as the TV's own sign-in pages). Keys are sealed like the rest of the
 * link (HMAC-signed, home network only). Cost: nothing until the phone asks.
 */
object Setup {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** The sign-in the phone started last, per service: its code, then "waiting" / "done" / "failed". */
    private class Pending(val url: String, val code: String, @Volatile var state: String, val job: Job?)
    private val pending = HashMap<String, Pending>()
    /** Website sign-ins the TV can start for the phone. */
    private val CODE_FLOWS = setOf("trakt", "simkl", "nuvio", "realdebrid", "alldebrid", "plex", "jellyfin")

    fun handle(a: JSONObject): JSONObject = when (a.optString("do")) {
        "state" -> state()
        "key" -> key(a)
        "signin" -> signIn(a.optString("id"))
        "paste" -> paste(a.optString("id"), a.optString("text"))
        "signout" -> signOut(a.optString("id"))
        "iptvAdd" -> iptvAdd(a)
        "iptvRemove" -> { com.lekathedon.visionlite.iptv.Iptv.remove(a.optString("pl")); state() }
        "taste" -> taste(a)
        "anime" -> { com.lekathedon.visionlite.anime.Hubs.setMode(a.optString("mode", "separate")); state() }
        "finish" -> { Integrations.setupDone.value = true; App.instance.prefs.tasteStartDone.value = true; state() }
        else -> JSONObject().put("ok", false).put("error", "unknown setup step")
    }

    private fun isSet(id: String) = when (id) {
        else -> Integrations.all.firstOrNull { it.id == id }?.let(Integrations::isSet) ?: false
    }

    private fun user(id: String): String = when {
        id == "trakt" -> ""
        id in Integrations.DEBRID -> com.lekathedon.visionlite.streams.DebridSignIn.user(id)
        id in Integrations.SERVERS -> com.lekathedon.visionlite.streams.MediaServers.user(id)
        else -> SignIns.user(id)
    }

    /** What is set (never the keys themselves), what can be done, and the choices the taste questions offer. */
    fun state(): JSONObject {
        val services = JSONArray()
        Integrations.all.filter { it.id != "youtube" && it.id != "imdb" }.forEach { s ->
            services.put(JSONObject().put("id", s.id).put("name", s.name).put("group", s.group.title).put("live", s.live ?: "")
                .put("where", s.where).put("set", isSet(s.id)).put("user", user(s.id))
                .put("how", when { s.id in CODE_FLOWS -> "code"; s.id == "anilist" || s.id == "mal" -> "paste"; s.fields.isNotEmpty() -> "key"; else -> "none" })
                .put("fields", JSONArray(s.fields.map { it.label }))
                .put("status", Integrations.status(s).value)
                .put("link", when (s.id) { "anilist" -> SignIns.aniListUrl(); "mal" -> SignIns.malUrl(); else -> "" })
                .put("pending", pending[s.id]?.let { JSONObject().put("url", it.url).put("code", it.code).put("state", it.state) }))
        }
        val pls = JSONArray(com.lekathedon.visionlite.iptv.Iptv.playlists().map { pl ->
            JSONObject().put("id", pl.id).put("name", pl.name).put("kind", if (pl.xtream) "xtream" else "m3u")
        })
        val interests = JSONArray(com.lekathedon.visionlite.recs.Interests.GROUPS.map { (g, r) ->
            JSONObject().put("group", g).put("ids", JSONArray(com.lekathedon.visionlite.recs.Interests.inGroup(r).map { id ->
                JSONObject().put("id", id).put("name", com.lekathedon.visionlite.recs.Interests.name(id) ?: id) }))
        }.filter { it.getJSONArray("ids").length() > 0 })
        val anime = JSONArray(com.lekathedon.visionlite.anime.MalGenres.GROUPS.map { (g, ids) ->
            JSONObject().put("group", g).put("ids", JSONArray(ids.map { id -> JSONObject().put("id", id).put("name", com.lekathedon.visionlite.recs.Interests.name(id) ?: id) }))
        })
        val people = JSONArray(com.lekathedon.visionlite.home.People.list.value.filter { it.hub == "NUVIO" }.map { JSONObject().put("id", it.id).put("name", it.name) })
        val loved = JSONArray(com.lekathedon.visionlite.recs.Recs.feedback().filter { it.like && !it.watched }.take(200).map {
            JSONObject().put("id", it.id).put("name", it.name).put("type", if (it.tv) "series" else "movie").put("love", it.love) })
        return JSONObject().put("ok", true)
            .put("setupDone", Integrations.setupDone.value).put("tasteDone", App.instance.prefs.tasteStartDone.value)
            .put("animeMode", com.lekathedon.visionlite.anime.Hubs.mode())
            .put("services", services).put("playlists", pls)
            .put("interests", interests).put("animeGenres", anime)
            .put("liked", JSONArray(com.lekathedon.visionlite.recs.Interests.liked().toList()))
            .put("people", people).put("loved", loved)
    }

    private fun key(a: JSONObject): JSONObject {
        val s = Integrations.all.firstOrNull { it.id == a.optString("id") && it.fields.isNotEmpty() } ?: return err("No such key")
        val v = a.optJSONArray("values") ?: JSONArray()
        Integrations.save(s, (0 until v.length()).map { v.optString(it) })
        // The same check the TV's own page runs: "ok" / "refused" shows on both.
        val good = runCatching { runBlocking { Integrations.check(s) } }.getOrNull()
        return state().put("checked", good)
    }

    private fun signIn(id: String): JSONObject {
        if (id !in CODE_FLOWS) return err("No website sign-in for $id")
        synchronized(pending) { pending.remove(id)?.job?.cancel() }
        val code: Triple<String, String, suspend () -> Boolean> = runCatching { runBlocking { start(id) } }.getOrElse { return err("Couldn't reach ${id}: ${it.message}") }
        val p = Pending(code.first, code.second, "waiting", scope.launch {
            val ok = runCatching { code.third() }.getOrDefault(false)
            synchronized(pending) { pending[id]?.state = if (ok) "done" else "failed" }
            if (ok) after(id)
        })
        synchronized(pending) { pending[id] = p }
        return state().put("url", code.first).put("code", code.second)
    }

    /** Starts [id]'s sign-in: (website, code to enter there, wait for the approval). */
    private suspend fun start(id: String): Triple<String, String, suspend () -> Boolean> = when (id) {
        "trakt" -> App.instance.trakt.startLogin().let { dc -> Triple(dc.url, dc.userCode, suspend { App.instance.trakt.waitForLogin(dc) }) }
        "realdebrid", "alldebrid" -> com.lekathedon.visionlite.streams.DebridSignIn.start(id).let { c -> Triple(c.url, c.userCode, suspend { com.lekathedon.visionlite.streams.DebridSignIn.wait(id, c) }) }
        "plex" -> com.lekathedon.visionlite.streams.MediaServers.plexStart().let { c -> Triple(c.url, c.userCode, suspend { com.lekathedon.visionlite.streams.MediaServers.plexWait(c) }) }
        "jellyfin" -> com.lekathedon.visionlite.streams.MediaServers.jfStart().let { c -> Triple(c.url, c.userCode, suspend { com.lekathedon.visionlite.streams.MediaServers.jfWait(c) }) }
        "nuvio" -> SignIns.nuvioStart().let { c -> Triple(c.url, c.userCode, suspend { SignIns.nuvioWait(c) }) }
        else -> SignIns.simklStart().let { c -> Triple(c.url, c.userCode, suspend { SignIns.simklWait(c) }) }
    }

    /** What the TV's own page does after a sign-in. */
    private suspend fun after(id: String) {
        Integrations.touch()
        // Nuvio from the phone = the one-time Join import (its first profile), then Nuvio is signed out for good.
        if (id == "nuvio") runCatching {
            com.lekathedon.visionlite.integrations.Join.apply(com.lekathedon.visionlite.integrations.Join.fromNuvio(SignIns.nuvioProfiles().firstOrNull()?.first ?: 1))
        }
    }

    private fun paste(id: String, text: String): JSONObject {
        val who = runCatching { runBlocking { if (id == "anilist") SignIns.aniListFinish(text) else if (id == "mal") SignIns.malFinish(text) else null } }.getOrNull()
        if (who == null) return err("That wasn't accepted. Sign in again and paste what the site shows.")
        Integrations.touch()
        return state()
    }

    private fun signOut(id: String): JSONObject {
        Integrations.all.firstOrNull { it.id == id }?.let(Integrations::clear)
        synchronized(pending) { pending.remove(id)?.job?.cancel() }
        return state()
    }

    private fun iptvAdd(a: JSONObject): JSONObject {
        val name = a.optString("name").trim().ifBlank { "Playlist" }
        val server = a.optString("server").trim()
        val url = a.optString("url").trim()
        if (server.isBlank() && url.isBlank()) return err("A playlist address, or a server with user and password, is needed")
        val pl = com.lekathedon.visionlite.iptv.Playlist("pl" + System.currentTimeMillis(), name, url = url, server = server,
            user = a.optString("user").trim(), pass = a.optString("pass").trim(), epg = a.optString("epg").trim())
        com.lekathedon.visionlite.iptv.Iptv.add(pl)
        // Loaded now (once), as the TV's own Add does; Live TV opens ready.
        scope.launch { runCatching { com.lekathedon.visionlite.iptv.Iptv.refresh(pl, force = true) } }
        return state()
    }

    /** The taste questions' answers: titles = Love it (or forgotten), genres liked / unliked, people followed / unfollowed. */
    private fun taste(a: JSONObject): JSONObject {
        val R = com.lekathedon.visionlite.recs.Recs
        val hubs = HashSet<com.lekathedon.visionlite.anime.Hub>()
        a.optJSONArray("love")?.let { l -> for (i in 0 until l.length()) l.optJSONObject(i)?.let { t ->
            val id = t.optString("id"); if (id.isBlank()) return@let
            R.love(id, t.optString("type") == "series", t.optString("name")); hubs += com.lekathedon.visionlite.anime.Hubs.hubOf(id)
        } }
        a.optJSONArray("forget")?.let { l -> for (i in 0 until l.length()) l.optString(i).takeIf { it.isNotBlank() }?.let(R::forget) }
        val I = com.lekathedon.visionlite.recs.Interests
        a.optJSONArray("interests")?.let { l -> for (i in 0 until l.length()) l.optString(i).takeIf { it.isNotBlank() && !I.isLiked(it) }?.let { I.toggle(it); hubs += if (com.lekathedon.visionlite.anime.MalGenres.isMal(it)) com.lekathedon.visionlite.anime.Hub.ANIME else com.lekathedon.visionlite.anime.Hub.FILMS } }
        a.optJSONArray("uninterests")?.let { l -> for (i in 0 until l.length()) l.optString(i).takeIf { I.isLiked(it) }?.let(I::toggle) }
        val P = com.lekathedon.visionlite.home.People
        a.optJSONArray("follow")?.let { l -> for (i in 0 until l.length()) l.optJSONObject(i)?.let { o ->
            val id = o.optString("id"); if (!id.startsWith("nm") || P.has(id, "NUVIO")) return@let
            P.toggle(com.lekathedon.visionlite.home.People.Person(id, o.optString("name"), o.optString("photo").takeIf { it.startsWith("http") })); hubs += com.lekathedon.visionlite.anime.Hub.FILMS
        } }
        a.optJSONArray("unfollow")?.let { l -> for (i in 0 until l.length()) l.optString(i).let { id ->
            P.list.value.firstOrNull { it.id == id && it.hub == "NUVIO" }?.let(P::toggle) } }
        if (a.optBoolean("done")) App.instance.prefs.tasteStartDone.value = true
        hubs.forEach { R.rebuildNow(it) }
        return state()
    }

    private fun err(m: String) = JSONObject().put("ok", false).put("error", m)
}
