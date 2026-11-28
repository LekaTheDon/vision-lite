package com.lekathedon.visionlite.tb

import com.lekathedon.visionlite.App
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Vision TB as the teacher (user 2026-11-28): one line per episode/film press in files/reports/tb-teacher.jsonl with
 * what each source matched (before the TorBox check) and what was shown (cached, with its file), so the server's
 * Vision add-on (the student) can be graded against it until both return the same. No request, no key, no link URL:
 * hashes, names, files, sizes only. Last 40 presses kept, about 6 MB at most (a film can match 1,200 releases).
 */
internal class Teacher(private val type: String, private val id: String) {
    private val matched = JSONObject()
    private val shown = JSONObject()
    @Volatile private var target: DiscoveryTarget? = null

    fun matched(src: String, rows: Int, rel: List<DiscoveredRelease>) = synchronized(this) {
        val o = matched.optJSONObject(src) ?: JSONObject().put("rows", 0).put("r", JSONObject()).also { matched.put(src, it) }
        o.put("rows", o.optInt("rows") + rows)
        val r = o.getJSONObject("r")
        rel.forEach { x ->
            if (!r.has(x.hash)) r.put(x.hash, JSONArray().put(x.match.reason).put(x.name.take(90)))
        }
    }

    fun shown(src: String, list: List<VerifiedRelease>, t: DiscoveryTarget) = synchronized(this) {
        target = t
        val a = shown.optJSONArray(src) ?: JSONArray().also { shown.put(src, it) }
        list.forEach { v -> a.put(JSONObject().put("h", v.release.hash).put("n", v.release.name)
            .put("f", v.file?.path ?: JSONObject.NULL).put("s", v.sortSize ?: JSONObject.NULL).put("4k", v.is4k)) }
    }

    fun save(ms: Long) = synchronized(this) {
        val t = target
        val line = JSONObject().put("at", System.currentTimeMillis()).put("type", type).put("id", id).put("ms", ms)
            .put("target", t?.let { JSONObject().put("imdb", it.imdbId).put("s", it.season ?: JSONObject.NULL).put("e", it.episode ?: JSONObject.NULL)
                .put("titles", JSONArray(it.titles)).put("year", it.year ?: JSONObject.NULL).put("anime", it.isAnime)
                .put("animeTitles", JSONArray(it.animeTitles)) } ?: JSONObject.NULL)
            .put("matched", matched).put("shown", shown).toString()
        val f = File(File(App.instance.filesDir, "reports").apply { mkdirs() }, "tb-teacher.jsonl")
        val old = if (f.exists()) f.readLines().takeLast(39) else emptyList()
        f.writeText((old + line).joinToString("\n", postfix = "\n"))
    }
}
