package com.mbk.halatuju.core.voice

import android.app.Application
import com.mbk.halatuju.core.model.ManeuverModifier
import com.mbk.halatuju.core.model.ManeuverType
import com.mbk.halatuju.core.model.UnitSystem
import com.mbk.halatuju.core.model.VoicePromptCue
import com.mbk.halatuju.core.model.VoiceStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.abs

class SpeechQueueAndChimeTest {

    @Test
    fun `speech queue serializes utterances without overlap and defends against stale finish callbacks`() {
        val queue = SpeechQueueCoordinator()

        // Prompts arriving during TTS warmup are buffered cleanly until ready
        assertNull(queue.enqueue("Proceed onto Jalan Ampang.", volume = 0.9f))
        assertEquals(1, queue.pendingCount)

        val firstAction = queue.markEngineReady()
        assertTrue(firstAction is SpeechQueueCoordinator.Action.StartSpeaking)
        val firstReq = (firstAction as SpeechQueueCoordinator.Action.StartSpeaking).request
        assertEquals("Proceed onto Jalan Ampang.", firstReq.text)
        assertEquals(firstReq.id, queue.currentActiveId)

        // While first is speaking, non-urgent second queues rather than overlapping
        assertNull(queue.enqueue("In 400 meters, turn right onto Jalan Tun Razak.", volume = 0.9f))
        // Identical duplicate ignored cleanly
        assertNull(queue.enqueue("In 400 meters, turn right onto Jalan Tun Razak.", volume = 0.9f))
        assertEquals(1, queue.pendingCount)

        // Zero or near-zero volume ignored cleanly
        assertNull(queue.enqueue("Silent test", volume = 0f))
        assertEquals(1, queue.pendingCount)

        // Wrong / stale utterance completion ID does NOT cancel current utterance
        assertNull(queue.onUtteranceFinished("stale_utt_999"))
        assertEquals(firstReq.id, queue.currentActiveId)

        // Completing first immediately advances to queued second utterance
        val secondAction = queue.onUtteranceFinished(firstReq.id)
        assertTrue(secondAction is SpeechQueueCoordinator.Action.StartSpeaking)
        val secondReq = (secondAction as SpeechQueueCoordinator.Action.StartSpeaking).request
        assertEquals("In 400 meters, turn right onto Jalan Tun Razak.", secondReq.text)

        // Urgent FINAL / ARRIVE interrupt flushes queued backlog and supersedes cleanly
        assertNull(queue.enqueue("Lower priority info", volume = 0.9f))
        val urgentAction = queue.enqueue("You have arrived at KLIA.", volume = 0.9f, interrupt = true)
        assertTrue(urgentAction is SpeechQueueCoordinator.Action.InterruptAndSpeak)
        val urgentReq = (urgentAction as SpeechQueueCoordinator.Action.InterruptAndSpeak).request
        assertEquals(0, queue.pendingCount)

        // Late onStop/onDone callback from interrupted secondReq is ignored cleanly
        assertNull(queue.onUtteranceFinished(secondReq.id))
        assertEquals(urgentReq.id, queue.currentActiveId)

        // Completing final urgent utterance releases focus cleanly
        val doneAction = queue.onUtteranceFinished(urgentReq.id)
        assertEquals(SpeechQueueCoordinator.Action.ReleaseFocusIfIdle, doneAction)
        assertNull(queue.currentActiveId)
    }

    @Test
    fun `chime tone synthesizer produces smooth non-clipping PCM waveform scaled by volume`() {
        val full = ChimeToneSynth.synthesizeDualTonePcm(volume = 1.0f)
        val half = ChimeToneSynth.synthesizeDualTonePcm(volume = 0.5f)
        val muted = ChimeToneSynth.synthesizeDualTonePcm(volume = 0.0f)

        val expectedSamples = (ChimeToneSynth.SAMPLE_RATE_HZ * ChimeToneSynth.CHIME_DURATION_MS) / 1000
        assertEquals(expectedSamples, full.size)
        assertTrue("Edge samples should taper to zero cleanly", abs(full.first().toInt()) == 0)
        assertTrue("End samples should fade smoothly", abs(full.last().toInt()) < 50)

        val maxFull = full.maxOf { abs(it.toInt()) }
        val maxHalf = half.maxOf { abs(it.toInt()) }
        assertTrue("Peak chime must be loud enough to hear and non-clipping: $maxFull", maxFull in 5_000..18_000)
        assertTrue("Half volume peak should be ~half full peak", maxHalf in (maxFull / 3)..(maxFull * 2 / 3))
        assertTrue(muted.all { it.toInt() == 0 })
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class VoiceCueFormatterTest {

    @Test
    fun `resource backed voice formatter outputs localized metric and imperial sentences cleanly`() {
        val formatter = VoiceCueFormatter(RuntimeEnvironment.getApplication())

        val depart = VoicePromptCue(
            legIndex = 0,
            stepIndex = 0,
            stage = VoiceStage.DEPART,
            distanceMeters = 0.0,
            maneuverType = ManeuverType.DEPART,
            maneuverModifier = ManeuverModifier.STRAIGHT,
            roadName = "Jalan Ampang"
        )
        assertEquals("Proceed onto Jalan Ampang.", formatter.format(depart, UnitSystem.METRIC))

        val rampMetric = VoicePromptCue(
            legIndex = 0,
            stepIndex = 2,
            stage = VoiceStage.EARLY,
            distanceMeters = 1_240.0,
            maneuverType = ManeuverType.OFF_RAMP,
            maneuverModifier = ManeuverModifier.SLIGHT_LEFT,
            roadName = "Lebuhraya KLIA",
            exitShield = "608"
        )
        assertEquals(
            "In 1.2 kilometers, take exit 608 slight left towards Lebuhraya KLIA.",
            formatter.format(rampMetric, UnitSystem.METRIC)
        )

        val turnImperial = VoicePromptCue(
            legIndex = 0,
            stepIndex = 3,
            stage = VoiceStage.MEDIUM,
            distanceMeters = 110.0,
            maneuverType = ManeuverType.TURN,
            maneuverModifier = ManeuverModifier.RIGHT,
            roadName = "Jalan Tun Razak"
        )
        assertEquals(
            "In 350 feet, turn right onto Jalan Tun Razak.",
            formatter.format(turnImperial, UnitSystem.IMPERIAL)
        )

        val roundaboutFinal = VoicePromptCue(
            legIndex = 0,
            stepIndex = 4,
            stage = VoiceStage.FINAL,
            distanceMeters = 45.0,
            maneuverType = ManeuverType.ROUNDABOUT,
            maneuverModifier = ManeuverModifier.RIGHT,
            roadName = "Persiaran KLIA",
            rotaryExitNumber = 3
        )
        assertEquals(
            "Take exit 3 onto Persiaran KLIA.",
            formatter.format(roundaboutFinal, UnitSystem.METRIC)
        )

        val arriveStop = VoicePromptCue(
            legIndex = 0,
            stepIndex = Int.MAX_VALUE,
            stage = VoiceStage.ARRIVE_STOP,
            distanceMeters = 0.0,
            maneuverType = ManeuverType.ARRIVE,
            maneuverModifier = ManeuverModifier.NONE,
            roadName = "KL Sentral",
            stopNumber = 1,
            placeName = "KL Sentral"
        )
        assertEquals("Arrived at stop 1, KL Sentral.", formatter.format(arriveStop, UnitSystem.METRIC))
    }
}
