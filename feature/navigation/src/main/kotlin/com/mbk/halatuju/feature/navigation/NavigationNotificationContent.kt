package com.mbk.halatuju.feature.navigation

import com.mbk.halatuju.core.model.ManeuverModifier
import com.mbk.halatuju.core.model.ManeuverType
import com.mbk.halatuju.core.model.UnitSystem
import com.mbk.halatuju.core.navigation.NavigationSessionState
import com.mbk.halatuju.core.navigation.NavigationStatus
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

enum class NotificationManeuverIcon {
    STRAIGHT,
    TURN_LEFT,
    TURN_RIGHT,
    SLIGHT_LEFT,
    SLIGHT_RIGHT,
    SHARP_LEFT,
    SHARP_RIGHT,
    UTURN_LEFT,
    UTURN_RIGHT,
    MERGE,
    ROUNDABOUT,
    ARRIVE_STOP,
    ARRIVE_DESTINATION
}

data class NavigationNotificationPayload(
    val isOngoing: Boolean,
    val isArrivalCompleted: Boolean,
    val title: String,
    val text: String,
    val shortCriticalText: String,
    val progressPermille: Int,
    val stopProgressMarkersPermille: List<Int>,
    val icon: NotificationManeuverIcon,
    val showEndAction: Boolean,
    val stepIndex: Int,
    val legIndex: Int,
    val distanceToManeuverMeters: Double,
    val isRerouting: Boolean,
    val isGpsSignalWeak: Boolean
)

object NavigationNotificationContentMapper {

    fun map(
        session: NavigationSessionState,
        unitSystem: UnitSystem,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): NavigationNotificationPayload {
        return when (session.status) {
            NavigationStatus.ARRIVED_DESTINATION -> {
                val destName = session.route?.waypoints?.lastOrNull()?.name
                    ?.takeIf { it.isNotBlank() }
                    ?: "Destination reached"
                NavigationNotificationPayload(
                    isOngoing = false,
                    isArrivalCompleted = true,
                    title = "You have arrived",
                    text = destName,
                    shortCriticalText = "Done",
                    progressPermille = 1_000,
                    stopProgressMarkersPermille = emptyList(),
                    icon = NotificationManeuverIcon.ARRIVE_DESTINATION,
                    showEndAction = false,
                    stepIndex = session.currentStepIndex,
                    legIndex = session.currentLegIndex,
                    distanceToManeuverMeters = 0.0,
                    isRerouting = false,
                    isGpsSignalWeak = false
                )
            }

            NavigationStatus.ARRIVED_STOP -> {
                val stopName = session.arrivedStopPlace?.name
                    ?.takeIf { it.isNotBlank() }
                    ?: "Tap to continue to next stop"
                val progress = (session.progressFraction.coerceIn(0f, 1f) * 1_000f)
                    .roundToInt()
                    .coerceIn(0, 1_000)
                NavigationNotificationPayload(
                    isOngoing = true,
                    isArrivalCompleted = false,
                    title = "Arrived at stop ${session.arrivedStopNumber}",
                    text = stopName,
                    shortCriticalText = "Stop ${session.arrivedStopNumber}".take(6),
                    progressPermille = progress,
                    stopProgressMarkersPermille = computeStopMarkersPermille(session),
                    icon = NotificationManeuverIcon.ARRIVE_STOP,
                    showEndAction = true,
                    stepIndex = session.currentStepIndex,
                    legIndex = session.currentLegIndex,
                    distanceToManeuverMeters = 0.0,
                    isRerouting = false,
                    isGpsSignalWeak = session.isGpsSignalWeak
                )
            }

            NavigationStatus.ACTIVE -> {
                val banner = session.banner
                val maneuverDist = NavigationFormatters.formatManeuverDistance(
                    banner.distanceToManeuverMeters,
                    unitSystem
                )
                val isRoundabout = isRoundaboutManeuver(banner.maneuverType)
                val roadName = banner.primaryText.ifBlank { "Follow route" }
                val title = when {
                    session.isRerouting -> "Rerouting…"
                    isRoundabout && banner.rotaryExitNumber != null ->
                        "$maneuverDist · Exit ${banner.rotaryExitNumber} · $roadName"
                    else -> "$maneuverDist · $roadName"
                }

                val remDur = NavigationFormatters.formatRemainingDuration(session.remainingDurationSeconds)
                val remDist = NavigationFormatters.formatRemainingDistance(
                    session.remainingDistanceMeters,
                    unitSystem
                )
                val etaClock = NavigationFormatters.formatEtaClock(session.estimatedArrivalEpochMs, zoneId)
                val baseSubtitle = "$remDur · $remDist · ETA $etaClock (${session.etaTrafficLabel})"
                val text = if (session.isGpsSignalWeak) {
                    "GPS signal weak · $baseSubtitle"
                } else {
                    baseSubtitle
                }

                val chipText = if (session.isRerouting) {
                    "Wait…"
                } else {
                    formatCompactChipDistance(banner.distanceToManeuverMeters, unitSystem)
                }

                val progress = (session.progressFraction.coerceIn(0f, 1f) * 1_000f)
                    .roundToInt()
                    .coerceIn(0, 1_000)

                NavigationNotificationPayload(
                    isOngoing = true,
                    isArrivalCompleted = false,
                    title = title,
                    text = text,
                    shortCriticalText = chipText,
                    progressPermille = progress,
                    stopProgressMarkersPermille = computeStopMarkersPermille(session),
                    icon = classifyManeuverIcon(banner.maneuverType, banner.maneuverModifier),
                    showEndAction = true,
                    stepIndex = session.currentStepIndex,
                    legIndex = session.currentLegIndex,
                    distanceToManeuverMeters = banner.distanceToManeuverMeters,
                    isRerouting = session.isRerouting,
                    isGpsSignalWeak = session.isGpsSignalWeak
                )
            }

            NavigationStatus.IDLE -> {
                NavigationNotificationPayload(
                    isOngoing = true,
                    isArrivalCompleted = false,
                    title = "Starting navigation…",
                    text = "Acquiring route guidance",
                    shortCriticalText = "Start",
                    progressPermille = 0,
                    stopProgressMarkersPermille = emptyList(),
                    icon = NotificationManeuverIcon.STRAIGHT,
                    showEndAction = true,
                    stepIndex = 0,
                    legIndex = 0,
                    distanceToManeuverMeters = 0.0,
                    isRerouting = false,
                    isGpsSignalWeak = false
                )
            }
        }
    }

