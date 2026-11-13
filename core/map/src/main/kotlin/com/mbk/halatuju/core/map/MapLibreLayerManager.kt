package com.mbk.halatuju.core.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.toArgb
import com.mbk.halatuju.core.map.theme.HalaTujuPalette
import com.mbk.halatuju.core.model.DebugOverlayData
import com.mbk.halatuju.core.model.GeoMath
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.NavRoute
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.model.PuckRenderState
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.cos
import kotlin.math.sin

/**
 * Installs and updates HalaTuju sources and layers on top of the active MapLibre [Style].
 */
internal class MapLibreLayerManager {

    fun installLayers(style: Style, isDarkTheme: Boolean) {
        if (style.getImage(IMAGE_PUCK) == null) {
            style.addImage(IMAGE_PUCK, createPuckBitmap())
        }
        if (style.getImage(IMAGE_PIN) == null) {
            style.addImage(IMAGE_PIN, createPinBitmap())
        }

        ensureSource(style, SOURCE_ALT_ROUTES)
        ensureSource(style, SOURCE_TRAVELED_ROUTE)
        ensureSource(style, SOURCE_ACTIVE_ROUTE)
        ensureSource(style, SOURCE_PINS)
        ensureSource(style, SOURCE_PUCK_ACCURACY)
        ensureSource(style, SOURCE_PUCK)
        ensureSource(style, SOURCE_DEBUG_ACCURACY)
        ensureSource(style, SOURCE_DEBUG_POINTS)

        val insertBelowId = findLabelAnchorLayerId(style)

        val activeRouteColor = if (isDarkTheme) {
            HalaTujuPalette.RouteBlueDark.toArgb()
        } else {
            HalaTujuPalette.RouteBlueLight.toArgb()
        }
        val activeCasingColor = if (isDarkTheme) 0xFF0A2E5C.toInt() else 0xFF0D47A1.toInt()
        val traveledColor = if (isDarkTheme) {
            HalaTujuPalette.RouteTraveledDark.toArgb()
        } else {
            HalaTujuPalette.RouteTraveledLight.toArgb()
        }
        val altRouteColor = if (isDarkTheme) 0xFF546E7A.toInt() else 0xFF90A4AE.toInt()

        addLayerIfMissing(style, insertBelowId) {
            LineLayer(LAYER_ALT_ROUTES, SOURCE_ALT_ROUTES).withProperties(
                PropertyFactory.lineColor(altRouteColor),
                PropertyFactory.lineWidth(6.5f),
                PropertyFactory.lineOpacity(0.75f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        }

        addLayerIfMissing(style, insertBelowId) {
            LineLayer(LAYER_TRAVELED_ROUTE, SOURCE_TRAVELED_ROUTE).withProperties(
                PropertyFactory.lineColor(traveledColor),
                PropertyFactory.lineWidth(7.5f),
                PropertyFactory.lineOpacity(0.65f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        }

        addLayerIfMissing(style, insertBelowId) {
            LineLayer(LAYER_ACTIVE_ROUTE_CASING, SOURCE_ACTIVE_ROUTE).withProperties(
                PropertyFactory.lineColor(activeCasingColor),
                PropertyFactory.lineWidth(12f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        }

        addLayerIfMissing(style, insertBelowId) {
            LineLayer(LAYER_ACTIVE_ROUTE, SOURCE_ACTIVE_ROUTE).withProperties(
                PropertyFactory.lineColor(activeRouteColor),
                PropertyFactory.lineWidth(8.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        }

        // Waypoint & dropped pins sit above route lines
        addLayerIfMissing(style, null) {
            SymbolLayer(LAYER_PINS, SOURCE_PINS).withProperties(
                PropertyFactory.iconImage(IMAGE_PIN),
                PropertyFactory.iconSize(1.0f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM)
            )
        }

        // True-metric GPS accuracy halo polygon fill + crisp outline
        addLayerIfMissing(style, null) {
            FillLayer(LAYER_PUCK_ACCURACY, SOURCE_PUCK_ACCURACY).withProperties(
                PropertyFactory.fillColor(
                    if (isDarkTheme) 0x2939D2C0 else 0x240E5A58
                )
            )
        }
        addLayerIfMissing(style, null) {
            LineLayer(LAYER_PUCK_ACCURACY_RING, SOURCE_PUCK_ACCURACY).withProperties(
                PropertyFactory.lineColor(
                    if (isDarkTheme) 0x8839D2C0.toInt() else 0x880E5A58.toInt()
                ),
                PropertyFactory.lineWidth(1.5f)
            )
        }

        // Animated 60fps directional puck
        addLayerIfMissing(style, null) {
            SymbolLayer(LAYER_PUCK, SOURCE_PUCK).withProperties(
                PropertyFactory.iconImage(IMAGE_PUCK),
                PropertyFactory.iconSize(1.05f),
                PropertyFactory.iconRotate(Expression.get("bearing")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        }

        // Developer debug raw-fix metric accuracy ring + comparison dots (raw=red, kalman=cyan, snapped=green)
        addLayerIfMissing(style, null) {
            LineLayer(LAYER_DEBUG_ACCURACY_RING, SOURCE_DEBUG_ACCURACY).withProperties(
                PropertyFactory.lineColor("#AAFF5252"),
                PropertyFactory.lineWidth(2f)
            )
        }
        addLayerIfMissing(style, null) {
            CircleLayer(LAYER_DEBUG_POINTS, SOURCE_DEBUG_POINTS).withProperties(
                PropertyFactory.circleRadius(6f),
                PropertyFactory.circleColor(
                    Expression.match(
                        Expression.get("kind"),
                        Expression.literal("#FF5252"),
                        Expression.stop("kalman", "#00E5FF"),
                        Expression.stop("snapped", "#00E676")
                    )
                ),
                PropertyFactory.circleStrokeWidth(1.5f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP)
            )
        }
    }

    fun updateRoutes(
        style: Style,
        activeRoute: NavRoute?,
        alternativeRoutes: List<NavRoute>,
        selectedIndex: Int,
        traveledDistanceMeters: Double
    ) {
        val altFeatures = alternativeRoutes
            .filterIndexed { idx, _ -> idx != selectedIndex }
            .mapNotNull { route ->
                toLineFeature(route.geometry)
            }
        setCollection(style, SOURCE_ALT_ROUTES, FeatureCollection.fromFeatures(altFeatures))

        if (activeRoute == null) {
            setCollection(style, SOURCE_TRAVELED_ROUTE, FeatureCollection.fromFeatures(emptyList()))
            setCollection(style, SOURCE_ACTIVE_ROUTE, FeatureCollection.fromFeatures(emptyList()))
        } else {
            val split = RouteGeometrySplitter.splitAtDistance(activeRoute, traveledDistanceMeters)
            setCollection(
                style,
                SOURCE_TRAVELED_ROUTE,
                toFeatureCollection(split.traveled)
            )
            setCollection(
                style,
                SOURCE_ACTIVE_ROUTE,
                toFeatureCollection(split.remaining)
            )
        }
    }

    fun updatePins(style: Style, waypoints: List<Place>, droppedPin: Place?) {
        val features = buildList {
            waypoints.drop(1).forEach { place ->
                add(Feature.fromGeometry(Point.fromLngLat(place.location.longitude, place.location.latitude)))
            }
            droppedPin?.let { pin ->
                add(Feature.fromGeometry(Point.fromLngLat(pin.location.longitude, pin.location.latitude)))
            }
        }
        setCollection(style, SOURCE_PINS, FeatureCollection.fromFeatures(features))
    }

    fun updatePuck(style: Style, puck: PuckRenderState?) {
        if (puck == null) {
            setCollection(style, SOURCE_PUCK, FeatureCollection.fromFeatures(emptyList()))
            setCollection(style, SOURCE_PUCK_ACCURACY, FeatureCollection.fromFeatures(emptyList()))
            return
        }
        val pt = Point.fromLngLat(puck.position.longitude, puck.position.latitude)
        val puckFeature = Feature.fromGeometry(pt).apply {
            addNumberProperty("bearing", puck.bearingDegrees)
            addBooleanProperty("deadReckoning", puck.isDeadReckoning)
        }
        val accCircle = createCirclePolygonFeature(
            center = puck.position,
            radiusMeters = puck.accuracyMeters.toDouble().coerceIn(3.0, 250.0)
        )
        setCollection(style, SOURCE_PUCK, FeatureCollection.fromFeature(puckFeature))
        setCollection(style, SOURCE_PUCK_ACCURACY, FeatureCollection.fromFeature(accCircle))
    }

    fun updateDebugOverlay(
        style: Style,
        enabled: Boolean,
        debugData: DebugOverlayData?
    ) {
        if (!enabled || debugData == null) {
            setCollection(style, SOURCE_DEBUG_POINTS, FeatureCollection.fromFeatures(emptyList()))
            setCollection(style, SOURCE_DEBUG_ACCURACY, FeatureCollection.fromFeatures(emptyList()))
            return
        }
        val rawCircleFeatures = debugData.rawFix?.let { fix ->
            listOf(
                createCirclePolygonFeature(
                    center = fix.position,
                    radiusMeters = fix.accuracyMeters.toDouble().coerceIn(3.0, 250.0)
                )
            )
        } ?: emptyList()
        setCollection(style, SOURCE_DEBUG_ACCURACY, FeatureCollection.fromFeatures(rawCircleFeatures))

        val features = buildList {
            debugData.rawFix?.position?.let { raw ->
                add(Feature.fromGeometry(Point.fromLngLat(raw.longitude, raw.latitude)).apply {
                    addStringProperty("kind", "raw")
                })
            }
            debugData.kalmanPosition?.let { kalman ->
                add(Feature.fromGeometry(Point.fromLngLat(kalman.longitude, kalman.latitude)).apply {
                    addStringProperty("kind", "kalman")
                })
            }
            debugData.snappedPosition?.let { snapped ->
                add(Feature.fromGeometry(Point.fromLngLat(snapped.longitude, snapped.latitude)).apply {
                    addStringProperty("kind", "snapped")
                })
            }
        }
        setCollection(style, SOURCE_DEBUG_POINTS, FeatureCollection.fromFeatures(features))
    }

    private fun createCirclePolygonFeature(
        center: LatLng,
        radiusMeters: Double,
        segments: Int = 32
    ): Feature {
        val ring = ArrayList<Point>(segments + 1)
        for (i in 0 until segments) {
            val angle = 2.0 * Math.PI * i / segments
            val vertex = GeoMath.fromLocalEnMeters(
                anchor = center,
                eastMeters = sin(angle) * radiusMeters,
                northMeters = cos(angle) * radiusMeters
            )
            ring += Point.fromLngLat(vertex.longitude, vertex.latitude)
        }
        ring += ring.first()
        return Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
    }

    private fun toLineFeature(points: List<LatLng>): Feature? {
        if (points.size < 2) return null
        val geoPoints = points.map { Point.fromLngLat(it.longitude, it.latitude) }
        return Feature.fromGeometry(LineString.fromLngLats(geoPoints))
    }

    private fun toFeatureCollection(points: List<LatLng>): FeatureCollection {
        val f = toLineFeature(points)
        return if (f == null) FeatureCollection.fromFeatures(emptyList())
        else FeatureCollection.fromFeature(f)
    }

    private fun setCollection(style: Style, sourceId: String, fc: FeatureCollection) {
        style.getSourceAs<GeoJsonSource>(sourceId)?.setGeoJson(fc)
    }

    private fun ensureSource(style: Style, sourceId: String) {
        if (style.getSource(sourceId) == null) {
            style.addSource(GeoJsonSource(sourceId))
        }
    }

    private inline fun addLayerIfMissing(
        style: Style,
        belowLayerId: String?,
        factory: () -> Layer
    ) {
        val layer = factory()
        if (style.getLayer(layer.id) != null) return
        if (belowLayerId != null && style.getLayer(belowLayerId) != null) {
            style.addLayerBelow(layer, belowLayerId)
        } else {
            style.addLayer(layer)
        }
    }

    private fun findLabelAnchorLayerId(style: Style): String? {
        return style.layers.firstOrNull { layer ->
            layer is SymbolLayer || layer.id.contains("label") || layer.id == "building-3d"
        }?.id
    }

    private fun createPuckBitmap(): Bitmap {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x55000000
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy + 2f, 34f, shadowPaint)

        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, 31f, circlePaint)

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0E5A58.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        canvas.drawCircle(cx, cy, 29f, ringPaint)

        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0E5A58.toInt()
            style = Paint.Style.FILL
        }
        val arrowPath = Path().apply {
            moveTo(cx, cy - 20f)
            lineTo(cx + 15f, cy + 14f)
            lineTo(cx, cy + 7f)
            lineTo(cx - 15f, cy + 14f)
            close()
        }
        canvas.drawPath(arrowPath, arrowPaint)
        return bmp
    }

    private fun createPinBitmap(): Bitmap {
        val w = 72
        val h = 88
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = w / 2f

        val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFD92B2B.toInt()
            style = Paint.Style.FILL
        }
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.FILL
        }
        val tipPath = Path().apply {
            moveTo(cx, h - 4f)
            lineTo(cx - 16f, 44f)
            lineTo(cx + 16f, 44f)
            close()
        }
        canvas.drawCircle(cx, 32f, 24f, pinPaint)
        canvas.drawPath(tipPath, pinPaint)
        canvas.drawCircle(cx, 32f, 9f, dotPaint)
        return bmp
    }

    companion object {
        const val SOURCE_ALT_ROUTES = "halatuju-alt-routes-src"
        const val SOURCE_TRAVELED_ROUTE = "halatuju-traveled-route-src"
        const val SOURCE_ACTIVE_ROUTE = "halatuju-active-route-src"
        const val SOURCE_PINS = "halatuju-pins-src"
        const val SOURCE_PUCK_ACCURACY = "halatuju-puck-acc-src"
        const val SOURCE_PUCK = "halatuju-puck-src"
        const val SOURCE_DEBUG_ACCURACY = "halatuju-debug-acc-src"
        const val SOURCE_DEBUG_POINTS = "halatuju-debug-pts-src"

        const val LAYER_ALT_ROUTES = "halatuju-alt-routes-layer"
        const val LAYER_TRAVELED_ROUTE = "halatuju-traveled-route-layer"
        const val LAYER_ACTIVE_ROUTE_CASING = "halatuju-active-route-casing-layer"
        const val LAYER_ACTIVE_ROUTE = "halatuju-active-route-layer"
        const val LAYER_PINS = "halatuju-pins-layer"
        const val LAYER_PUCK_ACCURACY = "halatuju-puck-acc-layer"
        const val LAYER_PUCK_ACCURACY_RING = "halatuju-puck-acc-ring-layer"
        const val LAYER_PUCK = "halatuju-puck-layer"
        const val LAYER_DEBUG_ACCURACY_RING = "halatuju-debug-acc-ring-layer"
        const val LAYER_DEBUG_POINTS = "halatuju-debug-pts-layer"

        const val IMAGE_PUCK = "halatuju-puck-img"
        const val IMAGE_PIN = "halatuju-pin-img"
    }
}
