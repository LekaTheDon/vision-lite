package com.lekathedon.visionlite.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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

/**
 * The app's colours, read from the skin in use (OLED, Tinted, Midnight, Ember, Moss). Each getter reads Compose
 * state, so a new skin redraws every screen that uses it; nothing else changes.
 */
object Palette {
    val Base: Color get() = Theme.skin.base
    val Black = Color(0xFF000000)
    val Card: Color get() = Theme.skin.card
    val Text: Color get() = Theme.skin.text
    val TextDim: Color get() = Theme.skin.dim
    val Focus = Color.White
    val Accent: Color get() = Theme.accent
}

/** Colour skins. [tinted] = the page takes the title's art colour (Echo's wash); the others paint their own page. */
enum class Skin(val key: String, val label: String, val base: Color, val glow: Color, val card: Color, val card2: Color,
                val text: Color, val dim: Color, val accent: Color, val onAccent: Color, val capsule: Color, val tinted: Boolean = false) {
    OLED("oled", "OLED black", Color(0xFF000000), Color(0xFF000000), Color(0xFF121214), Color(0xFF1E1E21),
        Color(0xFFF4F4F4), Color(0xFF9B9B9B), Color(0xFFECECEC), Color(0xFF000000), Color(0xFF0C0C0D)),
    TINT("tint", "Tinted", Color(0xFF0B0B0D), Color(0xFF0B0B0D), Color(0xFF1B1B1F), Color(0xFF26262B),
        Color(0xFFF2F2F2), Color(0xB3FFFFFF), Color(0xFFE4D64B), Color(0xFF1A1800), Color(0xFF121316), tinted = true),
    NIGHT("night", "Midnight", Color(0xFF0A0F1F), Color(0xFF16204A), Color(0xFF131C38), Color(0xFF1C2749),
        Color(0xFFEEF2FF), Color(0xFF98A3C9), Color(0xFF8FA7FF), Color(0xFF0A0F1F), Color(0xFF0E1530)),
    EMBER("ember", "Ember", Color(0xFF130B07), Color(0xFF2E150B), Color(0xFF22150E), Color(0xFF301E14),
        Color(0xFFFFF3EA), Color(0xFFC6A693), Color(0xFFFF9B4F), Color(0xFF1A0D05), Color(0xFF1E120B)),
    MOSS("moss", "Moss", Color(0xFF09110C), Color(0xFF11281B), Color(0xFF122019), Color(0xFF1A2C21),
        Color(0xFFECF7EF), Color(0xFF94AD9C), Color(0xFF7EDC97), Color(0xFF06140B), Color(0xFF0D1A12));
    companion object { fun of(k: String) = entries.firstOrNull { it.key == k } ?: TINT }
}

/** The skin and look in use (set from the prefs in MainActivity), and the accent the Tinted skin takes from the art. */
object Theme {
    var skin by mutableStateOf(Skin.TINT)
    /** Settings › Button size: buttons, icons and switches past Home (1 = the 0.37.0 size). */
    var scale by mutableStateOf(0.67f)
    val sizes = listOf("smallest" to "Smallest", "small" to "Small", "medium" to "Medium", "large" to "Large")
    fun scaleOf(key: String) = when (key) { "smallest" -> 0.55f; "medium" -> 0.83f; "large" -> 1f; else -> 0.67f }
    /** Tinted: the last title's art colour, brightened (null until a title page read one). */
    var artAccent by mutableStateOf<Color?>(null)
    val accent: Color get() = if (skin.tinted) artAccent ?: skin.accent else skin.accent
    val onAccent: Color get() = if (skin.tinted && artAccent != null) Color(0xFF111111) else skin.onAccent
}

/** True on screens past Home (not Home, not the player): they wear the look. Home, the pill and the clock never do. */
val LocalInside = staticCompositionLocalOf { false }

/** Page colour of the skin; [oled] = no art wash on the page (every skin but Tinted). */
data class Look(val skin: Skin) {
    val oled: Boolean get() = !skin.tinted
    val page: Color get() = skin.base
}

val LocalLook = staticCompositionLocalOf { Look(Skin.TINT) }

/** The skin's page: its colour plus a soft glow top right (Midnight, Ember, Moss); plain for OLED and Tinted. */
fun Modifier.skinPage(skin: Skin = Theme.skin): Modifier = this.background(skin.base).then(
    if (skin.glow == skin.base) Modifier else Modifier.drawBehind {
        drawRect(Brush.radialGradient(listOf(skin.glow, Color.Transparent), androidx.compose.ui.geometry.Offset(size.width * 0.85f, 0f), size.width * 0.75f))
    })

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
            bmp?.let { accentCache[url] = accentOf(it); darken(it) }?.also { synchronized(tintCache) { tintCache[url] = it } }
        } ?: tint
        accentCache[url]?.let { Theme.artAccent = it }
    }
    Canvas(modifier) {
        val img = tint ?: return@Canvas
        drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset.Zero,
            IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low)
        drawRect(Brush.verticalGradient(0f to Color(0x33000000), 0.5f to Color(0x99000000), 1f to Color(0xE6000000)))
    }
}

private val accentCache = com.lekathedon.visionlite.data.Lru<String, Color>(300)

/** The art's most colourful tone, made bright enough to read as a button on a dark page (Legacy's "accent from the art"). */
private fun accentOf(src: Bitmap): Color {
    val w = src.width; val h = src.height
    val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
    var best = 0; var bestScore = -1f
    val hsv = FloatArray(3)
    for (p in px) {
        android.graphics.Color.colorToHSV(p, hsv)
        val score = hsv[1] * (0.35f + hsv[2])
        if (score > bestScore) { bestScore = score; best = p }
    }
    android.graphics.Color.colorToHSV(best, hsv)
    if (hsv[1] < 0.18f) return Color(0xFFE6E6E6)
    hsv[1] = hsv[1].coerceIn(0.45f, 0.75f); hsv[2] = 0.92f
    return Color(android.graphics.Color.HSVToColor(hsv))
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

/** The app's logo (Vision wordmark, Vision's own art), [height] tall. */
@androidx.compose.runtime.Composable
fun VisionWordmark(height: androidx.compose.ui.unit.Dp, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) =
    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.lekathedon.visionlite.R.drawable.vision_wordmark), "Vision",
        modifier.height(height), contentScale = androidx.compose.ui.layout.ContentScale.FillHeight)
