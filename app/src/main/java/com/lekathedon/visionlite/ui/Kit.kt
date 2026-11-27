package com.lekathedon.visionlite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import com.lekathedon.visionlite.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.R

/**
 * The app's look (Marquee), drawn in the skin's colours. Fonts bundled, Latin only: Archivo + Barlow Condensed.
 * Tonal and Halo were removed 2026-11-27 (kept in git tag wip-looks-tonal-halo).
 */
object Fonts {
    val marquee = FontFamily(Font(R.font.marquee_400, FontWeight.Normal), Font(R.font.marquee_500, FontWeight.Medium),
        Font(R.font.marquee_700, FontWeight.Bold), Font(R.font.marquee_800, FontWeight.ExtraBold))
    val marqueeLabel = FontFamily(Font(R.font.marquee_label_600, FontWeight.SemiBold))
}

/** The look in use, as values the screens draw with. */
object Kit {
    val body: FontFamily get() = Fonts.marquee
    val display: FontFamily get() = Fonts.marquee
    /** Small labels: condensed tracked capitals. */
    val label: FontFamily get() = Fonts.marqueeLabel

    val accent: Color get() = Theme.accent
    val onAccent: Color get() = Theme.onAccent
    val card: Color get() = Theme.skin.card
    val card2: Color get() = Theme.skin.card2
    /** Pop-ups, side panels and the clock's panel: the skin's own page colour (OLED = true black), not a grey card. */
    val panel: Color get() = if (Theme.skin == Skin.OLED) Color.Black else Theme.skin.card
    /** An unfocused line inside a panel: barely lifted off [panel] (OLED keeps near-black). */
    val rowFill: Color get() = if (Theme.skin == Skin.OLED) Theme.skin.capsule else Theme.skin.card2.copy(alpha = 0.55f)
    val line: Color get() = Theme.skin.text.copy(alpha = 0.13f)
    /** Corners: cards, rows, buttons. */
    val cardR: Dp get() = 10.dp
    val rowR: Dp get() = 10.dp
    val buttonShape: Shape get() = RoundedCornerShape(10.dp)

    /** The focus ring: the skin's text colour. */
    val ringWidth: Dp get() = 2.dp
    val ringColor: Color get() = Theme.skin.text

    /** Settings › Button size: buttons and switches follow it fully, tiles and their text a little (smallest stays readable). */
    val k: Float get() = Theme.scale
    val soft: Float get() = 0.4f + 0.6f * Theme.scale

    fun lerp(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)
}

/**
 * Screens past Home: the look's body font for every Text, its focus ring for every Focusable. [font] alone (Home):
 * the look's font, while Home keeps its own sizes and parts (user 2026-11-25: Home's text, colours and pills follow the look).
 */
@Composable
fun Inside(on: Boolean, font: Boolean = on, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalInside provides on,
        LocalTextStyle provides if (font) LocalTextStyle.current.copy(fontFamily = Kit.body) else LocalTextStyle.current, content = content)
}

/** A Home row's title: Home's size (18sp), tracked capitals. */
@Composable
fun HomeHeading(t: String, modifier: Modifier = Modifier) =
    Text(t.uppercase(), color = Palette.Text, fontFamily = Fonts.marqueeLabel, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 2.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = modifier)

/** A section's heading: tracked capitals. */
@Composable
fun KitHeader(t: String, modifier: Modifier = Modifier, by: String? = null) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(t.uppercase(), color = Palette.Text, fontFamily = Fonts.marqueeLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.5.sp)
        by?.let { Text("  $it", color = Palette.TextDim, fontSize = 12.sp) }
    }
}

/** Small capitals above a title or a group: condensed tracked label. */
@Composable
fun Eyebrow(t: String, modifier: Modifier = Modifier, color: Color = Palette.TextDim) =
    Text(t.uppercase(), color = color, fontFamily = Kit.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 3.5.sp, modifier = modifier, maxLines = 1)

/** A slim switch. */
@Composable
fun KitSwitch(on: Boolean, @Suppress("UNUSED_PARAMETER") focused: Boolean, modifier: Modifier = Modifier) {
    val k = Kit.k
    Box(modifier.size(42.dp * k, 24.dp * k).clip(RoundedCornerShape(50)).background(if (on) Kit.accent else Kit.card2)
        .border(1.dp, if (on) Color.Transparent else Kit.line, RoundedCornerShape(50)).padding(3.dp * k),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(18.dp * k).clip(CircleShape).background(if (on) Kit.onAccent else Palette.TextDim))
    }
}

/** A small outlined chip. */
@Composable
fun KitChip(t: String, modifier: Modifier = Modifier, @Suppress("UNUSED_PARAMETER") strong: Boolean = false) =
    Text(t, color = Palette.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.border(1.dp, Palette.TextDim, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp), maxLines = 1)

/**
 * A button. [primary] = the main action (Play): gradient slab. Others: glass with a hairline; focused = text-colour
 * fill. [iconOnly] never shows the label (squares).
 */
