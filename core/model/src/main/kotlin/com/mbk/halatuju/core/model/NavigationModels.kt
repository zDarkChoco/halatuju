package com.mbk.halatuju.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class LocationProviderKind {
    GPS,
    NETWORK,
    FUSED,
    SIMULATED,
    GPX_REPLAY,
    DEAD_RECKONED
}

@Serializable
data class LocationFix(
    val position: LatLng,
    val accuracyMeters: Float,
    val speedMps: Float?,
    val bearingDegrees: Float?,
    val timestampMs: Long,
    val elapsedRealtimeNanos: Long = 0L,
    val provider: LocationProviderKind = LocationProviderKind.GPS
)

@Serializable
data class FilteredLocation(
    val rawFix: LocationFix,
    val smoothedPosition: LatLng,
    val smoothedSpeedMps: Float,
    val smoothedBearingDegrees: Float,
    val hasReliableBearing: Boolean,
    val rejectedAsOutlier: Boolean = false
)

@Serializable
enum class ManeuverType {
    DEPART,
    TURN,
    NEW_NAME,
    MERGE,
    ON_RAMP,
    OFF_RAMP,
    FORK,
    END_OF_ROAD,
    CONTINUE,
    ROUNDABOUT,
    ROTARY,
    ROUNDABOUT_TURN,
    EXIT_ROUNDABOUT,
    EXIT_ROTARY,
    NOTIFICATION,
    ARRIVE
}

@Serializable
enum class ManeuverModifier {
    NONE,
    UTURN,
    SHARP_RIGHT,
    RIGHT,
    SLIGHT_RIGHT,
    STRAIGHT,
    SLIGHT_LEFT,
    LEFT,
    SHARP_LEFT
}

@Serializable
enum class LaneIndication {
    NONE,
    STRAIGHT,
    SLIGHT_LEFT,
    LEFT,
    SHARP_LEFT,
    SLIGHT_RIGHT,
    RIGHT,
    SHARP_RIGHT,
    UTURN,
    MERGE_LEFT,
    MERGE_RIGHT
}

@Serializable
data class LaneInfo(
    val indications: List<LaneIndication>,
    val valid: Boolean
)

@Serializable
data class BannerInstruction(
    val distanceAlongGeometry: Double,
    val primaryText: String,
    val primaryType: ManeuverType = ManeuverType.TURN,
    val primaryModifier: ManeuverModifier = ManeuverModifier.STRAIGHT,
    val secondaryText: String? = null,
    val shieldText: String? = null,
    val exitNumber: Int? = null
)

@Serializable
data class VoiceInstruction(
    val distanceAlongGeometry: Double,
    val announcement: String,
    val ssmlAnnouncement: String? = null
)

@Serializable
data class SegmentSpeedLimit(
    val speedKmh: Int? = null,
    val isExplicitNone: Boolean = false,
    val isUnknown: Boolean = true
)

@Serializable
data class IntersectionLanes(
    val location: LatLng,
    val lanes: List<LaneInfo>,
    val geometryIndex: Int? = null
)

@Serializable
data class RouteStep(
    val instructionText: String,
    val roadName: String,
    val refCode: String? = null,
    val destinations: String? = null,
    val exits: String? = null,
    val maneuverType: ManeuverType,
    val maneuverModifier: ManeuverModifier,
    val maneuverLocation: LatLng,
    val bearingBefore: Double,
    val bearingAfter: Double,
    val rotaryExitNumber: Int? = null,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val geometryStartIndex: Int,
    val geometryEndIndex: Int,
    val bannerInstructions: List<BannerInstruction> = emptyList(),
    val voiceInstructions: List<VoiceInstruction> = emptyList(),
    val intersections: List<IntersectionLanes> = emptyList()
)

@Serializable
data class RouteLeg(
    val summary: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val steps: List<RouteStep>,
    val segmentMaxSpeeds: List<SegmentSpeedLimit> = emptyList(),
    val segmentDistancesMeters: List<Double> = emptyList(),
    val segmentDurationsSeconds: List<Double> = emptyList(),
    val segmentStartGeometryIndex: Int = 0
)

@Serializable
data class NavRoute(
    val id: String,
    val geometry: List<LatLng>,
    val cumulativeDistancesMeters: List<Double>,
    val totalDistanceMeters: Double,
    val baseDurationSeconds: Double,
    val adjustedDurationSeconds: Double,
    val trafficEstimated: Boolean,
    val trafficDelayFactor: Double = 1.0,
    val legs: List<RouteLeg>,
    val waypoints: List<Place>,
    val summaryLabel: String = ""
)
