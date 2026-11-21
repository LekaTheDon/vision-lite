package com.lekathedon.visionlite.home

import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.addons.MetaPreview
import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.library.backdropOf
import com.lekathedon.visionlite.library.posterOf
import com.lekathedon.visionlite.recs.Recs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lists added from the phone (Vision's imported lists): each is a Home row "list:<source>.<id>". Trakt lists (yours,
 * liked, anyone's public one), MDBList lists (needs the MDBList key) and IMDb Picks. Loaded only when the row nears the
 * screen, like every row.
 */
object ImportedLists {
    data class L(val key: String, val source: String, val id: String, val name: String)

    fun supports(o: JSONObject): Boolean = when (o.optString("source")) {
        "TRAKT", "TRAKT_LIKED", "MDBLIST", "SIMKL", "ANILIST", "MAL", "IMDB_LIST" -> true
        "IMDB" -> o.optString("remoteId") == "picks"
        else -> false
    }

    fun all(): List<L> = runCatching { JSONArray(App.instance.prefs.importedLists.value) }.getOrDefault(JSONArray()).let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter(::supports).map {
            L("list:" + it.optString("source").lowercase() + "." + it.optString("remoteId"), it.optString("source"), it.optString("remoteId"),
                it.optString("name").ifBlank { "List" })
        }
    }

    suspend fun items(l: L): List<MetaPreview> = withContext(Dispatchers.IO) {
        runCatching {
            when (l.source) {
                "TRAKT", "TRAKT_LIKED" -> App.instance.trakt.listItems(l.id).orEmpty().mapNotNull { i ->
                    val m = i.movie; val s = i.show
                    val imdb = (m?.ids?.imdb ?: s?.ids?.imdb)?.takeIf { it.startsWith("tt") } ?: return@mapNotNull null
                    meta(imdb, if (m != null) "movie" else "series", m?.title ?: s?.title.orEmpty())
                }
                "MDBLIST" -> {
                    val key = App.instance.prefs.sp.getString("int_mdblist_0", "").orEmpty().ifBlank { return@runCatching emptyList() }
                    val body = Net.http.newCall(Request.Builder().url("https://api.mdblist.com/lists/${l.id}/items?apikey=$key").build())
                        .execute().use { it.body?.string().orEmpty() }
                    val j = JSONObject(body)
                    fun part(k: String, type: String) = j.optJSONArray(k)?.let { a ->
                        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { o ->
                            o.optString("imdb_id").takeIf { it.startsWith("tt") }?.let { meta(it, type, o.optString("title")) }
                        }
                    }.orEmpty()
                    part("movies", "movie") + part("shows", "series")
                }
                "IMDB" -> Recs.imdbPicks(30).map { meta(it.imdb, it.type, it.name) }
                "IMDB_LIST" -> imdbList(l.id).second
                "SIMKL" -> simkl(l.id)
                "ANILIST" -> anilist(l.id)
                "MAL" -> mal(l.id)
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    // ---- Catalogs added on the TV by their link (Settings › Home rows › Add a catalog: QR or typed) ----

    /** Adds a list from its web link as a Home row. Returns a message for the screen. */
    suspend fun addLink(link: String, name: String): String = withContext(Dispatchers.IO) {
        val u = link.trim().removeSuffix("/").substringBefore('?')
        val (source, id, guess) = when {
            "trakt.tv/users/" in u && "/lists/" in u -> {
                val user = u.substringAfter("/users/").substringBefore('/'); val slug = u.substringAfter("/lists/").substringBefore('/')
                Triple("TRAKT", "$user/lists/$slug", pretty(slug))
            }
            "mdblist.com/lists/" in u -> {
                val parts = u.substringAfter("/lists/").split('/')
                if (parts.size < 2) return@withContext "That MDBList link has no list in it"
                if (App.instance.prefs.sp.getString("int_mdblist_0", "").isNullOrBlank()) return@withContext "MDBList lists need your MDBList key (Settings › Integrations)"
                Triple("MDBLIST", "${parts[0]}/${parts[1]}", pretty(parts[1]))
            }
            Regex("imdb\\.com/(?:[a-z-]+/)?list/(ls\\d+)").containsMatchIn(u) -> {
                val ls = Regex("ls\\d+").find(u)!!.value
                Triple("IMDB_LIST", ls, runCatching { imdbList(ls).first }.getOrNull() ?: "IMDb list")
            }
            else -> return@withContext "Use a Trakt, MDBList or IMDb list link"
        }
        if (source == "TRAKT" && App.instance.trakt.connected.value == null) return@withContext "Trakt lists need Trakt connected"
        val p = App.instance.prefs
        val cur = runCatching { JSONArray(p.importedLists.value) }.getOrDefault(JSONArray())
        if ((0 until cur.length()).any { cur.optJSONObject(it)?.let { o -> o.optString("source") == source && o.optString("remoteId") == id } == true })
            return@withContext "That list is already a row"
        cur.put(JSONObject().put("source", source).put("remoteId", id).put("name", name.ifBlank { guess }))
        p.importedLists.value = cur.toString()
        "Added \"${name.ifBlank { guess }}\" — it shows on Home (move it with Up/Down)"
    }

    /** Removes a list row (by its row key). */
    fun remove(key: String) {
        val p = App.instance.prefs
        val cur = runCatching { JSONArray(p.importedLists.value) }.getOrDefault(JSONArray())
        val kept = JSONArray()
        (0 until cur.length()).mapNotNull { cur.optJSONObject(it) }.forEach { o ->
            if ("list:" + o.optString("source").lowercase() + "." + o.optString("remoteId") != key) kept.put(o)
        }
        p.importedLists.value = kept.toString()
    }

    private fun pretty(slug: String) = slug.replace('-', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** A public IMDb list (keyless GraphQL): its name and up to 250 titles. */
    private fun imdbList(ls: String): Pair<String?, List<MetaPreview>> {
        val q = "{ list(id: \"$ls\") { name { originalText } titleListItemSearch(first: 250) { edges { node: title { id titleText { text } titleType { canHaveEpisodes } } } } } }"
        val text = Net.http.newCall(Request.Builder().url("https://api.graphql.imdb.com/").header("x-imdb-client-name", "imdb-web-next-localized")
            .header("Accept", "application/json").post(JSONObject().put("query", q).toString().toRequestBody("application/json".toMediaType())).build())
            .execute().use { it.body?.string().orEmpty() }
        val l = JSONObject(text).optJSONObject("data")?.optJSONObject("list") ?: return null to emptyList()
        val edges = l.optJSONObject("titleListItemSearch")?.optJSONArray("edges")
        val items = (0 until (edges?.length() ?: 0)).mapNotNull { edges!!.optJSONObject(it)?.optJSONObject("node") }.mapNotNull { n ->
            val id = n.optString("id").takeIf { it.startsWith("tt") } ?: return@mapNotNull null
            meta(id, if (n.optJSONObject("titleType")?.optBoolean("canHaveEpisodes") == true) "series" else "movie", n.optJSONObject("titleText")?.optString("text").orEmpty())
        }
        return l.optJSONObject("name")?.optString("originalText")?.ifBlank { null } to items
    }

    private fun token(id: String) = App.instance.prefs.sp.getString("si_${id}_token", "").orEmpty()

    private fun get(url: String, vararg headers: Pair<String, String>): String =
        Net.http.newCall(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()).execute().use { it.body?.string().orEmpty() }

    /** Simkl's own statuses (plantowatch, watching…), films, shows and anime; needs Lite's Simkl sign-in. */
    private fun simkl(status: String): List<MetaPreview> {
        val tk = token("simkl").ifBlank { return emptyList() }
        return listOf("movies" to "movie", "shows" to "series", "anime" to "series").flatMap { (kind, type) ->
            val body = runCatching { get("https://api.simkl.com/sync/all-items/$kind/$status", "Authorization" to "Bearer $tk",
                "simkl-api-key" to com.lekathedon.visionlite.BuildConfig.SIMKL_CLIENT_ID) }.getOrDefault("")
            val a = runCatching { JSONObject(body).optJSONArray(kind) }.getOrNull() ?: return@flatMap emptyList()
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { e ->
                val t = e.optJSONObject(if (kind == "movies") "movie" else "show") ?: return@mapNotNull null
                t.optJSONObject("ids")?.optString("imdb")?.takeIf { it.startsWith("tt") }?.let { meta(it, type, t.optString("title")) }
            }
        }
    }

    /** An AniList status list (CURRENT, PLANNING…) or "custom:<name>"; needs Lite's AniList sign-in. */
    private fun anilist(id: String): List<MetaPreview> {
        val tk = token("anilist").ifBlank { return emptyList() }
        val user = com.lekathedon.visionlite.integrations.SignIns.user("anilist").ifBlank { return emptyList() }
        val q = "query (\$u: String) { MediaListCollection(userName: \$u, type: ANIME) { lists { name isCustomList status " +
            "entries { media { id format title { english userPreferred } coverImage { large } bannerImage } } } } }"
        val body = JSONObject().put("query", q).put("variables", JSONObject().put("u", user)).toString()
        val text = Net.http.newCall(Request.Builder().url("https://graphql.anilist.co").header("Authorization", "Bearer $tk")
            .header("Accept", "application/json").post(body.toRequestBody("application/json".toMediaType())).build())
            .execute().use { it.body?.string().orEmpty() }
        val lists = JSONObject(text).optJSONObject("data")?.optJSONObject("MediaListCollection")?.optJSONArray("lists") ?: return emptyList()
        val custom = id.removePrefix("custom:").takeIf { id.startsWith("custom:") }
        return (0 until lists.length()).mapNotNull { lists.optJSONObject(it) }
            .filter { if (custom != null) it.optBoolean("isCustomList") && it.optString("name") == custom else !it.optBoolean("isCustomList") && it.optString("status") == id }
            .flatMap { l -> l.optJSONArray("entries")?.let { e -> (0 until e.length()).mapNotNull { e.optJSONObject(it)?.optJSONObject("media") } }.orEmpty() }
            .mapNotNull { m ->
                val mid = m.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
                val t = m.optJSONObject("title")
                MetaPreview("anilist:$mid", if (m.optString("format") == "MOVIE") "movie" else "series",
                    t?.optString("english")?.takeIf { it.isNotBlank() && it != "null" } ?: t?.optString("userPreferred").orEmpty(),
                    m.optJSONObject("coverImage")?.optString("large")?.takeIf { it.startsWith("http") }, m.optString("bannerImage").takeIf { it.startsWith("http") })
            }.distinctBy { it.id }
    }

    /** A MyAnimeList status list (watching, plan_to_watch…); needs Lite's MyAnimeList sign-in. */
    private fun mal(status: String): List<MetaPreview> {
        val tk = token("mal").ifBlank { return emptyList() }
        val j = JSONObject(get("https://api.myanimelist.net/v2/users/@me/animelist?status=$status&limit=200&fields=media_type",
            "Authorization" to "Bearer $tk"))
        val a = j.optJSONArray("data") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optJSONObject("node") }.mapNotNull { n ->
            val mid = n.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
            MetaPreview("mal:$mid", if (n.optString("media_type") == "movie") "movie" else "series", n.optString("title"),
                n.optJSONObject("main_picture")?.optString("large")?.takeIf { it.startsWith("http") })
        }
    }

    private fun meta(imdb: String, type: String, name: String) = MetaPreview(imdb, type, name, posterOf(imdb), backdropOf(imdb))
}
