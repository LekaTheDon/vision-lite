// Speed and network (Settings › Speed and network; user 2026-11-24: "look at Arc's Performance & navigation and Network
// in Advanced"). Arc's ideas, the Lite way, all in one file:
//  - DNS: who looks up server addresses for the whole app (add-ons, images, debrid, the player). System by default; a
//    DNS-over-HTTPS service when the provider blocks or bends addresses. A service that can't be reached falls back
//    to the box's own lookup, so a bad pick never takes the app offline.
//  - Speed test: Cloudflare's speed endpoints, latency (5 small requests) and ~10 s of download.
//  - Last link test: the link you played last, read for 10 s on one connection, set against its own bitrate.
//  - Quiet accessibility: a key remapper's or launcher's accessibility service (Button Mapper, Projectivy) makes
//    Compose describe every frame to it; when those are the only services, the app stops describing (screen readers
//    turn it straight back on).
// Cost: nothing until used; DNS adds one cached lookup per new host when a service is picked.
package com.lekathedon.visionlite.data

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.lekathedon.visionlite.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** The app's DNS: the box's own, or a DNS-over-HTTPS service (pref "dns"), switched without a restart. */
object SwitchDns : Dns {
    class Service(val key: String, val name: String, val url: String, val ips: List<String>, val note: String)
    val SERVICES = listOf(
        Service("system", "System", "", emptyList(), "The box's own lookup, usually your internet provider's. The default"),
        Service("cloudflare", "Cloudflare", "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1"), "Fast, keeps no logs"),
        Service("google", "Google", "https://dns.google/dns-query", listOf("8.8.8.8", "8.8.4.4"), "Fast everywhere"),
        Service("quad9", "Quad9", "https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112"), "Blocks known harmful sites"),
        Service("adguard", "AdGuard", "https://dns.adguard-dns.com/dns-query", listOf("94.140.14.14", "94.140.15.15"),
            "Blocks ads and trackers; can stop an add-on that uses an ad or tracking address")
    )

    @Volatile private var builtFor: String? = null
    @Volatile private var doh: Dns? = null
    /** Last time the chosen service failed: the box's lookup is used for a minute before it is tried again. */
    @Volatile private var failedAt = 0L
    @Volatile var lastFailure: String? = null; private set

    private fun choice(): String = runCatching { App.instance.prefs.dns.value }.getOrDefault("system")

    private fun dohFor(key: String): Dns? {
        if (key == builtFor) return doh
        val s = SERVICES.firstOrNull { it.key == key && it.url.isNotEmpty() }
        val d = s?.let {
            // Its own small client on the box's lookup, so asking the service never loops back here.
            val boot = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build()
            DnsOverHttps.Builder().client(boot).url(it.url.toHttpUrl())
                .bootstrapDnsHosts(it.ips.map { ip -> InetAddress.getByName(ip) }).includeIPv6(false).build()
        }
        doh = d; builtFor = key; failedAt = 0
        return d
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val d = dohFor(choice()) ?: return Dns.SYSTEM.lookup(hostname)
        if (System.currentTimeMillis() - failedAt < 60_000) return Dns.SYSTEM.lookup(hostname)
        return try { d.lookup(hostname) } catch (e: Exception) {
            failedAt = System.currentTimeMillis(); lastFailure = e.message
            Dns.SYSTEM.lookup(hostname)
        }
    }

    /** Settings › Test DNS: how long the chosen lookup takes for two hosts the app uses all the time. */
    suspend fun test(): String = withContext(Dispatchers.IO) {
        val key = choice()
        val d = dohFor(key) ?: Dns.SYSTEM
        listOf("api.themoviedb.org", "v3-cinemeta.strem.io").joinToString("  ·  ") { h ->
            val t0 = System.nanoTime()
            runCatching { d.lookup(h) }.fold({ "$h ${(System.nanoTime() - t0) / 1_000_000} ms" }, { "$h: no answer" })
        }
    }
}

/** The link played last (url, headers, title, length), kept for Settings › Test last played link. */
object LastLink {
    private val file get() = File(App.instance.filesDir, "last_link.json")
    fun record(url: String, headers: Map<String, String>, title: String) = runCatching {
        if (!url.startsWith("http")) return@runCatching
        file.writeText(JSONObject().put("url", url).put("title", title).put("headers", JSONObject(headers)).toString())
    }
    fun duration(ms: Long) = runCatching {
        if (ms <= 0) return@runCatching
        val o = JSONObject(file.readText()); if (o.optLong("durationMs") != ms) file.writeText(o.put("durationMs", ms).toString())
    }
    fun read(): JSONObject? = runCatching { JSONObject(file.readText()) }.getOrNull()
}

