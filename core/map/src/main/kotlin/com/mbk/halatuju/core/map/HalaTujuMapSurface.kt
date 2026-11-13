package com.mbk.halatuju.core.map

import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.SystemClock
import android.view.Gravity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mbk.halatuju.core.map.theme.HalaTujuPalette
import com.mbk.halatuju.core.model.GeoMath
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.MapCameraMode
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import kotlin.math.abs

object MapLibreRuntime {
    @Volatile
    private var initAttempted = false

    @Volatile
    private var nativeReady = false

    fun ensureInitialized(context: Context): Boolean {
        if (initAttempted) return nativeReady
        synchronized(this) {
            if (initAttempted) return nativeReady
            initAttempted = true
            nativeReady = runCatching {
                HttpRequestUtil.setOkHttpClient(MapHttpClientFactory.createTileHttpClient(context.cacheDir))
                MapLibre.getInstance(context.applicationContext)
                true
            }.getOrElse {
                false
            }
            return nativeReady
        }
    }
}

/**
 * Full-screen interactive MapLibre map surface with:
 * - Light (OpenFreeMap Liberty) & Dark (halatuju_dark.json) styles with 3D buildings
 * - Display frame rate 60 fps puck interpolation
 * - 2.5D navigation pitch (45-60 deg), course-up bearing & lower-third puck framing
 * - Permanent "© OpenStreetMap contributors" attribution pill
 */
