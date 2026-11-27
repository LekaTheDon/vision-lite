package com.lekathedon.visionlite.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.lekathedon.visionlite.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.data.Profiles
import com.lekathedon.visionlite.data.Profiles.Profile
import com.lekathedon.visionlite.home.ProfileFace
import com.lekathedon.visionlite.phone.PhoneBox
import com.lekathedon.visionlite.phone.PhoneForm
import com.lekathedon.visionlite.ui.Button
import com.lekathedon.visionlite.ui.Focusable
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun android.content.Context.activity(): android.app.Activity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) { if (c is android.app.Activity) return c; c = c.baseContext }
    return null
}

/**
 * Vision's "Who's watching?": every profile as its picture circle (animated pictures move), a lock under the ones
 * with a PIN. Picking one asks its PIN when it has one; another profile restarts the app in that profile.
 * [atStart] = shown before Home (Back leaves the app); otherwise an overlay from the pill (Back closes it).
 */
@Composable
fun ProfileGate(atStart: Boolean, pinOnly: Boolean = false, onManage: (() -> Unit)? = null, onClose: () -> Unit) {
    val ctx = LocalContext.current
    Profiles.version.collectAsState().value
    val list = Profiles.all()
    var pinFor by remember { mutableStateOf<Profile?>(if (pinOnly) Profiles.current() else null) }
    fun enter(p: Profile) {
        if (p.id == Profiles.currentId()) onClose() else ctx.activity()?.let { Profiles.switchTo(it, p.id) }
    }
    Box(Modifier.fillMaxSize().background(LocalLook.current.page), contentAlignment = Alignment.Center) {
        val pf = pinFor
        if (pf != null) {
            PinPad("Enter the PIN for ${pf.name}", face = pf, check = { Profiles.checkPin(pf.id, it) },
                onOk = { enter(pf) },
                onCancel = { if (pinOnly && list.size == 1) ctx.activity()?.finish() else pinFor = null })
        } else {
            BackHandler { if (atStart) ctx.activity()?.finish() else onClose() }
            Faces("Who's watching?", list, Profiles.currentId(), onPick = { p -> if (p.locked) pinFor = p else enter(p) },
                onManage = onManage)
        }
    }
}

