package com.lekathedon.visionlite.iptv

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.ui.Button
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URI

@Serializable
data class Rec(
    val id: String, val title: String, val channel: String, val url: String, val headers: Map<String, String> = emptyMap(),
    val start: Long, val stop: Long, val file: String, val state: String = "scheduled", val note: String = ""
)

/**
 * Recordings: a channel copied to a file, now (until the show ends) or at a show's time (an alarm wakes the
 * recorder). HLS is followed segment by segment, anything else is copied as it streams. Nothing runs unless a
 * recording is due; the recorder is a foreground service only while it records.
 */
object Recorder {
    private const val TAG = "Recorder"
    private val ser = ListSerializer(Rec.serializer())
    private val _list = MutableStateFlow(read())
    val list: StateFlow<List<Rec>> get() = _list
    internal val active = MutableStateFlow(emptySet<String>())

    private fun read(): List<Rec> = runCatching { Net.json.decodeFromString(ser, App.instance.prefs.recordings.value) }.getOrDefault(emptyList())
    @Synchronized internal fun update(id: String, f: (Rec) -> Rec) { save(_list.value.map { if (it.id == id) f(it) else it }) }
    @Synchronized private fun save(l: List<Rec>) { _list.value = l; App.instance.prefs.recordings.value = Net.json.encodeToString(ser, l) }

    fun dir(): File = (App.instance.getExternalFilesDir("Recordings") ?: File(App.instance.filesDir, "rec")).apply { mkdirs() }

    private fun make(c: Channel, title: String, start: Long, stop: Long): Rec {
        val id = "r${System.currentTimeMillis()}"
        val safe = "${c.name} - $title".replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(80)
        return Rec(id, title, c.name, c.url, c.headers, start, stop, File(dir(), "$safe-$id.ts").absolutePath)
    }

    /** Records [c] from now until [stop] (the show's end, or 2 hours when the guide has none). */
    fun recordNow(c: Channel, title: String, stop: Long?) {
        val now = System.currentTimeMillis()
        val r = make(c, title, now, stop?.takeIf { it > now } ?: (now + 2 * 3600_000L))
        save(_list.value + r)
        start(r.id)
    }

    /** Schedules a show; recording starts a minute early and ends two minutes late. */
    fun schedule(c: Channel, pr: EpgProgram) {
        if (isSet(c, pr)) return
        val r = make(c, pr.title, pr.startMs - 60_000L, pr.stopMs + 120_000L)
        save(_list.value + r)
        alarm(r)
    }

    /** Records [c] from [start] to [stop] (Sports: a game, a few minutes early, as long as you chose). */
    fun scheduleAt(c: Channel, title: String, start: Long, stop: Long): Rec {
        val now = System.currentTimeMillis()
        val r = make(c, title, maxOf(start, now), stop)
        save(_list.value + r)
        if (r.start <= now + 5_000) start(r.id) else alarm(r)
        return r
    }

    fun isRecording(url: String, start: Long) = _list.value.any { it.url == url && (it.state == "scheduled" || it.state == "recording") && kotlin.math.abs(it.start - start) < 3600_000L }

    fun isSet(c: Channel, pr: EpgProgram) = _list.value.any { it.url == c.url && it.title == pr.title && it.start == pr.startMs - 60_000L && it.state == "scheduled" }

    fun unschedule(c: Channel, pr: EpgProgram) { _list.value.filter { it.url == c.url && it.title == pr.title && it.state == "scheduled" }.forEach { cancel(it.id) } }

    fun cancel(id: String) {
        val r = _list.value.firstOrNull { it.id == id } ?: return
        if (r.state == "scheduled") { alarmManager().cancel(pending(id)); save(_list.value.filter { it.id != id }) }
        else stop(id)
    }

    fun stop(id: String) { App.instance.startService(Intent(App.instance, RecordService::class.java).setAction("stop").putExtra("id", id)) }

    fun delete(id: String) {
        val r = _list.value.firstOrNull { it.id == id } ?: return
        if (id in active.value) stop(id)
        alarmManager().cancel(pending(id))
        File(r.file).delete()
        save(_list.value.filter { it.id != id })
    }

    internal fun start(id: String) {
        val i = Intent(App.instance, RecordService::class.java).putExtra("id", id)
        if (Build.VERSION.SDK_INT >= 26) App.instance.startForegroundService(i) else App.instance.startService(i)
    }

