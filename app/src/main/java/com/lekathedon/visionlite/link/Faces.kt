package com.lekathedon.visionlite.link

import java.io.File
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A profile picture kept only as a file on this box (no link it came from) for the paired phone: the link server
 * answers GET /face/<id>-<stamp>?k=<sig> with the file. The sig is an HMAC of the path with the pairing secret, so only
 * a paired phone (which was handed the URL) can load it; a new picture changes the stamp, so the phone never shows an old one.
 */
object Faces {
    private fun sig(path: String): String? = runCatching {
        val secret = Pairing(com.lekathedon.visionlite.App.instance).secret
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
            .doFinal("face:$path".toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }.getOrNull()

    fun url(id: Int, file: String): String? {
        val f = File(file).takeIf { it.isFile } ?: return null
        val ip = PhoneLinkIp.get() ?: return null
        val path = "/face/$id-${f.lastModified()}"
        return "http://$ip:${Pairing.PORT}$path?k=${sig(path) ?: return null}"
    }

    /** The picture for a GET request line, or null (not a face, wrong signature, no such file). */
    fun serve(requestLine: String): Pair<ByteArray, String>? {
        val target = requestLine.removePrefix("GET ").substringBefore(' ')
        val path = target.substringBefore('?')
        if (!path.startsWith("/face/")) return null
        val k = target.substringAfter("?k=", "")
        val want = sig(path) ?: return null
        if (!java.security.MessageDigest.isEqual(want.toByteArray(), k.toByteArray())) return null
        val (id, stamp) = path.removePrefix("/face/").split('-').let { (it.getOrNull(0)?.toIntOrNull() ?: return null) to it.getOrNull(1) }
        val pic = com.lekathedon.visionlite.data.Profiles.byId(id)?.pic ?: return null
        val f = File(pic).takeIf { it.isFile && it.lastModified().toString() == stamp } ?: return null
        val bytes = f.readBytes()
        val type = when {
            bytes.size > 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "image/gif"
            bytes.size > 3 && bytes[0] == 0x89.toByte() -> "image/png"
            bytes.size > 12 && String(bytes, 8, 4) == "WEBP" -> "image/webp"
            else -> "image/jpeg"
        }
        return bytes to type
    }
}

/** The box's address on the home network (IPv4, not loopback / VPN tun). */
object PhoneLinkIp {
    fun get(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") }
            .sortedBy { if (it.name.startsWith("eth")) 0 else if (it.name.startsWith("wlan")) 1 else 2 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }.getOrNull()
}
