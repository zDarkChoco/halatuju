package com.mbk.halatuju.core.map

import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.SystemClock
import android.util.Log
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
import androidx.compose.ui.unit.sp
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
    private const val TAG = "HalaTujuMap"

    @Volatile
    private var initAttempted = false

    @Volatile
    private var nativeReady = false

    fun ensureInitialized(context: Context): Boolean {
        if (initAttempted) return nativeReady
        synchronized(this) {
            if (initAttempted) return nativeReady
            initAttempted = true
            if ("robolectric".equals(Build.FINGERPRINT, ignoreCase = true)) {
                nativeReady = false
                return false
            }
            nativeReady = runCatching {
                val appContext = context.applicationContext
                // MapLibre.getInstance must precede HttpRequestUtil.setOkHttpClient (its static init calls HttpIdentifier).
                MapLibre.getInstance(appContext)
                HttpRequestUtil.setOkHttpClient(MapHttpClientFactory.createTileHttpClient(appContext.cacheDir))
                true
            }.getOrElse { err ->
                Log.w(TAG, "Native MapLibre unavailable, using fallback preview canvas", err)
                false
            }
            return nativeReady
        }
    }
}

private class NativeMapHolder {
    var mapView: MapView? = null
    var map: MapLibreMap? by mutableStateOf(null)
    var activeStyleKey: String? by mutableStateOf(null)
    var isStarted: Boolean = false
    var isResumed: Boolean = false
    var isDestroyed: Boolean = false

    fun attachView(view: MapView, currentLifecycleState: Lifecycle.State) {
        if (mapView === view) return
        mapView = view
        isStarted = false
        isResumed = false
        isDestroyed = false
        view.onCreate(null)
        syncToLifecycle(currentLifecycleState)
    }

    fun syncToLifecycle(state: Lifecycle.State) {
        if (state.isAtLeast(Lifecycle.State.STARTED)) {
            dispatchStart()
        }
        if (state.isAtLeast(Lifecycle.State.RESUMED)) {
            dispatchResume()
        }
    }

    fun dispatchStart() {
        val mv = mapView ?: return
        if (!isDestroyed && !isStarted) {
            isStarted = true
            mv.onStart()
        }
    }

    fun dispatchResume() {
        dispatchStart()
        val mv = mapView ?: return
        if (!isDestroyed && !isResumed) {
            isResumed = true
            mv.onResume()
        }
    }

    fun dispatchPause() {
        val mv = mapView ?: return
        if (!isDestroyed && isResumed) {
            isResumed = false
            mv.onPause()
        }
    }

    fun dispatchStop() {
        dispatchPause()
        val mv = mapView ?: return
        if (!isDestroyed && isStarted) {
            isStarted = false
            mv.onStop()
        }
    }

