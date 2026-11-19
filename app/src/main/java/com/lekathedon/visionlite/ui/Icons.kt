package com.lekathedon.visionlite.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The few icons the app draws (Material paths, 24×24); no icon library, a few hundred bytes each. */
object Icons {
    private fun icon(name: String, vararg paths: String): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.White)) }
    }.build()

    val Play by lazy { icon("play", "M8,5v14l11,-7z") }
    val Pause by lazy { icon("pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z") }
    val Restart by lazy { icon("restart", "M12,5V1L7,6l5,5V7c3.31,0 6,2.69 6,6s-2.69,6 -6,6 -6,-2.69 -6,-6H4c0,4.42 3.58,8 8,8s8,-3.58 8,-8 -3.58,-8 -8,-8z") }
    val Next by lazy { icon("next", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z") }
    val Audio by lazy { icon("audio", "M12,3v9.28c-0.47,-0.17 -0.97,-0.28 -1.5,-0.28C8.01,12 6,14.01 6,16.5S8.01,21 10.5,21c2.31,0 4.2,-1.75 4.45,-4H15V6h4V3h-7z") }
    val Subtitles by lazy { icon("subtitles", "M20,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2zM4,12h4v2H4v-2zM14,18H4v-2h10v2zM20,18h-4v-2h4v2zM20,14H10v-2h10v2z") }
    val Speed by lazy { icon("speed", "M20.38,8.57l-1.23,1.85a8,8 0,0 1,-0.22 7.58H5.07A8,8 0,0 1,15.58 6.85l1.85,-1.23A10,10 0,0 0,3.35 19a2,2 0,0 0,1.72 1h13.85a2,2 0,0 0,1.74 -1,10 10,0 0,0 -0.27,-10.44zM10.59,15.41a2,2 0,0 0,2.83 0l5.66,-8.49 -8.49,5.66a2,2 0,0 0,0 2.83z") }
    val Aspect by lazy { icon("aspect", "M19,12h-2v3h-3v2h5v-5zM7,9h3V7H5v5h2V9zM21,3H3c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h18c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM21,19.01H3V4.99h18v14.02z") }
    val Hdr by lazy { icon("hdr", "M21,11.5v-1c0,-0.8 -0.7,-1.5 -1.5,-1.5H16v6h1.5v-2h1.1l0.9,2H21l-0.9,-2.1c0.5,-0.3 0.9,-0.8 0.9,-1.4zM19.5,11.5h-2v-1h2v1zM6.5,11h-2V9H3v6h1.5v-2.5h2V15H8V9H6.5v2zM13,9H9.5v6H13c0.8,0 1.5,-0.7 1.5,-1.5v-3c0,-0.8 -0.7,-1.5 -1.5,-1.5zM13,13.5h-2v-3h2v3z") }
    val Info by lazy { icon("info", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-6h2v6zM13,9h-2V7h2v2z") }
    val Engine by lazy { icon("engine", "M6.99,11L3,15l3.99,4v-3H14v-2H6.99v-3zM21,9l-3.99,-4v3H10v2h7.01v3L21,9z") }
    val Sources by lazy { icon("sources", "M3,13h2v-2H3v2zM3,17h2v-2H3v2zM3,9h2V7H3v2zM7,13h14v-2H7v2zM7,17h14v-2H7v2zM7,7v2h14V7H7z") }
}
