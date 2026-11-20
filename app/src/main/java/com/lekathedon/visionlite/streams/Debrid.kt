package com.lekathedon.visionlite.streams

import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.integrations.Integrations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Your own debrid accounts for torrent links (Vision's direct debrid, written small): a torrent row from your add-ons
 * (a hash, no link) becomes playable when Real-Debrid, AllDebrid or Premiumize is set up. Pressing it asks the
 * services in your Debrid order for a ready (cached) copy and plays its file: the right episode by name, else the
 * add-on's file number, else the biggest video. A copy that isn't ready is removed again, never left downloading.
 * Sign-in: Real-Debrid and AllDebrid by a code on their website (DebridSignIn); Premiumize by its key from the phone.
 * Cost: nothing until a torrent row is pressed; then 2-5 small requests to one service.
 */
object Debrid {
    class Id(val id: String, val name: String)
    val RD = Id("realdebrid", "Real-Debrid")
    val AD = Id("alldebrid", "AllDebrid")
    val PM = Id("premiumize", "Premiumize")
    private val ALL = listOf(RD, AD, PM)

    /** Services set up and allowed (Integrations: driver first, then helpers that are on). */
    fun ready(): List<Id> {
        val driver = Integrations.driver(Integrations.Group.LINKS)?.id
        return ALL.filter { Integrations.active(it.id) }.sortedBy { if (it.id == driver) 0 else 1 }
    }
    fun any() = ready().isNotEmpty()
    fun names() = ready().joinToString(" / ") { it.name }

    /** A playable URL for torrent [hash], or a reason it can't (Result failure with a short message). */
    suspend fun resolve(hash: String, fileIdx: Int?, fileName: String?, t: EpisodeCheck.Target?): Result<String> = withContext(Dispatchers.IO) {
        var last: Throwable = IllegalStateException("No debrid account set up")
        for (s in ready()) {
            try {
                val url = when (s.id) {
                    RD.id -> realDebrid(hash, fileIdx, fileName, t)
                    AD.id -> allDebrid(hash, fileIdx, fileName, t)
                    else -> premiumize(hash, fileIdx, fileName, t)
                }
                if (url != null) return@withContext Result.success(url)
                last = IllegalStateException("${s.name}: not ready (not cached)")
            } catch (e: CancellationException) { throw e } catch (e: Exception) { last = IllegalStateException("${s.name}: ${e.message ?: "failed"}") }
        }
        Result.failure(last)
    }

    // ---- file choice (Vision's DebridFileSelection, short) ----

    class File(val index: Int, val name: String, val bytes: Long, val ref: Any?)

    private val VIDEO = Regex("""\.(mkv|mp4|avi|m4v|ts|m2ts|webm|mov|wmv)$""", RegexOption.IGNORE_CASE)
    private val SAMPLE = Regex("""\b(sample|trailer|extras?)\b""", RegexOption.IGNORE_CASE)

    fun pick(files: List<File>, fileIdx: Int?, fileName: String?, t: EpisodeCheck.Target?): File? {
        val videos = files.filter { VIDEO.containsMatchIn(it.name) && !SAMPLE.containsMatchIn(it.name.substringAfterLast('/')) }.ifEmpty { files }
        fileName?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { n -> videos.firstOrNull { it.name.substringAfterLast('/').equals(n, true) }?.let { return it } }
        if (t != null) {
            val s = t.season; val e = t.episode
            val ep = Regex("""(?i)(?:s0*$s[ ._-]*e0*$e(?!\d)|\b0*$s[x×]0*$e(?!\d)|season[ ._-]*0*$s.*?episode[ ._-]*0*$e(?!\d))""")
            videos.filter { ep.containsMatchIn(it.name) }.maxByOrNull { it.bytes }?.let { return it }
        }
        fileIdx?.let { i -> files.firstOrNull { it.index == i }?.let { return it } }
        return if (t == null) videos.maxByOrNull { it.bytes } else null
    }

    private fun magnet(hash: String) = "magnet:?xt=urn:btih:${hash.lowercase()}"

    private fun call(r: Request): Pair<Int, String> = Net.http.newCall(r).execute().use { it.code to (it.body?.string() ?: "") }

    // ---- Real-Debrid ----

    private const val RD_API = "https://api.real-debrid.com/rest/1.0"

    private suspend fun realDebrid(hash: String, fileIdx: Int?, fileName: String?, t: EpisodeCheck.Target?): String? {
        val auth = DebridSignIn.rdAuthorization() ?: throw IllegalStateException("sign in again")
        fun req(path: String) = Request.Builder().url(RD_API + path).header("Authorization", auth)
        val (c1, b1) = call(req("/torrents/addMagnet").post(FormBody.Builder().add("magnet", magnet(hash)).build()).build())
        val id = runCatching { JSONObject(b1).optString("id") }.getOrNull()?.takeIf { c1 in 200..299 && it.isNotBlank() }
            ?: throw IllegalStateException("refused the torrent ($c1)")
        try {
            var info = JSONObject(call(req("/torrents/info/$id").build()).second)
            val files = info.optJSONArray("files") ?: JSONArray()
            val all = (0 until files.length()).map { i -> files.getJSONObject(i).let { File(it.optInt("id"), it.optString("path"), it.optLong("bytes"), it.optInt("id")) } }
            // RD numbers files from 1; add-ons from 0.
            val f = pick(all, fileIdx?.plus(1), fileName, t) ?: return null.also { drop(auth, id) }
            call(req("/torrents/selectFiles/$id").post(FormBody.Builder().add("files", f.ref.toString()).build()).build())
            repeat(4) {
                info = JSONObject(call(req("/torrents/info/$id").build()).second)
                if (info.optString("status") == "downloaded") {
                    val link = info.optJSONArray("links")?.optString(0)?.takeIf { it.isNotBlank() } ?: return null
                    val (c, b) = call(req("/unrestrict/link").post(FormBody.Builder().add("link", link).build()).build())
                    return JSONObject(b).optString("download").takeIf { c in 200..299 && it.isNotBlank() }
                }
                delay(700)
            }
            drop(auth, id); return null
        } catch (e: Exception) { if (e !is CancellationException) runCatching { drop(auth, id) }; throw e }
    }

    private fun drop(auth: String, id: String) {
        runCatching { call(Request.Builder().url("$RD_API/torrents/delete/$id").header("Authorization", auth).delete().build()) }
    }

    // ---- AllDebrid ----

    private const val AD_API = "https://api.alldebrid.com"
    const val AD_AGENT = "visionlite"

    private fun adReq(path: String, key: String) = Request.Builder().url("$AD_API/$path${if ('?' in path) '&' else '?'}agent=$AD_AGENT").header("Authorization", "Bearer $key")

    private suspend fun allDebrid(hash: String, fileIdx: Int?, fileName: String?, t: EpisodeCheck.Target?): String? {
        val key = DebridSignIn.adKey() ?: throw IllegalStateException("sign in again")
        val up = JSONObject(call(adReq("v4/magnet/upload", key).post(FormBody.Builder().add("magnets[]", hash).build()).build()).second)
        val m = up.optJSONObject("data")?.optJSONArray("magnets")?.optJSONObject(0) ?: throw IllegalStateException(up.optJSONObject("error")?.optString("message") ?: "refused")
        val id = m.optLong("id")
        if (!m.optBoolean("ready")) { adDrop(key, id); return null }
        val fr = JSONObject(call(adReq("v4/magnet/files", key).post(FormBody.Builder().add("id[]", id.toString()).build()).build()).second)
        val tree = fr.optJSONObject("data")?.optJSONArray("magnets")?.optJSONObject(0)?.optJSONArray("files") ?: return null
        val flat = ArrayList<File>()
        fun walk(a: JSONArray, path: String) {
            for (i in 0 until a.length()) {
                val n = a.getJSONObject(i)
                val name = n.optString("n")
                n.optJSONArray("e")?.let { walk(it, "$path$name/") } ?: flat.add(File(flat.size, path + name, n.optLong("s"), n.optString("l")))
            }
        }
        walk(tree, "")
        val f = pick(flat, fileIdx, fileName, t) ?: return null
        val un = JSONObject(call(adReq("v4/link/unlock", key).post(FormBody.Builder().add("link", f.ref.toString()).build()).build()).second)
        return un.optJSONObject("data")?.optString("link")?.takeIf { it.isNotBlank() }
    }

    private fun adDrop(key: String, id: Long) {
        runCatching { call(adReq("v4/magnet/delete", key).post(FormBody.Builder().add("id", id.toString()).build()).build()) }
    }

    // ---- Premiumize ----

    private fun premiumize(hash: String, fileIdx: Int?, fileName: String?, t: EpisodeCheck.Target?): String? {
        val key = Integrations.values(Integrations.byId(PM.id)).firstOrNull().orEmpty().ifBlank { throw IllegalStateException("no key") }
        val r = Request.Builder().url("https://www.premiumize.me/api/transfer/directdl?apikey=${java.net.URLEncoder.encode(key, "UTF-8")}")
            .post(FormBody.Builder().add("src", magnet(hash)).build()).build()
        val j = JSONObject(call(r).second)
        if (j.optString("status") != "success") return null
        val c = j.optJSONArray("content") ?: return null
        val files = (0 until c.length()).map { i -> c.getJSONObject(i).let { File(i, it.optString("path"), it.optLong("size"), it.optString("link")) } }
        return pick(files, fileIdx, fileName, t)?.ref?.toString()?.takeIf { it.isNotBlank() }
    }
}

/**
 * Real-Debrid's device sign-in (its open-source app id, as Vision) and AllDebrid's PIN: a code shows on the TV,
 * you approve on the website (QR), the TV keeps the token. A Real-Debrid API token saved the old way still works.
 */
object DebridSignIn {
    private val sp get() = App.instance.prefs.sp
    private fun t(k: String) = sp.getString(k, "") ?: ""
    private fun put(vararg kv: Pair<String, String>) = sp.edit().apply { kv.forEach { (k, v) -> putString(k, v) } }.apply()
    private const val RD_OAUTH = "https://api.real-debrid.com/oauth/v2"
    private const val RD_OPEN_ID = "X245A4XAIBGVM"

    class Code(val url: String, val userCode: String, val interval: Int, val expiresIn: Int, val device: String)

    fun signedIn(id: String) = when (id) {
        "realdebrid" -> t("si_realdebrid_refresh").isNotBlank() || t("int_realdebrid_0").isNotBlank()
        "alldebrid" -> t("si_alldebrid_token").isNotBlank()
        else -> false
    }
    fun user(id: String) = t("si_${id}_user")
    fun signOut(id: String) = put("int_${id}_0" to "", "si_${id}_token" to "", "si_${id}_refresh" to "", "si_${id}_cid" to "", "si_${id}_secret" to "", "si_${id}_user" to "", "si_${id}_until" to "")

    private fun call(r: Request): Pair<Int, String> = runCatching { Net.http.newCall(r).execute().use { it.code to (it.body?.string() ?: "") } }.getOrDefault(0 to "")

    suspend fun start(id: String): Code = withContext(Dispatchers.IO) {
        if (id == "realdebrid") {
            val (c, b) = call(Request.Builder().url("$RD_OAUTH/device/code?client_id=$RD_OPEN_ID&new_credentials=yes").build())
            val j = JSONObject(b.ifBlank { "{}" })
            if (c !in 200..299 || j.optString("device_code").isBlank()) throw java.io.IOException("Real-Debrid $c")
            Code(j.optString("direct_verification_url").ifBlank { j.optString("verification_url") }, j.optString("user_code"),
                j.optInt("interval", 5), j.optInt("expires_in", 600), j.optString("device_code"))
        } else {
            val (c, b) = call(Request.Builder().url("https://api.alldebrid.com/v4.1/pin/get?agent=${Debrid.AD_AGENT}").build())
            val d = JSONObject(b.ifBlank { "{}" }).optJSONObject("data") ?: throw java.io.IOException("AllDebrid $c")
            Code(d.optString("user_url").ifBlank { "https://alldebrid.com/pin/" }, d.optString("pin"), 5, d.optInt("expires_in", 600), d.optString("check"))
        }
    }

    /** Polls until approved. True = signed in. */
    suspend fun wait(id: String, c: Code): Boolean = withContext(Dispatchers.IO) {
        val until = System.currentTimeMillis() + c.expiresIn * 1000L
        while (System.currentTimeMillis() < until) {
            delay(c.interval.coerceAtLeast(3) * 1000L)
            if (id == "realdebrid") {
                val (code, b) = call(Request.Builder().url("$RD_OAUTH/device/credentials?client_id=$RD_OPEN_ID&code=${c.device}").build())
                val j = runCatching { JSONObject(b) }.getOrNull() ?: continue
                val cid = j.optString("client_id"); val sec = j.optString("client_secret")
                if (code !in 200..299 || cid.isBlank() || sec.isBlank()) continue
                val tok = token(cid, sec, c.device) ?: return@withContext false
                put("si_realdebrid_cid" to cid, "si_realdebrid_secret" to sec, "si_realdebrid_token" to tok.first,
                    "si_realdebrid_refresh" to tok.second, "si_realdebrid_until" to (System.currentTimeMillis() + tok.third * 1000).toString())
                val me = runCatching { JSONObject(call(Request.Builder().url("https://api.real-debrid.com/rest/1.0/user").header("Authorization", "Bearer ${tok.first}").build()).second) }.getOrNull()
                put("si_realdebrid_user" to (me?.optString("username") ?: ""))
                return@withContext true
            } else {
                val (_, b) = call(Request.Builder().url("https://api.alldebrid.com/v4/pin/check?agent=${Debrid.AD_AGENT}")
                    .post(FormBody.Builder().add("check", c.device).add("pin", c.userCode).build()).build())
                val d = runCatching { JSONObject(b).optJSONObject("data") }.getOrNull() ?: continue
                val key = d.optString("apikey")
                if (d.optBoolean("activated") && key.isNotBlank()) {
                    put("si_alldebrid_token" to key)
                    val me = runCatching { JSONObject(call(Request.Builder().url("https://api.alldebrid.com/v4/user?agent=${Debrid.AD_AGENT}").header("Authorization", "Bearer $key").build()).second) }.getOrNull()
                    put("si_alldebrid_user" to (me?.optJSONObject("data")?.optJSONObject("user")?.optString("username") ?: ""))
                    return@withContext true
                }
            }
        }
        false
    }

    /** (access, refresh, expires in s) */
    private fun token(cid: String, secret: String, code: String): Triple<String, String, Long>? {
        val (c, b) = call(Request.Builder().url("$RD_OAUTH/token").post(FormBody.Builder().add("client_id", cid).add("client_secret", secret)
            .add("code", code).add("grant_type", "http://oauth.net/grant_type/device/1.0").build()).build())
        val j = runCatching { JSONObject(b) }.getOrNull() ?: return null
        val a = j.optString("access_token").takeIf { c in 200..299 && it.isNotBlank() } ?: return null
        return Triple(a, j.optString("refresh_token"), j.optLong("expires_in", 3600))
    }

    /** "Bearer …" for Real-Debrid: the saved API token, or the sign-in's access token (refreshed near its end). */
    @Synchronized fun rdAuthorization(): String? {
        // An API token saved before sign-in existed (Settings kept it as a key).
        t("int_realdebrid_0").takeIf { it.isNotBlank() }?.let { return "Bearer $it" }
        val refresh = t("si_realdebrid_refresh").ifBlank { return null }
        val until = t("si_realdebrid_until").toLongOrNull() ?: 0L
        if (System.currentTimeMillis() < until - 120_000) return "Bearer " + t("si_realdebrid_token")
        val tok = token(t("si_realdebrid_cid"), t("si_realdebrid_secret"), refresh) ?: return null
        put("si_realdebrid_token" to tok.first, "si_realdebrid_refresh" to tok.second.ifBlank { refresh },
            "si_realdebrid_until" to (System.currentTimeMillis() + tok.third * 1000).toString())
        return "Bearer " + tok.first
    }

    fun adKey(): String? = t("si_alldebrid_token").ifBlank { null }
}
