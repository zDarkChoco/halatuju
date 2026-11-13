package com.mbk.halatuju.core.navigation

import com.mbk.halatuju.core.model.DebugOverlayData
import com.mbk.halatuju.core.model.GeoMath
import com.mbk.halatuju.core.model.LaneInfo
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.LocationFix
import com.mbk.halatuju.core.model.ManeuverModifier
import com.mbk.halatuju.core.model.ManeuverType
import com.mbk.halatuju.core.model.MapCameraMode
import com.mbk.halatuju.core.model.MapCameraState
import com.mbk.halatuju.core.model.NavRoute
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.model.PuckRenderState
import com.mbk.halatuju.core.model.RouteLeg
import com.mbk.halatuju.core.model.RouteStep
import com.mbk.halatuju.core.model.SegmentSpeedLimit
import com.mbk.halatuju.core.routing.TrafficProvider
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class EngineStepResult(
    val state: NavigationSessionState,
    val shouldRequestReroute: Boolean = false,
    val rerouteOrigin: LatLng? = null,
    val rerouteBearingDegrees: Double? = null,
    val voiceCue: VoicePromptCue? = null
)

/**
 * Pure Kotlin turn-by-turn state machine combining ENU Kalman filtering, circular bearing smoothing,
 * forward-monotonic route snapping, N-fix/duration off-route hysteresis, tunnel dead reckoning,
 * speed + maneuver auto-zoom, overspeed hysteresis, multi-leg intermediate stop detection,
 * and live pace-adapted ETA estimation.
 */
class NavigationEngine {

    private val kalman = EnuKalmanFilter()
    private val bearingSmoother = BearingSmoother()
    private val routeSnapper = RouteSnapper()
    private val offRouteDetector = OffRouteDetector()
    private val deadReckoner = DeadReckoner()
    private val autoZoom = AutoZoomController()
    private val voiceTracker = VoiceScheduleTracker()

    private var activeRoute: NavRoute? = null
    private var status: NavigationStatus = NavigationStatus.IDLE
    private var currentLegIndex: Int = 0
    private var distanceAlongRouteMeters: Double = 0.0
    private var tripStartEpochMs: Long = 0L
    private var isRerouting: Boolean = false
    private var overspeedLatched: Boolean = false
    private var chimeTriggerSeq: Long = 0L
    private var voiceTriggerSeq: Long = 0L
    private var lastVoicePrompt: VoicePromptEvent? = null

