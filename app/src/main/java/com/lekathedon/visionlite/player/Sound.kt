package com.lekathedon.visionlite.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.lekathedon.visionlite.App
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Sound for people without a surround system (This TV › Sound system, Settings › Audio): surround mixed to stereo
 * for TV speakers and headphones, night mode (quiet parts up, loud parts held), dialogue boost (the centre channel up)
 * and a volume boost, with a peak limiter after them. The way Arc does it on ExoPlayer and MPV, written for Lite.
 * Nothing here runs while it is all off or the sound goes to the soundbar as a bitstream.
 */
object Sound {
    private val p get() = App.instance.prefs

    /** Headphones, Bluetooth or USB audio connected: surround can't reach them, so it is mixed to stereo. */
    fun headphones(context: Context): Boolean = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val kinds = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP); add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES); add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
            if (android.os.Build.VERSION.SDK_INT >= 31) { add(AudioDeviceInfo.TYPE_BLE_HEADSET); add(AudioDeviceInfo.TYPE_BLE_SPEAKER) }
        }
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in kinds }
    }.getOrDefault(false)

    /** Surround is decoded in the box and mixed to two channels (TV speakers, headphones, or headphones plugged in now). */
    fun stereo(context: Context): Boolean = p.soundOut.value != "soundbar" || headphones(context)

    fun night(): Int = when (p.nightMode.value) { "light" -> 1; "medium" -> 2; "strong" -> 3; else -> 0 }
    fun dialogueDb(): Int = p.dialogueBoost.value.toIntOrNull()?.coerceIn(0, 12) ?: 0
    fun gainDb(): Int = p.volumeBoost.value.toIntOrNull()?.coerceIn(0, 12) ?: 0

    /** A short line for the player's stats. */
    fun describe(context: Context): String = listOfNotNull(
        if (stereo(context)) "stereo mix" + if (headphones(context)) " (headphones)" else "" else "surround / passthrough",
        night().takeIf { it > 0 }?.let { "night ${p.nightMode.value}" },
        dialogueDb().takeIf { it > 0 }?.let { "dialogue +$it dB" },
        gainDb().takeIf { it > 0 }?.let { "volume +$it dB" },
    ).joinToString(" · ")

    // ---- MPV ----

    /** mpv's options for the same sound: [stereo] mixes to two channels (LFE -6 dB, centre raised by dialogue boost). */
    fun mpvOptions(stereo: Boolean): List<Pair<String, String>> = buildList {
        if (stereo) {
            add("audio-channels" to "stereo")
            val centre = 0.7071f * 10f.pow(dialogueDb() / 20f)
            add("audio-swresample-o" to "lfe_mix_level=0.5,center_mix_level=${num(centre)}")
        }
        val filters = buildList {
            if (gainDb() > 0) add("volume=volume=${gainDb()}dB")
            preset(night())?.let { (thr, ratio, make) ->
                add("acompressor=threshold=${num(lin(thr))}:ratio=${num(ratio)}:attack=10:release=300:makeup=${num(lin(make))}:knee=2")
            }
            if (isNotEmpty() || stereo) add("alimiter=limit=${num(CEILING)}:attack=1:release=80:level=0")
        }
        if (filters.isNotEmpty()) add("af" to "lavfi=[${filters.joinToString(",")}]")
    }

    // ---- shared numbers ----

    internal const val CEILING = 0.93f
    /** Night mode: threshold dB, ratio, make-up dB (the make-up lifts quiet speech; the ratio holds explosions). */
    internal fun preset(level: Int): Triple<Float, Float, Float>? = when (level) {
        1 -> Triple(-18f, 2f, 5f)
        2 -> Triple(-22f, 3f, 9f)
        3 -> Triple(-28f, 5f, 13f)
        else -> null
    }
    private fun lin(db: Float) = 10f.pow(db / 20f)
    private fun num(v: Float) = String.format(Locale.ROOT, "%.4f", v)
}

/**
 * ExoPlayer's half: one processor in the audio sink. Reads the settings when a stream's audio is configured, so a
 * change counts from the next film. PCM 16-bit and float; passthrough bitstreams never come here.
 */
@OptIn(UnstableApi::class)
internal class SoundProcessor(private val context: Context) : BaseAudioProcessor() {
    private var inCh = 0
    private var outCh = 0
    private var mix = false
    private var rate = 48000
    private var gain = 1f
    private var centre = 1f
    private var preset: Triple<Float, Float, Float>? = null
    private var limit = 1f
    private var frame = FloatArray(0)
    private var outFrame = FloatArray(2)
    private var roles = IntArray(0)

    // Envelope, compressor gain (dB) and limiter gain, with their time constants.
    private var env = 0f
    private var compDb = 0f
    private var lim = 1f
    private var envAtk = 0f; private var envRel = 0f; private var compAtk = 0f; private var compRel = 0f; private var limRel = 0f

