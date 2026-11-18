package com.lekathedon.visionlite.integrations

import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.BuildConfig
import com.lekathedon.visionlite.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * Website sign-ins with the least typing (Vision's flows): Simkl = approve a code on the phone, nothing typed;
 * AniList / MyAnimeList = approve on the phone, then paste what the page shows into the TV's phone form.
 * The apps' IDs come from the build (repo secrets); the tokens stay on this TV.
 */
object SignIns {
    private val p get() = App.instance.prefs
    private fun t(key: String) = p.sp.getString(key, "") ?: ""
    private fun put(vararg kv: Pair<String, String>) {
        p.sp.edit().apply { kv.forEach { (k, v) -> putString(k, v) } }.apply()
    }

    fun configured(id: String) = when (id) {
        "simkl" -> BuildConfig.SIMKL_CLIENT_ID.isNotBlank()
        "anilist" -> BuildConfig.ANILIST_CLIENT_ID.isNotBlank()
        "mal" -> BuildConfig.MAL_CLIENT_ID.isNotBlank()
        "nuvio" -> BuildConfig.NUVIO_KEY.isNotBlank()
        else -> false
    }

    fun signedIn(id: String) = t("si_${id}_token").isNotBlank()
    fun user(id: String) = t("si_${id}_user")
    fun signOut(id: String) = put("si_${id}_token" to "", "si_${id}_refresh" to "", "si_${id}_user" to "", "si_${id}_until" to "")

    private fun save(id: String, token: String, refresh: String, expiresInS: Long, user: String) = put(
        "si_${id}_token" to token, "si_${id}_refresh" to refresh, "si_${id}_user" to user,
        "si_${id}_until" to if (expiresInS > 0) (System.currentTimeMillis() + expiresInS * 1000).toString() else "")

    /**
     * Simkl's shows and anime you watch, finished, put on hold or plan to watch (not dropped), for Coming up:
     * (IMDb id, title). One request; nothing when not signed in.
     */
    suspend fun simklShows(): List<Pair<String, String>> {
        if (!signedIn("simkl")) return emptyList()
        val (code, body) = call(Request.Builder().url("https://api.simkl.com/sync/all-items/?extended=full")
            .header("Authorization", "Bearer ${t("si_simkl_token")}").header("simkl-api-key", BuildConfig.SIMKL_CLIENT_ID).build())
        if (code !in 200..299) return emptyList()
        return runCatching {
            val o = org.json.JSONObject(body)
            listOf("shows", "anime").flatMap { k ->
                val a = o.optJSONArray(k) ?: return@flatMap emptyList()
                (0 until a.length()).mapNotNull { i ->
                    val it = a.optJSONObject(i) ?: return@mapNotNull null
                    if (it.optString("status") == "dropped") return@mapNotNull null
                    val sh = it.optJSONObject("show") ?: return@mapNotNull null
                    val imdb = sh.optJSONObject("ids")?.optString("imdb")?.takeIf { x -> x.startsWith("tt") } ?: return@mapNotNull null
                    imdb to sh.optString("title")
                }
            }
        }.getOrDefault(emptyList())
    }

    // ---- Simkl: PIN (old apps) or device code (Simkl's "V2" apps), both just "approve on the phone" ----

    class Code(val url: String, val userCode: String, val interval: Int, val expiresIn: Int, internal val device: String?)

    suspend fun simklStart(): Code {
        val id = BuildConfig.SIMKL_CLIENT_ID
        val (code, body) = call(Request.Builder().url("https://api.simkl.com/oauth/pin?client_id=${enc(id)}").header("simkl-api-key", id).build())
        if (code in 200..299) {
            val j = JSONObject(body)
            val user = j.optString("user_code")
            if (user.isNotBlank()) return Code(j.optString("verification_url").ifBlank { "https://simkl.com/pin" }.let { "$it/$user".replace("pin//", "pin/") },
                user, j.optInt("interval", 5), j.optInt("expires_in", 900), null)
        }
        // A V2 app: Simkl says to use the device flow instead.
        val (c2, b2) = call(Request.Builder().url("https://api.simkl.com/oauth2/device")
            .post(FormBody.Builder().add("client_id", id).add("scope", "media:read media:write").build()).build())
        val j = JSONObject(b2.ifBlank { "{}" })
        if (c2 !in 200..299 || j.optString("device_code").isBlank()) throw java.io.IOException("Simkl $c2 ${j.optString("message")}")
        return Code(j.optString("verification_uri_complete").ifBlank { j.optString("verification_uri", "https://simkl.com/pin") },
            j.optString("user_code"), j.optInt("interval", 5), j.optInt("expires_in", 900), j.optString("device_code"))
    }

    /** Polls until approved on the phone. True = signed in. */
    suspend fun simklWait(c: Code): Boolean {
        val id = BuildConfig.SIMKL_CLIENT_ID
        val until = System.currentTimeMillis() + c.expiresIn * 1000L
        var interval = c.interval.coerceAtLeast(3) * 1000L
        while (System.currentTimeMillis() < until) {
            delay(interval)
            val (code, body) = if (c.device == null)
                call(Request.Builder().url("https://api.simkl.com/oauth/pin/${enc(c.userCode)}?client_id=${enc(id)}").header("simkl-api-key", id).build())
            else call(Request.Builder().url("https://api.simkl.com/oauth2/token").post(FormBody.Builder()
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code").add("device_code", c.device).add("client_id", id).build()).build())
            val j = runCatching { JSONObject(body) }.getOrNull() ?: continue
            val token = j.optString("access_token")
            if (code in 200..299 && token.isNotBlank()) {
                save("simkl", token, j.optString("refresh_token"), j.optLong("expires_in", 0), simklUser(token)); return true
            }
            when (j.optString("error")) {
                "slow_down" -> interval += 2000
                "access_denied", "expired_token" -> return false
            }
        }
        return false
    }

    private suspend fun simklUser(token: String): String {
        val id = BuildConfig.SIMKL_CLIENT_ID
        val (_, b) = call(Request.Builder().url("https://api.simkl.com/users/settings").header("Authorization", "Bearer $token").header("simkl-api-key", id).build())
        return runCatching { JSONObject(b).getJSONObject("user").optString("name") }.getOrDefault("")
    }

    // ---- AniList: approve, then paste the code AniList shows ----

    fun aniListUrl() = "https://anilist.co/api/v2/oauth/authorize?client_id=${enc(BuildConfig.ANILIST_CLIENT_ID)}&response_type=token"

    /** The token from what was pasted (the code itself, or an address holding access_token=…). Null = not accepted. */
    suspend fun aniListFinish(pasted: String): String? {
        val text = pasted.trim()
        val token = Regex("access_token=([^&#\\s]+)").find(text)?.let { dec(it.groupValues[1]) }
            ?: Regex("[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}").find(text)?.value
            ?: return null
        val (code, body) = call(Request.Builder().url("https://graphql.anilist.co").header("Authorization", "Bearer $token")
            .post(JSONObject().put("query", "{ Viewer { name } }").toString().toRequestBody("application/json".toMediaType())).build())
        val name = runCatching { JSONObject(body).getJSONObject("data").getJSONObject("Viewer").optString("name") }.getOrNull()
        if (code !in 200..299 || name.isNullOrBlank()) return null
        save("anilist", token, "", 365L * 86_400, name)
        return name
    }

    // ---- MyAnimeList: approve (PKCE), then paste the address the page ends on ----

    private var malVerifier = ""
    private var malState = ""

    fun malUrl(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val r = SecureRandom()
        malVerifier = buildString { repeat(128) { append(chars[r.nextInt(chars.length)]) } }
        malState = buildString { repeat(16) { append(chars[r.nextInt(62)]) } }
        return "https://myanimelist.net/v1/oauth2/authorize?response_type=code&client_id=${enc(BuildConfig.MAL_CLIENT_ID)}" +
            "&code_challenge=${enc(malVerifier)}&code_challenge_method=plain&state=${enc(malState)}"
    }

    /** Signs in from the pasted address (or bare code). Null = refused. */
    suspend fun malFinish(pasted: String): String? {
        val text = pasted.trim()
        val q = text.substringAfter('?', text).split('&').mapNotNull { kv ->
            val i = kv.indexOf('='); if (i <= 0) null else kv.substring(0, i) to dec(kv.substring(i + 1))
        }.toMap()
        if (q["state"] != null && q["state"] != malState) return null
        val code = q["code"] ?: text.takeIf { !it.contains('=') && it.length >= 20 && !it.contains(' ') } ?: return null
        val form = FormBody.Builder().add("client_id", BuildConfig.MAL_CLIENT_ID).apply {
            if (BuildConfig.MAL_CLIENT_SECRET.isNotBlank()) add("client_secret", BuildConfig.MAL_CLIENT_SECRET)
        }.add("grant_type", "authorization_code").add("code", code).add("code_verifier", malVerifier).build()
        val (c, b) = call(Request.Builder().url("https://myanimelist.net/v1/oauth2/token").post(form).build())
        val j = runCatching { JSONObject(b) }.getOrNull() ?: return null
        val token = j.optString("access_token")
        if (c !in 200..299 || token.isBlank()) return null
        val (_, ub) = call(Request.Builder().url("https://api.myanimelist.net/v2/users/@me").header("Authorization", "Bearer $token").build())
        val name = runCatching { JSONObject(ub).optString("name") }.getOrDefault("")
        save("mal", token, j.optString("refresh_token"), j.optLong("expires_in", 2_678_400), name)
        return name.ifBlank { "signed in" }
    }

    // ---- Nuvio account (Vision's TV sign-in: scan, approve on nuvio.tv, nothing typed) ----
    private const val NUVIO = "https://api.nuvio.tv"
    private var nuvioNonce = ""
    private val JSON = "application/json".toMediaType()
    private fun nuvioReq(path: String, body: String, token: String? = null) = Request.Builder().url(NUVIO + path)
        .header("apikey", BuildConfig.NUVIO_KEY).header("Accept", "application/json")
        .apply { if (token != null) header("Authorization", "Bearer $token") }
        .post(body.toRequestBody(JSON)).build()

    suspend fun nuvioStart(): Code {
        val b = ByteArray(16).also { SecureRandom().nextBytes(it) }
        nuvioNonce = b.joinToString("") { "%02x".format(it) }
        val body = JSONObject().put("p_device_nonce", nuvioNonce).put("p_redirect_base_url", "https://nuvio.tv/link")
            .put("p_device_type", "tv").put("p_device_name", "Vision Lite").toString()
        val (c, r) = call(nuvioReq("/rest/v1/rpc/start_device_login_session", body))
        val j = runCatching { org.json.JSONArray(r).getJSONObject(0) }.getOrNull()
        if (c !in 200..299 || j == null || j.optString("device_code").isBlank()) throw java.io.IOException("Nuvio $c")
        val left = runCatching { (java.time.OffsetDateTime.parse(j.optString("expires_at")).toInstant().toEpochMilli() - System.currentTimeMillis()) / 1000 }
            .getOrDefault(600L).toInt().coerceIn(60, 1800)
        return Code(j.optString("verification_uri_complete").ifBlank { "https://nuvio.tv/link" }, j.optString("user_code"),
            j.optInt("poll_interval_seconds", 3), left, j.optString("device_code"))
    }

    /** Polls until approved on the phone, then trades the code for the account's tokens. True = signed in. */
    suspend fun nuvioWait(c: Code): Boolean {
        val dc = c.device ?: return false
        val until = System.currentTimeMillis() + c.expiresIn * 1000L
        while (System.currentTimeMillis() < until) {
            delay(c.interval.coerceAtLeast(2) * 1000L)
            val (_, r) = call(nuvioReq("/rest/v1/rpc/poll_tv_login_session",
                JSONObject().put("p_code", dc).put("p_device_nonce", nuvioNonce).toString()))
            when (runCatching { org.json.JSONArray(r).getJSONObject(0).optString("status") }.getOrDefault("")) {
                "approved" -> {
                    val (ec, eb) = call(nuvioReq("/functions/v1/tv-logins-exchange",
                        JSONObject().put("code", dc).put("device_nonce", nuvioNonce).toString()))
                    val j = runCatching { JSONObject(eb) }.getOrNull() ?: return false
                    val token = j.optString("access_token")
                    if (ec !in 200..299 || token.isBlank()) return false
                    save("nuvio", token, j.optString("refresh_token"), j.optLong("expires_in", 3600),
                        j.optJSONObject("user")?.optString("email").orEmpty())
                    return true
                }
                "expired", "denied", "rejected", "consumed", "cancelled" -> return false
            }
        }
        return false
    }

    /** A live access token: refreshed when it ran out (Supabase tokens last an hour). Blank = not signed in. */
    private suspend fun nuvioToken(): String {
        val token = t("si_nuvio_token").ifBlank { return "" }
        val until = t("si_nuvio_until").toLongOrNull() ?: 0L
        if (until > System.currentTimeMillis() + 60_000) return token
        val (c, r) = call(nuvioReq("/auth/v1/token?grant_type=refresh_token",
            JSONObject().put("refresh_token", t("si_nuvio_refresh")).toString()))
        val j = runCatching { JSONObject(r) }.getOrNull()
        val fresh = j?.optString("access_token").orEmpty()
        if (c !in 200..299 || fresh.isBlank()) return if (c in 400..499) "" else token
        save("nuvio", fresh, j!!.optString("refresh_token").ifBlank { t("si_nuvio_refresh") }, j.optLong("expires_in", 3600), user("nuvio"))
        return fresh
    }

    /** The account's profiles: (number, name). */
    suspend fun nuvioProfiles(): List<Pair<Int, String>> {
        val tk = nuvioToken().ifBlank { return emptyList() }
        val (c, r) = call(nuvioReq("/rest/v1/rpc/sync_pull_profiles", "{}", tk))
        if (c !in 200..299) return emptyList()
        val a = runCatching { org.json.JSONArray(r) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .map { it.optInt("profile_index", 1) to it.optString("name").ifBlank { "Profile ${it.optInt("profile_index", 1)}" } }
            .sortedBy { it.first }
    }
    fun nuvioProfile(): Int = t("si_nuvio_profile").toIntOrNull() ?: 1
    fun nuvioProfileName(): String = t("si_nuvio_profile_name")
    fun setNuvioProfile(n: Int, name: String) = put("si_nuvio_profile" to n.toString(), "si_nuvio_profile_name" to name)

    /**
     * For Coming up: the shows in the Nuvio library and every show with a watched episode in the Nuvio account
     * (what Vision and Nuvio save there), for the chosen profile. (IMDb id, title). A few requests; nothing when
     * not signed in.
     */
    suspend fun nuvioShows(): List<Pair<String, String>> {
        val tk = nuvioToken().ifBlank { return emptyList() }
        val prof = nuvioProfile()
        val out = LinkedHashMap<String, String>()
        fun take(a: org.json.JSONArray, title: String) {
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val type = o.optString("content_type")
                if (type != "series" && type != "tv" && type != "anime") continue
                val id = o.optString("content_id").substringBefore(':')
                if (id.startsWith("tt")) out.putIfAbsent(id, o.optString(title))
            }
        }
        var offset = 0
        while (offset < 5000) {
            val (c, r) = call(nuvioReq("/rest/v1/rpc/sync_pull_library",
                JSONObject().put("p_profile_id", prof).put("p_limit", 500).put("p_offset", offset).toString(), tk))
            val a = runCatching { org.json.JSONArray(r) }.getOrNull()
            if (c !in 200..299 || a == null) break
            take(a, "name"); if (a.length() < 500) break; offset += 500
        }
        var page = 1
        while (page <= 20) {
            val (c, r) = call(nuvioReq("/rest/v1/rpc/sync_pull_watched_items",
                JSONObject().put("p_profile_id", prof).put("p_page", page).put("p_page_size", 900).toString(), tk))
            val a = runCatching { org.json.JSONArray(r) }.getOrNull()
            if (c !in 200..299 || a == null) break
            take(a, "title"); if (a.length() < 900) break; page++
        }
        return out.map { it.key to it.value }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")

    private suspend fun call(r: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        runCatching { Net.http.newCall(r).execute().use { it.code to (it.body?.string() ?: "") } }.getOrDefault(0 to "")
    }
}
