package com.lekathedon.visionlite.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.addons.Addon
import com.lekathedon.visionlite.addons.CatalogDef
import com.lekathedon.visionlite.addons.MetaPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class Row(val key: String, val title: String, val addon: Addon, val def: CatalogDef, val items: List<MetaPreview>, val done: Boolean)

/**
 * Home rows = every add-on catalog that needs no extra (installed order). A row's first page loads only when the row
 * comes near the screen ([need]); more pages only when focus nears a row's end ([more]).
 */
class HomeModel : ViewModel() {
    private val app = App.instance
    private val state = MutableStateFlow<List<Row>>(emptyList())
    val rows: StateFlow<List<Row>> get() = state
    private val loading = HashMap<String, Job>()

    init {
        viewModelScope.launch {
            app.addons.ensureDefaults()
            app.addons.addons.collect { list -> rebuild(list) }
        }
    }

    private fun rebuild(list: List<Addon>) {
        loading.values.forEach { it.cancel() }; loading.clear()
        val old = state.value.associateBy { it.key }
        state.value = list.flatMap { a ->
            a.manifest.catalogs.filter { it.isBoardRow }.map { c ->
                val key = "${a.manifest.id}/${c.type}/${c.id}"
                old[key] ?: Row(key, title(a, c), a, c, emptyList(), done = false)
            }
        }
    }

    private fun title(a: Addon, c: CatalogDef): String {
        val type = when (c.type) { "movie" -> "Movies"; "series" -> "Series"; else -> c.type.replaceFirstChar { it.uppercase() } }
        val name = c.name ?: c.id
        return if (name.contains(type, ignoreCase = true)) name else "$name $type"
    }

    fun need(key: String) {
        val row = state.value.firstOrNull { it.key == key } ?: return
        if (row.items.isNotEmpty() || row.done || loading.containsKey(key)) return
        load(row, 0)
    }

    fun more(key: String) {
        val row = state.value.firstOrNull { it.key == key } ?: return
        if (row.done || !row.def.supportsSkip || loading.containsKey(key)) return
        load(row, row.items.size)
    }

    private fun load(row: Row, skip: Int) {
        loading[row.key] = viewModelScope.launch {
            val page = runCatching { app.client.catalog(row.addon, row.def, skip) }.getOrNull()
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
