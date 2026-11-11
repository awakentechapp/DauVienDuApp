package com.awaken.sightseeingtravel.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
import com.awaken.sightseeingtravel.core.model.PoiCategoryLabels
import com.awaken.sightseeingtravel.core.sensor.DeviceSpatialPose
import com.awaken.sightseeingtravel.core.sensor.LocationAndOrientationTracker
import com.awaken.sightseeingtravel.ui.ActiveNavigationRoute
import com.awaken.sightseeingtravel.ui.NavigationTravelMode
import com.google.gson.JsonObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** OpenFreeMap Liberty includes vector building data with 3D building extrusion. */
const val OPENFREEMAP_3D_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

private const val MAP_3D_DEFAULT_TILT_DEGREES = 55.0

/**
 * Native MapLibre GL Compose View (`org.maplibre.gl:android-sdk`) configured with:
 * - Vector tile style: `OpenFreeMap/Liberty` with 3D building extrusion
 * - Default camera tilt: 55 degrees so 3D buildings are visible at close zoom levels
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
    navigationTravelMode: NavigationTravelMode,
    isNavigationActive: Boolean,
    navigationSessionKey: String?,
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
    val currentNavigationTravelMode by rememberUpdatedState(navigationTravelMode)
    val currentIsNavigationActive by rememberUpdatedState(isNavigationActive)
    val currentSelectedPoiId by rememberUpdatedState(selectedPoiId)
    val currentSpatialPose by rememberUpdatedState(spatialPose)
    val currentOnPoiClick by rememberUpdatedState(onPoiMarkerClick)
    val density = context.resources.displayMetrics.density
    var fittedNavigationSession by remember { mutableStateOf<String?>(null) }

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
                        .bearing(spatialPose.courseDegrees.toDouble())
                        .tilt(MAP_3D_DEFAULT_TILT_DEGREES)
                        .build()
                ),
                900
            )
        }
    }

    // Focus the requested POI even when it was selected from a list outside the map.
    LaunchedEffect(focusTrigger, focusedPoiId, pois, mapLibreInstance, isStyleReady) {
        val map = mapLibreInstance ?: return@LaunchedEffect
        if (!isStyleReady || focusedPoiId == null) return@LaunchedEffect
        val focusedPoi = pois.firstOrNull { it.id == focusedPoiId } ?: return@LaunchedEffect

        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(focusedPoi.latitude, focusedPoi.longitude))
                    .zoom(15.5)
                    .tilt(MAP_3D_DEFAULT_TILT_DEGREES)
                    .build()
            ),
            750
        )
    }

    LaunchedEffect(navigationRoute?.routeId, navigationSessionKey, mapLibreInstance, isStyleReady) {
        val sessionKey = navigationSessionKey
        if (sessionKey == null) {
            fittedNavigationSession = null
            return@LaunchedEffect
        }
        if (fittedNavigationSession == sessionKey) return@LaunchedEffect
        val route = navigationRoute ?: return@LaunchedEffect
        val map = mapLibreInstance ?: return@LaunchedEffect
        if (!isStyleReady || route.coordinates.isEmpty()) return@LaunchedEffect

        fittedNavigationSession = sessionKey
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(spatialPose.latitude, spatialPose.longitude))
                    .zoom(16.8)
                    .bearing(spatialPose.courseDegrees.toDouble())
                    .tilt(MAP_3D_DEFAULT_TILT_DEGREES)
                    .build()
            ),
            750
        )
    }

    // Keep the user's live location centered while navigation is active, as in turn-by-turn map apps.
    LaunchedEffect(
        spatialPose.latitude,
        spatialPose.longitude,
        spatialPose.courseDegrees,
        spatialPose.hasFreshGpsFix,
        isNavigationActive,
        mapLibreInstance,
        isStyleReady
    ) {
        val map = mapLibreInstance ?: return@LaunchedEffect
        if (!isStyleReady || !isNavigationActive || !spatialPose.hasFreshGpsFix) return@LaunchedEffect

        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(spatialPose.latitude, spatialPose.longitude))
                    .zoom(17.0)
                    .bearing(spatialPose.courseDegrees.toDouble())
                    .tilt(MAP_3D_DEFAULT_TILT_DEGREES)
                    .build()
            ),
            650
        )
    }

    // Update GeoJSON sources whenever POIs, selectedPOI, or GPS pose changes
    LaunchedEffect(
        spatialPose.latitude,
        spatialPose.longitude,
        spatialPose.courseDegrees,
        spatialPose.hasFreshGpsFix,
        spatialPose.isSimulatedLocation,
        pois,
        selectedPoiId,
        focusedPoiId,
        navigationRoute,
        navigationTravelMode,
        isNavigationActive,
        isStyleReady
    ) {
        val map = mapLibreInstance ?: return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect
        if (!isStyleReady) return@LaunchedEffect

        updateGeoJsonLayers(
            style = style,
            spatialPose = spatialPose,
            pois = pois,
            selectedPoiId = selectedPoiId,
            navigationRoute = navigationRoute,
            navigationTravelMode = navigationTravelMode,
            isNavigationActive = isNavigationActive
        )

        if (!hasCenteredInitially) {
            hasCenteredInitially = true
            val focusedPoi = pois.firstOrNull { it.id == focusedPoiId }
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(
                            focusedPoi?.let { LatLng(it.latitude, it.longitude) }
                                ?: LatLng(spatialPose.latitude, spatialPose.longitude)
                        )
                        .zoom(if (focusedPoi != null) 15.5 else 14.5)
                        .tilt(MAP_3D_DEFAULT_TILT_DEGREES)
                        .build()
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
                    // Raster fallback keeps the map usable if OpenFreeMap Liberty cannot load.
                    addOnDidFailLoadingMapListener {
                        mapLibreInstance?.setStyle(
                            Style.Builder().fromJson(buildOpenStreetMapFallbackStyleJson())
                        ) { fallbackStyle ->
                            setupCustomPinBitmapsAndLayers(context, fallbackStyle)
                            isStyleReady = true
                            updateGeoJsonLayers(
                                style = fallbackStyle,
                                spatialPose = currentSpatialPose,
                                pois = currentPois,
                                selectedPoiId = currentSelectedPoiId,
                                navigationRoute = currentNavigationRoute,
                                navigationTravelMode = currentNavigationTravelMode,
                                isNavigationActive = currentIsNavigationActive
                            )
                        }
                    }

                    getMapAsync { map ->
                        mapLibreInstance = map
                        map.uiSettings.isCompassEnabled = true
                        map.uiSettings.isRotateGesturesEnabled = true
                        map.uiSettings.isTiltGesturesEnabled = true
                        map.uiSettings.isAttributionEnabled = true

                        loadOpenFreeMapLibertyStyle(
                            context = context,
                            map = map,
                            onStyleReady = { style ->
                                isStyleReady = true
                                updateGeoJsonLayers(
                                    style = style,
                                    spatialPose = currentSpatialPose,
                                    pois = currentPois,
                                    selectedPoiId = currentSelectedPoiId,
                                    navigationRoute = currentNavigationRoute,
                                    navigationTravelMode = currentNavigationTravelMode,
                                    isNavigationActive = currentIsNavigationActive
                                )
                            }
                        )

                        map.addOnMapClickListener { tapLatLng ->
                            val screenPoint = map.projection.toScreenLocation(tapLatLng)
                            val features = map.queryRenderedFeatures(
                                screenPoint,
                                LAYER_POI_SELECTED,
                                LAYER_POI_PINS
                            )
                            val tappedId = features.firstOrNull()?.getStringProperty(PROP_POI_ID)
                            if (tappedId != null) {
                                currentPois.firstOrNull { it.id == tappedId }?.let { matched ->
                                    currentOnPoiClick(matched)
                                    return@addOnMapClickListener true
                                }
                            }
                            // Use screen-space distance so the hit target stays consistent at every zoom level.
                            val closest = currentPois
                                .map { pin ->
                                    val pinPoint = map.projection.toScreenLocation(
                                        LatLng(pin.latitude, pin.longitude)
                                    )
                                    val dx = pinPoint.x - screenPoint.x
                                    val dy = pinPoint.y - screenPoint.y
                                    pin to (dx * dx + dy * dy)
                                }
                                .minByOrNull { it.second }
                            val maxTapDistancePx = 48f * density
                            if (closest != null && closest.second <= maxTapDistancePx * maxTapDistancePx) {
                                currentOnPoiClick(closest.first)
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
private const val ICON_NAVIGATION_WALKING = "icon-navigation-walking"
private const val ICON_NAVIGATION_CAR = "icon-navigation-car"
private const val ICON_NAVIGATION_MOTORCYCLE = "icon-navigation-motorcycle"

private const val SOURCE_POI_PINS = "source-poi-pins"
private const val LAYER_POI_PINS = "layer-poi-pins"
private const val SOURCE_POI_SELECTED = "source-poi-selected"
private const val LAYER_POI_SELECTED = "layer-poi-selected"
private const val ICON_POI_LOCKED = "icon-poi-locked"
private const val ICON_POI_FOOD = "icon-poi-food"
private const val ICON_POI_UNLOCKED = "icon-poi-unlocked"
private const val ICON_POI_SELECTED = "icon-poi-selected"

private const val SOURCE_NAVIGATION_ROUTE = "source-navigation-route"
private const val LAYER_NAVIGATION_ROUTE = "layer-navigation-route"

private const val PROP_POI_ID = "poi_id"
private const val PROP_POI_TITLE = "poi_title"
private const val PROP_POI_ICON = "poi_icon"
private const val PROP_GPS_ICON = "gps_icon"
private const val PROP_GPS_COURSE = "gps_course"

/**
 * Loads OpenFreeMap Liberty, whose style includes the `building-3d` extrusion layer.
 */
