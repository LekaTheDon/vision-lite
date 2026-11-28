package com.nuvio.tv.data.local

import android.content.Context

/** Arc's image setting, as the thumbnail engine reads it: Lite keeps thumbnails in RGB565 (half the memory). */
internal class ImagePerformancePreferences(@Suppress("UNUSED_PARAMETER") context: Context) {
    val rgb565Enabled: Boolean = true
}