    fun startRoute(
        route: NavRoute,
        nowEpochMs: Long,
        clockMs: Long,
        cameraMode: MapCameraMode = MapCameraMode.NAVIGATION_25D,
        isMuted: Boolean = false
    ): EngineStepResult {
        kalman.reset()
        offRouteDetector.reset(clearCooldown = true)
        deadReckoner.reset()
        autoZoom.reset()
        voiceTracker.reset()

        val startBearing = if (route.geometry.size >= 2) {
            RoutePointSampler.safeSegmentBearing(route.geometry[0], route.geometry[1])
        } else {
            0.0
        }
        bearingSmoother.reset(startBearing)

        activeRoute = route
        status = NavigationStatus.ACTIVE
        currentLegIndex = 0
        distanceAlongRouteMeters = 0.0
        tripStartEpochMs = nowEpochMs
        isRerouting = false
        overspeedLatched = false
        lastVoicePrompt = null

        val firstRoad = route.legs.firstOrNull()?.steps?.firstOrNull()?.roadName.orEmpty()
        val departCue = voiceTracker.markDepartIfNeeded(0, firstRoad)
        if (departCue != null) {
            voiceTriggerSeq += 1
            lastVoicePrompt = VoicePromptEvent(voiceTriggerSeq, departCue.toDefaultEnglishSpeech())
        }

        val initialPos = route.geometry.firstOrNull() ?: LatLng(0.0, 0.0)
        val banner = buildBannerState(route, currentLegIndex, 0, 0.0)
        val remDuration = route.adjustedDurationSeconds
        val speedLimit = lookupSpeedLimit(route, currentLegIndex, 0)

        deadReckoner.recordValidFix(
            timestampMs = clockMs,
            speedMps = 0f,
            onRoute = true,
            distanceAlongRouteMeters = 0.0
        )

        val state = NavigationSessionState(
            status = NavigationStatus.ACTIVE,
            route = route,
            currentLegIndex = 0,
            currentStepIndex = 0,
            distanceAlongRouteMeters = 0.0,
            remainingDistanceMeters = route.totalDistanceMeters,
            remainingDurationSeconds = remDuration,
            estimatedArrivalEpochMs = nowEpochMs + (remDuration * 1000.0).roundToLong(),
            etaTrafficLabel = route.trafficLabel,
            progressFraction = 0f,
            cameraMode = cameraMode,
            cameraState = MapCameraState(
                mode = cameraMode,
                center = initialPos,
                zoom = autoZoom.targetZoom,
                bearingDegrees = startBearing,
                tiltDegrees = 52.0
            ),
            puckState = PuckRenderState(
                position = initialPos,
                bearingDegrees = startBearing.toFloat(),
                accuracyMeters = 12f,
                isDeadReckoning = false
            ),
            banner = banner,
            overspeed = OverspeedState(
                currentSpeedKmh = 0,
                speedLimit = speedLimit,
                isOverspeeding = false,
                chimeTriggerId = chimeTriggerSeq
            ),
            isRerouting = false,
            isGpsSignalWeak = false,
            arrivedStopPlace = null,
            arrivedStopNumber = 0,
            totalStopsCount = (route.waypoints.size - 2).coerceAtLeast(0),
            isMuted = isMuted,
            voiceAnnouncementTrigger = lastVoicePrompt,
            debugOverlayData = DebugOverlayData(
                kalmanPosition = initialPos,
                snappedPosition = initialPos,
                targetZoom = autoZoom.targetZoom,
                stepManeuverSummary = banner.primaryText
            )
        )
        return EngineStepResult(state = state, voiceCue = departCue)
    }

    fun applyReroutedRoute(
        newRoute: NavRoute,
        nowEpochMs: Long,
        clockMs: Long,
        currentState: NavigationSessionState
    ): NavigationSessionState {
        activeRoute = newRoute
        currentLegIndex = 0
        distanceAlongRouteMeters = 0.0
        isRerouting = false
        status = NavigationStatus.ACTIVE
        offRouteDetector.reset(clearCooldown = true)
        voiceTracker.reset()
        deadReckoner.recordValidFix(
            timestampMs = clockMs,
            speedMps = currentState.debugOverlayData.currentSpeedMps,
            onRoute = true,
            distanceAlongRouteMeters = 0.0
        )
        val banner = buildBannerState(newRoute, 0, 0, 0.0)
        return currentState.copy(
            route = newRoute,
            status = NavigationStatus.ACTIVE,
            currentLegIndex = 0,
            currentStepIndex = 0,
            distanceAlongRouteMeters = 0.0,
            remainingDistanceMeters = newRoute.totalDistanceMeters,
            remainingDurationSeconds = newRoute.adjustedDurationSeconds,
            estimatedArrivalEpochMs = nowEpochMs + (newRoute.adjustedDurationSeconds * 1000.0).roundToLong(),
            etaTrafficLabel = newRoute.trafficLabel,
            isRerouting = false,
            arrivedStopPlace = null,
            banner = banner
        )
    }

    fun setRerouting(rerouting: Boolean) {
        isRerouting = rerouting
    }

