package com.lekathedon.visionlite.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil3.BitmapImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Palette {
    val Base = Color(0xFF0B0B0D)
    val Black = Color(0xFF000000)
    val Card = Color(0xFF1B1B1F)
    val Text = Color(0xFFF2F2F2)
    val TextDim = Color(0xB3FFFFFF)
    val Focus = Color.White
    val Accent = Color(0xFFE50914)
}

/** OLED black = pure black page, no tint. */
data class Look(val oled: Boolean) {
    val page: Color get() = if (oled) Palette.Black else Palette.Base
}

val LocalLook = staticCompositionLocalOf { Look(oled = false) }

/** Small cache of tints, so going back and forth between titles never decodes twice. */
private val tintCache = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
    override fun removeEldestEntry(e: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 16
}

/**
 * The page's colour tint: the title's art read tiny (24x14), darkened, then stretched across the screen; the GPU's
 * bilinear stretch of so few pixels is the blur. One image per frame; the art is decoded once per title.
 */
@Composable
fun ArtTint(url: String?, modifier: Modifier = Modifier.fillMaxSize()) {
    val context = LocalContext.current
    var tint by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) return@LaunchedEffect
        tint = synchronized(tintCache) { tintCache[url] } ?: withContext(Dispatchers.IO) {
            val req = ImageRequest.Builder(context).data(url).size(24, 14).allowHardware(false).build()
            val bmp = ((context.imageLoader.execute(req) as? SuccessResult)?.image as? BitmapImage)?.bitmap
            bmp?.let { darken(it) }?.also { synchronized(tintCache) { tintCache[url] = it } }
        } ?: tint
    }
    Canvas(modifier) {
        val img = tint ?: return@Canvas
        drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset.Zero,
            IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low)
        drawRect(Brush.verticalGradient(0f to Color(0x33000000), 0.5f to Color(0x99000000), 1f to Color(0xE6000000)))
    }
}

/** Scales every pixel so the mean brightness sits near 35%: dark art still colours the page, bright art stays calm. */
private fun darken(src: Bitmap): ImageBitmap {
    val w = src.width; val h = src.height
    val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
    var sum = 0.0
    for (p in px) sum += ((p shr 16 and 255) + (p shr 8 and 255) + (p and 255)) / 765.0
    val gain = (0.35 / (sum / px.size).coerceAtLeast(0.02)).coerceIn(0.3, 3.0)
    for (i in px.indices) {
        val p = px[i]
        fun c(v: Int) = (v * gain).toInt().coerceIn(0, 190)
        px[i] = (0xFF shl 24) or (c(p shr 16 and 255) shl 16) or (c(p shr 8 and 255) shl 8) or c(p and 255)
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}
