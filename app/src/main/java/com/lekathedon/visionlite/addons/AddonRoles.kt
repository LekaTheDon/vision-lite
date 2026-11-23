package com.lekathedon.visionlite.addons

import com.lekathedon.visionlite.App

/**
 * What each add-on is used for in this profile (user 2026-11-23): Rows (Home rows, search, discover), Details (titles,
 * plots, posters, episodes) and Links (its own link group). Aradeb, say: rows and Arabic details on, links off when
 * Vision TB and VOD play them. All on by default; per profile (each profile has its own add-ons).
 */
object AddonRoles {
    enum class Role(val label: String, val pref: String, val resources: Set<String>) {
        ROWS("Rows", "addon_no_rows", setOf("catalog")),
        DETAILS("Details", "addon_no_meta", setOf("meta")),
        LINKS("Links", "addon_no_links", setOf("stream"))
    }

    private val sp get() = App.instance.prefs.sp
    private fun off(r: Role): Set<String> = sp.getStringSet(r.pref, emptySet()).orEmpty()

    fun isOn(base: String, r: Role) = base !in off(r)
    fun set(base: String, r: Role, on: Boolean) {
        sp.edit().putStringSet(r.pref, if (on) off(r) - base else off(r) + base).apply()
        App.instance.addons.touch()
    }

    fun allows(base: String, resource: String): Boolean = Role.values().none { resource in it.resources && base in off(it) }
}