object NetTests {
    private val client get() = Net.http.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()

    /** Latency (median of 5) and download speed (up to 10 s or 100 MB) against Cloudflare's speed test servers. */
    suspend fun speed(): String = withContext(Dispatchers.IO) {
        val c = client
        val pings = (1..5).mapNotNull {
            val t0 = System.nanoTime()
            runCatching { c.newCall(Request.Builder().url("https://speed.cloudflare.com/__down?bytes=0").build()).execute().use { it.body?.bytes() } }
                .map { (System.nanoTime() - t0) / 1_000_000 }.getOrNull()
        }.sorted()
        if (pings.isEmpty()) return@withContext "Couldn't reach the test servers"
        val mbps = read(c, Request.Builder().url("https://speed.cloudflare.com/__down?bytes=100000000").build(), 10_000)
        "Latency ${pings[pings.size / 2]} ms  ·  download ${mbps?.let { "%.0f Mbit/s".format(it) } ?: "failed"}"
    }

    /** The last played link, 10 s on one connection, against its own average bitrate (size ÷ length) when known. */
    suspend fun lastLink(): String = withContext(Dispatchers.IO) {
        val o = LastLink.read() ?: return@withContext "Nothing played yet: play something, then test its link"
        val url = o.optString("url")
        val h = o.optJSONObject("headers")
        val req = Request.Builder().url(url).header("Range", "bytes=0-").apply {
            h?.keys()?.forEach { k -> header(k, h.optString(k)) }
        }.build()
        var size = -1L
        val mbps = read(client, req, 10_000) { r -> size = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: r.body?.contentLength() ?: -1 }
            ?: return@withContext "${o.optString("title")}: the link didn't answer (it may have expired)"
        val dur = o.optLong("durationMs")
        val need = if (size > 0 && dur > 0) size * 8.0 / (dur / 1000.0) / 1_000_000 else null
        val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
        val verdict = when {
            need == null -> "its bitrate isn't known (play it a little longer first)"
            mbps >= need * 2 -> "plenty: %.0f Mbit/s average needed".format(need)
            mbps >= need * 1.2 -> "enough, little headroom: %.0f Mbit/s needed".format(need)
            else -> "too slow for it: %.0f Mbit/s needed; a smaller or closer link will play smoother".format(need)
        }
        "${o.optString("title")} · $host · ${"%.0f".format(mbps)} Mbit/s on one connection · $verdict"
    }

    private fun read(c: OkHttpClient, req: Request, ms: Long, onHead: (okhttp3.Response) -> Unit = {}): Double? = runCatching {
        c.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@runCatching null
            onHead(r)
            val src = r.body?.byteStream() ?: return@runCatching null
            val buf = ByteArray(64 * 1024)
            val t0 = System.nanoTime()
            var got = 0L
            while (true) {
                val n = src.read(buf); if (n < 0) break
                got += n
                if ((System.nanoTime() - t0) / 1_000_000 >= ms) break
            }
            val secs = (System.nanoTime() - t0) / 1e9
            if (secs <= 0.2 || got == 0L) null else got * 8 / secs / 1_000_000
        }
    }.getOrNull()
}

/**
 * Quiet accessibility (Arc's "Smoother with launcher services"): true when accessibility is on only for key remappers /
 * launchers that never read the app's screen. Then the app's screen isn't described to them (smoother scrolling).
 */
object QuietA11y {
    /** Services known to use accessibility only for keys or their own windows. Add one only when known. */
    private val KEY_ONLY = setOf("flar2.homebutton", "com.spocky.projengmenu")

    fun services(ctx: Context): List<String> {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return emptyList()
        if (!am.isEnabled) return emptyList()
        return (am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).mapNotNull { it.resolveInfo?.serviceInfo?.packageName } +
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .split(':').map { it.substringBefore('/').trim() }.filter { it.isNotEmpty() }).distinct()
    }

    fun quiet(ctx: Context): Boolean {
        if (!App.instance.prefs.quietA11y.value) return false
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        if (!am.isEnabled || am.isTouchExplorationEnabled) return false
        val s = services(ctx)
        return s.isNotEmpty() && s.all { it in KEY_ONLY }
    }

    /** Names for Settings ("Button Mapper"), or the package when the label isn't known. */
    fun labels(ctx: Context): List<String> = services(ctx).map { p ->
        runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p) +
            if (p in KEY_ONLY) "" else " (reads the screen)"
    }
}
