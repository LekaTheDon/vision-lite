package com.lekathedon.visionlite.link

import android.util.Base64
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.integrations.Integrations
import com.lekathedon.visionlite.integrations.SignIns
import com.lekathedon.visionlite.recs.Recs
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The phone companion's "Vision" tab, answered by Lite itself: profiles, add-ons, lists, reminders, settings values,
 * the calendar, the keys the phone may copy, and the phone's changes (applied at once: Lite is the app that runs).
 * Same formats and seals as Vision Link, so the companion's pages work unchanged.
 */
internal class Phone(private val secret: () -> String) {
    private val p get() = App.instance.prefs

    // ---- Sealing (AES-GCM, key = HMAC(pairing code, purpose)) ----

    fun seal(text: String, purpose: String): String {
        val key = SecretKeySpec(Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret().toByteArray(), "HmacSHA256")) }
            .doFinal(purpose.toByteArray()), "AES")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return Base64.encodeToString(iv + c.doFinal(text.toByteArray()), Base64.NO_WRAP)
    }

    fun open(sealed: String, purpose: String): String? = runCatching {
        val key = SecretKeySpec(Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret().toByteArray(), "HmacSHA256")) }
            .doFinal(purpose.toByteArray()), "AES")
        val all = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, all, 0, 12)) }
        String(c.doFinal(all, 12, all.size - 12))
    }.getOrNull()

    // ---- State: profiles, add-ons, lists, reminders, Scout ratings ----

    fun state(): JSONObject {
        val faces = SignIns.cachedFaces().ifEmpty { runCatching { runBlocking { SignIns.nuvioFaces() } }.getOrDefault(emptyList()) }
        val active = SignIns.nuvioProfile()
        val profiles = JSONArray().apply {
            if (faces.isEmpty()) put(JSONObject().put("id", 1).put("name", "Lite"))
            faces.forEach { put(JSONObject().put("id", it.index).put("name", it.name).put("color", it.color).put("avatar", it.picture)) }
        }
        val store = App.instance.addons
        val addons = store.all.value
        val sports = com.lekathedon.visionlite.sports.Sports.follows.reminders
        val state = JSONObject()
            .put("profile", active).put("active", active).put("profiles", profiles)
            .put("addons", JSONObject().put("urls", JSONArray(addons.map { it.manifestUrl() })).put("enabled", JSONObject().apply { addons.forEach { put(it.manifestUrl(), store.isOn(it.base)) } }))
            .put("lists", runCatching { JSONArray(p.importedLists.value) }.getOrDefault(JSONArray()))
            .put("reminders", JSONObject()
                .put("bells", JSONArray(bells().toList()))
                .put("live", runCatching { JSONArray(p.phoneLive.value) }.getOrDefault(JSONArray()))
                .put("sports", JSONObject().put("reminders", JSONArray().apply {
                    sports.forEach { put(JSONObject().put("e", it.eventId).put("t", it.title).put("s", it.startMs)) }
                })))
            .put("scoutRatings", JSONArray().apply {
                Recs.feedback().filter { !it.watched }.sortedByDescending { it.at }.forEach { f ->
                    put(JSONObject().put("id", f.id).put("rating", if (!f.like) "NOT_FOR_ME" else if (f.love) "LOVE" else "LIKE")
                        .put("type", if (f.tv) "series" else "movie").put("name", f.name)
                        .put("poster", if (f.id.startsWith("tt")) com.lekathedon.visionlite.library.posterOf(f.id) else JSONObject.NULL))
                }
            })
        return state
    }

    private fun com.lekathedon.visionlite.addons.Addon.manifestUrl() = base.trimEnd('/') + "/manifest.json"

    private fun bells(): Set<String> = p.phoneBells.value.split('\n').filter { it.isNotBlank() }.toSet()

    // ---- Settings values: the "lite" store (the companion's list of Lite settings names the keys) ----

    fun settings(stores: JSONArray): JSONObject {
        val all = p.sp.all
        val out = JSONObject()
        for (i in 0 until stores.length()) {
            val st = stores.optJSONObject(i) ?: continue
            if (st.optString("store") != STORE) continue
            val keys = st.optJSONArray("keys") ?: continue
            val values = JSONObject()
            for (k in 0 until keys.length()) {
                val key = keys.optString(k)
                if (key in Commands.SAFE) all[key]?.let { values.put(key, it) }
            }
            out.put(STORE, values)
        }
        return out
    }

    // ---- Calendar: Coming up (Trakt's calendar of what you watch) ----

    fun calendar(from: Long, to: Long): JSONArray {
        val items = com.lekathedon.visionlite.home.ComingUp.items.value.filter { it.at in from..to }
        return JSONArray().apply {
            items.groupBy { it.imdb }.forEach { (imdb, list) ->
                val f = list.first()
                put(JSONObject().put("key", imdb).put("name", f.name).put("contentId", imdb).put("type", f.type)
                    .put("hub", com.lekathedon.visionlite.anime.Hubs.hubOf(imdb).key)
                    .put("ids", JSONObject().put("imdb", imdb))
                    .put("poster", com.lekathedon.visionlite.library.posterOf(imdb))
                    .put("entries", JSONArray().apply {
                        list.sortedBy { it.at }.forEach { e ->
                            put(JSONObject().put("kind", if (f.type == "movie") "MOVIE_DIGITAL" else "EPISODE").put("at", e.at)
                                .put("s", e.season ?: JSONObject.NULL).put("e", e.episode ?: JSONObject.NULL)
                                .put("title", e.epTitle ?: JSONObject.NULL))
                        }
                    }))
            }
        }
    }

    // ---- Keys the phone may copy (Trakt only borrowed: Lite keeps renewing it) ----

    fun creds(): String {
        val o = JSONObject()
        fun key(id: String) = Integrations.all.firstOrNull { it.id == id }?.let { Integrations.values(it).firstOrNull() }?.takeIf { it.isNotBlank() }
        listOf("tmdb", "mdblist", "gemini", "groq", "mistral", "torbox", "premiumize").forEach { id -> key(id)?.let { o.put(id, it) } }
        key("realdebrid")?.let { o.put("realdebrid", it) }
        val sp = p.sp
        sp.getString("si_anilist_token", "").takeIf { !it.isNullOrBlank() }?.let {
            o.put("anilist", JSONObject().put("token", it).put("user", SignIns.user("anilist"))
                .put("expiresAt", sp.getString("si_anilist_until", "")?.toLongOrNull() ?: 0))
        }
        sp.getString("si_mal_token", "").takeIf { !it.isNullOrBlank() }?.let {
            o.put("mal", JSONObject().put("access", it).put("user", SignIns.user("mal"))
                .put("expiresAt", sp.getString("si_mal_until", "")?.toLongOrNull() ?: 0))
        }
        // The Trakt app's Client ID is built into Lite; the phone needs the same one to use the borrowed token.
        com.lekathedon.visionlite.BuildConfig.TRAKT_ID.takeIf { it.isNotBlank() }?.let { o.put("traktClientId", it) }
        App.instance.trakt.connected.value?.let { t ->
            o.put("trakt", JSONObject().put("access", t.access).put("expiresAt", (t.createdAt + t.expiresIn) * 1000))
        }
        return seal(o.toString(), "vision-link-creds")
    }

    // ---- The phone's changes ----

    @Synchronized
    fun queue(items: JSONArray): JSONObject {
        val states = queueStates()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { continue }
            states.put(id, runCatching { apply(o) }.getOrElse { "failed: ${it.message ?: "error"}" })
        }
        // Keep the last 200 answers.
        val keys = states.keys().asSequence().toList()
        if (keys.size > 200) keys.take(keys.size - 200).forEach { states.remove(it) }
        p.phoneQueue.value = states.toString()
        return JSONObject().put("states", states)
    }

    fun queueStatus() = JSONObject().put("states", queueStates())

    private fun queueStates() = runCatching { JSONObject(p.phoneQueue.value) }.getOrDefault(JSONObject())

    /** Applies one change; "applied", or throws why not. */
    private fun apply(o: JSONObject): String {
        when (o.optString("kind")) {
            "setting" -> {
                val key = o.optString("key")
                require(o.optString("store") == STORE && key in Commands.SAFE) { "not a Lite setting" }
                val v = o.opt("value")
                require(p.setByKey(key, if (v is Boolean) v.toString() else v.toString())) { "not a Lite setting" }
            }
            "verdict" -> {
                val item = o.optJSONObject("item") ?: error("no title")
                val id = item.optString("imdb").takeIf { it.startsWith("tt") } ?: error("needs an IMDb id")
                val tv = item.optString("type") != "movie"
                when (o.optString("verdict")) {
                    "love" -> Recs.love(id, tv, item.optString("name"))
                    "like" -> Recs.moreLikeThis(id, tv, item.optString("name"))
                    "forget" -> Recs.forget(id)
                    "watched" -> Recs.watched(id, tv, item.optString("name"))
                    else -> Recs.notForMe(id, tv, item.optString("name"))
                }
            }
            "list" -> {
                val l = o.optJSONObject("list") ?: error("no list")
                val key = l.optString("source").lowercase() + "." + l.optString("remoteId")
                val cur = runCatching { JSONArray(p.importedLists.value) }.getOrDefault(JSONArray())
                val kept = JSONArray()
                for (i in 0 until cur.length()) cur.optJSONObject(i)?.let {
                    if (it.optString("source").lowercase() + "." + it.optString("remoteId") != key) kept.put(it)
                }
                if (o.optString("op") == "import") {
                    require(com.lekathedon.visionlite.home.ImportedLists.supports(l)) {
                        "Vision Lite shows Trakt lists, MDBList lists and IMDb Picks (not ${l.optString("source")} yet)"
                    }
                    kept.put(l)
                }
                p.importedLists.value = kept.toString()
            }
            "bell" -> {
                val k = o.optString("key").ifBlank { error("no title") }
                p.phoneBells.value = (if (o.optBoolean("on")) bells() + k else bells() - k).joinToString("\n")
            }
            "liveReminder" -> {
                val cur = runCatching { JSONArray(p.phoneLive.value) }.getOrDefault(JSONArray())
                var ck = o.optString("channelKey")
                if (ck.isBlank()) ck = channelKey(o.optString("playlist"), o.optString("stream")) ?: error("channel not in the TV's list")
                val start = o.optLong("startMs")
                val kept = JSONArray()
                for (i in 0 until cur.length()) cur.optJSONObject(i)?.let {
                    if (!(it.optString("k") == ck && it.optLong("s") == start) && it.optLong("s") > System.currentTimeMillis() - 86_400_000L) kept.put(it)
                }
                if (o.optString("op") == "add") kept.put(JSONObject().put("k", ck).put("c", o.optString("channelName")).put("t", o.optString("title")).put("s", start))
                p.phoneLive.value = kept.toString()
            }
            "sportsReminder" -> {
                val f = com.lekathedon.visionlite.sports.Sports.follows
                val e = o.optString("eventId").ifBlank { error("no game") }
                val rest = f.reminders.filter { it.eventId != e }
                val next = if (o.optString("op") == "add")
                    rest + com.lekathedon.visionlite.sports.SportsReminder(e, o.optString("title"), o.optLong("startMs")) else rest
                com.lekathedon.visionlite.sports.SportsMine.save(f.copy(reminders = next))
            }
            "addon" -> {
                val store = App.instance.addons
                val url = o.optString("url")
                val base = com.lekathedon.visionlite.addons.AddonStore.normalize(url)
                fun find() = store.all.value.firstOrNull { com.lekathedon.visionlite.addons.AddonStore.normalize(it.base) == base } ?: error("not installed on the TV")
                when (o.optString("op")) {
                    "install" -> runBlocking { store.install(url) }
                    "remove" -> store.remove(find().base)
                    "move" -> store.move(find().base, o.optInt("delta"))
                    "enable" -> store.setOn(find().base, o.optBoolean("on"))
                    else -> error("unknown add-on change")
                }
            }
            "signin" -> {
                val data = o.optString("sealed").let { open(it, "vision-link-signin") }?.let(::JSONObject) ?: error("couldn't open it (pair again)")
                val s = Integrations.all.firstOrNull { it.id == o.optString("service") } ?: error("Lite has no ${o.optString("service")}")
                Integrations.save(s, listOf(data.optString("key")))
            }
            "signout" -> {
                val s = Integrations.all.firstOrNull { it.id == o.optString("service") } ?: error("Lite has no ${o.optString("service")}")
                Integrations.clear(s)
            }
            "profile" -> error("rename profiles in the Nuvio app (Lite uses your Nuvio profiles)")
            else -> error("Lite doesn't know this change")
        }
        return "applied"
    }

    /** A channel's Lite key from its Xtream playlist id + stream number. */
    fun channelKey(playlist: String, stream: String): String? {
        if (playlist.isBlank() || stream.isBlank()) return null
        return com.lekathedon.visionlite.iptv.Iptv.keyForStream(playlist, stream)
    }

    companion object { const val STORE = "lite" }
}
