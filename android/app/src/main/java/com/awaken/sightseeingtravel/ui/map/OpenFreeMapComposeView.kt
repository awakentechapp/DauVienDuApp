package com.awaken.sightseeingtravel.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.awaken.sightseeingtravel.core.model.PoiPinSummaryDto
import com.awaken.sightseeingtravel.core.sensor.DeviceSpatialPose
import com.awaken.sightseeingtravel.core.sensor.LocationAndOrientationTracker
import com.awaken.sightseeingtravel.ui.ActiveNavigationRoute
import com.google.gson.JsonObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.abs

/**
 * Official OpenFreeMap/Bright Vector Tile Style URL for Android MapLibre.
 */
const val OPENFREEMAP_BRIGHT_STYLE_URL = "https://tiles.openfreemap.org/styles/bright"

/**
 * Native MapLibre GL Compose View (`org.maplibre.gl:android-sdk`) configured with:
 * - Vector tile style: `OpenFreeMap/Bright` (`https://tiles.openfreemap.org/styles/bright`)
 * - Automatic OpenStreetMap Raster Tile JSON fallback if vector tiles are unreachable
 * - Real-time GPS Location Marker + Published POI Heritage & Food (`Ăn uống`) Pins within 50km radius
 */
@Composable
fun OpenFreeMapComposeView(
    spatialPose: DeviceSpatialPose,
    pois: List<PoiPinSummaryDto>,
    selectedPoiId: String?,
    recenterTrigger: Int,
    focusedPoiId: String?,
    focusTrigger: Int,
    navigationRoute: ActiveNavigationRoute?,
    onPoiMarkerClick: (PoiPinSummaryDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Initialize MapLibre singleton once per process
    remember(context) {
        MapLibre.getInstance(context.applicationContext)
    }

    val mapView = remember {
        MapView(context).apply {
            onCreate(Bundle())
        }
    }

    var mapLibreInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    var isStyleReady by remember { mutableStateOf(false) }
    var hasCenteredInitially by remember { mutableStateOf(false) }

    val currentPois by rememberUpdatedState(pois)
    val currentNavigationRoute by rememberUpdatedState(navigationRoute)
    val currentOnPoiClick by rememberUpdatedState(onPoiMarkerClick)

    // Bind MapView lifecycle to Compose LifecycleOwner
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    // Recenter camera when requested by user button
    LaunchedEffect(recenterTrigger, mapLibreInstance) {
        if (recenterTrigger > 0) {
            mapLibreInstance?.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(spatialPose.latitude, spatialPose.longitude))
                        .zoom(14.8)
                        .bearing(spatialPose.azimuthDegrees.toDouble())
                        .tilt(30.0)
                        .build()
                ),
                900
            )
        }
    }

    // Focus the requested POI even when it was selected from a list outside the map.
    LaunchedEffect(focusTrigger, focusedPoiId, mapLibreInstance, isStyleReady) {
        val map = mapLibreInstance ?: return@LaunchedEffect
        if (!isStyleReady || focusedPoiId == null) return@LaunchedEffect
        val focusedPoi = pois.firstOrNull { it.id == focusedPoiId } ?: return@LaunchedEffect

        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(focusedPoi.latitude, focusedPoi.longitude))
                    .zoom(15.5)
                    .tilt(25.0)
                    .build()
            ),
            750
        )
    }

    LaunchedEffect(navigationRoute?.routeId, mapLibreInstance, isStyleReady) {
        val route = navigationRoute ?: return@LaunchedEffect
        val map = mapLibreInstance ?: return@LaunchedEffect
        if (!isStyleReady || route.coordinates.isEmpty()) return@LaunchedEffect

        val points = route.coordinates.map { LatLng(it.latitude, it.longitude) }
        if (points.size >= 2) {
            val boundsBuilder = LatLngBounds.Builder()
            points.forEach { boundsBuilder.include(it) }
            map.animateCamera(
                CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 96),
                850
            )
        } else {
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(points.first(), 15.0),
                750
            )
        }
    }

    // Update GeoJSON sources whenever POIs, selectedPOI, or GPS pose changes
    LaunchedEffect(spatialPose.latitude, spatialPose.longitude, pois, selectedPoiId, focusedPoiId, navigationRoute, isStyleReady) {
        val map = mapLibreInstance ?: return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect
        if (!isStyleReady) return@LaunchedEffect

        updateGeoJsonLayers(
            style = style,
            spatialPose = spatialPose,
            pois = pois,
            selectedPoiId = selectedPoiId,
            navigationRoute = navigationRoute
        )

        if (!hasCenteredInitially) {
            hasCenteredInitially = true
            val focusedPoi = pois.firstOrNull { it.id == focusedPoiId }
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    focusedPoi?.let { LatLng(it.latitude, it.longitude) }
                        ?: LatLng(spatialPose.latitude, spatialPose.longitude),
                    if (focusedPoi != null) 15.5 else 14.5
                ),
                750
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                mapView.apply {
                    // Fallback to OpenStreetMap if OpenFreeMap/Bright style fails to load
                    addOnDidFailLoadingMapListener {
                        mapLibreInstance?.setStyle(
                            Style.Builder().fromJson(buildOpenStreetMapFallbackStyleJson())
                        ) { fallbackStyle ->
                            setupCustomPinBitmapsAndLayers(context, fallbackStyle)
                            isStyleReady = true
                            updateGeoJsonLayers(
                                style = fallbackStyle,
                                spatialPose = spatialPose,
                                pois = currentPois,
                                selectedPoiId = selectedPoiId,
                                navigationRoute = currentNavigationRoute
                            )
                        }
                    }

                    getMapAsync { map ->
                        mapLibreInstance = map
                        map.uiSettings.isCompassEnabled = true
                        map.uiSettings.isRotateGesturesEnabled = true
                        map.uiSettings.isTiltGesturesEnabled = true
                        map.uiSettings.isAttributionEnabled = true

                        loadOpenFreeMapBrightStyle(
                            context = context,
                            map = map,
                            onStyleReady = { style ->
                                isStyleReady = true
                                updateGeoJsonLayers(
                                    style = style,
                                    spatialPose = spatialPose,
                                    pois = currentPois,
                                    selectedPoiId = selectedPoiId,
                                    navigationRoute = currentNavigationRoute
                                )
                            }
                        )

                        map.addOnMapClickListener { tapLatLng ->
                            val screenPoint = map.projection.toScreenLocation(tapLatLng)
                            val features = map.queryRenderedFeatures(screenPoint, LAYER_POI_PINS)
                            val tappedId = features.firstOrNull()?.getStringProperty(PROP_POI_ID)
                            if (tappedId != null) {
                                currentPois.firstOrNull { it.id == tappedId }?.let { matched ->
                                    currentOnPoiClick(matched)
                                    return@addOnMapClickListener true
                                }
                            }
                            // Fallback proximity check if symbol hitbox was slightly missed
                            val closest = currentPois.minByOrNull { pin ->
                                abs(pin.latitude - tapLatLng.latitude) + abs(pin.longitude - tapLatLng.longitude)
                            }
                            if (closest != null &&
                                abs(closest.latitude - tapLatLng.latitude) < 0.0008 &&
                                abs(closest.longitude - tapLatLng.longitude) < 0.0008
                            ) {
                                currentOnPoiClick(closest)
                                true
                            } else {
                                false
                            }
                        }
                    }
                }
            }
        )
    }
}

