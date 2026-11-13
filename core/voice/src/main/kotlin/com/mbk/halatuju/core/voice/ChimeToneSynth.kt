package com.mbk.halatuju.core.voice

import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure PCM synthesizer generating a crisp, gentle two-note overspeed alert chime (~180 ms)
 * with smooth attack/release envelopes to eliminate clicks or harshness.
 */
object ChimeToneSynth {
    const val SAMPLE_RATE_HZ = 22_050
    const val CHIME_DURATION_MS = 180

    fun synthesizeDualTonePcm(volume: Float = 1.0f): ShortArray {
        val clampedVol = volume.coerceIn(0f, 1f)
        val totalSamples = (SAMPLE_RATE_HZ * CHIME_DURATION_MS) / 1000
        val out = ShortArray(totalSamples)
        if (clampedVol <= 0.01f) return out

        val halfSamples = totalSamples / 2
        val freq1 = 880.0   // A5
        val freq2 = 1174.66 // D6
        val maxAmp = 16_000.0 * clampedVol // Keep calm below clipping

        for (i in 0 until totalSamples) {
            val isSecondNote = i >= halfSamples
            val localIdx = if (isSecondNote) i - halfSamples else i
            val noteLen = if (isSecondNote) totalSamples - halfSamples else halfSamples
            val freq = if (isSecondNote) freq2 else freq1

            val tSec = localIdx.toDouble() / SAMPLE_RATE_HZ
            val attackFade = (localIdx / (0.010 * SAMPLE_RATE_HZ)).coerceIn(0.0, 1.0)
            val decayEnv = exp(-4.2 * localIdx.toDouble() / noteLen.coerceAtLeast(1))
            val tailFade = ((noteLen - 1 - localIdx) / (0.014 * SAMPLE_RATE_HZ)).coerceIn(0.0, 1.0)
            val amp = maxAmp * attackFade * decayEnv * tailFade

            val sample = (amp * sin(2.0 * PI * freq * tSec)).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i] = sample.toShort()
        }
        return out
    }
}

internal class ChimeAudioTrackPlayer(
    private val audioFocusCoordinator: AudioFocusCoordinator
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeJob: Job? = null

    fun play(volume: Float) {
        val pcm = ChimeToneSynth.synthesizeDualTonePcm(volume)
        if (pcm.all { it.toInt() == 0 }) return

        activeJob?.cancel()
        activeJob = scope.launch {
            audioFocusCoordinator.acquireForChime()
            var track: AudioTrack? = null
            try {
                val byteSize = pcm.size * 2
                track = AudioTrack.Builder()
                    .setAudioAttributes(audioFocusCoordinator.navChimeAttributes)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(ChimeToneSynth.SAMPLE_RATE_HZ)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(byteSize)
                    .build()

                track.write(pcm, 0, pcm.size)
                track.play()
                delay(ChimeToneSynth.CHIME_DURATION_MS.toLong() + 40L)
            } catch (_: Throwable) {
                // Gracefully ignored in Robolectric / devices without audio mixer
            } finally {
                runCatching { track?.stop() }
                runCatching { track?.release() }
                audioFocusCoordinator.releaseForChime()
            }
        }
    }

    fun stop() {
        activeJob?.cancel()
        activeJob = null
        audioFocusCoordinator.releaseForChime()
    }
}
