package com.mbk.halatuju.feature.navigation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.mbk.halatuju.core.model.LaneIndication
import com.mbk.halatuju.core.model.ManeuverModifier
import com.mbk.halatuju.core.model.ManeuverType
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Custom Canvas vector renderer for all turn-by-turn navigation maneuvers, including
 * left-hand-traffic clockwise roundabouts, diverging ramps/forks, merges, U-turns, and sharp turns.
 */
@Composable
fun ManeuverIcon(
    maneuverType: ManeuverType,
    maneuverModifier: ManeuverModifier,
    tint: Color = Color.White,
    secondaryTint: Color = tint.copy(alpha = 0.36f),
    modifier: Modifier = Modifier.size(36.dp)
) {
    Canvas(modifier = modifier) {
        val s = size.minDimension
        val sw = (s * 0.125f).coerceAtLeast(2.2f)
        when (maneuverType) {
            ManeuverType.ROUNDABOUT,
            ManeuverType.ROTARY,
            ManeuverType.ROUNDABOUT_TURN,
            ManeuverType.EXIT_ROUNDABOUT,
            ManeuverType.EXIT_ROTARY -> drawRoundaboutIcon(
                modifier = maneuverModifier,
                s = s,
                strokeWidth = sw,
                tint = tint,
                secondaryTint = secondaryTint
            )

            ManeuverType.OFF_RAMP,
            ManeuverType.FORK -> drawForkOrOffRamp(
                modifier = maneuverModifier,
                s = s,
                strokeWidth = sw,
                tint = tint,
                secondaryTint = secondaryTint
            )

            ManeuverType.ON_RAMP,
            ManeuverType.MERGE -> drawMergeOrOnRamp(
                modifier = maneuverModifier,
                s = s,
                strokeWidth = sw,
                tint = tint,
                secondaryTint = secondaryTint
            )

            ManeuverType.DEPART -> drawNavigationChevron(s = s, tint = tint)

            ManeuverType.ARRIVE -> drawArrivalFlagPin(
                s = s,
                strokeWidth = sw,
                tint = tint,
                secondaryTint = secondaryTint
            )

            else -> drawTurnArrow(
                modifier = maneuverModifier,
                s = s,
                strokeWidth = sw,
                tint = tint
            )
        }
    }
}

internal fun DrawScope.drawLaneIndicationBranch(
    indication: LaneIndication,
    s: Float,
    strokeWidth: Float,
    color: Color
) {
    when (indication) {
        LaneIndication.NONE -> Unit
        LaneIndication.STRAIGHT -> drawSingleStemArrow(
            start = Offset(s * 0.50f, s * 0.86f),
            elbow = null,
            tip = Offset(s * 0.50f, s * 0.14f),
            strokeWidth = strokeWidth,
            color = color
        )

        LaneIndication.SLIGHT_LEFT -> drawSingleStemArrow(
            start = Offset(s * 0.50f, s * 0.86f),
            elbow = Offset(s * 0.50f, s * 0.54f),
            tip = Offset(s * 0.22f, s * 0.20f),
            strokeWidth = strokeWidth,
            color = color
        )

        LaneIndication.LEFT -> drawSingleStemArrow(
            start = Offset(s * 0.50f, s * 0.86f),
            elbow = Offset(s * 0.50f, s * 0.44f),
            tip = Offset(s * 0.14f, s * 0.44f),
            strokeWidth = strokeWidth,
            color = color
        )

        LaneIndication.SHARP_LEFT -> drawSingleStemArrow(
            start = Offset(s * 0.50f, s * 0.86f),
            elbow = Offset(s * 0.50f, s * 0.40f),
            tip = Offset(s * 0.16f, s * 0.68f),
            strokeWidth = strokeWidth,
            color = color
        )

        LaneIndication.SLIGHT_RIGHT -> drawSingleStemArrow(
            start = Offset(s * 0.50f, s * 0.86f),
            elbow = Offset(s * 0.50f, s * 0.54f),
            tip = Offset(s * 0.78f, s * 0.20f),
            strokeWidth = strokeWidth,
            color = color
        )

        LaneIndication.RIGHT -> drawSingleStemArrow(
            start = Offset(s * 0.50f, s * 0.86f),
            elbow = Offset(s * 0.50f, s * 0.44f),
            tip = Offset(s * 0.86f, s * 0.44f),
            strokeWidth = strokeWidth,
            color = color
        )

        LaneIndication.SHARP_RIGHT -> drawSingleStemArrow(
            start = Offset(s * 0.50f, s * 0.86f),
            elbow = Offset(s * 0.50f, s * 0.40f),
            tip = Offset(s * 0.84f, s * 0.68f),
            strokeWidth = strokeWidth,
            color = color
        )

        LaneIndication.UTURN -> drawUTurnArrow(
            s = s,
            strokeWidth = strokeWidth,
            color = color,
            centerStem = true
        )

        LaneIndication.MERGE_LEFT -> drawCurvedArrow(
            start = Offset(s * 0.50f, s * 0.86f),
            control = Offset(s * 0.50f, s * 0.50f),
            tip = Offset(s * 0.28f, s * 0.18f),
            strokeWidth = strokeWidth,
            color = color
        )

        LaneIndication.MERGE_RIGHT -> drawCurvedArrow(
            start = Offset(s * 0.50f, s * 0.86f),
            control = Offset(s * 0.50f, s * 0.50f),
            tip = Offset(s * 0.72f, s * 0.18f),
            strokeWidth = strokeWidth,
            color = color
        )
    }
}

