package com.lekathedon.visionlite.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.anime.AnimeIds
import com.lekathedon.visionlite.anime.Hub
import com.lekathedon.visionlite.anime.Hubs
import com.lekathedon.visionlite.addons.Addon
import com.lekathedon.visionlite.addons.CatalogDef
import com.lekathedon.visionlite.addons.MetaPreview
import com.lekathedon.visionlite.library.backdropOf
import com.lekathedon.visionlite.library.posterOf
import com.lekathedon.visionlite.recs.AiPicks
import com.lekathedon.visionlite.recs.Pick
import com.lekathedon.visionlite.recs.Recs
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

enum class RowKind { POSTER, COMING, SERVICES, PEOPLE, /** Genres / Decades tiles. */ TILES, /** Top 20 Today: Vision Echo's wide cards with the big 1-20 behind them. */ RANKED }

/** A Home row: Vision's own (addon == null) or an add-on catalog. */
data class Row(
    val key: String, val title: String, val addon: Addon?, val def: CatalogDef?, val items: List<MetaPreview>, val done: Boolean,
    val kind: RowKind = RowKind.POSTER
) {
    val builtIn get() = addon == null
    /** Where it comes from, for Settings › Home rows. */
    val source get() = addon?.manifest?.name ?: if (key.startsWith("rot/")) "Rotating" else if (key.startsWith("vod/")) "VOD" else "Vision"
}

/**
 * Home rows = Vision's rows (Coming up, Vision Picks, Because you watched / you like, Moods, Streaming services,
 * the charts) and, when switched on, every add-on catalog that needs no extra. A row loads only when it comes near
 * the screen ([need]); add-on rows ask more pages only when focus nears their end ([more]).
 */
private const val SERVER_ROW = "vision/server"

/** One Home per content hub ([homeModel]): each holds only its own hub's titles, its own row order and picks. */
@androidx.compose.runtime.Composable
fun homeModel(hub: Hub): HomeModel = androidx.lifecycle.viewmodel.compose.viewModel(key = "home:${hub.key}") { HomeModel(hub) }

class HomeModel(val hub: Hub = Hub.FILMS) : ViewModel() {
    private val app = App.instance
    private val state = MutableStateFlow<List<Row>>(emptyList())
    /** Each row's items as their source gave them; the row shows only this hub's ([keep]). */
    private val raw = HashMap<String, List<MetaPreview>>()
    private fun keep(list: List<MetaPreview>) = list.filter { Hubs.keeps(hub, it) && !com.lekathedon.visionlite.recs.Adult.hides(it) }
    private val rowOrder get() = Hubs.rowOrder(hub)
    private val engineKeys = listOf(VisionRows.PICKS) + VisionRows.BECAUSE + VisionRows.MOODS
    /** Mood rows' TMDB top-up, once per mood per app run (2 TMDB requests; nothing without a key). */
    val rows: StateFlow<List<Row>> get() = state
    private val loading = HashMap<String, Job>()
    private val needed = HashSet<String>()
    private val cwState = MutableStateFlow<List<com.lekathedon.visionlite.trakt.Resume>>(emptyList())
    private var cwRaw: List<com.lekathedon.visionlite.trakt.Resume> = emptyList()
    /** Trakt Continue Watching (empty when not connected). */
    val continueWatching: StateFlow<List<com.lekathedon.visionlite.trakt.Resume>> get() = cwState
    private var cwAt = 0L
    private var cwJob: Job? = null

    /** Hold menu's Drop: gone from the row at once; Trakt is told in the background. */
    fun dropContinue(r: com.lekathedon.visionlite.trakt.Resume) {
        cwState.value = cwState.value.filter { it.imdb != r.imdb }; cwRaw = cwRaw.filter { it.imdb != r.imdb }
        viewModelScope.launch { runCatching { app.trakt.drop(r.imdb, r.type == "series") } }
    }

    /** Asked when Home shows; at most every 30 s (coming back from the player always refreshes via [force]). */
    fun refreshContinue(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (app.trakt.connected.value == null) { cwState.value = emptyList(); return }
        if (!force && now - cwAt < 30_000) return
        cwAt = now
        cwJob?.cancel()
        cwJob = viewModelScope.launch {
            runCatching { app.trakt.continueWatching() }.getOrNull()?.let { cwRaw = it }
            cwState.value = cwRaw.filter { Hubs.keepsId(hub, it.imdb) }
        }
    }

