@file:androidx.annotation.OptIn(UnstableApi::class)

package com.lekathedon.visionlite.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * Audio delay for ExoPlayer ([Sync]). The audio track drives the clock, so audio itself can't be moved: the video
 * samples are moved the other way instead (timestamp - [delayUs]), which puts the sound [delayUs] behind the picture.
 * Read per sample, so a new delay applies after the flush [Sync] asks for.
 */
internal class DelayedAudio(source: MediaSource, private val delayUs: () -> Long) : WrappingMediaSource(source) {
    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        Period(mediaSource.createPeriod(id, allocator, startPositionUs), delayUs)

    override fun releasePeriod(mediaPeriod: MediaPeriod) = mediaSource.releasePeriod((mediaPeriod as Period).inner)

    override fun onChildSourceInfoRefreshed(newTimeline: Timeline) = refreshSourceInfo(newTimeline)

    private class Period(val inner: MediaPeriod, private val delayUs: () -> Long) : MediaPeriod by inner {
        private var outer: MediaPeriod.Callback? = null

        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
            outer = callback
            inner.prepare(object : MediaPeriod.Callback {
                override fun onPrepared(mediaPeriod: MediaPeriod) { outer?.onPrepared(this@Period) }
                override fun onContinueLoadingRequested(source: MediaPeriod) { outer?.onContinueLoadingRequested(this@Period) }
            }, positionUs)
        }

        override fun selectTracks(selections: Array<ExoTrackSelection?>, mayRetainStreamFlags: BooleanArray,
                                  streams: Array<SampleStream?>, streamResetFlags: BooleanArray, positionUs: Long): Long {
            val mine = Array(streams.size) { i -> streams[i]?.let { (it as? Shifted)?.inner ?: it } }
            val at = inner.selectTracks(selections, mayRetainStreamFlags, mine, streamResetFlags, positionUs)
            for (i in streams.indices) {
                val s = mine[i]
                val video = selections[i]?.selectedFormat?.sampleMimeType?.let(MimeTypes::getTrackType) == C.TRACK_TYPE_VIDEO
                streams[i] = when {
                    s == null || !video -> s
                    (streams[i] as? Shifted)?.inner === s -> streams[i]
                    else -> Shifted(s, delayUs)
                }
            }
            return at
        }
    }

    private class Shifted(val inner: SampleStream, private val delayUs: () -> Long) : SampleStream by inner {
        override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int =
            inner.readData(formatHolder, buffer, readFlags).also { if (it == C.RESULT_BUFFER_READ) buffer.timeUs -= delayUs() }

        override fun skipData(positionUs: Long): Int = inner.skipData((positionUs + delayUs()).coerceAtLeast(0))
    }
}
