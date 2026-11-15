package com.awaken.sightseeingtravel.ui.ar

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.awaken.sightseeingtravel.core.sensor.LocationAndOrientationTracker
import com.awaken.sightseeingtravel.ui.BrandColors
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.node.ImageNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberNodes
import io.github.sceneview.rememberOnGestureListener
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.cos
import java.util.concurrent.atomic.AtomicReference

private const val MaxArPlacementGpsAccuracyMeters = 15f

data class ArAnchorPlacementResult(
    val latitude: Double,
    val longitude: Double,
    /** Compass bearing from the live GPS origin to the placed target. */
    val targetAzimuthDegrees: Float,
    /** Compass bearing from the POI to the placed target. */
    val poiAzimuthDegrees: Float,
    val distanceFromPoiMeters: Float,
    val elevationMeters: Float,
    val scaleFactor: Float
)

fun createArCardPlacementBitmap(title: String, content: String): Bitmap {
    val bitmap = Bitmap.createBitmap(900, 560, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    canvas.drawRoundRect(
        RectF(0f, 0f, 900f, 560f), 48f, 48f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BrandColors.PrimaryDeep.toArgb() }
    )
    canvas.drawRoundRect(
        RectF(6f, 6f, 894f, 554f), 42f, 42f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BrandColors.Cream.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
    )
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 56f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    drawPlacementText(canvas, title.trim().ifBlank { "AR" }, 48f, 142f, 800f, titlePaint, 66f, 2)
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BrandColors.Cream.toArgb()
        textSize = 36f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    drawPlacementText(canvas, content.trim(), 50f, 270f, 790f, bodyPaint, 48f, 5)
    return bitmap
}

private fun drawPlacementText(
    canvas: AndroidCanvas,
    text: String,
    startX: Float,
    startY: Float,
    maxWidth: Float,
    paint: Paint,
    lineHeight: Float,
    maxLines: Int
) {
    var line = ""
    var lineNumber = 0
    text.split(Regex("\\s+")).forEach { word ->
        val candidate = if (line.isEmpty()) word else "$line $word"
        if (line.isNotEmpty() && paint.measureText(candidate) > maxWidth) {
            if (lineNumber >= maxLines) return
            canvas.drawText(line, startX, startY + lineNumber * lineHeight, paint)
            lineNumber++
            line = word
        } else {
            line = candidate
        }
    }
    if (line.isNotEmpty() && lineNumber < maxLines) {
        canvas.drawText(line, startX, startY + lineNumber * lineHeight, paint)
    }
}

/**
 * Live AR placement step for a community photo or an information card.
 * Tap a tracked horizontal surface to position the preview; zoom changes are
 * applied to the saved physical size so the object keeps its size next time.
 */
