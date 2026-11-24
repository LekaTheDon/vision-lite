package com.lekathedon.visionlite.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.transformations

/** Brings the focused item to [leadPx] from the start in one frame (no scroll animation). */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
fun snapSpec(leadPx: Float): BringIntoViewSpec = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = snap()
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - leadPx
}

/** An image decoded at the size it is drawn ([w] x [h]), never bigger. */
@Composable
fun Img(url: String?, w: Dp, h: Dp, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop, blur: Boolean = false,
    /** Shown instead when [url] fails (custom poster server down or the title unknown to it). */
    fallback: String? = null) {
    var failed by androidx.compose.runtime.saveable.rememberSaveable(url) { androidx.compose.runtime.mutableStateOf(false) }
    val src = if (failed && !fallback.isNullOrBlank()) fallback else url
    if (src.isNullOrBlank()) return
    val context = LocalContext.current
    val d = LocalDensity.current
    val req = remember(src, w, h, blur) {
        // Never decoded above 1080p (the box draws at 4K: a full-screen backdrop was a 33 MB 3840x2160 bitmap per
        // focused card while scrolling; at 1920x1080 it is a quarter of that and looks the same from the sofa).
        val pw = with(d) { w.roundToPx() }; val ph = with(d) { h.roundToPx() }
        val k = minOf(1f, 1920f / pw.coerceAtLeast(1), 1080f / ph.coerceAtLeast(1))
        with(d) { ImageRequest.Builder(context).data(src).size((pw * k).toInt().coerceAtLeast(1), (ph * k).toInt().coerceAtLeast(1))
            .apply { if (blur) transformations(BlurTransformation()) }.build() }
    }
    AsyncImage(model = req, contentDescription = null, contentScale = scale, modifier = modifier,
        onError = { if (src == url && !fallback.isNullOrBlank() && fallback != url) failed = true })
}

/** Spoiler text: blurred (Android 12+), hidden on older boxes where Compose can't blur. */
fun Modifier.spoiler(on: Boolean): Modifier = if (!on) this
    else if (android.os.Build.VERSION.SDK_INT >= 31) this.then(Modifier.blur(10.dp)) else this.then(Modifier.alpha(0f))

/** A focusable box: white ring when focused, OK = [onClick]. */
@Composable
fun Focusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp),
    onFocus: () -> Unit = {},
    content: @Composable (focused: Boolean) -> Unit
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocus() }
    // Past Home the ring is the look's (Kit); Home and the player keep the white ring.
    val inside = LocalInside.current
    Box(
        modifier
            .clip(shape)
            .border(if (!focused) 0.dp else if (inside) Kit.ringWidth else 3.dp,
                if (!focused) Color.Transparent else if (inside) Kit.ringColor else Palette.Focus, shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
    ) { content(focused) }
}

/** A text button for lists and dialogs. */
@Composable
fun Button(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (LocalInside.current) { KitButton(text, null, onClick, modifier); return }
    Focusable(onClick, modifier) { f ->
        Text(
            text,
            color = if (f) Color.Black else Palette.Text,
            fontSize = 15.sp,
            modifier = Modifier.background(if (f) Color.White else Color(0x22FFFFFF)).padding(horizontal = 18.dp, vertical = 10.dp)
        )
    }
}
