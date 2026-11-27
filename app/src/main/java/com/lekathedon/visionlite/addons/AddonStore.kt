package com.lekathedon.visionlite.addons

import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import java.net.URLEncoder

/** Installed add-ons, in the user's order, saved as JSON. Cinemeta is there from the start for titles and details. */
class AddonStore(private val prefs: Prefs) {
    /** Every installed add-on (Settings › Add-ons); [addons] is the ones switched on, which everything else uses. */
    private val everything = MutableStateFlow(load())
    val all: StateFlow<List<Addon>> get() = everything
    private var off: Set<String> = prefs.sp.getStringSet(KEY_OFF, emptySet()).orEmpty().toSet()
    private val state = MutableStateFlow(everything.value.filter { it.base !in off })
    val addons: StateFlow<List<Addon>> get() = state

    fun isOn(base: String) = base !in off

    /** A role changed (AddonRoles): everything reading the add-on list redraws. */
    fun touch() { versionFlow.value++ }
    private val versionFlow = MutableStateFlow(0)
    /** Bumped when an add-on's roles change (the list itself stays equal). */
    val version: StateFlow<Int> get() = versionFlow

    /** Off = kept installed, but asked for nothing (no rows, links, search or subtitles). */
    fun setOn(base: String, on: Boolean) {
        off = if (on) off - base else off + base
        prefs.sp.edit().putStringSet(KEY_OFF, off).apply()
        state.value = everything.value.filter { it.base !in off }
    }

    private fun load(): List<Addon> = runCatching {
        prefs.sp.getString(KEY, null)?.let { Net.json.decodeFromString(ListSerializer(Addon.serializer()), it) }
    }.getOrNull()?.map { a ->
        // Cinemeta saved before our server existed: ask through ours (same add-on, same answers).
        if (com.lekathedon.visionlite.data.Our.on && a.base.trimEnd('/') == "https://v3-cinemeta.strem.io") a.copy(base = CINEMETA) else a
    } ?: emptyList()

    private fun save(list: List<Addon>) {
        everything.value = list
        state.value = list.filter { it.base !in off }
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
        val old = everything.value
        val at = old.indexOfFirst { it.base == base || it.manifest.id == manifest.id }
        // Reinstalling keeps its place in the order.
        save(if (at >= 0) old.toMutableList().apply { removeAll { it.base == base || it.manifest.id == manifest.id }; add(at.coerceAtMost(size), addon) } else old + addon)
        return addon
    }

    fun remove(base: String) { save(everything.value.filterNot { it.base == base }); if (base in off) setOn(base, true) }

    fun move(base: String, by: Int) {
        val list = everything.value.toMutableList()
        val i = list.indexOfFirst { it.base == base }
        val j = (i + by).coerceIn(0, list.lastIndex)
        if (i < 0 || i == j) return
        list.add(j, list.removeAt(i))
        save(list)
    }

    companion object {
        private const val KEY = "addons"
        private const val KEY_SEEDED = "addons_seeded"
        private const val KEY_OFF = "addons_off"
        val CINEMETA get() = com.lekathedon.visionlite.data.Our.cinemeta

        fun normalize(url: String): String = url.trim()
            .replaceFirst(Regex("^stremio://"), "https://")
            .removeSuffix("/")
            .removeSuffix("/manifest.json")
            .removeSuffix("/")

        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}
