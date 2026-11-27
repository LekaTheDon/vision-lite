package com.lekathedon.visionlite.addons

import com.lekathedon.visionlite.data.Net
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/** Calls add-ons. Small in-memory caches so going back never refetches. */
class AddonClient(private val store: AddonStore) {
    private val catalogCache = object : LinkedHashMap<String, List<MetaPreview>>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<MetaPreview>>?) = size > 80
    }
    private val metaCache = object : LinkedHashMap<String, Meta>(32, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Meta>?) = size > 40
    }

    fun clearCatalogs() = synchronized(catalogCache) { catalogCache.clear() }

    suspend fun catalog(addon: Addon, def: CatalogDef, skip: Int = 0, search: String? = null, genre: String? = null): List<MetaPreview> {
        val extras = buildList {
            if (search != null) add("search=${AddonStore.enc(search)}")
            if (genre != null) add("genre=${AddonStore.enc(genre)}")
            if (skip > 0) add("skip=$skip")
        }
        val path = "catalog/${def.type}/${AddonStore.enc(def.id)}" + if (extras.isEmpty()) "" else "/" + extras.joinToString("&")
        val url = "${addon.base}/$path.json"
        synchronized(catalogCache) { catalogCache[url] }?.let { return it }
        val metas = Net.getJson<CatalogResponse>(url, timeoutSec = 12).metas
        synchronized(catalogCache) { catalogCache[url] = metas }
        return metas
    }

    /** Details already fetched (title page open before); no network. */
    fun cachedMeta(type: String, id: String): Meta? = synchronized(metaCache) { metaCache["$type/$id"] }

    /**
     * Details from the first add-on that serves meta for this id (installed order); with none (user 2026-11-23: no
     * add-ons needed), the app's own sources: IMDb for tt ids (title, year, genres, every episode), the anime lists for
     * anime ids. Autoplay, Vision TB, next episode and Home all read their show names and episodes from here.
     */
    suspend fun meta(type: String, id: String): Meta? {
        val key = "$type/$id"
        synchronized(metaCache) { metaCache[key] }?.let { return it }
        // Our own data first (user 2026-11-27: add-ons must never touch metadata). Add-ons only for ids our data can't
        // read (an add-on's own ids, e.g. Arabic catalogs), and never an add-on's error dressed up as a title.
        runCatching { nativeMeta(type, id) }.getOrNull()?.let { m ->
            synchronized(metaCache) { metaCache[key] = m }
            return m
        }
        for (a in store.addons.value.filter { it.serves("meta", type, id) }) {
            val m = runCatching { Net.getJson<MetaResponse>("${a.base}/meta/$type/${AddonStore.enc(id)}.json", 12).meta }.getOrNull()
            if (m != null && !isAddonError(m)) {
                synchronized(metaCache) { metaCache[key] = m }
                return m
            }
        }
        return null
    }

    /** AIOStreams & co. answer a failed source with a fake title ("[❌] PenguPlay", id "aiostreamserror…"). */
    private fun isAddonError(m: Meta): Boolean =
        m.id.startsWith("aiostreamserror", true) || m.name.orEmpty().trimStart().startsWith("[❌]") || m.name.isNullOrBlank()

    private suspend fun nativeMeta(type: String, id: String): Meta? {
        if (com.lekathedon.visionlite.detail.AnimeMeta.isAnimeId(id)) return com.lekathedon.visionlite.detail.AnimeMeta.meta(id, type)
        val imdb = id.substringBefore(':')
        val imdbApi = com.lekathedon.visionlite.detail.Imdb
        if (!imdbApi.isId(imdb)) return null
        return coroutineScope {
            val eps = if (type == "series") async { imdbApi.episodes(imdb) } else null
            val t = imdbApi.title(imdb) ?: return@coroutineScope null
            val imdbEps = eps?.await().orEmpty()
            // Seasons IMDb hasn't listed yet, from TMDB (detail/TmdbSeasons.kt), so Play / next episode find them.
            val later = if (type == "series") withContext(Dispatchers.IO) { com.lekathedon.visionlite.detail.TmdbSeasons.beyond(imdb, imdbEps) } else emptyList()
            val videos = imdbEps.map { e ->
                Video(id = "$imdb:${e.season}:${e.episode}", title = e.title, name = e.title, season = e.season, episode = e.episode,
                    released = e.airedMs?.let { java.time.Instant.ofEpochMilli(it).toString() }, thumbnail = e.image, overview = e.plot)
            } + later
            Meta(id = imdb, type = type, name = t.name, poster = t.poster,
                background = com.lekathedon.visionlite.data.Our.backdrop(imdb, "large"), logo = com.lekathedon.visionlite.data.Our.logo(imdb, "medium"),
                description = t.plot, releaseInfo = t.year?.let { y -> t.endYear?.let { "$y-$it" } ?: "$y" },
                imdbRating = t.rating?.toString(), genres = t.genres, runtime = t.runtimeMin?.let { "$it min" },
                cast = t.cast.map { it.name }, videos = videos)
        }
    }

    data class SourceStreams(val addon: Addon, val streams: List<Stream>, val failed: Boolean)

    /**
     * Streams from every add-on that serves them, asked together; each answer comes in as it lands ([onEach]).
     * Asked only when the user presses Play (nothing searches ahead).
     */
    suspend fun streams(type: String, id: String, timeoutMs: Long = 20_000, onEach: (SourceStreams) -> Unit) = coroutineScope {
        // At most a few add-ons answer at once on small boxes (device/Tier.kt): each holds a whole answer in memory.
        val gate = kotlinx.coroutines.sync.Semaphore(com.lekathedon.visionlite.device.Tier.addonFetches)
        store.addons.value.filter { it.serves("stream", type, id) }.map { a ->
            async {
                val list = gate.withPermit { withTimeoutOrNull(timeoutMs) {
                    runCatching { Net.getJson<StreamResponse>("${a.base}/stream/$type/${AddonStore.enc(id)}.json").streams }.getOrNull()
                } }
                onEach(SourceStreams(a, list.orEmpty(), list == null))
            }
        }.forEach { it.await() }
    }
}
