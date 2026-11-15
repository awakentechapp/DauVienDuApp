package com.awaken.sightseeingtravel.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
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

/** Dấu Viễn Du's branded OpenFreeMap vector style retains 3D building extrusion. */
const val OPENFREEMAP_3D_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

private const val MAP_3D_DEFAULT_TILT_DEGREES = 55.0

/**
 * Native MapLibre GL Compose View (`org.maplibre.gl:android-sdk`) configured with:
 * - Vector tile style: `OpenFreeMap/Liberty` with 3D building extrusion
 * - Default camera tilt: 55 degrees so 3D buildings are visible at close zoom levels
 * - Automatic OpenStreetMap Raster Tile JSON fallback if vector tiles are unreachable
 * - Real-time GPS Location Marker + Published POI Heritage & Food (`Ăn uống`) Pins within 15km radius
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
    compassResetTrigger: Int,
    onCameraBearingChanged: (Float) -> Unit,
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
    var hasCenteredOnLiveGps by remember { mutableStateOf(false) }
    var hasSetInitialCamera by remember { mutableStateOf(false) }
    var hasUserMovedMap by remember { mutableStateOf(false) }
    var isFollowingNavigation by remember { mutableStateOf(false) }

    val currentPois by rememberUpdatedState(pois)
    val currentNavigationRoute by rememberUpdatedState(navigationRoute)
    val currentNavigationTravelMode by rememberUpdatedState(navigationTravelMode)
    val currentIsNavigationActive by rememberUpdatedState(isNavigationActive)
    val currentSelectedPoiId by rememberUpdatedState(selectedPoiId)
    val currentSpatialPose by rememberUpdatedState(spatialPose)
    val currentOnCameraBearingChanged by rememberUpdatedState(onCameraBearingChanged)
    val currentOnPoiClick by rememberUpdatedState(onPoiMarkerClick)
    val density = context.resources.displayMetrics.density
    var fittedNavigationSession by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(navigationSessionKey) {
        isFollowingNavigation = navigationSessionKey != null
    }

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
    LaunchedEffect(recenterTrigger, mapLibreInstance, isStyleReady) {
        if (recenterTrigger <= 0 || !isStyleReady || !spatialPose.hasFreshGpsFix || spatialPose.isSimulatedLocation) {
            return@LaunchedEffect
        }
        val map = mapLibreInstance ?: return@LaunchedEffect
        hasUserMovedMap = false
        isFollowingNavigation = navigationSessionKey != null
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(spatialPose.latitude, spatialPose.longitude))
                    .zoom(if (isNavigationActive) 17.0 else 14.8)
                    .bearing(spatialPose.courseDegrees.toDouble())
                    .tilt(MAP_3D_DEFAULT_TILT_DEGREES)
                    .build()
            ),
            900
        )
    }

    LaunchedEffect(compassResetTrigger, mapLibreInstance, isStyleReady) {
        if (compassResetTrigger <= 0 || !isStyleReady) return@LaunchedEffect
        val map = mapLibreInstance ?: return@LaunchedEffect
        val cameraPosition = map.cameraPosition
        val target = cameraPosition.target ?: return@LaunchedEffect
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(target)
                    .zoom(cameraPosition.zoom)
                    .tilt(cameraPosition.tilt)
                    .bearing(0.0)
                    .build()
            ),
            450
        )
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
        isFollowingNavigation,
        mapLibreInstance,
        isStyleReady
    ) {
        val map = mapLibreInstance ?: return@LaunchedEffect
        if (!isStyleReady || !isNavigationActive || !isFollowingNavigation || !spatialPose.hasFreshGpsFix) {
            return@LaunchedEffect
        }

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

    // Center on the first real GPS fix, even if the map style finished loading first.
    LaunchedEffect(
        spatialPose.locationFixElapsedRealtimeNanos,
        spatialPose.hasFreshGpsFix,
        spatialPose.isSimulatedLocation,
        hasUserMovedMap,
        mapLibreInstance,
        isStyleReady
    ) {
        val map = mapLibreInstance ?: return@LaunchedEffect
        if (!isStyleReady || hasCenteredOnLiveGps || hasUserMovedMap) return@LaunchedEffect

        if (spatialPose.hasFreshGpsFix && !spatialPose.isSimulatedLocation) {
            hasCenteredOnLiveGps = true
        } else if (hasSetInitialCamera) {
            return@LaunchedEffect
        } else {
            hasSetInitialCamera = true
        }

        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(spatialPose.latitude, spatialPose.longitude))
                    .zoom(14.5)
                    .tilt(MAP_3D_DEFAULT_TILT_DEGREES)
                    .build()
            ),
            750
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
                        map.uiSettings.isCompassEnabled = false
                        map.uiSettings.isScrollGesturesEnabled = true
                        map.uiSettings.isZoomGesturesEnabled = true
                        map.uiSettings.isRotateGesturesEnabled = true
                        map.uiSettings.isTiltGesturesEnabled = true
                        map.uiSettings.isAttributionEnabled = true

                        map.addOnCameraMoveStartedListener { reason ->
                            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                                hasUserMovedMap = true
                                isFollowingNavigation = false
                            }
                        }

                        map.addOnCameraIdleListener {
                            val bearing = ((map.cameraPosition.bearing % 360.0 + 360.0) % 360.0).toFloat()
                            currentOnCameraBearingChanged(bearing)
                        }
                        currentOnCameraBearingChanged(
                            ((map.cameraPosition.bearing % 360.0 + 360.0) % 360.0).toFloat()
                        )

                        loadDauVienDuBrandStyle(
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

private const val SOURCE_NAVIGATION_ROUTE = "source-navigation-route"
private const val LAYER_NAVIGATION_ROUTE_CASING = "layer-navigation-route-casing"
private const val LAYER_NAVIGATION_ROUTE = "layer-navigation-route"

private const val PROP_POI_ID = "poi_id"
private const val PROP_POI_TITLE = "poi_title"
private const val PROP_POI_ICON = "poi_icon"
private const val PROP_GPS_ICON = "gps_icon"
private const val PROP_GPS_COURSE = "gps_course"

private enum class PoiMarkerState {
    LOCKED,
    UNLOCKED,
    SELECTED
}

private fun poiMarkerIconKey(category: String, markerState: PoiMarkerState): String =
    "icon-poi-${PoiCategoryLabels.normalize(category)}-${markerState.name.lowercase()}"

/**
 * Loads the local Dấu Viễn Du map style. It uses OpenFreeMap vector tiles and fonts,
 * while keeping the branded palette under the app's control instead of a hosted style URL.
 */