private const val SOURCE_USER_GPS = "source-user-gps"
private const val LAYER_USER_GPS = "layer-user-gps"
private const val ICON_USER_GPS = "icon-user-gps"

private const val SOURCE_POI_PINS = "source-poi-pins"
private const val LAYER_POI_PINS = "layer-poi-pins"
private const val ICON_POI_LOCKED = "icon-poi-locked"
private const val ICON_POI_FOOD = "icon-poi-food"
private const val ICON_POI_UNLOCKED = "icon-poi-unlocked"
private const val ICON_POI_SELECTED = "icon-poi-selected"

private const val SOURCE_NAVIGATION_ROUTE = "source-navigation-route"
private const val LAYER_NAVIGATION_ROUTE = "layer-navigation-route"

private const val PROP_POI_ID = "poi_id"
private const val PROP_POI_TITLE = "poi_title"
private const val PROP_POI_ICON = "poi_icon"

/**
 * Loads `OpenFreeMap/Bright` vector style (`https://tiles.openfreemap.org/styles/bright`).
 */
private fun loadOpenFreeMapBrightStyle(
    context: Context,
    map: MapLibreMap,
    onStyleReady: (Style) -> Unit
) {
    map.setStyle(Style.Builder().fromUri(OPENFREEMAP_BRIGHT_STYLE_URL)) { style ->
        setupCustomPinBitmapsAndLayers(context, style)
        onStyleReady(style)
    }
}

/**
 * Inline OpenStreetMap Raster Style JSON specification used as a resilient fallback.
 */
fun buildOpenStreetMapFallbackStyleJson(): String = """
{
  "version": 8,
  "name": "OpenFreeMap/Bright - OSM Fallback",
  "sources": {
    "osm-raster-tiles": {
      "type": "raster",
      "tiles": [
        "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
      ],
      "tileSize": 256,
      "attribution": "© OpenFreeMap/Bright • © OpenStreetMap contributors"
    }
  },
  "layers": [
    {
      "id": "osm-raster-layer",
      "type": "raster",
      "source": "osm-raster-tiles",
      "minzoom": 0,
      "maxzoom": 19
    }
  ]
}
""".trimIndent()

