package com.mbk.halatuju.feature.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mbk.halatuju.core.map.HalaTujuMapSurface
import com.mbk.halatuju.core.map.TileSource
import com.mbk.halatuju.core.map.theme.HalaTujuPalette
import com.mbk.halatuju.core.model.ManeuverType
import com.mbk.halatuju.core.model.MapCameraMode
import com.mbk.halatuju.core.model.UnitSystem
import com.mbk.halatuju.core.navigation.GuidanceBannerState
import com.mbk.halatuju.core.navigation.NavigationSessionState
import com.mbk.halatuju.core.navigation.NavigationStatus
import com.mbk.halatuju.core.navigation.OverspeedState
import java.time.ZoneId

@Composable
fun ActiveNavigationScreen(
    uiState: ActiveNavigationUiState,
    tileSource: TileSource,
    isDarkTheme: Boolean,
    onUserPanMap: () -> Unit,
    onToggleOverview: () -> Unit,
    onRecenterClick: () -> Unit,
    onToggleMute: () -> Unit,
    onContinueToNextStop: () -> Unit,
    onEndNavigation: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
    clockZoneId: ZoneId = ZoneId.systemDefault()
) {
    val session = uiState.session
    val unitSystem = uiState.settings.unitSystem

    // Screen wake-lock active strictly during ACTIVE or ARRIVED_STOP guidance on screen
    KeepScreenOnEffect(keepScreenOn = uiState.keepScreenOn)

    BackHandler(enabled = true) {
        onEndNavigation()
    }

    val mapState = remember(uiState, isDarkTheme) {
        uiState.toMapRenderingState(isDarkTheme = isDarkTheme)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .testTag("active_navigation_screen")
    ) {
        val isWide = maxWidth >= 600.dp
        val density = LocalDensity.current
        var portraitBottomBarHeightPx by remember { mutableIntStateOf(0) }
        val bottomBarClearanceDp = if (!isWide && session.status == NavigationStatus.ACTIVE) {
            with(density) {
                val measured = if (portraitBottomBarHeightPx > 0) portraitBottomBarHeightPx else 80.dp.roundToPx()
                measured.toDp() + 14.dp
            }
        } else {
            14.dp
        }
        val attributionClearanceForSpeedDp = with(density) { 28.sp.toDp() } + 14.dp

        val wideLeftInsetPx = with(density) {
            if (isWide) 396.dp.roundToPx() else 0
        }

        HalaTujuMapSurface(
            state = mapState,
            tileSource = tileSource,
            attributionPadding = PaddingValues(
                start = if (isWide) 406.dp else 14.dp,
                bottom = bottomBarClearanceDp + if (isWide) 0.dp else 4.dp
            ),
            leftPanelInsetPx = wideLeftInsetPx,
            onUserPanMap = onUserPanMap,
            onAttributionClick = onOpenAbout,
            modifier = Modifier
                .fillMaxSize()
                .testTag("navigation_map_surface")
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            if (isWide) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .widthIn(max = 382.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GuidanceTopSection(
                        session = session,
                        unitSystem = unitSystem,
                        onContinueToNextStop = onContinueToNextStop,
                        onEndNavigation = onEndNavigation
                    )

                    StatusBadgesRow(session = session)

                    if (session.status == NavigationStatus.ACTIVE) {
                        NavigationBottomBar(
                            session = session,
                            unitSystem = unitSystem,
                            clockZoneId = clockZoneId,
                            onEndNavigation = onEndNavigation
                        )
                    }
                }
            } else {
                // Compact portrait layout
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .widthIn(max = 460.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GuidanceTopSection(
                        session = session,
                        unitSystem = unitSystem,
                        onContinueToNextStop = onContinueToNextStop,
                        onEndNavigation = onEndNavigation
                    )
                    StatusBadgesRow(session = session)
                }

                if (session.status == NavigationStatus.ACTIVE) {
                    NavigationBottomBar(
                        session = session,
                        unitSystem = unitSystem,
                        clockZoneId = clockZoneId,
                        onEndNavigation = onEndNavigation,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .widthIn(max = 460.dp)
                            .fillMaxWidth()
                            .onSizeChanged { sz ->
                                portraitBottomBarHeightPx = sz.height
                            }
                    )
                }
            }

            // Live GPS speed + round regulatory speed limit badge (hides badge cleanly if unknown)
            if (session.status == NavigationStatus.ACTIVE) {
                SpeedCluster(
                    overspeed = session.overspeed,
                    unitSystem = unitSystem,
                    isDarkTheme = isDarkTheme,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(
                            start = if (isWide) 406.dp else 14.dp,
                            bottom = if (isWide) {
                                46.dp
                            } else {
                                bottomBarClearanceDp + attributionClearanceForSpeedDp
                            }
                        )
                )
            }

            // Floating navigation camera & audio controls on right edge
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = 14.dp,
                        bottom = if (isWide || session.status != NavigationStatus.ACTIVE) 20.dp else bottomBarClearanceDp + 6.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.End
            ) {
                FilledTonalIconButton(
                    onClick = onToggleMute,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("nav_mute_button")
                        .semantics {
                            contentDescription = if (session.isMuted) "Unmute voice guidance" else "Mute voice guidance"
                        },
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(
                        imageVector = if (session.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null
                    )
                }

                FilledTonalIconButton(
                    onClick = onToggleOverview,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("nav_overview_button")
                        .semantics {
                            contentDescription = if (session.cameraMode == MapCameraMode.OVERVIEW) {
                                "Exit route overview"
                            } else {
                                "Show full route overview"
                            }
                        },
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (session.cameraMode == MapCameraMode.OVERVIEW) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                        },
                        contentColor = if (session.cameraMode == MapCameraMode.OVERVIEW) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.AltRoute,
                        contentDescription = null
                    )
                }

                if (session.cameraMode != MapCameraMode.NAVIGATION_25D) {
                    Button(
                        onClick = onRecenterClick,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("nav_recenter_button")
                            .semantics { contentDescription = "Recenter navigation camera" },
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Recenter")
                    }
                }
            }
        }
    }
}

