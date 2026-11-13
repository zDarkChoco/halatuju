package com.mbk.halatuju.core.voice

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.mbk.halatuju.core.model.UnitSystem
import com.mbk.halatuju.core.model.VoicePromptCue
import com.mbk.halatuju.core.model.VoiceStage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production Android [TextToSpeech] speaker for turn-by-turn navigation:
 * - Lazy initialization guarded cleanly against devices without TTS engines or headless test runners
 * - Uses [SpeechQueueCoordinator] so announcements NEVER overlap
 * - Requests transient ducking focus via [AudioFocusCoordinator] and releases focus immediately after speech ends
 * - Uses `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` for reliable speaker & Bluetooth A2DP/HFP output
 */
@Singleton
class AndroidTtsSpeaker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cueFormatter: VoiceCueFormatter,
    private val audioFocusCoordinator: AudioFocusCoordinator
) : VoiceGuidancePlayer {

    private val queue = SpeechQueueCoordinator()
    private val chimePlayer = ChimeAudioTrackPlayer(audioFocusCoordinator)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val initLock = Any()

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var initStarted = false

    override fun warmup() {
        ensureTtsInitialized()
    }

    override fun speakCue(cue: VoicePromptCue, unitSystem: UnitSystem, volume: Float) {
        val text = cueFormatter.format(cue, unitSystem)
        val urgent = cue.stage == VoiceStage.FINAL ||
            cue.stage == VoiceStage.ARRIVE_STOP ||
            cue.stage == VoiceStage.ARRIVE_DESTINATION
        speakText(text = text, volume = volume, interrupt = urgent)
    }

    override fun speakText(text: String, volume: Float, interrupt: Boolean) {
        ensureTtsInitialized()
        val action = queue.enqueue(text = text, volume = volume, interrupt = interrupt)
        applyQueueAction(action)
    }

    override fun playOverspeedChime(volume: Float) {
        chimePlayer.play(volume)
    }

    override fun stopSpeaking() {
        chimePlayer.stop()
        applyQueueAction(queue.stopAll())
    }

    override fun shutdown() {
        stopSpeaking()
        synchronized(initLock) {
            runCatching { tts?.shutdown() }
            tts = null
            initStarted = false
        }
        audioFocusCoordinator.releaseAll()
    }

    private fun ensureTtsInitialized() {
        if (initStarted) return
        synchronized(initLock) {
            if (initStarted) return
            initStarted = true
            runCatching {
                lateinit var instance: TextToSpeech
                instance = TextToSpeech(context) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        onTtsEngineReady(instance)
                    } else {
                        queue.stopAll()
                        audioFocusCoordinator.releaseForSpeech()
                    }
                }
                tts = instance
            }.onFailure {
                tts = null
            }
        }
    }

    private fun onTtsEngineReady(instance: TextToSpeech) {
        runCatching {
            instance.setAudioAttributes(audioFocusCoordinator.navSpeechAttributes)
            val engLangResult = instance.setLanguage(Locale.forLanguageTag("en-MY"))
            if (engLangResult == TextToSpeech.LANG_MISSING_DATA ||
                engLangResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                val usResult = instance.setLanguage(Locale.US)
                if (usResult == TextToSpeech.LANG_MISSING_DATA ||
                    usResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    instance.setLanguage(Locale.ENGLISH)
                }
            }
            instance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    handleUtteranceFinished(utteranceId)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    handleUtteranceFinished(utteranceId)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    handleUtteranceFinished(utteranceId)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    handleUtteranceFinished(utteranceId)
                }
            })
        }
        applyQueueAction(queue.markEngineReady())
    }

    private fun handleUtteranceFinished(utteranceId: String?) {
        val id = utteranceId ?: return
        mainHandler.post {
            applyQueueAction(queue.onUtteranceFinished(id))
        }
    }

    private fun applyQueueAction(action: SpeechQueueCoordinator.Action?) {
        if (action == null) return
        when (action) {
            is SpeechQueueCoordinator.Action.StartSpeaking -> {
                executeSpeak(action.request)
            }
            is SpeechQueueCoordinator.Action.InterruptAndSpeak -> {
                executeSpeak(action.request)
            }
            SpeechQueueCoordinator.Action.StopAll -> {
                runCatching { tts?.stop() }
                audioFocusCoordinator.releaseForSpeech()
            }
            SpeechQueueCoordinator.Action.ReleaseFocusIfIdle -> {
                audioFocusCoordinator.releaseForSpeech()
            }
        }
    }

    private fun executeSpeak(req: SpeechQueueCoordinator.UtteranceRequest) {
        val engine = tts
        if (engine == null) {
            audioFocusCoordinator.releaseForSpeech()
            return
        }
        audioFocusCoordinator.acquireForSpeech()
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, req.volume)
        }
        val status = runCatching {
            engine.speak(req.text, TextToSpeech.QUEUE_FLUSH, params, req.id)
        }.getOrElse { TextToSpeech.ERROR }

        if (status == TextToSpeech.ERROR) {
            handleUtteranceFinished(req.id)
        }
    }
}
