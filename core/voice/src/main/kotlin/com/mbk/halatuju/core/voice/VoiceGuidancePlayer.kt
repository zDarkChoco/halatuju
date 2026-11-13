package com.mbk.halatuju.core.voice

import com.mbk.halatuju.core.model.UnitSystem
import com.mbk.halatuju.core.model.VoicePromptCue

/**
 * Abstraction for turn-by-turn spoken guidance and overspeed alert chimes so foreground
 * UI, background foreground-service drives, and simulation tests share a single contract.
 */
interface VoiceGuidancePlayer {
    fun warmup() {}
    fun speakCue(cue: VoicePromptCue, unitSystem: UnitSystem, volume: Float)
    fun speakText(text: String, volume: Float, interrupt: Boolean = false)
    fun playOverspeedChime(volume: Float)
    fun stopSpeaking()
    fun shutdown()
}

object NoOpVoiceGuidancePlayer : VoiceGuidancePlayer {
    override fun speakCue(cue: VoicePromptCue, unitSystem: UnitSystem, volume: Float) = Unit
    override fun speakText(text: String, volume: Float, interrupt: Boolean) = Unit
    override fun playOverspeedChime(volume: Float) = Unit
    override fun stopSpeaking() = Unit
    override fun shutdown() = Unit
}
