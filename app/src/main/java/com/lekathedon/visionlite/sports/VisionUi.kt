// The few pieces of Vision's Live TV look that its Sports screens (copied as is) lean on, drawn with Lite's own
// colours: text, the theme colours, the clock format, channel logos, the text box, notices.
package com.lekathedon.visionlite.sports

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import com.lekathedon.visionlite.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.ui.Palette
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Vision's theme colours, as Lite's palette. */
internal object NuvioTheme {
    object colors {
        val Background = Palette.Base
        val BackgroundElevated = Color(0xFF16171A)
        val Secondary = com.lekathedon.visionlite.ui.KitInk.accent
        val OnSecondary = Color.Black
        val TextPrimary = Palette.Text
        val TextSecondary = Color(0xB3FFFFFF)
        val TextTertiary = Color(0x80FFFFFF)
        val FocusBackground = Color.White
        val FocusRing = Color.White
        val Field = Color(0xFF232428)
        val Error = Color(0xFFE5484D)
    }
    object focusRing {
        fun border(width: Dp) = BorderStroke(width, Color.White)
        fun brush(): Brush = SolidColor(Color.White)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LiveText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = NuvioTheme.colors.TextPrimary,
    size: TextUnit = 16.sp,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
    align: TextAlign? = null,
    marquee: Boolean = false
) {
    Text(
        text = text,
        modifier = if (marquee && maxLines == 1) modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1_200, repeatDelayMillis = 1_500) else modifier,
        color = color, fontSize = size, fontWeight = weight, maxLines = maxLines,
        softWrap = !(marquee && maxLines == 1),
        overflow = if (marquee && maxLines == 1) TextOverflow.Clip else TextOverflow.Ellipsis,
        textAlign = align
    )
}

internal fun formatClock(ms: Long, use24h: Boolean): String =
    SimpleDateFormat(if (use24h) "HH:mm" else "h:mm a", Locale.getDefault()).format(Date(ms))

@Composable
internal fun ChannelLogo(url: String?, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(width = size * 1.6f, height = size), contentAlignment = Alignment.Center) {
        if (!url.isNullOrBlank()) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(2.dp))
    }
}

@Composable
internal fun LoadingSpinner(modifier: Modifier = Modifier) {
    CircularProgressIndicator(modifier = modifier.size(16.dp), color = NuvioTheme.colors.Secondary, strokeWidth = 2.dp)
}

@Composable
internal fun LiveTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    focusRequester: FocusRequester? = null,
    password: Boolean = false,
    numeric: Boolean = false,
    onDone: () -> Unit = {}
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(NuvioTheme.colors.Field)
            .border(2.dp, if (focused) NuvioTheme.colors.FocusRing else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        if (value.isEmpty()) LiveText(hint, color = NuvioTheme.colors.TextTertiary)
        BasicTextField(
            value = value, onValueChange = onValueChange, singleLine = true,
            textStyle = TextStyle(color = NuvioTheme.colors.TextPrimary, fontSize = 16.sp),
            cursorBrush = SolidColor(NuvioTheme.colors.FocusRing),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth().then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { focused = it.isFocused }
        )
    }
}

@Composable
internal fun TextInputDialog(
    title: String,
    initial: String,
    hint: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    numeric: Boolean = false
) {
    var text by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    SportsDialog(onDismiss = onDismiss) {
        LiveText(title, size = 20.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        LiveTextField(value = text, onValueChange = { text = it }, hint = hint, focusRequester = field, numeric = numeric, onDone = { onConfirm(text) })
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SportsAction(confirmLabel) { onConfirm(text) }
            SportsAction("Cancel") { onDismiss() }
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { field.requestFocus() } }
}

/** The soft glow at the top of the page (Vision's ThemeGlow), in the accent colour. */
@Composable
internal fun ThemeGlow(modifier: Modifier = Modifier) {
    val accent = NuvioTheme.colors.Secondary
    val top = NuvioTheme.colors.BackgroundElevated
    Box(modifier.drawBehind { drawThemeGlow(accent, top, size.width, size.height) })
}

internal fun DrawScope.drawThemeGlow(accent: Color, top: Color, width: Float, height: Float) {
    drawRect(Brush.verticalGradient(listOf(top.copy(alpha = 0.55f), Color.Transparent), startY = 0f, endY = height))
    drawRect(Brush.radialGradient(listOf(accent.copy(alpha = 0.10f), Color.Transparent), center = Offset(width * 0.85f, 0f), radius = width * 0.45f),
        size = androidx.compose.ui.geometry.Size(width, height))
}

/** Lite has one page colour (OLED black or the base); Vision's guide backdrops aren't carried over. */
internal fun resolveBackdrop(@Suppress("UNUSED_PARAMETER") backdrop: String): Color =
    Palette.Base

internal fun Modifier.guideBackdrop(backdrop: String): Modifier = this.background(resolveBackdrop(backdrop))

internal fun DrawScope.drawGuideBackdrop(color: Color, width: Float, height: Float) {
    drawRect(color, size = androidx.compose.ui.geometry.Size(width, height))
}

/** No screen saver while a game plays in the window. */
@Composable
internal fun KeepScreenAwake(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

/** Vision's short notices ("Saved: …"), as a toast. */
internal object LiveTvNotice {
    fun show(text: String) {
        val app = App.instance
        android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(app, text, Toast.LENGTH_SHORT).show() }
    }
}

/** How a recording names a show: Vision's cleanup (live / new / HD marks dropped). */
internal class TitleMarks(val title: String)
private val MARKS = Regex("""(?i)\s*(\((live|new|hd|uhd|4k)\)|\[(live|new|hd|uhd|4k)]|\b(live|new):\s*)""")
internal fun titleMarks(title: String): TitleMarks = TitleMarks(MARKS.replace(title, " ").replace(Regex("\\s+"), " "))
