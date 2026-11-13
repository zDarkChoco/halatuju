package com.mbk.halatuju.feature.navigation

import com.mbk.halatuju.core.model.UnitSystem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure formatting helpers for active turn-by-turn guidance UI.
 */
object NavigationFormatters {
    private val CLOCK_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    fun formatManeuverDistance(meters: Double, unitSystem: UnitSystem): String {
        val safe = meters.coerceAtLeast(0.0)
        return when (unitSystem) {
            UnitSystem.METRIC -> when {
                safe < 50.0 -> "${((safe / 5.0).roundToInt() * 5).coerceAtLeast(5)} m"
                safe < 300.0 -> "${((safe / 10.0).roundToInt() * 10)} m"
                safe < 1_000.0 -> "${((safe / 50.0).roundToInt() * 50)} m"
                safe < 10_000.0 -> String.format(Locale.US, "%.1f km", safe / 1000.0)
                else -> "${(safe / 1000.0).roundToInt()} km"
            }
            UnitSystem.IMPERIAL -> {
                val feet = safe * 3.28084
                val miles = safe / 1609.344
                when {
                    feet < 500.0 -> "${((feet / 25.0).roundToInt() * 25).coerceAtLeast(25)} ft"
                    miles < 0.2 -> "${((feet / 50.0).roundToInt() * 50)} ft"
                    miles < 10.0 -> String.format(Locale.US, "%.1f mi", miles)
                    else -> "${miles.roundToInt()} mi"
                }
            }
        }
    }

    fun formatRemainingDistance(meters: Double, unitSystem: UnitSystem): String {
        val safe = meters.coerceAtLeast(0.0)
        return when (unitSystem) {
            UnitSystem.METRIC -> when {
                safe < 1_000.0 -> "${((safe / 10.0).roundToInt() * 10).coerceAtLeast(10)} m"
                safe < 100_000.0 -> String.format(Locale.US, "%.1f km", safe / 1000.0)
                else -> "${(safe / 1000.0).roundToInt()} km"
            }
            UnitSystem.IMPERIAL -> {
                val feet = safe * 3.28084
                if (feet < 1_000.0) {
                    "${((feet / 20.0).roundToInt() * 20).coerceAtLeast(20)} ft"
                } else {
                    String.format(Locale.US, "%.1f mi", safe / 1609.344)
                }
            }
        }
    }

    fun formatRemainingDuration(seconds: Double): String {
        val mins = (seconds / 60.0).roundToInt().coerceAtLeast(0)
        return when {
            mins < 1 -> "< 1 min"
            mins < 60 -> "$mins min"
            else -> {
                val h = mins / 60
                val m = mins % 60
                if (m == 0) "$h h" else "$h h $m min"
            }
        }
    }

    fun formatEtaClock(epochMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val safeEpoch = if (epochMs > 0L) epochMs else System.currentTimeMillis()
        return Instant.ofEpochMilli(safeEpoch).atZone(zoneId).format(CLOCK_FORMATTER)
    }

    fun formatSpeedValue(speedKmh: Int, unitSystem: UnitSystem): Int {
        return when (unitSystem) {
            UnitSystem.METRIC -> speedKmh.coerceAtLeast(0)
            UnitSystem.IMPERIAL -> (speedKmh.coerceAtLeast(0) * 0.621371).roundToInt()
        }
    }

    fun speedUnitLabel(unitSystem: UnitSystem): String {
        return when (unitSystem) {
            UnitSystem.METRIC -> "km/h"
            UnitSystem.IMPERIAL -> "mph"
        }
    }
}
