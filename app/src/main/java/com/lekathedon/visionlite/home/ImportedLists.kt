package com.lekathedon.visionlite.home

import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.addons.MetaPreview
import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.library.backdropOf
import com.lekathedon.visionlite.library.posterOf
import com.lekathedon.visionlite.recs.Recs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
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
        "TRAKT", "TRAKT_LIKED", "MDBLIST" -> true
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
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    private fun meta(imdb: String, type: String, name: String) = MetaPreview(imdb, type, name, posterOf(imdb), backdropOf(imdb))
}
