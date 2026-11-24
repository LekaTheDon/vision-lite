package com.lekathedon.visionlite.iptv

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.browse.Chips
import com.lekathedon.visionlite.ui.Focusable
import com.lekathedon.visionlite.ui.Img
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class VodItem(val pl: String, val kind: String, val id: String, val name: String, val group: String, val poster: String?, val url: String?, val rating: String?)
data class VodEpisode(val season: Int, val number: Int, val title: String, val url: String, val plot: String?, val image: String?)

/**
 * VOD (Vision's On Demand): films and series from your playlists. Xtream logins list them through their own API, asked
 * only when VOD opens (at most once a day); M3U lists hand theirs over with the channels. Kept in vod.db, not memory.
 */
object Vod {
    private const val TAG = "Vod"
    private val lock = Mutex()
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> get() = _status
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> get() = _version

    private val VOD_PATH = Regex("/(movie|series)/", RegexOption.IGNORE_CASE)
    private val VOD_EXT = listOf(".mp4", ".mkv", ".avi", ".m4v", ".mov", ".wmv")
    fun looksVod(url: String) = VOD_PATH.containsMatchIn(url) || url.substringBefore('?').lowercase().let { u -> VOD_EXT.any { u.endsWith(it) } }

    fun fromM3u(pl: String, entries: List<M3uEntry>) {
        write(pl, "m3u", entries.mapIndexed { i, e -> VodItem(pl, "movie", "m$i", e.name, e.group?.ifBlank { null } ?: "Films", e.logo, e.url, null) })
    }

    fun removePlaylist(pl: String) { db.writableDatabase.delete("items", "pl=?", arrayOf(pl)); db.writableDatabase.delete("meta", "k LIKE ?", arrayOf("$pl|%")); _version.value++ }