@Composable
fun HalaTujuMapSurface(
    state: MapRenderingState,
    tileSource: TileSource,
    modifier: Modifier = Modifier,
    attributionPadding: PaddingValues = PaddingValues(12.dp),
    leftPanelInsetPx: Int = 0,
    onUserPanMap: () -> Unit = {},
    onMapLongClick: (LatLng) -> Unit = {},
    onAttributionClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val inspectionMode = LocalInspectionMode.current
    val useNativeMap = remember(inspectionMode) {
        !inspectionMode && MapLibreRuntime.ensureInitialized(context)
    }

    val currentOnUserPan by rememberUpdatedState(onUserPanMap)
    val currentOnLongClick by rememberUpdatedState(onMapLongClick)
    val currentState by rememberUpdatedState(state)

    val puckAnimator = remember { PuckAnimator() }
    var animatedPuck by remember { mutableStateOf(state.puckState) }

    LaunchedEffect(state.puckState) {
        val target = state.puckState ?: run {
            animatedPuck = null
            return@LaunchedEffect
        }
        val nowMs = SystemClock.uptimeMillis()
        puckAnimator.setTarget(target, nowMs)
        animatedPuck = puckAnimator.currentAt(nowMs)
        while (puckAnimator.isAnimating(SystemClock.uptimeMillis())) {
            withFrameNanos {
                animatedPuck = puckAnimator.currentAt(SystemClock.uptimeMillis())
            }
        }
        animatedPuck = puckAnimator.currentAt(SystemClock.uptimeMillis())
    }

    var mapViewSize by remember { mutableStateOf(IntSize(1080, 1920)) }
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { mapViewSize = it }
    ) {
        if (!useNativeMap) {
            FallbackPreviewMapCanvas(
                state = state.copy(puckState = animatedPuck ?: state.puckState),
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val layerManager = remember { MapLibreLayerManager() }
            var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
            var activeStyleKey by remember { mutableStateOf<String?>(null) }
            var mapViewRef by remember { mutableStateOf<MapView?>(null) }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    MapView(viewContext).apply {
                        onCreate(null)
                        getMapAsync { map ->
                            map.uiSettings.apply {
                                isAttributionEnabled = false
                                isLogoEnabled = false
                                isCompassEnabled = false
                                isTiltGesturesEnabled = true
                                isRotateGesturesEnabled = true
                                isScrollGesturesEnabled = true
                                isZoomGesturesEnabled = true
                                setCompassGravity(Gravity.TOP or Gravity.END)
                            }
                            map.addOnCameraMoveStartedListener { reason ->
                                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                                    currentOnUserPan()
                                }
                            }
                            map.addOnMapLongClickListener { point ->
                                currentOnLongClick(LatLng(point.latitude, point.longitude))
                                true
                            }
                            mapRef = map
                        }
                        mapViewRef = this
                    }
                }
            )

            DisposableEffect(lifecycleOwner, mapViewRef) {
                val mv = mapViewRef
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> mv?.onStart()
                        Lifecycle.Event.ON_RESUME -> mv?.onResume()
                        Lifecycle.Event.ON_PAUSE -> mv?.onPause()
                        Lifecycle.Event.ON_STOP -> mv?.onStop()
                        Lifecycle.Event.ON_DESTROY -> mv?.onDestroy()
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    mv?.onPause()
                    mv?.onStop()
                    mv?.onDestroy()
                }
            }

            // Load or switch style when theme or tile endpoints change
            val styleSpec = remember(state.isDarkTheme, state.endpoints) {
                tileSource.resolveStyle(state.isDarkTheme, state.endpoints)
            }
            val styleKey = remember(styleSpec) {
                when (styleSpec) {
                    is MapStyleSpec.StyleUrl -> "url:${styleSpec.url}"
                    is MapStyleSpec.StyleJson -> "json:${styleSpec.cacheKey}"
                }
            }

            LaunchedEffect(mapRef, styleKey) {
                val map = mapRef ?: return@LaunchedEffect
                if (activeStyleKey == styleKey && map.style?.isFullyLoaded == true) return@LaunchedEffect
                val builder = when (styleSpec) {
                    is MapStyleSpec.StyleUrl -> Style.Builder().fromUri(styleSpec.url)
                    is MapStyleSpec.StyleJson -> Style.Builder().fromJson(styleSpec.json)
                }
                map.setStyle(builder) { loadedStyle ->
                    activeStyleKey = styleKey
                    layerManager.installLayers(loadedStyle, currentState.isDarkTheme)
                    layerManager.updateRoutes(
                        loadedStyle,
                        currentState.activeRoute,
                        currentState.alternativeRoutes,
                        currentState.selectedRouteIndex,
                        currentState.traveledDistanceMeters
                    )
                    layerManager.updatePins(
                        loadedStyle,
                        currentState.waypoints,
                        currentState.droppedPin
                    )
                    layerManager.updatePuck(loadedStyle, animatedPuck)
                    layerManager.updateDebugOverlay(
                        loadedStyle,
                        currentState.debugOverlayEnabled,
                        currentState.debugOverlayData
                    )
                }
            }

            // Update GeoJSON sources smoothly whenever state or animated puck ticks
            LaunchedEffect(
                mapRef,
                activeStyleKey,
                animatedPuck,
                state.activeRoute,
                state.alternativeRoutes,
                state.selectedRouteIndex,
                state.traveledDistanceMeters,
                state.waypoints,
                state.droppedPin,
                state.debugOverlayEnabled,
                state.debugOverlayData
            ) {
                val style = mapRef?.style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
                layerManager.updateRoutes(
                    style,
                    state.activeRoute,
                    state.alternativeRoutes,
                    state.selectedRouteIndex,
                    state.traveledDistanceMeters
                )
                layerManager.updatePins(style, state.waypoints, state.droppedPin)
                layerManager.updatePuck(style, animatedPuck)
                layerManager.updateDebugOverlay(
                    style,
                    state.debugOverlayEnabled,
                    state.debugOverlayData
                )
            }

            // Synchronize camera position, tilt, bearing & lower-third padding
            LaunchedEffect(
                mapRef,
                activeStyleKey,
                state.cameraState,
                animatedPuck,
                state.activeRoute?.id,
                mapViewSize,
                leftPanelInsetPx
            ) {
                val map = mapRef ?: return@LaunchedEffect
                val cam = state.cameraState
                val widthPx = mapViewSize.width.coerceAtLeast(1)
                val heightPx = mapViewSize.height.coerceAtLeast(1)

                when (cam.mode) {
                    MapCameraMode.FREE_PAN -> Unit

                    MapCameraMode.OVERVIEW -> {
                        val pts = state.activeRoute?.geometry
                            ?.takeIf { it.size >= 2 }
                            ?: state.waypoints.map { it.location }.takeIf { it.size >= 2 }
                        if (pts != null) {
                            val builder = LatLngBounds.Builder()
                            pts.forEach { pt ->
                                builder.include(org.maplibre.android.geometry.LatLng(pt.latitude, pt.longitude))
                            }
                            val padH = with(density) { 48.dp.roundToPx() }
                            val padTop = with(density) { 120.dp.roundToPx() }
                            val padBottom = with(density) { 180.dp.roundToPx() }
                            map.animateCamera(
                                CameraUpdateFactory.newLatLngBounds(
                                    builder.build(),
                                    leftPanelInsetPx + padH,
                                    padTop,
                                    padH,
                                    padBottom
                                ),
                                550
                            )
                        }
                    }

                    MapCameraMode.FOLLOW_PUCK -> {
                        val target = animatedPuck?.position ?: cam.center
                        val nextPos = CameraPosition.Builder()
                            .target(org.maplibre.android.geometry.LatLng(target.latitude, target.longitude))
                            .zoom(cam.zoom)
                            .bearing(cam.bearingDegrees)
                            .tilt(cam.tiltDegrees.coerceIn(0.0, 45.0))
                            .padding(leftPanelInsetPx.toDouble(), 0.0, 0.0, 0.0)
                            .build()
                        val existing = map.cameraPosition
                        val distChange = existing.target?.let {
                            GeoMath.haversineMeters(LatLng(it.latitude, it.longitude), target)
                        } ?: Double.MAX_VALUE
                        if (distChange > 2.0 || abs(existing.zoom - cam.zoom) > 0.05) {
                            map.easeCamera(CameraUpdateFactory.newCameraPosition(nextPos), 350)
                        }
                    }

                    MapCameraMode.NAVIGATION_25D -> {
                        val target = animatedPuck?.position ?: cam.center
                        val heading = animatedPuck?.bearingDegrees?.toDouble() ?: cam.bearingDegrees
                        // Place target puck cleanly inside the lower third of the visible map canvas
                        val topBiasPadding = heightPx * 0.34
                        val bottomMarginPadding = heightPx * 0.14
                        val clampedTilt = cam.tiltDegrees.coerceIn(45.0, 60.0)
                        val nextPos = CameraPosition.Builder()
                            .target(org.maplibre.android.geometry.LatLng(target.latitude, target.longitude))
                            .zoom(cam.zoom)
                            .bearing(heading)
                            .tilt(clampedTilt)
                            .padding(leftPanelInsetPx.toDouble(), topBiasPadding, 0.0, bottomMarginPadding)
                            .build()
                        map.moveCamera(CameraUpdateFactory.newCameraPosition(nextPos))
                    }
                }
                @Suppress("UNUSED_EXPRESSION")
                widthPx
            }
        }

        // Permanent always-visible OpenStreetMap attribution chip
        Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(attributionPadding)
                .testTag("osm_attribution")
                .semantics {
                    contentDescription = "Map data attribution: OpenStreetMap contributors. Tap for licenses."
                }
                .clickable(role = Role.Button, onClick = onAttributionClick),
            shape = RoundedCornerShape(10.dp),
            color = if (state.isDarkTheme) {
                Color(0xCC11171A)
            } else {
                Color(0xD9FFFFFF)
            },
            tonalElevation = 2.dp
        ) {
            Text(
                text = "© OpenStreetMap contributors",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (state.isDarkTheme) {
                    Color(0xFFE4EAE9)
                } else {
                    Color(0xFF1A2424)
                }
            )
        }
    }
}

