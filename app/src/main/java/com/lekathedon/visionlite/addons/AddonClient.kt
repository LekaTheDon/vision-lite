package com.lekathedon.visionlite.addons

import com.lekathedon.visionlite.data.Net
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

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

    /** Details from the first add-on that serves meta for this id (installed order). */
    suspend fun meta(type: String, id: String): Meta? {
        val key = "$type/$id"
        synchronized(metaCache) { metaCache[key] }?.let { return it }
        for (a in store.addons.value.filter { it.serves("meta", type, id) }) {
            val m = runCatching { Net.getJson<MetaResponse>("${a.base}/meta/$type/${AddonStore.enc(id)}.json", 12).meta }.getOrNull()
            if (m != null) {
                synchronized(metaCache) { metaCache[key] = m }
                return m
            }
        }
        return null
    }

    data class SourceStreams(val addon: Addon, val streams: List<Stream>, val failed: Boolean)

    /**
     * Streams from every add-on that serves them, asked together; each answer comes in as it lands ([onEach]).
     * Asked only when the user presses Play (nothing searches ahead).
     */
    suspend fun streams(type: String, id: String, timeoutMs: Long = 20_000, onEach: (SourceStreams) -> Unit) = coroutineScope {
        store.addons.value.filter { it.serves("stream", type, id) }.map { a ->
            async {
                val list = withTimeoutOrNull(timeoutMs) {
                    runCatching { Net.getJson<StreamResponse>("${a.base}/stream/$type/${AddonStore.enc(id)}.json").streams }.getOrNull()
                }
                onEach(SourceStreams(a, list.orEmpty(), list == null))
            }
        }.forEach { it.await() }
    }
}