private fun loadOpenFreeMapLibertyStyle(
    context: Context,
    map: MapLibreMap,
    onStyleReady: (Style) -> Unit
) {
    map.setStyle(Style.Builder().fromUri(OPENFREEMAP_3D_STYLE_URL)) { style ->
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
  "name": "OpenStreetMap Raster Fallback",
  "sources": {
    "osm-raster-tiles": {
      "type": "raster",
      "tiles": [
        "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
      ],
      "tileSize": 256,
      "attribution": "© OpenStreetMap contributors"
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
    style.addImage(ICON_NAVIGATION_WALKING, createNavigationMarkerBitmap(density, NavigationTravelMode.WALKING))
    style.addImage(ICON_NAVIGATION_CAR, createNavigationMarkerBitmap(density, NavigationTravelMode.CAR))
    style.addImage(ICON_NAVIGATION_MOTORCYCLE, createNavigationMarkerBitmap(density, NavigationTravelMode.MOTORCYCLE))
    style.addImage(ICON_POI_LOCKED, createPoiPinBitmap(density, fillColor = Color.parseColor("#622821"), ringColor = Color.parseColor("#F6DCAD")))
    style.addImage(ICON_POI_FOOD, createPoiPinBitmap(density, fillColor = Color.parseColor("#9A4333"), ringColor = Color.parseColor("#FFF9F0")))
    style.addImage(ICON_POI_UNLOCKED, createPoiPinBitmap(density, fillColor = Color.parseColor("#9A4333"), ringColor = Color.parseColor("#F6DCAD")))
    style.addImage(ICON_POI_SELECTED, createPoiPinBitmap(density, fillColor = Color.parseColor("#3F1D18"), ringColor = Color.parseColor("#F6DCAD")))

    if (style.getSource(SOURCE_NAVIGATION_ROUTE) == null) {
        style.addSource(GeoJsonSource(SOURCE_NAVIGATION_ROUTE))
    }
    if (style.getLayer(LAYER_NAVIGATION_ROUTE) == null) {
        style.addLayer(
            LineLayer(LAYER_NAVIGATION_ROUTE, SOURCE_NAVIGATION_ROUTE).withProperties(
                lineColor(Color.parseColor("#9A4333")),
                lineWidth(5.0f),
                lineOpacity(0.9f)
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
                iconPadding(14f),
                textField("{$PROP_POI_TITLE}"),
                textSize(12.5f),
                textFont(arrayOf("Noto Sans Regular")),
                textOffset(arrayOf(0f, 1.8f)),
                textColor(Color.parseColor("#622821")),
                textHaloColor(Color.parseColor("#FFF9F0")),
                textHaloWidth(2.8f)
            )
        )
    }

    if (style.getSource(SOURCE_POI_SELECTED) == null) {
        style.addSource(GeoJsonSource(SOURCE_POI_SELECTED))
    }
    if (style.getLayer(LAYER_POI_SELECTED) == null) {
        style.addLayer(
            SymbolLayer(LAYER_POI_SELECTED, SOURCE_POI_SELECTED).withProperties(
                iconImage(ICON_POI_SELECTED),
                iconSize(1.28f),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                textField("{$PROP_POI_TITLE}"),
                textSize(13.5f),
                textFont(arrayOf("Noto Sans Bold")),
                textOffset(arrayOf(0f, 2.2f)),
                textColor(Color.parseColor("#622821")),
                textHaloColor(Color.WHITE),
                textHaloWidth(1.4f)
            )
        )
    }

    // Add GPS last so both the live location and route icon remain above POI markers.
    if (style.getSource(SOURCE_USER_GPS) == null) {
        style.addSource(GeoJsonSource(SOURCE_USER_GPS))
    }
    if (style.getLayer(LAYER_USER_GPS) == null) {
        style.addLayer(
            SymbolLayer(LAYER_USER_GPS, SOURCE_USER_GPS).withProperties(
                iconImage("{$PROP_GPS_ICON}"),
                iconRotate(Expression.get(PROP_GPS_COURSE)),
                iconRotationAlignment("map"),
                iconAllowOverlap(true),
                iconIgnorePlacement(true)
            )
        )
    }
}

private fun updateGeoJsonLayers(
    style: Style,
    spatialPose: DeviceSpatialPose,
    pois: List<PoiPinSummaryDto>,
    selectedPoiId: String?,
    navigationRoute: ActiveNavigationRoute?,
    navigationTravelMode: NavigationTravelMode,
    isNavigationActive: Boolean
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
    if (spatialPose.hasFreshGpsFix || spatialPose.isSimulatedLocation) {
        val navigationIcon = when (navigationTravelMode) {
            NavigationTravelMode.WALKING -> ICON_NAVIGATION_WALKING
            NavigationTravelMode.CAR -> ICON_NAVIGATION_CAR
            NavigationTravelMode.MOTORCYCLE -> ICON_NAVIGATION_MOTORCYCLE
        }
        val gpsProperties = JsonObject().apply {
            addProperty(PROP_GPS_ICON, if (isNavigationActive) navigationIcon else ICON_USER_GPS)
            addProperty(PROP_GPS_COURSE, spatialPose.courseDegrees.toDouble())
        }
        gpsSource?.setGeoJson(
            Feature.fromGeometry(
                Point.fromLngLat(spatialPose.longitude, spatialPose.latitude),
                gpsProperties
            )
        )
    } else {
        gpsSource?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
    }

    val selectedPoi = pois.firstOrNull { it.id == selectedPoiId }
    val poiSource = style.getSourceAs<GeoJsonSource>(SOURCE_POI_PINS)
    val features = pois.filterNot { it.id == selectedPoiId }.map { poi ->
        val liveDistance = LocationAndOrientationTracker.calculateDistanceMeters(
            spatialPose.latitude,
            spatialPose.longitude,
            poi.latitude,
            poi.longitude
        )
        val hasFreshGps = spatialPose.hasFreshGpsFix &&
            !spatialPose.isSimulatedLocation && spatialPose.accuracyMeters <= 50f
        val isUnlocked = hasFreshGps && poi.canUnlockAr(liveDistance)
        val isFood = PoiCategoryLabels.isFood(poi.category)
        val iconKey = when {
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

    val selectedSource = style.getSourceAs<GeoJsonSource>(SOURCE_POI_SELECTED)
    if (selectedPoi != null) {
        val selectedProperties = JsonObject().apply {
            addProperty(PROP_POI_ID, selectedPoi.id)
            addProperty(PROP_POI_TITLE, selectedPoi.title)
        }
        selectedSource?.setGeoJson(
            Feature.fromGeometry(
                Point.fromLngLat(selectedPoi.longitude, selectedPoi.latitude),
                selectedProperties
            )
        )
    } else {
        selectedSource?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
    }
}

private fun createUserGpsDotBitmap(density: Float): Bitmap {
    val sizePx = (28 * density).toInt().coerceAtLeast(28)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = sizePx / 2f

    val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(75, 98, 40, 33)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, center, haloPaint)

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F6DCAD")
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, center * 0.62f, borderPaint)

    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#622821")
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, center * 0.46f, dotPaint)

    return bitmap
}

private fun createNavigationMarkerBitmap(density: Float, mode: NavigationTravelMode): Bitmap {
    val sizePx = (50 * density).toInt().coerceAtLeast(50)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = sizePx / 2f
    val brand = Color.parseColor("#622821")

    canvas.drawCircle(
        center,
        center,
        center * 0.96f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(55, 63, 29, 24)
            style = Paint.Style.FILL
        }
    )
    canvas.drawCircle(
        center,
        center,
        center * 0.87f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F6DCAD")
            style = Paint.Style.FILL
        }
    )
    canvas.drawCircle(
        center,
        center,
        center * 0.74f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = brand
            style = Paint.Style.FILL
        }
    )

    val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F6DCAD")
        style = Paint.Style.STROKE
        strokeWidth = sizePx * 0.065f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val fillGlyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F6DCAD")
        style = Paint.Style.FILL
    }
    val path = Path()

    when (mode) {
        NavigationTravelMode.WALKING -> {
            canvas.drawCircle(center, center * 0.46f, center * 0.105f, fillGlyph)
            canvas.drawLine(center, center * 0.59f, center, center * 1.05f, glyph)
            canvas.drawLine(center, center * 0.72f, center * 0.72f, center * 0.91f, glyph)
            canvas.drawLine(center, center * 0.72f, center * 1.28f, center * 0.91f, glyph)
            canvas.drawLine(center, center * 1.03f, center * 0.77f, center * 1.35f, glyph)
            canvas.drawLine(center, center * 1.03f, center * 1.23f, center * 1.35f, glyph)
        }

        NavigationTravelMode.CAR -> {
            val body = RectF(center * 0.58f, center * 0.39f, center * 1.42f, center * 1.58f)
            canvas.drawRoundRect(body, center * 0.20f, center * 0.20f, fillGlyph)
            canvas.drawRoundRect(
                RectF(center * 0.72f, center * 0.61f, center * 1.28f, center * 0.91f),
                center * 0.08f,
                center * 0.08f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = brand
                    style = Paint.Style.FILL
                }
            )
            canvas.drawRoundRect(
                RectF(center * 0.75f, center * 1.04f, center * 1.25f, center * 1.34f),
                center * 0.06f,
                center * 0.06f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = brand
                    style = Paint.Style.FILL
                }
            )
        }

        NavigationTravelMode.MOTORCYCLE -> {
            val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#F6DCAD")
                style = Paint.Style.STROKE
                strokeWidth = sizePx * 0.055f
            }
            canvas.drawCircle(center, center * 0.48f, center * 0.12f, wheelPaint)
            canvas.drawCircle(center, center * 1.48f, center * 0.12f, wheelPaint)
            path.moveTo(center, center * 0.48f)
            path.lineTo(center * 0.72f, center * 0.93f)
            path.lineTo(center, center * 1.21f)
            path.lineTo(center * 1.28f, center * 0.77f)
            path.lineTo(center * 1.48f, center * 1.48f)
            path.moveTo(center * 0.72f, center * 0.93f)
            path.lineTo(center * 0.5f, center * 1.40f)
            canvas.drawPath(path, glyph)
            canvas.drawRoundRect(
                RectF(center * 0.82f, center * 0.68f, center * 1.18f, center * 0.86f),
                center * 0.08f,
                center * 0.08f,
                fillGlyph
            )
        }
    }

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
