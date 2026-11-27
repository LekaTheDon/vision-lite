// "Who's watching?" preview (user 2026-11-27): resting on a profile without a PIN shows its latest Continue watching
// title as the backdrop and a card with how much was watched (like Legacy). Home saves the profile's newest Trakt
// Continue watching item to that profile's own folder each time it refreshes; the picker only reads the file.
// Cost: one tiny file per profile, no network at the picker (the backdrop is the image cache's metahub picture).
package com.lekathedon.visionlite.profiles

import com.lekathedon.visionlite.data.Profiles
import com.lekathedon.visionlite.trakt.Resume
import org.json.JSONObject
import java.io.File

object LastWatched {
    data class Item(val type: String, val imdb: String, val name: String, val season: Int?, val episode: Int?, val progress: Float) {
        val backdrop get() = com.lekathedon.visionlite.data.Our.backdrop(imdb, "medium")
        val logo get() = com.lekathedon.visionlite.data.Our.logo(imdb, "medium")
    }

    private fun file(id: Int) = File(Profiles.dir(id), "last_watched.json")

    fun save(r: Resume) = runCatching {
        val o = JSONObject().put("type", r.type).put("imdb", r.imdb).put("name", r.name).put("progress", r.progress.toDouble())
        r.season?.let { o.put("season", it) }; r.episode?.let { o.put("episode", it) }
        file(Profiles.currentId()).writeText(o.toString())
    }

    fun of(id: Int): Item? = runCatching {
        val f = file(id); if (!f.exists()) return null
        val o = JSONObject(f.readText())
        Item(o.getString("type"), o.getString("imdb"), o.optString("name"),
            o.optInt("season", -1).takeIf { it >= 0 }, o.optInt("episode", -1).takeIf { it >= 0 }, o.optDouble("progress", 0.0).toFloat())
    }.getOrNull()
}