@Composable
fun ArAnchorPlacementDialog(
    title: String,
    poiLatitude: Double,
    poiLongitude: Double,
    currentLatitude: Double,
    currentLongitude: Double,
    hasFreshGpsFix: Boolean,
    gpsAccuracyMeters: Float,
    currentAzimuthDegrees: Float,
    maxDistanceFromPoiMeters: Double,
    minDistanceFromPoiMeters: Double = 0.0,
    previewBitmap: Bitmap,
    previewWidthMeters: Float,
    initialScaleFactor: Float = 1f,
    selectedLanguage: String,
    onDismiss: () -> Unit,
    onConfirm: (ArAnchorPlacementResult) -> Unit
) {
    val context = LocalContext.current
    val isEn = selectedLanguage.equals("en", ignoreCase = true)
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val childNodes = rememberNodes()
    val pendingTap = remember { AtomicReference<Offset?>(null) }
    var hasCameraPermission by remember(context) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var sessionError by remember { mutableStateOf<String?>(null) }
    var placementError by remember { mutableStateOf<String?>(null) }
    var isTracking by remember { mutableStateOf(false) }
    var placement by remember { mutableStateOf<ArAnchorPlacementResult?>(null) }
    var placementPose by remember { mutableStateOf<Pose?>(null) }
    var appliedScale by remember { mutableFloatStateOf(Float.NaN) }
    var appliedElevation by remember { mutableFloatStateOf(Float.NaN) }
    var scaleFactor by remember { mutableFloatStateOf(initialScaleFactor.coerceIn(0.5f, 3f)) }
    var elevationMeters by remember { mutableFloatStateOf(1.5f) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    fun clearPreview() {
        childNodes.toList().forEach { runCatching { it.destroy() } }
        childNodes.clear()
        appliedScale = Float.NaN
        appliedElevation = Float.NaN
    }

    fun installPreview(session: Session, pose: Pose, result: ArAnchorPlacementResult, scale: Float) {
        val anchorNode = AnchorNode(engine, session.createAnchor(pose))
        val width = previewWidthMeters * scale
        val height = (width * previewBitmap.height / previewBitmap.width).coerceIn(0.18f, 2.2f)
        val imageNode = ImageNode(
            materialLoader = materialLoader,
            bitmap = previewBitmap,
            size = Size(width, height)
        ).apply {
            position = Position(y = result.elevationMeters)
            isTouchable = false
        }
        anchorNode.addChildNode(imageNode)
        childNodes.add(anchorNode)
        appliedScale = scale
        appliedElevation = result.elevationMeters
    }

    DisposableEffect(Unit) {
        onDispose { clearPreview() }
    }

    BackHandler(onBack = onDismiss)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BrandColors.PrimaryDeep)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BrandColors.PrimaryDeep.copy(alpha = 0.88f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.ArrowBack, contentDescription = if (isEn) "Back" else "Quay lại", tint = Color.White)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1)
                    Text(
                        text = if (isEn) "Tap a surface to place • zoom to resize" else "Chạm mặt phẳng để đặt • phóng to/thu nhỏ",
                        color = BrandColors.Cream,
                        fontSize = 12.sp
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = if (isEn) "Close" else "Đóng", tint = Color.White)
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (hasCameraPermission) {
                    ARScene(
                        modifier = Modifier.fillMaxSize(),
                        engine = engine,
                        materialLoader = materialLoader,
                        childNodes = childNodes,
                        planeRenderer = true,
                        onSessionFailed = { error ->
                            sessionError = error.localizedMessage ?: if (isEn) "Could not start AR camera." else "Không thể mở camera AR."
                        },
                        onSessionUpdated = sessionUpdated@{ session, frame ->
                            isTracking = frame.camera.trackingState == TrackingState.TRACKING
                            val tap = pendingTap.getAndSet(null)
                            if (tap != null) {
                                val floorHit = frame.hitTest(tap.x, tap.y).firstOrNull { hit ->
                                    val plane = hit.trackable as? Plane
                                    plane != null &&
                                        plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                                        plane.trackingState == TrackingState.TRACKING &&
                                        plane.isPoseInPolygon(hit.hitPose)
                                }
                                if (floorHit == null) {
                                    placementError = if (isEn) "No tracked horizontal surface there. Move the camera slowly and tap again." else "Chưa nhận diện được mặt phẳng tại đây. Hãy lia camera chậm rồi chạm lại."
                                    return@sessionUpdated
                                }
                                if (!hasFreshGpsFix || gpsAccuracyMeters > MaxArPlacementGpsAccuracyMeters || currentLatitude !in -90.0..90.0 || currentLongitude !in -180.0..180.0) {
                                    placementError = if (isEn) "Wait for live GPS accuracy within 15 m before saving this placement." else "Cần chờ GPS thực có sai số không quá 15 m trước khi lưu vị trí."
                                    return@sessionUpdated
                                }

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
                                val horizontalDistance = hypot(rightMeters, forwardMeters)
                                if (horizontalDistance < 0.4 || horizontalDistance > 50.0) {
                                    placementError = if (isEn) "Choose a point 0.4 to 50 m from the camera." else "Chọn điểm cách camera từ 0,4 đến 50 m."
                                    return@sessionUpdated
                                }

                                val relativeBearing = Math.toDegrees(atan2(rightMeters, forwardMeters))
                                val targetBearing = normalizeHeading(currentAzimuthDegrees + relativeBearing.toFloat())
                                val (targetLatitude, targetLongitude) = destinationCoordinate(
                                    currentLatitude,
                                    currentLongitude,
                                    targetBearing.toDouble(),
                                    horizontalDistance
                                )
                                val poiDistance = LocationAndOrientationTracker.calculateDistanceMeters(
                                    poiLatitude,
                                    poiLongitude,
                                    targetLatitude,
                                    targetLongitude
                                )
                                if (poiDistance !in minDistanceFromPoiMeters..maxDistanceFromPoiMeters) {
                                    placementError = if (isEn) {
                                        "The selected point is ${poiDistance.roundToInt()} m from the POI. Choose a point between ${minDistanceFromPoiMeters.roundToInt()} and ${maxDistanceFromPoiMeters.roundToInt()} m."
                                    } else {
                                        "Điểm đã chọn cách POI ${poiDistance.roundToInt()} m. Hãy chọn điểm trong khoảng ${minDistanceFromPoiMeters.roundToInt()}–${maxDistanceFromPoiMeters.roundToInt()} m."
                                    }
                                    return@sessionUpdated
                                }

                                val result = ArAnchorPlacementResult(
                                    latitude = targetLatitude,
                                    longitude = targetLongitude,
                                    targetAzimuthDegrees = targetBearing,
                                    poiAzimuthDegrees = LocationAndOrientationTracker.calculateBearingDegrees(
                                        poiLatitude,
                                        poiLongitude,
                                        targetLatitude,
                                        targetLongitude
                                    ),
                                    distanceFromPoiMeters = poiDistance.toFloat(),
                                    elevationMeters = elevationMeters,
                                    scaleFactor = scaleFactor
                                )
                                val anchorPose = Pose(floorHit.hitPose.translation, cameraPose.rotationQuaternion)
                                placement = result
                                placementPose = anchorPose
                                clearPreview()
                                installPreview(session, anchorPose, result, scaleFactor)
                                placementError = null
                            } else if (placementPose != null && placement != null &&
                                (appliedScale != scaleFactor || appliedElevation != elevationMeters)
                            ) {
                                val result = placement?.copy(scaleFactor = scaleFactor) ?: return@sessionUpdated
                                val updatedResult = result.copy(elevationMeters = elevationMeters)
                                clearPreview()
                                installPreview(session, placementPose!!, updatedResult, scaleFactor)
                                placement = updatedResult
                            }
                        },
                        onGestureListener = rememberOnGestureListener(
                            onSingleTapConfirmed = { event: MotionEvent, _ ->
                                pendingTap.set(Offset(event.x, event.y))
                            }
                        )
                    )
                } else {
                    Surface(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = BrandColors.Cream
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(if (isEn) "Camera permission is required for AR placement." else "Cần quyền camera để đặt nội dung trong AR.")
                            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                                Text(if (isEn) "Allow camera" else "Cấp quyền camera")
                            }
                        }
                    }
                }

                // Viewfinder frame stays centered while the user taps the real surface to anchor it.
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(width = (previewWidthMeters * 110f * scaleFactor).coerceIn(108f, 310f).dp,
                            height = ((previewWidthMeters * previewBitmap.height / previewBitmap.width) * 110f * scaleFactor).coerceIn(72f, 240f).dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(2.dp, if (placement != null) BrandColors.Cream else Color.White.copy(alpha = 0.72f), RoundedCornerShape(14.dp))
                ) {
                    // The photo/card preview acts as a translucent viewfinder guide over the live scene.
                    androidx.compose.foundation.Image(
                        bitmap = previewBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().padding(3.dp).clip(RoundedCornerShape(11.dp)),
                        alpha = 0.42f
                    )
                }
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val arm = 12.dp.toPx()
                    drawLine(BrandColors.Cream, Offset(cx - arm, cy), Offset(cx + arm, cy), 2.dp.toPx())
                    drawLine(BrandColors.Cream, Offset(cx, cy - arm), Offset(cx, cy + arm), 2.dp.toPx())
                }

                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = BrandColors.PrimaryDeep.copy(alpha = 0.82f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.TouchApp, contentDescription = null, tint = BrandColors.Cream, modifier = Modifier.size(17.dp))
                        Text(
                            text = when {
                                sessionError != null -> sessionError!!
                                placementError != null -> placementError!!
                                placement != null -> if (isEn) "Placed • ${placement!!.distanceFromPoiMeters.roundToInt()} m from POI" else "Đã đặt • cách POI ${placement!!.distanceFromPoiMeters.roundToInt()} m"
                                !isTracking -> if (isEn) "Move the camera slowly to find a flat surface." else "Lia camera chậm để tìm mặt phẳng phù hợp."
                                else -> if (isEn) "Tap the surface where this content should appear." else "Chạm lên vị trí muốn hiển thị nội dung."
                            },
                            color = Color.White,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = BrandColors.Cream,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isEn) "Size ${"%.2f".format(scaleFactor)}×" else "Kích thước ${"%.2f".format(scaleFactor)}×",
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold,
                            color = BrandColors.PrimaryDeep
                        )
                        OutlinedButton(
                            onClick = { scaleFactor = (scaleFactor - 0.25f).coerceAtLeast(0.5f) },
                            enabled = scaleFactor > 0.5f
                        ) { Icon(Icons.Default.Remove, contentDescription = if (isEn) "Zoom out" else "Thu nhỏ") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { scaleFactor = (scaleFactor + 0.25f).coerceAtMost(3f) },
                            enabled = scaleFactor < 3f
                        ) { Icon(Icons.Default.Add, contentDescription = if (isEn) "Zoom in" else "Phóng to") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isEn) "Height ${"%.1f".format(elevationMeters)} m" else "Độ cao ${"%.1f".format(elevationMeters)} m",
                            modifier = Modifier.width(120.dp),
                            fontWeight = FontWeight.Medium,
                            color = BrandColors.PrimaryDeep,
                            fontSize = 12.sp
                        )
                        Slider(
                            value = elevationMeters,
                            onValueChange = { value ->
                                elevationMeters = value
                                placement = placement?.copy(elevationMeters = value)
                            },
                            valueRange = 0.3f..5f,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        text = if (isEn) "GPS ±${gpsAccuracyMeters.roundToInt()} m • heading ${currentAzimuthDegrees.roundToInt()}°" else "GPS ±${gpsAccuracyMeters.roundToInt()} m • hướng ${currentAzimuthDegrees.roundToInt()}°",
                        color = BrandColors.MutedText,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                            Text(if (isEn) "Cancel" else "Hủy")
                        }
                        Button(
                            onClick = { placement?.let(onConfirm) },
                            modifier = Modifier.weight(1.4f),
                            enabled = placement != null && placementError == null && isTracking &&
                                hasFreshGpsFix && gpsAccuracyMeters <= MaxArPlacementGpsAccuracyMeters
                        ) {
                            Text(if (isEn) "Use this placement" else "Dùng vị trí này")
                        }
                    }
                }
            }
        }
    }
}

private fun normalizeHeading(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f

private const val PlacementEarthRadiusMeters = 6_371_000.0

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
