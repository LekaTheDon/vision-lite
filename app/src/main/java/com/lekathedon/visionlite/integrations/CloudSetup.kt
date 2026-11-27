package com.lekathedon.visionlite.integrations

import android.content.Context
import android.content.SharedPreferences
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.data.Profiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Your setup in your Nuvio account (user 2026-11-23: "I don't want to go through 14 API sign-ins every time I log in
 * from a different device"). Every profile's settings, keys (TMDB, TorBox, Gemini…) and sign-ins (Trakt, Simkl, AniList,
 * MAL, ArabP2P…) are kept in the account's settings store under Lite's own slot (platform "visionlite"; Nuvio's apps
 * read "tv" / "mobile" and never see or change it). Sign in to Nuvio on a new device and it all comes back.
 *
 * Not kept: this box's own things (Dolby Vision, passthrough, frame rate, buffer, player engine…), the Nuvio login
 * itself, and caches. Each setting carries when it last changed here, so the newest change wins on every device; a new
 * device takes the account's copy whole. A signed-out token never signs the other devices out (a Trakt refresh on one
 * device can't undo another's).
 * Cost: one pull (~35 KB) at start when signed in; one push a minute after settings stop changing. Off = no calls.
 */
object CloudSetup {
    private const val PLATFORM = "visionlite"
    private const val V = 1
    private val ctx: Context get() = App.instance
    private val meta: SharedPreferences by lazy { ctx.getSharedPreferences("cloud_meta", Context.MODE_PRIVATE) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var applying = false
    private var pushJob: Job? = null
    private val listeners = HashMap<String, SharedPreferences.OnSharedPreferenceChangeListener>()

    /** Box-only settings and caches: never sent, never taken. */
    private val SKIP = setOf(
        "phone_link", "dv7", "afr", "match_res", "device_asked", "pt_ac3", "pt_eac3", "pt_truehd", "pt_dts", "pt_dtshd", "dv5_mpv", "hdr_on_sdr", "sound_out",
        "dv5_to_81", "strip_hdr10plus", "buffer", "tv_auto", "tv_facts", "perf_class", "exit_seen", "player_engine",
        "engine_backup", "seek_thumbs", "black_bars", "anime_ids_at", "lp_current", "lp_switched", "cloud_setup",
        "app_section", "phone_queue", "menus_1080_applied", "comingup_v2", "comingup_v3", "comingup_v4", "link_marks", "link_picks",
    )
    private val SKIP_PREFIX = listOf("si_nuvio_token", "si_nuvio_refresh", "si_nuvio_user", "si_nuvio_until", "si_nuvio_faces", "si_nuvio_avatar_")
    fun syncs(k: String) = k !in SKIP && SKIP_PREFIX.none { k.startsWith(it) } && !k.endsWith("_at")
    /** Sign-ins and keys: a blank one is never sent as a sign-out, nor taken over a working one. */
    private val CRED = Regex("^si_|token|refresh|_key$|^int_[a-z]+_0$|^arabp2p_")

    /** Off for good (2026-11-27): the account copy is read once, inside Join, and nothing is ever sent to Nuvio. */
    fun on() = false

    private fun files(): List<Pair<Int, String>> = Profiles.all().map { it.id to Profiles.prefsName(it.id) }.ifEmpty { listOf(1 to "vl") }
    private fun sp(name: String) = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** At app start: watch every profile's settings for changes, and take the account's newer ones. */
    fun start() {
        files().forEach { (id, name) -> watch(id, name) }
        if (on()) scope.launch { runCatching { pull(firstTime = false) } }
    }

    private fun watch(id: Int, name: String) {
        if (name in listeners) return
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (applying || key == null || !syncs(key)) return@OnSharedPreferenceChangeListener
            meta.edit().putLong("$id|$key", System.currentTimeMillis()).apply()
            schedulePush()
        }
        listeners[name] = l
        sp(name).registerOnSharedPreferenceChangeListener(l)
    }

    private fun schedulePush() {
        if (!on()) return
        pushJob?.cancel()
        pushJob = scope.launch { delay(60_000); runCatching { push() } }
    }

    // ---- account calls ----
    private suspend fun fetch(): Pair<Boolean, JSONObject?> {
        val (c, r) = SignIns.nuvioCall("/rest/v1/rpc/sync_pull_profile_settings_blob",
            JSONObject().put("p_profile_id", 1).put("p_platform", PLATFORM).toString())
        if (c !in 200..299) return false to null
        val a = runCatching { JSONArray(r) }.getOrNull() ?: return false to null
        return true to a.optJSONObject(0)?.optJSONObject("settings_json")
    }

    private suspend fun send(blob: JSONObject): Boolean {
        val (c, _) = SignIns.nuvioCall("/rest/v1/rpc/sync_push_profile_settings_blob",
            JSONObject().put("p_profile_id", 1).put("p_platform", PLATFORM).put("p_settings_json", blob).toString())
        return c in 200..299
    }

    // ---- values ----
    private fun enc(v: Any?): JSONObject? = when (v) {
        is Boolean -> JSONObject().put("t", "b").put("v", v)
        is Int -> JSONObject().put("t", "i").put("v", v)
        is Long -> JSONObject().put("t", "l").put("v", v)
        is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
        is String -> JSONObject().put("t", "s").put("v", v)
        is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
        else -> null
    }

