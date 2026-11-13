package com.mbk.halatuju.core.navigation

import com.mbk.halatuju.core.model.ManeuverModifier
import com.mbk.halatuju.core.model.ManeuverType
import com.mbk.halatuju.core.model.VoicePromptCue
import com.mbk.halatuju.core.model.VoiceStage

typealias VoiceStage = com.mbk.halatuju.core.model.VoiceStage
typealias VoicePromptCue = com.mbk.halatuju.core.model.VoicePromptCue

/**
 * Speed-adaptive voice announcement stage scheduler.
 * Guarantees each `(legIndex, stepIndex, stage)` fires at most once across city and expressway speeds.
 */
class VoiceScheduleTracker {
    private val firedStages = HashSet<String>()

    fun reset() {
        firedStages.clear()
    }

    fun markDepartIfNeeded(legIndex: Int, stepRoadName: String): VoicePromptCue? {
        val key = "$legIndex:0:DEPART"
        if (!firedStages.add(key)) return null
        return VoicePromptCue(
            legIndex = legIndex,
            stepIndex = 0,
            stage = VoiceStage.DEPART,
            distanceMeters = 0.0,
            maneuverType = ManeuverType.DEPART,
            maneuverModifier = ManeuverModifier.STRAIGHT,
            roadName = stepRoadName
        )
    }

    fun evaluateUpcoming(
        legIndex: Int,
        upcomingStepIndex: Int,
        stepLengthMeters: Double,
        distanceToManeuverMeters: Double,
        speedMps: Float,
        maneuverType: ManeuverType,
        maneuverModifier: ManeuverModifier,
        roadName: String,
        exitShield: String?,
        rotaryExitNumber: Int?,
        placeName: String = ""
    ): VoicePromptCue? {
        if (distanceToManeuverMeters < 0.0) return null
        val isHighway = speedMps >= 15.0f // >= 54 km/h scales anticipation distances upward

        val earlyMin = if (isHighway) 900.0 else 250.0
        val earlyMax = if (isHighway) 1_750.0 else 450.0
        val mediumMin = if (isHighway) 360.0 else 110.0
        val mediumMax = if (isHighway) 750.0 else 235.0
        val finalMax = if (isHighway) 180.0 else 65.0

        val stage: VoiceStage? = when {
            distanceToManeuverMeters <= finalMax -> VoiceStage.FINAL
            distanceToManeuverMeters in mediumMin..mediumMax && stepLengthMeters >= mediumMin -> VoiceStage.MEDIUM
            distanceToManeuverMeters in earlyMin..earlyMax && stepLengthMeters >= earlyMin -> VoiceStage.EARLY
            else -> null
        }
        if (stage == null) return null

        val key = "$legIndex:$upcomingStepIndex:${stage.name}"
        if (!firedStages.add(key)) return null

        // Mark earlier stages done if user skipped directly into a closer window
        if (stage == VoiceStage.FINAL) {
            firedStages.add("$legIndex:$upcomingStepIndex:${VoiceStage.MEDIUM.name}")
            firedStages.add("$legIndex:$upcomingStepIndex:${VoiceStage.EARLY.name}")
        } else if (stage == VoiceStage.MEDIUM) {
            firedStages.add("$legIndex:$upcomingStepIndex:${VoiceStage.EARLY.name}")
        }

        return VoicePromptCue(
            legIndex = legIndex,
            stepIndex = upcomingStepIndex,
            stage = stage,
            distanceMeters = distanceToManeuverMeters,
            maneuverType = maneuverType,
            maneuverModifier = maneuverModifier,
            roadName = roadName,
            exitShield = exitShield,
            rotaryExitNumber = rotaryExitNumber,
            placeName = placeName
        )
    }

    fun markArrivalIfNeeded(
        legIndex: Int,
        isIntermediateStop: Boolean,
        stopNumber: Int,
        placeName: String
    ): VoicePromptCue? {
        val stage = if (isIntermediateStop) VoiceStage.ARRIVE_STOP else VoiceStage.ARRIVE_DESTINATION
        val key = "$legIndex:ARRIVE:${stage.name}"
        if (!firedStages.add(key)) return null
        return VoicePromptCue(
            legIndex = legIndex,
            stepIndex = Int.MAX_VALUE,
            stage = stage,
            distanceMeters = 0.0,
            maneuverType = ManeuverType.ARRIVE,
            maneuverModifier = ManeuverModifier.NONE,
            roadName = placeName,
            stopNumber = stopNumber,
            placeName = placeName
        )
    }
}