private fun loadDauVienDuBrandStyle(
    context: Context,
    map: MapLibreMap,
    onStyleReady: (Style) -> Unit
) {
    val styleBuilder = runCatching {
        val styleJson = context.assets.open("map_style_dauviendu.json")
            .bufferedReader()
            .use { it.readText() }
        Style.Builder().fromJson(styleJson)
    }.getOrElse {
        Style.Builder().fromUri(OPENFREEMAP_3D_STYLE_URL)
    }

    map.setStyle(styleBuilder) { style ->
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
    PoiCategoryLabels.values.forEach { category ->
        PoiMarkerState.entries.forEach { markerState ->
            val (fillColor, ringColor) = when (markerState) {
                PoiMarkerState.LOCKED -> Color.parseColor("#622821") to Color.parseColor("#F6DCAD")
                PoiMarkerState.UNLOCKED -> Color.parseColor("#9A4333") to Color.parseColor("#F6DCAD")
                PoiMarkerState.SELECTED -> Color.parseColor("#3F1D18") to Color.parseColor("#F6DCAD")
            }
            style.addImage(
                poiMarkerIconKey(category, markerState),
                createPoiPinBitmap(density, fillColor, ringColor, category)
            )
        }
    }

    if (style.getSource(SOURCE_NAVIGATION_ROUTE) == null) {
        style.addSource(GeoJsonSource(SOURCE_NAVIGATION_ROUTE))
    }
    if (style.getLayer(LAYER_NAVIGATION_ROUTE_CASING) == null) {
        style.addLayer(
            LineLayer(LAYER_NAVIGATION_ROUTE_CASING, SOURCE_NAVIGATION_ROUTE).withProperties(
                lineColor(Color.WHITE),
                lineWidth(9.0f),
                lineOpacity(0.96f),
                lineCap("round"),
                lineJoin("round")
            )
        )
    }
    if (style.getLayer(LAYER_NAVIGATION_ROUTE) == null) {
        style.addLayer(
            LineLayer(LAYER_NAVIGATION_ROUTE, SOURCE_NAVIGATION_ROUTE).withProperties(
                lineColor(Color.parseColor("#4285F4")),
                lineWidth(5.5f),
                lineOpacity(0.98f),
                lineCap("round"),
                lineJoin("round")
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
                textHaloColor(Color.WHITE),
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
                iconImage("{$PROP_POI_ICON}"),
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
        val markerState = when {
            isUnlocked -> PoiMarkerState.UNLOCKED
            else -> PoiMarkerState.LOCKED
        }
        val iconKey = poiMarkerIconKey(poi.category, markerState)
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
            addProperty(PROP_POI_ICON, poiMarkerIconKey(selectedPoi.category, PoiMarkerState.SELECTED))
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
    val sizePx = (52 * density).toInt().coerceAtLeast(52)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = sizePx / 2f

    // The map symbol rotates this north-facing cone using the live GPS/compass course.
    val directionBeam = Path().apply {
        moveTo(center, center)
        lineTo(center - 15f * density, 2f * density)
        lineTo(center + 15f * density, 2f * density)
        close()
    }
    val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(64, 66, 133, 244)
        style = Paint.Style.FILL
    }
    canvas.drawPath(directionBeam, beamPaint)

    val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(72, 66, 133, 244)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, 12f * density, haloPaint)

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, 9f * density, borderPaint)

    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4285F4")
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, 6.5f * density, dotPaint)

    return bitmap
}

