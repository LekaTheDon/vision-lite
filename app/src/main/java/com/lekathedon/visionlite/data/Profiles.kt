package com.lekathedon.visionlite.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Lite's own profiles, Vision style: each profile is its own space with nothing shared — its own settings file
 * (sign-ins: Trakt, Nuvio profile, TorBox, debrid, Simkl/AniList/MAL, YouTube; Library, Continue watching marks,
 * taste engine, Home rows, searches, IPTV lists, Sports teams) and its own files folder (recommendations, rotating
 * rows, Coming up, IPTV/VOD databases). Only the box's own things are shared: This TV (Dolby Vision, passthrough,
 * frame rate, engine, buffer…), the data keys (TMDB, TVDB, MDBList, Gemini/Groq/Mistral), the Nuvio account login
 * (each profile picks its own Nuvio profile), the phone link and picture/chart caches.
 *
 * Profile 1 keeps the app's original files ("vl", filesDir), so nothing moves for existing data. Others live in
 * "vl_p<N>" and filesDir/profiles/<N>. A switch restarts the app, so no screen or cache can carry the old profile's
 * data over. A PIN is kept as a salted SHA-256, on this box only.
 */
object Profiles {
    data class Profile(val id: Int, val name: String, val pic: String = "", val color: String = "#1E88E5", val pin: String = "") {
        val locked get() = pin.isNotBlank()
    }

    private lateinit var ctx: Context
    private val base: SharedPreferences by lazy { ctx.getSharedPreferences("vl", Context.MODE_PRIVATE) }

    fun init(c: Context) { ctx = c.applicationContext }

    val Colors = listOf("#1E88E5", "#E53935", "#43A047", "#FB8C00", "#8E24AA", "#00ACC1", "#F4511E", "#6D4C41", "#546E7A", "#D81B60")