private fun DrawScope.drawTurnArrow(
    modifier: ManeuverModifier,
    s: Float,
    strokeWidth: Float,
    tint: Color
) {
    when (modifier) {
        ManeuverModifier.STRAIGHT, ManeuverModifier.NONE -> drawSingleStemArrow(
            start = Offset(s * 0.50f, s * 0.88f),
            elbow = null,
            tip = Offset(s * 0.50f, s * 0.12f),
            strokeWidth = strokeWidth,
            color = tint
        )

        ManeuverModifier.SLIGHT_LEFT -> drawSingleStemArrow(
            start = Offset(s * 0.58f, s * 0.88f),
            elbow = Offset(s * 0.58f, s * 0.54f),
            tip = Offset(s * 0.24f, s * 0.18f),
            strokeWidth = strokeWidth,
            color = tint
        )

        ManeuverModifier.LEFT -> drawSingleStemArrow(
            start = Offset(s * 0.66f, s * 0.88f),
            elbow = Offset(s * 0.66f, s * 0.38f),
            tip = Offset(s * 0.14f, s * 0.38f),
            strokeWidth = strokeWidth,
            color = tint
        )

        ManeuverModifier.SHARP_LEFT -> drawSingleStemArrow(
            start = Offset(s * 0.68f, s * 0.88f),
            elbow = Offset(s * 0.68f, s * 0.28f),
            tip = Offset(s * 0.18f, s * 0.66f),
            strokeWidth = strokeWidth,
            color = tint
        )

        ManeuverModifier.SLIGHT_RIGHT -> drawSingleStemArrow(
            start = Offset(s * 0.42f, s * 0.88f),
            elbow = Offset(s * 0.42f, s * 0.54f),
            tip = Offset(s * 0.76f, s * 0.18f),
            strokeWidth = strokeWidth,
            color = tint
        )

        ManeuverModifier.RIGHT -> drawSingleStemArrow(
            start = Offset(s * 0.34f, s * 0.88f),
            elbow = Offset(s * 0.34f, s * 0.38f),
            tip = Offset(s * 0.86f, s * 0.38f),
            strokeWidth = strokeWidth,
            color = tint
        )

        ManeuverModifier.SHARP_RIGHT -> drawSingleStemArrow(
            start = Offset(s * 0.32f, s * 0.88f),
            elbow = Offset(s * 0.32f, s * 0.28f),
            tip = Offset(s * 0.82f, s * 0.66f),
            strokeWidth = strokeWidth,
            color = tint
        )

        ManeuverModifier.UTURN -> drawUTurnArrow(
            s = s,
            strokeWidth = strokeWidth,
            color = tint,
            centerStem = false
        )
    }
}

