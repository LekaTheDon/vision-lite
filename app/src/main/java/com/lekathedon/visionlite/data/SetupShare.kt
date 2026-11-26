package com.lekathedon.visionlite.data

import android.util.Base64
import com.lekathedon.visionlite.App
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Setup code (user 2026-11-26): a friend's box gets exactly this profile's add-ons, Vision TB sources and indexers
 * (which are on, add-on roles, link filters) and nothing else: no keys, no sign-ins, no watch history, no IPTV.
 * The friend then signs in to their own Trakt / TorBox / APIs. Add-on links that carry a debrid account inside them
 * (AIOStreams and similar configured links) are copied as they are, so they keep using that account.
 * Code = "VL1:" + base64(gzip(JSON)), a few KB, pasted on the friend's box by phone (Settings › Add-ons).
 * Cost: nothing until used.
 */
object SetupShare {
    private val STRINGS = listOf("addons", "tb_addons", "max_quality", "size_limit", "leave_out")
    private val SETS = listOf("addons_off", "tb_off", "addon_no_rows", "addon_no_meta", "addon_no_links")
    private val FLAGS = listOf("vision_tb", "hide_torrents", "hide_cam")

    fun export(): String {
        val sp = App.instance.prefs.sp
        val o = JSONObject().put("v", 1)
        STRINGS.forEach { k -> sp.getString(k, null)?.let { o.put(k, it) } }
        SETS.forEach { k -> sp.getStringSet(k, null)?.let { o.put(k, JSONArray(it.toList())) } }
        FLAGS.forEach { k -> if (sp.contains(k)) o.put(k, sp.getBoolean(k, false)) }
        val bytes = ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(o.toString().toByteArray()) } }.toByteArray()
        val code = "VL1:" + Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
        // Also left where Claude / the phone link can pick it up (the app's own folder, not shared storage).
        runCatching { java.io.File(App.instance.getExternalFilesDir(null), "setup-code.txt").writeText(code) }
        return code
    }

    /** Applies a code; returns what came in ("3 add-ons, 21 sources") or throws on a bad code. Restart to see it everywhere. */
    fun import(code: String): String {
        val raw = code.trim().removePrefix("VL1:")
        val json = GZIPInputStream(ByteArrayInputStream(Base64.decode(raw, Base64.URL_SAFE or Base64.NO_WRAP))).bufferedReader().use { it.readText() }
        val o = JSONObject(json)
        require(o.optInt("v") == 1) { "not a Vision Lite setup code" }
        val e = App.instance.prefs.sp.edit()
        STRINGS.forEach { k -> if (o.has(k)) e.putString(k, o.getString(k)) }
        SETS.forEach { k -> o.optJSONArray(k)?.let { a -> e.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) } }
        FLAGS.forEach { k -> if (o.has(k)) e.putBoolean(k, o.getBoolean(k)) }
        e.putBoolean("addons_seeded", true).commit()
        val addons = runCatching { JSONArray(o.optString("addons", "[]")).length() }.getOrDefault(0)
        val tb = runCatching { JSONArray(o.optString("tb_addons", "[]")).length() }.getOrDefault(0)
        return "$addons add-ons, $tb Vision TB add-ons and your source choices"
    }
}
