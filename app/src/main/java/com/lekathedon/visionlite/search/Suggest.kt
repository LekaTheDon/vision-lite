package com.lekathedon.visionlite.search

import com.lekathedon.visionlite.addons.MetaPreview
import com.lekathedon.visionlite.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request
import java.net.URLEncoder

/**
 * Search-as-you-type: IMDb's own suggestion feed (the one behind imdb.com's search box). No key, one small request
 * per pause in typing, films and series only. The ids are IMDb tt ids, which every add-on and the title page take.
 */
object Suggest {
    @Serializable private data class Img(val imageUrl: String? = null)
    @Serializable private data class Hit(val id: String = "", val l: String = "", val qid: String? = null, val y: Int? = null, val i: Img? = null)
    @Serializable private data class Res(val d: List<Hit> = emptyList())

    private val cache = object : LinkedHashMap<String, List<MetaPreview>>(32, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<MetaPreview>>) = size > 40
    }

    suspend fun of(term: String): List<MetaPreview> {
        val key = term.trim().lowercase()
        if (key.length < 2) return emptyList()
        synchronized(cache) { cache[key] }?.let { return it }
        val list = withContext(Dispatchers.IO) {
            runCatching {
                val q = URLEncoder.encode(key, "UTF-8").replace("+", "%20")
                val req = Request.Builder().url("https://v3.sg.media-imdb.com/suggestion/x/$q.json").build()
                Net.http.newCall(req).execute().use { r -> if (!r.isSuccessful) null else Net.json.decodeFromString(Res.serializer(), r.body!!.string()) }
            }.getOrNull()?.d.orEmpty()
                .filter { it.id.startsWith("tt") && it.l.isNotBlank() }
                .mapNotNull { h ->
                    val type = when (h.qid) { "movie", "tvMovie" -> "movie"; "tvSeries", "tvMiniSeries" -> "series"; else -> null } ?: return@mapNotNull null
                    // Small poster: IMDb resizes on the fly.
                    val poster = h.i?.imageUrl?.replace("._V1_.jpg", "._V1_UX300_.jpg")
                    MetaPreview(id = h.id, type = type, name = h.l, poster = poster, releaseInfo = h.y?.toString())
                }.take(8)
        }
        synchronized(cache) { cache[key] = list }
        return list
    }
}
