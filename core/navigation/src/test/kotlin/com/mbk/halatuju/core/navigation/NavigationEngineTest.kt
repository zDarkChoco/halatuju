package com.mbk.halatuju.core.navigation

import com.mbk.halatuju.core.model.GeoMath
import com.mbk.halatuju.core.model.IntersectionLanes
import com.mbk.halatuju.core.model.KlDefaults
import com.mbk.halatuju.core.model.LaneIndication
import com.mbk.halatuju.core.model.LaneInfo
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.LocationFix
import com.mbk.halatuju.core.model.LocationProviderKind
import com.mbk.halatuju.core.model.ManeuverModifier
import com.mbk.halatuju.core.model.ManeuverType
import com.mbk.halatuju.core.model.MapCameraMode
import com.mbk.halatuju.core.model.NavRoute
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.model.RouteLeg
import com.mbk.halatuju.core.model.RouteStep
import com.mbk.halatuju.core.model.SegmentSpeedLimit
import com.mbk.halatuju.core.routing.KlPeakTrafficProvider
import com.mbk.halatuju.core.routing.TrafficEstimate
import com.mbk.halatuju.core.routing.TrafficProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class NavigationEngineTest {

    private val anchor = KlDefaults.KLCC

    private fun pointEn(eastMeters: Double, northMeters: Double): LatLng {
        return GeoMath.fromLocalEnMeters(anchor, eastMeters, northMeters)
    }

    private fun fixEn(
        eastMeters: Double,
        northMeters: Double,
        accuracyMeters: Float = 8f,
        speedMps: Float? = 15f,
        bearingDegrees: Float? = 0f,
        timestampMs: Long
    ): LocationFix {
        return LocationFix(
            position = pointEn(eastMeters, northMeters),
            accuracyMeters = accuracyMeters,
            speedMps = speedMps,
            bearingDegrees = bearingDegrees,
            timestampMs = timestampMs,
            provider = LocationProviderKind.GPS
        )
    }

    @Test
    fun `kalman filter smooths lateral jitter, weights accuracy R, rejects outliers, and recovers on tunnel exit`() {
        val filter = EnuKalmanFilter()

        // Drive steadily North at 15 m/s along KL corridor with +/- 10 m East/West jitter
        val rawEastErrors = mutableListOf<Double>()
        val filteredEastErrors = mutableListOf<Double>()

        for (t in 0..14) {
            val jitterE = if (t % 2 == 0) 10.0 else -10.0
            val fix = fixEn(
                eastMeters = jitterE,
                northMeters = t * 15.0,
                accuracyMeters = 10f,
                speedMps = 15f,
                bearingDegrees = 0f,
                timestampMs = 1_000L + t * 1_000L
            )
            val res = filter.update(fix)
            assertFalse(res.rejectedAsOutlier)
            val (fe, fn) = GeoMath.toLocalEnMeters(anchor, res.smoothedPosition)
            if (t >= 3) {
                rawEastErrors.add(abs(jitterE))
                filteredEastErrors.add(abs(fe))
                assertTrue("Filtered north progress should follow corridor", fn > (t - 2) * 15.0)
            }
        }

        val rawRms = sqrt(rawEastErrors.map { it * it }.average())
        val filtRms = sqrt(filteredEastErrors.map { it * it }.average())
        assertTrue(
            "Expected Kalman RMS ($filtRms m) well below raw jitter RMS ($rawRms m)",
            filtRms < rawRms * 0.65
        )

        // Single 600 m teleport outlier MUST be rejected
        val beforeJump = filter.update(
            fixEn(0.0, 15 * 15.0, accuracyMeters = 8f, speedMps = 15f, bearingDegrees = 0f, timestampMs = 16_000L)
        )
        val outlier = filter.update(
            fixEn(600.0, 600.0, accuracyMeters = 8f, speedMps = 15f, bearingDegrees = 45f, timestampMs = 17_000L)
        )
        assertTrue("Impossible 600m 1-second jump must be rejected", outlier.rejectedAsOutlier)
        assertEquals(1, filter.rejectedOutlierCount)
        assertEquals(beforeJump.smoothedPosition.latitude, outlier.smoothedPosition.latitude, 1e-6)
        assertEquals(beforeJump.smoothedPosition.longitude, outlier.smoothedPosition.longitude, 1e-6)

        // Coarse >180m accuracy outlier also rejected without throwing off track
        val coarse = filter.update(
            fixEn(120.0, 240.0, accuracyMeters = 220f, speedMps = 15f, bearingDegrees = 0f, timestampMs = 18_000L)
        )
        assertTrue(coarse.rejectedAsOutlier)

        // Tunnel exit relocation: 5 consistent relocated fixes allow filter to re-initialize cleanly
        val tunnelExitAnchorE = 800.0
        var reacquired = false
        for (k in 0..5) {
            val res = filter.update(
                fixEn(
                    eastMeters = tunnelExitAnchorE,
                    northMeters = 900.0 + k * 18.0,
                    accuracyMeters = 6f,
                    speedMps = 18f,
                    bearingDegrees = 0f,
                    timestampMs = 40_000L + k * 1_000L
                )
            )
            if (!res.rejectedAsOutlier) {
                val (eOut, _) = GeoMath.toLocalEnMeters(anchor, res.smoothedPosition)
                if (abs(eOut - tunnelExitAnchorE) < 20.0) {
                    reacquired = true
                }
            }
        }
        assertTrue("Kalman filter should re-acquire after consistent tunnel emergence", reacquired)
    }

    @Test
    fun `bearing smoother holds last heading below 2 mps and wraps cleanly across 359 to 0 deg`() {
        val smoother = BearingSmoother()

        // Establish initial moving heading due North-West (355 deg) at 10 m/s
        val (b0, r0) = smoother.update(speedMps = 10.0f, rawBearingDegrees = 355f)
        assertTrue(r0)
        assertEquals(355.0, b0, 1e-6)

        // Slow below 2.0 m/s -> erratic compass swings (140 deg, 220 deg) must NOT rotate heading
        val (bLow1, rLow1) = smoother.update(speedMps = 1.9f, rawBearingDegrees = 140f)
        val (bLow2, rLow2) = smoother.update(speedMps = 0.0f, rawBearingDegrees = 220f)
        assertTrue(rLow1)
        assertTrue(rLow2)
        assertEquals(355.0, bLow1, 1e-6)
        assertEquals(355.0, bLow2, 1e-6)

        // Cross 359 -> 2 deg at highway speed -> must move clockwise +7 deg via 0, NEVER counter-clockwise via 180
        val (bCross1, _) = smoother.update(speedMps = 20.0f, rawBearingDegrees = 2f)
        assertTrue(
            "Wrap 355 -> 2 deg must stay near 358..2, got $bCross1",
            bCross1 >= 355.0 || bCross1 <= 3.0
        )

        val (bCross2, _) = smoother.update(speedMps = 20.0f, rawBearingDegrees = 1f)
        val deltaFromNorth = GeoMath.angularDifferenceDegrees(bCross2, 0.5)
        assertTrue("Heading should converge tightly across 0/360 seam (delta=$deltaFromNorth)", deltaFromNorth < 4.0)

        // Cross backward 1 -> 357 deg cleanly
        smoother.reset(1.0)
        val (bReverse, _) = smoother.update(speedMps = 14.0f, rawBearingDegrees = 357f)
        assertTrue(
            "Wrap 1 -> 357 deg must stay near 0/359, got $bReverse",
            bReverse >= 357.0 || bReverse <= 1.0
        )
    }

    @Test
    fun `route snapper rejects opposite carriageway, perpendicular overpass, parallel frontage road, and backward jumps`() {
        val snapper = RouteSnapper()
        val route = straightNorthRoute(lengthMeters = 1_000.0)

        // 1. Valid on-corridor fix (8 m East offset, 12 deg heading delta, accuracy 8 m -> corridor 25 m)
        val onRoute = snapper.snap(
            route = route,
            position = pointEn(8.0, 200.0),
            headingDegrees = 12.0,
            hasReliableBearing = true,
            accuracyMeters = 8f,
            minProgressMeters = 100.0
        )
        assertTrue(onRoute.isSnapped)
        assertEquals(200.0, onRoute.distanceAlongRouteMeters, 1.0)
        val (snapE, snapN) = GeoMath.toLocalEnMeters(anchor, onRoute.snappedPosition)
        assertEquals(0.0, snapE, 0.25)
        assertEquals(200.0, snapN, 1.0)

        // 2. Dynamic corridor radius: 22 m accuracy expands corridor to max(25, 2*22) = 44 m -> 38 m East snaps
        val wideAccSnap = snapper.snap(
            route = route,
            position = pointEn(38.0, 250.0),
            headingDegrees = 0.0,
            hasReliableBearing = true,
            accuracyMeters = 22f,
            minProgressMeters = 200.0
        )
        assertTrue("2*22m=44m corridor should accept 38m offset", wideAccSnap.isSnapped)

        // 3. Parallel frontage road (32 m East, accuracy 8 m -> corridor 25 m) -> REJECTED
        val parallelFrontage = snapper.snap(
            route = route,
            position = pointEn(32.0, 300.0),
            headingDegrees = 0.0,
            hasReliableBearing = true,
            accuracyMeters = 8f,
            minProgressMeters = 250.0
        )
        assertFalse("Parallel frontage road 32m away must not snap", parallelFrontage.isSnapped)

        // 4. Opposite carriageway (10 m East, inside corridor, heading South 180 deg) -> REJECTED
        val oppositeCarriageway = snapper.snap(
            route = route,
            position = pointEn(10.0, 300.0),
            headingDegrees = 180.0,
            hasReliableBearing = true,
            accuracyMeters = 8f,
            minProgressMeters = 250.0
        )
        assertFalse("Opposite carriageway (180 deg heading delta) must not snap", oppositeCarriageway.isSnapped)
        assertTrue(oppositeCarriageway.headingDeltaDegrees > 170.0)

        // 5. Perpendicular overpass (0 m East, exact overlap, heading East 90 deg) -> REJECTED
        val overpass = snapper.snap(
            route = route,
            position = pointEn(0.0, 400.0),
            headingDegrees = 90.0,
            hasReliableBearing = true,
            accuracyMeters = 6f,
            minProgressMeters = 350.0
        )
        assertFalse("Perpendicular crossing overpass (90 deg) must not snap", overpass.isSnapped)

        // 6. Monotonic progress: fix 40 m backward must NEVER regress route distance
        val backward = snapper.snap(
            route = route,
            position = pointEn(0.0, 400.0),
            headingDegrees = 0.0,
            hasReliableBearing = true,
            accuracyMeters = 6f,
            minProgressMeters = 450.0
        )
        assertFalse("Fix 50m behind current monotonic progress must not snap backward", backward.isSnapped)
        assertEquals(450.0, backward.distanceAlongRouteMeters, 1e-6)
    }

    @Test
    fun `off route detector enforces both minimum consecutive fixes and elapsed duration with cooldown`() {
        val detector = OffRouteDetector(
            minConsecutiveFixes = 5,
            minDurationMs = 6_000L,
            rerouteCooldownMs = 8_000L
        )

        // 5 fixes occurring within 4.0 seconds (< 6.0 s): still NOT off-route
        for (i in 0 until 5) {
            val st = detector.evaluate(isSnapped = false, timestampMs = i * 1_000L)
            assertFalse("Must require >= 6s even with 5 fixes (at ${i}s)", st.isOffRoute)
            assertFalse(st.shouldRequestReroute)
        }

        // At t = 6.0 s (6th fix, 6s elapsed): triggers off-route & reroute!
        val triggered = detector.evaluate(isSnapped = false, timestampMs = 6_000L)
        assertTrue(triggered.isOffRoute)
        assertTrue(triggered.shouldRequestReroute)

        // Next fix 1s later stays off-route but obeys 8s cooldown
        val duringCooldown = detector.evaluate(isSnapped = false, timestampMs = 7_000L)
        assertTrue(duringCooldown.isOffRoute)
        assertFalse(duringCooldown.shouldRequestReroute)

        // Brief return to corridor resets consecutive counter & timer cleanly
        val recovered = detector.evaluate(isSnapped = true, timestampMs = 8_000L)
        assertFalse(recovered.isOffRoute)
        assertEquals(0, recovered.consecutiveOffRouteFixes)

        // 4 fixes over 10s (< 5 fixes): STILL NOT off-route
        assertFalse(detector.evaluate(false, 10_000L).isOffRoute)
        assertFalse(detector.evaluate(false, 13_000L).isOffRoute)
        assertFalse(detector.evaluate(false, 16_000L).isOffRoute)
        val fourFixesOnly = detector.evaluate(false, 20_000L)
        assertFalse("Must require >= 5 fixes even after 10s", fourFixesOnly.isOffRoute)

        // 5th fix -> triggers again cleanly once cooldown (>= 8s since 6_000L) passed
        val retriggered = detector.evaluate(false, 21_000L)
        assertTrue(retriggered.isOffRoute)
        assertTrue(retriggered.shouldRequestReroute)
    }

    @Test
    fun `dead reckoner activates after 2_5s gps blackout, extrapolates smoothly, and decays after 25s`() {
        val route = straightNorthRoute(lengthMeters = 3_000.0)
        val dr = DeadReckoner()
        dr.recordValidFix(
            timestampMs = 10_000L,
            speedMps = 20f,
            onRoute = true,
            distanceAlongRouteMeters = 500.0
        )

        // Below threshold -> inactive
        assertNull(dr.tick(route, nowMs = 11_000L, maxDistanceLimitMeters = 3_000.0))
        assertNull(dr.tick(route, nowMs = 12_400L, maxDistanceLimitMeters = 3_000.0))

        // At 13_000 ms (3s outage): tick starts extrapolating along route
        var lastDist = 500.0
        for (ms in 13_000L..25_000L step 1_000L) {
            val out = dr.tick(route, nowMs = ms, maxDistanceLimitMeters = 3_000.0)
            assertNotNull(out)
            assertTrue(out!!.isDeadReckoning)
            assertTrue(
                "Expected forward tunnel progression at ${ms}ms",
                out.distanceAlongRouteMeters > lastDist
            )
            lastDist = out.distanceAlongRouteMeters
        }
        val distAt25s = lastDist
        assertTrue("Expected ~200m+ progression during tunnel cruise", distAt25s > 720.0)

        // After 25s (outage > 25_000ms), speed decays linearly down to 0 by 60s outage (t=70_000ms)
        var decayedOut: DeadReckonOutput? = null
        for (ms in 26_000L..71_000L step 1_000L) {
            decayedOut = dr.tick(route, nowMs = ms, maxDistanceLimitMeters = 3_000.0)
        }
        assertNotNull(decayedOut)
        assertEquals(0f, decayedOut!!.speedMps, 0.01f)
    }

    @Test
    fun `auto zoom interpolates between city and highway, prevents speed breathing, and zooms in within 300m maneuver`() {
        val controller = AutoZoomController()

        // Steady city speed (25 km/h = 6.94 m/s) -> converges to city zoom ~17.5
        var zCity = 0.0
        for (i in 1..20) {
            zCity = controller.update(
                speedMps = 6.94f,
                distanceToNextManeuverMeters = 900.0,
                hasUpcomingManeuver = true,
                timestampMs = i * 1_000L
            )
        }
        assertEquals(AutoZoomController.DEFAULT_CITY_ZOOM, zCity, 0.15)

        // Highway speed (100 km/h = 27.8 m/s) -> converges smoothly to highway zoom ~14.6
        var zHighway = zCity
        for (i in 21..45) {
            zHighway = controller.update(
                speedMps = 27.8f,
                distanceToNextManeuverMeters = 2_000.0,
                hasUpcomingManeuver = true,
                timestampMs = i * 1_000L
            )
        }
        assertEquals(AutoZoomController.DEFAULT_HIGHWAY_ZOOM, zHighway, 0.15)

        // Anti-breathing test: oscillate +/- 2.5 km/h around 55 km/h -> zero breathing oscillation
        val settleController = AutoZoomController()
        var tMs = 1_000L
        repeat(15) {
            settleController.update(15.3f, 1_500.0, true, tMs)
            tMs += 1_000L
        }
        val baselineZoom = settleController.targetZoom
        val zoomsDuringOscillation = (1..20).map { step ->
            val oscillatingSpeedMps = if (step % 2 == 0) 15.9f else 14.7f // +/- 2.2 km/h jitter (< 4 km/h deadband)
            tMs += 1_000L
            settleController.update(oscillatingSpeedMps, 1_500.0, true, tMs)
        }
        assertTrue(
            "Speed jitter within hysteresis deadband must never breathe zoom",
            zoomsDuringOscillation.all { abs(it - baselineZoom) < 0.02 }
        )

        // Maneuver approach zoom-in within 300 m, then restore once > 380 m
        var cruiseTime = 60_000L
        repeat(12) {
            controller.update(speedMps = 25.0f, distanceToNextManeuverMeters = 1_200.0, hasUpcomingManeuver = true, timestampMs = cruiseTime)
            cruiseTime += 1_000L
        }
        val preManeuverHighwayZoom = controller.targetZoom
        assertTrue(preManeuverHighwayZoom < 15.1)

        // Approach from 280m down to 100m over 8s
        repeat(8) { s ->
            val d = 280.0 - s * 22.0
            controller.update(speedMps = 25.0f, distanceToNextManeuverMeters = d, hasUpcomingManeuver = true, timestampMs = cruiseTime)
            cruiseTime += 1_000L
        }
        assertTrue(controller.isManeuverZoomInActive)
        val nearManeuverZoom = controller.targetZoom
        assertTrue(
            "Expected maneuver zoom-in significantly above highway zoom ($preManeuverHighwayZoom -> $nearManeuverZoom)",
            nearManeuverZoom > preManeuverHighwayZoom + 1.5
        )

        // Exit maneuver zone (> 380 m to next maneuver) -> restores smoothly back down
        repeat(12) {
            controller.update(speedMps = 25.0f, distanceToNextManeuverMeters = 900.0, hasUpcomingManeuver = true, timestampMs = cruiseTime)
            cruiseTime += 1_000L
        }
        assertFalse(controller.isManeuverZoomInActive)
        assertTrue(
            "Expected highway zoom restoration after clearing maneuver",
            controller.targetZoom < nearManeuverZoom - 1.5
        )
    }

    @Test
    fun `navigation engine handles multi stop arrival, overspeed hysteresis, and once-per-stage voice timing`() {
        val engine = NavigationEngine()
        val trafficProvider = ConstantTrafficProvider()

        // Build 2-leg 3,000 m corridor route: Leg 0 (0..1500m, stop 1 at 1500m), Leg 1 (1500..3000m, dest at 3000m)
        val route = twoLegNorthRoute(speedLimitKmh = 80)

        val init = engine.startRoute(
            route = route,
            nowEpochMs = 1_000_000L,
            clockMs = 10_000L,
            cameraMode = MapCameraMode.NAVIGATION_25D,
            isMuted = false
        )
        var state = init.state
        assertEquals(NavigationStatus.ACTIVE, state.status)
        val initCue = init.voiceCue
        assertNotNull("Expected initial depart voice cue", initCue)
        assertEquals(VoiceStage.DEPART, initCue!!.stage)
        assertEquals(1, state.totalStopsCount)
        assertEquals(80, state.overspeed.speedLimit.speedKmh)

        val observedVoiceKeys = mutableListOf<String>()
        observedVoiceKeys.add("${initCue.legIndex}:${initCue.stepIndex}:${initCue.stage}")

        val chimeEvents = mutableListOf<Long>()
        var lastChimeSeq = state.overspeed.chimeTriggerId

        // Step along Leg 0 with physically consistent (northMeters, speedMps) samples (limit = 80, tol = +5):
        val leg0DriveProfile = listOf(
            // Start cruising safely at ~76 km/h (21 m/s)
            30.0 to 21.0f,
            60.0 to 21.0f,
            // Slightly above limit (82.8 km/h = 23.0 m/s) -> within +5 tolerance, MUST NOT chime
            95.0 to 23.0f,
            130.0 to 23.0f,
            // Exceed 85 km/h (90 km/h = 25.0 m/s) -> triggers chime #1!
            180.0 to 25.0f,
            230.0 to 25.0f,
            // Ease slightly to 83 km/h (23.0 m/s): still > 80 limit -> MUST remain latched (no re-chime)
            276.0 to 23.0f,
            322.0 to 23.0f,
            // Drop below posted 80 km/h limit (72 km/h = 20.0 m/s) across several fixes -> unlatches!
            362.0 to 20.0f,
            402.0 to 20.0f,
            442.0 to 20.0f,
            // Speed up again past 85 km/h (90 km/h = 25.0 m/s) -> triggers chime #2!
            492.0 to 25.0f,
            542.0 to 25.0f,
            // Approach turn at 1,000 m at 20 m/s (hits MEDIUM & FINAL voice windows cleanly)
            700.0 to 20.0f,
            840.0 to 20.0f,
            960.0 to 20.0f,
            1_080.0 to 20.0f,
            1_300.0 to 18.0f,
            1_480.0 to 10.0f // within 32m of intermediate stop 1 at 1500m -> ARRIVED_STOP!
        )

        var prevNorth = 0.0
        var clockMs = 11_000L
        var epochMs = 1_001_000L
        leg0DriveProfile.forEach { (northM, spd) ->
            val dtMs = (((northM - prevNorth).coerceAtLeast(10.0) / spd.coerceAtLeast(5f)) * 1000.0).toLong()
            clockMs += dtMs
            epochMs += dtMs
            prevNorth = northM

            val res = engine.onLocationFix(
                fix = fixEn(0.0, northM, accuracyMeters = 5f, speedMps = spd, bearingDegrees = 0f, timestampMs = epochMs),
                toleranceKmh = 5,
                trafficProvider = trafficProvider,
                cameraMode = MapCameraMode.NAVIGATION_25D,
                isMuted = false,
                nowEpochMs = epochMs,
                clockMs = clockMs,
                previousState = state
            )
            state = res.state
            assertFalse("Realistic fix at $northM m should not be rejected", state.debugOverlayData.rejectedOutlierCount > 0)
            if (state.overspeed.chimeTriggerId > lastChimeSeq) {
                chimeEvents.add(state.overspeed.chimeTriggerId)
                lastChimeSeq = state.overspeed.chimeTriggerId
            }
            res.voiceCue?.let { cue ->
                val key = "${cue.legIndex}:${cue.stepIndex}:${cue.stage}"
                assertFalse("Voice stage $key must never fire twice!", key in observedVoiceKeys)
                observedVoiceKeys.add(key)
            }
        }

        // Verify overspeed chimed strictly twice (initial excursion, remained silent at 83, reset <=80, second excursion)
        assertEquals("Expected exact overspeed hysteresis chimes = 2", 2, chimeEvents.size)

        // Verify arrival at Intermediate Stop 1
        assertEquals(NavigationStatus.ARRIVED_STOP, state.status)
        assertNotNull(state.arrivedStopPlace)
        assertEquals("KL Sentral", state.arrivedStopPlace!!.name)
        assertEquals(1, state.arrivedStopNumber)
        assertTrue(observedVoiceKeys.any { it.contains("ARRIVE_STOP") })

        // Verify explicit Continue transitions cleanly to Leg 1
        state = engine.advancePastIntermediateStop(epochMs, state)
        assertEquals(NavigationStatus.ACTIVE, state.status)
        assertEquals(1, state.currentLegIndex)
        assertNull(state.arrivedStopPlace)
        assertEquals(0, state.arrivedStopNumber)

        // Drive Leg 1 through unknown speed limit zone -> badge hidden cleanly, then arrive at KLIA (3000m)
        val leg1Points = listOf(1_650.0, 1_900.0, 2_250.0, 2_600.0, 2_985.0)
        leg1Points.forEach { northM ->
            val dtMs = (((northM - prevNorth).coerceAtLeast(10.0) / 22.0) * 1000.0).toLong()
            clockMs += dtMs
            epochMs += dtMs
            prevNorth = northM

            val res = engine.onLocationFix(
                fix = fixEn(0.0, northM, accuracyMeters = 6f, speedMps = 22f, bearingDegrees = 0f, timestampMs = epochMs),
                toleranceKmh = 5,
                trafficProvider = trafficProvider,
                cameraMode = MapCameraMode.NAVIGATION_25D,
                isMuted = false,
                nowEpochMs = epochMs,
                clockMs = clockMs,
                previousState = state
            )
            state = res.state
        }
        assertTrue(
            "Unknown speed limit on Leg 1 must report isUnknown=true",
            state.overspeed.speedLimit.isUnknown
        )
        assertEquals(NavigationStatus.ARRIVED_DESTINATION, state.status)
    }

    @Test
    fun `voice scheduler fires early, medium, and final exactly once scaling highway vs city thresholds`() {
        val tracker = VoiceScheduleTracker()
        tracker.markDepartIfNeeded(0, "Jalan Ampang")

        // City speed (36 km/h = 10 m/s): early window 250..450m, medium 110..235m, final <= 65m
        assertNull(
            tracker.evaluateUpcoming(
                legIndex = 0, upcomingStepIndex = 1, stepLengthMeters = 800.0,
                distanceToManeuverMeters = 520.0, speedMps = 10f,
                maneuverType = ManeuverType.TURN, maneuverModifier = ManeuverModifier.RIGHT,
                roadName = "Jalan Tun Razak", exitShield = null, rotaryExitNumber = null
            )
        )

        val cityEarly = tracker.evaluateUpcoming(
            legIndex = 0, upcomingStepIndex = 1, stepLengthMeters = 800.0,
            distanceToManeuverMeters = 360.0, speedMps = 10f,
            maneuverType = ManeuverType.TURN, maneuverModifier = ManeuverModifier.RIGHT,
            roadName = "Jalan Tun Razak", exitShield = null, rotaryExitNumber = null
        )
        assertEquals(VoiceStage.EARLY, cityEarly?.stage)

        // Repeating distance inside EARLY does NOT re-fire
        assertNull(
            tracker.evaluateUpcoming(
                legIndex = 0, upcomingStepIndex = 1, stepLengthMeters = 800.0,
                distanceToManeuverMeters = 310.0, speedMps = 10f,
                maneuverType = ManeuverType.TURN, maneuverModifier = ManeuverModifier.RIGHT,
                roadName = "Jalan Tun Razak", exitShield = null, rotaryExitNumber = null
            )
        )

        val cityMed = tracker.evaluateUpcoming(
            legIndex = 0, upcomingStepIndex = 1, stepLengthMeters = 800.0,
            distanceToManeuverMeters = 180.0, speedMps = 10f,
            maneuverType = ManeuverType.TURN, maneuverModifier = ManeuverModifier.RIGHT,
            roadName = "Jalan Tun Razak", exitShield = null, rotaryExitNumber = null
        )
        assertEquals(VoiceStage.MEDIUM, cityMed?.stage)

        val cityFinal = tracker.evaluateUpcoming(
            legIndex = 0, upcomingStepIndex = 1, stepLengthMeters = 800.0,
            distanceToManeuverMeters = 45.0, speedMps = 10f,
            maneuverType = ManeuverType.TURN, maneuverModifier = ManeuverModifier.RIGHT,
            roadName = "Jalan Tun Razak", exitShield = null, rotaryExitNumber = null
        )
        assertEquals(VoiceStage.FINAL, cityFinal?.stage)

        // Highway speed (90 km/h = 25 m/s) on step 2: EARLY fires around 1,200 m (> city 450m)
        val hwyEarly = tracker.evaluateUpcoming(
            legIndex = 0, upcomingStepIndex = 2, stepLengthMeters = 3_000.0,
            distanceToManeuverMeters = 1_200.0, speedMps = 25f,
            maneuverType = ManeuverType.OFF_RAMP, maneuverModifier = ManeuverModifier.SLIGHT_LEFT,
            roadName = "Lebuhraya KLIA", exitShield = "608", rotaryExitNumber = null
        )
        assertEquals(VoiceStage.EARLY, hwyEarly?.stage)
        assertTrue(hwyEarly!!.toDefaultEnglishSpeech().contains("608"))

        // Roundabout English speech generation includes exit number cleanly
        val rbtCue = tracker.evaluateUpcoming(
            legIndex = 0, upcomingStepIndex = 3, stepLengthMeters = 1_000.0,
            distanceToManeuverMeters = 120.0, speedMps = 10f,
            maneuverType = ManeuverType.ROUNDABOUT, maneuverModifier = ManeuverModifier.RIGHT,
            roadName = "Bulatan Kampung Pandan", exitShield = null, rotaryExitNumber = 3
        )
        assertEquals(VoiceStage.MEDIUM, rbtCue?.stage)
        assertTrue(rbtCue!!.toDefaultEnglishSpeech().contains("exit 3"))
    }

    @Test
    fun `navigation engine triggers reroute only after full off-route hysteresis, recovers on new route, and extrapolates tunnels`() {
        val engine = NavigationEngine()
        val trafficProvider = ConstantTrafficProvider()
        val initialRoute = straightNorthRoute(lengthMeters = 2_000.0)

        var state = engine.startRoute(
            route = initialRoute,
            nowEpochMs = 2_000_000L,
            clockMs = 10_000L,
            cameraMode = MapCameraMode.NAVIGATION_25D,
            isMuted = false
        ).state

        // Drive on-route up to 300 m, then lose GPS for 5s -> tunnel dead reckoning advances puck smoothly
        var clockMs = 10_000L
        var epochMs = 2_000_000L
        listOf(50.0, 120.0, 200.0, 300.0).forEach { north ->
            clockMs += 4_000L
            epochMs += 4_000L
            state = engine.onLocationFix(
                fix = fixEn(0.0, north, accuracyMeters = 6f, speedMps = 20f, bearingDegrees = 0f, timestampMs = epochMs),
                toleranceKmh = 5,
                trafficProvider = trafficProvider,
                cameraMode = MapCameraMode.NAVIGATION_25D,
                isMuted = false,
                nowEpochMs = epochMs,
                clockMs = clockMs,
                previousState = state
            ).state
        }
        assertFalse(state.isGpsSignalWeak)
        val distBeforeTunnel = state.distanceAlongRouteMeters

        // Tick at +1s (below 2.5s loss threshold) -> still null
        assertNull(engine.onDeadReckonTick(5, trafficProvider, epochMs + 1_000L, clockMs + 1_000L, state))

        // Tick at +3.5s, +4.5s, +5.5s -> dead reckoning active & advancing forward
        for (sec in listOf(3_500L, 4_500L, 5_500L)) {
            val drRes = engine.onDeadReckonTick(5, trafficProvider, epochMs + sec, clockMs + sec, state)
            assertNotNull("Expected dead reckon output at +${sec}ms outage", drRes)
            state = drRes!!.state
            assertTrue(state.isGpsSignalWeak)
            assertTrue(state.puckState.isDeadReckoning)
            assertTrue(state.debugOverlayData.isDeadReckoning)
        }
        assertTrue(
            "Expected dead reckoning progression beyond $distBeforeTunnel m, got ${state.distanceAlongRouteMeters}",
            state.distanceAlongRouteMeters > distBeforeTunnel + 30.0
        )

        // Exit tunnel 65m East driving East (90 deg, off corridor) repeatedly:
        // Need 5+ consistent tunnel-exit/detour steps so Kalman accepts new road + 6s off-route triggers reroute
        clockMs += 6_000L
        epochMs += 6_000L
        var rerouteTriggered = false
        for (step in 1..12) {
            clockMs += 1_200L
            epochMs += 1_200L
            val detourEast = 70.0 + step * 18.0
            val res = engine.onLocationFix(
                fix = fixEn(detourEast, 420.0, accuracyMeters = 6f, speedMps = 15f, bearingDegrees = 90f, timestampMs = epochMs),
                toleranceKmh = 5,
                trafficProvider = trafficProvider,
                cameraMode = MapCameraMode.NAVIGATION_25D,
                isMuted = false,
                nowEpochMs = epochMs,
                clockMs = clockMs,
                previousState = state
            )
            state = res.state
            assertFalse("GPS signal weak indicator clears immediately on valid fix", state.isGpsSignalWeak)
            if (res.shouldRequestReroute) {
                assertTrue(res.state.isRerouting)
                assertTrue(res.state.debugOverlayData.offRouteConsecutiveCount >= 5)
                assertTrue(res.state.debugOverlayData.offRouteDurationSeconds >= 6.0)
                rerouteTriggered = true
                break
            }
        }
        assertTrue("Sustained detour off-corridor must trigger reroute", rerouteTriggered)

        // Applying rerouted route clears Rerouting indicator, resets debug overlay off-route state,
        // snaps onto the new route polyline, and refreshes speed limit immediately
        val rerouteOrigin = state.debugOverlayData.kalmanPosition!!
        val reroutedPts = (0..12).map { idx ->
            GeoMath.fromLocalEnMeters(rerouteOrigin, eastMeters = idx * 100.0, northMeters = 0.0)
        }
        val reroutedLeg = RouteLeg(
            summary = "Rerouted Road",
            distanceMeters = 1_200.0,
            durationSeconds = 60.0,
            steps = listOf(
                RouteStep(
                    instructionText = "Continue east on Rerouted Road",
                    roadName = "Rerouted Road",
                    maneuverType = ManeuverType.DEPART,
                    maneuverModifier = ManeuverModifier.STRAIGHT,
                    maneuverLocation = reroutedPts.first(),
                    bearingBefore = 90.0,
                    bearingAfter = 90.0,
                    distanceMeters = 1_200.0,
                    durationSeconds = 60.0,
                    geometryStartIndex = 0,
                    geometryEndIndex = 12
                )
            ),
            segmentMaxSpeeds = List(12) {
                SegmentSpeedLimit(speedKmh = 70, isExplicitNone = false, isUnknown = false)
            },
            segmentStartGeometryIndex = 0
        )
        val reroutedRoute = NavRoute(
            id = "reroute_east_test",
            geometry = reroutedPts,
            cumulativeDistancesMeters = (0..12).map { it * 100.0 },
            totalDistanceMeters = 1_200.0,
            baseDurationSeconds = 60.0,
            adjustedDurationSeconds = 60.0,
            trafficEstimated = true,
            legs = listOf(reroutedLeg),
            waypoints = listOf(
                Place("orig", "Origin", "", reroutedPts.first()),
                Place("dest", "Dest", "", reroutedPts.last())
            ),
            summaryLabel = "Rerouted Road"
        )
        val rerouted = engine.applyReroutedRoute(
            newRoute = reroutedRoute,
            nowEpochMs = epochMs,
            clockMs = clockMs,
            currentState = state,
            toleranceKmh = 5
        )
        assertFalse(rerouted.isRerouting)
        assertFalse(rerouted.isGpsSignalWeak)
        assertEquals(NavigationStatus.ACTIVE, rerouted.status)
        assertEquals(1_200.0, rerouted.remainingDistanceMeters, 1e-6)
        assertFalse(rerouted.debugOverlayData.isOffRoute)
        assertFalse(rerouted.debugOverlayData.isDeadReckoning)
        assertEquals(0, rerouted.debugOverlayData.offRouteConsecutiveCount)
        assertEquals(0.0, rerouted.debugOverlayData.offRouteDurationSeconds, 1e-6)
        assertNotNull(rerouted.debugOverlayData.snappedPosition)
        assertTrue(rerouted.debugOverlayData.crossTrackErrorMeters < 5.0)
        assertEquals(70, rerouted.overspeed.speedLimit.speedKmh)
    }

    @Test
    fun `navigation engine auto advances past intermediate stop when driver continues past stop`() {
        val engine = NavigationEngine()
        val trafficProvider = ConstantTrafficProvider()
        val route = twoLegNorthRoute(speedLimitKmh = 60)

        var state = engine.startRoute(route, nowEpochMs = 1_000_000L, clockMs = 1_000L).state
        var clockMs = 1_000L
        var epochMs = 1_000_000L
        var prevN = 0.0

        for (north in listOf(400.0, 800.0, 1_200.0, 1_490.0)) {
            val dt = (((north - prevN) / 20.0) * 1000.0).toLong()
            clockMs += dt
            epochMs += dt
            prevN = north
            state = engine.onLocationFix(
                fix = fixEn(0.0, north, accuracyMeters = 5f, speedMps = 20f, bearingDegrees = 0f, timestampMs = epochMs),
                toleranceKmh = 5,
                trafficProvider = trafficProvider,
                cameraMode = MapCameraMode.NAVIGATION_25D,
                isMuted = false,
                nowEpochMs = epochMs,
                clockMs = clockMs,
                previousState = state
            ).state
        }
        assertEquals(NavigationStatus.ARRIVED_STOP, state.status)
        assertEquals("KL Sentral", state.arrivedStopPlace?.name)

        // Driver continues past Stop 1 (> 65m past KL Sentral at 1500m) without tapping Continue
        for (north in listOf(1_530.0, 1_575.0)) {
            val dt = (((north - prevN) / 15.0) * 1000.0).toLong()
            clockMs += dt
            epochMs += dt
            prevN = north
            state = engine.onLocationFix(
                fix = fixEn(0.0, north, accuracyMeters = 5f, speedMps = 15f, bearingDegrees = 0f, timestampMs = epochMs),
                toleranceKmh = 5,
                trafficProvider = trafficProvider,
                cameraMode = MapCameraMode.NAVIGATION_25D,
                isMuted = false,
                nowEpochMs = epochMs,
                clockMs = clockMs,
                previousState = state
            ).state
        }
        assertEquals(NavigationStatus.ACTIVE, state.status)
        assertEquals(1, state.currentLegIndex)
        assertNull(state.arrivedStopPlace)
    }

    private fun straightNorthRoute(lengthMeters: Double): NavRoute {
        val segments = 20
        val stepLen = lengthMeters / segments
        val pts = (0..segments).map { i -> pointEn(0.0, i * stepLen) }
        val cum = (0..segments).map { i -> i * stepLen }
        val pStart = Place("start", "KLCC", "KL", pts.first())
        val pEnd = Place("end", "North End", "KL", pts.last())
        val step = RouteStep(
            instructionText = "Head north",
            roadName = "Jalan Ampang",
            maneuverType = ManeuverType.DEPART,
            maneuverModifier = ManeuverModifier.STRAIGHT,
            maneuverLocation = pts.first(),
            bearingBefore = 0.0,
            bearingAfter = 0.0,
            distanceMeters = lengthMeters,
            durationSeconds = lengthMeters / 15.0,
            geometryStartIndex = 0,
            geometryEndIndex = segments
        )
        return NavRoute(
            id = "north_test",
            geometry = pts,
            cumulativeDistancesMeters = cum,
            totalDistanceMeters = lengthMeters,
            baseDurationSeconds = lengthMeters / 15.0,
            adjustedDurationSeconds = lengthMeters / 15.0,
            trafficEstimated = true,
            legs = listOf(RouteLeg("Jalan Ampang", lengthMeters, lengthMeters / 15.0, listOf(step))),
            waypoints = listOf(pStart, pEnd),
            summaryLabel = "Jalan Ampang"
        )
    }

    private fun twoLegNorthRoute(speedLimitKmh: Int): NavRoute {
        // 30 segments x 100 m = 3,000 m total
        val segMeters = 100.0
        val pts = (0..30).map { i -> pointEn(0.0, i * segMeters) }
        val cum = (0..30).map { i -> i * segMeters }

        val pStart = Place("klcc", "KLCC", "KL", pts[0])
        val pStop = Place("kls", "KL Sentral", "Brickfields", pts[15])
        val pDest = Place("klia", "KLIA", "Sepang", pts[30])

        val lanesAtExit = IntersectionLanes(
            location = pts[10],
            geometryIndex = 10,
            lanes = listOf(
                LaneInfo(listOf(LaneIndication.STRAIGHT), valid = false),
                LaneInfo(listOf(LaneIndication.SLIGHT_RIGHT), valid = true)
            )
        )

        val leg0Steps = listOf(
            RouteStep(
                instructionText = "Head north on Jalan Tun Razak",
                roadName = "Jalan Tun Razak",
                maneuverType = ManeuverType.DEPART,
                maneuverModifier = ManeuverModifier.STRAIGHT,
                maneuverLocation = pts[0],
                bearingBefore = 0.0,
                bearingAfter = 0.0,
                distanceMeters = 1_000.0,
                durationSeconds = 50.0,
                geometryStartIndex = 0,
                geometryEndIndex = 10
            ),
            RouteStep(
                instructionText = "Keep right toward KL Sentral",
                roadName = "Lebuhraya Persekutuan",
                exits = "101A",
                maneuverType = ManeuverType.FORK,
                maneuverModifier = ManeuverModifier.SLIGHT_RIGHT,
                maneuverLocation = pts[10],
                bearingBefore = 0.0,
                bearingAfter = 0.0,
                distanceMeters = 500.0,
                durationSeconds = 25.0,
                geometryStartIndex = 10,
                geometryEndIndex = 15,
                intersections = listOf(lanesAtExit)
            ),
            RouteStep(
                instructionText = "Arrive at KL Sentral",
                roadName = "KL Sentral",
                maneuverType = ManeuverType.ARRIVE,
                maneuverModifier = ManeuverModifier.NONE,
                maneuverLocation = pts[15],
                bearingBefore = 0.0,
                bearingAfter = 0.0,
                distanceMeters = 0.0,
                durationSeconds = 0.0,
                geometryStartIndex = 15,
                geometryEndIndex = 15
            )
        )

        val leg1Steps = listOf(
            RouteStep(
                instructionText = "Continue south toward KLIA",
                roadName = "MEX Highway",
                maneuverType = ManeuverType.DEPART,
                maneuverModifier = ManeuverModifier.STRAIGHT,
                maneuverLocation = pts[15],
                bearingBefore = 0.0,
                bearingAfter = 0.0,
                distanceMeters = 1_500.0,
                durationSeconds = 60.0,
                geometryStartIndex = 15,
                geometryEndIndex = 30
            ),
            RouteStep(
                instructionText = "Arrive at KLIA",
                roadName = "KLIA",
                maneuverType = ManeuverType.ARRIVE,
                maneuverModifier = ManeuverModifier.NONE,
                maneuverLocation = pts[30],
                bearingBefore = 0.0,
                bearingAfter = 0.0,
                distanceMeters = 0.0,
                durationSeconds = 0.0,
                geometryStartIndex = 30,
                geometryEndIndex = 30
            )
        )

        val leg0 = RouteLeg(
            summary = "Jalan Tun Razak",
            distanceMeters = 1_500.0,
            durationSeconds = 75.0,
            steps = leg0Steps,
            segmentMaxSpeeds = List(15) {
                SegmentSpeedLimit(speedKmh = speedLimitKmh, isExplicitNone = false, isUnknown = false)
            },
            segmentStartGeometryIndex = 0
        )

        val leg1 = RouteLeg(
            summary = "MEX Highway",
            distanceMeters = 1_500.0,
            durationSeconds = 60.0,
            steps = leg1Steps,
            segmentMaxSpeeds = List(15) { SegmentSpeedLimit(isUnknown = true) },
            segmentStartGeometryIndex = 15
        )

        return NavRoute(
            id = "two_leg_test",
            geometry = pts,
            cumulativeDistancesMeters = cum,
            totalDistanceMeters = 3_000.0,
            baseDurationSeconds = 135.0,
            adjustedDurationSeconds = 135.0,
            trafficEstimated = true,
            legs = listOf(leg0, leg1),
            waypoints = listOf(pStart, pStop, pDest),
            summaryLabel = "Jalan Tun Razak → MEX"
        )
    }

    private class ConstantTrafficProvider : TrafficProvider {
        override suspend fun estimateRouteEta(route: NavRoute, departureTimeEpochMs: Long): TrafficEstimate {
            return TrafficEstimate(route.baseDurationSeconds, 1.0, true, "estimated")
        }

        override fun adaptRemainingEta(
            route: NavRoute,
            distanceAlongRouteMeters: Double,
            baseRemainingSeconds: Double,
            traveledObservedSeconds: Double,
            traveledExpectedSeconds: Double,
            departureTimeEpochMs: Long
        ): TrafficEstimate {
            return TrafficEstimate(baseRemainingSeconds, 1.0, true, "estimated")
        }
    }
}
