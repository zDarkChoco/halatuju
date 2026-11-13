package com.mbk.halatuju.core.navigation

import com.mbk.halatuju.core.model.DebugOverlayData
import com.mbk.halatuju.core.model.LaneInfo
import com.mbk.halatuju.core.model.ManeuverModifier
import com.mbk.halatuju.core.model.ManeuverType
import com.mbk.halatuju.core.model.MapCameraMode
import com.mbk.halatuju.core.model.MapCameraState
import com.mbk.halatuju.core.model.NavRoute
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.model.PuckRenderState
import com.mbk.halatuju.core.model.SegmentSpeedLimit

enum class NavigationStatus {
    IDLE,
    ACTIVE,
    ARRIVED_STOP,
    ARRIVED_DESTINATION
}

data class SecondaryManeuverPreview(
    val maneuverType: ManeuverType,
    val maneuverModifier: ManeuverModifier,
    val roadName: String
)

data class GuidanceBannerState(
    val maneuverType: ManeuverType = ManeuverType.CONTINUE,
    val maneuverModifier: ManeuverModifier = ManeuverModifier.STRAIGHT,
    val distanceToManeuverMeters: Double = 0.0,
    val primaryText: String = "",
    val secondaryDestinations: String? = null,
    val exitShieldText: String? = null,
    val rotaryExitNumber: Int? = null,
    val lanes: List<LaneInfo> = emptyList(),
    val thenStep: SecondaryManeuverPreview? = null
)

data class OverspeedState(
    val currentSpeedKmh: Int = 0,
    val speedLimit: SegmentSpeedLimit = SegmentSpeedLimit(isUnknown = true),
    val isOverspeeding: Boolean = false,
    val chimeTriggerId: Long = 0L
)

data class NavigationSessionState(
    val status: NavigationStatus = NavigationStatus.IDLE,
    val route: NavRoute? = null,
    val currentLegIndex: Int = 0,
    val currentStepIndex: Int = 0,
    val distanceAlongRouteMeters: Double = 0.0,
    val remainingDistanceMeters: Double = 0.0,
    val remainingDurationSeconds: Double = 0.0,
    val estimatedArrivalEpochMs: Long = 0L,
    val etaTrafficLabel: String = "estimated",
    val progressFraction: Float = 0f,
    val cameraMode: MapCameraMode = MapCameraMode.NAVIGATION_25D,
    val cameraState: MapCameraState = MapCameraState(
        mode = MapCameraMode.NAVIGATION_25D,
        tiltDegrees = 52.0,
        zoom = 16.8
    ),
    val puckState: PuckRenderState = PuckRenderState(),
    val banner: GuidanceBannerState = GuidanceBannerState(),
    val overspeed: OverspeedState = OverspeedState(),
    val isRerouting: Boolean = false,
    val isGpsSignalWeak: Boolean = false,
    val arrivedStopPlace: Place? = null,
    val arrivedStopNumber: Int = 0,
    val totalStopsCount: Int = 0,
    val isMuted: Boolean = false,
    val voiceAnnouncementTrigger: VoicePromptEvent? = null,
    val debugOverlayData: DebugOverlayData = DebugOverlayData()
)

data class VoicePromptEvent(
    val id: Long,
    val text: String,
    val cue: com.mbk.halatuju.core.model.VoicePromptCue? = null
)