@Composable
fun KitButton(label: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false,
              on: Boolean = false, iconOnly: Boolean = false, wide: Boolean = false,
              extra: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null) {
    val shape = RoundedCornerShape(10.dp)
    val k = Kit.k
    val h = 46.dp * k
    Focusable(onClick, modifier, shape) { f ->
        val bg: Modifier
        val ink: Color
        when {
            primary -> {
                bg = Modifier.background(Brush.horizontalGradient(listOf(Kit.accent, Kit.lerp(Kit.accent, Color(0xFF7FB6FF), 0.45f))))
                ink = Kit.onAccent
            }
            f -> { bg = Modifier.background(Palette.Text); ink = Theme.skin.base }
            else -> { bg = Modifier.background(Color(0x8C000000)).border(1.dp, Kit.line, shape); ink = Palette.Text }
        }
        val showLabel = !iconOnly || icon == null
        Box {
            Row((if (wide) Modifier.fillMaxWidth() else Modifier).height(h).then(bg).padding(horizontal = if (showLabel) (if (primary) 20.dp else 16.dp) * k else 0.dp)
                .then(if (!showLabel) Modifier.size(h, h) else Modifier),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (wide) Arrangement.Start else Arrangement.Center) {
                if (icon != null) {
                    androidx.compose.material3.Icon(icon, label, Modifier.size((if (primary) 24.dp else 22.dp) * k),
                        tint = if (on && !f && !primary) Kit.accent else ink)
                }
                if (showLabel) Text(label, color = ink, fontSize = (if (primary) 17.sp else 15.sp) * Kit.soft,
                    fontWeight = if (primary) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1, softWrap = false,
                    modifier = Modifier.padding(start = if (icon != null) 10.dp * k else 0.dp))
            }
            extra?.invoke(this)
        }
    }
}

/**
 * An icon-only action (details page): its name is shown by the row's caption, [count] in a small gold badge on the
 * top right (Sequel / Prequel, Connections, Production), [on] tints the icon in the accent.
 */
@Composable
fun KitIcon(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, on: Boolean = false, count: Int? = null) {
    val badge: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = if (count == null) null else {
        {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 1.dp).size(16.dp * Kit.soft).clip(CircleShape).background(Gold),
                contentAlignment = Alignment.Center) { Text("$count", color = Color.Black, fontSize = 10.sp * Kit.soft, fontWeight = FontWeight.Bold, maxLines = 1) }
        }
    }
    KitButton(label, icon, onClick, modifier, on = on, iconOnly = true, extra = badge)
}

/** Awards and badges: a warm yellow gold, the same in every skin. */
val Gold = Color(0xFFE8C15A)

/** Display text: a title in the look's display face. */
@Composable
fun DisplayText(t: String, size: TextUnit, modifier: Modifier = Modifier, color: Color = Palette.Text, maxLines: Int = 2) =
    Text(t, color = color, fontFamily = Kit.display, fontSize = size, lineHeight = size * 1.02f, maxLines = maxLines, fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-1.4).sp, modifier = modifier,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)

/**
 * A menu or prompt card in the look and skin (user 2026-11-25: no popup in the old washed-out grey): the skin's card,
 * the look's corner and hairline, the look's fonts and buttons (Inside). [title] in the display face, [sub] dim.
 */
@Composable
fun KitPopup(title: String?, modifier: Modifier = Modifier, sub: String? = null, eyebrow: String? = null,
             content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Inside(true) {
        val shape = RoundedCornerShape(Kit.cardR)
        androidx.compose.foundation.layout.Column(modifier.clip(shape).background(Kit.panel.copy(alpha = 0.98f)).border(1.dp, Kit.line, shape)
            .padding(horizontal = 22.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            eyebrow?.let { Eyebrow(it) }
            title?.let { DisplayText(it.uppercase(), 20.sp, maxLines = 2) }
            sub?.let { Text(it, color = Palette.TextDim, fontSize = 13.sp) }
            content()
        }
    }
}

/** The look's focus fill for list rows and small panels: (background, ink), as KitButton paints a focused button. */
val kitFocus: Pair<Color, Color> get() = Palette.Text to Theme.skin.base

/** A menu line in the look: a full-width button, label on the left (menus and prompts, in a [KitPopup]). */
@Composable
fun KitMenuItem(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) =
    KitButton(label, icon, onClick, modifier.fillMaxWidth(), wide = true)

/**
 * The look's colours as plain names, for screens drawn over video or art (player, Live TV, VOD, link list) so they
 * follow the skin and look like every other page: [bg]/[ink] = the focused row, [accent] = the skin's accent.
 */
object KitInk {
    val bg: Color get() = kitFocus.first
    val ink: Color get() = kitFocus.second
    val inkDim: Color get() = kitFocus.second.copy(alpha = 0.72f)
    val accent: Color get() = Kit.accent
    val panel: Color get() = Kit.panel
    val text: Color get() = Palette.Text
    val dim: Color get() = Palette.TextDim
}
