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
                   val url: String, val headers: Map<String, String>) {
    val key get() = "$pl|$url"
}

/**
 * Live TV (IPTV). On demand only: nothing loads at app start; opening Live TV loads a playlist that is new or a day
 * old, and Refresh reloads. Channels and the guide (now −2 h to +24 h) live in a small SQLite file, so a 50,000
 * channel list or a 200 MB guide never sits in memory; rows ask for their own "now / next" as they are drawn.
 */
object Iptv {
    private const val TAG = "Iptv"
    private const val STALE_MS = 24L * 3600_000
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
    suspend fun ensureLoaded() {
        val now = System.currentTimeMillis()
        playlists().filter { now - loadedAt(it.id) > STALE_MS }.forEach { refresh(it) }
    }

    suspend fun refreshAll() = playlists().forEach { refresh(it) }

    suspend fun refresh(pl: Playlist): Unit = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                _status.value = "Loading ${pl.name}…"
                val (channels, guides) = if (pl.xtream) xtream(pl) else m3u(pl)
                writeChannels(pl.id, channels)
                _version.value++
                val guide = pl.epg.ifBlank { guides.firstOrNull().orEmpty() }
                if (guide.isNotBlank()) {
                    _status.value = "Loading the guide for ${pl.name}…"
                    loadGuide(pl.id, guide)
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
        val list = parsed.entries.mapIndexed { i, e ->
            Channel(pl.id, e.chno ?: (i + 1), e.name, e.group?.takeIf { it.isNotBlank() } ?: "Other", e.logo, e.tvgId ?: e.tvgName, e.url, e.headers)
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
                "$base/live/${enc(pl.user)}/${enc(pl.pass)}/$id.ts", emptyMap())
        }
        return list to listOf("$base/xmltv.php?$auth")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun writeChannels(pl: String, list: List<Channel>) {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            w.delete("ch", "pl=?", arrayOf(pl))
            val st = w.compileStatement("INSERT OR REPLACE INTO ch(pl,n,ord,name,grp,logo,tvg,url,hdr) VALUES(?,?,?,?,?,?,?,?,?)")
            list.forEachIndexed { i, c ->
                st.clearBindings()
                st.bindString(1, pl); st.bindLong(2, i.toLong()); st.bindLong(3, c.n.toLong()); st.bindString(4, c.name); st.bindString(5, c.group)
                c.logo?.let { st.bindString(6, it) }; c.tvg?.let { st.bindString(7, it) }; st.bindString(8, c.url)
                if (c.headers.isNotEmpty()) st.bindString(9, JSONObject(c.headers).toString())
                st.executeInsert()
            }
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
    }

    /** Streams the guide straight into the database, only the channels this playlist has, now −2 h to +24 h. */
    private fun loadGuide(pl: String, url: String) {
        val r = db.readableDatabase
        val ids = HashSet<String>(); val byName = HashMap<String, MutableList<String>>()
        r.rawQuery("SELECT tvg, name, n FROM ch WHERE pl=?", arrayOf(pl)).use { c ->
            while (c.moveToNext()) {
                c.getString(0)?.let { ids += it }
                byName.getOrPut(norm(c.getString(1))) { ArrayList() } += c.getLong(2).toString()
            }
        }
        val now = System.currentTimeMillis()
        val w = db.writableDatabase
        val named = HashMap<String, String>()   // guide id → our channel rows without a tvg id
        w.beginTransaction()
        try {
            w.delete("prog", "pl=?", arrayOf(pl))
            val st = w.compileStatement("INSERT INTO prog(pl,tvg,start,stop,title,descr) VALUES(?,?,?,?,?,?)")
            var count = 0
            open(url).use { input ->
                XmltvParser.parse(input, now - 2 * 3600_000, now + 24 * 3600_000, keepUnlisted = { it in ids },
                    onPrograms = { id, progs ->
                        progs.forEach { pr ->
                            st.clearBindings(); st.bindString(1, pl); st.bindString(2, id); st.bindLong(3, pr.startMs); st.bindLong(4, pr.stopMs)
                            st.bindString(5, pr.title); pr.description?.let { st.bindString(6, it) }; st.executeInsert(); count++
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
            w.setTransactionSuccessful()
            Log.i(TAG, "guide: $count shows")
        } finally { w.endTransaction() }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")

    // ---- reading ----

    /** Groups across all playlists, in playlist order, with channel counts. */
    fun groups(): List<Pair<String, Int>> = db.readableDatabase.rawQuery(
        "SELECT grp, COUNT(*) FROM ch GROUP BY grp ORDER BY MIN(rowid)", null).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) }
    }

    /** [group] null = all; "★" = favourites. */
    fun channels(group: String?): List<Channel> {
        val favs = if (group == "★") favourites() else null
        val (where, args) = when {
            group == null || favs != null -> "" to null
            else -> "WHERE grp=?" to arrayOf(group)
        }
        val all = db.readableDatabase.rawQuery("SELECT pl,ord,name,grp,logo,tvg,url,hdr FROM ch $where ORDER BY rowid", args).use { c ->
            buildList {
                while (c.moveToNext()) add(Channel(c.getString(0), c.getInt(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6),
                    c.getString(7)?.let { h -> runCatching { JSONObject(h).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } }.getOrNull() } ?: emptyMap()))
            }
        }
        return if (favs != null) all.filter { it.key in favs } else all
    }

    /** What is on now and next on a channel. */
    fun nowNext(c: Channel, now: Long = System.currentTimeMillis()): List<EpgProgram> {
        val tvg = c.tvg ?: return emptyList()
        return db.readableDatabase.rawQuery("SELECT start,stop,title,descr FROM prog WHERE pl=? AND tvg=? AND stop>? ORDER BY start LIMIT 2",
            arrayOf(c.pl, tvg, now.toString())).use { q ->
            buildList { while (q.moveToNext()) add(EpgProgram(q.getLong(0), q.getLong(1), q.getString(2), q.getString(3))) }
        }
    }

    fun hasChannels() = db.readableDatabase.rawQuery("SELECT 1 FROM ch LIMIT 1", null).use { it.moveToFirst() }

    private fun loadedAt(id: String) = db.readableDatabase.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf("loaded:$id")).use {
        if (it.moveToFirst()) it.getString(0).toLongOrNull() ?: 0L else 0L
    }

    private fun meta(k: String, v: String) {
        db.writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private val db by lazy {
        object : SQLiteOpenHelper(App.instance, "iptv.db", null, 1) {
            override fun onCreate(d: SQLiteDatabase) {
                d.execSQL("CREATE TABLE ch(pl TEXT, n INTEGER, ord INTEGER, name TEXT, grp TEXT, logo TEXT, tvg TEXT, url TEXT, hdr TEXT, PRIMARY KEY(pl,n))")
                d.execSQL("CREATE INDEX ch_grp ON ch(grp)")
                d.execSQL("CREATE TABLE prog(pl TEXT, tvg TEXT, start INTEGER, stop INTEGER, title TEXT, descr TEXT)")
                d.execSQL("CREATE INDEX prog_ch ON prog(pl, tvg, stop)")
                d.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
            }
            override fun onUpgrade(d: SQLiteDatabase, o: Int, n: Int) {}
        }.also { it.setWriteAheadLoggingEnabled(true) }
    }
}
