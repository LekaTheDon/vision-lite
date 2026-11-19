package com.lekathedon.visionlite.tb

import android.os.SystemClock
import android.util.Log
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.addons.Stream
import com.lekathedon.visionlite.addons.StreamHints
import com.lekathedon.visionlite.data.Net
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Vision TB in Lite: Vision's sources, matcher and TorBox check (files in this package, copied as they are), run
 * only when the stream list opens and only when the switch is on with a TorBox key. Shows only what TorBox has
 * cached, each row pinned to a file TorBox lists. Movies and shows by IMDb id; the anime sources are left out.
 */
object VisionTb {
    const val NAME = "Vision TB"
    private const val TAG = "VisionTB"
    private const val API = "https://api.torbox.app/v1/api"
    private const val TOTAL_MS = 15_000L
    private const val SOURCE_MS = 8_000L
    private const val CHECK_MS = 6_000L
    private val JSON = "application/json".toMediaType()

    private val client by lazy {
        Net.http.newBuilder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(SOURCE_MS, TimeUnit.MILLISECONDS)
            .callTimeout(SOURCE_MS, TimeUnit.MILLISECONDS).build()
    }

    /** Hashes TorBox said aren't cached, for 60 s (TorBox allows 100 checks a minute). */
    private val notCached = object : LinkedHashMap<String, Long>(256, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Long>?) = size > 4000
    }

    fun isOn(): Boolean = App.instance.prefs.visionTb.value && App.instance.prefs.torboxKey.value.isNotBlank() &&
        com.lekathedon.visionlite.integrations.Integrations.active("torbox")

    /** A row's play data: the hash and the file TorBox listed for it. */
    data class Pick(val hash: String, val fileName: String?)

    /**
     * Streams [onRows] in batches as each source's cached rows are checked; ends within [TOTAL_MS]. Vision's inputs:
     * every title (TbMeta), this episode's title, the anime ids and anime sources, the keyed sources (Jackettio,
     * Meteor) and the torrents your own add-ons returned ([harvest], checked like any other source).
     */
    suspend fun discover(
        type: String, id: String,
        harvest: kotlinx.coroutines.channels.ReceiveChannel<List<DiscoveryCandidate>>? = null,
        onRows: suspend (List<Pair<Stream, Pick>>) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (!isOn()) { harvest?.cancel(); return@withContext }
        val key = App.instance.prefs.torboxKey.value.trim()
        val parts = id.split(':')
        val series = type == "series"
        var imdbAt = parts[0].takeIf { it.startsWith("tt") }
        var seasonAt = parts.getOrNull(1)?.toIntOrNull()
        var episodeAt = parts.getOrNull(2)?.toIntOrNull()
        // Anime ids (kitsu:, mal:, anilist:): ani.zip gives the IMDb id and IMDb-style numbers for the id sources.
        var zipAt: TbMeta.AniZip? = null
        val byAnimeId = imdbAt == null && parts[0].lowercase() in setOf("kitsu", "mal", "anilist")
        if (byAnimeId) {
            val r = withTimeoutOrNull(3_000) { TbMeta.fromAnimeId(id) }
            if (r == null) { harvest?.cancel(); return@withContext }
            zipAt = r.first; imdbAt = r.first.imdb; seasonAt = r.second.first; episodeAt = r.second.second
        }
        val imdb = imdbAt
        val season = seasonAt
        val episode = episodeAt
        val zip = zipAt
        if (imdb == null && !byAnimeId) { harvest?.cancel(); return@withContext }
        if (series && (season == null || episode == null)) { harvest?.cancel(); return@withContext }
        val t0 = SystemClock.elapsedRealtime()
        withTimeoutOrNull(TOTAL_MS) {
            val idTarget = DiscoveryTarget(imdb, series, emptyList(), null, season, episode)
            val claimed = HashSet<String>()
            var budget = DiscoveryPipeline.MAX_CACHE_CHECKS
            suspend fun pass(source: DiscoverySource, rows: List<DiscoveryCandidate>, target: DiscoveryTarget) {
                val out = DiscoveryPipeline.processSource(source, rows, target,
                    claim = { rel -> synchronized(claimed) { rel.filter { claimed.add(it.hash) }.take(budget.coerceAtLeast(0)).also { budget -= it.size } } },
                    check = { hashes, _ -> check(key, hashes) })
                val list = out.verified.shown.map { it.toStream() to Pick(it.release.hash, it.file?.path?.substringAfterLast('/')) }
                if (list.isNotEmpty()) onRows(list)
            }
            coroutineScope {
                val meta = async {
                    imdb?.let { i -> withTimeoutOrNull(4_000) { runCatching { App.instance.client.meta(if (series) "series" else "movie", i) }.getOrNull() } }
                }
                val tmdb = async { imdb?.let { i -> withTimeoutOrNull(4_000) { TbMeta.tmdbTitles(i, series) } } }
                // Every title (details add-on, TMDB's original + alternatives, ani.zip's), the year, this episode's title.
                val full = async {
                    val m = meta.await()
                    val t = tmdb.await()
                    val year = m?.releaseInfo?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
                    val hint = if (series && imdb != null && season != null && episode != null)
                        withTimeoutOrNull(5_000) { TbMeta.episodeHint(imdb, season, episode, TbMeta.namesOf(m)) } else null
                    val titles = (listOfNotNull(m?.name?.takeIf { it.isNotBlank() }) + t?.first.orEmpty() + zip?.titles.orEmpty()).distinct()
                    idTarget.copy(titles = titles, year = year, episodeHint = hint, isAnime = series && (t?.second == true || zip != null))
                }
                // Anime (shows): Kitsu / AniList / AniDB ids, Kitsu's titles, SeaDex's best releases. An IMDb season other
                // than 1 can hold several anime entries (Demon Slayer S2), so only season 1 or an anime id gets the ids.
                val anime = async {
                    val f = full.await()
                    if (!series) return@async f
                    val looksAnime = f.isAnime || meta.await()?.genres.orEmpty().any { it.equals("Animation", true) || it.equals("Anime", true) }
                    val z = zip ?: if (looksAnime && imdb != null) withTimeoutOrNull(3_000) { TbMeta.animeOfImdb(imdb) } else null
                    if (z == null) return@async f
                    if (!byAnimeId && f.season != 1) return@async f.copy(isAnime = true)
                    val refs = AnimeRefs(z.kitsu, if (byAnimeId) parts.getOrNull(2)?.toIntOrNull() ?: f.episode else f.episode, z.anilist, z.anidb)
                    val sea = refs.anilistId?.let { al -> async { withTimeoutOrNull(3_000) { TbMeta.seaDex(al) } } }
                    val kt = refs.kitsuId?.let { k -> withTimeoutOrNull(3_000) { TbMeta.kitsuTitles(k) } }.orEmpty()
                    sea?.await()
                    f.copy(isAnime = true, anime = refs, animeTitles = (kt + z.titles).distinct())
                }
                suspend fun run(req: SourceRequest, quick: Boolean, target: kotlinx.coroutines.Deferred<DiscoveryTarget> = full) {
                    val rows = fetch(req, idTarget) ?: return
                    if (rows.isEmpty()) return
                    // Torrentio files releases by IMDb episode: its rows vouch for this episode (autoplay's trust).
                    if (series && (req.source == DiscoverySource.TORRENTIO || req.source == DiscoverySource.TORRENTIO_REGIONAL))
                        com.lekathedon.visionlite.streams.AutoPick.vouch(id, rows.map { it.hash to (it.fileName ?: it.name) })
                    // Shows: rows naming this exact episode go before the titles arrive; the rest wait for them.
                    if (quick && series && !target.isCompleted) pass(req.source, rows, idTarget)
                    pass(req.source, rows, target.await())
                }
                if (imdb != null) {
                    DiscoveryPipeline.planById(idTarget, includeTorboxSearch = false, includeRegional = true).forEach { launch { run(it, true) } }
                    // Jackettio and Meteor asked with your TorBox key (the hash is read from the link; the link is never kept).
                    DiscoveryPipeline.planKeyed(idTarget, key).forEach { launch { run(it, true) } }
                }
                launch {
                    val f = full.await()
                    (DiscoveryPipeline.planByText(f) + DiscoveryPipeline.planAlternates(f)).forEach { launch { run(it, false) } }
                    val a = anime.await()
                    if (a.isAnime) DiscoveryPipeline.planAnime(a, null).forEach { launch { run(it, false, anime) } }
                }
                // Your add-ons' torrents (any add-on that returns hashes): checked against TorBox like any source.
                harvest?.let { ch -> launch { for (b in ch) if (b.isNotEmpty()) launch { pass(DiscoverySource.ADDONS, b, full.await()) } } }
            }
        }
        harvest?.cancel()
        Log.i(TAG, "VISION_TB_MS total=${SystemClock.elapsedRealtime() - t0}")
    }

    private suspend fun fetch(req: SourceRequest, target: DiscoveryTarget): List<DiscoveryCandidate>? = withTimeoutOrNull(SOURCE_MS) {
        try {
            val b = Request.Builder().url(req.url).header("User-Agent", "VisionLite")
            req.jsonBody?.let { b.post(it.toRequestBody(JSON)) }
            call(b.build()) { r ->
                if (!r.isSuccessful) null
                else {
                    val body = r.body ?: return@call null
                    if (req.source == DiscoverySource.STREMTHRU) DiscoverySources.parseStremThru(body.charStream())
                    else DiscoverySources.parse(req.source, body.string(), target)
                }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "source ${req.source} failed: ${e::class.simpleName}"); null
        }
    }

    /** checkcached with file lists, 100 hashes a call; failed chunks stay Unknown (never shown). */
    private suspend fun check(key: String, hashes: List<String>): Map<String, CacheVerdict> {
        val now = SystemClock.elapsedRealtime()
        val out = HashMap<String, CacheVerdict>()
        synchronized(notCached) { hashes.forEach { h -> notCached[h]?.let { if (now - it < 60_000) out[h] = CacheVerdict.NotCached } } }
        val ask = hashes.filter { it !in out }
        coroutineScope {
            ask.chunked(DiscoveryPipeline.FILES_CHUNK).map { chunk -> async { chunk to askTorbox(key, chunk) } }.awaitAll()
        }.forEach { (chunk, got) ->
            if (got == null) chunk.forEach { out[it] = CacheVerdict.Unknown }
            else {
                out.putAll(got)
                synchronized(notCached) { got.forEach { (h, v) -> if (v is CacheVerdict.NotCached) notCached[h] = now } }
            }
        }
        return out
    }

    private suspend fun askTorbox(key: String, chunk: List<String>): Map<String, CacheVerdict>? = withTimeoutOrNull(CHECK_MS) {
        try {
            val req = Request.Builder().url("$API/torrents/checkcached?format=object&list_files=true")
                .header("Authorization", "Bearer $key")
                .post(JSONObject().put("hashes", JSONArray(chunk)).toString().toRequestBody(JSON)).build()
            call(req) { r -> if (r.isSuccessful) DiscoverySources.parseCheckCached(r.body?.string().orEmpty(), chunk) else { Log.w(TAG, "checkcached HTTP ${r.code}"); null } }
        } catch (e: CancellationException) { throw e } catch (e: IOException) { null }
    }

    /**
     * Play: add the cached torrent to the user's TorBox (only if cached), find the pinned file, ask its link.
     * About 1 s. Null with a reason when TorBox says no.
     */
    suspend fun resolve(pick: Pick): Result<String> {
        val early = synchronized(ready) { ready.remove(pick) }
        if (early != null && SystemClock.elapsedRealtime() - early.first < 30 * 60_000L) early.second.await().let { if (it.isSuccess) return it }
        return resolveNow(pick)
    }

    /**
     * Autoplay's likely pick, asked from TorBox while the last sources still answer, so playing it starts about a
     * second sooner. The caller asks at most 2 a title; an unused one just stays in your TorBox list.
     */
    fun prefetch(pick: Pick) {
        synchronized(ready) {
            if (pick in ready) return
            if (ready.size > 6) ready.keys.firstOrNull()?.let(ready::remove)
            ready[pick] = SystemClock.elapsedRealtime() to bg.async { resolveNow(pick) }
        }
    }

    private val ready = LinkedHashMap<Pick, Pair<Long, kotlinx.coroutines.Deferred<Result<String>>>>()
    private val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    private suspend fun resolveNow(pick: Pick): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val key = App.instance.prefs.torboxKey.value.trim()
            val auth = "Bearer $key"
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("magnet", "magnet:?xt=urn:btih:${pick.hash}")
                .addFormDataPart("add_only_if_cached", "true")
                .addFormDataPart("allow_zip", "false").build()
            val created = json(Request.Builder().url("$API/torrents/createtorrent").header("Authorization", auth).post(form).build())
            val data = created.optJSONObject("data")
            val tid = data?.optInt("torrent_id", 0)?.takeIf { it > 0 } ?: data?.optInt("id", 0)?.takeIf { it > 0 }
                ?: throw IOException(created.optString("detail").ifBlank { "TorBox didn't add it" })
            val item = json(Request.Builder().url("$API/torrents/mylist?id=$tid&bypass_cache=true").header("Authorization", auth).build())
                .optJSONObject("data") ?: throw IOException("TorBox list unreadable")
            val files = item.optJSONArray("files") ?: JSONArray()
            val all = (0 until files.length()).map { files.getJSONObject(it) }
            fun nameOf(f: JSONObject) = f.optString("short_name").ifBlank { f.optString("name").substringAfterLast('/') }
            val file = pick.fileName?.let { want -> all.firstOrNull { nameOf(it).equals(want, true) } }
                ?: all.filter { VIDEO.containsMatchIn(nameOf(it)) }.maxByOrNull { it.optLong("size") }
                ?: throw IOException("No video file in the torrent")
            val link = json(Request.Builder().url("$API/torrents/requestdl?token=$key&torrent_id=$tid&file_id=${file.optInt("id")}").header("Authorization", auth).build())
            link.optString("data").takeIf { it.startsWith("http") } ?: throw IOException(link.optString("detail").ifBlank { "No link from TorBox" })
        }
    }

    private val VIDEO = Regex("\\.(mkv|mp4|m2ts|ts|avi|mov|webm)$", RegexOption.IGNORE_CASE)

    private fun json(req: Request): JSONObject = Net.http.newCall(req).execute().use { r -> JSONObject(r.body?.string().orEmpty().ifBlank { "{}" }) }

    /** execute() ignores cancellation; this closes the socket when the coroutine is cancelled (Vision's fix). */
    private suspend fun <T> call(req: Request, read: (okhttp3.Response) -> T): T = coroutineScope {
        val c = client.newCall(req)
        val guard = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { try { kotlinx.coroutines.awaitCancellation() } finally { c.cancel() } }
        try { kotlinx.coroutines.runInterruptible(Dispatchers.IO) { c.execute().use(read) } } finally { guard.cancel() }
    }

    private fun VerifiedRelease.toStream(): Stream {
        val size = playSize
        val label = quality.label().ifEmpty { "Cached" }
        val file = file?.path?.substringAfterLast('/')
        return Stream(
            name = "$NAME\n$label",
            title = buildString {
                append(release.name)
                if (file != null && file != release.name) append('\n').append(file)
                append('\n')
                size?.let { append("💾 ").append(gb(it)).append(' ') }
                release.seeders?.let { append("👤 ").append(it).append(' ') }
                append("⚙️ ").append(release.sources.joinToString(", ") { it.label })
            },
            infoHash = release.hash,
            behaviorHints = StreamHints(filename = file, videoSize = size)
        )
    }

    private fun gb(b: Long) = if (b >= 1_073_741_824L) String.format(Locale.US, "%.2f GB", b / 1_073_741_824.0) else String.format(Locale.US, "%.0f MB", b / 1_048_576.0)
}
