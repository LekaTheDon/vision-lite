package com.lekathedon.visionlite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
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
 * The three looks' parts (Tonal, Marquee, Halo), drawn in the skin's colours. Fonts are bundled, Latin only:
 * Tonal = Bricolage Grotesque + Figtree, Marquee = Archivo + Barlow Condensed, Halo = Plus Jakarta Sans (~0.6 MB raw).
 */
object Fonts {
    val tonalDisplay = FontFamily(Font(R.font.tonal_display_700, FontWeight.Bold), Font(R.font.tonal_display_800, FontWeight.ExtraBold))
    val tonalBody = FontFamily(Font(R.font.tonal_body_400, FontWeight.Normal), Font(R.font.tonal_body_500, FontWeight.Medium),
        Font(R.font.tonal_body_600, FontWeight.SemiBold), Font(R.font.tonal_body_700, FontWeight.Bold))
    val marquee = FontFamily(Font(R.font.marquee_400, FontWeight.Normal), Font(R.font.marquee_500, FontWeight.Medium),
        Font(R.font.marquee_700, FontWeight.Bold), Font(R.font.marquee_800, FontWeight.ExtraBold))
    val marqueeLabel = FontFamily(Font(R.font.marquee_label_600, FontWeight.SemiBold))
    val halo = FontFamily(Font(R.font.halo_400, FontWeight.Normal), Font(R.font.halo_500, FontWeight.Medium),
        Font(R.font.halo_700, FontWeight.Bold), Font(R.font.halo_800, FontWeight.ExtraBold))
}

/** The look in use, as values the screens draw with. */
object Kit {
    val style: Style get() = Theme.style
    val body: FontFamily get() = when (style) { Style.TONAL -> Fonts.tonalBody; Style.MARQUEE -> Fonts.marquee; Style.HALO -> Fonts.halo }
    val display: FontFamily get() = when (style) { Style.TONAL -> Fonts.tonalDisplay; Style.MARQUEE -> Fonts.marquee; Style.HALO -> Fonts.halo }
    /** Small labels: Marquee's condensed tracked capitals, the body font elsewhere. */
    val label: FontFamily get() = if (style == Style.MARQUEE) Fonts.marqueeLabel else body

    val accent: Color get() = Theme.accent
    val onAccent: Color get() = Theme.onAccent
    val card: Color get() = Theme.skin.card
    val card2: Color get() = Theme.skin.card2
    val line: Color get() = Theme.skin.text.copy(alpha = 0.13f)
    /** Tonal's coloured surface: the accent laid lightly over the card. */
    val tonal: Color get() = lerp(card, accent, 0.18f)

    /** Corners: cards, rows, buttons. */
    val cardR: Dp get() = when (style) { Style.TONAL -> 24.dp; Style.MARQUEE -> 10.dp; Style.HALO -> 18.dp }
    val rowR: Dp get() = when (style) { Style.TONAL -> 18.dp; Style.MARQUEE -> 10.dp; Style.HALO -> 14.dp }
    val buttonShape: Shape get() = when (style) { Style.TONAL -> RoundedCornerShape(18.dp); Style.MARQUEE -> RoundedCornerShape(10.dp); Style.HALO -> RoundedCornerShape(50) }

    /** The focus ring past Home: Tonal and Halo in the accent, Marquee in the text colour. */
    val ringWidth: Dp get() = when (style) { Style.TONAL -> 3.dp; Style.MARQUEE -> 2.dp; Style.HALO -> 2.5.dp }
    val ringColor: Color get() = if (style == Style.MARQUEE) Theme.skin.text else Theme.accent

    /** Settings › Button size: buttons and switches follow it fully, tiles and their text a little (smallest stays readable). */
    val k: Float get() = Theme.scale
    val soft: Float get() = 0.4f + 0.6f * Theme.scale

    fun lerp(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)
}