    /** Lists the Xtream logins' films and series that are new or a day old (called when VOD opens). */
    suspend fun ensureLoaded(force: Boolean = false) = lock.withLock {
        withContext(Dispatchers.IO) {
            for (pl in Iptv.playlists().filter { it.xtream }) {
                if (!force && System.currentTimeMillis() - loadedAt(pl.id) < Iptv.staleMs()) continue
                try {
                    val first = loadedAt(pl.id) == 0L
                    _status.value = if (first) "Downloading ${pl.name}'s films and series (first time only)…" else "Updating ${pl.name}'s films and series…"
                    val base = Iptv.xtreamBase(pl)
                    val api = "$base/player_api.php?username=${Iptv.encode(pl.user)}&password=${Iptv.encode(pl.pass)}"
                    // Streamed straight into vod.db: big panels send 50-100 MB lists (one string of them ran the app out of memory).
                    fun names(action: String): Map<String, String> = HashMap<String, String>().also { m ->
                        eachObject("$api&action=$action") { o -> m[o["category_id"].orEmpty()] = o["category_name"].orEmpty() }
                    }
                    val movieCats = names("get_vod_categories")
                    writeStream(pl.id, "movie") { add ->
                        eachObject("$api&action=get_vod_streams") { o ->
                            val id = o["stream_id"]?.ifBlank { null } ?: return@eachObject
                            add(VodItem(pl.id, "movie", id, o["name"].orEmpty(), movieCats[o["category_id"].orEmpty()] ?: "Films", o["stream_icon"]?.takeIf { it.startsWith("http") },
                                "$base/movie/${Iptv.encode(pl.user)}/${Iptv.encode(pl.pass)}/$id.${o["container_extension"]?.ifBlank { null } ?: "mp4"}", o["rating"]?.takeIf { it.isNotBlank() && it != "0" }))
                        }
                    }
                    val seriesCats = names("get_series_categories")
                    writeStream(pl.id, "series") { add ->
                        eachObject("$api&action=get_series") { o ->
                            val id = o["series_id"]?.ifBlank { null } ?: return@eachObject
                            add(VodItem(pl.id, "series", id, o["name"].orEmpty(), seriesCats[o["category_id"].orEmpty()] ?: "Series", o["cover"]?.takeIf { it.startsWith("http") }, null,
                                o["rating"]?.takeIf { it.isNotBlank() && it != "0" }))
                        }
                    }
                    db.writableDatabase.execSQL("INSERT OR REPLACE INTO meta(k,v) VALUES(?,?)", arrayOf("${pl.id}|loaded", System.currentTimeMillis().toString()))
                    _status.value = ""
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "${pl.name}: ${e.message}"); _status.value = "${pl.name}'s VOD didn't load: ${e.message}"
                }
                _version.value++
            }
        }
    }

    /**
     * Each object of a top-level JSON array at [url], as flat strings (nested values skipped), read as a stream: memory
     * stays flat however long the list is. A panel answering with an object (or nothing) gives no items.
     */
    private fun eachObject(url: String, each: (Map<String, String?>) -> Unit) {
        android.util.JsonReader(Iptv.openStream(url).bufferedReader()).use { r ->
            r.isLenient = true
            if (r.peek() != android.util.JsonToken.BEGIN_ARRAY) { r.skipValue(); return }
            r.beginArray()
            val o = HashMap<String, String?>()
            while (r.hasNext()) {
                if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                o.clear(); r.beginObject()
                while (r.hasNext()) {
                    val k = r.nextName()
                    when (r.peek()) {
                        android.util.JsonToken.STRING, android.util.JsonToken.NUMBER -> o[k] = r.nextString()
                        android.util.JsonToken.BOOLEAN -> o[k] = r.nextBoolean().toString()
                        android.util.JsonToken.NULL -> { r.nextNull(); o[k] = null }
                        else -> r.skipValue()
                    }
                }
                r.endObject(); each(o)
            }
            r.endArray()
        }
    }

    /** Replaces [pl]'s [kind] items with what [fill] adds, in one transaction (nothing changes when it fails). */
    /**
     * Streams one kind into vod.db in one transaction. When the provider's list is exactly what we have (same
     * fingerprint as last time), the transaction is dropped: nothing changes on screen and no row reloads.
     */
    private fun writeStream(pl: String, kind: String, fill: ((VodItem) -> Unit) -> Unit) {
        val w = db.writableDatabase
        var changed = false
        w.beginTransaction()
        try {
            var h = 1125899906842597L
            w.delete("items", "pl=? AND kind=?", arrayOf(pl, kind))
            val st = w.compileStatement("INSERT OR REPLACE INTO items(pl,kind,id,name,grp,poster,url,rating) VALUES(?,?,?,?,?,?,?,?)")
            var n = 0
            val showCount = _status.value.contains("first time")
            fill { v ->
                n++
                if (showCount && n % 5000 == 0) _status.value = "Downloading ${if (kind == "movie") "films" else "series"}: ${"%,d".format(n)}… (first time only)"
                st.clearBindings(); st.bindString(1, v.pl); st.bindString(2, v.kind); st.bindString(3, v.id); st.bindString(4, v.name); st.bindString(5, v.group)
                v.poster?.let { st.bindString(6, it) }; v.url?.let { st.bindString(7, it) }; v.rating?.let { st.bindString(8, it) }; st.executeInsert()
                h = 31 * h + "${v.id}|${v.name}|${v.group}|${v.poster}|${v.url}|${v.rating}".hashCode()
            }
            val sig = "$h:$n"
            val key = "$pl|$kind|sig"
            if (n == 0 || sig != metaGet(key)) {
                w.execSQL("INSERT OR REPLACE INTO meta(k,v) VALUES(?,?)", arrayOf(key, sig))
                w.setTransactionSuccessful(); changed = true
            }
        } finally { w.endTransaction() }
        if (changed) _version.value++
    }

    /** A series' episodes (Xtream get_series_info). */
    suspend fun episodes(item: VodItem): List<VodEpisode> = withContext(Dispatchers.IO) {
        val pl = Iptv.playlists().firstOrNull { it.id == item.pl && it.xtream } ?: return@withContext emptyList()
        val base = Iptv.xtreamBase(pl)
        runCatching {
            val o = JSONObject(Iptv.openStream("$base/player_api.php?username=${Iptv.encode(pl.user)}&password=${Iptv.encode(pl.pass)}&action=get_series_info&series_id=${item.id}")
                .bufferedReader().use { it.readText() })
            val eps = o.optJSONObject("episodes") ?: return@runCatching emptyList()
            eps.keys().asSequence().flatMap { s ->
                val a = eps.optJSONArray(s) ?: JSONArray()
                (0 until a.length()).mapNotNull { i ->
                    val e = a.optJSONObject(i) ?: return@mapNotNull null
                    val info = e.optJSONObject("info")
                    VodEpisode(s.toIntOrNull() ?: e.optInt("season"), e.optInt("episode_num"), e.optString("title"),
                        "$base/series/${Iptv.encode(pl.user)}/${Iptv.encode(pl.pass)}/${e.optString("id")}.${e.optString("container_extension").ifBlank { "mp4" }}",
                        info?.optString("plot")?.takeIf { it.isNotBlank() }, info?.optString("movie_image")?.takeIf { it.startsWith("http") })
                }.asSequence()
            }.sortedWith(compareBy({ it.season }, { it.number })).toList()
        }.getOrDefault(emptyList())
    }

    fun groups(kind: String): List<Pair<String, Int>> {
        val v = _version.value
        groupCache[kind]?.takeIf { it.first == v }?.let { return it.second }
        return db.readableDatabase.rawQuery("SELECT grp, COUNT(*) FROM items WHERE kind=? GROUP BY grp ORDER BY MIN(rowid)", arrayOf(kind)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) }
        }.also { groupCache[kind] = v to it }
    }
    /** The page opens with its categories at once (no empty frame) once they were read this run. */
    fun groupsCached(kind: String): List<Pair<String, Int>> = groupCache[kind]?.second.orEmpty()
    private val groupCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, List<Pair<String, Int>>>>()

    fun items(kind: String, group: String?, offset: Int = 0, limit: Int = 3000): List<VodItem> {
        val (w, a) = if (group == null) "kind=?" to arrayOf(kind) else "kind=? AND grp=?" to arrayOf(kind, group)
        return db.readableDatabase.rawQuery("SELECT pl,kind,id,name,grp,poster,url,rating FROM items WHERE $w ORDER BY rowid LIMIT $limit OFFSET $offset", a).use { c ->
            buildList { while (c.moveToNext()) add(VodItem(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7))) }
        }
    }

    /** One title by its keys (a Home VOD card opens it). */
    fun item(pl: String, kind: String, id: String): VodItem? = db.readableDatabase.rawQuery(
        "SELECT pl,kind,id,name,grp,poster,url,rating FROM items WHERE pl=? AND kind=? AND id=?", arrayOf(pl, kind, id)).use { c ->
        if (c.moveToFirst()) VodItem(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7)) else null
    }

    /** Small saved facts (VOD → TMDB matches); kept across refreshes. */
    fun metaGet(k: String): String? = db.readableDatabase.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(k)).use { if (it.moveToFirst()) it.getString(0) else null }
    fun metaPut(k: String, v: String) { db.writableDatabase.execSQL("INSERT OR REPLACE INTO meta(k,v) VALUES(?,?)", arrayOf(k, v)) }

    /** How many films and series vod.db holds (0 = no VOD: Vision TB doesn't look here). */
    fun count(): Long = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM items", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    /** Titles of [kind] whose name holds [word] (Vision TB's VOD match narrows with this, then compares whole names). */
    fun search(kind: String, word: String, limit: Int = 200): List<VodItem> {
        if (word.isBlank()) return emptyList()
        return db.readableDatabase.rawQuery("SELECT pl,kind,id,name,grp,poster,url,rating FROM items WHERE kind=? AND name LIKE ? LIMIT $limit",
            arrayOf(kind, "%${word.replace("%", "").replace("_", "")}%")).use { c ->
            buildList { while (c.moveToNext()) add(VodItem(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7))) }
        }
    }

    private fun write(pl: String, what: String, list: List<VodItem>) {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            if (what == "m3u") w.delete("items", "pl=? AND id LIKE 'm%'", arrayOf(pl)) else w.delete("items", "pl=? AND kind=?", arrayOf(pl, what))
            val st = w.compileStatement("INSERT OR REPLACE INTO items(pl,kind,id,name,grp,poster,url,rating) VALUES(?,?,?,?,?,?,?,?)")
            list.forEach { v ->
                st.clearBindings(); st.bindString(1, v.pl); st.bindString(2, v.kind); st.bindString(3, v.id); st.bindString(4, v.name); st.bindString(5, v.group)
                v.poster?.let { st.bindString(6, it) }; v.url?.let { st.bindString(7, it) }; v.rating?.let { st.bindString(8, it) }; st.executeInsert()
            }
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
        _version.value++
    }

    private fun loadedAt(pl: String) = db.readableDatabase.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf("$pl|loaded")).use { if (it.moveToFirst()) it.getString(0).toLongOrNull() ?: 0L else 0L }

    private val db by lazy {
        object : SQLiteOpenHelper(App.instance, com.lekathedon.visionlite.data.Profiles.db("vod.db"), null, 1) {
            override fun onCreate(d: SQLiteDatabase) {
                d.execSQL("CREATE TABLE items(pl TEXT, kind TEXT, id TEXT, name TEXT, grp TEXT, poster TEXT, url TEXT, rating TEXT, PRIMARY KEY(pl,kind,id))")
                d.execSQL("CREATE INDEX items_grp ON items(kind, grp)")
                d.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
            }
            override fun onUpgrade(d: SQLiteDatabase, o: Int, n: Int) {}
        }.also { it.setWriteAheadLoggingEnabled(true) }   // TMDB matches written while rows read (no lock waits)
    }
}