@Composable
private fun GuidanceTopSection(
    session: NavigationSessionState,
    unitSystem: UnitSystem,
    onContinueToNextStop: () -> Unit,
    onEndNavigation: () -> Unit
) {
    when (session.status) {
        NavigationStatus.ARRIVED_STOP -> StopArrivalCard(
            session = session,
            onContinue = onContinueToNextStop,
            onEnd = onEndNavigation
        )
        NavigationStatus.ARRIVED_DESTINATION -> DestinationArrivalCard(
            session = session,
            onDone = onEndNavigation
        )
        else -> ManeuverBannerCard(
            banner = session.banner,
            unitSystem = unitSystem
        )
    }
}

@Composable
private fun ManeuverBannerCard(
    banner: GuidanceBannerState,
    unitSystem: UnitSystem,
    modifier: Modifier = Modifier
) {
    val visibleLanes = remember(banner.lanes) { sanitizeLanesForDisplay(banner.lanes) }
    val hasAttachmentBelow = visibleLanes.isNotEmpty() || banner.thenStep != null
    val isRoundabout = banner.maneuverType == ManeuverType.ROUNDABOUT ||
        banner.maneuverType == ManeuverType.ROTARY ||
        banner.maneuverType == ManeuverType.ROUNDABOUT_TURN ||
        banner.maneuverType == ManeuverType.EXIT_ROUNDABOUT ||
        banner.maneuverType == ManeuverType.EXIT_ROTARY

    Column(modifier = modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("nav_maneuver_banner"),
            shape = if (hasAttachmentBelow) {
                RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 6.dp)
            } else {
                RoundedCornerShape(20.dp)
            },
            colors = CardDefaults.cardColors(
                containerColor = HalaTujuPalette.GuidanceBannerBg,
                contentColor = HalaTujuPalette.GuidanceBannerContent
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(Color.White.copy(alpha = 0.14f), CircleShape)
                        .semantics {
                            if (isRoundabout && banner.rotaryExitNumber != null) {
                                contentDescription = "Roundabout exit ${banner.rotaryExitNumber}"
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    ManeuverIcon(
                        maneuverType = banner.maneuverType,
                        maneuverModifier = banner.maneuverModifier,
                        tint = Color.White,
                        secondaryTint = Color.White.copy(alpha = 0.36f),
                        modifier = Modifier.size(38.dp)
                    )
                    if (banner.rotaryExitNumber != null && isRoundabout) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(22.dp)
                                .testTag("nav_rotary_exit_badge"),
                            shape = CircleShape,
                            color = HalaTujuPalette.AmberAccent
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = banner.rotaryExitNumber.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1F1600)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = NavigationFormatters.formatManeuverDistance(
                                banner.distanceToManeuverMeters,
                                unitSystem
                            ),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        banner.exitShieldText?.let { shield ->
                            Surface(
                                modifier = Modifier.testTag("nav_exit_shield"),
                                shape = RoundedCornerShape(6.dp),
                                color = HalaTujuPalette.AmberAccent
                            ) {
                                Text(
                                    text = "Exit $shield",
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1F1600)
                                )
                            }
                        }
                    }

                    Text(
                        text = banner.primaryText.ifBlank { "Continue along route" },
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    val dests = banner.secondaryDestinations
                    if (!dests.isNullOrBlank()) {
                        Text(
                            text = dests,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        if (visibleLanes.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            LaneGuidanceStrip(
                lanes = visibleLanes,
                upcomingModifier = banner.maneuverModifier,
                attachedBelowBanner = true,
                hasThenChipBelow = banner.thenStep != null
            )
        }

        banner.thenStep?.let { thenStep ->
            Surface(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .testTag("nav_then_chip"),
                shape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp, topStart = 6.dp, topEnd = 6.dp),
                color = HalaTujuPalette.DeepTealDark,
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Then",
                        style = MaterialTheme.typography.labelLarge,
                        color = HalaTujuPalette.CrispMint
                    )
                    ManeuverIcon(
                        maneuverType = thenStep.maneuverType,
                        maneuverModifier = thenStep.maneuverModifier,
                        tint = Color.White,
                        secondaryTint = Color.White.copy(alpha = 0.36f),
                        modifier = Modifier.size(18.dp)
                    )
                    if (thenStep.roadName.isNotBlank()) {
                        Text(
                            text = thenStep.roadName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.92f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedCluster(
    overspeed: OverspeedState,
    unitSystem: UnitSystem,
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean = isSystemInDarkTheme()
) {
    val speedVal = NavigationFormatters.formatSpeedValue(overspeed.currentSpeedKmh, unitSystem)
    val unitLabel = NavigationFormatters.speedUnitLabel(unitSystem)
    val limitKmh = overspeed.speedLimit.speedKmh
    val showSpeedBadge = !overspeed.speedLimit.isUnknown &&
        !overspeed.speedLimit.isExplicitNone &&
        limitKmh != null

    val isOver = overspeed.isOverspeeding
    val speedCardBg = when {
        isOver && isDarkTheme -> Color(0xFF3A1214).copy(alpha = 0.96f)
        isOver -> Color(0xFFFFEBEB).copy(alpha = 0.98f)
        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
    }
    val speedCardText = if (isOver) HalaTujuPalette.SpeedLimitRed else MaterialTheme.colorScheme.onSurface
    val speedCardBorder = if (isOver) BorderStroke(1.5.dp, HalaTujuPalette.SpeedLimitRed) else null

    Row(
        modifier = modifier.testTag("nav_speed_cluster"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier
                .testTag("nav_current_speed")
                .semantics {
                    contentDescription = if (isOver) {
                        "Current speed $speedVal $unitLabel, exceeding speed limit"
                    } else {
                        "Current speed $speedVal $unitLabel"
                    }
                },
            shape = RoundedCornerShape(14.dp),
            color = speedCardBg,
            border = speedCardBorder,
            tonalElevation = 4.dp,
            shadowElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = speedVal.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = speedCardText
                )
                Text(
                    text = unitLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOver) HalaTujuPalette.SpeedLimitRed else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (showSpeedBadge) {
            val limitVal = NavigationFormatters.formatSpeedValue(
                limitKmh,
                unitSystem
            )
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .background(Color.White, CircleShape)
                    .border(4.dp, HalaTujuPalette.SpeedLimitRed, CircleShape)
                    .testTag("nav_speed_limit_badge")
                    .semantics {
                        contentDescription = "Speed limit $limitVal $unitLabel"
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = limitVal.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF161D1D)
                )
            }
        }
    }
}

@Composable
private fun StatusBadgesRow(session: NavigationSessionState) {
    if (!session.isRerouting && !session.isGpsSignalWeak) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (session.isRerouting) {
            Surface(
                modifier = Modifier.testTag("rerouting_pill"),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                tonalElevation = 3.dp
            ) {
                Text(
                    text = "Rerouting…",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        if (session.isGpsSignalWeak) {
            Surface(
                modifier = Modifier.testTag("gps_weak_pill"),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                tonalElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.GpsOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "GPS signal weak",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun StopArrivalCard(
    session: NavigationSessionState,
    onContinue: () -> Unit,
    onEnd: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("nav_stop_arrival_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Arrived at stop ${session.arrivedStopNumber}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            session.arrivedStopPlace?.name?.takeIf { it.isNotBlank() }?.let { stopName ->
                Text(
                    text = stopName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onEnd,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("nav_stop_end_button")
                ) {
                    Text("End")
                }
                Button(
                    onClick = onContinue,
                    modifier = Modifier
                        .weight(1.6f)
                        .height(48.dp)
                        .testTag("nav_continue_stop_button")
                ) {
                    Text("Continue")
                }
            }
        }
    }
}

@Composable
private fun DestinationArrivalCard(
    session: NavigationSessionState,
    onDone: () -> Unit
) {
    val destName = session.route?.waypoints?.lastOrNull()?.name?.takeIf { it.isNotBlank() }
        ?: "your destination"
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("nav_destination_arrival_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "You have arrived",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = destName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(
                onClick = onDone,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("nav_done_button")
            ) {
                Text("Done")
            }
        }
    }
}

@Composable
private fun NavigationBottomBar(
    session: NavigationSessionState,
    unitSystem: UnitSystem,
    clockZoneId: ZoneId,
    onEndNavigation: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.testTag("nav_bottom_bar"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = NavigationFormatters.formatRemainingDuration(session.remainingDurationSeconds),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = NavigationFormatters.formatRemainingDistance(
                            session.remainingDistanceMeters,
                            unitSystem
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = "ETA ${NavigationFormatters.formatEtaClock(session.estimatedArrivalEpochMs, clockZoneId)} (${session.etaTrafficLabel})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Button(
                onClick = onEndNavigation,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("nav_end_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("End")
            }
        }
    }
}
