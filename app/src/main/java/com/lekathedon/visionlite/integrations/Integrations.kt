package com.lekathedon.visionlite.integrations

import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.data.Prefs
import com.lekathedon.visionlite.phone.PhoneForm.Field
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Every integration and tracker in one place. Each group has one driver (the service the app takes its answer
 * from) and helpers (the other set-up services, each On or Off). A service that is Off, or not set up, makes no
 * calls. Keys stay on this TV (entered from the phone), never in the repo.
 */
object Integrations {
    enum class Group(val title: String, val driverDoes: String, val helperDoes: String) {
        LINKS("Debrid (links)", "Its cached links come first", "Also asked for cached links"),
        TRACKING("Tracking", "Continue Watching, Library and watched marks come from it", "Also gets your plays and watched marks"),
        ANIME("Anime tracking", "Anime progress comes from it", "Also gets anime progress"),
        DATA("Details and ratings", "Details and ratings come from it", "Fills what the driver lacks"),
        AI("AI", "Asked first", "Asked when the driver fails"),
        EXTRAS("Extras", "", ""),
    }

    /**
     * [live] = what it does in Lite today; null = not in Lite yet (its key is kept for when it arrives, it makes
     * no calls and can't drive). [signIn] = a sign-in page instead of key fields.
     */
    class Service(
        val id: String, val name: String, val group: Group, val live: String?, val where: String,
        val fields: List<Field> = emptyList(), val signIn: Boolean = false,
        val check: (suspend (List<String>) -> Boolean)? = null,
    )

    val all: List<Service> = listOf(
        Service("torbox", "TorBox", Group.LINKS, "Vision TB: TorBox-cached links first", "torbox.app → Settings → API key",
            listOf(Field("TorBox API key", secret = true))) { v -> ok(get("https://api.torbox.app/v1/api/user/me", bearer = v[0])) },
        Service("realdebrid", "Real-Debrid", Group.LINKS, null, "real-debrid.com/apitoken",
            listOf(Field("Real-Debrid API token", secret = true))) { v -> ok(get("https://api.real-debrid.com/rest/1.0/user", bearer = v[0])) },
        Service("premiumize", "Premiumize", Group.LINKS, null, "premiumize.me/account → API key",
            listOf(Field("Premiumize API key", secret = true))) { v ->
            get("https://www.premiumize.me/api/account/info?apikey=${enc(v[0])}")?.second?.contains("\"success\"") == true },

        Service("trakt", "Trakt", Group.TRACKING, "Continue Watching, Library, scrobbling, resume", "Sign in with the QR", signIn = true),
        Service("simkl", "Simkl", Group.TRACKING, null, "Sign in: scan, approve on simkl.com", signIn = true),
        Service("imdb", "IMDb", Group.TRACKING, null, "Sign-in comes with IMDb sync"),

        Service("anilist", "AniList", Group.ANIME, null, "Sign in: scan, approve on anilist.co, paste once", signIn = true),
        Service("mal", "MyAnimeList", Group.ANIME, null, "Sign in: scan, approve on myanimelist.net, paste once", signIn = true),

        Service("tmdb", "TMDB", Group.DATA, "Top 20 Today rows, Streaming services", "themoviedb.org/settings/api → API key or read token",
            listOf(Field("TMDB API key", secret = true))) { v ->
            val k = v[0]
            ok(if (k.length > 40) get("https://api.themoviedb.org/3/configuration", bearer = k) else get("https://api.themoviedb.org/3/configuration?api_key=${enc(k)}")) },
        Service("tvdb", "TVDB", Group.DATA, null, "thetvdb.com/dashboard/account/apikey",
            listOf(Field("TVDB API key", secret = true))) { v ->
            ok(post("https://api4.thetvdb.com/v4/login", org.json.JSONObject().put("apikey", v[0]).toString())) },
        Service("mdblist", "MDBList", Group.DATA, null, "mdblist.com/preferences → API key (ratings and list sync, one entry)",
            listOf(Field("MDBList API key", secret = true))) { v -> ok(get("https://api.mdblist.com/user?apikey=${enc(v[0])}")) },

        Service("gemini", "Gemini", Group.AI, "AI Picks", "aistudio.google.com/apikey (free tier)",
            listOf(Field("Gemini API key", secret = true))) { v -> ok(get("https://generativelanguage.googleapis.com/v1beta/models?key=${enc(v[0])}")) },
        Service("groq", "Groq", Group.AI, "AI Picks", "console.groq.com/keys (free tier)",
            listOf(Field("Groq API key", secret = true))) { v -> ok(get("https://api.groq.com/openai/v1/models", bearer = v[0])) },
        Service("mistral", "Mistral", Group.AI, "AI Picks", "console.mistral.ai/api-keys (free tier)",
            listOf(Field("Mistral API key", secret = true))) { v -> ok(get("https://api.mistral.ai/v1/models", bearer = v[0])) },

        Service("youtube", "YouTube", Group.EXTRAS, null, "Sign-in comes with YouTube"),
    )

