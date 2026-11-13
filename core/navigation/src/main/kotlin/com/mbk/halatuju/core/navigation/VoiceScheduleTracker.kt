package com.mbk.halatuju.core.navigation

import com.mbk.halatuju.core.model.ManeuverModifier
import com.mbk.halatuju.core.model.ManeuverType
import kotlin.math.roundToInt

enum class VoiceStage {
    DEPART,
    EARLY,
    MEDIUM,
    FINAL,
    ARRIVE_STOP,
    ARRIVE_DESTINATION
}

data class VoicePromptCue(
    val legIndex: Int,
    val stepIndex: Int,
    val stage: VoiceStage,
    val distanceMeters: Double,
    val maneuverType: ManeuverType,
    val maneuverModifier: ManeuverModifier,
    val roadName: String,
    val exitShield: String? = null,
    val rotaryExitNumber: Int? = null,
    val stopNumber: Int = 0,
    val placeName: String = ""
) {
    /**
     * Generates clean English fallback prose when formatted outside an Android Context (e.g., JVM tests).
     * Inside Android UI/Service, [VoiceCueFormatter] maps these fields through localized `strings.xml` resources
     * so Bahasa Melayu (`values-ms`) drops in cleanly.
     */
    fun toDefaultEnglishSpeech(): String {
        val action = when (maneuverType) {
            ManeuverType.ROUNDABOUT, ManeuverType.ROTARY -> {
                val exitPart = rotaryExitNumber?.let { "take exit $it" } ?: "enter the roundabout"
                val roadPart = if (roadName.isNotBlank()) " onto $roadName" else ""
                "$exitPart$roadPart"
            }
            ManeuverType.OFF_RAMP, ManeuverType.FORK -> {
                val modText = modifierPhrase(maneuverModifier)
                val exitPart = exitShield?.let { "exit $it " } ?: ""
                val roadPart = if (roadName.isNotBlank()) "towards $roadName" else ""
                "take $exitPart$modText $roadPart".trim()
            }
            ManeuverType.MERGE -> {
                val roadPart = if (roadName.isNotBlank()) " onto $roadName" else ""
                "merge$roadPart"
            }
            ManeuverType.ARRIVE -> {
                if (placeName.isNotBlank()) "you will arrive at $placeName" else "you will reach your destination"
            }
            else -> {
                val mod = modifierPhrase(maneuverModifier)
                val roadPart = if (roadName.isNotBlank()) " onto $roadName" else ""
                when {
                    maneuverModifier == ManeuverModifier.UTURN -> "make a U-turn$roadPart"
                    maneuverModifier == ManeuverModifier.STRAIGHT -> "continue straight$roadPart"
                    mod.isNotBlank() -> "turn $mod$roadPart"
                    else -> "continue$roadPart"
                }
            }
        }

        return when (stage) {
            VoiceStage.DEPART -> if (roadName.isNotBlank()) "Proceed onto $roadName." else "Follow the route."
            VoiceStage.EARLY, VoiceStage.MEDIUM -> {
                val rounded = roundedMetersOrKm(distanceMeters)
                "In $rounded, $action."
            }
            VoiceStage.FINAL -> {
                if (maneuverType == ManeuverType.ARRIVE) {
                    if (placeName.isNotBlank()) "Approaching $placeName." else "Your destination is just ahead."
                } else {
                    action.replaceFirstChar { it.uppercaseChar() } + "."
                }
            }
            VoiceStage.ARRIVE_STOP -> if (placeName.isNotBlank()) {
                "Arrived at stop $stopNumber, $placeName."
            } else {
                "Arrived at stop $stopNumber."
            }
            VoiceStage.ARRIVE_DESTINATION -> if (placeName.isNotBlank()) {
                "You have arrived at $placeName."
            } else {
                "You have arrived at your destination."
            }
        }
    }

    private fun roundedMetersOrKm(meters: Double): String {
        return if (meters >= 1000.0) {
            val km = (meters / 100.0).roundToInt() / 10.0
            if (km % 1.0 == 0.0) "${km.toInt()} kilometers" else "$km kilometers"
        } else {
            val step50 = ((meters / 50.0).roundToInt() * 50).coerceAtLeast(50)
            "$step50 meters"
        }
    }

    private fun modifierPhrase(mod: ManeuverModifier): String = when (mod) {
        ManeuverModifier.SHARP_LEFT -> "sharp left"
        ManeuverModifier.LEFT -> "left"
        ManeuverModifier.SLIGHT_LEFT -> "slight left"
        ManeuverModifier.STRAIGHT -> "straight"
        ManeuverModifier.SLIGHT_RIGHT -> "slight right"
        ManeuverModifier.RIGHT -> "right"
        ManeuverModifier.SHARP_RIGHT -> "sharp right"
        ManeuverModifier.UTURN -> "U-turn"
        ManeuverModifier.NONE -> ""
    }
}

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
