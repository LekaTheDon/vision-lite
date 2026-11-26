package com.lekathedon.visionlite.addons

import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.data.Net
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** [name]: the release the subtitle was made for (OpenSubtitles' subtitleFileName), to match it to the playing file. */
@Serializable data class Sub(val id: String = "", val url: String = "", val lang: String = "",
                             @SerialName("subtitleFileName") val name: String = "", val from: String = "")
@Serializable internal data class SubsResponse(val subtitles: List<Sub> = emptyList())

/**
 * Subtitles from outside the file, asked when a link list opens (Play was pressed), kept for the player. Only the
 * user's subtitle languages are kept (all, up to 20, when none is set).
 * Sources: add-ons that serve subtitles, plus OpenSubtitles built in (user 2026-11-26, as Haven: the best link often
 * has no subtitle in your language). OpenSubtitles' Stremio service needs no key or account.
 * Cost: one OpenSubtitles request (~20-60 KB) per title played, only with Settings › Subtitles from OpenSubtitles on;
 * the files themselves only when the player opens them.
 */
object Subs {
    const val OPENSUBTITLES = "https://opensubtitles-v3.strem.io"
    private val cache = object : LinkedHashMap<String, List<Sub>>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<Sub>>?) = size > 8
    }
    private val inFlight = HashMap<String, CompletableDeferred<Unit>>()

    fun forId(id: String): List<Sub> = synchronized(cache) { cache[id] }.orEmpty()

    /**
     * Waits (at most [ms]) for this title's subtitles, asking for them if nobody did yet (a resumed link skips the
     * link search), so the player opens with them instead of starting before they land.
     */
    suspend fun await(type: String, id: String, ms: Long = 2_500) {
        if (synchronized(cache) { cache.containsKey(id) }) return
        val running = synchronized(cache) { inFlight[id] }
        withTimeoutOrNull(ms) { if (running != null) running.await() else runCatching { load(type, id) } }
    }

    suspend fun load(type: String, id: String) {
        var mine = false
        val done = synchronized(cache) {
            if (cache.containsKey(id)) return
            inFlight.getOrPut(id) { mine = true; CompletableDeferred() }
        }
        if (!mine) { done.await(); return }
        try {
            val got = fetch(type, id)
            synchronized(cache) { cache[id] = got }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { synchronized(cache) { cache[id] = emptyList() }
        } finally {
            synchronized(cache) { inFlight.remove(id) }
            done.complete(Unit)
        }
    }

    private suspend fun fetch(type: String, id: String): List<Sub> {
        val app = App.instance
        if (app.prefs.subsWhen.value == "off") return emptyList()
        val from = app.addons.addons.value.filter { it.serves("subtitles", type, id) }.map { it.base to it.manifest.name }.toMutableList()
        if (app.prefs.openSubtitles.value && id.startsWith("tt") && from.none { it.first.contains("opensubtitles", true) }) from += OPENSUBTITLES to "OpenSubtitles"
        if (from.isEmpty()) return emptyList()
        val all = coroutineScope {
            from.map { (base, name) ->
                async {
                    withTimeoutOrNull(8_000) {
                        runCatching { Net.getJson<SubsResponse>("$base/subtitles/$type/${AddonStore.enc(id)}.json").subtitles.map { it.copy(from = name) } }.getOrNull()
                    }.orEmpty()
                }
            }.awaitAll().flatten()
        }.filter { it.url.startsWith("http") }.distinctBy { it.url }
        val want = listOf(app.prefs.subLang.value, app.prefs.subLang2.value).filter { it.isNotEmpty() }.map { iso3(it) }
        return if (want.isEmpty()) all.take(20) else want.flatMap { w -> all.filter { iso3(it.lang) == w }.take(6) }
    }

    /**
     * The subtitles in the order the player should prefer them: per language, the one made for the same release as
     * the playing file first (same group / source / cut), so its timing fits without syncing.
     */
    fun ranked(id: String, file: String): List<Sub> {
        val subs = forId(id)
        if (subs.size < 2 || file.isBlank()) return subs
        val mine = words(file)
        val langs = subs.map { iso3(it.lang) }.distinct()
        return subs.sortedWith(compareBy<Sub> { langs.indexOf(iso3(it.lang)) }
            .thenByDescending { if (it.name.isBlank()) 0 else words(it.name).count { w -> w in mine } })
    }

    private val Noise = setOf("mkv", "mp4", "avi", "srt", "the", "and", "of", "a", "x264", "x265", "h264", "h265", "hevc", "avc", "ddp5", "dd5", "aac", "1", "0")
    private fun words(s: String): Set<String> = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 2 && it !in Noise }.toSet()

    /** ISO 639-2/B codes OpenSubtitles uses (fre, ger, pob…) folded into the /T ones Locale gives. */
    private val Bib = mapOf("fre" to "fra", "ger" to "deu", "chi" to "zho", "cze" to "ces", "dut" to "nld", "gre" to "ell", "per" to "fas",
        "rum" to "ron", "slo" to "slk", "alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "geo" to "kat", "ice" to "isl",
        "mac" to "mkd", "mao" to "mri", "may" to "msa", "tib" to "bod", "wel" to "cym", "pob" to "por", "scc" to "srp", "scr" to "hrv", "ze" to "zho")
    fun iso3(code: String): String {
        val c = code.lowercase().substringBefore('-')
        Bib[c]?.let { return it }
        return runCatching { java.util.Locale(c).isO3Language }.getOrNull()?.ifEmpty { null } ?: c
    }
}
