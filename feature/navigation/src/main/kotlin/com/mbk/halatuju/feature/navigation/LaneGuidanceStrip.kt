package com.mbk.halatuju.feature.navigation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mbk.halatuju.core.map.theme.HalaTujuPalette
import com.mbk.halatuju.core.model.LaneIndication
import com.mbk.halatuju.core.model.LaneInfo
import com.mbk.halatuju.core.model.ManeuverModifier

/**
 * Validates and cleans raw lane guidance data from Valhalla / OSM:
 * - Strips empty or `NONE`-only lane placeholders.
 * - Hides the entire strip whenever no valid lane exists ("never show wrong or empty lane boxes").
 */
internal fun sanitizeLanesForDisplay(lanes: List<LaneInfo>): List<LaneInfo> {
    if (lanes.isEmpty()) return emptyList()
    val cleaned = lanes.mapNotNull { lane ->
        val visibleIndications = lane.indications
            .filter { it != LaneIndication.NONE }
            .distinct()
        if (visibleIndications.isEmpty()) null else lane.copy(indications = visibleIndications)
    }
    if (cleaned.isEmpty() || cleaned.none { it.valid }) return emptyList()
    return cleaned
}

internal fun modifierMatchesIndication(
    modifier: ManeuverModifier,
    indication: LaneIndication
): Boolean = when (modifier) {
    ManeuverModifier.STRAIGHT, ManeuverModifier.NONE -> indication == LaneIndication.STRAIGHT
    ManeuverModifier.SLIGHT_LEFT ->
        indication == LaneIndication.SLIGHT_LEFT || indication == LaneIndication.MERGE_LEFT
    ManeuverModifier.LEFT -> indication == LaneIndication.LEFT
    ManeuverModifier.SHARP_LEFT -> indication == LaneIndication.SHARP_LEFT
    ManeuverModifier.SLIGHT_RIGHT ->
        indication == LaneIndication.SLIGHT_RIGHT || indication == LaneIndication.MERGE_RIGHT
    ManeuverModifier.RIGHT -> indication == LaneIndication.RIGHT
    ManeuverModifier.SHARP_RIGHT -> indication == LaneIndication.SHARP_RIGHT
    ManeuverModifier.UTURN -> indication == LaneIndication.UTURN
}

/**
 * Dynamic gantry-style lane guidance bar attached neatly beneath the maneuver banner.
 * Renders custom vector arrows for every indication and combination while hiding gracefully when
 * OSM provides no lane tags.
 */
@Composable
fun LaneGuidanceStrip(
    lanes: List<LaneInfo>,
    upcomingModifier: ManeuverModifier,
    modifier: Modifier = Modifier,
    attachedBelowBanner: Boolean = true,
    hasThenChipBelow: Boolean = false
) {
    val cleanedLanes = sanitizeLanesForDisplay(lanes)
    if (cleanedLanes.isEmpty()) return

    val validCount = cleanedLanes.count { it.valid }
    val shape = when {
        attachedBelowBanner && hasThenChipBelow -> RoundedCornerShape(4.dp)
        attachedBelowBanner -> RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp, topStart = 4.dp, topEnd = 4.dp)
        else -> RoundedCornerShape(16.dp)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("nav_lane_guidance_strip")
            .semantics {
                contentDescription = "$validCount of ${cleanedLanes.size} lanes recommended"
            },
        shape = shape,
        color = Color(0xFF073A38),
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            cleanedLanes.forEachIndexed { index, lane ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(24.dp)
                            .background(Color.White.copy(alpha = 0.22f))
                    )
                }
                LaneSlotGlyph(
                    lane = lane,
                    upcomingModifier = upcomingModifier,
                    modifier = Modifier.testTag("nav_lane_slot_$index")
                )
            }
        }
    }
}

@Composable
private fun LaneSlotGlyph(
    lane: LaneInfo,
    upcomingModifier: ManeuverModifier,
    modifier: Modifier = Modifier
) {
    val activeBg = if (lane.valid) Color.White.copy(alpha = 0.16f) else Color.Transparent
    val matchedBranchExists = lane.valid && lane.indications.any { modifierMatchesIndication(upcomingModifier, it) }

    Box(
        modifier = modifier
            .background(activeBg, RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(30.dp)) {
            val s = size.minDimension
            val sw = (s * 0.125f).coerceAtLeast(2.2f)

            // Draw non-primary indications first so the highlighted branch renders cleanly on top
            val secondaryIndications = mutableListOf<LaneIndication>()
            val primaryIndications = mutableListOf<LaneIndication>()

            lane.indications.forEach { ind ->
                val isPrimaryBranch = lane.valid && (!matchedBranchExists || modifierMatchesIndication(upcomingModifier, ind))
                if (isPrimaryBranch) {
                    primaryIndications.add(ind)
                } else {
                    secondaryIndications.add(ind)
                }
            }

            secondaryIndications.forEach { ind ->
                val secondaryColor = if (lane.valid) {
                    Color.White.copy(alpha = 0.48f)
                } else {
                    Color.White.copy(alpha = 0.30f)
                }
                drawLaneIndicationBranch(
                    indication = ind,
                    s = s,
                    strokeWidth = sw * 0.90f,
                    color = secondaryColor
                )
            }

            primaryIndications.forEach { ind ->
                drawLaneIndicationBranch(
                    indication = ind,
                    s = s,
                    strokeWidth = sw * 1.05f,
                    color = if (matchedBranchExists) HalaTujuPalette.CrispMint else Color.White
                )
            }
        }
    }
}