/**
 * Deterministic vector map canvas used only in Compose previews and Robolectric JVM screenshot tests
 * where native MapLibre OpenGL shared libraries are absent.
 */
@Composable
private fun FallbackPreviewMapCanvas(
    state: MapRenderingState,
    modifier: Modifier = Modifier
) {
    val bgColor = if (state.isDarkTheme) Color(0xFF0E1318) else Color(0xFFEFF3F3)
    val parkColor = if (state.isDarkTheme) Color(0xFF13261F) else Color(0xFFD5EAD9)
    val waterColor = if (state.isDarkTheme) Color(0xFF091A2B) else Color(0xFFBFE0F5)
    val buildingColor = if (state.isDarkTheme) Color(0xFF1B2731) else Color(0xFFD9E1E2)
    val roadSecondary = if (state.isDarkTheme) Color(0xFF233240) else Color(0xFFFFFFFF)
    val roadHighway = if (state.isDarkTheme) Color(0xFF395066) else Color(0xFFF6C969)
    val routeColor = if (state.isDarkTheme) HalaTujuPalette.RouteBlueDark else HalaTujuPalette.RouteBlueLight
    val traveledColor = if (state.isDarkTheme) HalaTujuPalette.RouteTraveledDark else HalaTujuPalette.RouteTraveledLight

    Box(
        modifier = modifier
            .background(bgColor)
            .testTag("map_surface_preview")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Park and water polygons (evocative KLCC / Klang river layout)
            drawRect(color = parkColor, topLeft = Offset(w * 0.56f, h * 0.28f), size = Size(w * 0.34f, h * 0.24f))
            drawRect(color = waterColor, topLeft = Offset(0f, h * 0.62f), size = Size(w * 0.42f, h * 0.06f))

            // Subtle extruded-style building blocks
            val blocks = listOf(
                Offset(w * 0.14f, h * 0.20f) to Size(w * 0.16f, h * 0.09f),
                Offset(w * 0.34f, h * 0.17f) to Size(w * 0.14f, h * 0.12f),
                Offset(w * 0.62f, h * 0.14f) to Size(w * 0.18f, h * 0.09f),
                Offset(w * 0.16f, h * 0.43f) to Size(w * 0.20f, h * 0.11f),
                Offset(w * 0.62f, h * 0.68f) to Size(w * 0.24f, h * 0.13f)
            )
            blocks.forEach { (pos, sz) ->
                drawRect(color = buildingColor, topLeft = pos, size = sz)
            }

            // Road grid + expressway curve
            for (i in 1..4) {
                val y = h * (i * 0.19f)
                drawLine(roadSecondary, Offset(0f, y), Offset(w, y + h * 0.03f), strokeWidth = 10f)
            }
            for (i in 1..3) {
                val x = w * (i * 0.25f)
                drawLine(roadSecondary, Offset(x, 0f), Offset(x - w * 0.04f, h), strokeWidth = 10f)
            }
            drawLine(roadHighway, Offset(0f, h * 0.78f), Offset(w, h * 0.32f), strokeWidth = 18f)

            // Active + alternative route overlays if present in preview state
            val active = state.activeRoute
            if (active != null && active.geometry.size >= 2) {
                val allPoints = buildList {
                    addAll(active.geometry)
                    state.alternativeRoutes.forEach { addAll(it.geometry) }
                }
                val isOverview = state.cameraState.mode == MapCameraMode.OVERVIEW
                val minLat = allPoints.minOf { it.latitude }
                val maxLat = allPoints.maxOf { it.latitude }
                val minLon = allPoints.minOf { it.longitude }
                val maxLon = allPoints.maxOf { it.longitude }
                val refCenter = if (isOverview) {
                    LatLng((minLat + maxLat) * 0.5, (minLon + maxLon) * 0.5)
                } else {
                    state.puckState?.position ?: state.cameraState.center
                }
                val metersPerPx = if (isOverview) {
                    val (spanE, spanN) = GeoMath.toLocalEnMeters(LatLng(minLat, minLon), LatLng(maxLat, maxLon))
                    val usableW = (w * 0.72f).coerceAtLeast(100f)
                    val usableH = (h * 0.38f).coerceAtLeast(100f)
                    maxOf(spanE.toFloat() / usableW, spanN.toFloat() / usableH, 6.0f).toDouble()
                } else {
                    6.0
                }
                val anchorY = when (state.cameraState.mode) {
                    MapCameraMode.NAVIGATION_25D -> h * 0.67f
                    MapCameraMode.OVERVIEW -> h * 0.43f
                    else -> h * 0.5f
                }

                fun project(pt: LatLng): Offset {
                    val (e, n) = GeoMath.toLocalEnMeters(refCenter, pt)
                    return Offset(
                        x = w * 0.5f + (e / metersPerPx).toFloat(),
                        y = anchorY - (n / metersPerPx).toFloat()
                    )
                }

                val altColor = if (state.isDarkTheme) Color(0xFF546E7A) else Color(0xFF90A4AE)
                state.alternativeRoutes
                    .filterIndexed { idx, _ -> idx != state.selectedRouteIndex }
                    .forEach { alt ->
                        if (alt.geometry.size >= 2) {
                            val altPath = Path().apply {
                                val first = project(alt.geometry.first())
                                moveTo(first.x, first.y)
                                alt.geometry.drop(1).forEach { p ->
                                    val o = project(p)
                                    lineTo(o.x, o.y)
                                }
                            }
                            drawPath(altPath, altColor, style = Stroke(11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }

                val split = RouteGeometrySplitter.splitAtDistance(active, state.traveledDistanceMeters)
                if (split.traveled.size >= 2) {
                    val tPath = Path().apply {
                        val first = project(split.traveled.first())
                        moveTo(first.x, first.y)
                        split.traveled.drop(1).forEach { p ->
                            val o = project(p)
                            lineTo(o.x, o.y)
                        }
                    }
                    drawPath(tPath, traveledColor, style = Stroke(14f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                if (split.remaining.size >= 2) {
                    val rPath = Path().apply {
                        val first = project(split.remaining.first())
                        moveTo(first.x, first.y)
                        split.remaining.drop(1).forEach { p ->
                            val o = project(p)
                            lineTo(o.x, o.y)
                        }
                    }
                    drawPath(rPath, routeColor, style = Stroke(16f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }

                // Waypoint & destination dots
                state.waypoints.drop(1).forEachIndexed { idx, wp ->
                    val pt = project(wp.location)
                    val isDest = idx == state.waypoints.size - 2
                    drawCircle(color = Color.White, radius = 15f, center = pt)
                    drawCircle(
                        color = if (isDest) Color(0xFFD92B2B) else HalaTujuPalette.AmberAccent,
                        radius = 11f,
                        center = pt
                    )
                }
            }

            // Puck chevron
            val puck = state.puckState
            if (puck != null) {
                val puckCenter = Offset(
                    x = w * 0.5f,
                    y = if (state.cameraState.mode == MapCameraMode.NAVIGATION_25D) h * 0.67f else h * 0.5f
                )
                drawCircle(
                    color = HalaTujuPalette.CrispMint.copy(alpha = 0.22f),
                    radius = 44f,
                    center = puckCenter
                )
                drawCircle(color = Color.White, radius = 22f, center = puckCenter)
                drawCircle(
                    color = HalaTujuPalette.DeepTeal,
                    radius = 22f,
                    center = puckCenter,
                    style = Stroke(width = 4f)
                )
                rotate(degrees = puck.bearingDegrees, pivot = puckCenter) {
                    val arrow = Path().apply {
                        moveTo(puckCenter.x, puckCenter.y - 14f)
                        lineTo(puckCenter.x + 11f, puckCenter.y + 10f)
                        lineTo(puckCenter.x, puckCenter.y + 5f)
                        lineTo(puckCenter.x - 11f, puckCenter.y + 10f)
                        close()
                    }
                    drawPath(arrow, color = HalaTujuPalette.DeepTeal)
                }
            }

            // Debug overlay points in preview
            if (state.debugOverlayEnabled && state.debugOverlayData != null) {
                val dbgCenter = Offset(w * 0.5f, h * 0.5f)
                state.debugOverlayData.rawFix?.let {
                    drawCircle(Color.Red, radius = 8f, center = dbgCenter + Offset(-14f, -12f))
                }
                state.debugOverlayData.kalmanPosition?.let {
                    drawCircle(Color.Cyan, radius = 8f, center = dbgCenter + Offset(-5f, -4f))
                }
                state.debugOverlayData.snappedPosition?.let {
                    drawCircle(Color.Green, radius = 8f, center = dbgCenter)
                }
            }
        }
    }
}