/** Screens past Home: the look's body font for every Text, its focus ring for every Focusable. */
@Composable
fun Inside(on: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalInside provides on,
        LocalTextStyle provides if (on) LocalTextStyle.current.copy(fontFamily = Kit.body) else LocalTextStyle.current, content = content)
}

/** A section's heading in the look: Tonal big and heavy, Marquee tracked capitals, Halo bold. */
@Composable
fun KitHeader(t: String, modifier: Modifier = Modifier, by: String? = null) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        when (Kit.style) {
            Style.TONAL -> Text(t, color = Palette.Text, fontFamily = Fonts.tonalDisplay, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp)
            Style.MARQUEE -> Text(t.uppercase(), color = Palette.Text, fontFamily = Fonts.marqueeLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.5.sp)
            Style.HALO -> Text(t, color = Palette.Text, fontFamily = Fonts.halo, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.2).sp)
        }
        by?.let { Text("  $it", color = Palette.TextDim, fontSize = 12.sp) }
    }
}

/** Small capitals above a title or a group: Marquee's condensed tracked label, plain spaced caps in the others. */
@Composable
fun Eyebrow(t: String, modifier: Modifier = Modifier, color: Color = Palette.TextDim) =
    Text(t.uppercase(), color = color, fontFamily = Kit.label, fontSize = if (Kit.style == Style.MARQUEE) 13.sp else 11.sp,
        fontWeight = if (Kit.style == Style.MARQUEE) FontWeight.SemiBold else FontWeight.Bold,
        letterSpacing = if (Kit.style == Style.MARQUEE) 3.5.sp else 1.6.sp, modifier = modifier, maxLines = 1)

/** A switch in the look: Tonal = chunky with a tick, Marquee = slim, Halo = "● On" / "○ Off" pill. */
@Composable
fun KitSwitch(on: Boolean, focused: Boolean, modifier: Modifier = Modifier) {
    val k = Kit.k
    when (Kit.style) {
        Style.TONAL -> Box(modifier.size(56.dp * k, 32.dp * k).clip(RoundedCornerShape(50)).background(if (on) Kit.accent else Kit.card2)
            .border(2.dp, if (on) Kit.accent else Palette.TextDim, RoundedCornerShape(50)).padding(horizontal = (if (on) 4.dp else 6.dp) * k),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
            if (on) Box(Modifier.size(24.dp * k).clip(CircleShape).background(Kit.onAccent), contentAlignment = Alignment.Center) {
                Text("✓", color = Kit.accent, fontSize = 14.sp * k, fontWeight = FontWeight.Bold)
            } else Box(Modifier.size(16.dp * k).clip(CircleShape).background(Palette.TextDim))
        }
        Style.MARQUEE -> Box(modifier.size(42.dp * k, 24.dp * k).clip(RoundedCornerShape(50)).background(if (on) Kit.accent else Kit.card2)
            .border(1.dp, if (on) Color.Transparent else Kit.line, RoundedCornerShape(50)).padding(3.dp * k),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(18.dp * k).clip(CircleShape).background(if (on) Kit.onAccent else Palette.TextDim))
        }
        Style.HALO -> {
            val ink = if (focused) Kit.onAccent else Palette.Text
            Text(if (on) "●  On" else "○  Off", fontFamily = Fonts.halo, fontSize = 13.sp * Kit.soft, fontWeight = FontWeight.Bold,
                color = if (on) (if (focused) Kit.accent else Theme.skin.base) else ink.copy(alpha = 0.75f),
                modifier = modifier.clip(RoundedCornerShape(50))
                    .then(if (on) Modifier.background(if (focused) Kit.onAccent else Palette.Text) else Modifier.border(1.5.dp, ink.copy(alpha = 0.6f), RoundedCornerShape(50)))
                    .padding(horizontal = 12.dp * Kit.soft, vertical = 5.dp * Kit.soft))
        }
    }
}