    fun all(): List<Profile> {
        val raw = base.getString("lp_profiles", "").orEmpty()
        val list = runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                Profile(it.getInt("id"), it.optString("name"), it.optString("pic"), it.optString("color", "#1E88E5"), it.optString("pin"))
            }
        }.getOrDefault(emptyList())
        if (list.isNotEmpty()) return list
        // First time: the one profile the app always had, named and pictured from its Nuvio profile when signed in.
        val name = base.getString("si_nuvio_profile_name", "").orEmpty().ifBlank { "Me" }
        val first = Profile(1, name, base.getString("si_nuvio_avatar", "").orEmpty(),
            base.getString("si_nuvio_avatar_color", "").orEmpty().ifBlank { "#1E88E5" })
        save(listOf(first))
        return listOf(first)
    }

    private fun save(list: List<Profile>) {
        base.edit().putString("lp_profiles", JSONArray().apply {
            list.forEach { p -> put(JSONObject().put("id", p.id).put("name", p.name).put("pic", p.pic).put("color", p.color).put("pin", p.pin)) }
        }.toString()).commit()
        _version.value++
    }

    private val _version = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** Bumped on every change, so the picker and Settings redraw. */
    val version: kotlinx.coroutines.flow.StateFlow<Int> get() = _version

    fun currentId(): Int = base.getInt("lp_current", 1)
    fun current(): Profile = all().let { l -> l.firstOrNull { it.id == currentId() } ?: l.first() }
    fun byId(id: Int) = all().firstOrNull { it.id == id }

    fun prefsName(id: Int) = if (id == 1) "vl" else "vl_p$id"
    fun dir(id: Int): File = if (id == 1) ctx.filesDir else File(ctx.filesDir, "profiles/$id").apply { mkdirs() }
    /** This profile's database file name ("iptv.db" for profile 1, "p2_iptv.db" for profile 2…). */
    fun db(name: String) = currentId().let { if (it == 1) name else "p${it}_$name" }

    fun add(name: String, color: String = Colors[all().size % Colors.size], pic: String = ""): Profile {
        val list = all()
        val p = Profile((list.maxOfOrNull { it.id } ?: 0) + 1, name.ifBlank { "Profile ${list.size + 1}" }, pic, color)
        save(list + p)
        return p
    }

    fun update(p: Profile) = save(all().map { if (it.id == p.id) p else it })

    /** Deletes a profile and everything it kept (settings file, files folder, databases). Not the one in use, not profile 1. */
    fun delete(id: Int): Boolean {
        if (id == 1 || id == currentId()) return false
        save(all().filterNot { it.id == id })
        ctx.getSharedPreferences(prefsName(id), Context.MODE_PRIVATE).edit().clear().commit()
        runCatching { ctx.deleteSharedPreferences(prefsName(id)) }
        File(ctx.filesDir, "profiles/$id").deleteRecursively()
        File(ctx.filesDir, "avatars").listFiles { x -> x.name.startsWith("$id-") }?.forEach { it.delete() }
        ctx.databaseList().filter { it.startsWith("p${id}_") }.forEach { ctx.deleteDatabase(it) }
        return true
    }

    /** Writes keys straight into another profile's settings (a Nuvio profile link on import). */
    fun putFor(id: Int, vararg kv: Pair<String, String>) {
        ctx.getSharedPreferences(prefsName(id), Context.MODE_PRIVATE).edit().apply { kv.forEach { (k, v) -> putString(k, v) } }.commit()
    }

    // ---------------------------------------------------------------- pictures

    /**
     * Keeps a profile's picture on the box (filesDir/avatars), so it never expires (Nuvio's supporter pictures are
     * day-long signed links) and shows without the network. GIFs stay GIFs. Blocking; call off the main thread.
     * Falls back to the link itself when the download fails.
     */
    fun setPicture(id: Int, url: String) {
        val p = byId(id) ?: return
        if (url.isBlank() || !url.startsWith("http")) { update(p.copy(pic = url)); return }
        val f = runCatching {
            Net.http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                val bytes = r.body?.bytes()
                if (!r.isSuccessful || bytes == null || bytes.size < 32 || bytes.size > 8_000_000) return@use null
                File(ctx.filesDir, "avatars").apply { mkdirs() }.listFiles { x -> x.name.startsWith("$id-") }?.forEach { it.delete() }
                File(ctx.filesDir, "avatars/$id-${System.currentTimeMillis()}").apply { writeBytes(bytes) }
            }
        }.getOrNull()
        byId(id)?.let { update(it.copy(pic = f?.path ?: url)) }
    }

    /** Pictures still kept as links (the first profile's Nuvio picture, older choices) are saved on the box. */
    fun keepPictures() { all().filter { it.pic.startsWith("http") }.forEach { setPicture(it.id, it.pic) } }

    // ---------------------------------------------------------------- PIN

    fun setPin(id: Int, pin: String) {
        val p = byId(id) ?: return
        if (pin.isBlank()) { update(p.copy(pin = "")); return }
        val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        update(p.copy(pin = "$salt:${hash(salt, pin)}"))
    }

    fun checkPin(id: Int, pin: String): Boolean {
        val stored = byId(id)?.pin.orEmpty()
        if (stored.isBlank()) return true
        val (salt, h) = stored.split(':').takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: return false
        return MessageDigest.isEqual(hash(salt, pin).toByteArray(), h.toByteArray())
    }

    private fun hash(salt: String, pin: String) =
        MessageDigest.getInstance("SHA-256").digest("$salt|$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------- start + switch

    /** Who's watching? at app start (only when there's more than one profile). */
    var askAtStart: Boolean
        get() = base.getBoolean("lp_ask_start", true)
        set(v) { base.edit().putBoolean("lp_ask_start", v).apply(); _version.value++ }

    /** What the start needs: "pick" (Who's watching?), "pin" (the profile in use is locked), or null (straight to Home). */
    fun startGate(): String? {
        val justSwitched = base.getBoolean("lp_switched", false)
        if (justSwitched) { base.edit().putBoolean("lp_switched", false).commit(); return null }
        if (all().size > 1 && askAtStart) return "pick"
        return if (current().locked) "pin" else null
    }

    /** Switches to [id] (PIN already checked) and restarts the app in that profile. */
    /** Starts the app again in the profile in use (after another profile's settings were shared into it). */
    fun restart(activity: android.app.Activity) {
        val i = activity.packageManager.getLaunchIntentForPackage(activity.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(i)
        activity.finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    fun switchTo(activity: android.app.Activity, id: Int) {
        if (id == currentId()) return
        base.edit().putInt("lp_current", id).putBoolean("lp_switched", true).commit()
        val i = activity.packageManager.getLaunchIntentForPackage(activity.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(i)
        activity.finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    // ---------------------------------------------------------------- what's shared

    private val DEVICE_KEYS = setOf(
        "phone_link", "dv7", "afr", "match_res", "device_asked", "pt_ac3", "pt_eac3", "pt_truehd", "pt_dts", "pt_dtshd", "dv5_mpv", "hdr_on_sdr", "sound_out",
        "dv5_to_81", "strip_hdr10plus", "buffer", "tv_auto", "tv_facts", "perf_class", "exit_seen", "player_engine",
        "engine_backup", "seek_thumbs", "black_bars", "anime_ids_at", "anime_moves",
        "si_nuvio_token", "si_nuvio_refresh", "si_nuvio_user", "si_nuvio_until", "si_nuvio_faces", "cloud_setup",
        "dns", "quiet_a11y", "full_exit", "fast_horizontal",
    )
    // Profiles are fully separate (user 2026-11-27): only this box's own hardware settings and the Nuvio account login
    // (which holds every profile's setup) are shared. Data keys (TMDB, TVDB, MDBList, Gemini, Groq, Mistral) were shared
    // until 0.41.10; [separateKeys] copies them once into each profile so nothing is lost.
    private val DEVICE_PREFIXES = listOf("lp_")
    private val WAS_SHARED = listOf("int_tmdb_", "int_tvdb_", "int_mdblist_", "int_gemini_", "int_groq_", "int_mistral_")

    /** Once: every profile gets its own copy of the data keys that used to be shared through profile 1's file. */
    fun separateKeys() {
        if (base.getBoolean("lp_keys_split", false)) return
        val shared = base.all.filterKeys { k -> WAS_SHARED.any { k.startsWith(it) } }
        all().filter { it.id != 1 }.forEach { p ->
            val e = ctx.getSharedPreferences(prefsName(p.id), Context.MODE_PRIVATE).edit()
            shared.forEach { (k, v) -> put(e, k, v) }
            e.commit()
        }
        base.edit().putBoolean("lp_keys_split", true).commit()
    }

    private fun put(e: SharedPreferences.Editor, k: String, v: Any?) {
        when (v) {
            is String -> e.putString(k, v); is Boolean -> e.putBoolean(k, v); is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v); is Float -> e.putFloat(k, v)
            is Set<*> -> @Suppress("UNCHECKED_CAST") e.putStringSet(k, v as Set<String>)
        }
    }

    /** What a profile can share with another (Manage profiles › Share settings). */
    enum class Share(val label: String, val sub: String) {
        LOOK("Look and player", "Skin, look, sizes, Home rows, subtitles and audio choices"),
        NO_KEYS("Everything except keys and sign-ins", "All settings; no API keys, Trakt, Simkl, debrid or other sign-ins"),
        NO_WATCHING("Everything except Trakt", "Settings and keys; not Trakt or Simkl watch history and sign-ins"),
        ALL("Everything", "All settings, keys and sign-ins"),
    }
    private val SECRET = Regex("^si_|token|refresh|_key$|^int_[a-z]+_|^arabp2p_|^debrid|^tb_|^rd_|^ad_|^pm_")
    private val WATCHING = Regex("trakt|simkl|nuvio")
    private val LOOK = Regex("^(skin|style|ui_size|home_follows|poster_size|ratings|clock|player_buttons|controls_size|row|rows_|home_|sub_|audio_|dub_|subs_|night_mode|dialogue_boost|volume_boost|app_lang|trailer|billboard|screensaver|oled)")

    /** Copies [from]'s settings of the chosen kind into [to] (this box's own settings never move). */
    fun share(from: Int, to: Int, what: Share): Int {
        if (from == to) return 0
        val src = ctx.getSharedPreferences(prefsName(from), Context.MODE_PRIVATE).all.filterKeys { k ->
            !isDevice(k) && k != "lp_keys_split" && when (what) {
                Share.ALL -> true
                Share.NO_KEYS -> !SECRET.containsMatchIn(k)
                Share.NO_WATCHING -> !WATCHING.containsMatchIn(k)
                Share.LOOK -> LOOK.containsMatchIn(k)
            }
        }
        val e = ctx.getSharedPreferences(prefsName(to), Context.MODE_PRIVATE).edit()
        src.forEach { (k, v) -> put(e, k, v) }
        e.commit()
        return src.size
    }

    fun isDevice(key: String) = key in DEVICE_KEYS || DEVICE_PREFIXES.any { key.startsWith(it) }

    /** The settings file the app uses: profile 1 = "vl" as always; others = their own file, box keys still in "vl". */
    fun prefs(): SharedPreferences {
        val id = currentId()
        return if (id == 1) base else Split(ctx.getSharedPreferences(prefsName(id), Context.MODE_PRIVATE), base)
    }

    /** One SharedPreferences over two files: each key goes to the profile's file or the box's, by [isDevice]. */
    private class Split(private val mine: SharedPreferences, private val box: SharedPreferences) : SharedPreferences {
        private fun of(k: String?) = if (k != null && isDevice(k)) box else mine
        override fun getAll(): MutableMap<String, *> = HashMap<String, Any?>().apply {
            box.all.forEach { (k, v) -> if (isDevice(k)) put(k, v) }
            mine.all.forEach { (k, v) -> if (!isDevice(k)) put(k, v) }
        }
        override fun getString(k: String?, d: String?) = of(k).getString(k, d)
        override fun getStringSet(k: String?, d: MutableSet<String>?) = of(k).getStringSet(k, d)
        override fun getInt(k: String?, d: Int) = of(k).getInt(k, d)
        override fun getLong(k: String?, d: Long) = of(k).getLong(k, d)
        override fun getFloat(k: String?, d: Float) = of(k).getFloat(k, d)
        override fun getBoolean(k: String?, d: Boolean) = of(k).getBoolean(k, d)
        override fun contains(k: String?) = of(k).contains(k)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {
            mine.registerOnSharedPreferenceChangeListener(l); box.registerOnSharedPreferenceChangeListener(l)
        }
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {
            mine.unregisterOnSharedPreferenceChangeListener(l); box.unregisterOnSharedPreferenceChangeListener(l)
        }
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val a: SharedPreferences.Editor = mine.edit()
            val b: SharedPreferences.Editor = box.edit()
            fun e(k: String?) = if (k != null && isDevice(k)) b else a
            override fun putString(k: String?, v: String?) = apply { e(k).putString(k, v) }
            override fun putStringSet(k: String?, v: MutableSet<String>?) = apply { e(k).putStringSet(k, v) }
            override fun putInt(k: String?, v: Int) = apply { e(k).putInt(k, v) }
            override fun putLong(k: String?, v: Long) = apply { e(k).putLong(k, v) }
            override fun putFloat(k: String?, v: Float) = apply { e(k).putFloat(k, v) }
            override fun putBoolean(k: String?, v: Boolean) = apply { e(k).putBoolean(k, v) }
            override fun remove(k: String?) = apply { e(k).remove(k) }
            /** Clears this profile's own settings only; the box's stay. */
            override fun clear() = apply { a.clear() }
            override fun commit() = a.commit() and b.commit()
            override fun apply() { a.apply(); b.apply() }
        }
    }
}
