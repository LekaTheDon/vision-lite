package com.lekathedon.visionlite.report

import com.lekathedon.visionlite.BuildConfig
import com.lekathedon.visionlite.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Sends a report to the Nuvio Vision team as a GitHub issue in the app's own repo. The build carries an issues-only
 * key (repo secret REPORT_TOKEN); without it the button isn't shown and the phone page (ReportServer) is the way.
 * GitHub takes ~65k characters per post: the report goes as the issue plus a few comments.
 */
object Send {
    val available: Boolean get() = BuildConfig.REPORT_TOKEN.isNotBlank() && BuildConfig.REPORT_REPO.isNotBlank()

    private const val CHUNK = 60_000
    private const val MAX_POSTS = 8

    /** The issue's web address, or the failure. */
    suspend fun github(title: String, text: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val parts = split(fit(text))
            val api = "https://api.github.com/repos/${BuildConfig.REPORT_REPO}"
            val issue = post("$api/issues", JSONObject().put("title", "[report] $title").put("body", fence(parts[0], 1, parts.size)))
            val number = issue.getInt("number")
            parts.drop(1).forEachIndexed { i, part -> post("$api/issues/$number/comments", JSONObject().put("body", fence(part, i + 2, parts.size))) }
            issue.optString("html_url", "#$number")
        }
    }

    /** Too long: the start (summary, exits, crashes, settings) and the newest log lines are kept. */
    private fun fit(text: String): String {
        val max = CHUNK * MAX_POSTS - 2_000
        if (text.length <= max) return text
        val head = text.take(120_000)
        return head + "\n\n… (older log lines left out) …\n\n" + text.takeLast(max - head.length - 100)
    }

    private fun split(text: String): List<String> {
        val out = ArrayList<String>()
        var rest = text
        while (rest.length > CHUNK) {
            val cut = rest.lastIndexOf('\n', CHUNK).takeIf { it > CHUNK / 2 } ?: CHUNK
            out += rest.substring(0, cut); rest = rest.substring(cut)
        }
        out += rest
        return out
    }

    private fun fence(s: String, n: Int, of: Int) = (if (of > 1) "Part $n of $of\n\n" else "") + "````text\n" + s.replace("````", "'''") + "\n````"

    private fun post(url: String, body: JSONObject): JSONObject {
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer ${BuildConfig.REPORT_TOKEN}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        Net.http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("GitHub said ${r.code}")
            return JSONObject(text)
        }
    }
}
