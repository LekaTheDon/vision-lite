package com.lekathedon.visionlite.library

import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.data.Net
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable data class Saved(val type: String, val id: String, val name: String, val poster: String? = null)

/** Local saves (no account needed), newest first, kept in prefs as JSON. */
object Saves {
    private val ser = ListSerializer(Saved.serializer())
    fun all(): List<Saved> = runCatching { Net.json.decodeFromString(ser, App.instance.prefs.saved.value) }.getOrDefault(emptyList())
    fun has(id: String) = all().any { it.id == id }
    fun toggle(s: Saved): Boolean {
        val list = all()
        val on = list.none { it.id == s.id }
        App.instance.prefs.saved.value = Net.json.encodeToString(ser, if (on) listOf(s) + list else list.filterNot { it.id == s.id })
        return on
    }
}
