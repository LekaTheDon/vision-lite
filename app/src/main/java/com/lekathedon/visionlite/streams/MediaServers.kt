package com.lekathedon.visionlite.streams

import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.addons.Stream
import com.lekathedon.visionlite.data.Net
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Your own Plex and Jellyfin servers as link sources (Vision's media servers, written small). After Play, the title
 * is looked up on each signed-in server by its IMDb/TMDB id and its file shows in the links list (group "Plex" /
 * "Jellyfin"), played straight from the server. Plus a Home row "On your server": what was added last.
 * Sign-in: Plex by a code at plex.tv/link; Jellyfin by Quick Connect (its address from the phone, then a code you
 * approve in Jellyfin). Cost: nothing until Play (or Home, once per start, when signed in); 1-3 requests a server.
 */
object MediaServers {
    const val PLEX = "Plex"
    const val JELLYFIN = "Jellyfin"
    private val sp get() = App.instance.prefs.sp
    private fun t(k: String) = sp.getString(k, "") ?: ""
    private fun put(vararg kv: Pair<String, String>) = sp.edit().apply { kv.forEach { (k, v) -> putString(k, v) } }.apply()
    private val http by lazy { Net.http.newBuilder().callTimeout(8, TimeUnit.SECONDS).build() }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun call(r: Request): Pair<Int, String> = runCatching { http.newCall(r).execute().use { it.code to (it.body?.string() ?: "") } }.getOrDefault(0 to "")

    private val clientId: String get() = t("ms_client").ifBlank { java.util.UUID.randomUUID().toString().also { put("ms_client" to it) } }

    fun plexOn() = t("ms_plex_token").isNotBlank() && com.lekathedon.visionlite.integrations.Integrations.helper(com.lekathedon.visionlite.integrations.Integrations.byId("plex")).value
    fun jellyfinOn() = t("ms_jf_token").isNotBlank() && com.lekathedon.visionlite.integrations.Integrations.helper(com.lekathedon.visionlite.integrations.Integrations.byId("jellyfin")).value
    fun signedIn(id: String) = if (id == "plex") t("ms_plex_token").isNotBlank() else t("ms_jf_token").isNotBlank()
    fun user(id: String) = if (id == "plex") t("ms_plex_user") else t("ms_jf_user")
    fun signOut(id: String) = if (id == "plex") put("ms_plex_token" to "", "ms_plex_user" to "", "ms_plex_servers" to "")
        else put("ms_jf_token" to "", "ms_jf_user" to "", "ms_jf_uid" to "")

    // ---- Plex sign-in: a 4-letter code at plex.tv/link ----

    private fun plexReq(url: String, token: String? = null) = Request.Builder().url(url).header("Accept", "application/json")
        .header("X-Plex-Client-Identifier", clientId).header("X-Plex-Product", "Vision").header("X-Plex-Device-Name", "Vision TV")
        .apply { token?.let { header("X-Plex-Token", it) } }

    class Code(val url: String, val userCode: String, val id: String, val expiresIn: Int)

    suspend fun plexStart(): Code = withContext(Dispatchers.IO) {
        val (c, b) = call(plexReq("https://plex.tv/api/v2/pins").post(ByteArray(0).toRequestBody()).build())
        val j = JSONObject(b.ifBlank { "{}" })
        if (c !in 200..299 || j.optString("code").isBlank()) throw java.io.IOException("Plex $c")
        Code("https://plex.tv/link", j.optString("code"), j.optString("id"), j.optInt("expiresIn", 900))
    }

    suspend fun plexWait(code: Code): Boolean = withContext(Dispatchers.IO) {
        val until = System.currentTimeMillis() + code.expiresIn * 1000L
        while (System.currentTimeMillis() < until) {
            delay(3_000)
            val j = runCatching { JSONObject(call(plexReq("https://plex.tv/api/v2/pins/${code.id}").build()).second) }.getOrNull() ?: continue
            val tok = j.optString("authToken").takeIf { it.isNotBlank() && it != "null" } ?: continue
            val me = runCatching { JSONObject(call(plexReq("https://plex.tv/api/v2/user", tok).build()).second) }.getOrNull()
            put("ms_plex_token" to tok, "ms_plex_user" to (me?.optString("username") ?: ""), "ms_plex_servers" to "")
            return@withContext true
        }
        false
    }

    /** Your Plex servers (name, best address, server token), found once and kept until sign-out. */
    private fun plexServers(): List<Triple<String, String, String>> {
        t("ms_plex_servers").takeIf { it.isNotBlank() }?.let { s ->
            return runCatching { JSONArray(s).let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { Triple(it.getString("n"), it.getString("u"), it.getString("t")) } } } }.getOrDefault(emptyList())
        }
        val tok = t("ms_plex_token").ifBlank { return emptyList() }
        val (_, b) = call(plexReq("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", tok).build())
        val arr = runCatching { JSONArray(b) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Triple<String, String, String>>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (!r.optString("provides").contains("server")) continue
            val conns = r.optJSONArray("connections") ?: continue
            val list = (0 until conns.length()).map { conns.getJSONObject(it) }
            // A local address first (same network as the TV), then remote, relay last.
            val best = list.sortedBy { (if (it.optBoolean("local")) 0 else 1) + (if (it.optBoolean("relay")) 2 else 0) }
                .firstOrNull { c -> call(plexReq(c.optString("uri") + "/identity", r.optString("accessToken").ifBlank { tok }).build()).first in 200..299 } ?: continue
            out += Triple(r.optString("name"), best.optString("uri"), r.optString("accessToken").ifBlank { tok })
        }
        put("ms_plex_servers" to JSONArray(out.map { JSONObject().put("n", it.first).put("u", it.second).put("t", it.third) }).toString())
        return out
    }

    // ---- Jellyfin: Quick Connect ----

    private fun jfAuth(token: String? = null) = "MediaBrowser Client=\"Vision\", Device=\"TV\", DeviceId=\"$clientId\", Version=\"1\"" + (token?.let { ", Token=\"$it\"" } ?: "")
    fun jfAddress() = t("int_jellyfin_0").trim().trimEnd('/').let { if (it.isNotBlank() && !it.startsWith("http")) "http://$it" else it }

    suspend fun jfStart(): Code = withContext(Dispatchers.IO) {
        val base = jfAddress().ifBlank { throw java.io.IOException("no server address") }
        val (c, b) = call(Request.Builder().url("$base/QuickConnect/Initiate").header("Authorization", jfAuth()).post(ByteArray(0).toRequestBody()).build())
        val j = JSONObject(b.ifBlank { "{}" })
        if (c !in 200..299) throw java.io.IOException(if (c == 401 || c == 403) "Quick Connect is off on the server (Dashboard › General)" else "Jellyfin $c")
        Code("$base/web/#/quickconnect", j.optString("Code"), j.optString("Secret"), 600)
    }

    suspend fun jfWait(code: Code): Boolean = withContext(Dispatchers.IO) {
        val base = jfAddress()
        val until = System.currentTimeMillis() + code.expiresIn * 1000L
        while (System.currentTimeMillis() < until) {
            delay(3_000)
            val j = runCatching { JSONObject(call(Request.Builder().url("$base/QuickConnect/Connect?secret=${enc(code.id)}").header("Authorization", jfAuth()).build()).second) }.getOrNull() ?: continue
            if (!j.optBoolean("Authenticated")) continue
            val (c, b) = call(Request.Builder().url("$base/Users/AuthenticateWithQuickConnect").header("Authorization", jfAuth())
                .post(JSONObject().put("Secret", code.id).toString().toRequestBody("application/json".toMediaType())).build())
            val a = runCatching { JSONObject(b) }.getOrNull() ?: return@withContext false
            val tok = a.optString("AccessToken").takeIf { c in 200..299 && it.isNotBlank() } ?: return@withContext false
            put("ms_jf_token" to tok, "ms_jf_uid" to (a.optJSONObject("User")?.optString("Id") ?: ""), "ms_jf_user" to (a.optJSONObject("User")?.optString("Name") ?: ""))
            return@withContext true
        }
        false
    }

    // ---- links ----

    /** Links for [type]/[id] ("tt…" or "tt…:S:E"; "tmdb:…" too) from every signed-in server; empty when none. */
    suspend fun links(type: String, id: String, title: String = ""): List<Link> = withContext(Dispatchers.IO) {
        if (!plexOn() && !jellyfinOn()) return@withContext emptyList()
        val parts = id.split(':')
        val (src, ext) = if (parts[0] == "tmdb") "tmdb" to parts.getOrNull(1).orEmpty() else "imdb" to parts[0]
        val rest = if (src == "tmdb") parts.drop(2) else parts.drop(1)
        val se = if (type == "series" && rest.size >= 2) rest[0].toIntOrNull()?.let { s -> rest[1].toIntOrNull()?.let { s to it } } else null
        if (ext.isBlank() || (type == "series" && se == null)) return@withContext emptyList()
        coroutineScope {
            val jobs = buildList {
                if (plexOn()) plexServers().forEach { srv -> add(async { safe { plexFind(srv, type, src, ext, se, title) } }) }
                if (jellyfinOn()) add(async { safe { jfFind(type, src, ext, se) } })
            }
            jobs.awaitAll().flatten()
        }
    }

    private inline fun safe(f: () -> List<Link>): List<Link> = try { f() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }

    private fun stream(name: String, details: String, url: String, file: String?, bytes: Long?) =
        Stream(name = name, description = details, url = url, behaviorHints = com.lekathedon.visionlite.addons.StreamHints(filename = file, videoSize = bytes))

    private fun plexFind(srv: Triple<String, String, String>, type: String, src: String, ext: String, se: Pair<Int, Int>?, title: String): List<Link> {
        val (name, base, tok) = srv
        val want = "$src://$ext"
        val ptype = if (type == "series") 2 else 1
        val (_, b) = call(plexReq("$base/library/all?type=$ptype&guid=${enc(want)}&includeGuids=1", tok).build())
        var meta = runCatching { JSONObject(b).getJSONObject("MediaContainer").optJSONArray("Metadata") }.getOrNull()
        // Servers that don't filter by an outside id: search the title, keep the item carrying the id.
        fun hasId(m: JSONObject) = m.optJSONArray("Guid")?.let { g -> (0 until g.length()).any { g.getJSONObject(it).optString("id") == want } } == true
        meta = meta?.let { a -> JSONArray((0 until a.length()).map { a.getJSONObject(it) }.filter { hasId(it) }) }
        if ((meta == null || meta.length() == 0) && title.isNotBlank()) {
            val (_, sb) = call(plexReq("$base/search?query=${enc(title.substringBefore(" (").substringBefore(" S0"))}&type=$ptype&includeGuids=1", tok).build())
            meta = runCatching { JSONObject(sb).getJSONObject("MediaContainer").optJSONArray("Metadata") }.getOrNull()
                ?.let { a -> JSONArray((0 until a.length()).map { a.getJSONObject(it) }.filter { hasId(it) }) }
        }
        if (meta == null || meta.length() == 0) return emptyList()
        if (se != null) {
            val show = meta.getJSONObject(0).optString("ratingKey")
            val (_, eb) = call(plexReq("$base/library/metadata/$show/allLeaves", tok).build())
            val eps = runCatching { JSONObject(eb).getJSONObject("MediaContainer").optJSONArray("Metadata") }.getOrNull() ?: return emptyList()
            meta = JSONArray((0 until eps.length()).map { eps.getJSONObject(it) }.filter { it.optInt("parentIndex") == se.first && it.optInt("index") == se.second })
        }
        val out = ArrayList<Link>()
        for (i in 0 until meta.length()) {
            val media = meta.getJSONObject(i).optJSONArray("Media") ?: continue
            for (m in 0 until media.length()) {
                val md = media.getJSONObject(m)
                val part = md.optJSONArray("Part")?.optJSONObject(0) ?: continue
                val file = part.optString("file").substringAfterLast('/').ifBlank { null }
                val url = base + part.optString("key") + "?X-Plex-Token=" + tok
                val res = md.optString("videoResolution").let { if (it.equals("4k", true)) "2160p" else if (it.all(Char::isDigit) && it.isNotBlank()) "${it}p" else it }
                val details = listOfNotNull(res.ifBlank { null }, md.optString("videoCodec").uppercase().ifBlank { null }, md.optString("audioCodec").uppercase().ifBlank { null },
                    part.optLong("size").takeIf { it > 0 }?.let { "%.1f GB".format(it / 1e9) }, file).joinToString(" · ")
                out += Link(PLEX, stream("$PLEX · $name\n$res", details, url, file, part.optLong("size").takeIf { it > 0 }))
            }
        }
        return out
    }

    private fun jfFind(type: String, src: String, ext: String, se: Pair<Int, Int>?): List<Link> {
        val base = jfAddress(); val tok = t("ms_jf_token"); val uid = t("ms_jf_uid")
        fun get(path: String) = runCatching { JSONObject(call(Request.Builder().url(base + path).header("Authorization", jfAuth(tok)).build()).second) }.getOrNull()
        val pid = if (src == "imdb") "imdb.$ext" else "tmdb.$ext"
        val kind = if (type == "series") "Series" else "Movie"
        val found = get("/Items?userId=$uid&Recursive=true&IncludeItemTypes=$kind&AnyProviderIdEquals=${enc(pid)}&Fields=MediaSources,ProviderIds")
            ?.optJSONArray("Items") ?: return emptyList()
        // Older servers ignore AnyProviderIdEquals: keep only the item that really has the id.
        val item = (0 until found.length()).map { found.getJSONObject(it) }.firstOrNull {
            it.optJSONObject("ProviderIds")?.let { p -> p.optString(if (src == "imdb") "Imdb" else "Tmdb") == ext } == true
        } ?: return emptyList()
        val target = if (se == null) item else {
            val eps = get("/Shows/${item.optString("Id")}/Episodes?userId=$uid&season=${se.first}&Fields=MediaSources")?.optJSONArray("Items") ?: return emptyList()
            (0 until eps.length()).map { eps.getJSONObject(it) }.firstOrNull { it.optInt("IndexNumber") == se.second } ?: return emptyList()
        }
        val sources = target.optJSONArray("MediaSources") ?: return emptyList()
        return (0 until sources.length()).map { i ->
            val ms = sources.getJSONObject(i)
            val file = ms.optString("Path").substringAfterLast('/').substringAfterLast('\\').ifBlank { null }
            val v = ms.optJSONArray("MediaStreams")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("Type") == "Video" } }
            val res = v?.optInt("Height")?.takeIf { it > 0 }?.let { h -> if (h >= 2000) "2160p" else "${h}p" } ?: ""
            val size = ms.optLong("Size").takeIf { it > 0 }
            val url = "$base/Videos/${target.optString("Id")}/stream?static=true&mediaSourceId=${enc(ms.optString("Id"))}&api_key=$tok"
            Link(JELLYFIN, stream("$JELLYFIN\n$res", listOfNotNull(res.ifBlank { null }, v?.optString("Codec")?.uppercase(), size?.let { "%.1f GB".format(it / 1e9) }, file).joinToString(" · "), url, file, size))
        }
    }

    // ---- Home row: what was added last ----

    class Added(val id: String, val type: String, val name: String, val poster: String?)

    suspend fun recentlyAdded(): List<Added> = withContext(Dispatchers.IO) {
        val out = ArrayList<Added>()
        if (plexOn()) plexServers().forEach { (_, base, tok) ->
            val (_, b) = call(plexReq("$base/library/recentlyAdded?X-Plex-Container-Size=30&includeGuids=1", tok).build())
            val meta = runCatching { JSONObject(b).getJSONObject("MediaContainer").optJSONArray("Metadata") }.getOrNull() ?: return@forEach
            for (i in 0 until meta.length()) {
                val m = meta.getJSONObject(i)
                val kind = m.optString("type")
                // Episodes and seasons point at their show.
                val guids = m.optJSONArray("Guid")
                val imdb = guids?.let { g -> (0 until g.length()).map { g.getJSONObject(it).optString("id") }.firstOrNull { it.startsWith("imdb://") }?.removePrefix("imdb://") }
                when (kind) {
                    "movie" -> imdb?.let { out += Added(it, "movie", m.optString("title"), null) }
                    "show" -> imdb?.let { out += Added(it, "series", m.optString("title"), null) }
                }
            }
        }
        if (jellyfinOn()) {
            val base = jfAddress(); val tok = t("ms_jf_token"); val uid = t("ms_jf_uid")
            val arr = runCatching { JSONArray(call(Request.Builder().url("$base/Users/$uid/Items/Latest?Limit=30&IncludeItemTypes=Movie,Series&Fields=ProviderIds")
                .header("Authorization", jfAuth(tok)).build()).second) }.getOrNull()
            if (arr != null) for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val imdb = m.optJSONObject("ProviderIds")?.optString("Imdb")?.takeIf { it.startsWith("tt") } ?: continue
                out += Added(imdb, if (m.optString("Type") == "Series") "series" else "movie", m.optString("Name"), null)
            }
        }
        out.distinctBy { it.id }
    }
}