private fun DrawScope.drawForkOrOffRamp(
    modifier: ManeuverModifier,
    s: Float,
    strokeWidth: Float,
    tint: Color,
    secondaryTint: Color
) {
    val goLeft = modifier == ManeuverModifier.LEFT ||
        modifier == ManeuverModifier.SLIGHT_LEFT ||
        modifier == ManeuverModifier.SHARP_LEFT

    val trunkX = s * 0.50f
    val branchY = s * 0.56f
    val altTip = if (goLeft) Offset(s * 0.72f, s * 0.18f) else Offset(s * 0.28f, s * 0.18f)
    val mainTip = when (modifier) {
        ManeuverModifier.SHARP_LEFT -> Offset(s * 0.16f, s * 0.40f)
        ManeuverModifier.LEFT -> Offset(s * 0.18f, s * 0.28f)
        ManeuverModifier.SLIGHT_LEFT -> Offset(s * 0.22f, s * 0.18f)
        ManeuverModifier.SHARP_RIGHT -> Offset(s * 0.84f, s * 0.40f)
        ManeuverModifier.RIGHT -> Offset(s * 0.82f, s * 0.28f)
        ManeuverModifier.SLIGHT_RIGHT -> Offset(s * 0.78f, s * 0.18f)
        else -> Offset(s * 0.50f, s * 0.14f)
    }

    // Dimmed through-road continuation branch
    drawSingleStemArrow(
        start = Offset(trunkX, s * 0.86f),
        elbow = Offset(trunkX, branchY),
        tip = altTip,
        strokeWidth = strokeWidth * 0.90f,
        color = secondaryTint
    )

    // Bright highlighted ramp / fork arrow
    drawSingleStemArrow(
        start = Offset(trunkX, s * 0.88f),
        elbow = Offset(trunkX, branchY),
        tip = mainTip,
        strokeWidth = strokeWidth,
        color = tint
    )
}

private fun DrawScope.drawMergeOrOnRamp(
    modifier: ManeuverModifier,
    s: Float,
    strokeWidth: Float,
    tint: Color,
    secondaryTint: Color
) {
    val fromLeft = modifier == ManeuverModifier.SLIGHT_RIGHT ||
        modifier == ManeuverModifier.RIGHT ||
        modifier == ManeuverModifier.SHARP_RIGHT

    val activeFeederX = if (fromLeft) s * 0.22f else s * 0.78f
    val otherFeederX = if (fromLeft) s * 0.78f else s * 0.22f
    val joinPt = Offset(s * 0.50f, s * 0.48f)

    // Dimmed continuing lane stream joining from the opposite side
    drawLine(
        color = secondaryTint,
        start = Offset(otherFeederX, s * 0.86f),
        end = joinPt,
        strokeWidth = strokeWidth * 0.88f,
        cap = StrokeCap.Round
    )

    // Active feeder leg rising smoothly into the merged vertical exit arrow
    drawSingleStemArrow(
        start = Offset(activeFeederX, s * 0.86f),
        elbow = joinPt,
        tip = Offset(s * 0.50f, s * 0.12f),
        strokeWidth = strokeWidth,
        color = tint
    )
}