private fun createNavigationMarkerBitmap(density: Float, mode: NavigationTravelMode): Bitmap {
    val sizePx = (58 * density).toInt().coerceAtLeast(58)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = sizePx / 2f
    val unit = density
    val badgeRadius = center * 0.82f

    canvas.drawCircle(
        center,
        center + 1.2f * unit,
        badgeRadius,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 12, 35, 72)
            setShadowLayer(4f * unit, 0f, 2f * unit, Color.argb(110, 0, 0, 0))
        }
    )
    canvas.drawCircle(
        center,
        center,
        badgeRadius * 0.96f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    )
    canvas.drawCircle(
        center,
        center,
        badgeRadius * 0.86f,
        gradientPaint(
            topColor = Color.parseColor("#72C3FF"),
            bottomColor = Color.parseColor("#1764D1"),
            topY = center - badgeRadius,
            bottomY = center + badgeRadius
        )
    )
    canvas.drawOval(
        RectF(
            center - badgeRadius * 0.55f,
            center - badgeRadius * 0.70f,
            center + badgeRadius * 0.55f,
            center - badgeRadius * 0.43f
        ),
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(52, 255, 255, 255) }
    )
    canvas.drawCircle(
        center,
        center,
        badgeRadius * 0.84f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(105, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 0.8f * unit
        }
    )

    when (mode) {
        NavigationTravelMode.WALKING -> drawRaisedWalker(canvas, center, unit)
        NavigationTravelMode.CAR -> drawRaisedCar(canvas, center, unit)
        NavigationTravelMode.MOTORCYCLE -> drawRaisedMotorcycle(canvas, center, unit)
    }

    return bitmap
}

