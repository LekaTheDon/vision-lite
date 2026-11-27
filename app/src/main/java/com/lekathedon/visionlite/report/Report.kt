package com.lekathedon.visionlite.report

import android.content.Context
import android.os.Build
import android.view.Display
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.BuildConfig
import com.lekathedon.visionlite.device.DeviceCheck
import com.lekathedon.visionlite.device.Tier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * A problem report: what happened (the user's words), the device (box, TV, soundbar, memory class), Android's record
 * of how the app ended the last times (crash / froze / memory), saved crashes, the settings (no keys or logins), and
 * the app's own log (Android lets an app read only its own lines). Keys, tokens, logins and personal links are
 * blanked before it leaves the box.
 */
object Report {
    data class Built(val id: String, val text: String, val logLines: Int)

    private const val LOG_LINES = 5000

    suspend fun build(context: Context, what: String, note: String): Built = withContext(Dispatchers.IO) {
        val id = java.util.UUID.randomUUID().toString().take(8)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
        val log = logcat()
        val text = buildString {
            appendLine("=== Vision problem report $id ===")
            appendLine("What: $what")
            if (note.isNotBlank()) appendLine("Note: ${note.trim()}")
            appendLine("When: ${fmt.format(Date())}")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${abi(context)}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE} / ${Build.PRODUCT}) · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("SoC: ${if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE} · board ${Build.BOARD} · ABIs ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Class: ${Tier.summary()}")
            appendLine("Memory now: ${runCatching { Mem.line(context) }.getOrDefault("?")}")
            appendLine("Display: ${display(context)}")
            appendLine("Build: ${Build.FINGERPRINT}")

            appendLine(); appendLine("--- How the app ended the last times (Android's record) ---")
            val exits = Problems.exits(context)
            if (exits.isEmpty()) appendLine(if (Build.VERSION.SDK_INT < 30) "(Android 11+ only)" else "(none recorded)")
            exits.forEach { e ->
                if (Build.VERSION.SDK_INT >= 30) appendLine("${fmt.format(Date(e.timestamp))} · ${Problems.reasonName(e.reason)} · importance ${e.importance} · " +
                    "pss ${e.pss / 1024}MB rss ${e.rss / 1024}MB · ${e.description ?: ""}")
            }
            // The newest freeze's thread dump (where the app was stuck).
            if (Build.VERSION.SDK_INT >= 30) exits.firstOrNull { it.reason == android.app.ApplicationExitInfo.REASON_ANR }?.let { anr ->
                runCatching { anr.traceInputStream?.bufferedReader()?.useLines { l -> l.take(400).toList() } }.getOrNull()?.let {
                    appendLine(); appendLine("--- Freeze (ANR) trace, ${fmt.format(Date(anr.timestamp))} ---"); it.forEach(::appendLine)
                }
            }

            appendLine(); appendLine("--- Saved crashes ---")
            val crashes = Crash.saved(context).take(3)
            if (crashes.isEmpty()) appendLine("(none)")
            crashes.forEach { appendLine(runCatching { it.readText().take(20_000) }.getOrDefault("")); appendLine() }

            appendLine("--- This TV check ---")
            appendLine(App.instance.prefs.tvFacts.value.ifBlank { "(not run)" })

            appendLine(); appendLine("--- Settings (keys, logins and lists left out) ---")
            settings().forEach { appendLine(it) }

            appendLine(); appendLine("--- Log (${log.size} lines, this app only) ---")
            log.forEach(::appendLine)
        }
        Built(id, redact(text), log.size)
    }

    private fun abi(context: Context): String =
        "APK " + (context.applicationInfo.nativeLibraryDir?.substringAfterLast('/') ?: "?") + " · " + if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "64-bit" else "32-bit"

    private fun display(context: Context): String = runCatching {
        val d = context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val m = d.mode
        @Suppress("DEPRECATION")
        val hdr = d.hdrCapabilities?.supportedHdrTypes?.joinToString { when (it) { 1 -> "DV"; 2 -> "HDR10"; 3 -> "HLG"; 4 -> "HDR10+"; else -> "$it" } }
        "${m.physicalWidth}x${m.physicalHeight}@${"%.3f".format(m.refreshRate)} · HDR [${hdr ?: "?"}] · ${d.supportedModes.size} modes"
    }.getOrDefault("?")

    /** The app's own log lines (Android 4.1+ gives an app only its own): the last [LOG_LINES], previous runs included. */
    private fun logcat(): List<String> = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", LOG_LINES.toString()).redirectErrorStream(true).start()
        val out = ArrayList<String>(LOG_LINES)
        val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { if (out.size < LOG_LINES + 100) out.add(it) } } }.apply { start() }
        if (!p.waitFor(8, TimeUnit.SECONDS)) p.destroy()
        reader.join(1_000)
        synchronized(out) { out.toList() }
    }.getOrDefault(listOf("(log could not be read)"))

    // ---- Keeping keys and personal details out ----

    private val SECRET_KEY = Regex("key|token|secret|pass|cred|auth|cookie|session|login|playlist|pin|mail|user|account|xtream|m3u|server|url|addon|imported|bells|phone_|recordings|saved|people|favs|recent|follows|picks|marks|feedback|dropped|tokens", RegexOption.IGNORE_CASE)

    /** Every setting: switches and short choices as they are; anything that could hold a key, login, URL or list shows only whether it's set. */
    private fun settings(): List<String> = App.instance.prefs.sp.all.toSortedMap().map { (k, v) ->
        when {
            v is Boolean || v is Int || v is Long || v is Float -> "$k = $v"
            v is String && !SECRET_KEY.containsMatchIn(k) && v.length <= 40 -> "$k = $v"
            v is String -> "$k = ${if (v.isBlank() || v == "[]" || v == "{}") "(empty)" else "(set, ${v.length} chars)"}"
            else -> "$k = (${v?.javaClass?.simpleName})"
        }
    }

    /** Every stored key/login value (and its long pieces), to blank them wherever they appear in the log. */
    private fun storedSecrets(): List<String> {
        val out = HashSet<String>()
        App.instance.prefs.sp.all.forEach { (k, v) ->
            if (v !is String || !SECRET_KEY.containsMatchIn(k) || v.isBlank()) return@forEach
            if (v.length in 8..300 && v.none { it.isWhitespace() } && !v.startsWith("[") && !v.startsWith("{")) out += v
            Regex("[A-Za-z0-9._~+/=-]{12,}").findAll(v).forEach { out += it.value }
        }
        listOf(BuildConfig.TRAKT_ID, BuildConfig.TRAKT_SECRET, BuildConfig.SIMKL_CLIENT_ID, BuildConfig.ANILIST_CLIENT_ID,
            BuildConfig.MAL_CLIENT_ID, BuildConfig.MAL_CLIENT_SECRET, BuildConfig.NUVIO_KEY).filter { it.length >= 8 }.forEach { out += it }
        // Common words and hosts would blank half the log: only things that look like keys.
        return out.filter { s -> s.length >= 12 && s.any { it.isDigit() } && !s.startsWith("http") }.sortedByDescending { it.length }
    }

    private val RULES = listOf(
        // ?key=… &token=… &password=… &username=…
        Regex("(?i)([?&;](?:api_?key|apikey|key|token|access_token|refresh_token|id_token|password|pass|pwd|username|user|auth|sig|signature|secret|client_secret|code|session)=)[^&\\s\"'<>]+") to "$1***",
        Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+") to "$1***",
        Regex("(?i)(authorization[\"']?\\s*[:=]\\s*[\"']?)(?!bearer\\b)[A-Za-z0-9._~+/=-]+") to "$1***",
        // http://user:pass@host
        Regex("(?i)(https?://)[^/\\s:@\"']+:[^/\\s@\"']+@") to "$1***:***@",
        // Xtream: /live/<user>/<pass>/…, /movie/…, /series/…, /timeshift/…
        Regex("(?i)(https?://[^\\s/\"']+/(?:live|movie|series|timeshift)/)[^/\\s\"']+/[^/\\s\"']+/") to "$1***/***/",
        // Add-on configs and debrid keys inside link paths: long path pieces (torrent hashes kept: 40 hex)
        Regex("(?<=/)(?![a-fA-F0-9]{40}(?:[/?\\s\"']|$))[A-Za-z0-9%._~|,=+-]{32,}(?=[/?\\s\"']|$)") to "***",
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}") to "***@***",
    )

    fun redact(text: String): String {
        var t = text
        for (s in storedSecrets()) t = t.replace(s, "***")
        for ((re, to) in RULES) t = re.replace(t, to)
        return t
    }

    /** Short title for the report ("Wouldn't play · TCL Smart TV · 0.23.0"). */
    fun title(what: String): String = "$what · ${Build.MANUFACTURER} ${Build.MODEL} · ${BuildConfig.VERSION_NAME}"

}
