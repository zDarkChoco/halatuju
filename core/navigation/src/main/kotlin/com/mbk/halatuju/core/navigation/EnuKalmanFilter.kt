package com.mbk.halatuju.core.navigation

import com.mbk.halatuju.core.model.FilteredLocation
import com.mbk.halatuju.core.model.GeoMath
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.LocationFix
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Constant-velocity 4-state Kalman filter `[east, north, ve, vn]` in a local tangent ENU frame.
 *
 * Uses each fix's horizontal `accuracyMeters` as measurement covariance $R$ and rejects
 * physically impossible jumps or high-Mahalanobis statistical outliers while preserving
 * re-acquisition if the receiver genuinely relocated (e.g., tunnel emergence).
 */
class EnuKalmanFilter(
    private val processAccelNoiseMps2: Double = DEFAULT_PROCESS_ACCEL_MPS2,
    private val maxPlausibleSpeedMps: Double = DEFAULT_MAX_PLAUSIBLE_SPEED_MPS,
    private val mahalanobisSqThreshold: Double = DEFAULT_MAHALANOBIS_SQ_THRESHOLD
) {
    private var anchor: LatLng? = null
    private var initialized = false

    // State vector: [east, north, ve, vn]
    private var e = 0.0
    private var n = 0.0
    private var ve = 0.0
    private var vn = 0.0

    // 4x4 symmetric covariance matrix stored flattened (row-major)
    private val p = DoubleArray(16)

    private var lastFixTimeMs = 0L
    private var consecutiveRejections = 0
    private var totalRejectedOutliers = 0

    fun reset() {
        anchor = null
        initialized = false
        e = 0.0
        n = 0.0
        ve = 0.0
        vn = 0.0
        p.fill(0.0)
        lastFixTimeMs = 0L
        consecutiveRejections = 0
        totalRejectedOutliers = 0
    }

    val rejectedOutlierCount: Int
        get() = totalRejectedOutliers

    fun update(fix: LocationFix, smoothedBearingFallbackDeg: Double? = null): FilteredLocation {
        val accMeters = fix.accuracyMeters.let {
            if (!it.isFinite() || it <= 0f) 15f else it.coerceIn(3f, 200f)
        }

        if (!initialized) {
            initializeAt(fix, accMeters)
            val initBearing = fix.bearingDegrees ?: smoothedBearingFallbackDeg?.toFloat() ?: 0f
            val initSpeed = (fix.speedMps ?: 0f).coerceIn(0f, 65f)
            return FilteredLocation(
                rawFix = fix,
                smoothedPosition = fix.position,
                smoothedSpeedMps = initSpeed,
                smoothedBearingDegrees = GeoMath.normalizeDegrees(initBearing.toDouble()).toFloat(),
                hasReliableBearing = fix.bearingDegrees != null && initSpeed >= 2.0f,
                rejectedAsOutlier = false
            )
        }

        val currentAnchor = checkNotNull(anchor)
        // Re-anchor cleanly if cumulative displacement exceeds 50 km so flat-Earth ENU approximation stays sub-meter
        if (hypot(e, n) > REANCHOR_THRESHOLD_METERS) {
            val worldNow = GeoMath.fromLocalEnMeters(currentAnchor, e, n)
            anchor = worldNow
            e = 0.0
            n = 0.0
        }

        val refAnchor = checkNotNull(anchor)
        val dtSec = ((fix.timestampMs - lastFixTimeMs).coerceAtLeast(0L) / 1000.0)
            .coerceIn(MIN_DT_SEC, MAX_DT_SEC)

        // If fix accuracy is very coarse (> 150m) while we have an active track, treat as noisy outlier
        if (fix.accuracyMeters.isFinite() && fix.accuracyMeters > COARSE_OUTLIER_ACCURACY_M &&
            consecutiveRejections < MAX_CONSECUTIVE_OUTLIERS
        ) {
            return handleOutlier(fix, smoothedBearingFallbackDeg)
        }

        // 1. Predict step (constant-velocity state transition)
        val predE = e + ve * dtSec
        val predN = n + vn * dtSec
        val predVe = ve
        val predVn = vn

        val predP = predictCovariance(p, dtSec, processAccelNoiseMps2 * processAccelNoiseMps2)

        // 2. Innovation in local ENU meters
        val (measE, measN) = GeoMath.toLocalEnMeters(refAnchor, fix.position)
        val innovE = measE - predE
        val innovN = measN - predN
        val distToPred = hypot(innovE, innovN)

        val rVar = accMeters.toDouble() * accMeters.toDouble()
        val sE = predP[0] + rVar
        val sN = predP[5] + rVar
        val mahalanobisSq = (innovE * innovE) / sE.coerceAtLeast(1.0) +
            (innovN * innovN) / sN.coerceAtLeast(1.0)

        val wallDt = ((fix.timestampMs - lastFixTimeMs).coerceAtLeast(100L)) / 1000.0
        val physicalJumpLimit = maxOf(MIN_JUMP_GATE_METERS, maxPlausibleSpeedMps * wallDt + accMeters * 2.0)

        val isImpossibleJump = distToPred > physicalJumpLimit
        val isStatisticalOutlier = distToPred > 35.0 && mahalanobisSq > mahalanobisSqThreshold

        if ((isImpossibleJump || isStatisticalOutlier) &&
            consecutiveRejections < MAX_CONSECUTIVE_OUTLIERS
        ) {
            return handleOutlier(fix, smoothedBearingFallbackDeg)
        }

        // If we rejected several fixes in a row and GPS has settled somewhere new (e.g., tunnel exit), re-initialize
        if (consecutiveRejections >= MAX_CONSECUTIVE_OUTLIERS && isImpossibleJump) {
            initializeAt(fix, accMeters)
            val brg = fix.bearingDegrees ?: smoothedBearingFallbackDeg?.toFloat() ?: 0f
            val spd = (fix.speedMps ?: 0f).coerceIn(0f, 65f)
            return FilteredLocation(
                rawFix = fix,
                smoothedPosition = fix.position,
                smoothedSpeedMps = spd,
                smoothedBearingDegrees = GeoMath.normalizeDegrees(brg.toDouble()).toFloat(),
                hasReliableBearing = fix.bearingDegrees != null && spd >= 2.0f,
                rejectedAsOutlier = false
            )
        }

        // 3. Measurement update
        consecutiveRejections = 0
        e = predE
        n = predN
        ve = predVe
        vn = predVn

        // Decoupled 2D update on (E, Ve) and (N, Vn) blocks since Q and R are isotropic
        updateAxis1D(predP, axis = 0, meas = measE, rVar = rVar)
        updateAxis1D(predP, axis = 1, meas = measN, rVar = rVar)
        predP.copyInto(p)
        lastFixTimeMs = fix.timestampMs

        val posWorld = GeoMath.fromLocalEnMeters(refAnchor, e, n)
        val filterSpeed = hypot(ve, vn)
        val rawSpeedMps = fix.speedMps
        val blendedSpeed = if (rawSpeedMps != null && rawSpeedMps.isFinite() && rawSpeedMps >= 0f) {
            (0.65 * rawSpeedMps.toDouble() + 0.35 * filterSpeed).toFloat()
        } else {
            filterSpeed.toFloat()
        }.coerceIn(0f, 70f)

        val fallbackVelBearing = if (filterSpeed >= 2.0) {
            GeoMath.normalizeDegrees(Math.toDegrees(atan2(ve, vn))).toFloat()
        } else {
            smoothedBearingFallbackDeg?.toFloat() ?: 0f
        }
        val outBearing = fix.bearingDegrees?.let { GeoMath.normalizeDegrees(it.toDouble()).toFloat() }
            ?: fallbackVelBearing

        return FilteredLocation(
            rawFix = fix,
            smoothedPosition = posWorld,
            smoothedSpeedMps = blendedSpeed,
            smoothedBearingDegrees = outBearing,
            hasReliableBearing = blendedSpeed >= 2.0f && (fix.bearingDegrees != null || filterSpeed >= 2.0),
            rejectedAsOutlier = false
        )
    }

    private fun handleOutlier(
        fix: LocationFix,
        smoothedBearingFallbackDeg: Double?
    ): FilteredLocation {
        consecutiveRejections += 1
        totalRejectedOutliers += 1
        val refAnchor = checkNotNull(anchor)
        val currentWorld = GeoMath.fromLocalEnMeters(refAnchor, e, n)
        val filterSpeed = hypot(ve, vn).toFloat().coerceIn(0f, 70f)
        val fallbackBrg = if (filterSpeed >= 2.0f) {
            GeoMath.normalizeDegrees(Math.toDegrees(atan2(ve, vn))).toFloat()
        } else {
            smoothedBearingFallbackDeg?.toFloat() ?: 0f
        }
        return FilteredLocation(
            rawFix = fix,
            smoothedPosition = currentWorld,
            smoothedSpeedMps = filterSpeed,
            smoothedBearingDegrees = fallbackBrg,
            hasReliableBearing = filterSpeed >= 2.0f,
            rejectedAsOutlier = true
        )
    }

    private fun initializeAt(fix: LocationFix, accMeters: Float) {
        anchor = fix.position
        initialized = true
        e = 0.0
        n = 0.0
        val spd = (fix.speedMps ?: 0f).coerceIn(0f, 55f).toDouble()
        val brgRad = Math.toRadians((fix.bearingDegrees ?: 0f).toDouble())
        if (spd >= 1.5 && fix.bearingDegrees != null) {
            ve = spd * sin(brgRad)
            vn = spd * cos(brgRad)
        } else {
            ve = 0.0
            vn = 0.0
        }

        val posVar = (accMeters * accMeters).toDouble()
        val velVar = 25.0
        p.fill(0.0)
        p[0] = posVar
        p[5] = posVar
        p[10] = velVar
        p[15] = velVar
        lastFixTimeMs = fix.timestampMs
        consecutiveRejections = 0
    }

    private fun predictCovariance(prev: DoubleArray, dt: Double, qSigmaSq: Double): DoubleArray {
        val next = DoubleArray(16)
        val dt2 = dt * dt
        val dt3 = dt2 * dt
        val dt4 = dt3 * dt
        val qpp = qSigmaSq * (dt4 * 0.25)
        val qpv = qSigmaSq * (dt3 * 0.5)
        val qvv = qSigmaSq * dt2

        // East block indices (0, 2)
        val p00 = prev[0]
        val p02 = prev[2]
        val p22 = prev[10]
        next[0] = p00 + 2.0 * dt * p02 + dt2 * p22 + qpp
        next[2] = p02 + dt * p22 + qpv
        next[8] = next[2]
        next[10] = p22 + qvv

        // North block indices (1, 3) -> rows/cols 1,3 => indices 5, 7, 13, 15
        val p11 = prev[5]
        val p13 = prev[7]
        val p33 = prev[15]
        next[5] = p11 + 2.0 * dt * p13 + dt2 * p33 + qpp
        next[7] = p13 + dt * p33 + qpv
        next[13] = next[7]
        next[15] = p33 + qvv

        return next
    }

    private fun updateAxis1D(predP: DoubleArray, axis: Int, meas: Double, rVar: Double) {
        val iPos = if (axis == 0) 0 else 5
        val iCross1 = if (axis == 0) 2 else 7
        val iCross2 = if (axis == 0) 8 else 13
        val iVel = if (axis == 0) 10 else 15

        val pPos = predP[iPos]
        val pCross = predP[iCross1]
        val pVel = predP[iVel]
        val s = (pPos + rVar).coerceAtLeast(1e-3)
        val kPos = pPos / s
        val kVel = pCross / s

        if (axis == 0) {
            val y = meas - e
            e += kPos * y
            ve += kVel * y
        } else {
            val y = meas - n
            n += kPos * y
            vn += kVel * y
        }

        predP[iPos] = (1.0 - kPos) * pPos
        predP[iCross1] = (1.0 - kPos) * pCross
        predP[iCross2] = predP[iCross1]
        predP[iVel] = (pVel - kVel * pCross).coerceAtLeast(0.01)
    }

    companion object {
        const val DEFAULT_PROCESS_ACCEL_MPS2 = 2.2
        const val DEFAULT_MAX_PLAUSIBLE_SPEED_MPS = 58.0 // ~209 km/h
        const val DEFAULT_MAHALANOBIS_SQ_THRESHOLD = 36.0
        private const val MIN_JUMP_GATE_METERS = 42.0
        private const val COARSE_OUTLIER_ACCURACY_M = 180f
        private const val MAX_CONSECUTIVE_OUTLIERS = 4
        private const val REANCHOR_THRESHOLD_METERS = 50_000.0
        private const val MIN_DT_SEC = 0.05
        private const val MAX_DT_SEC = 10.0
    }
}
