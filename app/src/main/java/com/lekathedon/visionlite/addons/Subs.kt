package com.lekathedon.visionlite.addons

import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.data.Net
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

@Serializable data class Sub(val id: String = "", val url: String = "", val lang: String = "")
@Serializable internal data class SubsResponse(val subtitles: List<Sub> = emptyList())

/**
 * Subtitles from add-ons that serve them (OpenSubtitles etc.), asked when a link list opens (Play was pressed),
 * kept for the player. Only the user's subtitle languages are kept (all, up to 20, when none is set).
 */
object Subs {
    private val cache = object : LinkedHashMap<String, List<Sub>>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<Sub>>?) = size > 8
    }

    fun forId(id: String): List<Sub> = synchronized(cache) { cache[id] }.orEmpty()

    suspend fun load(type: String, id: String) {
        if (synchronized(cache) { cache.containsKey(id) }) return
        val app = App.instance
        if (app.prefs.subsWhen.value == "off") return
        val from = app.addons.addons.value.filter { it.serves("subtitles", type, id) }
        if (from.isEmpty()) return
        val all = coroutineScope {
            from.map { a ->
                async {
                    withTimeoutOrNull(8_000) {
                        runCatching { Net.getJson<SubsResponse>("${a.base}/subtitles/$type/${AddonStore.enc(id)}.json").subtitles }.getOrNull()
                    }.orEmpty()
                }
            }.awaitAll().flatten()
        }.filter { it.url.startsWith("http") }
        val want = listOf(app.prefs.subLang.value, app.prefs.subLang2.value).filter { it.isNotEmpty() }.map { iso3(it) }
        val kept = if (want.isEmpty()) all.take(20)
            else want.flatMap { w -> all.filter { iso3(it.lang) == w }.take(5) }
        synchronized(cache) { cache[id] = kept }
    }

    fun iso3(code: String): String = runCatching { java.util.Locale(code.substringBefore('-')).isO3Language }.getOrNull()?.ifEmpty { null } ?: code.lowercase()
}