private fun gradientPaint(topColor: Int, bottomColor: Int, topY: Float, bottomY: Float): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = LinearGradient(0f, topY, 0f, bottomY, topColor, bottomColor, Shader.TileMode.CLAMP)
    }

private fun raisedStroke(color: Int, width: Float): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

private fun drawRaisedWalker(canvas: Canvas, center: Float, unit: Float) {
    val dark = Color.parseColor("#153B70")
    val jacket = gradientPaint(
        Color.parseColor("#FFFFFF"),
        Color.parseColor("#9ED0FF"),
        center - 7f * unit,
        center + 6f * unit
    )
    val limbs = Path().apply {
        moveTo(center - 2.7f * unit, center - 4f * unit)
        lineTo(center - 6.8f * unit, center + 0.5f * unit)
        lineTo(center - 5.2f * unit, center + 3.0f * unit)
        moveTo(center + 2.7f * unit, center - 4f * unit)
        lineTo(center + 6.8f * unit, center - 0.2f * unit)
        lineTo(center + 5.5f * unit, center + 2.8f * unit)
        moveTo(center - 2f * unit, center + 5f * unit)
        lineTo(center - 5f * unit, center + 10f * unit)
        lineTo(center - 6.5f * unit, center + 14f * unit)
        moveTo(center + 2f * unit, center + 5f * unit)
        lineTo(center + 5f * unit, center + 9f * unit)
        lineTo(center + 3f * unit, center + 14f * unit)
    }
    canvas.save()
    canvas.translate(0f, 1.3f * unit)
    canvas.drawPath(limbs, raisedStroke(dark, 4.5f * unit))
    canvas.restore()
    canvas.drawPath(limbs, raisedStroke(Color.parseColor("#F6FBFF"), 3.0f * unit))
    canvas.drawPath(limbs, raisedStroke(Color.parseColor("#9BCBFA"), 0.8f * unit))

    val torso = Path().apply {
        moveTo(center - 3f * unit, center - 7f * unit)
        quadTo(center, center - 9f * unit, center + 3f * unit, center - 7f * unit)
        lineTo(center + 3.8f * unit, center + 4.5f * unit)
        quadTo(center, center + 7f * unit, center - 3.8f * unit, center + 4.5f * unit)
        close()
    }
    canvas.save()
    canvas.translate(0f, 1.5f * unit)
    canvas.drawPath(torso, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dark })
    canvas.restore()
    canvas.drawPath(torso, jacket)
    canvas.drawLine(
        center - 1.2f * unit,
        center - 5f * unit,
        center - 1.8f * unit,
        center + 2.5f * unit,
        raisedStroke(Color.argb(170, 255, 255, 255), 0.9f * unit)
    )

    canvas.drawCircle(center, center - 11.5f * unit, 3.4f * unit, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dark })
    canvas.drawCircle(
        center - 0.5f * unit,
        center - 12f * unit,
        3.0f * unit,
        gradientPaint(Color.parseColor("#FFE3BF"), Color.parseColor("#E9A879"), center - 15f * unit, center - 8f * unit)
    )
    canvas.drawCircle(
        center - 1.4f * unit,
        center - 13f * unit,
        0.75f * unit,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 255, 255, 255) }
    )
}