    /** After a restart of the box, the alarms are set again. */
    internal fun rearm() {
        val now = System.currentTimeMillis()
        _list.value.forEach { r ->
            when {
                r.state == "scheduled" && r.stop > now -> alarm(r)
                r.state == "recording" && r.id !in active.value -> update(r.id) { it.copy(state = if (File(it.file).length() > 0) "done" else "failed", note = "Stopped early") }
            }
        }
    }

    private fun alarmManager() = App.instance.getSystemService(AlarmManager::class.java)
    private fun pending(id: String) = PendingIntent.getBroadcast(App.instance, id.hashCode(), Intent(App.instance, RecAlarm::class.java).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun alarm(r: Rec) {
        val am = alarmManager()
        val at = maxOf(r.start, System.currentTimeMillis() + 1000)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(r.id))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(r.id))
    }

    /** Copies [r] to its file until its stop time or until cancelled. */
    internal suspend fun capture(r: Rec) {
        val out = File(r.file)
        update(r.id) { it.copy(state = "recording") }
        var err = ""
        FileOutputStream(out, true).use { fos ->
            while (kotlinx.coroutines.currentCoroutineContext().isActive && System.currentTimeMillis() < r.stop) {
                try {
                    val (body, type, final) = fetch(r.url, r.headers)
                    if (type.contains("mpegurl", true) || body.startsWith("#EXTM3U")) hls(final, body, r, fos)
                    else stream(final, r, fos)
                    err = ""
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    err = e.message ?: "error"; Log.w(TAG, "${r.title}: $err"); delay(3000)
                }
            }
        }
        update(r.id) { it.copy(state = if (out.length() > 0) "done" else "failed", note = if (out.length() > 0) "" else err) }
    }

    /** First look at the link: returns its text when it's a playlist (else ""), its type and its final address. */
    private fun fetch(url: String, headers: Map<String, String>): Triple<String, String, String> {
        Net.http.newCall(req(url, headers)).execute().use { res ->
            if (!res.isSuccessful) throw java.io.IOException("HTTP ${res.code}")
            val type = res.header("Content-Type").orEmpty()
            val final = res.request.url.toString()
            val text = if (type.contains("mpegurl", true) || final.substringBefore('?').endsWith(".m3u8")) res.body!!.string() else ""
            return Triple(text, type, final)
        }
    }

    private fun req(url: String, headers: Map<String, String>) = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()

    private suspend fun stream(url: String, r: Rec, fos: FileOutputStream) {
        Net.http.newBuilder().readTimeout(java.time.Duration.ofSeconds(30)).build().newCall(req(url, r.headers)).execute().use { res ->
            val inp = res.body!!.byteStream()
            val buf = ByteArray(64 * 1024)
            while (kotlinx.coroutines.currentCoroutineContext().isActive && System.currentTimeMillis() < r.stop) {
                val n = inp.read(buf); if (n < 0) break
                fos.write(buf, 0, n)
            }
        }
    }

    private suspend fun hls(base: String, first: String, r: Rec, fos: FileOutputStream) {
        var url = base; var text = first
        if (text.contains("#EXT-X-STREAM-INF")) {
            val lines = text.lines()
            val best = lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF") }.maxByOrNull {
                Regex("BANDWIDTH=(\\d+)").find(lines[it])?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            } ?: return
            url = URI(url).resolve(lines.drop(best + 1).first { it.isNotBlank() && !it.startsWith("#") }.trim()).toString()
            text = get(url, r.headers)
        }
        if (text.contains("#EXT-X-KEY") && !text.contains("METHOD=NONE")) throw java.io.IOException("This channel is encrypted; it can't be recorded")
        val seen = LinkedHashSet<String>()
        var mapDone = false
        while (kotlinx.coroutines.currentCoroutineContext().isActive && System.currentTimeMillis() < r.stop) {
            val lines = text.lines()
            if (!mapDone) lines.firstOrNull { it.startsWith("#EXT-X-MAP") }?.let { m ->
                Regex("URI=\"([^\"]+)\"").find(m)?.groupValues?.get(1)?.let { fos.write(bytes(URI(url).resolve(it).toString(), r.headers)) }
            }
            mapDone = true
            val segs = lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { URI(url).resolve(it.trim()).toString() }
            for (s in segs) if (seen.add(s)) { fos.write(bytes(s, r.headers)); if (System.currentTimeMillis() >= r.stop) break }
            while (seen.size > 200) seen.remove(seen.first())
            if (text.contains("#EXT-X-ENDLIST")) break
            val target = Regex("#EXT-X-TARGETDURATION:(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 6
            delay((target * 500).coerceIn(1000, 6000))
            text = get(url, r.headers)
        }
    }

    private fun get(url: String, h: Map<String, String>) = Net.http.newCall(req(url, h)).execute().use { if (!it.isSuccessful) throw java.io.IOException("HTTP ${it.code}"); it.body!!.string() }
    private fun bytes(url: String, h: Map<String, String>) = Net.http.newCall(req(url, h)).execute().use { if (!it.isSuccessful) throw java.io.IOException("HTTP ${it.code}"); it.body!!.bytes() }
}

