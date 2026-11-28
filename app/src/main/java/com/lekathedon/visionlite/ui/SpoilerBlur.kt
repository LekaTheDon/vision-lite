package com.lekathedon.visionlite.ui

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation

/**
 * Spoiler blur for episode stills: shrink to a few dozen pixels, then grow back with filtering, twice. Smooth enough
 * that nothing can be made out, and far cheaper than a per-pixel blur (two small scaled copies, no pixel loops).
 */
class SpoilerBlur(private val steps: Int = 24) : Transformation() {
    override val cacheKey = "spoiler_blur_$steps"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width.coerceAtLeast(1); val h = input.height.coerceAtLeast(1)
        val sw = (w / steps).coerceAtLeast(2); val sh = (h / steps).coerceAtLeast(2)
        val tiny = Bitmap.createScaledBitmap(input, sw, sh, true)
        val mid = Bitmap.createScaledBitmap(tiny, sw * 4, sh * 4, true)
        val small = Bitmap.createScaledBitmap(mid, sw, sh, true)
        val out = Bitmap.createScaledBitmap(small, w, h, true)
        if (tiny !== input) tiny.recycle()
        mid.recycle(); small.recycle()
        return out
    }
}