    override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (f.encoding != C.ENCODING_PCM_16BIT && f.encoding != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET
        val stereo = Sound.stereo(context)
        val ch = f.channelCount
        mix = stereo && ch > 2
        val dlg = Sound.dialogueDb()
        val g = Sound.gainDb()
        preset = Sound.preset(Sound.night())
        roles = rolesFor(ch)
        val hasCentre = roles.contains(C_FC)
        if (!mix && preset == null && g == 0 && !(dlg > 0 && hasCentre)) return AudioProcessor.AudioFormat.NOT_SET
        inCh = ch; outCh = if (mix) 2 else ch; rate = f.sampleRate
        gain = 10f.pow(g / 20f)
        centre = if (hasCentre) 10f.pow(dlg / 20f) else 1f
        limit = Sound.CEILING
        frame = FloatArray(ch); outFrame = FloatArray(outCh)
        envAtk = coef(0.003f); envRel = coef(0.2f); compAtk = coef(0.01f); compRel = coef(0.35f); limRel = coef(0.1f)
        return AudioProcessor.AudioFormat(f.sampleRate, outCh, f.encoding)
    }

    override fun onFlush() { env = 0f; compDb = 0f; lim = 1f }
    override fun onReset() { inCh = 0; outCh = 0; frame = FloatArray(0) }

    override fun queueInput(input: ByteBuffer) {
        if (!input.hasRemaining() || inCh == 0) return
        input.order(ByteOrder.nativeOrder())
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytes = if (float) 4 else 2
        val frames = input.remaining() / (inCh * bytes)
        val out = replaceOutputBuffer(frames * outCh * bytes).order(ByteOrder.nativeOrder())
        repeat(frames) {
            for (c in 0 until inCh) frame[c] = if (float) input.float else input.short / 32768f
            process()
            for (c in 0 until outCh) {
                val v = outFrame[c]
                if (float) out.putFloat(v) else out.putShort((v * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        input.position(input.limit())
        out.flip()
    }

    private fun process() {
        val o = outFrame
        if (mix) {
            // ITU-style Lo/Ro: centre and surrounds at -3 dB, LFE at -6 dB, back centre half to each side.
            var l = 0f; var r = 0f
            for (c in 0 until inCh) {
                val v = frame[c].takeIf { it.isFinite() } ?: 0f
                when (roles[c]) {
                    C_FL -> l += v
                    C_FR -> r += v
                    C_FC -> { val x = v * 0.7071f * centre; l += x; r += x }
                    C_LFE -> { val x = v * 0.5f; l += x; r += x }
                    C_SL -> l += v * 0.7071f
                    C_SR -> r += v * 0.7071f
                    C_BC -> { val x = v * 0.5f; l += x; r += x }
                }
            }
            o[0] = l; o[1] = r
        } else {
            for (c in 0 until inCh) o[c] = (frame[c].takeIf { it.isFinite() } ?: 0f) * if (roles[c] == C_FC) centre else 1f
        }
        // Gain, then the compressor on the loudest channel (one gain for all keeps the picture of the sound).
        var peak = 0f
        for (c in 0 until outCh) { o[c] *= gain; peak = max(peak, abs(o[c])) }
        val pr = preset
        if (pr != null) {
            env = if (peak > env) peak + envAtk * (env - peak) else peak + envRel * (env - peak)
            val target = curve(env, pr)
            compDb = target + (if (target < compDb) compAtk else compRel) * (compDb - target)
            val k = 10f.pow(compDb / 20f)
            peak = 0f
            for (c in 0 until outCh) { o[c] *= k; peak = max(peak, abs(o[c])) }
        }
        // Peak limiter: instant down, smooth back up.
        val need = if (peak * lim > limit) limit / peak else 1f
        lim = if (need < lim) need else 1f + limRel * (lim - 1f)
        if (peak * lim > limit) lim = limit / peak
        for (c in 0 until outCh) o[c] = (o[c] * lim).coerceIn(-1f, 1f)
    }

    /** Gain change in dB for a level: soft-knee compression above the threshold, make-up faded out near silence. */
    private fun curve(level: Float, pr: Triple<Float, Float, Float>): Float {
        val (thr, ratio, make) = pr
        val db = 20f * log10(max(level, 1e-6f))
        val over = db - thr
        val slope = 1f / ratio - 1f
        val red = when { over <= -3f -> 0f; over >= 3f -> slope * over; else -> slope * (over + 3f).pow(2) / 12f }
        val weight = ((db + 66f) / 12f).coerceIn(0f, 1f)
        return red + make * weight
    }

    private fun coef(sec: Float) = exp(-1f / (sec * rate))

    private companion object {
        const val C_FL = 0; const val C_FR = 1; const val C_FC = 2; const val C_LFE = 3; const val C_SL = 4; const val C_SR = 5; const val C_BC = 6

        /** Android / ExoPlayer channel order per count (FL FR FC LFE BL BR SL SR); backs and sides both feed the sides. */
        fun rolesFor(ch: Int): IntArray = when (ch) {
            1 -> intArrayOf(C_FC)
            2 -> intArrayOf(C_FL, C_FR)
            3 -> intArrayOf(C_FL, C_FR, C_FC)
            4 -> intArrayOf(C_FL, C_FR, C_SL, C_SR)
            5 -> intArrayOf(C_FL, C_FR, C_FC, C_SL, C_SR)
            6 -> intArrayOf(C_FL, C_FR, C_FC, C_LFE, C_SL, C_SR)
            7 -> intArrayOf(C_FL, C_FR, C_FC, C_LFE, C_SL, C_SR, C_BC)
            else -> IntArray(ch) { i -> when (i) { 0 -> C_FL; 1 -> C_FR; 2 -> C_FC; 3 -> C_LFE; else -> if (i % 2 == 0) C_SL else C_SR } }
        }
    }
}