private fun drawRaisedCar(canvas: Canvas, center: Float, unit: Float) {
    val tirePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#17283A") }
    val tireHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8091A3") }
    listOf(-9f, 7f).forEach { y ->
        canvas.drawRoundRect(
            RectF(center - 10.5f * unit, center + y * unit, center - 6.5f * unit, center + (y + 6f) * unit),
            1.5f * unit,
            1.5f * unit,
            tirePaint
        )
        canvas.drawRoundRect(
            RectF(center + 6.5f * unit, center + y * unit, center + 10.5f * unit, center + (y + 6f) * unit),
            1.5f * unit,
            1.5f * unit,
            tirePaint
        )
        canvas.drawLine(center - 9.8f * unit, center + (y + 1.2f) * unit, center - 7.2f * unit, center + (y + 1.2f) * unit, tireHighlight)
        canvas.drawLine(center + 7.2f * unit, center + (y + 1.2f) * unit, center + 9.8f * unit, center + (y + 1.2f) * unit, tireHighlight)
    }

    val body = Path().apply {
        moveTo(center - 5f * unit, center + 15f * unit)
        quadTo(center - 8f * unit, center + 14f * unit, center - 8f * unit, center + 9f * unit)
        lineTo(center - 7.4f * unit, center - 9f * unit)
        quadTo(center - 6.8f * unit, center - 15f * unit, center, center - 16f * unit)
        quadTo(center + 6.8f * unit, center - 15f * unit, center + 7.4f * unit, center - 9f * unit)
        lineTo(center + 8f * unit, center + 9f * unit)
        quadTo(center + 8f * unit, center + 14f * unit, center + 5f * unit, center + 15f * unit)
        close()
    }
    canvas.save()
    canvas.translate(0f, 1.8f * unit)
    canvas.drawPath(body, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#12355D") })
    canvas.restore()
    canvas.drawPath(
        body,
        gradientPaint(Color.parseColor("#FFFFFF"), Color.parseColor("#8EC8FF"), center - 16f * unit, center + 15f * unit)
    )
    canvas.drawPath(body, raisedStroke(Color.parseColor("#5A9DE0"), 0.8f * unit))

    val frontGlass = Path().apply {
        moveTo(center - 5.5f * unit, center - 8f * unit)
        quadTo(center - 4.8f * unit, center - 13f * unit, center, center - 13f * unit)
        quadTo(center + 4.8f * unit, center - 13f * unit, center + 5.5f * unit, center - 8f * unit)
        lineTo(center + 4.7f * unit, center - 2f * unit)
        lineTo(center - 4.7f * unit, center - 2f * unit)
        close()
    }
    canvas.drawPath(frontGlass, gradientPaint(Color.parseColor("#315A78"), Color.parseColor("#142E45"), center - 13f * unit, center - 2f * unit))
    canvas.drawLine(center - 3.6f * unit, center - 10.5f * unit, center + 3.2f * unit, center - 10.5f * unit, raisedStroke(Color.argb(170, 210, 242, 255), 0.75f * unit))

    val rearGlass = Path().apply {
        moveTo(center - 4.8f * unit, center + 3f * unit)
        lineTo(center + 4.8f * unit, center + 3f * unit)
        lineTo(center + 4.3f * unit, center + 8f * unit)
        quadTo(center, center + 10.2f * unit, center - 4.3f * unit, center + 8f * unit)
        close()
    }
    canvas.drawPath(rearGlass, gradientPaint(Color.parseColor("#264B69"), Color.parseColor("#142E45"), center + 3f * unit, center + 9f * unit))
    canvas.drawRoundRect(
        RectF(center - 5.4f * unit, center - 13.8f * unit, center - 3.1f * unit, center - 12.6f * unit),
        0.7f * unit,
        0.7f * unit,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFF6C7") }
    )
    canvas.drawRoundRect(
        RectF(center + 3.1f * unit, center - 13.8f * unit, center + 5.4f * unit, center - 12.6f * unit),
        0.7f * unit,
        0.7f * unit,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFF6C7") }
    )
    canvas.drawRoundRect(
        RectF(center - 5.3f * unit, center + 12.4f * unit, center - 2.6f * unit, center + 14f * unit),
        0.6f * unit,
        0.6f * unit,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F16658") }
    )
    canvas.drawRoundRect(
        RectF(center + 2.6f * unit, center + 12.4f * unit, center + 5.3f * unit, center + 14f * unit),
        0.6f * unit,
        0.6f * unit,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F16658") }
    )
}

private fun drawRaisedMotorcycle(canvas: Canvas, center: Float, unit: Float) {
    val tire = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#142638") }
    val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C9D8E4") }
    listOf(-13f, 13f).forEach { y ->
        canvas.drawCircle(center, center + y * unit, 3.6f * unit, tire)
        canvas.drawCircle(center, center + y * unit, 1.45f * unit, rim)
    }

    val frame = Path().apply {
        moveTo(center, center - 12f * unit)
        lineTo(center - 4f * unit, center - 4f * unit)
        lineTo(center + 4f * unit, center + 4f * unit)
        lineTo(center, center + 12f * unit)
        moveTo(center - 4f * unit, center - 4f * unit)
        lineTo(center + 5f * unit, center - 7f * unit)
        lineTo(center + 1f * unit, center + 1f * unit)
        lineTo(center - 4f * unit, center + 7f * unit)
    }
    canvas.drawPath(frame, raisedStroke(Color.parseColor("#163C65"), 3.4f * unit))
    canvas.drawPath(frame, raisedStroke(Color.parseColor("#B7DFFF"), 1.5f * unit))
    canvas.drawLine(center - 6f * unit, center - 11f * unit, center + 6f * unit, center - 11f * unit, raisedStroke(Color.parseColor("#EFF8FF"), 1.6f * unit))

    val tank = Path().apply {
        moveTo(center - 4.6f * unit, center - 6f * unit)
        quadTo(center, center - 9f * unit, center + 4.8f * unit, center - 5f * unit)
        lineTo(center + 3.1f * unit, center + 1f * unit)
        quadTo(center, center + 3.4f * unit, center - 3.5f * unit, center + 1f * unit)
        close()
    }
    canvas.save()
    canvas.translate(0f, 1.3f * unit)
    canvas.drawPath(tank, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#6A2630") })
    canvas.restore()
    canvas.drawPath(tank, gradientPaint(Color.parseColor("#FFB05F"), Color.parseColor("#D84B3F"), center - 8f * unit, center + 3f * unit))
    canvas.drawLine(center - 1.8f * unit, center - 6f * unit, center - 0.8f * unit, center - 1f * unit, raisedStroke(Color.argb(190, 255, 238, 205), 0.8f * unit))

    canvas.drawRoundRect(
        RectF(center - 2.6f * unit, center + 2f * unit, center + 2.7f * unit, center + 8f * unit),
        1.7f * unit,
        1.7f * unit,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#253B50") }
    )
    canvas.drawCircle(
        center,
        center - 12f * unit,
        2.2f * unit,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFF4C7") }
    )
    canvas.drawCircle(
        center,
        center - 12f * unit,
        3.3f * unit,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(135, 255, 240, 185)
            style = Paint.Style.STROKE
            strokeWidth = 0.8f * unit
        }
    )
}

private fun createPoiPinBitmap(
    density: Float,
    fillColor: Int,
    ringColor: Int,
    category: String
): Bitmap {
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

    val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFF9F0")
        style = Paint.Style.STROKE
        strokeWidth = 1.65f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val glyphFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFF9F0")
        style = Paint.Style.FILL
    }
    val unit = density

    when (PoiCategoryLabels.normalize(category)) {
        PoiCategoryLabels.FOOD_AND_DRINK -> drawFoodGlyph(canvas, center, unit, glyphPaint)
        PoiCategoryLabels.TOURIST_ATTRACTION -> drawAttractionGlyph(canvas, center, unit, glyphPaint)
        PoiCategoryLabels.CULTURAL_HERITAGE -> drawCulturalHeritageGlyph(canvas, center, unit, glyphPaint)
        else -> drawHistoricalSiteGlyph(canvas, center, unit, glyphPaint, glyphFillPaint)
    }

    return bitmap
}

