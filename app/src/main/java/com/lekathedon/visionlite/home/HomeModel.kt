package com.lekathedon.visionlite.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lekathedon.visionlite.App
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

enum class RowKind { POSTER, COMING, SERVICES, PEOPLE }

/** A Home row: Vision's own (addon == null) or an add-on catalog. */
data class Row(
    val key: String, val title: String, val addon: Addon?, val def: CatalogDef?, val items: List<MetaPreview>, val done: Boolean,
    val kind: RowKind = RowKind.POSTER
) {
    val builtIn get() = addon == null
    /** Where it comes from, for Settings › Home rows. */
    val source get() = addon?.manifest?.name ?: "Vision"
}

/**
 * Home rows = Vision's rows (Coming up, Vision Picks, IMDb Picks, AI Picks, Streaming services, Favourite people,
 * the charts) and, when switched on, every add-on catalog that needs no extra. A row loads only when it comes near
 * the screen ([need]); add-on rows ask more pages only when focus nears their end ([more]).
 */
class HomeModel : ViewModel() {
    private val app = App.instance
    private val state = MutableStateFlow<List<Row>>(emptyList())
    val rows: StateFlow<List<Row>> get() = state
    private val loading = HashMap<String, Job>()
    private val needed = HashSet<String>()
    private val cwState = MutableStateFlow<List<com.lekathedon.visionlite.trakt.Resume>>(emptyList())
    /** Trakt Continue Watching (empty when not connected). */
    val continueWatching: StateFlow<List<com.lekathedon.visionlite.trakt.Resume>> get() = cwState
    private var cwAt = 0L
    private var cwJob: Job? = null

    /** Hold menu's Drop: gone from the row at once; Trakt is told in the background. */
    fun dropContinue(r: com.lekathedon.visionlite.trakt.Resume) {
        cwState.value = cwState.value.filter { it.imdb != r.imdb }
        viewModelScope.launch { runCatching { app.trakt.drop(r.imdb, r.type == "series") } }
    }

    /** Asked when Home shows; at most every 30 s (coming back from the player always refreshes via [force]). */
    fun refreshContinue(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (app.trakt.connected.value == null) { cwState.value = emptyList(); return }
        if (!force && now - cwAt < 30_000) return
        cwAt = now
        cwJob?.cancel()
        cwJob = viewModelScope.launch { cwState.value = runCatching { app.trakt.continueWatching() }.getOrDefault(cwState.value) }
    }

    init {
        viewModelScope.launch {
            app.addons.ensureDefaults()
            combine(app.addons.addons, app.prefs.rowOrder.flow, app.prefs.addonRows.flow, app.prefs.importedLists.flow) { list, _, _, _ -> list }
                .collect { list -> rebuild(list) }
        }
        // Vision's rows follow their sources: anything new there shows on Home at once.
        viewModelScope.launch { combine(Recs.pool, Recs.changed) { _, _ -> }.collect { fill(VisionRows.PICKS); fill(VisionRows.IMDB) } }
        viewModelScope.launch { AiPicks.picks.collect { fill(VisionRows.AI) } }
        viewModelScope.launch { AiPicks.status.collect { fill(VisionRows.AI) } }
        viewModelScope.launch { ComingUp.items.collect { fill(VisionRows.COMING_UP) } }
        viewModelScope.launch { Services.art.collect { fill(VisionRows.SERVICES) } }
        viewModelScope.launch { People.list.collect { fill(VisionRows.PEOPLE) } }
    }

    /** Refresh: forget every loaded page and ask again as rows come into view. */
    fun refreshAll() {
        loading.values.forEach { it.cancel() }; loading.clear(); needed.clear()
        app.client.clearCatalogs()
        state.value = state.value.map { if (it.builtIn) it else it.copy(items = emptyList(), done = false) }
        ComingUp.refresh(true)
    }

