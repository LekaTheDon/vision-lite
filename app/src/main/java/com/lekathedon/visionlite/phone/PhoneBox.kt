package com.lekathedon.visionlite.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.ui.Button
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.Qr

/**
 * A form shown with its QR code: scan and send from the phone (saved at once), or type on the TV and press Save.
 * The phone server lives only while this is on screen.
 */
@Composable
fun PhoneBox(title: String, fields: List<PhoneForm.Field>, onSave: (List<String>) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val values = remember { fields.map { mutableStateOf(it.initial) } }
    var url by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    DisposableEffect(Unit) {
        val ip = PhoneForm.lanIp(context)
        val form = ip?.let {
            PhoneForm.start(title, fields) { sent -> main.post { onSave(sent.map { v -> v.trim() }) } }
        }
        url = form?.url(ip!!)
        if (form == null) note = "Phone typing needs the box on your network; type on the TV instead."
        onDispose { form?.stop() }
    }
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
    androidx.activity.compose.BackHandler { onCancel() }
    Row(Modifier.padding(top = 12.dp).background(Palette.Card).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        url?.let { u ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Qr(u, 190.dp)
                Text(u, color = Palette.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Column(Modifier.fillMaxWidth(0.6f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Palette.Text, fontSize = 18.sp)
            Text(if (url != null) "Scan with your phone and press Send, or type here." else note, color = Palette.TextDim, fontSize = 13.sp)
            fields.forEachIndexed { i, f ->
                Text(f.label, color = Palette.TextDim, fontSize = 13.sp)
                BasicTextField(values[i].value, { values[i].value = it }, singleLine = true,
                    textStyle = TextStyle(color = Palette.Text, fontSize = 15.sp), cursorBrush = SolidColor(Color.White),
                    visualTransformation = if (f.secret) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth().background(Color(0x22FFFFFF)).padding(10.dp),
                    decorationBox = { inner -> if (values[i].value.isEmpty()) Text(f.hint, color = Palette.TextDim, fontSize = 15.sp); inner() })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button("Cancel", onCancel, Modifier.focusRequester(cancel))
                Button("Save", { onSave(values.map { it.value.trim() }) })
            }
        }
    }
}
