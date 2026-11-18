package com.lekathedon.visionlite.ui

import android.view.KeyEvent

/**
 * A hold of OK opens a menu while the finger is still on OK: its repeats and its release would land on the menu's
 * first option (picking it, or closing the menu). After a hold, every OK event is swallowed until OK is let go,
 * so the menu waits for a fresh press. MainActivity runs [consume] before anything else sees a key.
 */
object HoldGuard {
    @Volatile private var armed = false
    fun held() { armed = true }
    fun consume(e: KeyEvent): Boolean {
        if (!armed) return false
        val ok = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER || e.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (!ok) return false
        if (e.action == KeyEvent.ACTION_UP) armed = false
        return true
    }
}