    fun byId(id: String) = all.first { it.id == id }
    fun inGroup(g: Group) = all.filter { it.group == g }

    private val p: Prefs get() = App.instance.prefs
    private val texts = HashMap<String, Prefs.Text>()
    private val flags = HashMap<String, Prefs.Flag>()
    private fun text(key: String) = synchronized(texts) { texts.getOrPut(key) { p.Text(key, "") } }
    private fun flag(key: String, default: Boolean) = synchronized(flags) { flags.getOrPut(key) { p.Flag(key, default) } }

    /** Bumped on every change, so screens redraw. */
    private val _changed = MutableStateFlow(0)
    val changed: StateFlow<Int> get() = _changed
    private fun bump() { _changed.value++ }
    fun touch() = bump()
    /** Sign-ins on the service's website (SignInScreen); Trakt has its own page. */
    val WEB = setOf("simkl", "anilist", "mal")

    /** TorBox keeps Vision TB's key. */
    private fun keyId(s: Service) = s.id
    private fun field(s: Service, i: Int): Prefs.Text = if (s.id == "torbox" && i == 0) p.torboxKey else text("int_${keyId(s)}_$i")

    fun values(s: Service): List<String> = s.fields.indices.map { field(s, it).value }

    fun save(s: Service, v: List<String>) {
        s.fields.indices.forEach { field(s, it).value = v.getOrElse(it) { "" }.trim() }
        status(s).value = ""
        if (s.id == "torbox" && v.firstOrNull().orEmpty().isNotBlank()) p.visionTb.value = true
        bump()
    }

    fun clear(s: Service) {
        if (s.id == "trakt") App.instance.trakt.disconnect()
        if (s.id in WEB) SignIns.signOut(s.id)
        s.fields.indices.forEach { field(s, it).value = "" }
        if (s.id == "torbox") p.visionTb.value = false
        status(s).value = ""
        bump()
    }

    fun isSet(s: Service): Boolean = when {
        s.id == "trakt" -> App.instance.trakt.connected.value != null
        s.id in WEB -> SignIns.signedIn(s.id)
        s.fields.isEmpty() -> false
        else -> field(s, 0).value.isNotBlank()
    }

    /** "ok" / "refused" / "" (not checked) for the key last saved. */
    fun status(s: Service): Prefs.Text = text("int_${keyId(s)}_status")

    suspend fun check(s: Service): Boolean? {
        val c = s.check ?: return null
        if (!isSet(s)) return null
        val ok = runCatching { c(values(s)) }.getOrDefault(false)
        status(s).value = if (ok) "ok" else "refused"
        bump()
        return ok
    }

    /** The group's driver: the one chosen if it is set up and works in Lite, else the first that is. */
    fun driver(g: Group): Service? {
        val able = inGroup(g).filter { it.live != null && isSet(it) }
        return able.firstOrNull { it.id == text("int_driver_${g.name}").value } ?: able.firstOrNull()
    }

    fun setDriver(g: Group, s: Service) { text("int_driver_${g.name}").value = s.id; bump() }

    fun helper(s: Service): Prefs.Flag = flag("int_${s.id}_on", true)

    fun setHelper(s: Service, on: Boolean) { helper(s).value = on; bump() }

    /** Whether the app may use [id] now: set up, works in Lite, and either driving or switched on as a helper. */
    fun active(id: String): Boolean {
        val s = byId(id)
        return s.live != null && isSet(s) && (driver(s.group)?.id == id || helper(s).value)
    }

    /** The first-start setup was gone through (or skipped) once. */
    val setupDone: Prefs.Flag get() = flag("int_setup_done", false)

    // --- key checks: one small request each, only when a key is saved ---

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    private fun ok(r: Pair<Int, String>?) = r != null && r.first in 200..299

    private suspend fun get(url: String, bearer: String? = null, headers: Map<String, String> = emptyMap()) =
        call(Request.Builder().url(url).apply { bearer?.let { header("Authorization", "Bearer $it") }; headers.forEach { (k, v) -> header(k, v) } }.build())

    private suspend fun post(url: String, json: String) =
        call(Request.Builder().url(url).post(json.toRequestBody("application/json".toMediaType())).build())

    private suspend fun call(r: Request): Pair<Int, String>? = withContext(Dispatchers.IO) {
        runCatching { Net.http.newCall(r).execute().use { it.code to (it.body?.string()?.take(4000) ?: "") } }.getOrNull()
    }
}