    init {
        viewModelScope.launch {
            AnimeIds.warm()
            app.addons.ensureDefaults()
            combine(kotlinx.coroutines.flow.combine(app.addons.addons, app.addons.version) { l, _ -> l }, rowOrder.flow, app.prefs.addonRows.flow, app.prefs.importedLists.flow, com.lekathedon.visionlite.integrations.Integrations.changed) { list, _, _, _, _ -> list }
                .combine(Rotation.rows(hub)) { list, _ -> list }
                .combine(app.prefs.vodArabic.flow) { list, _ -> list }
                .combine(com.lekathedon.visionlite.iptv.Vod.version) { list, _ -> list }
                .collect { list -> rebuild(list) }
        }
        // Rotating rows: the set built last time shows at once (disk); the next one builds a minute after Home shows.
        viewModelScope.launch { runCatching { Rotation.onHome(hub) } }
        // Vision's rows follow their sources: anything new there shows on Home at once.
        // The engine's rows (Vision Picks + Scout + the AIs): anything it learns or you say (Love / Like / Not for me) shows at once.
        viewModelScope.launch { combine(Recs.pool(hub), Recs.changed, AiPicks.picks(hub)) { _, _, _ -> }.collect { engineKeys.forEach { fill(it) } } }
        // Hide adult titles: a title found out to be adult leaves every row at once.
        viewModelScope.launch {
            combine(com.lekathedon.visionlite.recs.Adult.revision, app.prefs.hideAdult.flow) { a, b -> a to b }.collect {
                state.value = state.value.map { r -> raw[r.key]?.let { r.copy(items = keep(it)) } ?: r }
            }
        }
        // Hide adult titles switched: the tiles' pictures are chosen again under the new rule (no-op when unchanged).
        viewModelScope.launch {
            app.prefs.hideAdult.flow.collect {
                if (VisionRows.SERVICES in needed) runCatching { Services.refresh() }
                if (hub == Hub.FILMS && VisionRows.GENRES in needed) runCatching { com.lekathedon.visionlite.browse.Wuplay.loadArt() }
            }
        }
        viewModelScope.launch { ComingUp.items.collect { fill(VisionRows.COMING_UP) } }
        viewModelScope.launch { Services.art.collect { fill(VisionRows.SERVICES) } }
        // Favourite people: a follow / unfollow changes the two rows at once (and the engine at its next build).
        viewModelScope.launch { People.list.collect { VisionRows.FAVS.forEach { if (it in needed) loadFav(it) } } }
        viewModelScope.launch { com.lekathedon.visionlite.recs.Interests.changed.collect { VisionRows.GENRE_ROWS.forEach { if (it in needed) loadGenre(it) } } }
        viewModelScope.launch {
            (if (hub == Hub.ANIME) com.lekathedon.visionlite.anime.AniBrowse.art else com.lekathedon.visionlite.browse.Wuplay.art).collect { fill(VisionRows.GENRES); fill(VisionRows.DECADES) }
        }
        // A title moved between hubs, or the anime list refreshed: every row sorts itself again.
        viewModelScope.launch {
            AnimeIds.changed.collect { n ->
                if (n == 0) return@collect
                state.value = state.value.map { r -> raw[r.key]?.let { r.copy(items = keep(it)) } ?: r }
                cwState.value = cwRaw.filter { Hubs.keepsId(hub, it.imdb) }
            }
        }
        // The weekly anime list refresh: a minute after Home first shows, never at start.
        viewModelScope.launch { kotlinx.coroutines.delay(60_000); AnimeIds.refreshIfDue() }
    }

    /** Refresh: forget every loaded page and ask again as rows come into view. */
    fun refreshAll() {
        loading.values.forEach { it.cancel() }; loading.clear(); needed.clear()
        app.client.clearCatalogs()
        state.value = state.value.map { if (it.builtIn) it else it.copy(items = emptyList(), done = false).also { r -> raw.remove(r.key) } }
        ComingUp.refresh(true)
    }

