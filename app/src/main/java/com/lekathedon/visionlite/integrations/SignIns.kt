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
        else -> false
    }

    fun signedIn(id: String) = t("si_${id}_token").isNotBlank()
    fun user(id: String) = t("si_${id}_user")
    fun signOut(id: String) = put("si_${id}_token" to "", "si_${id}_refresh" to "", "si_${id}_user" to "", "si_${id}_until" to "")

    private fun save(id: String, token: String, refresh: String, expiresInS: Long, user: String) = put(
        "si_${id}_token" to token, "si_${id}_refresh" to refresh, "si_${id}_user" to user,
        "si_${id}_until" to if (expiresInS > 0) (System.currentTimeMillis() + expiresInS * 1000).toString() else "")

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

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")

    private suspend fun call(r: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        runCatching { Net.http.newCall(r).execute().use { it.code to (it.body?.string() ?: "") } }.getOrDefault(0 to "")
    }
}
