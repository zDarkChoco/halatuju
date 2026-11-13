package com.mbk.halatuju.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class MapCameraMode {
    FOLLOW_PUCK,
    NAVIGATION_25D,
    OVERVIEW,
    FREE_PAN
}

@Serializable
data class MapCameraState(
    val mode: MapCameraMode = MapCameraMode.FOLLOW_PUCK,
    val center: LatLng = KlDefaults.KLCC,
    val zoom: Double = 15.2,
    val bearingDegrees: Double = 0.0,
    val tiltDegrees: Double = 0.0
)

@Serializable
data class PuckRenderState(
    val position: LatLng = KlDefaults.KLCC,
    val bearingDegrees: Float = 0f,
    val accuracyMeters: Float = 15f,
    val isDeadReckoning: Boolean = false
)

@Serializable
data class DebugOverlayData(
    val rawFix: LocationFix? = null,
    val kalmanPosition: LatLng? = null,
    val snappedPosition: LatLng? = null,
    val crossTrackErrorMeters: Double = 0.0,
    val headingDeltaDegrees: Double = 0.0,
    val offRouteConsecutiveCount: Int = 0,
    val offRouteDurationSeconds: Double = 0.0,
    val isOffRoute: Boolean = false,
    val isDeadReckoning: Boolean = false,
    val targetZoom: Double = 15.5,
    val currentSpeedMps: Float = 0f,
    val stepManeuverSummary: String = "",
    val rejectedOutlierCount: Int = 0
)