private fun setupCustomPinBitmapsAndLayers(context: Context, style: Style) {
    val density = context.resources.displayMetrics.density
    style.addImage(ICON_USER_GPS, createUserGpsDotBitmap(density))
    style.addImage(ICON_POI_LOCKED, createPoiPinBitmap(density, fillColor = Color.parseColor("#0284C7"), ringColor = Color.WHITE))
    style.addImage(ICON_POI_FOOD, createPoiPinBitmap(density, fillColor = Color.parseColor("#E11D48"), ringColor = Color.parseColor("#FFE4E6")))
    style.addImage(ICON_POI_UNLOCKED, createPoiPinBitmap(density, fillColor = Color.parseColor("#16A34A"), ringColor = Color.parseColor("#BBF7D0")))
    style.addImage(ICON_POI_SELECTED, createPoiPinBitmap(density, fillColor = Color.parseColor("#EA580C"), ringColor = Color.parseColor("#FEF08A")))

    if (style.getSource(SOURCE_NAVIGATION_ROUTE) == null) {
        style.addSource(GeoJsonSource(SOURCE_NAVIGATION_ROUTE))
    }
    if (style.getLayer(LAYER_NAVIGATION_ROUTE) == null) {
        style.addLayer(
            LineLayer(LAYER_NAVIGATION_ROUTE, SOURCE_NAVIGATION_ROUTE).withProperties(
                lineColor(Color.parseColor("#2563EB")),
                lineWidth(5.0f),
                lineOpacity(0.9f)
            )
        )
    }

    if (style.getSource(SOURCE_USER_GPS) == null) {
        style.addSource(GeoJsonSource(SOURCE_USER_GPS))
    }
    if (style.getLayer(LAYER_USER_GPS) == null) {
        style.addLayer(
            SymbolLayer(LAYER_USER_GPS, SOURCE_USER_GPS).withProperties(
                iconImage(ICON_USER_GPS),
                iconAllowOverlap(true),
                iconIgnorePlacement(true)
            )
        )
    }

    if (style.getSource(SOURCE_POI_PINS) == null) {
        style.addSource(GeoJsonSource(SOURCE_POI_PINS))
    }
    if (style.getLayer(LAYER_POI_PINS) == null) {
        style.addLayer(
            SymbolLayer(LAYER_POI_PINS, SOURCE_POI_PINS).withProperties(
                iconImage("{$PROP_POI_ICON}"),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                textField("{$PROP_POI_TITLE}"),
                textSize(12.5f),
                textOffset(arrayOf(0f, 1.8f)),
                textColor(Color.parseColor("#0F172A")),
                textHaloColor(Color.WHITE),
                textHaloWidth(1.8f)
            )
        )
    }
}

private fun updateGeoJsonLayers(
    style: Style,
    spatialPose: DeviceSpatialPose,
    pois: List<PoiPinSummaryDto>,
    selectedPoiId: String?,
    navigationRoute: ActiveNavigationRoute?
) {
    val routeSource = style.getSourceAs<GeoJsonSource>(SOURCE_NAVIGATION_ROUTE)
    val routePoints = navigationRoute?.coordinates.orEmpty().map {
        Point.fromLngLat(it.longitude, it.latitude)
    }
    if (routePoints.size >= 2) {
        routeSource?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(routePoints)))
    } else {
        routeSource?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
    }

    val gpsSource = style.getSourceAs<GeoJsonSource>(SOURCE_USER_GPS)
    gpsSource?.setGeoJson(
        Feature.fromGeometry(Point.fromLngLat(spatialPose.longitude, spatialPose.latitude))
    )

    val poiSource = style.getSourceAs<GeoJsonSource>(SOURCE_POI_PINS)
    val features = pois.map { poi ->
        val liveDistance = LocationAndOrientationTracker.calculateDistanceMeters(
            spatialPose.latitude,
            spatialPose.longitude,
            poi.latitude,
            poi.longitude
        )
        val hasFreshGps = spatialPose.hasFreshGpsFix &&
            !spatialPose.isSimulatedLocation && spatialPose.accuracyMeters <= 50f
        val isUnlocked = hasFreshGps && poi.canUnlockAr(liveDistance)
        val isFood = poi.category.contains("Ăn uống", ignoreCase = true) ||
            poi.category.contains("Food", ignoreCase = true) ||
            poi.category.contains("Ẩm thực", ignoreCase = true)
        val iconKey = when {
            poi.id == selectedPoiId -> ICON_POI_SELECTED
            isUnlocked -> ICON_POI_UNLOCKED
            isFood -> ICON_POI_FOOD
            else -> ICON_POI_LOCKED
        }
        val props = JsonObject().apply {
            addProperty(PROP_POI_ID, poi.id)
            addProperty(PROP_POI_TITLE, poi.title)
            addProperty(PROP_POI_ICON, iconKey)
        }
        Feature.fromGeometry(Point.fromLngLat(poi.longitude, poi.latitude), props)
    }
    poiSource?.setGeoJson(FeatureCollection.fromFeatures(features))
}

private fun createUserGpsDotBitmap(density: Float): Bitmap {
    val sizePx = (28 * density).toInt().coerceAtLeast(28)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = sizePx / 2f

    val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(75, 37, 99, 235)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, center, haloPaint)

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, center * 0.62f, borderPaint)

    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2563EB")
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, center * 0.46f, dotPaint)

    return bitmap
}

private fun createPoiPinBitmap(density: Float, fillColor: Int, ringColor: Int): Bitmap {
    val sizePx = (38 * density).toInt().coerceAtLeast(38)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = sizePx / 2f

    val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ringColor
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, center * 0.88f, outerPaint)

    val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, center * 0.68f, innerPaint)

    val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, center * 0.26f, corePaint)

    return bitmap
}