    private fun rebuild(list: List<Addon>) {
        state.value.filter { !it.builtIn }.forEach { loading.remove(it.key)?.cancel() }
        val old = state.value.associateBy { it.key }
        val order = rowOrder.value.split('\n').filter { it.isNotEmpty() }.withIndex().associate { it.value to it.index }
        val vision = VisionRows.forHub(hub).map { (key, title) ->
            old[key] ?: Row(key, title, null, null, emptyList(), done = false, kind = when (key) {
                VisionRows.COMING_UP -> RowKind.COMING; VisionRows.SERVICES -> RowKind.SERVICES
                VisionRows.GENRES, VisionRows.DECADES, VisionRows.MOODS -> RowKind.TILES
                in VisionRows.ranked -> RowKind.RANKED; else -> RowKind.POSTER
            })
        }
        // Lists added from the phone: after Vision's rows, before the add-ons.
        val imported = ImportedLists.all().map { l -> old[l.key] ?: Row(l.key, l.name, null, null, emptyList(), done = false) } +
            // Plex / Jellyfin: what was added last (only when signed in; asked when the row comes near).
            (if (com.lekathedon.visionlite.streams.MediaServers.plexOn() || com.lekathedon.visionlite.streams.MediaServers.jellyfinOn())
                listOf(old[SERVER_ROW] ?: Row(SERVER_ROW, "New on your server", null, null, emptyList(), done = false)) else emptyList())
        // Rotating rows: under your own, before the add-ons; their titles come ready (built in the background).
        val rotating = Rotation.rows(hub).value.map { r ->
            val key = Rotation.key(r.id)
            raw[key] = r.items
            Row(key, r.title, null, null, keep(r.items), done = true)
        }
        val addons = if (!app.prefs.addonRows.value) emptyList() else list.filter { com.lekathedon.visionlite.addons.AddonRoles.allows(it.base, "catalog") }.flatMap { a ->
            a.manifest.catalogs.filter { it.isBoardRow }.map { c ->
                val key = "${a.manifest.id}/${c.type}/${c.id}"
                old[key] ?: Row(key, title(a, c), a, c, emptyList(), done = false)
            }
        }
        // Arabic VOD categories (this profile switched them on): anime ones in the Anime hub, the rest in Films & TV.
        val vod = if (!app.prefs.vodArabic.value) emptyList() else com.lekathedon.visionlite.iptv.VodArabic.groups()
            .filter { it.anime == (hub == com.lekathedon.visionlite.anime.Hub.ANIME) }
            .map { g -> old[g.key] ?: Row(g.key, g.grp, null, null, emptyList(), done = false) }
        // Rows new since you arranged Home (Genres, Decades) sit right after Streaming services, not at the bottom.
        val svc = order[VisionRows.SERVICES]
        fun place(i: Int, r: Row): Double = order[r.key]?.toDouble()
            ?: if (svc != null && r.key == VisionRows.GENRES) svc + 0.1 else if (svc != null && r.key == VisionRows.DECADES) svc + 0.2
            // Because you like · 1 / 2 (new in 0.28): right under Because you watched · 1 / 2.
            else VisionRows.FAVS.indexOf(r.key).takeIf { it >= 0 }?.let { order[VisionRows.BECAUSE[it]] }?.let { it + 0.1 }
            // Your genres (new in 0.33): right under Because you like · 1 / 2.
            ?: VisionRows.GENRE_ROWS.indexOf(r.key).takeIf { it >= 0 }?.let { order[VisionRows.BECAUSE[it]] }?.let { it + 0.2 } ?: (order.size + i).toDouble()
        state.value = (vision + imported + rotating + vod + addons).withIndex().sortedBy { (i, r) -> place(i, r) }.map { it.value }
    }

    private fun title(a: Addon, c: CatalogDef): String {
        val type = when (c.type) { "movie" -> "Movies"; "series" -> "Series"; else -> c.type.replaceFirstChar { it.uppercase() } }
        val name = c.name ?: c.id
        return if (name.contains(type, ignoreCase = true)) name else "$name $type"
    }

    /** Home's look-ahead: starts a row below the screen loading; a row already started is left alone (no redraw). */
    fun prefetch(key: String) {
        val row = state.value.firstOrNull { it.key == key } ?: return
        if (row.builtIn && key in needed) return
        need(key)
    }

    fun need(key: String) {
        val row = state.value.firstOrNull { it.key == key } ?: return
        if (row.builtIn) { if (needed.add(key)) start(row) else fill(key); return }
        if (row.items.isNotEmpty() || row.done || loading.containsKey(key)) return
        load(row, 0)
    }

    fun more(key: String) {
        val row = state.value.firstOrNull { it.key == key } ?: return
        if (row.builtIn || row.done || row.def?.supportsSkip != true || loading.containsKey(key)) return
        load(row, raw[key]?.size ?: row.items.size)
    }