/**
 * VOD in Home's look: Films / Series, then one Home row per category (posters, focus memory, Up/Down back to the card
 * you left). Each row reads 40 titles from vod.db and the next 40 as you reach its end. A series opens its episodes.
 */
@Composable
fun VodScreen(nav: Nav) {
    val version by Vod.version.collectAsState()
    val status by Vod.status.collectAsState()
    LaunchedEffect(Unit) { com.lekathedon.visionlite.iptv.IptvSync.kick() }
    var kind by rememberSaveable { mutableStateOf("movie") }
    val groups by produceState(Vod.groupsCached(kind), version, kind) { value = withContext(Dispatchers.IO) { Vod.groups(kind) } }
    var series by remember { mutableStateOf<VodItem?>(null) }
    if (series != null) { androidx.activity.compose.BackHandler { series = null }; Episodes(nav, series!!); return }
    val store = remember(version, kind) { HashMap<String, VodRow>() }
    Column(Modifier.fillMaxSize().background(LocalLook.current.page).padding(start = 40.dp, end = 24.dp, top = 24.dp)) {
        Row {
            Text("VOD", color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(status.ifEmpty { if (Iptv.playlists().isEmpty()) "Add a playlist in Live TV first." else "${groups.sumOf { it.second }} titles · ${groups.size} categories" },
                color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(start = 14.dp, top = 8.dp))
        }
        Chips(listOf("movie" to "Films", "series" to "Series"), kind, Modifier.padding(top = 12.dp)) { kind = it }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 60.dp)) {
            items(groups, key = { "$kind|${it.first}" }) { (g, n) ->
                VodRail(nav, kind, g, n, store) { series = it }
            }
        }
    }
}