/** Wakes the recorder at a show's time, and sets the alarms again after the box restarts. */
class RecAlarm : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) { Recorder.rearm(); return }
        intent.getStringExtra("id")?.let { Recorder.start(it) }
    }
}

/** Runs the recordings that are due, as a foreground service, and stops itself when none is left. */
class RecordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        foreground()
        val id = intent?.getStringExtra("id")
        if (intent?.action == "stop") { id?.let { jobs[it]?.cancel() }; return START_NOT_STICKY }
        val r = Recorder.list.value.firstOrNull { it.id == id }
        if (r == null || id in jobs || r.stop <= System.currentTimeMillis()) { done(); return START_NOT_STICKY }
        jobs[r.id] = scope.launch {
            try { Recorder.capture(r) } finally {
                Recorder.update(r.id) { if (it.state == "recording") it.copy(state = "done") else it }
                synchronized(jobs) { jobs.remove(r.id) }
                Recorder.active.value = Recorder.active.value - r.id
                done()
            }
        }
        Recorder.active.value = Recorder.active.value + r.id
        return START_NOT_STICKY
    }

    private fun done() { synchronized(jobs) { if (jobs.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } } }

    private fun foreground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("rec", "Recordings", NotificationManager.IMPORTANCE_LOW))
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "rec") else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(android.R.drawable.presence_video_online).setContentTitle("Nuvio Vision is recording").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(7, n)
    }

    override fun onDestroy() { scope.coroutineContext[Job]?.cancel(); super.onDestroy() }
}

/** Recordings: what's coming, what's recording, what's done. Play, stop, cancel or delete. */
@Composable
fun RecordingsScreen(nav: Nav) {
    val list by Recorder.list.collectAsState()
    val active by Recorder.active.collectAsState()
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(5000); tick++ } }
    val fmt = remember { java.text.SimpleDateFormat("EEE d MMM HH:mm", java.util.Locale.getDefault()) }
    val sorted = list.sortedWith(compareBy<Rec>({ when { it.id in active -> 0; it.state == "scheduled" -> 1; else -> 2 } }, { if (it.state == "scheduled") it.start else -it.start }))
    Column(Modifier.fillMaxSize().background(LocalLook.current.page).padding(start = 40.dp, end = 30.dp, top = 24.dp)) {
        Text("Recordings", color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(if (list.isEmpty()) "Nothing yet. In Live TV's guide: OK on a coming show schedules it; hold OK on what's on now records it." else "Saved to ${Recorder.dir().absolutePath}",
            color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        LazyColumn(Modifier.fillMaxSize().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
            items(sorted, key = { it.id }) { r ->
                val rec = r.id in active
                val size = remember(r.file, tick) { File(r.file).length() }
                val state = when { rec -> "● Recording · ${size / 1_048_576} MB"; r.state == "scheduled" -> "Scheduled"; r.state == "failed" -> "Failed" + (r.note.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: "")
                    else -> "${size / 1_048_576} MB" + (r.note.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "") }
                Row(Modifier.fillMaxWidth().background(Palette.Card).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.title, color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("${r.channel} · ${fmt.format(java.util.Date(r.start))} – ${fmt.format(java.util.Date(r.stop)).substringAfterLast(' ')} · $state",
                            color = if (rec) androidx.compose.ui.graphics.Color(0xFFFF6B6B) else Palette.TextDim, fontSize = 13.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (size > 0) Button("Play", { nav.push(Screen.Player(File(r.file).toURI().toString(), r.title, type = "vod")) })
                        if (rec) Button("Stop", { Recorder.stop(r.id) })
                        if (r.state == "scheduled") Button("Cancel", { Recorder.cancel(r.id) })
                        else if (!rec) Button("Delete", { Recorder.delete(r.id) })
                    }
                }
            }
        }
    }
}
