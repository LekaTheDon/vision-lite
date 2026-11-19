package com.lekathedon.visionlite.link

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.MainActivity
import com.lekathedon.visionlite.ui.Screen
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
        "wake" -> if (Root.run("input keyevent KEYCODE_WAKEUP", 4) != null) ok() else err("No root access to wake the box")
        "start" -> open(null)
        "play" -> play(a)
        "details" -> details(a)
        "media" -> media(a)
        "watch" -> watch(a)
        "rows" -> LinkState.rows?.let { ok().put("at", it.optLong("at")).put("rows", it.optJSONArray("rows")) }
            ?: err("Lite hasn't shown Home yet since it started")
        "iptv" -> iptv()
        "channel" -> channel(a)
        "youtube" -> youtube(a)
        "addons" -> addons()
        "addAddon" -> addAddon(a)
        "settings" -> settings(a)
        else -> err("Unknown command")
    }

    private fun status(): JSONObject {
        val r = ok()
        r.put("awake", (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive)
        r.put("installed", true).put("running", true).put("front", LinkState.front)
        LinkState.mediaJson()?.let { r.put("media", it) }
        r.put("canLaunch", Settings.canDrawOverlays(ctx))
        com.lekathedon.visionlite.music.MusicPlayer.now.value?.let { m ->
            r.put("music", JSONObject().put("title", m.title).put("artist", m.artist).put("art", m.art.orEmpty()).put("playing", m.playing))
        }
        return r
    }

    // ---- Opening screens ----

    /** Brings Lite to the front (waking the box first) and hands it [screen]; null = just Home as it is. */
    private fun open(screen: Screen?): JSONObject {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) Root.run("input keyevent KEYCODE_WAKEUP", 4)
        screen?.let { LinkState.open.trySend(it) }
        if (LinkState.front) return ok()
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        if (Settings.canDrawOverlays(ctx) && runCatching { ctx.startActivity(i) }.isSuccess) return ok()
        if (Root.run("am start -n ${ctx.packageName}/.MainActivity", 6) != null) return ok()
        return err("Android blocked opening Vision Lite (needs root once for 'draw over apps')")
    }

    /** Play = the title's link list, as Play from the remote does (smart autoplay picks for you only when it's on). */
    private fun play(a: JSONObject): JSONObject {
        val id = a.optString("contentId").ifBlank { return err("No title id") }
        val type = a.optString("contentType", "movie")
        val name = a.optString("title").ifBlank { id }
        val streamId = if (a.has("season") && a.has("episode")) "$id:${a.getInt("season")}:${a.getInt("episode")}" else id
        return open(Screen.Streams(type, streamId, name, id))
    }

    private fun details(a: JSONObject): JSONObject {
        val type = a.optString("type").ifBlank { return err("No type") }
        val id = a.optString("id").ifBlank { return err("No title id") }
        return open(Screen.Detail(type, id, a.optString("name").ifBlank { id }))
    }

    private fun youtube(a: JSONObject): JSONObject {
        val id = a.optString("id").takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) } ?: return err("Not a YouTube video id")
        return open(Screen.YtPlay(id, a.optString("title")))
    }

    // ---- Live TV ----

    private fun iptv(): JSONObject {
        val iptv = com.lekathedon.visionlite.iptv.Iptv
        fun list(keys: List<String>) = JSONArray().apply {
            runCatching { iptv.channelsByKey(keys) }.getOrDefault(emptyList()).forEach { c ->
                put(JSONObject().put("key", c.key).put("name", c.name).put("group", c.group).put("logo", c.logo.orEmpty()))
            }
        }
        return ok().put("favourites", list(iptv.favourites().toList())).put("recent", list(iptv.recents()))
    }

    private fun channel(a: JSONObject): JSONObject {
        val key = a.optString("key").ifBlank { return err("No channel") }
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
    private fun settings(a: JSONObject): JSONObject {
        val sp = App.instance.prefs.sp
        val set = a.optJSONObject("set")
        if (set != null) for (k in set.keys()) {
            if (k in SAFE) App.instance.prefs.setByKey(k, set.get(k).toString())
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
        } ?: return err("Nothing is playing in Vision Lite")
        Thread.sleep(200)
        return ok().put("media", LinkState.mediaJson() ?: JSONObject.NULL)
    }

    private fun watch(a: JSONObject): JSONObject {
        val rev = LinkState.await(a.optInt("rev", -1), a.optLong("ms", 45_000).coerceIn(1_000, 240_000))
        return ok().put("rev", rev).put("media", LinkState.mediaJson() ?: JSONObject.NULL)
    }

    private fun ok() = JSONObject().put("ok", true)
    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg)

    private companion object {
        /** Settings the phone may change: the plain choices on the Settings page. */
        val SAFE = setOf(
            "autoplay", "next_episode", "next_popup", "skip_intro", "resume", "rewind_on_resume",
            "audio_lang", "audio_lang2", "sub_lang", "sub_lang2", "subs_when", "sub_size", "sub_style",
            "stream_sort", "max_quality", "size_limit", "hide_torrents", "hide_cam",
            "oled_black", "trailers", "trailer_delay", "trailer_sound", "poster_size", "clock", "ratings",
            "ep_ratings", "reviews_source", "sequel_prequel", "connections", "rec_min_imdb", "exit_confirm", "watched_home"
        )
    }
}
