package com.lekathedon.visionlite.player

import androidx.media3.common.Format
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor

/**
 * MKV frame rate for ExoPlayer, Vision's fix (dvmkv MatroskaExtractor "surface the container-declared frame rate").
 * media3 reads the track's DefaultDuration but never puts it in Format.frameRate, so an MKV reached the decoder with
 * frame-rate 0. Seen 2026-11-20 on Babylon Berlin (1080i 25): Lite gave the Amlogic decoder 0, the box's own AFR guessed
 * 1080p30 and the frame times came out at 30; Vision gave it 25 and got 1080p25. With this the file's own rate leads.
 */
object MkvFrameRate {
    private const val ID_TRACK_ENTRY = 0xAE
    private const val ID_TRACK_NUMBER = 0xD7
    private const val ID_DEFAULT_DURATION = 0x23E383

    /** Per extractor: track number -> declared fps, filled while the Tracks element is read. */
    class Rates {
        val byTrack = HashMap<Int, Float>()
        private var number = -1
        private var durationNs = 0L
        fun integer(id: Int, value: Long) {
            if (id == ID_TRACK_NUMBER) number = value.toInt()
            if (id == ID_DEFAULT_DURATION) durationNs = value
        }
        fun end(id: Int) {
            if (id != ID_TRACK_ENTRY) return
            if (number >= 0 && durationNs > 0) {
                val fps = 1_000_000_000f / durationNs
                if (fps in 5f..121f) byTrack[number] = fps
            }
            number = -1; durationNs = 0L
        }
    }

    // Same parser the default factory gives MKV (embedded SRT/ASS still transcoded).
    class Plain(private val rates: Rates) : MatroskaExtractor(androidx.media3.extractor.text.DefaultSubtitleParserFactory(), 0) {
        override fun integerElement(id: Int, value: Long) { rates.integer(id, value); super.integerElement(id, value) }
        override fun endMasterElement(id: Int) { rates.end(id); super.endMasterElement(id) }
    }

    class Ass(private val rates: Rates, parsers: io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory,
              handler: io.github.peerless2012.ass.media.AssHandler) :
        io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor(parsers, handler) {
        override fun integerElement(id: Int, value: Long) { rates.integer(id, value); super.integerElement(id, value) }
        override fun endMasterElement(id: Int) { rates.end(id); super.endMasterElement(id) }
    }

    /** MatroskaExtractor.init is final, so the output is wrapped from outside: the video format gets the declared fps. */
    private class Wrap(private val inner: Extractor, private val rates: Rates) : Extractor by inner {
        override fun init(output: ExtractorOutput) = inner.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                val o = output.track(id, type)
                // Audio: DTS-HD / DTS:X named from the first frame (MkvDts); video: the declared fps.
                if (type == androidx.media3.common.C.TRACK_TYPE_AUDIO) return MkvDts(o)
                val t = o
                return object : TrackOutput by t {
                    override fun format(format: Format) {
                        val fps = rates.byTrack[id]
                        t.format(if (format.frameRate <= 0f && fps != null && androidx.media3.common.MimeTypes.isVideo(format.sampleMimeType))
                            format.buildUpon().setFrameRate(fps).build() else format)
                    }
                }
            }
            override fun endTracks() = output.endTracks()
            override fun seekMap(seekMap: SeekMap) = output.seekMap(seekMap)
        })
        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = inner.read(input, seekPosition)
    }

    /** The default extractors with every MKV one swapped for the frame-rate reader (libass one when [ass] is set). */
    fun factory(base: ExtractorsFactory, ass: Pair<io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory,
        io.github.peerless2012.ass.media.AssHandler>?): ExtractorsFactory = ExtractorsFactory {
        base.createExtractors().also { list ->
            list.forEachIndexed { i, e ->
                if (e is MatroskaExtractor) {
                    val r = Rates()
                    list[i] = Wrap(if (ass != null) Ass(r, ass.first, ass.second) else Plain(r), r)
                }
            }
        }
    }
}
