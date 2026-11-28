package com.lekathedon.visionlite.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.lekathedon.visionlite.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.player.pics.SeekPics

private fun painterOf(p: SeekPics.Pic?): BitmapPainter? = p?.bitmap?.let { BitmapPainter(it.asImageBitmap()) }

/**
 * Seek thumbnails above the bar while a seek is pending (own engine, player/pics): once most of the film has pictures,
 * a strip of neighbours either side of a larger, framed centre one (where the seek lands); until then the single
 * nearest picture, following the cursor. Nothing shows when there is no picture yet.
 */
@Composable
internal fun SeekPicsPane(seekMs: Long, durMs: Long, modifier: Modifier = Modifier) {
    if (durMs <= 0) return
    val tick by SeekPics.tick
    val spacing = remember(tick) { SeekPics.stripSpacingMs() }
    val centre = remember(seekMs, tick) { painterOf(SeekPics.pictureFor(seekMs)) }
    val aspect = centre?.intrinsicSize?.let { it.width / it.height } ?: SeekPics.aspect() ?: return
    val K = com.lekathedon.visionlite.ui.Kit
    val ring = com.lekathedon.visionlite.ui.Palette.Text
    val label = @Composable { Text(clock(seekMs), color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(bottom = 6.dp)) }
    if (spacing != null) {
        val centreH = 127.dp; val tileH = 95.dp; val gap = 10.dp
        Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            label()
            Row(verticalAlignment = Alignment.Bottom) {
                for (k in -3..3) {
                    if (k == 0) {
                        Box(Modifier.padding(horizontal = gap / 2).height(centreH).width(centreH * aspect).clip(RoundedCornerShape(K.rowR))
                            .background(Color.Black).border(2.dp, ring, RoundedCornerShape(K.rowR))) {
                            centre?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                        }
                    } else {
                        val t = (seekMs / spacing + k) * spacing
                        val p = remember(t, tick) { if (t in 0..durMs) painterOf(SeekPics.exactFor(t)) else null }
                        Box(Modifier.padding(horizontal = gap / 2, vertical = (centreH - tileH) / 2).height(tileH).width(tileH * aspect)
                            .clip(RoundedCornerShape(K.rowR)).background(if (t in 0..durMs) Color(0x66000000) else Color.Transparent)) {
                            p?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                        }
                    }
                }
            }
        }
        return
    }
    if (centre == null) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val h = 108.dp; val w = h * aspect
        val x = ((maxWidth - w) * (seekMs.toFloat() / durMs).coerceIn(0f, 1f))
        Column(Modifier.offset(x = x).width(w), horizontalAlignment = Alignment.CenterHorizontally) {
            label()
            Box(Modifier.height(h).width(w).clip(RoundedCornerShape(K.rowR)).border(2.dp, ring, RoundedCornerShape(K.rowR))) {
                Image(centre, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            }
        }
    }
}
