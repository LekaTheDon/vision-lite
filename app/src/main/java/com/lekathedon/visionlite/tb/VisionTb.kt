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

    /** Streams [onRows] in batches as each source's cached rows are checked; ends within [TOTAL_MS]. */
    suspend fun discover(type: String, id: String, onRows: suspend (List<Pair<Stream, Pick>>) -> Unit) = withContext(Dispatchers.IO) {
        if (!isOn()) return@withContext
        val key = App.instance.prefs.torboxKey.value.trim()
        val parts = id.split(':')
        val imdb = parts[0].takeIf { it.startsWith("tt") } ?: return@withContext
        val series = type == "series"
        val season = parts.getOrNull(1)?.toIntOrNull()
        val episode = parts.getOrNull(2)?.toIntOrNull()
        if (series && (season == null || episode == null)) return@withContext
        val t0 = SystemClock.elapsedRealtime()
        withTimeoutOrNull(TOTAL_MS) {
            val idTarget = DiscoveryTarget(imdb, series, emptyList(), null, season, episode)
            val claimed = HashSet<String>()
            var budget = DiscoveryPipeline.MAX_CACHE_CHECKS
            var shown = 0
            suspend fun pass(source: DiscoverySource, rows: List<DiscoveryCandidate>, target: DiscoveryTarget) {
                val out = DiscoveryPipeline.processSource(source, rows, target,
                    claim = { rel -> synchronized(claimed) { rel.filter { claimed.add(it.hash) }.take(budget.coerceAtLeast(0)).also { budget -= it.size } } },
                    check = { hashes, _ -> check(key, hashes) })
                val list = out.verified.shown.map { it.toStream() to Pick(it.release.hash, it.file?.path?.substringAfterLast('/')) }
                if (list.isNotEmpty()) { synchronized(claimed) { shown += list.size }; onRows(list) }
            }
            coroutineScope {
                // Titles + year from the details add-on (Cinemeta normally; already in memory from the details page).
                val full = async {
                    val m = withTimeoutOrNull(4_000) { runCatching { App.instance.client.meta(if (series) "series" else "movie", imdb) }.getOrNull() }
                    val year = m?.releaseInfo?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
                    idTarget.copy(titles = listOfNotNull(m?.name?.takeIf { it.isNotBlank() }), year = year)
                }
                suspend fun run(req: SourceRequest, quick: Boolean) {
                    val rows = fetch(req, idTarget) ?: return
                    if (rows.isEmpty()) return
                    // Torrentio files releases by IMDb episode: its rows vouch for this episode (autoplay's trust).
                    if (series && (req.source == DiscoverySource.TORRENTIO || req.source == DiscoverySource.TORRENTIO_REGIONAL))
                        com.lekathedon.visionlite.streams.AutoPick.vouch(id, rows.map { it.hash to (it.fileName ?: it.name) })
                    // Shows: rows naming this exact episode go before the titles arrive; the rest wait for them.
                    if (quick && series && !full.isCompleted) pass(req.source, rows, idTarget)
                    pass(req.source, rows, full.await())
                }
                DiscoveryPipeline.planById(idTarget, includeTorboxSearch = false, includeRegional = true).forEach { launch { run(it, true) } }
                launch { DiscoveryPipeline.planByText(full.await()).forEach { launch { run(it, false) } } }
            }
        }
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
    suspend fun resolve(pick: Pick): Result<String> = withContext(Dispatchers.IO) {
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