    private fun write(e: SharedPreferences.Editor, k: String, o: JSONObject) {
        when (o.optString("t")) {
            "b" -> e.putBoolean(k, o.optBoolean("v"))
            "i" -> e.putInt(k, o.optInt("v"))
            "l" -> e.putLong(k, o.optLong("v"))
            "f" -> e.putFloat(k, o.optDouble("v").toFloat())
            "s" -> e.putString(k, o.optString("v"))
            "ss" -> e.putStringSet(k, o.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet())
        }
    }

    private fun blank(v: Any?) = v == null || (v is String && v.isBlank())

    /**
     * Takes the account's settings that are newer than this device's ([firstTime] = all of them: a device just signed in
     * starts from your setup). True = something in the profile in use changed.
     */
    suspend fun pull(firstTime: Boolean): Boolean = lock.withLock {
        val (ok, blob) = fetch()
        if (!ok) return@withLock false
        val first = firstTime || !meta.getBoolean("seeded", false)
        if (blob == null || blob.optInt("v") != V) { meta.edit().putBoolean("seeded", true).apply(); return@withLock false }
        val now = Profiles.currentId()
        var changed = false
        applying = true
        try {
            // The profiles themselves first, so each profile's file is known.
            blob.optJSONObject("p")?.let { p ->
                val ids = p.keys().asSequence().toList().sortedBy { it != "1" }
                for (id in ids) {
                    val entries = p.optJSONObject(id) ?: continue
                    val n = id.toIntOrNull() ?: continue
                    val prefs = sp(Profiles.prefsName(n))
                    val ed = prefs.edit(); val mt = meta.edit(); var any = false
                    for (k in entries.keys()) {
                        if (!syncs(k)) continue
                        val o = entries.optJSONObject(k) ?: continue
                        val at = o.optLong("at")
                        val local = prefs.all[k]
                        // Older than this device's copy: skipped, unless this device has nothing there (a key, sign-in
                        // or setting it never had). Settings saved before the sync existed carry no time (0), so
                        // without this a device already signed in to Nuvio never got them (user 2026-11-23: Trakt asked again).
                        val fills = o.has("v") && blank(local) && !(o.optString("t") == "s" && o.optString("v").isBlank())
                        if (!first && !fills && at <= meta.getLong("$n|$k", 0)) continue
                        if (!o.has("v")) {
                            if (CRED.containsMatchIn(k) || local == null) continue
                            ed.remove(k)
                        } else {
                            val remoteBlank = o.optString("t") == "s" && o.optString("v").isBlank()
                            if (remoteBlank && CRED.containsMatchIn(k) && !blank(local)) continue
                            if (enc(local)?.opt("v")?.toString() == o.opt("v")?.toString()) { mt.putLong("$n|$k", at); continue }
                            write(ed, k, o)
                        }
                        mt.putLong("$n|$k", at); any = true
                        if (n == now || (n == 1 && Profiles.isDevice(k))) { changed = true; o.opt("v")?.let { App.instance.prefs.setByKey(k, it.toString()) } }
                    }
                    if (any) ed.commit()
                    mt.apply()
                    if (n != 1) watch(n, Profiles.prefsName(n))
                }
            }
        } finally { applying = false }
        meta.edit().putBoolean("seeded", true).putLong("pulled_at", System.currentTimeMillis()).apply()
        if (changed) { App.instance.trakt.reload(); Integrations.touch() }
        // This device has newer settings than the account's copy: send them.
        if (meta.all.any { (k, v) -> k.contains('|') && v is Long && v > blob.optLong("at") }) schedulePush()
        changed
    }

    /** Sends this device's settings, after taking any newer ones from the account. */
    suspend fun push(): Boolean { pull(firstTime = false); return lock.withLock { pushNow() } }

    private suspend fun pushNow(): Boolean {
        val p = JSONObject()
        for ((id, name) in files()) {
            val o = JSONObject()
            val all = sp(name).all
            all.forEach { (k, v) ->
                if (!syncs(k)) return@forEach
                val e = enc(v) ?: return@forEach
                if (v is String && v.length > 64_000) return@forEach
                o.put(k, e.put("at", meta.getLong("$id|$k", 0)))
            }
            // Removed here since: sent as removed (never for sign-ins and keys).
            meta.all.forEach { (mk, at) ->
                if (!mk.startsWith("$id|") || at !is Long) return@forEach
                val k = mk.substringAfter('|')
                if (k !in all && syncs(k) && !CRED.containsMatchIn(k)) o.put(k, JSONObject().put("at", at))
            }
            p.put(id.toString(), o)
        }
        val ok = send(JSONObject().put("v", V).put("at", System.currentTimeMillis()).put("p", p))
        if (ok) meta.edit().putLong("pushed_at", System.currentTimeMillis()).apply()
        return ok
    }

    /** Starts the app again (after a whole setup came in), like a profile switch. */
    fun restart(c: Context) {
        val i = c.packageManager.getLaunchIntentForPackage(c.packageName)!!
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        c.startActivity(i)
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    fun lastSync(): Long = maxOf(meta.getLong("pulled_at", 0), meta.getLong("pushed_at", 0))
}
