package com.lekathedon.visionlite.link

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.MainActivity
import com.lekathedon.visionlite.ui.Screen
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the phone can ask (same signed protocol as Vision Link, so the companion's client carries over). Lite answers
 * from its own state and opens its own screens: no root reads, no deep links into another app.
 */
class Commands(private val ctx: Context) {
    fun handle(cmd: String, a: JSONObject): JSONObject = when (cmd) {
        "ping" -> ok().put("app", "visionlite").put("version", com.lekathedon.visionlite.BuildConfig.VERSION_NAME)
        "status" -> status()
        "wake" -> if (wake()) ok() else err("Android didn't let Vision wake the box")
        "start" -> open(null)
        "play" -> play(a)
        "details" -> details(a)
        // The phone's Who's watching (user 2026-11-27): every profile with its picture link and its last title.
        "profiles" -> profiles()
        // A title's trailer for the phone to play itself (the TV keeps playing what it plays). 12 s at most.
        "trailer" -> trailer(a)
        // The phone picks a profile for itself only (user 2026-11-28): the TV no longer switches (or restarts) for it.
        "use_profile" -> if (com.lekathedon.visionlite.data.Profiles.byId(a.optInt("id")) != null) ok()
            else err("That profile has a PIN: pick it on the TV")
        "media" -> media(a)
        "watch" -> watch(a)
        "rows" -> otherProfile(a)?.let { id ->
            runCatching { JSONObject(java.io.File(com.lekathedon.visionlite.data.Profiles.dir(id), "phone_rows.json").readText()) }.getOrNull()
                ?.let { ok().put("at", it.optLong("at")).put("rows", it.optJSONArray("rows")).put("hubs", it.optJSONArray("hubs")) }
                ?: ok().put("at", 0).put("rows", JSONArray()).put("hubs", JSONArray())
        } ?: LinkState.rows?.let { ok().put("at", it.optLong("at")).put("rows", it.optJSONArray("rows")).put("hubs", it.optJSONArray("hubs")) }
            ?: err("Vision hasn't shown Home yet since it started")
        // The anime list + your moves, so the phone splits Films & TV / Anime exactly like the TV (list only when it changed).
        "anime" -> com.lekathedon.visionlite.anime.AnimeIds.let { ids ->
            ok().put("stamp", ids.stamp()).put("moves", ids.movesText()).apply { if (a.optLong("since", -1) != ids.stamp()) put("ids", ids.listText()) }
        }
        "iptv" -> iptv(a)
        "channel" -> channel(a)
        "next" -> next()
        "sync" -> sync()
        "addons" -> addons()
        "addAddon" -> addAddon(a)
        // Setup code (user 2026-11-26): the phone shares this profile's add-ons/sources, or applies a friend's.
        "setupCode" -> ok().put("code", com.lekathedon.visionlite.data.SetupShare.export())
        "useSetupCode" -> runCatching { com.lekathedon.visionlite.data.SetupShare.import(a.optString("code")) }
            .fold({ ok().put("got", it) }, { JSONObject().put("ok", false).put("error", it.message ?: "bad code") })
        // The phone app's settings list (names, pages, choices) with the current values (2026-11-27 phone rewrite).
        "schema" -> ok().put("schema", com.lekathedon.visionlite.settings.phoneSchema())
        "settings" -> if (a.has("stores")) ok().put("profile", a.optInt("profile", 1))
            .put("sealed", phone.seal(phone.settings(a.optJSONArray("stores") ?: JSONArray()).toString(), "vision-link-settings")) else settings(a)
        // The companion's Vision tab (see Phone).
        "vision" -> ok().put("sealed", phone.seal(phone.state().toString(), "vision-link-state")).put("queue", phone.queueStatus())
        "calendar" -> System.currentTimeMillis().let { now ->
            ok().put("titles", phone.calendar(a.optLong("from", now - 7 * DAY), a.optLong("to", now + 60 * DAY)))
        }
        "creds" -> ok().put("sealed", phone.creds())
        // The whole setup from the phone (keys, sign-ins, YouTube, IPTV, taste, Anime): see Setup.
        "setup" -> Setup.handle(a)
        "queue" -> ok().put("queue", phone.queue(a.optJSONArray("items") ?: JSONArray()))
        "queueStatus" -> ok().put("queue", phone.queueStatus())
        else -> err("Unknown command")
    }

    private val phone = Phone { Pairing(ctx).secret }

    /** The player's Next episode, as its own button. */
    private fun next(): JSONObject {
        if (LinkState.mediaJson() == null) return err("Nothing is playing in Vision")
        LinkState.next.trySend(Unit)
        return ok()
    }

    /** "Sync with TV": Lite reads Trakt again now (Continue watching, Coming up, watched titles). */
    private fun sync(): JSONObject {
        com.lekathedon.visionlite.home.ComingUp.refresh(true)
        com.lekathedon.visionlite.player.PlayerReturn.mark()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { com.lekathedon.visionlite.home.Watched.refresh(force = true) }
        }
        return ok().put("synced", true)
    }

    private fun status(): JSONObject {
        val r = ok()
        r.put("awake", (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive)
        r.put("installed", true).put("running", true).put("front", LinkState.front)
        LinkState.mediaJson()?.let { r.put("media", it) }
        r.put("canLaunch", Settings.canDrawOverlays(ctx))
        return r
    }

    /** Wakes the screen with a wake lock (no root); root's key press only if that didn't work. */
    @Suppress("DEPRECATION")
    private fun wake(): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) return true
        runCatching {
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "visionlite:wake").acquire(3_000)
        }
        repeat(10) { if (pm.isInteractive) return true; Thread.sleep(100) }
        return Root.run("input keyevent KEYCODE_WAKEUP", 4) != null
    }

    // ---- Opening screens ----

    /** Brings Lite to the front (waking the box first) and hands it [screen]; null = just Home as it is. */
    private fun open(screen: Screen?): JSONObject {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) wake()
        screen?.let { LinkState.open.trySend(it) }
        if (LinkState.front) return ok()
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        if (Settings.canDrawOverlays(ctx) && runCatching { ctx.startActivity(i) }.isSuccess) return ok()
        if (Root.run("am start -n ${ctx.packageName}/.MainActivity", 6) != null) return ok()
        return err("Android blocked opening Vision: allow 'Display over other apps' for Vision in the TV's settings")
    }

    /** Play = the title's link list, as Play from the remote does (smart autoplay picks for you only when it's on). */
    private fun play(a: JSONObject): JSONObject {
        val id = a.optString("contentId").ifBlank { return err("No title id") }
        val type = a.optString("contentType", "movie")
        val name = a.optString("title").ifBlank { id }
        val streamId = if (a.has("season") && a.has("episode")) "$id:${a.getInt("season")}:${a.getInt("episode")}" else id
        return open(Screen.Streams(type, streamId, name, id))
    }

    private fun profiles(): JSONObject {
        val P = com.lekathedon.visionlite.data.Profiles
        val list = JSONArray()
        P.all().forEach { p ->
            val o = JSONObject().put("id", p.id).put("name", p.name).put("color", p.color).put("avatar", p.shareable).put("locked", p.locked)
            if (!p.locked) com.lekathedon.visionlite.profiles.LastWatched.of(p.id)?.let { l ->
                o.put("last", JSONObject().put("type", l.type).put("id", l.imdb).put("name", l.name).put("logo", l.logo).put("backdrop", l.backdrop)
                    .put("season", l.season ?: JSONObject.NULL).put("episode", l.episode ?: JSONObject.NULL).put("progress", l.progress.toDouble()))
            }
            list.put(o)
        }
        return ok().put("active", P.currentId()).put("profiles", list)
    }

    private fun trailer(a: JSONObject): JSONObject {
        val id = a.optString("id").ifBlank { return err("No title id") }
        val m = com.lekathedon.visionlite.addons.MetaPreview(id, a.optString("type", "movie"), a.optString("name"))
        val t = kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(12_000) { com.lekathedon.visionlite.trailer.TrailerChain.find(m, 1080) } }
            ?: return err("No trailer for this title")
        return ok().put("url", t.url).put("audio", t.audio ?: JSONObject.NULL)
    }

    private fun details(a: JSONObject): JSONObject {
        val type = a.optString("type").ifBlank { return err("No type") }
        val id = a.optString("id").ifBlank { return err("No title id") }
        return open(Screen.Detail(type, id, a.optString("name").ifBlank { id }))
    }

    // ---- Live TV ----

    /**
     * Playlists, favourites and recent channels, as the companion reads them; with "login", the Xtream logins sealed
     * with the pairing code (the phone browses the provider's lists itself, never opening a stream).
     */
    private fun iptv(a: JSONObject): JSONObject {
        val iptv = com.lekathedon.visionlite.iptv.Iptv
        val pls = iptv.playlists()
        val stream = Regex("""/(\d+)\.(ts|m3u8)$""")
        fun list(keys: List<String>) = JSONArray().apply {
            runCatching { iptv.channelsByKey(keys) }.getOrDefault(emptyList()).forEach { c ->
                put(JSONObject().put("key", c.key).put("playlist", c.pl).put("stream", stream.find(c.url)?.groupValues?.get(1).orEmpty())
                    .put("name", c.name).put("group", c.group).put("logo", c.logo ?: JSONObject.NULL).put("epg", c.tvg ?: JSONObject.NULL))
            }
        }
        val out = JSONObject()
            .put("playlists", JSONArray().apply { pls.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("xtream", it.xtream)) } })
            .put("favorites", list(iptv.favourites().toList())).put("recent", list(iptv.recents()))
        if (a.optBoolean("login")) out.put("login", phone.seal(JSONArray().apply {
            pls.filter { it.xtream }.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("server", it.server).put("user", it.user).put("pass", it.pass)) }
        }.toString(), "vision-link-iptv-login"))
        return ok().put("iptv", out)
    }

    private fun channel(a: JSONObject): JSONObject {
        val key = a.optString("key").ifBlank { phone.channelKey(a.optString("playlist"), a.optString("stream")).orEmpty() }
            .ifBlank { return err("This channel isn't in the TV's list (refresh the playlist on the TV)") }
        val c = runCatching { com.lekathedon.visionlite.iptv.Iptv.channelsByKey(listOf(key)) }.getOrDefault(emptyList()).firstOrNull()
            ?: return err("This channel isn't in the TV's list (refresh the playlist on the TV)")
        com.lekathedon.visionlite.iptv.Iptv.played(c)
        return open(Screen.Player(c.url, c.name, c.headers, type = "live"))
    }

    // ---- Add-ons and settings ----

    private fun addons() = ok().put("addons", JSONArray().apply {
        App.instance.addons.addons.value.forEach { put(JSONObject().put("name", it.manifest.name).put("version", it.manifest.version).put("url", it.base)) }
    })

    private fun addAddon(a: JSONObject): JSONObject {
        val url = a.optString("url").ifBlank { return err("No add-on link") }
        return runCatching { runBlocking { App.instance.addons.install(url) } }
            .fold({ ok().put("name", it.manifest.name) }, { err("Couldn't install: ${it.message}") })
    }

    /** Read or change a Lite setting by name (only the switches and choices on Settings, never keys or sign-ins). */
    /** The phone's profile when it isn't the one the TV is in (null = the TV's own). */
    private fun otherProfile(a: JSONObject): Int? {
        val id = a.optInt("profile", -1)
        val P = com.lekathedon.visionlite.data.Profiles
        return id.takeIf { it > 0 && it != P.currentId() && P.byId(it)?.locked == false }
    }

    private fun settings(a: JSONObject): JSONObject {
        val other = otherProfile(a)
        val sp = other?.let { com.lekathedon.visionlite.data.Profiles.prefsOf(it) } ?: App.instance.prefs.sp
        val set = a.optJSONObject("set")
        if (set != null) for (k in set.keys()) {
            if (k !in SAFE) continue
            val v = set.get(k).toString()
            if (other == null) App.instance.prefs.setByKey(k, v)
            else sp.edit().apply {   // same type as stored (or as the TV's own profile has it)
                when (sp.all[k] ?: App.instance.prefs.sp.all[k]) {
                    is Boolean -> putBoolean(k, v.toBoolean())
                    is Int -> v.toIntOrNull()?.let { putInt(k, it) }
                    is Long -> v.toLongOrNull()?.let { putLong(k, it) }
                    is Float -> v.toFloatOrNull()?.let { putFloat(k, it) }
                    else -> putString(k, v)
                }
            }.apply()
        }
        val out = JSONObject()
        SAFE.forEach { k -> sp.all[k]?.let { out.put(k, it) } }
        return ok().put("settings", out)
    }

    // ---- The player ----

    private fun media(a: JSONObject): JSONObject {
        LinkState.onPlayer { p, _ ->
            val dur = p.duration.takeIf { it > 0 } ?: 0L
            fun clamp(ms: Long) = if (dur > 0) ms.coerceIn(0, dur) else ms.coerceAtLeast(0)
            when (a.optString("action")) {
                "play" -> p.play()
                "pause" -> p.pause()
                "toggle" -> if (p.playWhenReady) p.pause() else p.play()
                "seekTo" -> p.seekTo(clamp(a.optLong("ms")))
                "seekBy" -> p.seekTo(clamp(p.currentPosition + a.optLong("ms")))
                "stop" -> LinkState.open.trySend(Screen.Home)
            }
        } ?: return err("Nothing is playing in Vision")
        Thread.sleep(200)
        return ok().put("media", LinkState.mediaJson() ?: JSONObject.NULL)
    }

    private fun watch(a: JSONObject): JSONObject {
        val rev = LinkState.await(a.optInt("rev", -1), a.optLong("ms", 45_000).coerceIn(1_000, 240_000))
        return ok().put("rev", rev).put("media", LinkState.mediaJson() ?: JSONObject.NULL)
    }

    private fun ok() = JSONObject().put("ok", true)
    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg)

    internal companion object {
        const val DAY = 86_400_000L
        /** Settings the phone may change: the plain choices on the Settings page. */
        val SAFE = setOf(
            "autoplay", "player_engine", "engine_backup", "libass", "next_episode", "next_popup", "skip_intro", "resume", "rewind_on_resume", "audio_lang", "audio_lang2", "sub_lang", "sub_lang2", "subs_when", "sub_size", "sub_style", "sub_hdr", "sub_hdr_bright", "black_bars", "stream_sort", "max_quality", "size_limit", "hide_torrents", "hide_cam", "skin", "style", "ui_size", "home_follows", "poster_size", "ratings", "clock", "watched_home", "trailers", "trailer_delay", "trailer_sound", "detail_ep_ratings", "detail_reviews", "detail_sequel_prequel", "detail_connections", "rec_min_imdb", "exit_confirm", "vod_in_tb2", "dub_lang", "autoplay_subs", "app_lang", "arabp2p_browse", "screensaver_min", "billboard_source", "fast_horizontal", "sound_out", "night_mode", "dialogue_boost", "volume_boost"
        )
    }
}
