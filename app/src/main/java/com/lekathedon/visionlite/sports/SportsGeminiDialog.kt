package com.lekathedon.visionlite.sports

import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/** Lite: the Gemini key is Settings › Integrations › AI › Gemini (QR / phone form, never typed with the remote). */
@Composable
internal fun SportsGeminiKeyDialog(@Suppress("UNUSED_PARAMETER") client: SportsGemini, onClose: () -> Unit) {
    LaunchedEffect(Unit) {
        SportsViewModel.nav?.push(com.lekathedon.visionlite.ui.Screen.Integration("gemini"))
        onClose()
    }
}

@Composable
internal fun SportsGeminiLookupDialog(client: SportsGemini, event: SportsEvent, region: String, onClose: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    var html by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    val uri = LocalUriHandler.current
    LaunchedEffect(event.id, revision) {
        busy = true; error = null; html = null
        runCatching { client.lookup(event, region) }.onSuccess { html = it }
            .onFailure { error = it.message?.take(200) ?: "Lookup failed." }
        busy = false
    }
    SportsDialog(onDismiss = onClose, width = 1000.dp) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LiveText("Gemini source lookup", size = 22.sp)
            LiveText("An informational answer; it does not replace confirmed scores or choose your stream.", size = 12.sp)
            if (busy) LiveText("Checking sources…", size = 15.sp)
            error?.let { LiveText(it, size = 14.sp) }
            html?.let { content ->
                AndroidView(modifier = Modifier.fillMaxWidth().height(390.dp), factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.domStorageEnabled = false
                        settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                if (request.isForMainFrame && request.hasGesture() && request.url.scheme == "https")
                                    runCatching { uri.openUri(request.url.toString()) }
                                return true
                            }
                        }
                        loadDataWithBaseURL("https://localhost.invalid/", content, "text/html", "UTF-8", null)
                    }
                }, onRelease = { it.stopLoading(); it.destroy() })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SportsPill("Close", onClick = onClose)
                SportsPill("Refresh", onClick = { if (!busy) revision++ })
            }
        }
    }
}