    /** The Refresh tile at the end of a Picks row. */
    fun refreshPicks(@Suppress("UNUSED_PARAMETER") key: String) = Recs.rebuildNow(hub)


    /** A Vision row's first look: its source is asked to refresh when due (each keeps its own clock). */
    private fun start(row: Row) {
        val key = row.key
        when (key) {
            VisionRows.COMING_UP -> ComingUp.onHome()
            VisionRows.PICKS, in VisionRows.BECAUSE, VisionRows.MOODS -> viewModelScope.launch { runCatching { Recs.refreshIfDue(hub) } }
            VisionRows.SERVICES -> viewModelScope.launch { runCatching { Services.refresh() } }
            in VisionRows.FAVS -> { loadFav(key); return }
            in VisionRows.GENRE_ROWS -> { loadGenre(key); return }
            in vodKeys() -> { loadVod(key); return }
            // The tiles' pictures: read from disk, asked again once a week (28 small TMDB calls), never before the row shows.
            VisionRows.GENRES, VisionRows.DECADES -> viewModelScope.launch { runCatching { if (hub == Hub.FILMS) com.lekathedon.visionlite.browse.Wuplay.loadArt() else com.lekathedon.visionlite.anime.AniBrowse.loadArt() } }
            SERVER_ROW -> {
                loading[key] = viewModelScope.launch {
                    val items = runCatching { com.lekathedon.visionlite.streams.MediaServers.recentlyAdded() }.getOrDefault(emptyList())
                    set(key, items.map { MetaPreview(it.id, it.type, it.name, posterOf(it.id), backdropOf(it.id)) })
                    loading.remove(key)
                }
                return
            }
            else -> ImportedLists.all().firstOrNull { it.key == key }?.let { l ->
                loading[key] = viewModelScope.launch {
                    set(key, ImportedLists.items(l))
                    loading.remove(key)
                }
                return
            } ?: Charts.byKey(key)?.let { c ->
                loading[key] = viewModelScope.launch {
                    val items = runCatching { Charts.items(c) }.getOrDefault(emptyList())
                    set(key, items)
                    loading.remove(key)
                }
                return
            }
        }
        fill(key)
    }

    /** Puts a Vision row's current items in (only once Home has asked for that row). */
    private fun fill(key: String) {
        if (key !in needed) return
        val items: List<MetaPreview> = when (key) {
            VisionRows.COMING_UP -> ComingUp.items.value.map { i ->
                // Wide card: the show's landscape backdrop (TVmaze's image is a tall poster, cropped to its middle).
                MetaPreview(i.imdb, i.type, i.name, i.image ?: posterOf(i.imdb), backdropOf(i.imdb),
                    description = ComingUp.episodeLine(i), releaseInfo = ComingUp.label(i))
            }
            VisionRows.PICKS -> Recs.forYou(30, hub = hub).map(::meta)
            in VisionRows.BECAUSE -> {
                val b = Recs.becauseNow(VisionRows.BECAUSE.size, hub).getOrNull(VisionRows.BECAUSE.indexOf(key))
                set(key, b?.picks.orEmpty().map(::meta), b?.let { "Because you watched ${it.seed}" }); return
            }
            // Every mood a tile (its picture: the engine's first title for it, none = its colour). No requests.
            VisionRows.MOODS -> com.lekathedon.visionlite.recs.Moods.tiles(hub)
            // Each hub its own services: the anime-only ones (Crunchyroll, HIDIVE, RetroCrush, d Anime Store, U-NEXT, ADN, Anime Times, Toei, Anime Generation) only in Anime.
            VisionRows.SERVICES -> if (!Services.on()) emptyList() else Services.S.entries.filter { it.anime == (hub == Hub.ANIME) }.map { s ->
                val art = Services.art.value.firstOrNull { it.id == s.id }
                MetaPreview("svc:${s.id}", "service", s.title, null, Tmdb.img(art?.backdrop, "w780"), Tmdb.img(art?.logo, "w300"))
            }
            VisionRows.GENRES, VisionRows.DECADES -> {
                val anime = hub == Hub.ANIME
                val art = if (anime) com.lekathedon.visionlite.anime.AniBrowse.art.value else com.lekathedon.visionlite.browse.Wuplay.art.value
                val tiles = if (key == VisionRows.GENRES) com.lekathedon.visionlite.browse.BrowseTiles.genres(anime) else com.lekathedon.visionlite.browse.BrowseTiles.decades(anime)
                tiles.map { (id, name) -> MetaPreview(id, if (key == VisionRows.GENRES) "genre" else "decade", name, null, art[id]) }
            }
            else -> return
        }
        set(key, items)
    }

