package com.lekathedon.visionlite.link

import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A tiny HTTP listener on the home network: POST /cmd with a JSON body and header X-Sig = hex HMAC-SHA256(secret, body).
 * The body carries "t" (phone clock, ms) and "n" (random nonce): old or replayed commands are refused.
 * A few requests at once, so a phone waiting on "watch" doesn't hold up its other commands; it blocks in accept()
 * while idle, so it costs no CPU.
 */
class Server(private val pairing: Pairing, private val commands: Commands) {
    @Volatile private var socket: ServerSocket? = null
    private val seen = LinkedHashMap<String, Long>()
    private val workers = java.util.concurrent.Executors.newFixedThreadPool(MAX_REQUESTS)

    fun start() {
        if (socket != null) return
        val s = ServerSocket(Pairing.PORT)
        socket = s
        Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: SocketException) { break } catch (e: Exception) { continue }
                workers.execute {
                    runCatching { serve(c) }.onFailure { Log.w(TAG, "request failed: ${it.message}") }
                    runCatching { c.close() }
                }
            }
        }, "vision-lite-link").apply { isDaemon = true }.start()
    }

    fun stop() { runCatching { socket?.close() }; socket = null }

    private fun serve(c: Socket) {
        c.soTimeout = 15000
        val input = BufferedInputStream(c.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len !in 0..262_144) return reply(c, 413, JSONObject().put("ok", false).put("error", "Too large"))
        val body = ByteArray(len)
        var read = 0
        while (read < len) { val n = input.read(body, read, len - read); if (n < 0) break; read += n }
        if (requestLine.startsWith("GET /face/")) {
            val (bytes, type) = Faces.serve(requestLine) ?: return reply(c, 404, JSONObject().put("ok", false).put("error", "Not found"))
            val head = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: max-age=86400\r\nConnection: close\r\n\r\n"
            c.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
            return
        }
        if (!requestLine.startsWith("POST /cmd ")) return reply(c, 404, JSONObject().put("ok", false).put("error", "Not found"))

        val text = String(body, 0, read, Charsets.UTF_8)
        if (!signed(text, headers["x-sig"].orEmpty())) {
            return reply(c, 401, JSONObject().put("ok", false).put("error", "Not paired with this TV (scan the code again)"))
        }
        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: return reply(c, 400, JSONObject().put("ok", false).put("error", "Bad request"))
        val t = json.optLong("t"); val nonce = json.optString("n")
        val now = System.currentTimeMillis()
        if (Math.abs(now - t) > MAX_SKEW_MS || nonce.isBlank() || !fresh(nonce, now)) {
            return reply(c, 401, JSONObject().put("ok", false).put("error", "Old or repeated command (check the phone's clock)"))
        }
        pairing.lastPhone = json.optString("from").take(40).ifBlank { null }
        pairing.lastSeen = now
        val result = runCatching { commands.handle(json.optString("c"), json.optJSONObject("a") ?: JSONObject()) }
            .getOrElse { JSONObject().put("ok", false).put("error", it.message ?: "Failed") }
        reply(c, 200, result)
    }

    private fun signed(body: String, sig: String): Boolean {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(pairing.secret.toByteArray(), "HmacSHA256")) }
        val expected = mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(expected.toByteArray(), sig.lowercase().toByteArray())
    }

    @Synchronized
    private fun fresh(nonce: String, now: Long): Boolean {
        val it = seen.entries.iterator()
        while (it.hasNext()) if (now - it.next().value > 2 * MAX_SKEW_MS) it.remove()
        if (seen.containsKey(nonce)) return false
        seen[nonce] = now
        return true
    }

    private fun reply(c: Socket, code: Int, json: JSONObject) {
        val bytes = json.toString().toByteArray()
        val status = when (code) { 200 -> "OK"; 401 -> "Unauthorized"; 404 -> "Not Found"; 413 -> "Too Large"; else -> "Bad Request" }
        val head = "HTTP/1.1 $code $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        c.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(b.toChar())
        }
    }

    companion object {
        private const val TAG = "VisionLiteLink"
        private const val MAX_SKEW_MS = 5 * 60_000L
        private const val MAX_REQUESTS = 6
    }
}
