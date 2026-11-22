package com.lekathedon.visionlite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * While a title is being opened (links searched, then the first frame): the title's big backdrop with its logo, never
 * a black page (user 2026-11-22). [id] = "tt…" or "tt…:s:e"; other ids show the name on the page colour.
 */
@Composable
fun LoadingHero(id: String, title: String, status: String, sub: String = "", modifier: Modifier = Modifier, extra: @Composable () -> Unit = {}) {
    val imdb = id.substringBefore(':').takeIf { it.startsWith("tt") }
    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (imdb != null) Img("https://images.metahub.space/background/large/$imdb/img", 1280.dp, 720.dp, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xE6000000), 0.45f to Color(0x80000000), 0.8f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xCC000000))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 64.dp, bottom = 64.dp)) {
            var logoFailed by remember(imdb) { mutableStateOf(imdb == null) }
            val name = title.replace(Regex("""\s+S\d+E\d+$"""), "")
            if (!logoFailed) coil3.compose.AsyncImage("https://images.metahub.space/logo/medium/$imdb/img", name,
                Modifier.sizeIn(maxWidth = 420.dp, maxHeight = 130.dp), contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                onError = { logoFailed = true })
            else Text(name, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Regex("""S(\d+)E(\d+)$""").find(title)?.let { m ->
                Text("Season ${m.groupValues[1].toInt()} · Episode ${m.groupValues[2].toInt()}", color = Color(0xE6FFFFFF), fontSize = 16.sp,
                    modifier = Modifier.padding(top = 10.dp))
            }
            Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                Text(status, color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(start = 12.dp))
            }
            if (sub.isNotEmpty()) Text(sub, color = Color(0xB3FFFFFF), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            extra()
        }
    }
}