    /** "Because you like X": the favourite this row follows now, their titles (one IMDb request, kept for the run). */
    private fun loadFav(key: String) {
        val i = VisionRows.FAVS.indexOf(key)
        loading.remove(key)?.cancel()
        loading[key] = viewModelScope.launch {
            val p = People.now(hub.key, VisionRows.FAVS.size).getOrNull(i)
            if (p == null) set(key, emptyList())
            else set(key, runCatching { People.row(p) }.getOrDefault(emptyList()), "Because you like ${p.name}")
            loading.remove(key)
        }
    }

    private fun vodKeys(): Set<String> = state.value.filter { it.key.startsWith(com.lekathedon.visionlite.iptv.VodArabic.PREFIX) }.map { it.key }.toSet()

    /** An Arabic VOD row: its cards at once (from vod.db), then again once new titles are matched to TMDB. */
    private fun loadVod(key: String) {
        val g = com.lekathedon.visionlite.iptv.VodArabic.byKey(key) ?: return
        loading.remove(key)?.cancel()
        loading[key] = viewModelScope.launch {
            set(key, kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.lekathedon.visionlite.iptv.VodArabic.cards(g, false) }.getOrDefault(emptyList()) })
            if (runCatching { com.lekathedon.visionlite.iptv.VodArabic.match(g) }.getOrDefault(false))
                set(key, kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.lekathedon.visionlite.iptv.VodArabic.cards(g, true) }.getOrDefault(emptyList()) })
            loading.remove(key)
        }
    }

    /** "Because you like <genre>": the genre this row follows now (Interests), IMDb's titles with it. */
    private fun loadGenre(key: String) {
        val i = VisionRows.GENRE_ROWS.indexOf(key)
        loading.remove(key)?.cancel()
        loading[key] = viewModelScope.launch {
            val g = com.lekathedon.visionlite.recs.Interests.now(hub, VisionRows.GENRE_ROWS.size).getOrNull(i)
            val name = g?.let { com.lekathedon.visionlite.recs.Interests.name(it) }
            if (g == null || name == null) set(key, emptyList())
            else set(key, runCatching { com.lekathedon.visionlite.recs.Interests.row(hub, g) }.getOrDefault(emptyList()), "Because you like $name")
            loading.remove(key)
        }
    }

    private fun set(key: String, items: List<MetaPreview>, title: String? = null) {
        raw[key] = items
        // VOD rows were already split by hub (their TMDB ids aren't known to the anime lists).
        val kept = if (key.startsWith(com.lekathedon.visionlite.iptv.VodArabic.PREFIX)) items.filter { !com.lekathedon.visionlite.recs.Adult.hides(it) } else keep(items)
        state.value = state.value.map { r -> if (r.key == key) r.copy(items = kept, done = true, title = title ?: r.title) else r }
    }

    private fun meta(p: Pick) = MetaPreview(p.imdb, p.type, p.name, p.poster ?: posterOf(p.imdb), backdropOf(p.imdb),
        description = listOfNotNull(p.because?.let { "Because you watched $it" }, p.why).joinToString(" · ").ifBlank { null }, releaseInfo = p.year?.toString(), imdbRating = p.rating?.let { "%.1f".format(it) }, genres = p.genres)

    /** An add-on page; when none of it is this hub's, the next pages are asked (up to 4) so the row isn't left empty. */
    private fun load(row: Row, skip: Int) {
        val addon = row.addon ?: return; val def = row.def ?: return
        loading[row.key] = viewModelScope.launch {
            var at = skip
            for (tries in 0 until 4) {
                val page = runCatching { app.client.catalog(addon, def, at) }.getOrNull()
                val before = raw[row.key].orEmpty()
                val seen = before.mapTo(HashSet()) { it.id }
                val add = page.orEmpty().filter { seen.add(it.id) }
                raw[row.key] = before + add
                val ended = page == null || add.isEmpty()
                val kept = keep(add)
                state.value = state.value.map { r -> if (r.key != row.key) r else r.copy(items = r.items + kept, done = ended) }
                if (ended || kept.isNotEmpty() || !def.supportsSkip) break
                at = raw[row.key].orEmpty().size
            }
            loading.remove(row.key)
        }
    }
}
