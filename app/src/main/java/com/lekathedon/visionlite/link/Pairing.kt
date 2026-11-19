package com.lekathedon.visionlite.link

import android.content.Context
import java.security.SecureRandom

/**
 * The secret shared with the phone (Vision Lite Companion), shown once as a QR + code in Settings › Phone companion.
 * Every command is HMAC-SHA256 signed with it ([Server]), so only the paired phone is obeyed.
 */
class Pairing(context: Context) {
    private val p = context.getSharedPreferences("link", Context.MODE_PRIVATE)

    val secret: String get() = p.getString("secret", null) ?: newSecret()

    var lastPhone: String?
        get() = p.getString("last_phone", null)
        set(v) { p.edit().putString("last_phone", v).apply() }

    var lastSeen: Long
        get() = p.getLong("last_seen", 0)
        set(v) { p.edit().putLong("last_seen", v).apply() }

    /** A new code: the old phone stops working until it scans again. */
    fun newSecret(): String {
        val rnd = SecureRandom()
        val s = (1..16).map { ALPHABET[rnd.nextInt(ALPHABET.length)] }.joinToString("")
        p.edit().putString("secret", s).remove("last_phone").putLong("last_seen", 0).apply()
        return s
    }

    companion object {
        /** No 0/O/1/I: the code can also be typed on the phone. */
        const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        /** Not Vision Link's 47800: both can run on one box. */
        const val PORT = 47801
        const val SERVICE_TYPE = "_visionlite._tcp"
        fun pretty(s: String) = s.chunked(4).joinToString("-")
    }
}
