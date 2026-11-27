package com.lekathedon.visionlite.player.dv

import android.media.MediaCodec
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer

/**
 * The decoder half of Convert to 8.1 (Vision's DolbyVisionCodecFallback + createDolbyVisionFallbackCodecSelector,
 * which Lite never had). Many TV / box Dolby Vision decoders don't list profile 8 even though they play it.
 * media3 then sorts the plain HEVC decoder ahead of them: the converted 8.1 film played as HDR10 (TCL TV, Ray, 2026-11-27).
 * While converting, the TV's own Dolby Vision decoder always goes first; HEVC stays behind it as the fallback.
 */
@UnstableApi
internal object DvDecoders {
    private const val TAG = "DvDecoders"
    private const val DV = MimeTypes.VIDEO_DOLBY_VISION

    /** The DV decoders as media3 lists them; when it lists none, the ones the platform has (Vision's fallback, profile ignored). */
    fun selector(converting: Boolean) = MediaCodecSelector { mime, secure, tunneling ->
        val d = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
        if (converting && mime == DV && d.isEmpty()) hidden() else d
    }

    /** Dolby Vision decoders first for a Dolby Vision track (media3 had put the HEVC decoder first). */
    fun dvFirst(format: Format, list: List<MediaCodecInfo>): List<MediaCodecInfo> {
        if (format.sampleMimeType != DV) return list
        val (dv, rest) = list.partition { it.codecMimeType.equals(DV, true) }
        if (dv.isNotEmpty() && list.firstOrNull() != dv.first()) Log.i(TAG, "8.1: ${dv.first().name} ahead of ${list.first().name}")
        return dv + rest
    }

    /** The type the video decoder was really opened as this play (video/dolby-vision = the TV's DV path; video/hevc = HDR10). */
    @Volatile var openedAs: String? = null
    val reachedDv: Boolean get() = openedAs.equals(DV, true)

    /** Every decoder that takes video/dolby-vision, whatever profiles it lists, with its real capabilities. */
    private fun hidden(): List<MediaCodecInfo> = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { i ->
            !i.isEncoder && i.supportedTypes.any { it.equals(DV, true) }
        }.mapNotNull { i ->
            val caps = runCatching { i.getCapabilitiesForType(DV) }.getOrNull() ?: return@mapNotNull null
            if (runCatching { MediaCodec.createByCodecName(i.name).release() }.isFailure) return@mapNotNull null
            val sw = i.name.startsWith("OMX.google.", true) || i.name.startsWith("c2.android.", true)
            val q = Build.VERSION.SDK_INT >= 29
            Log.i(TAG, "hidden DV decoder: ${i.name}")
            MediaCodecInfo.newInstance(i.name, DV, DV, caps, if (q) i.isHardwareAccelerated else !sw,
                if (q) i.isSoftwareOnly else sw, if (q) i.isVendor else !sw, false, false)
        }
    }.getOrElse { Log.e(TAG, "DV decoder probe failed", it); emptyList() }
}

/** ExoPlayer's video renderer with [DvDecoders.dvFirst] (only used while Convert to 8.1 is on). */
@UnstableApi
internal class DvFirstVideoRenderer(b: MediaCodecVideoRenderer.Builder) : MediaCodecVideoRenderer(b) {
    override fun getDecoderInfos(mediaCodecSelector: MediaCodecSelector, format: Format, requiresSecureDecoder: Boolean): List<MediaCodecInfo> =
        DvDecoders.dvFirst(format, super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder))

    override fun onCodecInitialized(name: String, configuration: androidx.media3.exoplayer.mediacodec.MediaCodecAdapter.Configuration,
                                    initializedTimestampMs: Long, initializationDurationMs: Long) {
        super.onCodecInitialized(name, configuration, initializedTimestampMs, initializationDurationMs)
        DvDecoders.openedAs = codecInfo?.codecMimeType
        Log.i("DvDecoders", "video decoder $name opened as ${DvDecoders.openedAs}")
    }
}
