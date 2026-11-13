package com.mbk.halatuju.core.voice

/**
 * Pure Kotlin state machine enforcing strict single-stream serialized speech output:
 * - Zero overlapping TTS playback.
 * - Buffers utterances cleanly if enqueued before Android TextToSpeech finishes warming up.
 * - Bounded FIFO queue prevents stale turn backlog in dense traffic.
 * - Guards against stale UtteranceProgressListener callbacks after interrupts.
 */
class SpeechQueueCoordinator {

    data class UtteranceRequest(
        val id: String,
        val text: String,
        val volume: Float,
        val interrupt: Boolean = false
    )

    sealed class Action {
        data class StartSpeaking(val request: UtteranceRequest) : Action()
        data class InterruptAndSpeak(val request: UtteranceRequest) : Action()
        data object StopAll : Action()
        data object ReleaseFocusIfIdle : Action()
    }

    private val lock = Any()
    private val pending = ArrayDeque<UtteranceRequest>()
    private var engineReady = false
    private var activeId: String? = null
    private var activeText: String? = null
    private var seq = 0L

    val currentActiveId: String?
        get() = synchronized(lock) { activeId }

    val pendingCount: Int
        get() = synchronized(lock) { pending.size }

    fun markEngineReady(): Action? = synchronized(lock) {
        engineReady = true
        if (activeId == null && pending.isNotEmpty()) {
            val next = pending.removeFirst()
            activeId = next.id
            activeText = next.text
            Action.StartSpeaking(next)
        } else {
            null
        }
    }

    fun enqueue(
        text: String,
        volume: Float,
        interrupt: Boolean = false
    ): Action? = synchronized(lock) {
        val clean = text.trim()
        val clampedVol = volume.coerceIn(0f, 1f)
        if (clean.isEmpty() || clampedVol <= 0.01f) return null

        // Deduplicate against currently spoken or most recently queued prompt
        if (!interrupt) {
            if (clean == activeText || pending.lastOrNull()?.text == clean) {
                return null
            }
        }

        val id = "halatuju_utt_${++seq}"
        val req = UtteranceRequest(id = id, text = clean, volume = clampedVol, interrupt = interrupt)

        if (!engineReady) {
            if (interrupt) {
                pending.clear()
                pending.addLast(req)
            } else {
                while (pending.size >= MAX_PENDING) {
                    pending.removeFirst()
                }
                pending.addLast(req)
            }
            return null
        }

        val current = activeId
        return if (current == null) {
            activeId = id
            activeText = clean
            Action.StartSpeaking(req)
        } else if (interrupt) {
            pending.clear()
            activeId = id
            activeText = clean
            Action.InterruptAndSpeak(req)
        } else {
            while (pending.size >= MAX_PENDING) {
                pending.removeFirst()
            }
            pending.addLast(req)
            null
        }
    }

    fun onUtteranceFinished(utteranceId: String): Action? = synchronized(lock) {
        if (utteranceId != activeId) {
            return null
        }
        activeId = null
        activeText = null
        return if (engineReady && pending.isNotEmpty()) {
            val next = pending.removeFirst()
            activeId = next.id
            activeText = next.text
            Action.StartSpeaking(next)
        } else {
            Action.ReleaseFocusIfIdle
        }
    }

    fun stopAll(): Action = synchronized(lock) {
        pending.clear()
        activeId = null
        activeText = null
        Action.StopAll
    }

    companion object {
        private const val MAX_PENDING = 2
    }
}
