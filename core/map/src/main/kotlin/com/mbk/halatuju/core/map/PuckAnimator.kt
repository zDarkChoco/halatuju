package com.mbk.halatuju.core.map

import com.mbk.halatuju.core.model.GeoMath
import com.mbk.halatuju.core.model.PuckRenderState

/**
 * Smoothly interpolates location puck coordinates, circular bearing (handling 359° -> 0° wrap),
 * and accuracy radius between incoming fixes so the puck glides at display frame rate.
 */
class PuckAnimator(
    private val defaultTransitionMs: Long = 320L
) {
    private var startState: PuckRenderState? = null
    private var targetState: PuckRenderState? = null
    private var transitionStartMs: Long = 0L
    private var currentState: PuckRenderState? = null

    fun setTarget(target: PuckRenderState, nowMs: Long) {
        val current = currentAt(nowMs)
        if (current == null) {
            startState = target
            targetState = target
            currentState = target
            transitionStartMs = nowMs
            return
        }

        val jumpMeters = GeoMath.haversineMeters(current.position, target.position)
        // Only treat massive non-linear camera shifts (> 2500 m, e.g. jump to a GPX file start) as instant
        if (jumpMeters > 2_500.0) {
            startState = target
            targetState = target
            currentState = target
            transitionStartMs = nowMs
            return
        }

        startState = current
        targetState = target
        transitionStartMs = nowMs
    }

    fun currentAt(nowMs: Long): PuckRenderState? {
        val from = startState ?: return null
        val to = targetState ?: return from
        if (from == to) {
            currentState = to
            return to
        }

        val rawT = ((nowMs - transitionStartMs).toDouble() / defaultTransitionMs.toDouble())
            .coerceIn(0.0, 1.0)
        // Smoothstep easing prevents abrupt acceleration starts/stops between 1 Hz GPS fixes
        val easedT = rawT * rawT * (3.0 - 2.0 * rawT)

        val pos = GeoMath.interpolateLatLng(from.position, to.position, easedT)
        val bearingDelta = GeoMath.signedAngleDeltaDegrees(
            from.bearingDegrees.toDouble(),
            to.bearingDegrees.toDouble()
        )
        val bearing = GeoMath.normalizeDegrees(from.bearingDegrees.toDouble() + bearingDelta * easedT).toFloat()
        val acc = (from.accuracyMeters + (to.accuracyMeters - from.accuracyMeters) * easedT).toFloat()

        return PuckRenderState(
            position = pos,
            bearingDegrees = bearing,
            accuracyMeters = acc,
            isDeadReckoning = to.isDeadReckoning
        ).also { currentState = it }
    }

    fun isAnimating(nowMs: Long): Boolean {
        val from = startState ?: return false
        val to = targetState ?: return false
        return from != to && (nowMs - transitionStartMs) < defaultTransitionMs
    }
}