@Composable
private fun Faces(title: String, list: List<Profile>, current: Int, onPick: (Profile) -> Unit, onManage: (() -> Unit)? = null) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    // The profile in focus: its latest Continue watching title behind the faces (not for PIN profiles); none = plain
    // black page (user 2026-11-27: the page behind showed through). Logo and S·E top right, the progress bar under
    // the focused face, so it moves along with the focus.
    var at by remember { mutableStateOf<Profile?>(null) }
    val last = remember(at?.id) { at?.takeIf { !it.locked }?.let { LastWatched.of(it.id) } }
    val K = com.lekathedon.visionlite.ui.Kit
    val ink = com.lekathedon.visionlite.ui.KitInk
    Box(Modifier.fillMaxSize().background(LocalLook.current.page)) {
        androidx.compose.animation.Crossfade(last, animationSpec = androidx.compose.animation.core.tween(400), label = "who") { l ->
            if (l != null) Box(Modifier.fillMaxSize()) {
                coil3.compose.AsyncImage(l.backdrop, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to LocalLook.current.page.copy(alpha = 0.5f), 0.5f to LocalLook.current.page.copy(alpha = 0.72f), 1f to LocalLook.current.page)))
                Column(Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 64.dp).width(420.dp), horizontalAlignment = Alignment.End) {
                    Text("Continue watching", color = ink.dim, fontSize = 14.sp, fontFamily = K.body, fontWeight = FontWeight.Medium)
                    coil3.compose.SubcomposeAsyncImage(l.logo, l.name, Modifier.padding(top = 10.dp).sizeIn(maxWidth = 360.dp, maxHeight = 96.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit, alignment = Alignment.CenterEnd,
                        error = { com.lekathedon.visionlite.ui.DisplayText(l.name, 26.sp) })
                    if (l.season != null) Text("S${l.season} · E${l.episode}", color = ink.text, fontSize = 15.sp, fontFamily = K.body,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            com.lekathedon.visionlite.ui.DisplayText(title.uppercase(), 30.sp, maxLines = 1)
            LazyRow(Modifier.padding(top = 36.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                items(list, key = { it.id }) { p ->
                    val source = remember { MutableInteractionSource() }
                    val focused by source.collectIsFocusedAsState()
                    LaunchedEffect(focused) { if (focused) at = p }
                    val mod = if (p.id == current || (list.none { it.id == current } && p === list.first())) Modifier.focusRequester(first) else Modifier
                    Column(mod.width(150.dp).clickable(interactionSource = source, indication = null) { onPick(p) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(124.dp).then(if (focused) Modifier.border(K.ringWidth + 1.dp, K.ringColor, CircleShape) else Modifier).padding(6.dp)) {
                            ProfileFace(p.name, p.pic, p.color, 112.dp)
                        }
                        Text(p.name, color = if (focused) ink.text else ink.dim, fontSize = 16.sp, fontFamily = K.body, maxLines = 1,
                            fontWeight = if (focused || p.id == current) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(top = 10.dp))
                        Text(if (p.locked) "PIN" else " ", color = ink.dim, fontSize = 12.sp, fontFamily = K.body)
                        // This profile's progress, under its own face while it has the focus.
                        val l = if (focused && p.id == at?.id) last else null
                        androidx.compose.animation.AnimatedVisibility(l != null,
                            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInHorizontally { -it / 3 },
                            exit = androidx.compose.animation.fadeOut()) {
                            val pct = (l ?: last)?.progress?.coerceIn(0f, 100f) ?: 0f
                            Column(Modifier.padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.width(120.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(K.line)) {
                                    Box(Modifier.fillMaxWidth(pct / 100f).height(4.dp).background(K.accent))
                                }
                                Text("${pct.toInt()}% watched", color = ink.dim, fontSize = 12.sp, fontFamily = K.body, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
            }
            if (onManage != null) Button("Manage profiles", onManage, Modifier.padding(top = 36.dp))
        }
    }
}

/**
 * A 4-digit PIN: number keys on the remote, or the pad on screen. [check] = null when setting a new PIN (OK takes any
 * 4 digits). Wrong PIN: says so and clears.
 */
@Composable
fun PinPad(title: String, face: Profile? = null, check: ((String) -> Boolean)?, onOk: (String) -> Unit, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler { onCancel() }
    fun type(d: String) {
        if (pin.length >= 4) return
        wrong = false
        pin += d
        if (pin.length == 4) {
            val p = pin
            if (check == null || check(p)) onOk(p)
            else scope.launch { wrong = true; delay(350); pin = "" }
        }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    Column(Modifier.onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val code = e.key.nativeKeyCode
        when (code) {
            in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> { type((code - android.view.KeyEvent.KEYCODE_0).toString()); true }
            android.view.KeyEvent.KEYCODE_DEL -> { pin = pin.dropLast(1); true }
            else -> false
        }
    }, horizontalAlignment = Alignment.CenterHorizontally) {
        face?.let { ProfileFace(it.name, it.pic, it.color, 84.dp) }
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(4) { i ->
                Box(Modifier.size(18.dp).clip(CircleShape).background(if (i < pin.length) (if (wrong) Color(0xFFE53935) else Color.White) else Color(0x33FFFFFF)))
            }
        }
        Text(if (wrong) "Wrong PIN" else "Number keys on the remote work too", color = if (wrong) Color(0xFFE57373) else Color(0x88FFFFFF),
            fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "Clear", "0", "⌫")
        LazyVerticalGrid(GridCells.Fixed(3), Modifier.padding(top = 16.dp).width(260.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(keys) { i, k ->
                Focusable({
                    when (k) { "Clear" -> { pin = "" }; "⌫" -> { pin = pin.dropLast(1) }; else -> type(k) }
                }, if (i == 0) Modifier.focusRequester(first) else Modifier, shape = RoundedCornerShape(12.dp)) { f ->
                    Box(Modifier.fillMaxWidth().background(if (f) com.lekathedon.visionlite.ui.KitInk.bg else com.lekathedon.visionlite.ui.Kit.rowFill).padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                        Text(k, color = if (f) com.lekathedon.visionlite.ui.KitInk.ink else com.lekathedon.visionlite.ui.KitInk.text, fontSize = if (k.length > 1) 15.sp else 22.sp)
                    }
                }
            }
        }
    }
}

/**
 * One profile's page (Settings › Profiles): name, picture (Nuvio's avatars, or any picture/GIF link from the phone),
 * colour, PIN, delete. A locked profile that isn't the one in use asks its PIN first.
 */
@Composable
fun ProfileEdit(nav: Nav, id: Int) {
    Profiles.version.collectAsState().value
    val p = Profiles.byId(id) ?: run { LaunchedEffect(Unit) { nav.pop() }; return }
    var unlocked by remember(id) { mutableStateOf(!p.locked || id == Profiles.currentId()) }
    var mode by remember(id) { mutableStateOf("") }   // "", name, pic, link, pin-new, pin-confirm, pin-old, delete
    var newPin by remember { mutableStateOf("") }
    // Share settings (user 2026-11-27): profiles are separate unless one shares its setup with another, by choice.
    var shareTo by remember(id) { mutableStateOf<Profile?>(null) }
    var shareWhat by remember(id) { mutableStateOf<Profiles.Share?>(null) }
    var shared by remember(id) { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().background(LocalLook.current.page), contentAlignment = Alignment.Center) {
        when {
            !unlocked -> PinPad("Enter the PIN for ${p.name}", p, check = { Profiles.checkPin(id, it) }, onOk = { unlocked = true }, onCancel = { nav.pop() })
            mode == "pin-old" -> PinPad("Current PIN", p, check = { Profiles.checkPin(id, it) }, onOk = { mode = "pin-new" }, onCancel = { mode = "" })
            mode == "pin-new" -> PinPad("New 4-digit PIN", p, check = null, onOk = { newPin = it; mode = "pin-confirm" }, onCancel = { mode = "" })
            mode == "pin-confirm" -> PinPad("Same PIN again", p, check = { it == newPin }, onOk = { Profiles.setPin(id, it); mode = "" }, onCancel = { mode = "" })
            mode == "pin-remove" -> PinPad("PIN to remove", p, check = { Profiles.checkPin(id, it) }, onOk = { Profiles.setPin(id, ""); mode = "" }, onCancel = { mode = "" })
            mode == "name" -> Box(Modifier.padding(48.dp)) {
                PhoneBox("Profile name", listOf(PhoneForm.Field("Name", initial = p.name)), onSave = { v ->
                    v.firstOrNull()?.takeIf { it.isNotBlank() }?.let { Profiles.update(p.copy(name = it.take(24))) }; mode = "" }, onCancel = { mode = "" })
            }
            mode == "link" -> Box(Modifier.padding(48.dp)) {
                PhoneBox("Profile picture", listOf(PhoneForm.Field("Picture link (image or GIF)", hint = "https://…", initial = p.pic)), onSave = { v ->
                    val u = v.firstOrNull().orEmpty(); mode = ""
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) { Profiles.setPicture(id, u) } }, onCancel = { mode = "" })
            }
            mode == "share-pin" -> shareTo?.let { t -> PinPad("Enter the PIN for ${t.name}", t, check = { Profiles.checkPin(t.id, it) },
                onOk = { mode = "share-do" }, onCancel = { mode = "" }) }
            mode == "share-do" -> {
                val t = shareTo; val w = shareWhat
                LaunchedEffect(Unit) {
                    if (t != null && w != null) {
                        val n = Profiles.share(id, t.id, w)
                        if (t.id == Profiles.currentId()) { ctx.activity()?.let { Profiles.restart(it) } }
                        shared = "$n settings shared with ${t.name}"
                    }
                    mode = ""
                }
            }
            mode == "share-to" || mode == "share-what" -> {
                BackHandler { mode = if (mode == "share-what") "share-to" else "" }
                val first = remember(mode) { FocusRequester() }
                LaunchedEffect(mode) { withFrameNanos { }; runCatching { first.requestFocus() } }
                LazyColumn(Modifier.width(560.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    item {
                        com.lekathedon.visionlite.ui.DisplayText(if (mode == "share-to") "Share ${p.name}'s settings with" else "What to share with ${shareTo?.name}", 24.sp,
                            Modifier.padding(bottom = 18.dp))
                    }
                    if (mode == "share-to") {
                        val others = Profiles.all().filter { it.id != id }
                        items(others.size) { i -> val o = others[i]
                            Line(o.name, if (o.locked) "Its PIN is asked" else "", if (i == 0) Modifier.focusRequester(first) else Modifier) { shareTo = o; mode = "share-what" }
                        }
                    } else {
                        val opts = Profiles.Share.values().toList()
                        items(opts.size) { i -> val w = opts[i]
                            Line(w.label, w.sub, if (i == 0) Modifier.focusRequester(first) else Modifier) {
                                shareWhat = w; mode = if (shareTo?.locked == true && shareTo?.id != Profiles.currentId()) "share-pin" else "share-do"
                            }
                        }
                    }
                }
            }
            mode == "pic" -> AvatarPicker(p, onPick = { url -> mode = ""; scope.launch(kotlinx.coroutines.Dispatchers.IO) { Profiles.setPicture(id, url) } }, onLink = { mode = "link" }, onCancel = { mode = "" })
            else -> {
                BackHandler { nav.pop() }
                val first = remember { FocusRequester() }
                LaunchedEffect(mode) { withFrameNanos { }; runCatching { first.requestFocus() } }
                val inUse = id == Profiles.currentId()
                var confirmDelete by remember { mutableStateOf(false) }
                LazyColumn(Modifier.width(560.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            ProfileFace(p.name, p.pic, p.color, 120.dp)
                            com.lekathedon.visionlite.ui.DisplayText(p.name, 26.sp, Modifier.padding(top = 12.dp, bottom = 18.dp), maxLines = 1)
                        }
                    }
                    item { Line("Name", p.name, Modifier.focusRequester(first)) { mode = "name" } }
                    item { Line("Picture", if (p.pic.isBlank()) "Colour and initial" else "Picture set (GIFs move)") { mode = "pic" } }
                    item {
                        val i = Profiles.Colors.indexOf(p.color)
                        Line("Colour", "OK for the next one", swatch = p.color) { Profiles.update(p.copy(color = Profiles.Colors[(i + 1) % Profiles.Colors.size])) }
                    }
                    item { Line(if (p.locked) "Change PIN" else "Set a PIN", if (p.locked) "Asked when this profile is picked" else "Nobody else can open this profile") {
                        mode = if (p.locked) "pin-old" else "pin-new" } }
                    if (p.locked) item { Line("Remove PIN", "") { mode = "pin-remove" } }
                    if (Profiles.all().size > 1) item { Line("Share settings with…", shared ?: "Copy this profile's setup to another profile; you choose what") { shared = null; mode = "share-to" } }
                    if (!inUse) item { Line("Switch to this profile", "The app restarts in it") { ctx.activity()?.let { Profiles.switchTo(it, id) } } }
                    if (!inUse && id != 1) item {
                        Line(if (confirmDelete) "OK again to delete" else "Delete profile", "Its sign-ins, library, settings and history go too") {
                            if (confirmDelete) { Profiles.delete(id); nav.pop() } else confirmDelete = true
                        }
                    }
                    if (inUse) item { Text("In use now. Its accounts (Trakt, TorBox…) are set in Settings while it's in use.",
                        color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp)) }
                }
            }
        }
    }
}

@Composable
private fun Line(title: String, sub: String, modifier: Modifier = Modifier, swatch: String? = null, onClick: () -> Unit) {
    Focusable(onClick, modifier.padding(top = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(10.dp)) { f ->
        Row(Modifier.fillMaxWidth().background(if (f) com.lekathedon.visionlite.ui.KitInk.bg else com.lekathedon.visionlite.ui.Kit.rowFill).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = if (f) com.lekathedon.visionlite.ui.KitInk.ink else com.lekathedon.visionlite.ui.KitInk.text, fontSize = 16.sp)
                if (sub.isNotBlank()) Text(sub, color = if (f) com.lekathedon.visionlite.ui.KitInk.inkDim else Palette.TextDim, fontSize = 12.sp)
            }
            swatch?.let { c ->
                Box(Modifier.size(22.dp).clip(CircleShape).background(runCatching { Color(android.graphics.Color.parseColor(c)) }.getOrDefault(Color.Gray)))
            } ?: Text("›", color = if (f) com.lekathedon.visionlite.ui.KitInk.ink else Palette.TextDim, fontSize = 18.sp)
        }
    }
}

/** A picture link from the phone, or none (colour + initial). */
@Composable
private fun AvatarPicker(p: Profile, onPick: (String) -> Unit, onLink: () -> Unit, onCancel: () -> Unit) {
    BackHandler { onCancel() }
    var pics by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) { pics = emptyList() }   // pictures: a link from the phone, or colour + initial (no outside catalog)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Picture for ${p.name}", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button("Link from phone (image or GIF)", onLink, Modifier.focusRequester(first))
            Button("No picture", { onPick("") })
        }
        when {
            pics == null -> Text("Loading…", color = Palette.TextDim, fontSize = 14.sp)
            pics!!.isEmpty() -> Text("Send any picture or GIF link from the phone, or keep the colour and initial.",
                color = Palette.TextDim, fontSize = 14.sp)
            else -> LazyVerticalGrid(GridCells.Adaptive(96.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(pics!!) { _, url ->
                    val source = remember { MutableInteractionSource() }
                    val focused by source.collectIsFocusedAsState()
                    Box(Modifier.size(96.dp).clickable(interactionSource = source, indication = null) { onPick(url) }
                        .then(if (focused) Modifier.border(3.dp, Color.White, CircleShape) else Modifier).padding(5.dp)) {
                        ProfileFace(p.name, url, p.color, 86.dp)
                    }
                }
            }
        }
    }
}