    fun advancePastIntermediateStop(
        nowEpochMs: Long,
        currentState: NavigationSessionState
    ): NavigationSessionState {
        val route = activeRoute ?: return currentState
        if (currentLegIndex >= route.legs.lastIndex) return currentState
        currentLegIndex += 1
        val legStartDist = legStartDistanceMeters(route, currentLegIndex)
        if (distanceAlongRouteMeters < legStartDist) {
            distanceAlongRouteMeters = legStartDist
        }
        status = NavigationStatus.ACTIVE
        offRouteDetector.reset(clearCooldown = true)

        val legStartStepIdx = 0
        val banner = buildBannerState(route, currentLegIndex, legStartStepIdx, distanceAlongRouteMeters)
        val remainingDist = (route.totalDistanceMeters - distanceAlongRouteMeters).coerceAtLeast(0.0)
        return currentState.copy(
            status = NavigationStatus.ACTIVE,
            currentLegIndex = currentLegIndex,
            currentStepIndex = legStartStepIdx,
            distanceAlongRouteMeters = distanceAlongRouteMeters,
            remainingDistanceMeters = remainingDist,
            arrivedStopPlace = null,
            arrivedStopNumber = 0,
            banner = banner
        )
    }

    fun stop(): NavigationSessionState {
        kalman.reset()
        bearingSmoother.reset()
        offRouteDetector.reset(clearCooldown = true)
        deadReckoner.reset()
        autoZoom.reset()
        voiceTracker.reset()
        activeRoute = null
        status = NavigationStatus.IDLE
        currentLegIndex = 0
        distanceAlongRouteMeters = 0.0
        isRerouting = false
        overspeedLatched = false
        lastVoicePrompt = null
        return NavigationSessionState(status = NavigationStatus.IDLE)
    }

