package com.lekathedon.visionlite.player

import androidx.compose.ui.input.key.Key
import com.lekathedon.visionlite.App

/**
 * Remote buttons in the live TV player, assigned by you (TiviMate's "Remote control buttons"): each button and each
 * long press does one action. Stored in one pref, "button=action" pairs; anything not stored has the default.
 * Back always closes / leaves and digits always type a channel number.
 */
internal object LiveKeys {
    enum class Action(val label: String) {
        NONE("Nothing"), CHANNELS("Channel list"), GROUPS("Channel groups"), MENU("Menu (control bar)"), GUIDE("This channel's guide"),
        INFO("Info banner"), REWIND("Rewind"), FORWARD("Fast forward"), PLAY_PAUSE("Pause / play"), GO_LIVE("Back to live"),
        START_OVER("Start over (catch-up)"), ZAP_PREV("Channel up (previous in list)"), ZAP_NEXT("Channel down (next in list)"),
        LAST_CHANNEL("Last channel"), FAVOURITE("Add / remove favourite"), RECORD("Record / stop"), AUDIO("Audio track"),
        SUBTITLES("Subtitles"), PICTURE("Picture size"), SLEEP("Sleep timer"), STATS("Stats"),
    }

    /** Buttons that can be assigned, with their default action (TiviMate's layout, plus rewind on Left / Right; Down = the player options, user 2026-11-26). */
    enum class Button(val label: String, val default: Action) {
        OK("OK", Action.CHANNELS), LONG_OK("Hold OK", Action.MENU),
        UP("Up", Action.ZAP_PREV), LONG_UP("Hold Up", Action.ZAP_PREV),
        DOWN("Down", Action.MENU), LONG_DOWN("Hold Down", Action.MENU),
        LEFT("Left", Action.REWIND), LONG_LEFT("Hold Left", Action.REWIND),
        RIGHT("Right", Action.FORWARD), LONG_RIGHT("Hold Right", Action.FORWARD),
        MENU("Menu", Action.MENU), PLAY_PAUSE("Play / Pause", Action.PLAY_PAUSE), RW("Rewind (RW)", Action.REWIND), FF("Fast forward (FF)", Action.FORWARD),
        CH_UP("Channel +", Action.ZAP_PREV), CH_DOWN("Channel −", Action.ZAP_NEXT), GUIDE("Guide / EPG", Action.GUIDE), INFO("Info", Action.INFO),
        LAST("Last channel button", Action.LAST_CHANNEL),
        RED("Red", Action.RECORD), GREEN("Green", Action.FAVOURITE), YELLOW("Yellow", Action.AUDIO), BLUE("Blue", Action.SUBTITLES),
    }

    private fun map(): Map<String, String> = App.instance.prefs.liveKeys.value.split(';').mapNotNull { p ->
        p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()

    fun get(b: Button): Action = map()[b.name]?.let { n -> Action.entries.firstOrNull { it.name == n } } ?: b.default

    fun set(b: Button, a: Action) {
        val m = map().toMutableMap()
        if (a == b.default) m.remove(b.name) else m[b.name] = a.name
        App.instance.prefs.liveKeys.value = m.entries.joinToString(";") { "${it.key}=${it.value}" }
    }

    fun reset() { App.instance.prefs.liveKeys.value = "" }

    /** The short-press button for a key, and its hold twin (null = the key has no hold of its own). */
    fun buttonOf(k: Key): Pair<Button, Button?>? = when (k) {
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> Button.OK to Button.LONG_OK
        Key.DirectionUp -> Button.UP to Button.LONG_UP
        Key.DirectionDown -> Button.DOWN to Button.LONG_DOWN
        Key.DirectionLeft -> Button.LEFT to Button.LONG_LEFT
        Key.DirectionRight -> Button.RIGHT to Button.LONG_RIGHT
        Key.Menu -> Button.MENU to null
        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> Button.PLAY_PAUSE to null
        Key.MediaRewind -> Button.RW to null
        Key.MediaFastForward -> Button.FF to null
        Key.ChannelUp -> Button.CH_UP to null
        Key.ChannelDown -> Button.CH_DOWN to null
        Key.Guide -> Button.GUIDE to null
        Key.Info -> Button.INFO to null
        Key.LastChannel -> Button.LAST to null
        Key.ProgramRed -> Button.RED to null
        Key.ProgramGreen -> Button.GREEN to null
        Key.ProgramYellow -> Button.YELLOW to null
        Key.ProgramBlue -> Button.BLUE to null
        else -> null
    }

    /** Actions that repeat while the button is held down (no separate hold action needed). */
    fun repeats(a: Action) = a == Action.REWIND || a == Action.FORWARD || a == Action.ZAP_PREV || a == Action.ZAP_NEXT
}