/** One category row's titles, kept by the page so a row scrolled away and back keeps what it had. */
class VodRow { val items = androidx.compose.runtime.mutableStateListOf<VodItem>(); var busy = false; var end = false }

@Composable
private fun VodRail(nav: Nav, kind: String, group: String, count: Int, store: MutableMap<String, VodRow>, openSeries: (VodItem) -> Unit) {
    val d = store.getOrPut(group) { VodRow() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun more() {
        if (d.busy || d.end) return
        d.busy = true
        scope.launch {
            try {
                val got = withContext(Dispatchers.IO) { Vod.items(kind, group, d.items.size, 40) }
                d.items.addAll(got); d.end = got.size < 40
            } finally { d.busy = false }
        }
    }
    LaunchedEffect(group) { if (d.items.isEmpty()) more() }
    // Our look (user 2026-11-23): each title matched to TMDB once (saved in vod.db) shows its poster and opens its
    // title page (Vision TB + this VOD copy); one TMDB doesn't know keeps the playlist's card and plays from VOD.
    var matched by remember { mutableStateOf(0) }
    val size = d.items.size
    LaunchedEffect(size) { if (size > 0 && VodArabic.match(d.items.toList())) matched++ }
    val metas by produceState(emptyList<com.lekathedon.visionlite.addons.MetaPreview>(), size, matched) {
        val list = d.items.toList()
        value = withContext(Dispatchers.IO) { list.map { v -> VodArabic.card(v).let { if (it.id.startsWith("vod:")) it.copy(imdbRating = v.rating) else it } } }
    }
    val byId = remember(size) { d.items.associateBy { "vod:${it.pl}|${it.kind}|${it.id}" } }
    val scope2 = androidx.compose.runtime.rememberCoroutineScope()
    com.lekathedon.visionlite.home.PageRow("vod|$kind|$group", "$group · $count", metas, loading = !d.end && metas.isEmpty(),
        sub = { m -> m.imdbRating?.let { "★ $it" } }, onEnd = { more() }) { m ->
        val v = byId[m.id]
        if (v == null) scope2.launch { com.lekathedon.visionlite.home.openTitle(nav, m) }
        else if (v.kind == "series") openSeries(v) else v.url?.let { nav.push(Screen.Player(it, v.name, type = "vod")) }
    }
}

@Composable
internal fun Episodes(nav: Nav, s: VodItem) {
    val eps by produceState<List<VodEpisode>?>(null, s) { value = Vod.episodes(s) }
    val seasons = eps.orEmpty().map { it.season }.distinct()
    var season by remember(s) { mutableStateOf<Int?>(null) }
    val sea = season ?: seasons.firstOrNull()
    Column(Modifier.fillMaxSize().background(LocalLook.current.page).padding(40.dp)) {
        Row {
            if (s.poster != null) Img(s.poster, 110.dp, 165.dp, Modifier.size(110.dp, 165.dp))
            Column(Modifier.padding(start = 18.dp)) {
                Text(s.name, color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(if (eps == null) "Loading…" else "${eps!!.size} episodes", color = Palette.TextDim, fontSize = 14.sp)
                if (seasons.size > 1) Chips(seasons.map { it.toString() to "Season $it" }, sea?.toString(), Modifier.padding(top = 12.dp)) { season = it.toInt() }
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(eps.orEmpty().filter { it.season == sea }) { e ->
                Focusable({ nav.push(Screen.Player(e.url, "${s.name} · S${e.season}E${e.number}", type = "vod")) }, Modifier.fillMaxWidth()) { f ->
                    Column(Modifier.fillMaxWidth().background(if (f) Color.White else Color(0x12FFFFFF)).padding(12.dp)) {
                        Text("${e.number}. ${e.title}", color = if (f) Color.Black else Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        e.plot?.let { Text(it, color = if (f) Color.DarkGray else Palette.TextDim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

/** A VOD series opened from a Home row (no TMDB match): its episodes. */
@Composable
fun VodSeriesScreen(nav: Nav, pl: String, id: String) {
    val item = remember(pl, id) { Vod.item(pl, "series", id) }
    if (item == null) { Text("Not in your VOD any more", color = Palette.TextDim, modifier = Modifier.padding(48.dp)); return }
    Episodes(nav, item)
}
