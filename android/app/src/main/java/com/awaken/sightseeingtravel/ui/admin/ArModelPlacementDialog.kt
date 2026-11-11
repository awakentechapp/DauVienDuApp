package com.awaken.sightseeingtravel.ui.admin

import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.awaken.sightseeingtravel.core.model.AdminPoiDto
import com.awaken.sightseeingtravel.core.model.ArModelLibraryItemDto
import com.awaken.sightseeingtravel.core.model.PlaceArModelRequest
import com.awaken.sightseeingtravel.core.sensor.LocationAndOrientationTracker
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.ImageNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import io.github.sceneview.rememberOnGestureListener
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private const val PlacementEarthRadiusMeters = 6_371_000.0
private const val MaxPlacementGpsAccuracyMeters = 15f

private data class PlacedGeoPoint(
    val azimuthDegrees: Float,
    val distanceMeters: Float,
    val elevationMeters: Float
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArModelPlacementDialog(
    poi: AdminPoiDto,
    currentLatitude: Double,
    currentLongitude: Double,
    hasFreshGpsFix: Boolean,
    gpsAccuracyMeters: Float,
    currentAzimuthDegrees: Float,
    onLoadLibrary: suspend () -> List<ArModelLibraryItemDto>,
    onSave: suspend (PlaceArModelRequest) -> String?,
    onDismiss: () -> Unit
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val childNodes = rememberNodes()
    val pendingTap = remember { AtomicReference<Offset?>(null) }
    var library by remember(poi.id) { mutableStateOf<List<ArModelLibraryItemDto>>(emptyList()) }
    var selectedModel by remember(poi.id) { mutableStateOf<ArModelLibraryItemDto?>(null) }
    var modelInstance by remember(poi.id) { mutableStateOf<ModelInstance?>(null) }
    var previewModelNode by remember(poi.id) { mutableStateOf<ModelNode?>(null) }
    var isLoadingLibrary by remember(poi.id) { mutableStateOf(true) }
    var isLoadingModel by remember(poi.id) { mutableStateOf(false) }
    var libraryError by remember(poi.id) { mutableStateOf<String?>(null) }
    var placementError by remember(poi.id) { mutableStateOf<String?>(null) }
    var sessionError by remember(poi.id) { mutableStateOf<String?>(null) }
    var placedPoint by remember(poi.id) { mutableStateOf<PlacedGeoPoint?>(null) }
    var modelName by remember(poi.id) { mutableStateOf("") }
    var modelHeadingDegrees by remember(poi.id) { mutableFloatStateOf(normalizeHeading(currentAzimuthDegrees)) }
    var isSaving by remember(poi.id) { mutableStateOf(false) }
    var saveError by remember(poi.id) { mutableStateOf<String?>(null) }
    var isSessionTracking by remember(poi.id) { mutableStateOf(false) }
    var modelMenuExpanded by remember(poi.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(poi.id) {
        isLoadingLibrary = true
        libraryError = null
        runCatching { onLoadLibrary() }
            .onSuccess { loaded ->
                library = loaded
                selectedModel = loaded.firstOrNull()
                modelName = loaded.firstOrNull()?.name.orEmpty()
                loaded.firstOrNull()?.let { modelHeadingDegrees = normalizeHeading(it.modelRotationDegrees) }
                if (loaded.isEmpty()) libraryError = "Kho chưa có mô hình 3D. Hãy dựng mô hình từ thẻ AR trước."
            }
            .onFailure { libraryError = it.localizedMessage ?: "Không thể tải kho vật thể 3D." }
        isLoadingLibrary = false
    }

    LaunchedEffect(selectedModel?.modelUrl) {
        modelInstance = null
        previewModelNode = null
        placedPoint = null
        placementError = null
        childNodes.toList().forEach { runCatching { it.destroy() } }
        childNodes.clear()
        val item = selectedModel ?: return@LaunchedEffect
        isLoadingModel = true
        runCatching { modelLoader.loadModelInstance(item.modelUrl) }
            .onSuccess { modelInstance = it }
            .onFailure { placementError = it.localizedMessage ?: "Không thể tải mô hình 3D đã chọn." }
        isLoadingModel = false
    }

    SideEffect {
        previewModelNode?.rotation = Rotation(
            y = LocationAndOrientationTracker.signedAngleDeltaDegrees(
                currentAzimuthDegrees,
                modelHeadingDegrees
            )
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            childNodes.toList().forEach { runCatching { it.destroy() } }
            childNodes.clear()
        }
    }

    BackHandler(onBack = onDismiss)

    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF080D15))
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xE6141E2C))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { if (!isSaving) onDismiss() }, enabled = !isSaving) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Quay lại", tint = Color.White)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Đặt vật thể 3D", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(poi.titleFor("vi"), color = Color(0xFFB6DDF4), fontSize = 12.sp, maxLines = 1)
                }
                IconButton(onClick = { if (!isSaving) onDismiss() }, enabled = !isSaving) {
                    Icon(Icons.Default.Close, contentDescription = "Đóng", tint = Color.White)
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                ARScene(
                    modifier = Modifier.fillMaxSize(),
                    engine = engine,
                    modelLoader = modelLoader,
                    materialLoader = materialLoader,
                    childNodes = childNodes,
                    planeRenderer = true,
                    onSessionFailed = { error ->
                        sessionError = error.localizedMessage ?: "Không thể mở ARCore trên thiết bị này."
                    },
                    onSessionUpdated = sessionUpdated@ { session, frame ->
                        isSessionTracking = frame.camera.trackingState == TrackingState.TRACKING
                        val tap = pendingTap.getAndSet(null) ?: return@sessionUpdated
                        if (!hasFreshGpsFix || gpsAccuracyMeters > MaxPlacementGpsAccuracyMeters) {
                            placementError = "Cần GPS thật với sai số không quá 15 m để lưu đúng vị trí vật thể."
                            return@sessionUpdated
                        }
                        if (modelInstance == null) {
                            placementError = "Chờ mô hình 3D tải xong rồi chạm vào vị trí đặt."
                            return@sessionUpdated
                        }

                        val floorHit = frame.hitTest(tap.x, tap.y).firstOrNull { hit ->
                            val plane = hit.trackable as? Plane
                            plane != null &&
                                plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                                plane.trackingState == TrackingState.TRACKING &&
                                plane.isPoseInPolygon(hit.hitPose)
                        }
                        if (floorHit == null) {
                            placementError = "Chưa nhận diện mặt phẳng. Hãy lia camera chậm trên nền phẳng rồi chạm lại."
                            return@sessionUpdated
                        }

                        runCatching {
                            val cameraPose = frame.camera.pose
                            val cameraTranslation = cameraPose.translation
                            val targetTranslation = floorHit.hitPose.translation
                            val deltaX = targetTranslation[0] - cameraTranslation[0]
                            val deltaZ = targetTranslation[2] - cameraTranslation[2]
                            val forward = cameraPose.rotateVector(floatArrayOf(0f, 0f, -1f))
                            val right = cameraPose.rotateVector(floatArrayOf(1f, 0f, 0f))
                            val forwardLength = hypot(forward[0].toDouble(), forward[2].toDouble()).coerceAtLeast(0.001)
                            val rightLength = hypot(right[0].toDouble(), right[2].toDouble()).coerceAtLeast(0.001)
                            val forwardMeters = (deltaX.toDouble() * forward[0] + deltaZ.toDouble() * forward[2]) / forwardLength
                            val rightMeters = (deltaX.toDouble() * right[0] + deltaZ.toDouble() * right[2]) / rightLength
                            val horizontalDistance = hypot(rightMeters.toDouble(), forwardMeters.toDouble())
                            if (horizontalDistance < 0.4 || horizontalDistance > 50.0) {
                                error("Điểm đặt phải cách camera từ 0,4 đến 50 m.")
                            }

                            val relativeBearing = Math.toDegrees(atan2(rightMeters.toDouble(), forwardMeters.toDouble()))
                            val targetBearing = normalizeHeading(currentAzimuthDegrees + relativeBearing.toFloat())
                            val (targetLatitude, targetLongitude) = destinationCoordinate(
                                currentLatitude,
                                currentLongitude,
                                targetBearing.toDouble(),
                                horizontalDistance
                            )
                            val poiDistance = LocationAndOrientationTracker.calculateDistanceMeters(
                                poi.latitude,
                                poi.longitude,
                                targetLatitude,
                                targetLongitude
                            )
                            if (poiDistance !in 1.0..50.0) {
                                error("Vị trí đặt phải cách POI từ 1 đến 50 m (hiện ${poiDistance.toInt()} m).")
                            }

                            val point = PlacedGeoPoint(
                                azimuthDegrees = LocationAndOrientationTracker.calculateBearingDegrees(
                                    poi.latitude,
                                    poi.longitude,
                                    targetLatitude,
                                    targetLongitude
                                ),
                                distanceMeters = poiDistance.toFloat(),
                                elevationMeters = (1.5 + targetTranslation[1] - cameraTranslation[1])
                                    .coerceIn(-5.0, 20.0).toFloat()
                            )

                            previewModelNode?.let { old ->
                                runCatching { old.destroy() }
                            }
                            childNodes.toList().forEach { runCatching { it.destroy() } }
                            childNodes.clear()

                            val placementAnchorPose = Pose(
                                floorHit.hitPose.translation,
                                cameraPose.rotationQuaternion
                            )
                            val anchorNode = AnchorNode(engine, session.createAnchor(placementAnchorPose))
                            val modelNode = ModelNode(
                                modelInstance = modelInstance!!,
                                scaleToUnits = 1.2f,
                                centerOrigin = Position(y = -0.5f)
                            ).apply {
                                rotation = Rotation(
                                    y = LocationAndOrientationTracker.signedAngleDeltaDegrees(
                                        currentAzimuthDegrees,
                                        modelHeadingDegrees
                                    )
                                )
                            }
                            anchorNode.addChildNode(modelNode)
                            childNodes.add(anchorNode)
                            previewModelNode = modelNode
                            placedPoint = point
                            placementError = null
                            saveError = null
                        }.onFailure { placementError = it.localizedMessage ?: "Không thể lưu vị trí đã chọn." }
                    },
                    onGestureListener = rememberOnGestureListener(
                        onSingleTapConfirmed = { event: MotionEvent, _ ->
                            pendingTap.set(Offset(event.x, event.y))
                        }
                    )
                )

                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xD9101724)
                ) {
                    Text(
                        text = when {
                            sessionError != null -> sessionError!!
                            !isSessionTracking -> "Lia camera chậm để AR nhận diện mặt phẳng, sau đó chạm vị trí muốn đặt."
                            placedPoint != null -> "Đã chọn vị trí • ${placedPoint!!.distanceMeters.toInt()} m từ POI"
                            else -> "Chạm vào mặt phẳng ngang để đặt mô hình 3D"
                        },
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFF8FAFC),
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 330.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isLoadingLibrary) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Box {
                        OutlinedButton(
                            onClick = { modelMenuExpanded = !modelMenuExpanded },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = library.isNotEmpty() && !isSaving
                        ) {
                            Icon(Icons.Default.ViewInAr, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = selectedModel?.let { "Mô hình: ${it.name}" } ?: "Chọn vật thể trong kho",
                                maxLines = 1
                            )
                        }
                        DropdownMenu(
                            expanded = modelMenuExpanded,
                            onDismissRequest = { modelMenuExpanded = false },
                            modifier = Modifier.heightIn(max = 280.dp)
                        ) {
                            library.forEach { item ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(item.name, fontWeight = FontWeight.SemiBold)
                                            Text("POI ${item.sourcePoiId.take(8)}", style = MaterialTheme.typography.bodySmall)
                                        }
                                    },
                                    onClick = {
                                        selectedModel = item
                                        modelName = item.name
                                        modelHeadingDegrees = normalizeHeading(item.modelRotationDegrees)
                                        modelMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    if (libraryError != null) {
                        Text(libraryError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (isLoadingModel) {
                        Text("Đang tải mô hình 3D để xem trước…", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(
                        value = modelName,
                        onValueChange = { modelName = it.take(200) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Tên hiển thị phía trên vật thể") },
                        singleLine = true,
                        enabled = !isSaving
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Xoay ${modelHeadingDegrees.toInt()}°", fontWeight = FontWeight.Medium)
                        Slider(
                            value = modelHeadingDegrees,
                            onValueChange = { modelHeadingDegrees = normalizeHeading(it) },
                            valueRange = 0f..360f,
                            modifier = Modifier.weight(1f),
                            enabled = modelInstance != null && !isSaving
                        )
                    }
                    Text(
                        text = if (hasFreshGpsFix) "GPS sai số khoảng ${gpsAccuracyMeters.toInt()} m (yêu cầu ≤15 m) • hướng ${currentAzimuthDegrees.toInt()}°"
                        else "Đang chờ GPS thật để tính vị trí lưu",
                        color = if (hasFreshGpsFix && gpsAccuracyMeters <= MaxPlacementGpsAccuracyMeters) Color(0xFF15803D) else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    placementError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { if (!isSaving) onDismiss() },
                            modifier = Modifier.weight(1f),
                            enabled = !isSaving
                        ) { Text("Hủy") }
                        Button(
                            onClick = {
                                val point = placedPoint ?: return@Button
                                val item = selectedModel ?: return@Button
                                if (modelName.isBlank()) {
                                    saveError = "Hãy nhập tên hiển thị phía trên vật thể."
                                    return@Button
                                }
                                isSaving = true
                                saveError = null
                                val request = PlaceArModelRequest(
                                    sourceCardId = item.sourceCardId,
                                    name = modelName.trim(),
                                    azimuthDegrees = point.azimuthDegrees,
                                    distanceMeters = point.distanceMeters,
                                    elevationMeters = point.elevationMeters,
                                    modelRotationDegrees = normalizeHeading(modelHeadingDegrees)
                                )
                                scope.launch {
                                    val error = runCatching { onSave(request) }
                                        .getOrElse { it.localizedMessage ?: "Không thể lưu vật thể 3D." }
                                    isSaving = false
                                    if (error == null) onDismiss() else saveError = error
                                }
                            },
                            modifier = Modifier.weight(1.3f),
                            enabled = placedPoint != null && selectedModel != null && !isSaving && !isLoadingModel &&
                                modelName.isNotBlank() && hasFreshGpsFix && gpsAccuracyMeters <= MaxPlacementGpsAccuracyMeters
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("Lưu vị trí AR")
                        }
                    }
                }
            }
        }
    }
}

private fun normalizeHeading(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f

private fun destinationCoordinate(
    latitude: Double,
    longitude: Double,
    bearingDegrees: Double,
    distanceMeters: Double
): Pair<Double, Double> {
    val angularDistance = distanceMeters / PlacementEarthRadiusMeters
    val bearing = Math.toRadians(bearingDegrees)
    val lat1 = Math.toRadians(latitude)
    val lon1 = Math.toRadians(longitude)
    val lat2 = kotlin.math.asin(
        sin(lat1) * cos(angularDistance) + cos(lat1) * sin(angularDistance) * cos(bearing)
    )
    val lon2 = lon1 + atan2(
        sin(bearing) * sin(angularDistance) * cos(lat1),
        cos(angularDistance) - sin(lat1) * sin(lat2)
    )
    return Math.toDegrees(lat2) to ((Math.toDegrees(lon2) + 540.0) % 360.0 - 180.0)
}
