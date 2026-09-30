package com.lakesidegames.electioneer.engine

import com.lakesidegames.electioneer.content.bundleText
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.*

@Serializable
private data class NativeTone(val freq: Double, val duration: Double, val type: String = "sine", val delay: Double = 0.0, val gain: Double = 0.2)

// Both platforms play the same PCM generated from the web's oscillator presets.
class NativeSound private constructor() {
    companion object {
        private val cues by lazy { EngineJson.decodeFromString<Map<String, List<NativeTone>>>(bundleText("sound-cues")) }
        private const val rate = 22050
        fun wav(cue: String, volume: Double): ByteArray {
            val tones = cues[cue] ?: return byteArrayOf()
            val level = if (volume.isFinite()) volume.coerceIn(0.0, 1.0) else 0.0
            val samples = ceil(tones.maxOf { it.delay + it.duration + 0.02 } * rate).toInt()
            val bytes = ByteArray(44 + samples * 2)
            fun number(offset: Int, value: Int, count: Int) { for (i in 0 until count) bytes[offset + i] = (value ushr (i * 8)).toByte() }
            fun text(offset: Int, value: String) { value.encodeToByteArray().copyInto(bytes, offset) }
            text(0, "RIFF"); number(4, bytes.size - 8, 4); text(8, "WAVE"); text(12, "fmt ")
            number(16, 16, 4); number(20, 1, 2); number(22, 1, 2); number(24, rate, 4)
            number(28, rate * 2, 4); number(32, 2, 2); number(34, 16, 2); text(36, "data"); number(40, samples * 2, 4)
            for (sample in 0 until samples) {
                val time = sample.toDouble() / rate
                val amplitude = tones.sumOf { tone ->
                    val t = time - tone.delay
                    if (t < 0 || t > tone.duration || level == 0.0) 0.0 else {
                        val peak = tone.gain * level
                        val envelope = if (t < 0.01) peak * t / 0.01
                            else peak * (0.0001 / peak).pow((t - 0.01) / (tone.duration - 0.01))
                        val phase = t * tone.freq
                        val wave = when (tone.type) {
                            "square" -> if (sin(2 * PI * phase) >= 0) 1.0 else -1.0
                            "triangle" -> 2 / PI * asin(sin(2 * PI * phase))
                            "sawtooth" -> 2 * (phase - floor(phase + 0.5))
                            else -> sin(2 * PI * phase)
                        }
                        wave * envelope
                    }
                }
                number(44 + sample * 2, (amplitude.coerceIn(-1.0, 1.0) * 32767).roundToInt(), 2)
            }
            return bytes
        }
        @OptIn(ExperimentalEncodingApi::class)
        fun wavBase64(cue: String, volume: Double): String = Base64.encode(wav(cue, volume))
    }
}
