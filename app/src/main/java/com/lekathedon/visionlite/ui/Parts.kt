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
import androidx.compose.ui.Modifier
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

/** Brings the focused item to [leadPx] from the start in one frame (no scroll animation). */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
fun snapSpec(leadPx: Float): BringIntoViewSpec = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = snap()
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - leadPx
}

/** An image decoded at the size it is drawn ([w] x [h]), never bigger. */
@Composable
fun Img(url: String?, w: Dp, h: Dp, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop) {
    if (url.isNullOrBlank()) return
    val context = LocalContext.current
    val d = LocalDensity.current
    val req = remember(url, w, h) {
        with(d) { ImageRequest.Builder(context).data(url).size(w.roundToPx(), h.roundToPx()).build() }
    }
    AsyncImage(model = req, contentDescription = null, contentScale = scale, modifier = modifier)
}

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
    Box(
        modifier
            .clip(shape)
            .border(if (focused) 3.dp else 0.dp, if (focused) Palette.Focus else Color.Transparent, shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
    ) { content(focused) }
}

/** A text button for lists and dialogs. */
@Composable
fun Button(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Focusable(onClick, modifier) { f ->
        Text(
            text,
            color = if (f) Color.Black else Palette.Text,
            fontSize = 15.sp,
            modifier = Modifier.background(if (f) Color.White else Color(0x22FFFFFF)).padding(horizontal = 18.dp, vertical = 10.dp)
        )
    }
}