    fun dispatchDestroy(expectedView: MapView? = null) {
        val mv = mapView ?: return
        if (expectedView != null && mv !== expectedView) return
        if (!isDestroyed) {
            runCatching { map?.cancelTransitions() }
            dispatchStop()
            isDestroyed = true
            map = null
            activeStyleKey = null
            mapView = null
            mv.onDestroy()
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

    var mapViewSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { mapViewSize = it }
    ) {
        if (!useNativeMap) {
            FallbackPreviewMapCanvas(
                state = state.copy(puckState = animatedPuck ?: state.puckState),
                leftPanelInsetPx = leftPanelInsetPx,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val layerManager = remember { MapLibreLayerManager() }
            val holder = remember { NativeMapHolder() }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    MapView(viewContext).also { mv ->
                        holder.attachView(mv, lifecycleOwner.lifecycle.currentState)
                        mv.addOnDidFailLoadingMapListener { errorMessage ->
                            if (!holder.isDestroyed && holder.mapView === mv) {
                                Log.w("HalaTujuMap", "Map style load failed: $errorMessage")
                            }
                        }
                        mv.getMapAsync { loadedMap ->
                            if (holder.isDestroyed || holder.mapView !== mv) return@getMapAsync
                            loadedMap.uiSettings.apply {
                                isAttributionEnabled = false
                                isLogoEnabled = false
                                isCompassEnabled = false
                                isTiltGesturesEnabled = true
                                isRotateGesturesEnabled = true
                                isScrollGesturesEnabled = true
                                isZoomGesturesEnabled = true
                                setCompassGravity(Gravity.TOP or Gravity.END)
                            }
                            loadedMap.addOnCameraMoveStartedListener { reason ->
                                if (!holder.isDestroyed &&
                                    reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
                                ) {
                                    currentOnUserPan()
                                }
                            }
                            loadedMap.addOnMapLongClickListener { point ->
                                if (holder.isDestroyed) return@addOnMapLongClickListener false
                                currentOnLongClick(LatLng(point.latitude, point.longitude))
                                true
                            }
                            holder.map = loadedMap
                        }
                    }
                },
                onRelease = { releasedView ->
                    holder.dispatchDestroy(releasedView)
                }
            )

            DisposableEffect(lifecycleOwner, holder) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> holder.dispatchStart()
                        Lifecycle.Event.ON_RESUME -> holder.dispatchResume()
                        Lifecycle.Event.ON_PAUSE -> holder.dispatchPause()
                        Lifecycle.Event.ON_STOP -> holder.dispatchStop()
                        Lifecycle.Event.ON_DESTROY -> holder.dispatchDestroy()
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                holder.syncToLifecycle(lifecycleOwner.lifecycle.currentState)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    holder.dispatchDestroy()
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
            val latestStyleKey by rememberUpdatedState(styleKey)
            val latestAnimatedPuck by rememberUpdatedState(animatedPuck)

            LaunchedEffect(holder.map, styleKey) {
                val map = holder.map ?: return@LaunchedEffect
                if (holder.isDestroyed || holder.mapView == null) return@LaunchedEffect
                if (holder.activeStyleKey == styleKey && map.style?.isFullyLoaded == true) return@LaunchedEffect
                val builder = when (styleSpec) {
                    is MapStyleSpec.StyleUrl -> Style.Builder().fromUri(styleSpec.url)
                    is MapStyleSpec.StyleJson -> Style.Builder().fromJson(styleSpec.json)
                }
                map.setStyle(builder) { loadedStyle ->
                    if (holder.isDestroyed || holder.map !== map || styleKey != latestStyleKey) return@setStyle
                    holder.activeStyleKey = styleKey
                    runCatching {
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
                        layerManager.updatePuck(loadedStyle, latestAnimatedPuck)
                        layerManager.updateDebugOverlay(
                            loadedStyle,
                            currentState.debugOverlayEnabled,
                            currentState.debugOverlayData
                        )
                    }.onFailure { err ->
                        Log.w("HalaTujuMap", "Failed to apply style layers", err)
                    }
                }
            }

            // Update routes, pins & debug overlay when navigation/search state changes
            LaunchedEffect(
                holder.map,
                holder.activeStyleKey,
                state.activeRoute,
                state.alternativeRoutes,
                state.selectedRouteIndex,
                state.traveledDistanceMeters,
                state.waypoints,
                state.droppedPin,
                state.debugOverlayEnabled,
                state.debugOverlayData
            ) {
                if (holder.isDestroyed || holder.mapView == null) return@LaunchedEffect
                val style = holder.map?.style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
                runCatching {
                    layerManager.updateRoutes(
                        style,
                        state.activeRoute,
                        state.alternativeRoutes,
                        state.selectedRouteIndex,
                        state.traveledDistanceMeters
                    )
                    layerManager.updatePins(style, state.waypoints, state.droppedPin)
                    layerManager.updateDebugOverlay(
                        style,
                        state.debugOverlayEnabled,
                        state.debugOverlayData
                    )
                }.onFailure { err ->
                    Log.w("HalaTujuMap", "Route layer update failed", err)
                }
            }

            // Update puck GeoJSON independently at 60 fps during interpolation
            LaunchedEffect(holder.map, holder.activeStyleKey, animatedPuck) {
                if (holder.isDestroyed || holder.mapView == null) return@LaunchedEffect
                val style = holder.map?.style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
                runCatching {
                    layerManager.updatePuck(style, animatedPuck)
                }.onFailure { err ->
                    Log.w("HalaTujuMap", "Puck layer update failed", err)
                }
            }

            // Synchronize camera position, tilt, bearing & lower-third padding
            LaunchedEffect(
                holder.map,
                holder.activeStyleKey,
                state.cameraState,
                animatedPuck,
                state.activeRoute?.id,
                mapViewSize,
                leftPanelInsetPx
            ) {
                if (holder.isDestroyed || holder.mapView == null) return@LaunchedEffect
                val map = holder.map ?: return@LaunchedEffect
                val widthPx = (holder.mapView?.width ?: 0).takeIf { it > 0 } ?: mapViewSize.width
                val heightPx = (holder.mapView?.height ?: 0).takeIf { it > 0 } ?: mapViewSize.height
                if (widthPx <= 32 || heightPx <= 32) return@LaunchedEffect

                val cam = state.cameraState
                runCatching {
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
                                // Keep a tiny minimal bounding box so collinear/identical endpoints never throw
                                val first = pts.first()
                                val nePad = GeoMath.fromLocalEnMeters(first, eastMeters = 25.0, northMeters = 25.0)
                                val swPad = GeoMath.fromLocalEnMeters(first, eastMeters = -25.0, northMeters = -25.0)
                                builder.include(org.maplibre.android.geometry.LatLng(nePad.latitude, nePad.longitude))
                                builder.include(org.maplibre.android.geometry.LatLng(swPad.latitude, swPad.longitude))

                                val basePadH = with(density) { 48.dp.roundToPx() }
                                val maxHorizSum = (widthPx * 0.6f).toInt().coerceAtLeast(16)
                                var padLeft = (leftPanelInsetPx + basePadH).coerceAtMost(maxHorizSum - 8)
                                var padRight = basePadH.coerceAtMost((maxHorizSum - padLeft).coerceAtLeast(8))
                                if (padLeft + padRight >= widthPx) {
                                    padLeft = (widthPx * 0.1f).toInt()
                                    padRight = (widthPx * 0.1f).toInt()
                                }

                                val desiredTop = with(density) { 120.dp.roundToPx() }
                                val desiredBottom = with(density) { 180.dp.roundToPx() }
                                val maxVertSum = (heightPx * 0.6f).toInt().coerceAtLeast(16)
                                val totalVert = desiredTop + desiredBottom
                                val (padTop, padBottom) = if (totalVert > maxVertSum) {
                                    val scale = maxVertSum.toFloat() / totalVert.toFloat()
                                    (desiredTop * scale).toInt() to (desiredBottom * scale).toInt()
                                } else {
                                    desiredTop to desiredBottom
                                }

                                map.animateCamera(
                                    CameraUpdateFactory.newLatLngBounds(
                                        builder.build(),
                                        padLeft,
                                        padTop,
                                        padRight,
                                        padBottom
                                    ),
                                    550
                                )
                            }
                        }

                        MapCameraMode.FOLLOW_PUCK -> {
                            val target = animatedPuck?.position ?: cam.center
                            val safeLeft = leftPanelInsetPx.toDouble().coerceIn(0.0, widthPx * 0.5)
                            val nextPos = CameraPosition.Builder()
                                .target(org.maplibre.android.geometry.LatLng(target.latitude, target.longitude))
                                .zoom(cam.zoom)
                                .bearing(cam.bearingDegrees)
                                .tilt(cam.tiltDegrees.coerceIn(0.0, 45.0))
                                .padding(safeLeft, 0.0, 0.0, 0.0)
                                .build()
                            val existing = map.cameraPosition
                            val distChange = existing.target?.let {
                                GeoMath.haversineMeters(LatLng(it.latitude, it.longitude), target)
                            } ?: Double.MAX_VALUE
                            if (distChange > 2.0 || abs(existing.zoom - cam.zoom) > 0.05 ||
                                abs(existing.tilt - nextPos.tilt) > 0.5
                            ) {
                                map.easeCamera(CameraUpdateFactory.newCameraPosition(nextPos), 350)
                            }
                        }

                        MapCameraMode.NAVIGATION_25D -> {
                            val target = animatedPuck?.position ?: cam.center
                            val heading = animatedPuck?.bearingDegrees?.toDouble() ?: cam.bearingDegrees
                            // Place target puck cleanly inside the lower third (~68% y) of the visible map canvas
                            val topBiasPadding = heightPx * 0.48
                            val bottomMarginPadding = heightPx * 0.12
                            val safeLeft = leftPanelInsetPx.toDouble().coerceIn(0.0, widthPx * 0.5)
                            val clampedTilt = cam.tiltDegrees.coerceIn(45.0, 60.0)
                            val nextPos = CameraPosition.Builder()
                                .target(org.maplibre.android.geometry.LatLng(target.latitude, target.longitude))
                                .zoom(cam.zoom)
                                .bearing(heading)
                                .tilt(clampedTilt)
                                .padding(safeLeft, topBiasPadding, 0.0, bottomMarginPadding)
                                .build()
                            map.moveCamera(CameraUpdateFactory.newCameraPosition(nextPos))
                        }
                    }
                }.onFailure { err ->
                    Log.w("HalaTujuMap", "Camera update skipped", err)
                }
            }
        }

        // Permanent always-visible OpenStreetMap attribution chip
        val attributionScale = density.fontScale.coerceIn(1.0f, 1.25f)
        val attributionFontSize = with(density) { (11f * attributionScale).dp.toSp() }
        Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(attributionPadding)
                .padding(end = 68.dp)
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
                style = MaterialTheme.typography.labelSmall.copy(fontSize = attributionFontSize),
                maxLines = 1,
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
    leftPanelInsetPx: Int = 0,
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
            val safeInsetX = leftPanelInsetPx.toFloat().coerceIn(0f, w * 0.6f)
            val anchorX = safeInsetX + (w - safeInsetX) * 0.5f

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

            val isOverview = state.cameraState.mode == MapCameraMode.OVERVIEW
            val anchorY = when (state.cameraState.mode) {
                MapCameraMode.NAVIGATION_25D -> h * 0.67f
                MapCameraMode.OVERVIEW -> if (safeInsetX > 0f) h * 0.5f else h * 0.43f
                else -> h * 0.5f
            }

            val active = state.activeRoute
            val allPoints = if (active != null && active.geometry.size >= 2) {
                buildList {
                    addAll(active.geometry)
                    state.alternativeRoutes.forEach { addAll(it.geometry) }
                }
            } else {
                emptyList()
            }

            val refCenter = if (isOverview && allPoints.size >= 2) {
                val minLat = allPoints.minOf { it.latitude }
                val maxLat = allPoints.maxOf { it.latitude }
                val minLon = allPoints.minOf { it.longitude }
                val maxLon = allPoints.maxOf { it.longitude }
                LatLng((minLat + maxLat) * 0.5, (minLon + maxLon) * 0.5)
            } else {
                state.puckState?.position ?: state.cameraState.center
            }

            val metersPerPx = if (isOverview && allPoints.size >= 2) {
                val minLat = allPoints.minOf { it.latitude }
                val maxLat = allPoints.maxOf { it.latitude }
                val minLon = allPoints.minOf { it.longitude }
                val maxLon = allPoints.maxOf { it.longitude }
                val (spanE, spanN) = GeoMath.toLocalEnMeters(LatLng(minLat, minLon), LatLng(maxLat, maxLon))
                val usableW = ((w - safeInsetX) * 0.72f).coerceAtLeast(100f)
                val usableH = (h * (if (safeInsetX > 0f) 0.62f else 0.38f)).coerceAtLeast(100f)
                maxOf(spanE.toFloat() / usableW, spanN.toFloat() / usableH, 6.0f).toDouble()
            } else {
                4.5
            }

            val mapBearingDeg = if (isOverview) 0.0 else state.cameraState.bearingDegrees
            val bearingRad = Math.toRadians(mapBearingDeg)
            val sinB = kotlin.math.sin(bearingRad)
            val cosB = kotlin.math.cos(bearingRad)

            fun project(pt: LatLng): Offset {
                val (e, n) = GeoMath.toLocalEnMeters(refCenter, pt)
                val screenRight = e * cosB - n * sinB
                val screenForward = e * sinB + n * cosB
                return Offset(
                    x = anchorX + (screenRight / metersPerPx).toFloat(),
                    y = anchorY - (screenForward / metersPerPx).toFloat()
                )
            }

            // Active + alternative route overlays if present in preview state
            if (active != null && active.geometry.size >= 2) {
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

            // Puck chevron (rotated relative to active course-up map bearing)
            val puck = state.puckState
            if (puck != null) {
                val puckCenter = if (isOverview) {
                    project(puck.position)
                } else {
                    Offset(x = anchorX, y = anchorY)
                }
                val haloRadiusPx = ((puck.accuracyMeters / metersPerPx).toFloat()).coerceIn(28f, 68f)
                drawCircle(
                    color = HalaTujuPalette.CrispMint.copy(alpha = 0.22f),
                    radius = haloRadiusPx,
                    center = puckCenter
                )
                drawCircle(color = Color.White, radius = 22f, center = puckCenter)
                drawCircle(
                    color = HalaTujuPalette.DeepTeal,
                    radius = 22f,
                    center = puckCenter,
                    style = Stroke(width = 4f)
                )
                rotate(degrees = puck.bearingDegrees - mapBearingDeg.toFloat(), pivot = puckCenter) {
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

            // Debug overlay points & raw accuracy ring projected in map coordinates (raw=red, kalman=cyan, snapped=green)
            if (state.debugOverlayEnabled && state.debugOverlayData != null) {
                state.debugOverlayData.rawFix?.let { rawFix ->
                    val rawCenter = project(rawFix.position)
                    val rawRadiusPx = ((rawFix.accuracyMeters / metersPerPx).toFloat()).coerceIn(14f, 84f)
                    drawCircle(
                        color = Color(0x88FF5252),
                        radius = rawRadiusPx,
                        center = rawCenter,
                        style = Stroke(width = 2.5f)
                    )
                    drawCircle(Color(0xFFFF5252), radius = 9f, center = rawCenter)
                }
                state.debugOverlayData.kalmanPosition?.let { kalman ->
                    drawCircle(Color(0xFF00E5FF), radius = 9f, center = project(kalman))
                }
                state.debugOverlayData.snappedPosition?.let { snapped ->
                    drawCircle(Color(0xFF00E676), radius = 9f, center = project(snapped))
                }
            }
        }
    }
}