private fun DrawScope.drawRoundaboutIcon(
    modifier: ManeuverModifier,
    s: Float,
    strokeWidth: Float,
    tint: Color,
    secondaryTint: Color
) {
    val center = Offset(s * 0.50f, s * 0.52f)
    val radius = s * 0.23f
    val ringRect = Rect(
        left = center.x - radius,
        top = center.y - radius,
        right = center.x + radius,
        bottom = center.y + radius
    )

    // Subtle full roundabout ring
    drawOval(
        color = secondaryTint,
        topLeft = Offset(ringRect.left, ringRect.top),
        size = ringRect.size,
        style = Stroke(width = strokeWidth * 0.85f)
    )

    // Entry stem up from 6 o'clock
    drawLine(
        color = tint,
        start = Offset(center.x, s * 0.92f),
        end = Offset(center.x, center.y + radius),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )

    // Left-hand-traffic clockwise sweep (0 deg = 3 o'clock, 90 deg = 6 o'clock)
    val sweepDegrees = when (modifier) {
        ManeuverModifier.SHARP_LEFT -> 45f
        ManeuverModifier.LEFT -> 90f
        ManeuverModifier.SLIGHT_LEFT -> 135f
        ManeuverModifier.STRAIGHT, ManeuverModifier.NONE -> 180f
        ManeuverModifier.SLIGHT_RIGHT -> 225f
        ManeuverModifier.RIGHT -> 270f
        ManeuverModifier.SHARP_RIGHT -> 315f
        ManeuverModifier.UTURN -> 335f
    }

    drawArc(
        color = tint,
        startAngle = 90f,
        sweepAngle = sweepDegrees,
        useCenter = false,
        topLeft = Offset(ringRect.left, ringRect.top),
        size = ringRect.size,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
    )

    val exitAngleRad = ((90f + sweepDegrees) * PI / 180.0)
    val cosA = cos(exitAngleRad).toFloat()
    val sinA = sin(exitAngleRad).toFloat()
    val exitStart = Offset(center.x + cosA * radius, center.y + sinA * radius)
    val exitTip = Offset(center.x + cosA * (radius + s * 0.25f), center.y + sinA * (radius + s * 0.25f))

    drawLineSegmentToTip(
        start = exitStart,
        tip = exitTip,
        strokeWidth = strokeWidth,
        color = tint
    )
}

private fun DrawScope.drawUTurnArrow(
    s: Float,
    strokeWidth: Float,
    color: Color,
    centerStem: Boolean
) {
    val enterX = if (centerStem) s * 0.50f else s * 0.64f
    val returnX = if (centerStem) s * 0.18f else s * 0.24f
    val arcCenterX = (enterX + returnX) / 2f
    val arcRadius = (enterX - returnX) / 2f
    val arcCenterY = s * 0.36f
    val tipY = s * 0.72f

    // Upward entry leg
    drawLine(
        color = color,
        start = Offset(enterX, s * 0.86f),
        end = Offset(enterX, arcCenterY),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )

    // Semi-circular U-turn arch across the top (from 0 deg to 180 deg counter-clockwise)
    drawArc(
        color = color,
        startAngle = 0f,
        sweepAngle = -180f,
        useCenter = false,
        topLeft = Offset(arcCenterX - arcRadius, arcCenterY - arcRadius),
        size = androidx.compose.ui.geometry.Size(arcRadius * 2f, arcRadius * 2f),
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
    )

    // Return stem pointing down cleanly
    drawLineSegmentToTip(
        start = Offset(returnX, arcCenterY),
        tip = Offset(returnX, tipY),
        strokeWidth = strokeWidth,
        color = color
    )
}

private fun DrawScope.drawNavigationChevron(s: Float, tint: Color) {
    val p = Path().apply {
        moveTo(s * 0.50f, s * 0.14f)
        lineTo(s * 0.80f, s * 0.84f)
        lineTo(s * 0.50f, s * 0.68f)
        lineTo(s * 0.20f, s * 0.84f)
        close()
    }
    drawPath(p, color = tint)
}

private fun DrawScope.drawArrivalFlagPin(
    s: Float,
    strokeWidth: Float,
    tint: Color,
    secondaryTint: Color
) {
    drawCircle(
        color = secondaryTint,
        radius = s * 0.34f,
        center = Offset(s * 0.50f, s * 0.50f),
        style = Stroke(width = strokeWidth * 0.85f)
    )
    drawCircle(
        color = tint,
        radius = s * 0.18f,
        center = Offset(s * 0.50f, s * 0.50f)
    )
}

