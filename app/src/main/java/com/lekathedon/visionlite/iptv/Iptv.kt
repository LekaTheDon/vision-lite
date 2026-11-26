package com.lekathedon.visionlite.iptv

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

// Models the parsers (from Vision) hand over.
data class CatchupInfo(val type: String, val source: String?, val days: Int, val correctionHours: Double = 0.0)
data class DrmInfo(val scheme: String, val license: String, val licenseHeaders: Map<String, String> = emptyMap(), val manifestType: String? = null)
data class EpgProgram(
    val startMs: Long, val stopMs: Long, val title: String, val description: String? = null, val category: String? = null,
    val episode: String? = null, val icon: String? = null, val year: Int? = null, val people: List<String> = emptyList()
) {
    fun isLive(nowMs: Long): Boolean = nowMs in startMs until stopMs
    fun progress(nowMs: Long): Float = ((nowMs - startMs).toFloat() / (stopMs - startMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
}

/** A playlist: an M3U link, or an Xtream login (server + username + password). [epg] overrides the guide link. */
@Serializable data class Playlist(val id: String, val name: String, val url: String = "", val server: String = "",
                                  val user: String = "", val pass: String = "", val epg: String = "") {
    val xtream get() = server.isNotBlank()
}

data class Channel(val pl: String, val n: Int, val name: String, val group: String, val logo: String?, val tvg: String?,
                   val url: String, val headers: Map<String, String>, val catchup: CatchupInfo? = null) {
    val key get() = "$pl|$url"
}

/**
 * Live TV (IPTV). On demand only: nothing loads at app start; opening Live TV loads a playlist that is new or a day
 * old, and Refresh reloads. Channels and the guide (now −2 h to +24 h) live in a small SQLite file, so a 50,000
 * channel list or a 200 MB guide never sits in memory; rows ask for their own "now / next" as they are drawn.
 */
object Iptv {
    private const val TAG = "Iptv"
    /** Due after the chosen interval, less 4 h so the 05:00 run always counts (TiviMate: "Update interval, hours"). */
    internal fun staleMs(): Long = ((p.iptvUpdateHours.value.toLongOrNull() ?: 24L) * 3600_000L - 4 * 3600_000L).coerceAtLeast(3600_000L)
    private val p get() = App.instance.prefs
    private val lock = Mutex()
    private val http by lazy { Net.http.newBuilder().readTimeout(60, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES).build() }

    /** What is happening, for the screen ("" = idle). */
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> get() = _status
    /** Bumps when channels, the guide, playlists or favourites change. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> get() = _version

    // ---- playlists and favourites (prefs) ----

    private val plSer = ListSerializer(Playlist.serializer())
    fun playlists(): List<Playlist> = runCatching { Net.json.decodeFromString(plSer, p.iptvPlaylists.value) }.getOrDefault(emptyList())
    private fun savePlaylists(l: List<Playlist>) { p.iptvPlaylists.value = Net.json.encodeToString(plSer, l); _version.value++ }

    fun add(pl: Playlist) = savePlaylists(playlists().filter { it.id != pl.id } + pl)
    fun remove(id: String) {
        savePlaylists(playlists().filter { it.id != id })
        Vod.removePlaylist(id)
        db.writableDatabase.apply { delete("ch", "pl=?", arrayOf(id)); delete("meta", "k=?", arrayOf("loaded:$id")) }
        _version.value++
    }

    fun favourites(): Set<String> = p.iptvFavs.value.split('\n').filter { it.isNotBlank() }.toSet()
    fun isFavourite(c: Channel) = c.key in favourites()
    fun toggleFavourite(c: Channel) {
        val f = favourites()
        p.iptvFavs.value = (if (c.key in f) f - c.key else f + c.key).joinToString("\n"); _version.value++
    }

    // ---- loading ----

    /** Loads playlists never loaded or older than a day (called when Live TV opens). */
    /** Live TV or Sports was opened this session: Sports may then run in the background for reminders and alerts. */
    val openedThisSession = kotlinx.coroutines.flow.MutableStateFlow(false)

    suspend fun ensureLoaded() {
        openedThisSession.value = true
        val now = System.currentTimeMillis()
        playlists().filter { now - loadedAt(it.id) > staleMs() }.forEach { refresh(it) }
    }

    suspend fun refreshAll() = playlists().forEach { refresh(it, force = true) }
    fun neverLoaded() = playlists().any { loadedAt(it.id) == 0L }

    /**
     * One playlist: channels, then the guide. Like TiviMate, an update changes only what changed: the same channel list
     * isn't rewritten, and a guide the server says is unchanged (ETag / Last-Modified / size) isn't downloaded again,
     * only its old shows are trimmed. Old data stays on screen throughout (one transaction each, WAL).
     */
    suspend fun refresh(pl: Playlist, force: Boolean = false): Unit = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                val first = loadedAt(pl.id) == 0L
                _status.value = if (first) "Downloading ${pl.name} (first time only)…" else "Updating ${pl.name}…"
                val (channels, guides) = if (pl.xtream) xtream(pl) else m3u(pl)
                val sig = channels.joinToString("\n") { "${it.n}|${it.name}|${it.group}|${it.url}|${it.logo}|${it.tvg}|${it.catchup}" }.hashCode().toString() + ":" + channels.size
                val old = metaGet("chsig:${pl.id}")
                // No fingerprint yet (first update after 0.34.1): write the list, but don't count it as a change.
                val changed = force || first || (old != null && old != sig)
                if (changed || old == null) { writeChannels(pl.id, channels); meta("chsig:${pl.id}", sig); _version.value++ }
                val guide = pl.epg.ifBlank { guides.firstOrNull().orEmpty() }
                if (guide.isNotBlank()) {
                    if (first) _status.value = "Downloading the guide for ${pl.name} (first time only)…"
                    loadGuide(pl.id, guide, mustParse = changed)
                }
                meta("loaded:${pl.id}", System.currentTimeMillis().toString())
                _status.value = ""
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "${pl.name}: ${e.message}")
                _status.value = "${pl.name} didn't load: ${e.message ?: e.javaClass.simpleName}"
            }
            _version.value++
        }
    }

    internal fun openStream(url: String): InputStream = open(url)
    internal fun xtreamBase(pl: Playlist) = pl.server.trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
    internal fun encode(s: String) = enc(s)

    private fun open(url: String, ua: String? = null): InputStream {
        val r = http.newCall(Request.Builder().url(url).header("User-Agent", ua ?: "VisionLite").build()).execute()
        if (!r.isSuccessful) { r.close(); throw java.io.IOException("HTTP ${r.code}") }
        val body = r.body!!.byteStream()
        val buffered = java.io.BufferedInputStream(body)
        buffered.mark(2)
        val gz = buffered.read() == 0x1f && buffered.read() == 0x8b
        buffered.reset()
        return if (gz || url.endsWith(".gz")) GZIPInputStream(buffered) else buffered
    }

    private fun m3u(pl: Playlist): Pair<List<Channel>, List<String>> {
        val parsed = open(pl.url).bufferedReader().use { M3uParser.parse(it) }
        // Films and episodes in the list go to VOD, not to the channels.
        val (vod, live) = parsed.entries.partition { Vod.looksVod(it.url) }
        Vod.fromM3u(pl.id, vod)
        val list = live.mapIndexed { i, e ->
            Channel(pl.id, e.chno ?: (i + 1), e.name, e.group?.takeIf { it.isNotBlank() } ?: "Other", e.logo, e.tvgId ?: e.tvgName, e.url, e.headers, e.catchup)
        }
        return list to parsed.epgUrls
    }

    /** Xtream's own API (like TiviMate): categories + live streams; the guide from xmltv.php. */
    private fun xtream(pl: Playlist): Pair<List<Channel>, List<String>> {
        val base = pl.server.trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
        val auth = "username=${enc(pl.user)}&password=${enc(pl.pass)}"
        val api = "$base/player_api.php?$auth"
        val cats = JSONArray(open("$api&action=get_live_categories").bufferedReader().use { it.readText() })
        val names = (0 until cats.length()).associate { cats.getJSONObject(it).let { c -> c.optString("category_id") to c.optString("category_name") } }
        val streams = JSONArray(open("$api&action=get_live_streams").bufferedReader().use { it.readText() })
        val list = (0 until streams.length()).mapNotNull { i ->
            val s = streams.optJSONObject(i) ?: return@mapNotNull null
            val id = s.optString("stream_id").ifBlank { return@mapNotNull null }
            Channel(pl.id, s.optInt("num", i + 1), s.optString("name"), names[s.optString("category_id")] ?: "Other",
                s.optString("stream_icon").takeIf { it.startsWith("http") }, s.optString("epg_channel_id").takeIf { it.isNotBlank() && it != "null" },
                "$base/live/${enc(pl.user)}/${enc(pl.pass)}/$id.ts", emptyMap(),
                if (s.optString("tv_archive") == "1") CatchupInfo("xc", null, s.optString("tv_archive_duration").toIntOrNull()?.takeIf { it > 0 } ?: 1) else null)
        }
        return list to listOf("$base/xmltv.php?$auth")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun writeChannels(pl: String, list: List<Channel>) {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            w.delete("ch", "pl=?", arrayOf(pl))
            val st = w.compileStatement("INSERT OR REPLACE INTO ch(pl,n,ord,name,grp,logo,tvg,url,hdr,cu_type,cu_src,cu_days,cu_corr) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")
            list.forEachIndexed { i, c ->
                st.clearBindings()
                st.bindString(1, pl); st.bindLong(2, i.toLong()); st.bindLong(3, c.n.toLong()); st.bindString(4, c.name); st.bindString(5, c.group)
                c.logo?.let { st.bindString(6, it) }; c.tvg?.let { st.bindString(7, it) }; st.bindString(8, c.url)
                if (c.headers.isNotEmpty()) st.bindString(9, JSONObject(c.headers).toString())
                c.catchup?.let { cu -> st.bindString(10, cu.type); cu.source?.let { st.bindString(11, it) }; st.bindLong(12, cu.days.toLong()); st.bindDouble(13, cu.correctionHours) }
                st.executeInsert()
            }
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
    }

    /** Streams the guide straight into the database, only the channels this playlist has, now −24 h (catch-up) to +24 h. */
    /** The guide keeps 3 days (user 2026-11-24): yesterday, today and tomorrow, from local midnight to midnight. */
    private fun dayStart(offset: Int): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        add(java.util.Calendar.DAY_OF_MONTH, offset)
    }.timeInMillis

    private fun loadGuide(pl: String, url: String, mustParse: Boolean) {
        val now = System.currentTimeMillis()
        val keepFrom = dayStart(-1)
        // Asked with what the server said last time; "not modified" (or the same ETag / date / size) = keep ours.
        val tag = "epg:$pl:" + url.hashCode()
        val req = Request.Builder().url(url).header("User-Agent", "VisionLite").apply {
            if (!mustParse) {
                metaGet("$tag:etag")?.let { header("If-None-Match", it) }
                metaGet("$tag:lm")?.let { header("If-Modified-Since", it) }
            }
        }.build()
        val resp = http.newCall(req).execute()
        val etag = resp.header("ETag"); val lm = resp.header("Last-Modified"); val size = resp.body?.contentLength()?.takeIf { it > 0 }?.toString()
        val same = resp.code == 304 || (!mustParse && resp.isSuccessful && (
            (etag != null && etag == metaGet("$tag:etag")) || (lm != null && lm == metaGet("$tag:lm")) ||
                (etag == null && lm == null && size != null && size == metaGet("$tag:size"))))
        if (same) {
            resp.close()
            val w = db.writableDatabase
            w.beginTransaction()
            try {
                w.execSQL("DELETE FROM p2 WHERE stop<? AND g IN (SELECT id FROM gch WHERE pl=?)", arrayOf<Any>(keepFrom, pl))
                dropUnusedText(w); w.setTransactionSuccessful()
            } finally { w.endTransaction() }
            Log.i(TAG, "guide unchanged on the server: kept, old shows trimmed")
            return
        }
        if (!resp.isSuccessful) { resp.close(); throw java.io.IOException("guide HTTP ${resp.code}") }
        resp.use { r0 ->
            val body = java.io.BufferedInputStream(r0.body!!.byteStream())
            body.mark(2); val gz = body.read() == 0x1f && body.read() == 0x8b; body.reset()
            parseGuide(pl, if (gz || url.endsWith(".gz")) GZIPInputStream(body) else body, keepFrom, now)
        }
        etag?.let { meta("$tag:etag", it) }; lm?.let { meta("$tag:lm", it) }; size?.let { meta("$tag:size", it) }
    }

    private fun parseGuide(pl: String, input: InputStream, keepFrom: Long, now: Long) {
        val r = db.readableDatabase
        val ids = HashSet<String>(); val byName = HashMap<String, MutableList<String>>()
        r.rawQuery("SELECT tvg, name, n FROM ch WHERE pl=?", arrayOf(pl)).use { c ->
            while (c.moveToNext()) {
                c.getString(0)?.let { ids += it }
                byName.getOrPut(norm(c.getString(1))) { ArrayList() } += c.getLong(2).toString()
            }
        }
        val w = db.writableDatabase
        val named = HashMap<String, String>()   // guide id → our channel rows without a tvg id
        w.beginTransaction()
        try {
            w.execSQL("DELETE FROM p2 WHERE g IN (SELECT id FROM gch WHERE pl=?)", arrayOf(pl))
            w.delete("gch", "pl=?", arrayOf(pl))
            dropUnusedText(w)
            // Each title / description / category is stored once (a news bulletin airs 20 times a day); shows point to it.
            val texts = HashMap<Long, Long>()
            w.rawQuery("SELECT id, s FROM txt", null).use { c -> while (c.moveToNext()) texts[hash64(c.getString(1))] = c.getLong(0) }
            val addTxt = w.compileStatement("INSERT INTO txt(s) VALUES(?)")
            fun text(s: String?): Long? = s?.takeIf { it.isNotBlank() }?.let { t ->
                val h = hash64(t); texts[h] ?: run { addTxt.clearBindings(); addTxt.bindString(1, t); addTxt.executeInsert().also { texts[h] = it } }
            }
            val addCh = w.compileStatement("INSERT INTO gch(pl,tvg) VALUES(?,?)")
            val st = w.compileStatement("INSERT OR IGNORE INTO p2(g,start,stop,t,d,c) VALUES(?,?,?,?,?,?)")
            var count = 0
            input.use { input ->
                XmltvParser.parse(input, keepFrom, dayStart(2), keepUnlisted = { it in ids },
                    onPrograms = { id, progs ->
                        addCh.clearBindings(); addCh.bindString(1, pl); addCh.bindString(2, id)
                        val g = addCh.executeInsert()
                        progs.forEach { pr ->
                            st.clearBindings(); st.bindLong(1, g); st.bindLong(2, pr.startMs); st.bindLong(3, pr.stopMs)
                            st.bindLong(4, text(pr.title) ?: text("…")!!); text(pr.description)?.let { st.bindLong(5, it) }; text(pr.category)?.let { st.bindLong(6, it) }
                            st.executeInsert(); count++
                        }
                    }) { channels ->
                    channels.values.forEach { ch -> if (ch.id !in ids) ch.displayNames.firstOrNull { norm(it) in byName }?.let { named[ch.id] = norm(it) } }
                    ids + named.keys
                }
            }
            // Channels matched by name take the guide's id.
            named.forEach { (id, name) ->
                byName[name]?.forEach { n -> w.execSQL("UPDATE ch SET tvg=? WHERE pl=? AND n=? AND tvg IS NULL", arrayOf(id, pl, n)) }
            }
            val copies = shareGuideWithCopies(w, pl)
            w.insertWithOnConflict("meta", null, ContentValues().apply { put("k", "guide_rev"); put("v", System.currentTimeMillis().toString()) }, SQLiteDatabase.CONFLICT_REPLACE)
            w.setTransactionSuccessful()
            Log.i(TAG, "guide: $count shows, $copies channel copies took a sibling's guide")
        } finally { w.endTransaction() }
    }

    /** Texts no show points to any more. */
    private fun dropUnusedText(w: SQLiteDatabase) = w.execSQL(
        "DELETE FROM txt WHERE id NOT IN (SELECT t FROM p2 UNION SELECT d FROM p2 WHERE d IS NOT NULL UNION SELECT c FROM p2 WHERE c IS NOT NULL)")

    /** 64-bit FNV-1a: guide texts deduplicated without holding the strings in memory. */
    private fun hash64(s: String): Long {
        var h = -0x340d631b7bdddcdbL
        for (ch in s) { h = (h xor ch.code.toLong()) * 0x100000001b3L }
        return h xor s.length.toLong()
    }

    /** Opens iptv.db off the main thread at app start, so the one-time move to the slim guide (0.34.1) never blocks a page. */
    fun warmDb() { db.readableDatabase; if (metaGet("vacuum") == "1") { db.writableDatabase.execSQL("VACUUM"); meta("vacuum", "0") } }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")

    /**
     * Haven's "channel copies": a 4K / UHD / RAW / HEVC copy often has no guide id of its own (here "4K: SKY SPORTS
     * MAIN EVENTS ᵁᴴᴰ ³⁸⁴⁰ᴾ" next to "VIP: SKY SPORTS MAIN EVENT ᴴᴰ" with skysportsmainevent.uk). A copy is the same
     * name once the provider prefix ("4K:", "NOW:", "VIP:"), quality words and plural s are gone. A copy without shows
     * takes a sibling's guide only when that guide id spells the same channel and its country (".uk") is the channel's
     * own (from its "UK:" prefix or its group's); the id most copies use wins. "+1" channels stay apart. Live TV and
     * Sports then see the match on the 4K copy too (Sports already lists 4K first). Returns how many took a guide.
     */
    private fun shareGuideWithCopies(w: SQLiteDatabase, pl: String): Int {
        val shows = HashMap<String, Int>()
        w.rawQuery("SELECT tvg, COUNT(*) FROM prog WHERE pl=? GROUP BY tvg", arrayOf(pl)).use { c -> while (c.moveToNext()) shows[c.getString(0)] = c.getInt(1) }
        if (shows.isEmpty()) return 0
        val fams = HashMap<String, HashMap<String, IntArray>>()   // family → guide id → [copies using it, shows]
        val bare = ArrayList<Triple<Long, String, String?>>()        // (row n, family, country) without shows
        w.rawQuery("SELECT n, name, grp, tvg FROM ch WHERE pl=?", arrayOf(pl)).use { c ->
            while (c.moveToNext()) {
                val (fam, country) = copyOf(c.getString(1), c.getString(2)) ?: continue
                val tvg = c.getString(3); val count = tvg?.let { shows[it] } ?: 0
                if (count > 0) fams.getOrPut(fam) { HashMap() }.getOrPut(tvg!!) { intArrayOf(0, count) }[0]++
                else bare += Triple(c.getLong(0), fam, country)
            }
        }
        var n = 0
        for ((row, fam, country) in bare) {
            val id = fams[fam]?.entries?.filter { (id, _) ->
                val dot = id.lastIndexOf('.')
                val base = if (dot > 0) id.substring(0, dot) else id
                val suffix = if (dot > 0) id.substring(dot + 1).lowercase().let { if (it == "gb") "uk" else it }.takeIf { it.length == 2 && it.all(Char::isLetter) } else null
                squash(base) == squash(fam) && (country == null || suffix == null || suffix == country)
            }?.maxByOrNull { it.value[0].toLong() * 1_000_000 + it.value[1] }?.key ?: continue
            w.execSQL("UPDATE ch SET tvg=? WHERE pl=? AND n=?", arrayOf<Any>(id, pl, row)); n++
        }
        return n
    }

    private val QUALITY_WORDS = setOf("uhd", "4k", "8k", "2160p", "3840p", "2160", "3840", "fhd", "hd", "sd", "hq", "hevc", "h265", "h264", "x265",
        "1080p", "1080", "1080i", "720p", "720", "raw", "backup", "bk", "alt", "vip", "multi", "fps", "50fps", "60fps", "hdr", "hdr10", "dv")
    private val COUNTRIES = ("uk us ca it es fr de pt br ar mx nl be pl ro rs hr ba si me tr gr cy al mk bg hu cz sk at ch se no dk fi ie au nz za " +
        "ng in pk sa ae qa kw om bh eg ma dz tn jo lb iq ir il ru ua by kz cn jp kr ph id my th vn co cl pe ve ec uy py bo").split(' ').toSet()
    private val PREFIX = Regex("^[^\\p{L}\\p{N}]*([\\p{L}\\p{N}+ ]{1,6}?)\\s*[:|]\\s*")
    private fun nfkc(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC).lowercase()
    private fun country(word: String?) = word?.trim()?.let { if (it == "gb") "uk" else it }?.takeIf { it in COUNTRIES }
    private fun squash(s: String) = nfkc(s).replace(Regex("[^a-z0-9]"), "").replace("s", "")

    /** (family, country): the channel without provider prefix, quality words and plural s; null when nothing is left. */
    internal fun copyOf(name: String, group: String?): Pair<String, String?>? {
        var t = nfkc(name)
        val m = PREFIX.find(t)
        val pre = m?.groupValues?.get(1)
        if (m != null) t = t.substring(m.range.last + 1)
        val cc = country(pre) ?: group?.let { g -> PREFIX.find(nfkc(g))?.groupValues?.get(1) }.let(::country)
        t = t.replace(Regex("\\+\\s?(\\d)\\b"), " plus$1 ")
        val words = t.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() && it !in QUALITY_WORDS }
            .map { if (it.length > 3 && it.endsWith("s")) it.dropLast(1) else it }
        return words.joinToString(" ").takeIf { it.length >= 3 }?.let { it to cc }
    }

    // ---- reading ----

    /** Groups across all playlists, in playlist order, with channel counts. */
    fun groups(): List<Pair<String, Int>> = Groups.arrange("live", allGroups(), { it.first })
    /** Every group, hidden ones too, in playlist order (Arrange / hide). */
    fun allGroups(): List<Pair<String, Int>> {
        val v = _version.value
        groupCache?.takeIf { it.first == v }?.let { return it.second }
        return db.readableDatabase.rawQuery("SELECT grp, COUNT(*) FROM ch GROUP BY grp ORDER BY MIN(rowid)", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) }
        }.also { groupCache = v to it }
    }
    fun groupsCached(): List<Pair<String, Int>> = groupCache?.second.orEmpty().let { Groups.arrange("live", it, { g -> g.first }) }
    @Volatile private var groupCache: Pair<Int, List<Pair<String, Int>>>? = null

    /** [group] null = all; "★" = favourites. */
    /** The last list read per group (memory only): Live TV opens on it at once, then reads it fresh. */
    private val listCache = java.util.concurrent.ConcurrentHashMap<String, List<Channel>>()
    fun channelsCached(group: String?): List<Channel> = listCache[group ?: ""].orEmpty()
    fun channels(group: String?): List<Channel> = channelsRead(group).also { if (it.size <= 5000) listCache[group ?: ""] = it }
    private fun channelsRead(group: String?): List<Channel> {
        val favs = if (group == "★") favourites() else null
        val (where, args) = when {
            group == null || favs != null || group == RECENT -> "" to null
            else -> "WHERE grp=?" to arrayOf(group)
        }
        val recent = if (group == RECENT) recents() else null
        if (recent != null) return channelsByKey(recent)
        val all = db.readableDatabase.rawQuery("SELECT $COLS FROM ch $where ORDER BY rowid", args).use { c ->
            buildList { while (c.moveToNext()) add(readChannel(c)) }
        }
        if (favs != null) return all.filter { it.key in favs }
        // Settings › Live TV › Hide +1 channels (off by default): the hour-later copies leave the lists, never favourites.
        if (p.iptvHidePlus1.value) return hideGroups(group, all).filter { !ChannelVersions.isPlusOne(it.name) }
        return hideGroups(group, all)
    }
    private fun hideGroups(group: String?, all: List<Channel>): List<Channel> {
        // All channels leaves out the groups you hid (Groups.kt).
        if (group == null) { val hid = Groups.hidden("live"); if (hid.isNotEmpty()) return all.filter { it.group !in hid } }
        return all
    }

    /** What is on now and next on a channel. */
    fun nowNext(c: Channel, now: Long = System.currentTimeMillis()): List<EpgProgram> {
        val tvg = c.tvg ?: return emptyList()
        return db.readableDatabase.rawQuery("SELECT start,stop,title,descr FROM prog WHERE pl=? AND tvg=? AND stop>? ORDER BY start LIMIT 2",
            arrayOf(c.pl, tvg, now.toString())).use { q ->
            buildList { while (q.moveToNext()) add(EpgProgram(q.getLong(0), q.getLong(1), q.getString(2), q.getString(3))) }
        }
    }

    private const val COLS = "pl,ord,name,grp,logo,tvg,url,hdr,cu_type,cu_src,cu_days,cu_corr"
    private fun readChannel(c: android.database.Cursor) = Channel(c.getString(0), c.getInt(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6),
        c.getString(7)?.let { h -> runCatching { JSONObject(h).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } }.getOrNull() } ?: emptyMap(),
        c.getString(8)?.let { CatchupInfo(it, c.getString(9), c.getInt(10), c.getDouble(11)) })

    /** An Xtream channel's key from its playlist id and stream number (the phone browses by stream number). */
    fun keyForStream(playlist: String, stream: String): String? {
        if (!stream.all { it.isDigit() }) return null
        return db.readableDatabase.rawQuery("SELECT pl||'|'||url FROM ch WHERE pl=? AND (url LIKE ? OR url LIKE ?) LIMIT 1",
            arrayOf(playlist, "%/$stream.ts", "%/$stream.m3u8")).use { if (it.moveToFirst()) it.getString(0) else null }
    }

    /** Channels by key ("playlist|url"), in the order given. */
    fun channelsByKey(keys: List<String>): List<Channel> {
        if (keys.isEmpty()) return emptyList()
        val found = HashMap<String, Channel>()
        keys.chunked(400).forEach { part ->
            db.readableDatabase.rawQuery("SELECT $COLS FROM ch WHERE pl||'|'||url IN (${part.joinToString(",") { "?" }})", part.toTypedArray()).use { c ->
                while (c.moveToNext()) readChannel(c).let { found[it.key] = it }
            }
        }
        return keys.mapNotNull { found[it] }
    }

    // ---- watching: recents, channel up / down, catch-up ----

    const val RECENT = "⟲"
    fun recents(): List<String> = p.iptvRecent.value.split('\n').filter { it.isNotBlank() }
    /** The list Up / Down steps through in the player (the group it was started from). */
    @Volatile var zapList: List<Channel> = emptyList()

    fun played(c: Channel) {
        p.iptvRecent.value = (listOf(c.key) + recents().filter { it != c.key }).take(30).joinToString("\n")
    }

    /** The channel [step] away from the one at [url] in [zapList] (wraps round). */
    fun zap(url: String, step: Int): Channel? {
        val l = zapList.ifEmpty { return null }
        val i = l.indexOfFirst { it.url == url }.coerceAtLeast(0)
        return l[Math.floorMod(i + step, l.size)]
    }

    /** Typed channel numbers in the live player: the zap list first, then every playlist. */
    fun byNumber(n: Int): Channel? = zapList.firstOrNull { it.n == n }
        ?: db.readableDatabase.rawQuery("SELECT $COLS FROM ch WHERE ord=? ORDER BY rowid LIMIT 1", arrayOf(n.toString())).use { if (it.moveToFirst()) readChannel(it) else null }

    /** The channel played before this one (Left in the live player). */
    fun previous(current: String): Channel? = recents().firstOrNull { it.substringAfter('|') != current }?.let { channelsByKey(listOf(it)).firstOrNull() }

    /** Start over: the running show from its start, out of the channel's archive (catch-up channels only). */
    fun startOverUrl(c: Channel, pr: EpgProgram, now: Long = System.currentTimeMillis()): String? {
        val cu = c.catchup ?: return null
        if (!pr.isLive(now) || !CatchupUrlBuilder.isAvailable(cu, pr.startMs, now)) return null
        return CatchupUrlBuilder.build(c.url, cu, pr.startMs, pr.stopMs, now)
    }

    fun byUrl(url: String): Channel? = zapList.firstOrNull { it.url == url }
        ?: db.readableDatabase.rawQuery("SELECT $COLS FROM ch WHERE url=? LIMIT 1", arrayOf(url)).use { if (it.moveToFirst()) readChannel(it) else null }

    /** Channels whose name holds every one of [words] (any case), or with guide id [tvg] in playlist [pl]: ChannelVersions narrows them. */
    fun versionCandidates(words: List<String>, tvg: String?, pl: String): List<Channel> {
        val likes = words.map { "%" + it.replace("%", "").replace("_", "") + "%" }
        val byName = likes.joinToString(" AND ") { "name LIKE ?" }
        val (sql, args) = if (tvg != null) "SELECT $COLS FROM ch WHERE ($byName) OR (pl=? AND tvg=?) LIMIT 400" to (likes + listOf(pl, tvg)).toTypedArray()
            else "SELECT $COLS FROM ch WHERE $byName LIMIT 400" to likes.toTypedArray()
        return db.readableDatabase.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(readChannel(c)) } }
    }

    /** A channel's guide, yesterday to tomorrow (what's kept). */
    fun schedule(c: Channel): List<EpgProgram> {
        val tvg = c.tvg ?: return emptyList()
        return db.readableDatabase.rawQuery("SELECT start,stop,title,descr,cat FROM prog WHERE pl=? AND tvg=? ORDER BY start", arrayOf(c.pl, tvg)).use { q ->
            buildList { while (q.moveToNext()) add(EpgProgram(q.getLong(0), q.getLong(1), q.getString(2), q.getString(3), q.getString(4))) }
        }
    }

    /** The link that plays [pr] from the archive, when the channel keeps one that far back. */
    fun catchupUrl(c: Channel, pr: EpgProgram, now: Long = System.currentTimeMillis()): String? {
        val cu = c.catchup ?: return null
        if (pr.stopMs > now || !CatchupUrlBuilder.isAvailable(cu, pr.startMs, now)) return null
        return CatchupUrlBuilder.build(c.url, cu, pr.startMs, pr.stopMs, now)
    }

    // ---- for Sports: every channel, and the guide in the shape Vision's matcher reads ----

    @Volatile private var liveCache: Pair<Int, List<com.lekathedon.visionlite.sports.LiveChannel>>? = null
    fun liveChannels(): List<com.lekathedon.visionlite.sports.LiveChannel> {
        val v = _version.value
        liveCache?.takeIf { it.first == v }?.let { return it.second }
        val names = playlists().associate { it.id to it.name }
        val list = channels(null).map { c ->
            com.lekathedon.visionlite.sports.LiveChannel(c.key, c.pl, names[c.pl].orEmpty(), c.name, c.tvg, c.tvg, c.logo,
                c.pl + "|" + c.group, c.group, c.n, c.url, c.headers, c.catchup)
        }
        liveCache = v to list
        return list
    }

    internal fun guideRevision(): String? = db.readableDatabase.rawQuery("SELECT v FROM meta WHERE k='guide_rev'", null).use { if (it.moveToFirst()) it.getString(0) else null }
    internal fun guideDb(): SQLiteDatabase = db.readableDatabase

    fun hasChannels() = db.readableDatabase.rawQuery("SELECT 1 FROM ch LIMIT 1", null).use { it.moveToFirst() }

    private fun loadedAt(id: String) = db.readableDatabase.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf("loaded:$id")).use {
        if (it.moveToFirst()) it.getString(0).toLongOrNull() ?: 0L else 0L
    }

    private fun metaGet(k: String): String? = db.readableDatabase.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(k)).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun meta(k: String, v: String) {
        db.writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private val db by lazy {
        object : SQLiteOpenHelper(App.instance, com.lekathedon.visionlite.data.Profiles.db("iptv.db"), null, 3) {
            /**
             * Slim guide (0.34.1; 241k shows were 73 MB): guide channel ids as numbers (gch), each text once (txt), shows
             * (p2) clustered by channel and start. The view "prog" gives the old columns, so every reader stays as it was.
             */
            fun slimGuide(d: SQLiteDatabase) {
                d.execSQL("CREATE TABLE gch(id INTEGER PRIMARY KEY, pl TEXT, tvg TEXT, UNIQUE(pl, tvg))")
                d.execSQL("CREATE TABLE txt(id INTEGER PRIMARY KEY, s TEXT)")
                d.execSQL("CREATE TABLE p2(g INTEGER, start INTEGER, stop INTEGER, t INTEGER, d INTEGER, c INTEGER, PRIMARY KEY(g, start)) WITHOUT ROWID")
                d.execSQL("CREATE INDEX p2_start ON p2(start)")
                d.execSQL("CREATE VIEW prog AS SELECT g.pl AS pl, g.tvg AS tvg, p.start AS start, p.stop AS stop, t.s AS title, " +
                    "x.s AS descr, k.s AS cat FROM p2 p JOIN gch g ON g.id = p.g JOIN txt t ON t.id = p.t LEFT JOIN txt x ON x.id = p.d LEFT JOIN txt k ON k.id = p.c")
            }

            override fun onCreate(d: SQLiteDatabase) {
                d.execSQL("CREATE TABLE ch(pl TEXT, n INTEGER, ord INTEGER, name TEXT, grp TEXT, logo TEXT, tvg TEXT, url TEXT, hdr TEXT, " +
                    "cu_type TEXT, cu_src TEXT, cu_days INTEGER, cu_corr REAL, PRIMARY KEY(pl,n))")
                d.execSQL("CREATE INDEX ch_grp ON ch(grp)")
                d.execSQL("CREATE INDEX ch_tvg ON ch(pl, tvg)")
                slimGuide(d)
                d.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
            }
            override fun onUpgrade(d: SQLiteDatabase, o: Int, n: Int) {
                if (o == 2) {
                    // 0.34.1: the guide already on the box moves into the slim layout (nothing downloads again).
                    d.execSQL("ALTER TABLE prog RENAME TO prog_old")
                    d.execSQL("DROP INDEX IF EXISTS prog_ch"); d.execSQL("DROP INDEX IF EXISTS prog_start")
                    slimGuide(d)
                    d.execSQL("INSERT INTO gch(pl, tvg) SELECT DISTINCT pl, tvg FROM prog_old WHERE tvg IS NOT NULL")
                    d.execSQL("INSERT INTO txt(s) SELECT s FROM (SELECT title AS s FROM prog_old UNION SELECT descr FROM prog_old " +
                        "UNION SELECT cat FROM prog_old) WHERE s IS NOT NULL AND s <> ''")
                    d.execSQL("CREATE INDEX tmp_txt ON txt(s)")
                    d.execSQL("INSERT OR IGNORE INTO p2(g, start, stop, t, d, c) SELECT g.id, o.start, o.stop, " +
                        "(SELECT id FROM txt WHERE s = o.title), (SELECT id FROM txt WHERE s = o.descr), (SELECT id FROM txt WHERE s = o.cat) " +
                        "FROM prog_old o JOIN gch g ON g.pl = o.pl AND g.tvg = o.tvg WHERE o.title IS NOT NULL AND o.title <> ''")
                    d.execSQL("DROP INDEX tmp_txt"); d.execSQL("DROP TABLE prog_old")
                    d.execSQL("INSERT OR REPLACE INTO meta(k, v) VALUES('vacuum', '1')")
                    return
                }
                // 0.12 → 0.13: catch-up and guide categories; the playlists reload on the next open.
                listOf("ch", "prog", "meta", "p2", "txt", "gch").forEach { runCatching { d.execSQL("DROP TABLE IF EXISTS $it") } }
                runCatching { d.execSQL("DROP VIEW IF EXISTS prog") }
                onCreate(d)
            }
        }.also { it.setWriteAheadLoggingEnabled(true) }
    }
}
