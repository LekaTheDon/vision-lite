package com.lekathedon.visionlite.ui

import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.link.Root

/**
 * Vision's "1080p menus" (Advanced, vision.7 MenuDownscale): the AM9 draws apps at 3840x2160 (density 640), so every
 * frame of Home is 8.3 M pixels; the GPU needed ~17 ms a frame and half the frames missed 60 fps. Android's own
 * per-app downscale (DOWNSCALED + DOWNSCALE_50, root) draws the app at 1920x1080 and the box scales it up for free:
 * a quarter of the work per frame. Films and episodes on ExoPlayer keep their full 4K / Dolby Vision (hardware video
 * layer); MPV draws its picture inside the app, so with this on MPV shows 4K at 1080p. Takes effect at the next start.
 */
object MenuDownscale {
    private const val PKG = "com.lekathedon.visionlite"

    /** Makes Android's setting follow the switch (once per change); true when a restart is needed for it to show. */
    fun sync(): Boolean {
        val p = App.instance.prefs
        val want = if (p.menus1080.value) "on" else "off"
        if (p.menus1080Applied.value == want) return false
        val cmd = if (want == "on") "am compat enable --no-kill DOWNSCALED $PKG; am compat enable --no-kill DOWNSCALE_50 $PKG"
            else "am compat reset --no-kill DOWNSCALED $PKG; am compat reset --no-kill DOWNSCALE_50 $PKG"
        if (Root.run(cmd) == null) return false
        p.menus1080Applied.value = want
        return true
    }
}