    /**
     * Produces <= 6 character strings so Android 16 status chips render the full label cleanly
     * without system ellipsis or collapsing to icon-only (< 7 chars threshold).
     */
    fun formatCompactChipDistance(meters: Double, unitSystem: UnitSystem): String {
        val safe = meters.coerceAtLeast(0.0)
        return when (unitSystem) {
            UnitSystem.METRIC -> when {
                safe < 50.0 -> "${((safe / 5.0).roundToInt() * 5).coerceAtLeast(5)}m"
                safe < 300.0 -> "${((safe / 10.0).roundToInt() * 10)}m"
                safe < 1_000.0 -> "${((safe / 50.0).roundToInt() * 50)}m"
                safe < 10_000.0 -> String.format(Locale.US, "%.1fkm", safe / 1000.0)
                else -> "${(safe / 1000.0).roundToInt()}km"
            }
            UnitSystem.IMPERIAL -> {
                val feet = safe * 3.28084
                val miles = safe / 1609.344
                when {
                    feet < 500.0 -> "${((feet / 25.0).roundToInt() * 25).coerceAtLeast(25)}ft"
                    miles < 0.2 -> "${((feet / 50.0).roundToInt() * 50)}ft"
                    miles < 10.0 -> String.format(Locale.US, "%.1fmi", miles)
                    else -> "${miles.roundToInt()}mi"
                }
            }
        }.take(6)
    }

    fun classifyManeuverIcon(
        type: ManeuverType,
        modifier: ManeuverModifier
    ): NotificationManeuverIcon {
        if (type == ManeuverType.ARRIVE) return NotificationManeuverIcon.ARRIVE_DESTINATION
        if (isRoundaboutManeuver(type)) return NotificationManeuverIcon.ROUNDABOUT
        if (type == ManeuverType.MERGE || type == ManeuverType.ON_RAMP) {
            return NotificationManeuverIcon.MERGE
        }
        return when (modifier) {
            ManeuverModifier.UTURN -> NotificationManeuverIcon.UTURN_RIGHT
            ManeuverModifier.SHARP_LEFT -> NotificationManeuverIcon.SHARP_LEFT
            ManeuverModifier.SHARP_RIGHT -> NotificationManeuverIcon.SHARP_RIGHT
            ManeuverModifier.LEFT -> NotificationManeuverIcon.TURN_LEFT
            ManeuverModifier.RIGHT -> NotificationManeuverIcon.TURN_RIGHT
            ManeuverModifier.SLIGHT_LEFT -> NotificationManeuverIcon.SLIGHT_LEFT
            ManeuverModifier.SLIGHT_RIGHT -> NotificationManeuverIcon.SLIGHT_RIGHT
            ManeuverModifier.STRAIGHT, ManeuverModifier.NONE -> NotificationManeuverIcon.STRAIGHT
        }
    }

    private fun isRoundaboutManeuver(type: ManeuverType): Boolean =
        type == ManeuverType.ROUNDABOUT ||
            type == ManeuverType.ROTARY ||
            type == ManeuverType.ROUNDABOUT_TURN ||
            type == ManeuverType.EXIT_ROUNDABOUT ||
            type == ManeuverType.EXIT_ROTARY

    private fun computeStopMarkersPermille(session: NavigationSessionState): List<Int> {
        val route = session.route ?: return emptyList()
        val total = route.totalDistanceMeters
        if (route.legs.size <= 1 || total <= 1.0) return emptyList()
        var acc = 0.0
        val markers = mutableListOf<Int>()
        for (i in 0 until route.legs.lastIndex) {
            acc += route.legs[i].distanceMeters
            val permille = ((acc / total) * 1_000.0).roundToInt().coerceIn(1, 999)
            if (markers.lastOrNull() != permille) {
                markers.add(permille)
            }
        }
        return markers
    }
}
