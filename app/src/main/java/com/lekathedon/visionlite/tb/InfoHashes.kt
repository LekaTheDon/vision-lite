package com.lekathedon.visionlite.tb

/** BitTorrent v1 info hashes in one form: 40 lowercase hex characters. */
object InfoHashes {
    private val HEX40 = Regex("^[0-9a-fA-F]{40}$")
    private val BASE32 = Regex("^[A-Za-z2-7]{32}$")
    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** 40-hex or 32-char base32 btih -> 40 lowercase hex; anything else (incl. v2 hashes) -> null. */
    fun normalize(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (HEX40.matches(value)) return value.lowercase()
        if (BASE32.matches(value)) return base32ToHex(value.uppercase())
        return null
    }

    fun fromMagnet(magnet: String?): String? {
        if (magnet == null || !magnet.startsWith("magnet:", ignoreCase = true)) return null
        val btih = magnet.substringAfter("urn:btih:", "").substringBefore('&')
        return normalize(btih)
    }

    private fun base32ToHex(value: String): String? {
        var buffer = 0
        var bits = 0
        val out = StringBuilder(40)
        for (c in value) {
            val v = BASE32_ALPHABET.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.append("%02x".format((buffer shr bits) and 0xff))
            }
        }
        return out.toString().takeIf { it.length == 40 }
    }
}