    fun onLocationFix(
        fix: LocationFix,
        toleranceKmh: Int,
        trafficProvider: TrafficProvider,
        cameraMode: MapCameraMode,
        isMuted: Boolean,
        nowEpochMs: Long,
        clockMs: Long,
        previousState: NavigationSessionState
    ): EngineStepResult {
        val route = activeRoute ?: return EngineStepResult(previousState)
        if (status == NavigationStatus.IDLE || status == NavigationStatus.ARRIVED_DESTINATION) {
            return EngineStepResult(previousState)
        }

        val filtered = kalman.update(fix, bearingSmoother.bearingDegrees)

        // Outliers do not move the route snapper or corrupt off-route hysteresis
        if (filtered.rejectedAsOutlier) {
            val dbg = previousState.debugOverlayData.copy(
                rawFix = fix,
                kalmanPosition = filtered.smoothedPosition,
                rejectedOutlierCount = kalman.rejectedOutlierCount
            )
            return EngineStepResult(
                state = previousState.copy(debugOverlayData = dbg)
            )
        }

        val (smoothedBearing, reliableBrg) = bearingSmoother.update(
            speedMps = filtered.smoothedSpeedMps,
            rawBearingDegrees = fix.bearingDegrees ?: filtered.smoothedBearingDegrees
        )

        // If stopped at intermediate waypoint and driver starts heading away (>65m), auto-advance to next leg
        if (status == NavigationStatus.ARRIVED_STOP && currentLegIndex < route.legs.lastIndex) {
            val stopWaypoint = route.waypoints.getOrNull(currentLegIndex + 1)
            if (stopWaypoint != null &&
                GeoMath.haversineMeters(filtered.smoothedPosition, stopWaypoint.location) > 65.0
            ) {
                currentLegIndex += 1
                status = NavigationStatus.ACTIVE
                offRouteDetector.reset(clearCooldown = true)
            }
        }

        val legCeilingDist = legEndDistanceMeters(route, currentLegIndex)
        val snap = routeSnapper.snap(
            route = route,
            position = filtered.smoothedPosition,
            headingDegrees = smoothedBearing,
            hasReliableBearing = reliableBrg,
            accuracyMeters = fix.accuracyMeters,
            minProgressMeters = distanceAlongRouteMeters,
            maxSearchDistanceMeters = legCeilingDist
        )

        if (snap.isSnapped) {
            distanceAlongRouteMeters = maxOf(distanceAlongRouteMeters, snap.distanceAlongRouteMeters)
        }

        deadReckoner.recordValidFix(
            timestampMs = clockMs,
            speedMps = filtered.smoothedSpeedMps,
            onRoute = snap.isSnapped,
            distanceAlongRouteMeters = distanceAlongRouteMeters
        )

        val offRoute = offRouteDetector.evaluate(snap.isSnapped, clockMs)

        val displayPos = if (snap.isSnapped) snap.snappedPosition else filtered.smoothedPosition
        val displayBearing = if (snap.isSnapped) snap.snappedBearingDegrees else smoothedBearing
        val activeGeomIdx = if (snap.isSnapped) {
            snap.segmentIndex
        } else {
            RoutePointSampler.sampleAtDistance(route, distanceAlongRouteMeters).segmentIndex
        }

        // Check intermediate stop / destination arrival
        var arrivedStopPlace: Place? = previousState.arrivedStopPlace
        var cue: VoicePromptCue? = null

        val distToLegEnd = (legCeilingDist - distanceAlongRouteMeters).coerceAtLeast(0.0)
        val stopWaypoint = route.waypoints.getOrNull(currentLegIndex + 1)
        val directDistToTargetWaypoint = stopWaypoint?.let {
            GeoMath.haversineMeters(filtered.smoothedPosition, it.location)
        } ?: Double.MAX_VALUE

        if (status == NavigationStatus.ACTIVE &&
            (distToLegEnd <= ARRIVAL_RADIUS_METERS || directDistToTargetWaypoint <= ARRIVAL_RADIUS_METERS)
        ) {
            if (currentLegIndex < route.legs.lastIndex) {
                status = NavigationStatus.ARRIVED_STOP
                arrivedStopPlace = stopWaypoint
                cue = voiceTracker.markArrivalIfNeeded(
                    legIndex = currentLegIndex,
                    isIntermediateStop = true,
                    stopNumber = currentLegIndex + 1,
                    placeName = stopWaypoint?.name.orEmpty()
                )
            } else {
                status = NavigationStatus.ARRIVED_DESTINATION
                cue = voiceTracker.markArrivalIfNeeded(
                    legIndex = currentLegIndex,
                    isIntermediateStop = false,
                    stopNumber = 0,
                    placeName = route.waypoints.lastOrNull()?.name.orEmpty()
                )
            }
        }

        val currentStepIdx = findCurrentStepIndex(route.legs[currentLegIndex], activeGeomIdx)
        val banner = buildBannerState(route, currentLegIndex, currentStepIdx, distanceAlongRouteMeters)

        // Schedule speed/distance voice announcement for upcoming maneuver
        if (cue == null && status == NavigationStatus.ACTIVE && !offRoute.isOffRoute) {
            val leg = route.legs[currentLegIndex]
            val upcomingIdx = (currentStepIdx + 1).coerceAtMost(leg.steps.lastIndex)
            if (upcomingIdx > currentStepIdx) {
                val upcomingStep = leg.steps[upcomingIdx]
                val currentStepLen = leg.steps[currentStepIdx].distanceMeters
                val destName = if (upcomingStep.maneuverType == ManeuverType.ARRIVE) {
                    route.waypoints.getOrNull(currentLegIndex + 1)?.name.orEmpty()
                } else {
                    ""
                }
                cue = voiceTracker.evaluateUpcoming(
                    legIndex = currentLegIndex,
                    upcomingStepIndex = upcomingIdx,
                    stepLengthMeters = currentStepLen,
                    distanceToManeuverMeters = banner.distanceToManeuverMeters,
                    speedMps = filtered.smoothedSpeedMps,
                    maneuverType = banner.maneuverType,
                    maneuverModifier = banner.maneuverModifier,
                    roadName = banner.primaryText,
                    exitShield = banner.exitShieldText,
                    rotaryExitNumber = banner.rotaryExitNumber,
                    placeName = destName
                )
            }
        }

        if (cue != null) {
            voiceTriggerSeq += 1
            lastVoicePrompt = VoicePromptEvent(voiceTriggerSeq, cue.toDefaultEnglishSpeech())
        }

        val targetZoom = autoZoom.update(
            speedMps = filtered.smoothedSpeedMps,
            distanceToNextManeuverMeters = banner.distanceToManeuverMeters,
            hasUpcomingManeuver = status == NavigationStatus.ACTIVE && banner.distanceToManeuverMeters > 0.0,
            timestampMs = clockMs
        )

        val speedLimit = lookupSpeedLimit(route, currentLegIndex, activeGeomIdx)
        val overspeed = computeOverspeedState(
            speedMps = filtered.smoothedSpeedMps,
            limit = speedLimit,
            toleranceKmh = toleranceKmh
        )

        val remainingDist = (route.totalDistanceMeters - distanceAlongRouteMeters).coerceAtLeast(0.0)
        val progressFrac = if (route.totalDistanceMeters > 1.0) {
            (distanceAlongRouteMeters / route.totalDistanceMeters).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
        val (adaptedRemainingSec, etaLabel) = computeAdaptedRemainingEta(
            route = route,
            distanceAlongRouteMeters = distanceAlongRouteMeters,
            nowEpochMs = nowEpochMs,
            trafficProvider = trafficProvider
        )

        val state = NavigationSessionState(
            status = status,
            route = route,
            currentLegIndex = currentLegIndex,
            currentStepIndex = currentStepIdx,
            distanceAlongRouteMeters = distanceAlongRouteMeters,
            remainingDistanceMeters = remainingDist,
            remainingDurationSeconds = adaptedRemainingSec,
            estimatedArrivalEpochMs = nowEpochMs + (adaptedRemainingSec * 1000.0).roundToLong(),
            etaTrafficLabel = etaLabel,
            progressFraction = progressFrac,
            cameraMode = cameraMode,
            cameraState = MapCameraState(
                mode = cameraMode,
                center = displayPos,
                zoom = targetZoom,
                bearingDegrees = displayBearing,
                tiltDegrees = if (cameraMode == MapCameraMode.NAVIGATION_25D) 52.0 else 0.0
            ),
            puckState = PuckRenderState(
                position = displayPos,
                bearingDegrees = displayBearing.toFloat(),
                accuracyMeters = fix.accuracyMeters,
                isDeadReckoning = false
            ),
            banner = banner,
            overspeed = overspeed,
            isRerouting = isRerouting || offRoute.isOffRoute,
            isGpsSignalWeak = false,
            arrivedStopPlace = if (status == NavigationStatus.ARRIVED_STOP) arrivedStopPlace else null,
            arrivedStopNumber = if (status == NavigationStatus.ARRIVED_STOP) currentLegIndex + 1 else 0,
            totalStopsCount = (route.waypoints.size - 2).coerceAtLeast(0),
            isMuted = isMuted,
            voiceAnnouncementTrigger = lastVoicePrompt,
            debugOverlayData = DebugOverlayData(
                rawFix = fix,
                kalmanPosition = filtered.smoothedPosition,
                snappedPosition = if (snap.isSnapped) snap.snappedPosition else null,
                crossTrackErrorMeters = snap.crossTrackErrorMeters,
                headingDeltaDegrees = snap.headingDeltaDegrees,
                offRouteConsecutiveCount = offRoute.consecutiveOffRouteFixes,
                offRouteDurationSeconds = offRoute.offRouteDurationSeconds,
                isOffRoute = offRoute.isOffRoute,
                isDeadReckoning = false,
                targetZoom = targetZoom,
                currentSpeedMps = filtered.smoothedSpeedMps,
                stepManeuverSummary = "${banner.maneuverType}:${banner.maneuverModifier} (${banner.distanceToManeuverMeters.roundToInt()}m)",
                rejectedOutlierCount = kalman.rejectedOutlierCount
            )
        )

        return EngineStepResult(
            state = state,
            shouldRequestReroute = offRoute.shouldRequestReroute,
            rerouteOrigin = filtered.smoothedPosition,
            rerouteBearingDegrees = smoothedBearing,
            voiceCue = cue
        )
    }

    fun onDeadReckonTick(
        toleranceKmh: Int,
        trafficProvider: TrafficProvider,
        nowEpochMs: Long,
        clockMs: Long,
        previousState: NavigationSessionState
    ): EngineStepResult? {
        val route = activeRoute ?: return null
        if (status != NavigationStatus.ACTIVE) return null
        val legCeiling = legEndDistanceMeters(route, currentLegIndex)
        val output = deadReckoner.tick(route, clockMs, legCeiling) ?: return null

        distanceAlongRouteMeters = maxOf(distanceAlongRouteMeters, output.distanceAlongRouteMeters)
        val sample = RoutePointSampler.sampleAtDistance(route, distanceAlongRouteMeters)
        val currentStepIdx = findCurrentStepIndex(route.legs[currentLegIndex], sample.segmentIndex)
        val banner = buildBannerState(route, currentLegIndex, currentStepIdx, distanceAlongRouteMeters)

        val targetZoom = autoZoom.update(
            speedMps = output.speedMps,
            distanceToNextManeuverMeters = banner.distanceToManeuverMeters,
            hasUpcomingManeuver = banner.distanceToManeuverMeters > 0.0,
            timestampMs = clockMs
        )

        val speedLimit = lookupSpeedLimit(route, currentLegIndex, sample.segmentIndex)
        val overspeed = computeOverspeedState(
            speedMps = output.speedMps,
            limit = speedLimit,
            toleranceKmh = toleranceKmh
        )

        val remainingDist = (route.totalDistanceMeters - distanceAlongRouteMeters).coerceAtLeast(0.0)
        val progressFrac = if (route.totalDistanceMeters > 1.0) {
            (distanceAlongRouteMeters / route.totalDistanceMeters).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
        val (adaptedRemainingSec, etaLabel) = computeAdaptedRemainingEta(
            route = route,
            distanceAlongRouteMeters = distanceAlongRouteMeters,
            nowEpochMs = nowEpochMs,
            trafficProvider = trafficProvider
        )

        val nextState = previousState.copy(
            currentStepIndex = currentStepIdx,
            distanceAlongRouteMeters = distanceAlongRouteMeters,
            remainingDistanceMeters = remainingDist,
            remainingDurationSeconds = adaptedRemainingSec,
            estimatedArrivalEpochMs = nowEpochMs + (adaptedRemainingSec * 1000.0).roundToLong(),
            etaTrafficLabel = etaLabel,
            progressFraction = progressFrac,
            cameraState = previousState.cameraState.copy(
                center = output.position,
                zoom = targetZoom,
                bearingDegrees = output.bearingDegrees
            ),
            puckState = previousState.puckState.copy(
                position = output.position,
                bearingDegrees = output.bearingDegrees.toFloat(),
                isDeadReckoning = true
            ),
            banner = banner,
            overspeed = overspeed,
            isGpsSignalWeak = true,
            debugOverlayData = previousState.debugOverlayData.copy(
                snappedPosition = output.position,
                isDeadReckoning = true,
                targetZoom = targetZoom,
                currentSpeedMps = output.speedMps
            )
        )
        return EngineStepResult(state = nextState)
    }

    private fun computeOverspeedState(
        speedMps: Float,
        limit: SegmentSpeedLimit,
        toleranceKmh: Int
    ): OverspeedState {
        val currentKmh = (speedMps.coerceAtLeast(0f) * 3.6f).roundToInt()
        val limitKmh = limit.speedKmh
        if (limit.isUnknown || limit.isExplicitNone || limitKmh == null) {
            overspeedLatched = false
            return OverspeedState(
                currentSpeedKmh = currentKmh,
                speedLimit = SegmentSpeedLimit(isUnknown = true),
                isOverspeeding = false,
                chimeTriggerId = chimeTriggerSeq
            )
        }

        val exceededThreshold = currentKmh > (limitKmh + toleranceKmh)
        if (exceededThreshold) {
            if (!overspeedLatched) {
                overspeedLatched = true
                chimeTriggerSeq += 1
            }
        } else if (currentKmh <= limitKmh) {
            // Reset hysteresis strictly once speed returns at or below the posted limit
            overspeedLatched = false
        }

        return OverspeedState(
            currentSpeedKmh = currentKmh,
            speedLimit = limit,
            isOverspeeding = exceededThreshold,
            chimeTriggerId = chimeTriggerSeq
        )
    }

    private fun computeAdaptedRemainingEta(
        route: NavRoute,
        distanceAlongRouteMeters: Double,
        nowEpochMs: Long,
        trafficProvider: TrafficProvider
    ): Pair<Double, String> {
        val totalDist = route.totalDistanceMeters.coerceAtLeast(1.0)
        val fractionRemaining = ((totalDist - distanceAlongRouteMeters) / totalDist).coerceIn(0.0, 1.0)
        val baseRemaining = route.baseDurationSeconds * fractionRemaining
        val observedSec = ((nowEpochMs - tripStartEpochMs).coerceAtLeast(0L)) / 1000.0
        val expectedTraveledSec = route.baseDurationSeconds * (1.0 - fractionRemaining)
        val estimate = trafficProvider.adaptRemainingEta(
            route = route,
            distanceAlongRouteMeters = distanceAlongRouteMeters,
            baseRemainingSeconds = baseRemaining,
            traveledObservedSeconds = observedSec,
            traveledExpectedSeconds = expectedTraveledSec,
            departureTimeEpochMs = nowEpochMs
        )
        return estimate.adjustedDurationSeconds to estimate.label
    }

    private fun lookupSpeedLimit(
        route: NavRoute,
        legIndex: Int,
        geomIndex: Int
    ): SegmentSpeedLimit {
        val leg = route.legs.getOrNull(legIndex) ?: return SegmentSpeedLimit(isUnknown = true)
        if (leg.segmentMaxSpeeds.isEmpty()) return SegmentSpeedLimit(isUnknown = true)
        val relIdx = (geomIndex - leg.segmentStartGeometryIndex)
            .coerceIn(0, leg.segmentMaxSpeeds.lastIndex)
        return leg.segmentMaxSpeeds[relIdx]
    }

    private fun buildBannerState(
        route: NavRoute,
        legIndex: Int,
        currentStepIdx: Int,
        distanceAlongRoute: Double
    ): GuidanceBannerState {
        val leg = route.legs.getOrNull(legIndex) ?: return GuidanceBannerState()
        val steps = leg.steps
        if (steps.isEmpty()) return GuidanceBannerState()

        val curStep = steps[currentStepIdx.coerceIn(0, steps.lastIndex)]
        val nextStepIdx = (currentStepIdx + 1).coerceAtMost(steps.lastIndex)
        val hasSeparateNextStep = currentStepIdx + 1 <= steps.lastIndex
        val upcomingStep = if (hasSeparateNextStep) steps[nextStepIdx] else curStep

        val maneuverCumIndex = if (hasSeparateNextStep) {
            upcomingStep.geometryStartIndex.coerceIn(0, route.cumulativeDistancesMeters.lastIndex)
        } else {
            curStep.geometryEndIndex.coerceIn(0, route.cumulativeDistancesMeters.lastIndex)
        }
        val maneuverCumDist = route.cumulativeDistancesMeters.getOrElse(maneuverCumIndex) { distanceAlongRoute }
        val distToManeuver = (maneuverCumDist - distanceAlongRoute).coerceAtLeast(0.0)

        val shield = upcomingStep.exits ?: curStep.bannerInstructions.firstOrNull()?.shieldText
        val roadTitle = upcomingStep.roadName.ifBlank {
            curStep.bannerInstructions.firstOrNull()?.primaryText ?: curStep.roadName
        }

        // Lanes: first look for upcoming intersections along currentStep; fall back to next maneuver intersection within 550m
        val activeLanes = findActiveLanes(curStep, upcomingStep, route, distanceAlongRoute, distToManeuver)

        // Closely spaced "Then ..." preview when the following maneuver begins within 250 m
        val thenCandidate = if (hasSeparateNextStep) steps.getOrNull(nextStepIdx + 1) else null
        val thenPreview = if (thenCandidate != null && upcomingStep.distanceMeters <= CLOSE_MANEUVER_THRESHOLD_M) {
            SecondaryManeuverPreview(
                maneuverType = thenCandidate.maneuverType,
                maneuverModifier = thenCandidate.maneuverModifier,
                roadName = thenCandidate.roadName
            )
        } else {
            null
        }

        val displayTitle = if (upcomingStep.maneuverType == ManeuverType.ARRIVE) {
            route.waypoints.getOrNull(legIndex + 1)?.name?.let { "Arrive at $it" }
                ?: roadTitle.ifBlank { "Arrive at destination" }
        } else {
            roadTitle
        }

        return GuidanceBannerState(
            maneuverType = upcomingStep.maneuverType,
            maneuverModifier = upcomingStep.maneuverModifier,
            distanceToManeuverMeters = distToManeuver,
            primaryText = displayTitle,
            secondaryDestinations = upcomingStep.destinations,
            exitShieldText = shield,
            rotaryExitNumber = upcomingStep.rotaryExitNumber,
            lanes = activeLanes,
            thenStep = thenPreview
        )
    }

    private fun findActiveLanes(
        currentStep: RouteStep,
        upcomingStep: RouteStep,
        route: NavRoute,
        distanceAlongRoute: Double,
        distToUpcomingManeuver: Double
    ): List<LaneInfo> {
        // Upcoming intersection still ahead along the current step (within ~450 m)
        val inStep = currentStep.intersections.firstOrNull { ix ->
            val ixIdx = ix.geometryIndex ?: currentStep.geometryStartIndex
            val ixDist = route.cumulativeDistancesMeters.getOrElse(ixIdx) { Double.MAX_VALUE }
            ix.lanes.isNotEmpty() && ixDist >= distanceAlongRoute - 15.0 && (ixDist - distanceAlongRoute) <= 450.0
        }
        if (inStep != null) return inStep.lanes

        // Or the lane strip belonging to the upcoming maneuver when approaching within ~550 m
        if (upcomingStep != currentStep && distToUpcomingManeuver <= 550.0) {
            val nextIx = upcomingStep.intersections.firstOrNull { it.lanes.isNotEmpty() }
            if (nextIx != null) return nextIx.lanes
        }
        return emptyList()
    }

    private fun findCurrentStepIndex(leg: RouteLeg, geomIdx: Int): Int {
        val steps = leg.steps
        if (steps.isEmpty()) return 0
        for (i in steps.indices) {
            val s = steps[i]
            if (geomIdx < s.geometryEndIndex && s.maneuverType != ManeuverType.ARRIVE) {
                return i
            }
        }
        return steps.lastIndex
    }

    private fun legStartDistanceMeters(route: NavRoute, legIndex: Int): Double {
        val leg = route.legs.getOrNull(legIndex) ?: return 0.0
        val idx = leg.segmentStartGeometryIndex.coerceIn(0, route.cumulativeDistancesMeters.lastIndex)
        return route.cumulativeDistancesMeters.getOrElse(idx) { 0.0 }
    }

    private fun legEndDistanceMeters(route: NavRoute, legIndex: Int): Double {
        if (legIndex >= route.legs.lastIndex) return route.totalDistanceMeters
        val nextLeg = route.legs[legIndex + 1]
        val endIdx = nextLeg.segmentStartGeometryIndex.coerceIn(0, route.cumulativeDistancesMeters.lastIndex)
        return route.cumulativeDistancesMeters.getOrElse(endIdx) { route.totalDistanceMeters }
    }

    companion object {
        private const val ARRIVAL_RADIUS_METERS = 32.0
        private const val CLOSE_MANEUVER_THRESHOLD_M = 250.0
    }
}