    private fun rebuild(list: List<Addon>) {
        state.value.filter { !it.builtIn }.forEach { loading.remove(it.key)?.cancel() }
        val old = state.value.associateBy { it.key }
        val order = app.prefs.rowOrder.value.split('\n').filter { it.isNotEmpty() }.withIndex().associate { it.value to it.index }
        val vision = VisionRows.all.map { (key, title) ->
            old[key] ?: Row(key, title, null, null, emptyList(), done = false, kind = when (key) {
                VisionRows.COMING_UP -> RowKind.COMING; VisionRows.SERVICES -> RowKind.SERVICES; VisionRows.PEOPLE -> RowKind.PEOPLE; else -> RowKind.POSTER
            })
        }
        // Lists added from the phone: after Vision's rows, before the add-ons.
        val imported = ImportedLists.all().map { l -> old[l.key] ?: Row(l.key, l.name, null, null, emptyList(), done = false) }
        val addons = if (!app.prefs.addonRows.value) emptyList() else list.flatMap { a ->
            a.manifest.catalogs.filter { it.isBoardRow }.map { c ->
                val key = "${a.manifest.id}/${c.type}/${c.id}"
                old[key] ?: Row(key, title(a, c), a, c, emptyList(), done = false)
            }
        }
        state.value = (vision + imported + addons).withIndex().sortedBy { (i, r) -> order[r.key] ?: (order.size + i) }.map { it.value }
    }

    private fun title(a: Addon, c: CatalogDef): String {
        val type = when (c.type) { "movie" -> "Movies"; "series" -> "Series"; else -> c.type.replaceFirstChar { it.uppercase() } }
        val name = c.name ?: c.id
        return if (name.contains(type, ignoreCase = true)) name else "$name $type"
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
        load(row, row.items.size)
    }

    /** The Refresh tile at the end of a Picks row. */
    fun refreshPicks(key: String) {
        when (key) {
            VisionRows.AI -> viewModelScope.launch { AiPicks.refreshIfDue(force = true) }
            else -> Recs.rebuildNow()
        }
    }

    /** A Vision row's first look: its source is asked to refresh when due (each keeps its own clock). */
    private fun start(row: Row) {
        val key = row.key
        when (key) {
            VisionRows.COMING_UP -> ComingUp.onHome()
            VisionRows.PICKS, VisionRows.IMDB -> viewModelScope.launch { runCatching { Recs.refreshIfDue() } }
            VisionRows.AI -> viewModelScope.launch { runCatching { AiPicks.refreshIfDue() } }
            VisionRows.SERVICES -> viewModelScope.launch { runCatching { Services.refresh() } }
            VisionRows.PEOPLE -> {}
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
            VisionRows.PICKS -> Recs.forYou(30).map(::meta)
            VisionRows.IMDB -> Recs.imdbPicks(30).map(::meta)
            VisionRows.AI -> AiPicks.picks.value.map(::meta)
            VisionRows.SERVICES -> if (!Services.on()) emptyList() else Services.S.entries.map { s ->
                val art = Services.art.value.firstOrNull { it.id == s.id }
                MetaPreview("svc:${s.id}", "service", s.title, null, Tmdb.img(art?.backdrop, "w780"), Tmdb.img(art?.logo, "w300"))
            }
            VisionRows.PEOPLE -> People.list.value.map { MetaPreview(it.id, "person", it.name, it.photo) }
            else -> return
        }
        set(key, items)
    }

    private fun set(key: String, items: List<MetaPreview>) {
        state.value = state.value.map { r -> if (r.key == key) r.copy(items = items, done = true) else r }
    }

    private fun meta(p: Pick) = MetaPreview(p.imdb, p.type, p.name, p.poster ?: posterOf(p.imdb), backdropOf(p.imdb),
        description = p.why, releaseInfo = p.year?.toString(), imdbRating = p.rating?.let { "%.1f".format(it) }, genres = p.genres)

    private fun load(row: Row, skip: Int) {
        val addon = row.addon ?: return; val def = row.def ?: return
        loading[row.key] = viewModelScope.launch {
            val page = runCatching { app.client.catalog(addon, def, skip) }.getOrNull()
            state.value = state.value.map { r ->
                if (r.key != row.key) r
                else {
                    val seen = r.items.mapTo(HashSet()) { it.id }
                    val add = page.orEmpty().filter { seen.add(it.id) }
                    r.copy(items = r.items + add, done = page == null || add.isEmpty())
                }
            }
            loading.remove(row.key)
        }
    }
}