private fun drawFoodGlyph(canvas: Canvas, center: Float, unit: Float, paint: Paint) {
    // Fork on the left and knife on the right.
    val left = center - 3.2f * unit
    canvas.drawLine(left, center - 5.5f * unit, left, center + 5.5f * unit, paint)
    canvas.drawLine(left - 2f * unit, center - 5.5f * unit, left - 2f * unit, center - 2f * unit, paint)
    canvas.drawLine(left, center - 5.5f * unit, left, center - 2f * unit, paint)
    canvas.drawLine(left + 2f * unit, center - 5.5f * unit, left + 2f * unit, center - 2f * unit, paint)
    canvas.drawLine(left - 2f * unit, center - 2f * unit, left + 2f * unit, center - 2f * unit, paint)

    val right = center + 3.2f * unit
    val knife = Path().apply {
        moveTo(right, center - 5.5f * unit)
        lineTo(right + 2.4f * unit, center - 1f * unit)
        lineTo(right, center + 0.8f * unit)
        close()
        moveTo(right, center + 0.8f * unit)
        lineTo(right, center + 5.5f * unit)
    }
    canvas.drawPath(knife, paint)
}

private fun drawAttractionGlyph(canvas: Canvas, center: Float, unit: Float, paint: Paint) {
    // A camera symbol represents places visitors explore and photograph.
    canvas.drawRoundRect(
        RectF(center - 6f * unit, center - 3.5f * unit, center + 6f * unit, center + 4.5f * unit),
        1.2f * unit,
        1.2f * unit,
        paint
    )
    val top = Path().apply {
        moveTo(center - 3.5f * unit, center - 3.5f * unit)
        lineTo(center - 2f * unit, center - 5.2f * unit)
        lineTo(center + 2f * unit, center - 5.2f * unit)
        lineTo(center + 3.5f * unit, center - 3.5f * unit)
    }
    canvas.drawPath(top, paint)
    canvas.drawCircle(center, center + 0.5f * unit, 2.25f * unit, paint)
}

