package com.lekathedon.visionlite.player.mpv

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode

/**
 * MPV dressed as a media3 [Player], so the controls, Trakt sync, resume and the phone link work the same on both
 * engines. State is read from mpv every 500 ms while it exists (position itself is read live when asked).
 */
@OptIn(UnstableApi::class)
class MpvPlayer(val view: MpvView, private val url: String, private val headers: Map<String, String>, private val startMs: Long) :
    SimpleBasePlayer(Looper.getMainLooper()) {

    /** The file's tracks were read (Smart Auto looks at the video codec then). */
    var onLoaded: (() -> Unit)? = null
    /** mpv gave up on the file (reason text); only before [firstFrame] the engine switch takes it. */
    var onFailed: ((String) -> Unit)? = null
    @Volatile var firstFrame = false; private set

    private val main = Handler(Looper.getMainLooper())
    private var playWhenReady = true
    private var prepared = false
    private var error: PlaybackException? = null
    private var speed = 1f
    private var params = TrackSelectionParameters.DEFAULT
    private var tracks = Tracks.EMPTY
    private var tracksDirty = true
    private var released = false

    private val observer = object : MPV.EventObserver {
        override fun eventProperty(property: String) { if (property == "track-list") { tracksDirty = true; main.post { invalidateState() } } }
        override fun eventProperty(property: String, value: Long) {}
        override fun eventProperty(property: String, value: Boolean) {}
        override fun eventProperty(property: String, value: String) {}
        override fun eventProperty(property: String, value: Double) {}
        override fun eventProperty(property: String, value: MPVNode) {}
        override fun event(eventId: Int, data: MPVNode) {
            when (eventId) {
                MPV.mpvEvent.MPV_EVENT_FILE_LOADED -> main.post { tracksDirty = true; invalidateState(); onLoaded?.invoke() }
                MPV.mpvEvent.MPV_EVENT_PLAYBACK_RESTART, MPV.mpvEvent.MPV_EVENT_VIDEO_RECONFIG -> main.post {
                    firstFrame = true
                    invalidateState()
                }
                MPV.mpvEvent.MPV_EVENT_END_FILE -> {
                    val m = runCatching { data.asMap() }.getOrNull().orEmpty()
                    if (m["reason"]?.asString()?.trim().equals("error", true)) {
                        val why = m["file_error"]?.asString()?.trim().orEmpty().ifBlank { "mpv couldn't play this file" }
                        main.post {
                            error = PlaybackException("MPV: $why", null, PlaybackException.ERROR_CODE_UNSPECIFIED)
                            invalidateState()
                            onFailed?.invoke(why)
                        }
                    }
                }
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() { if (released) return; invalidateState(); main.postDelayed(this, 500) }
    }

    init {
        view.mpv.addObserver(observer)
        view.load(url, headers, startMs)
        prepared = true
        main.postDelayed(tick, 500)
    }

    override fun getState(): State {
        if (tracksDirty && view.ready) { tracks = buildTracks(); tracksDirty = false }
        val dur = view.durationMs()
        val stateNow = when {
            error != null -> Player.STATE_IDLE
            !prepared -> Player.STATE_IDLE
            view.eof() -> Player.STATE_ENDED
            dur <= 0 && !view.seekable() && !firstFrame -> Player.STATE_BUFFERING
            view.pausedForCache() || view.seeking() || !firstFrame -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }
        val item = MediaItemData.Builder(url)
            .setMediaItem(MediaItem.fromUri(url))
            .setDurationUs(if (dur > 0) dur * 1000 else C.TIME_UNSET)
            .setIsSeekable(dur > 0 && view.seekable())
            .setIsDynamic(dur <= 0)
            .setTracks(tracks)
            .build()
        return State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAll(
                COMMAND_PLAY_PAUSE, COMMAND_PREPARE, COMMAND_STOP, COMMAND_RELEASE, COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                COMMAND_SEEK_TO_DEFAULT_POSITION, COMMAND_SET_SPEED_AND_PITCH, COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_TIMELINE,
                COMMAND_GET_TRACKS, COMMAND_SET_TRACK_SELECTION_PARAMETERS
            ).build())
            .setPlaylist(listOf(item))
            .setPlayWhenReady(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(stateNow)
            .setPlayerError(error)
            .setPlaybackParameters(PlaybackParameters(speed))
            .setTrackSelectionParameters(params)
            .setIsLoading(stateNow == Player.STATE_BUFFERING)
            .setContentPositionMs { view.positionMs() }
            .setContentBufferedPositionMs { view.positionMs() + (view.cacheAheadSec() * 1000).toLong() }
            .setTotalBufferedDurationMs { (view.cacheAheadSec() * 1000).toLong() }
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        view.setPaused(!playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (error != null) { error = null; firstFrame = false; view.load(url, headers, view.positionMs()) }
        prepared = true
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> { view.setPaused(true); return Futures.immediateVoidFuture() }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        view.seekTo(if (positionMs == C.TIME_UNSET) 0 else positionMs)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        speed = playbackParameters.speed
        view.setSpeed(speed)
        return Futures.immediateVoidFuture()
    }

    /** The controls pick a track by override (audio / subtitles) or turn subtitles off; mpv gets aid / sid. */
    override fun handleSetTrackSelectionParameters(trackSelectionParameters: TrackSelectionParameters): ListenableFuture<*> {
        val p = trackSelectionParameters
        fun idFor(type: Int): Int? = p.overrides.values.firstOrNull { it.type == type }
            ?.let { o -> o.mediaTrackGroup.getFormat(o.trackIndices.firstOrNull() ?: 0).id?.substringAfterLast(':')?.toIntOrNull() }
        idFor(C.TRACK_TYPE_AUDIO)?.let { view.selectAudio(it) }
        if (p.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) view.subsOff() else idFor(C.TRACK_TYPE_TEXT)?.let { view.selectSub(it) }
        params = p
        tracksDirty = true
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        main.removeCallbacks(tick)
        runCatching { view.mpv.removeObserver(observer) }
        view.release()
        return Futures.immediateVoidFuture()
    }

    private fun buildTracks(): Tracks = Tracks(view.tracks().map { t ->
        val f = Format.Builder()
            .setId("mpv:${t.type}:${t.id}")
            .setLanguage(t.lang)
            .setLabel(t.title)
            .setSampleMimeType(mimeOf(t.type, t.codec))
            .setChannelCount(t.channels ?: Format.NO_VALUE)
            .setSampleRate(t.sampleRate ?: Format.NO_VALUE)
            .setSelectionFlags(if (t.forced) C.SELECTION_FLAG_FORCED else 0)
            .build()
        Tracks.Group(TrackGroup("mpv:${t.type}:${t.id}", f), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(t.selected))
    })

    companion object {
        /** mpv's codec names as media3 MIME types (so the panels name them as on ExoPlayer). */
        fun mimeOf(type: String, codec: String?): String {
            val c = codec?.lowercase().orEmpty()
            return when (type) {
                "audio" -> when (c) {
                    "ac3" -> "audio/ac3"; "eac3" -> "audio/eac3"; "truehd", "mlp" -> "audio/true-hd"; "dts" -> "audio/vnd.dts"
                    "aac" -> "audio/mp4a-latm"; "opus" -> "audio/opus"; "flac" -> "audio/flac"; "mp3" -> "audio/mpeg"
                    "vorbis" -> "audio/vorbis"; "alac" -> "audio/alac"
                    else -> if (c.startsWith("pcm")) "audio/raw" else "audio/x-${c.ifBlank { "unknown" }}"
                }
                "sub" -> when (c) {
                    "subrip", "srt" -> "application/x-subrip"; "ass", "ssa" -> "text/x-ssa"; "webvtt" -> "text/vtt"
                    "hdmv_pgs_subtitle", "pgs" -> "application/pgs"; "dvd_subtitle" -> "application/vobsub"
                    else -> "text/x-${c.ifBlank { "unknown" }}"
                }
                else -> when (c) {
                    "h264" -> "video/avc"; "hevc" -> "video/hevc"; "av1" -> "video/av01"; "vp9" -> "video/x-vnd.on2.vp9"
                    "vp8" -> "video/x-vnd.on2.vp8"; "mpeg2video" -> "video/mpeg2"; "vc1" -> "video/wvc1"; "mpeg4" -> "video/mp4v-es"
                    else -> "video/x-${c.ifBlank { "unknown" }}"
                }
            }
        }
    }
}
