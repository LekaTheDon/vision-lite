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
                if (!force && System.currentTimeMillis() - loadedAt(pl.id) < 24 * 3600_000L) continue
                try {
                    _status.value = "Loading ${pl.name}'s films and series…"
                    val base = Iptv.xtreamBase(pl)
                    val api = "$base/player_api.php?username=${Iptv.encode(pl.user)}&password=${Iptv.encode(pl.pass)}"
                    fun arr(action: String) = JSONArray(Iptv.openStream("$api&action=$action").bufferedReader().use { it.readText() })
                    fun names(a: JSONArray) = (0 until a.length()).associate { a.getJSONObject(it).let { c -> c.optString("category_id") to c.optString("category_name") } }
                    val movieCats = names(arr("get_vod_categories"))
                    val movies = arr("get_vod_streams")
                    val films = (0 until movies.length()).mapNotNull { i ->
                        val o = movies.optJSONObject(i) ?: return@mapNotNull null
                        val id = o.optString("stream_id").ifBlank { return@mapNotNull null }
                        VodItem(pl.id, "movie", id, o.optString("name"), movieCats[o.optString("category_id")] ?: "Films", o.optString("stream_icon").takeIf { it.startsWith("http") },
                            "$base/movie/${Iptv.encode(pl.user)}/${Iptv.encode(pl.pass)}/$id.${o.optString("container_extension").ifBlank { "mp4" }}", o.optString("rating").takeIf { it.isNotBlank() && it != "0" })
                    }
                    write(pl.id, "movie", films)
                    val seriesCats = names(arr("get_series_categories"))
                    val series = arr("get_series")
                    write(pl.id, "series", (0 until series.length()).mapNotNull { i ->
                        val o = series.optJSONObject(i) ?: return@mapNotNull null
                        val id = o.optString("series_id").ifBlank { return@mapNotNull null }
                        VodItem(pl.id, "series", id, o.optString("name"), seriesCats[o.optString("category_id")] ?: "Series", o.optString("cover").takeIf { it.startsWith("http") }, null,
                            o.optString("rating").takeIf { it.isNotBlank() && it != "0" })
                    })
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

    fun groups(kind: String): List<Pair<String, Int>> = db.readableDatabase.rawQuery(
        "SELECT grp, COUNT(*) FROM items WHERE kind=? GROUP BY grp ORDER BY MIN(rowid)", arrayOf(kind)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) } }

    fun items(kind: String, group: String?): List<VodItem> {
        val (w, a) = if (group == null) "kind=?" to arrayOf(kind) else "kind=? AND grp=?" to arrayOf(kind, group)
        return db.readableDatabase.rawQuery("SELECT pl,kind,id,name,grp,poster,url,rating FROM items WHERE $w ORDER BY rowid LIMIT 3000", a).use { c ->
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
        object : SQLiteOpenHelper(App.instance, "vod.db", null, 1) {
            override fun onCreate(d: SQLiteDatabase) {
                d.execSQL("CREATE TABLE items(pl TEXT, kind TEXT, id TEXT, name TEXT, grp TEXT, poster TEXT, url TEXT, rating TEXT, PRIMARY KEY(pl,kind,id))")
                d.execSQL("CREATE INDEX items_grp ON items(kind, grp)")
                d.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
            }
            override fun onUpgrade(d: SQLiteDatabase, o: Int, n: Int) {}
        }
    }
}

/** VOD: Films / Series, a category on the left, posters on the right; a series opens its seasons and episodes. */
@Composable
fun VodScreen(nav: Nav) {
    val version by Vod.version.collectAsState()
    val status by Vod.status.collectAsState()
    LaunchedEffect(Unit) { Vod.ensureLoaded() }
    var kind by rememberSaveable { mutableStateOf("movie") }
    var group by rememberSaveable(kind) { mutableStateOf<String?>(null) }
    val groups by produceState(emptyList<Pair<String, Int>>(), version, kind) { value = withContext(Dispatchers.IO) { Vod.groups(kind) } }
    val items by produceState(emptyList<VodItem>(), version, kind, group) { value = withContext(Dispatchers.IO) { Vod.items(kind, group) } }
    var series by remember { mutableStateOf<VodItem?>(null) }
    if (series != null) { androidx.activity.compose.BackHandler { series = null }; Episodes(nav, series!!); return }
    Column(Modifier.fillMaxSize().background(LocalLook.current.page).padding(start = 36.dp, end = 24.dp, top = 24.dp)) {
        Row {
            Text("VOD", color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(status.ifEmpty { if (Iptv.playlists().isEmpty()) "Add a playlist in Live TV first." else "${items.size} titles" }, color = Palette.TextDim, fontSize = 13.sp,
                modifier = Modifier.padding(start = 14.dp, top = 8.dp))
        }
        Chips(listOf("movie" to "Films", "series" to "Series"), kind, Modifier.padding(top = 12.dp)) { kind = it }
        Row(Modifier.fillMaxSize().padding(top = 12.dp)) {
            LazyColumn(Modifier.width(230.dp).fillMaxHeight(), contentPadding = PaddingValues(bottom = 30.dp)) {
                items(listOf("" to groups.sumOf { it.second }) + groups, key = { it.first }) { (g, n) ->
                    val sel = (group ?: "") == g
                    Focusable({ group = g.ifEmpty { null } }, Modifier.padding(bottom = 4.dp).fillMaxWidth()) { f ->
                        Row(Modifier.fillMaxWidth().background(if (f) Color.White else if (sel) Color(0x33FFFFFF) else Color.Transparent).padding(10.dp)) {
                            Text(g.ifEmpty { "All" }, color = if (f) Color.Black else Palette.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("$n", color = if (f) Color.DarkGray else Palette.TextDim, fontSize = 12.sp)
                        }
                    }
                }
            }
            LazyVerticalGrid(GridCells.Adaptive(130.dp), Modifier.weight(1f).padding(start = 16.dp), contentPadding = PaddingValues(bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(items, key = { it.pl + it.kind + it.id }) { v ->
                    Column(Modifier.width(130.dp)) {
                        Focusable({ if (v.kind == "series") series = v else v.url?.let { nav.push(Screen.Player(it, v.name, type = "vod")) } }, Modifier.size(130.dp, 195.dp)) {
                            Box(Modifier.fillMaxSize().background(Palette.Card)) {
                                Text(v.name, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                                if (v.poster != null) Img(v.poster, 130.dp, 195.dp, Modifier.fillMaxSize())
                            }
                        }
                        Text(v.name + (v.rating?.let { " · ★ $it" } ?: ""), color = Palette.TextDim, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Episodes(nav: Nav, s: VodItem) {
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
