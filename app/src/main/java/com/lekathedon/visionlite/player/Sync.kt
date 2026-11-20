@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lekathedon.visionlite.player

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.lekathedon.visionlite.App
import org.json.JSONObject

/**
 * Audio and subtitle delay (Vision's sync), remembered per show or film. ExoPlayer: audio (and text) samples are moved
 * by the audio delay (Vision's AudioDelayMediaSource) and the text renderers draw at the position moved back by both
 * delays; MPV: its audio-delay / sub-delay. A change flushes the buffer with a seek to where you are, so it applies at
 * once. Cost: two numbers per title in one pref.
 */
object Sync {
    @Volatile var audioMs = 0L
    @Volatile var subMs = 0L
    private var title = ""

    const val STEP = 50L
    const val LIMIT = 5_000L

    /** Loads the delays saved for [id]'s show / film (0 / 0 when none). */
    fun open(id: String) {
        title = id.substringBefore(':')
        val o = runCatching { JSONObject(App.instance.prefs.syncDelays.value.ifBlank { "{}" }).optJSONObject(title) }.getOrNull()
        audioMs = o?.optLong("a") ?: 0L
        subMs = o?.optLong("s") ?: 0L
    }

    fun set(audio: Long = audioMs, sub: Long = subMs) {
        audioMs = audio.coerceIn(-LIMIT, LIMIT); subMs = sub.coerceIn(-LIMIT, LIMIT)
        if (title.isBlank()) return
        val all = runCatching { JSONObject(App.instance.prefs.syncDelays.value.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        if (audioMs == 0L && subMs == 0L) all.remove(title) else all.put(title, JSONObject().put("a", audioMs).put("s", subMs))
        if (all.length() > 200) all.keys().asSequence().toList().take(all.length() - 200).forEach { all.remove(it) }
        App.instance.prefs.syncDelays.value = all.toString()
    }

    fun label(ms: Long) = when { ms == 0L -> "0 ms"; ms > 0 -> "+$ms ms"; else -> "$ms ms" }

    /** Every source ExoPlayer makes goes through the audio-delay wrapper. */
    fun wrap(f: MediaSource.Factory): MediaSource.Factory = object : MediaSource.Factory {
        override fun setDrmSessionManagerProvider(p: DrmSessionManagerProvider): MediaSource.Factory { f.setDrmSessionManagerProvider(p); return this }
        override fun setLoadErrorHandlingPolicy(p: LoadErrorHandlingPolicy): MediaSource.Factory { f.setLoadErrorHandlingPolicy(p); return this }
        override fun getSupportedTypes(): IntArray = f.supportedTypes
        override fun createMediaSource(mediaItem: MediaItem): MediaSource = AudioDelayMediaSource(f.createMediaSource(mediaItem)) { audioMs * 1000 }
    }
}