private fun DrawScope.drawSingleStemArrow(
    start: Offset,
    elbow: Offset?,
    tip: Offset,
    strokeWidth: Float,
    color: Color
) {
    if (elbow == null) {
        drawLineSegmentToTip(start = start, tip = tip, strokeWidth = strokeWidth, color = color)
        return
    }

    val dirX = tip.x - elbow.x
    val dirY = tip.y - elbow.y
    val len = hypot(dirX.toDouble(), dirY.toDouble()).toFloat().coerceAtLeast(1f)
    val ux = dirX / len
    val uy = dirY / len
    val headLen = (strokeWidth * 2.25f).coerceAtMost(len * 0.65f)
    val shaftEnd = Offset(tip.x - ux * headLen * 0.55f, tip.y - uy * headLen * 0.55f)

    val path = Path().apply {
        moveTo(start.x, start.y)
        lineTo(elbow.x, elbow.y)
        lineTo(shaftEnd.x, shaftEnd.y)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
    drawArrowHead(tip = tip, angleRad = atan2(uy.toDouble(), ux.toDouble()), headLen = headLen, color = color)
}

private fun DrawScope.drawCurvedArrow(
    start: Offset,
    control: Offset,
    tip: Offset,
    strokeWidth: Float,
    color: Color
) {
    val dirX = tip.x - control.x
    val dirY = tip.y - control.y
    val len = hypot(dirX.toDouble(), dirY.toDouble()).toFloat().coerceAtLeast(1f)
    val ux = dirX / len
    val uy = dirY / len
    val headLen = (strokeWidth * 2.25f).coerceAtMost(len * 0.60f)
    val shaftEnd = Offset(tip.x - ux * headLen * 0.55f, tip.y - uy * headLen * 0.55f)

    val path = Path().apply {
        moveTo(start.x, start.y)
        quadraticTo(control.x, control.y, shaftEnd.x, shaftEnd.y)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
    drawArrowHead(tip = tip, angleRad = atan2(uy.toDouble(), ux.toDouble()), headLen = headLen, color = color)
}

private fun DrawScope.drawLineSegmentToTip(
    start: Offset,
    tip: Offset,
    strokeWidth: Float,
    color: Color
) {
    val dx = tip.x - start.x
    val dy = tip.y - start.y
    val len = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
    val ux = dx / len
    val uy = dy / len
    val headLen = (strokeWidth * 2.25f).coerceAtMost(len * 0.65f)
    val shaftEnd = Offset(tip.x - ux * headLen * 0.55f, tip.y - uy * headLen * 0.55f)

    drawLine(
        color = color,
        start = start,
        end = shaftEnd,
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
    drawArrowHead(tip = tip, angleRad = atan2(uy.toDouble(), ux.toDouble()), headLen = headLen, color = color)
}

private fun DrawScope.drawArrowHead(
    tip: Offset,
    angleRad: Double,
    headLen: Float,
    color: Color
) {
    val ux = cos(angleRad).toFloat()
    val uy = sin(angleRad).toFloat()
    val px = -uy
    val py = ux
    val halfWidth = headLen * 0.78f

    val baseCenter = Offset(tip.x - ux * headLen, tip.y - uy * headLen)
    val leftWing = Offset(baseCenter.x + px * halfWidth, baseCenter.y + py * halfWidth)
    val rightWing = Offset(baseCenter.x - px * halfWidth, baseCenter.y - py * halfWidth)
    val notch = Offset(tip.x - ux * (headLen * 0.72f), tip.y - uy * (headLen * 0.72f))

    val headPath = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(leftWing.x, leftWing.y)
        lineTo(notch.x, notch.y)
        lineTo(rightWing.x, rightWing.y)
        close()
    }
    drawPath(headPath, color = color)
}
