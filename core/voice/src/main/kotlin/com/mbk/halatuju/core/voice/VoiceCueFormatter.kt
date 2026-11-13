package com.mbk.halatuju.core.voice

import android.content.Context
import com.mbk.halatuju.core.model.ManeuverModifier
import com.mbk.halatuju.core.model.ManeuverType
import com.mbk.halatuju.core.model.UnitSystem
import com.mbk.halatuju.core.model.VoicePromptCue
import com.mbk.halatuju.core.model.VoiceStage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Formats structured [VoicePromptCue] objects through localized Android string resources
 * (`values/strings.xml`) so Bahasa Melayu (`values-ms`) translations drop in without code changes.
 */
@Singleton
class VoiceCueFormatter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun format(cue: VoicePromptCue, unitSystem: UnitSystem = UnitSystem.METRIC): String {
        val action = buildActionPhrase(cue)
        return when (cue.stage) {
            VoiceStage.DEPART -> {
                if (cue.roadName.isNotBlank()) {
                    context.getString(R.string.voice_stage_depart_road, cue.roadName)
                } else {
                    context.getString(R.string.voice_stage_depart_generic)
                }
            }

            VoiceStage.EARLY, VoiceStage.MEDIUM -> {
                val distLabel = formatDistance(cue.distanceMeters, unitSystem)
                context.getString(R.string.voice_stage_in_distance, distLabel, action)
            }

            VoiceStage.FINAL -> {
                if (cue.maneuverType == ManeuverType.ARRIVE) {
                    if (cue.placeName.isNotBlank()) {
                        context.getString(R.string.voice_stage_approaching_destination, cue.placeName)
                    } else {
                        context.getString(R.string.voice_stage_approaching_generic)
                    }
                } else {
                    val capitalized = action.replaceFirstChar { ch ->
                        if (ch.isLowerCase()) ch.titlecase(Locale.getDefault()) else ch.toString()
                    }
                    context.getString(R.string.voice_stage_now, capitalized)
                }
            }

            VoiceStage.ARRIVE_STOP -> {
                if (cue.placeName.isNotBlank()) {
                    context.getString(R.string.voice_stage_arrive_stop_named, cue.stopNumber, cue.placeName)
                } else {
                    context.getString(R.string.voice_stage_arrive_stop_generic, cue.stopNumber)
                }
            }

            VoiceStage.ARRIVE_DESTINATION -> {
                if (cue.placeName.isNotBlank()) {
                    context.getString(R.string.voice_stage_arrive_destination_named, cue.placeName)
                } else {
                    context.getString(R.string.voice_stage_arrive_destination_generic)
                }
            }
        }
    }

    private fun buildActionPhrase(cue: VoicePromptCue): String {
        return when (cue.maneuverType) {
            ManeuverType.ROUNDABOUT,
            ManeuverType.ROTARY,
            ManeuverType.ROUNDABOUT_TURN -> {
                val base = cue.rotaryExitNumber?.let {
                    context.getString(R.string.voice_action_roundabout_exit, it)
                } ?: context.getString(R.string.voice_action_roundabout_enter)
                appendOntoRoad(base, cue.roadName)
            }

            ManeuverType.OFF_RAMP,
            ManeuverType.ON_RAMP,
            ManeuverType.FORK -> {
                val modText = formatModifier(cue.maneuverModifier).ifBlank {
                    context.getString(R.string.voice_mod_straight)
                }
                val base = if (!cue.exitShield.isNullOrBlank()) {
                    context.getString(R.string.voice_action_ramp_exit_mod, cue.exitShield, modText)
                } else {
                    context.getString(R.string.voice_action_ramp_mod, modText)
                }
                appendTowardsRoad(base, cue.roadName)
            }

            ManeuverType.MERGE -> {
                appendOntoRoad(context.getString(R.string.voice_action_merge), cue.roadName)
            }

            ManeuverType.ARRIVE -> {
                if (cue.placeName.isNotBlank()) {
                    context.getString(R.string.voice_action_arrive_named, cue.placeName)
                } else {
                    context.getString(R.string.voice_action_arrive_generic)
                }
            }

            else -> {
                val modText = formatModifier(cue.maneuverModifier)
                val base = when {
                    cue.maneuverModifier == ManeuverModifier.UTURN ->
                        context.getString(R.string.voice_action_uturn)
                    cue.maneuverModifier == ManeuverModifier.STRAIGHT ->
                        context.getString(R.string.voice_action_straight)
                    modText.isNotBlank() ->
                        context.getString(R.string.voice_action_turn_mod, modText)
                    else ->
                        context.getString(R.string.voice_action_continue)
                }
                appendOntoRoad(base, cue.roadName)
            }
        }
    }

    private fun appendOntoRoad(action: String, roadName: String): String {
        return if (roadName.isNotBlank()) {
            context.getString(R.string.voice_prep_onto, action, roadName)
        } else {
            action
        }
    }

    private fun appendTowardsRoad(action: String, roadName: String): String {
        return if (roadName.isNotBlank()) {
            context.getString(R.string.voice_prep_towards, action, roadName)
        } else {
            action
        }
    }

    private fun formatModifier(modifier: ManeuverModifier): String = when (modifier) {
        ManeuverModifier.SHARP_LEFT -> context.getString(R.string.voice_mod_sharp_left)
        ManeuverModifier.LEFT -> context.getString(R.string.voice_mod_left)
        ManeuverModifier.SLIGHT_LEFT -> context.getString(R.string.voice_mod_slight_left)
        ManeuverModifier.STRAIGHT -> context.getString(R.string.voice_mod_straight)
        ManeuverModifier.SLIGHT_RIGHT -> context.getString(R.string.voice_mod_slight_right)
        ManeuverModifier.RIGHT -> context.getString(R.string.voice_mod_right)
        ManeuverModifier.SHARP_RIGHT -> context.getString(R.string.voice_mod_sharp_right)
        ManeuverModifier.UTURN -> context.getString(R.string.voice_mod_uturn)
        ManeuverModifier.NONE -> ""
    }

    private fun formatDistance(meters: Double, unitSystem: UnitSystem): String {
        val safe = meters.coerceAtLeast(10.0)
        return when (unitSystem) {
            UnitSystem.METRIC -> {
                if (safe >= 1000.0) {
                    val tenths = (safe / 100.0).roundToInt()
                    if (tenths % 10 == 0) {
                        context.getString(R.string.voice_dist_km_whole, tenths / 10)
                    } else {
                        context.getString(R.string.voice_dist_km_decimal, String.format(Locale.US, "%.1f", tenths / 10.0))
                    }
                } else {
                    val step50 = ((safe / 50.0).roundToInt() * 50).coerceAtLeast(50)
                    context.getString(R.string.voice_dist_meters, step50)
                }
            }

            UnitSystem.IMPERIAL -> {
                val feet = safe * 3.28084
                val miles = safe / 1609.344
                if (miles >= 0.25) {
                    val tenths = (miles * 10.0).roundToInt()
                    if (tenths % 10 == 0) {
                        context.getString(R.string.voice_dist_miles_whole, tenths / 10)
                    } else {
                        context.getString(R.string.voice_dist_miles_decimal, String.format(Locale.US, "%.1f", tenths / 10.0))
                    }
                } else {
                    val step50 = ((feet / 50.0).roundToInt() * 50).coerceAtLeast(50)
                    context.getString(R.string.voice_dist_feet, step50)
                }
            }
        }
    }
}