/** A small chip: Tonal tonal square-ish, Marquee outlined box, Halo round. */
@Composable
fun KitChip(t: String, modifier: Modifier = Modifier, strong: Boolean = false) {
    when (Kit.style) {
        Style.TONAL -> Text(t, color = if (strong) Kit.onAccent else Kit.lerp(Palette.Text, Kit.accent, 0.45f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = modifier.clip(RoundedCornerShape(10.dp)).background(if (strong) Kit.accent else Kit.tonal).padding(horizontal = 11.dp, vertical = 5.dp), maxLines = 1)
        Style.MARQUEE -> Text(t, color = Palette.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = modifier.border(1.dp, Palette.TextDim, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp), maxLines = 1)
        Style.HALO -> Text(t, color = if (strong) Kit.onAccent else Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = modifier.clip(RoundedCornerShape(50)).background(if (strong) Kit.accent else Color(0x99000000))
                .border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp), maxLines = 1)
    }
}

/**
 * A button in the look. [primary] = the main action (Play): Tonal tall accent block with a round icon, Marquee gradient
 * slab, Halo accent pill. Others: Tonal tonal block, Marquee glass with a hairline, Halo round glass. [iconOnly] shows
 * the label only while focused (Halo, Tonal) or never (Marquee squares).
 */
@Composable
fun KitButton(label: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false,
              on: Boolean = false, iconOnly: Boolean = false, extra: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null) {
    val style = Kit.style
    val shape = when (style) {
        Style.TONAL -> RoundedCornerShape(if (primary) 24.dp else 16.dp)
        Style.MARQUEE -> RoundedCornerShape(10.dp)
        Style.HALO -> RoundedCornerShape(50)
    }
    val k = Kit.k
    val h = when (style) { Style.TONAL -> if (primary) 60.dp else 52.dp; Style.MARQUEE -> 46.dp; Style.HALO -> 48.dp } * k
    Focusable(onClick, modifier, shape) { f ->
        val bg: Modifier
        val ink: Color
        when {
            primary && style == Style.MARQUEE -> {
                bg = Modifier.background(Brush.horizontalGradient(listOf(Kit.accent, Kit.lerp(Kit.accent, Color(0xFF7FB6FF), 0.45f))))
                ink = Kit.onAccent
            }
            primary -> { bg = Modifier.background(Kit.accent); ink = Kit.onAccent }
            f -> when (style) {
                Style.TONAL -> { bg = Modifier.background(Kit.lerp(Kit.card2, Kit.accent, 0.35f)); ink = Palette.Text }
                Style.MARQUEE -> { bg = Modifier.background(Palette.Text); ink = Theme.skin.base }
                Style.HALO -> { bg = Modifier.background(Kit.accent); ink = Kit.onAccent }
            }
            else -> when (style) {
                Style.TONAL -> { bg = Modifier.background(Kit.card2); ink = Palette.Text }
                Style.MARQUEE -> { bg = Modifier.background(Color(0x8C000000)).border(1.dp, Kit.line, shape); ink = Palette.Text }
                Style.HALO -> { bg = Modifier.background(Color(0x40000000)).border(1.dp, Kit.line, shape); ink = Palette.Text }
            }
        }
        val showLabel = !iconOnly || icon == null
        Box {
            Row(Modifier.height(h).then(bg).padding(horizontal = if (showLabel) (if (primary) 20.dp else 16.dp) * k else 0.dp)
                .then(if (!showLabel) Modifier.size(h, h) else Modifier),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (icon != null) {
                    if (primary && style == Style.TONAL) Box(Modifier.size(34.dp * k).clip(CircleShape).background(ink), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.Icon(icon, null, Modifier.size(20.dp * k), tint = Kit.accent)
                    } else androidx.compose.material3.Icon(icon, label, Modifier.size((if (primary) 24.dp else 22.dp) * k),
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
        letterSpacing = if (Kit.style == Style.MARQUEE) (-1.4).sp else (-0.8).sp, modifier = modifier,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
