package com.lekathedon.visionlite.addons

import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import java.net.URLEncoder

/** Installed add-ons, in the user's order, saved as JSON. Cinemeta is there from the start for titles and details. */
class AddonStore(private val prefs: Prefs) {
    private val state = MutableStateFlow(load())
    val addons: StateFlow<List<Addon>> get() = state

    private fun load(): List<Addon> = runCatching {
        prefs.sp.getString(KEY, null)?.let { Net.json.decodeFromString(ListSerializer(Addon.serializer()), it) }
    }.getOrNull() ?: emptyList()

    private fun save(list: List<Addon>) {
        state.value = list
        prefs.sp.edit().putString(KEY, Net.json.encodeToString(ListSerializer(Addon.serializer()), list)).apply()
    }

    /** First start: Cinemeta, so Home has rows before any add-on is added. */
    suspend fun ensureDefaults() {
        if (state.value.isNotEmpty() || prefs.sp.getBoolean(KEY_SEEDED, false)) return
        runCatching { install(CINEMETA) }
        prefs.sp.edit().putBoolean(KEY_SEEDED, true).apply()
    }

    /** Accepts "…/manifest.json", the base URL, or stremio:// links. */
    suspend fun install(url: String): Addon {
        val base = normalize(url)
        val manifest = Net.getJson<Manifest>("$base/manifest.json", timeoutSec = 15)
        val addon = Addon(base, manifest)
        save(state.value.filterNot { it.base == base || it.manifest.id == manifest.id } + addon)
        return addon
    }

    fun remove(base: String) = save(state.value.filterNot { it.base == base })

    fun move(base: String, by: Int) {
        val list = state.value.toMutableList()
        val i = list.indexOfFirst { it.base == base }
        val j = (i + by).coerceIn(0, list.lastIndex)
        if (i < 0 || i == j) return
        list.add(j, list.removeAt(i))
        save(list)
    }

    companion object {
        private const val KEY = "addons"
        private const val KEY_SEEDED = "addons_seeded"
        const val CINEMETA = "https://v3-cinemeta.strem.io"

        fun normalize(url: String): String = url.trim()
            .replaceFirst(Regex("^stremio://"), "https://")
            .removeSuffix("/")
            .removeSuffix("/manifest.json")
            .removeSuffix("/")

        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}