private fun drawCulturalHeritageGlyph(canvas: Canvas, center: Float, unit: Float, paint: Paint) {
    // A traditional roof and columns distinguish cultural heritage from a monument.
    val roof = Path().apply {
        moveTo(center - 7f * unit, center - 2f * unit)
        lineTo(center - 5.2f * unit, center - 4f * unit)
        lineTo(center - 3.5f * unit, center - 4f * unit)
        lineTo(center, center - 6f * unit)
        lineTo(center + 3.5f * unit, center - 4f * unit)
        lineTo(center + 5.2f * unit, center - 4f * unit)
        lineTo(center + 7f * unit, center - 2f * unit)
        close()
    }
    canvas.drawPath(roof, paint)
    canvas.drawLine(center - 6.5f * unit, center - 0.5f * unit, center + 6.5f * unit, center - 0.5f * unit, paint)
    listOf(-4f, 0f, 4f).forEach { offset ->
        canvas.drawLine(
            center + offset * unit,
            center - 0.5f * unit,
            center + offset * unit,
            center + 4f * unit,
            paint
        )
    }
    canvas.drawLine(center - 6.5f * unit, center + 4.5f * unit, center + 6.5f * unit, center + 4.5f * unit, paint)
    canvas.drawLine(center - 7f * unit, center + 6f * unit, center + 7f * unit, center + 6f * unit, paint)
}

private fun drawHistoricalSiteGlyph(
    canvas: Canvas,
    center: Float,
    unit: Float,
    strokePaint: Paint,
    fillPaint: Paint
) {
    // An obelisk silhouette identifies historic sites without reusing the heritage temple glyph.
    val monument = Path().apply {
        moveTo(center, center - 7f * unit)
        lineTo(center + 3f * unit, center - 1.5f * unit)
        lineTo(center + 2.5f * unit, center + 4f * unit)
        lineTo(center - 2.5f * unit, center + 4f * unit)
        lineTo(center - 3f * unit, center - 1.5f * unit)
        close()
    }
    canvas.drawPath(monument, fillPaint)
    canvas.drawLine(center - 4.5f * unit, center + 4.7f * unit, center + 4.5f * unit, center + 4.7f * unit, strokePaint)
    canvas.drawLine(center - 5.5f * unit, center + 6.2f * unit, center + 5.5f * unit, center + 6.2f * unit, strokePaint)
}
