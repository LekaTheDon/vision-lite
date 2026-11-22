package com.lekathedon.visionlite.ui

import android.os.SystemClock
import android.view.KeyEvent
import com.lekathedon.visionlite.App

/**
 * Vision's dpadRepeatThrottle, app-wide (user 2026-11-22: Vision's scrolling settings built in): a held D-pad repeats
 * faster than Compose can scroll and bring the next card in, so focus runs ahead and the rows judder. Repeats closer
 * together than the gate (Left/Right 80 ms, or 48 ms with Fast horizontal navigation; Up/Down 112 ms) are dropped;
 * single presses always pass. Also the idle clock for the screensaver. Off in the players (holding seeks there).
 */
object DpadPace {
    @Volatile var inPlayer = false
    @Volatile var lastInput = SystemClock.uptimeMillis(); private set
    private var lastH = 0L
    private var lastV = 0L

    fun consume(e: KeyEvent): Boolean {
        lastInput = SystemClock.uptimeMillis()
        if (inPlayer || e.action != KeyEvent.ACTION_DOWN || e.repeatCount == 0) return false
        val now = e.eventTime
        return when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val gate = if (App.instance.prefs.fastHorizontal.value) 48 else 80
                if (now - lastH < gate) true else { lastH = now; false }
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (now - lastV < 112) true else { lastV = now; false }
            else -> false
        }
    }

    fun touch() { lastInput = SystemClock.uptimeMillis() }
}
