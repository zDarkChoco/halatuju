package com.mbk.halatuju.core.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates transient navigation audio focus (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`) across both
 * spoken TTS turns and short overspeed chimes so background music/podcasts lower cleanly and
 * resume immediately once all guidance audio finishes.
 *
 * Uses `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` so Android routes prompts appropriately over
 * device speakers, wired headsets, Bluetooth A2DP/HFP, and Android Auto receivers.
 */
@Singleton
class AudioFocusCoordinator @Inject constructor(
    @ApplicationContext context: Context
) {
    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var speechActive = false
    private var chimeActive = false
    private var activeFocusRequest: AudioFocusRequest? = null

    val navSpeechAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    val navChimeAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun acquireForSpeech() = synchronized(lock) {
        speechActive = true
        ensureFocusRequestedLocked()
    }

    fun releaseForSpeech() = synchronized(lock) {
        speechActive = false
        releaseFocusIfUnusedLocked()
    }

    fun acquireForChime() = synchronized(lock) {
        chimeActive = true
        ensureFocusRequestedLocked()
    }

    fun releaseForChime() = synchronized(lock) {
        chimeActive = false
        releaseFocusIfUnusedLocked()
    }

    fun releaseAll() = synchronized(lock) {
        speechActive = false
        chimeActive = false
        releaseFocusIfUnusedLocked()
    }

    private fun ensureFocusRequestedLocked() {
        if (activeFocusRequest != null) return
        val am = audioManager ?: return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(navSpeechAttributes)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener({ _ -> }, mainHandler)
            .build()
        val res = runCatching { am.requestAudioFocus(req) }.getOrNull()
        if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            activeFocusRequest = req
        }
    }

    private fun releaseFocusIfUnusedLocked() {
        if (speechActive || chimeActive) return
        val req = activeFocusRequest ?: return
        activeFocusRequest = null
        runCatching { audioManager?.abandonAudioFocusRequest(req) }
    }
}
