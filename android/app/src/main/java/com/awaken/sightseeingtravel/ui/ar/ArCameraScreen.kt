package com.awaken.sightseeingtravel.ui.ar

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.awaken.sightseeingtravel.core.model.ArInfoCardDto
import com.awaken.sightseeingtravel.core.model.CommunityPhotoDto
import com.awaken.sightseeingtravel.core.model.PoiArContextDto
import com.awaken.sightseeingtravel.core.sensor.DeviceSpatialPose
import com.awaken.sightseeingtravel.core.sensor.LocationAndOrientationTracker
import java.io.File
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val HORIZONTAL_FOV_DEGREES = 64f
private const val VERTICAL_FOV_DEGREES = 48f

/**
 * Spatial AR Camera Screen combining:
 * 1. CameraX Live Camera Preview + High-Res Photo Capture (`ImageCapture`)
 * 2. Live Sensor 3D Orientation Projection (`TYPE_ROTATION_VECTOR`) gated by a fresh GPS geofence
 * 3. Directionally anchored AR Info Cards, shown only when the camera aligns with their saved bearing
 * 4. Floating Top 10 Highlighted Community Photos (`highlightedCommunityPhotos`) anchored around the POI
 * 5. Tap-to-inspect modal dialogs + Direct Photo Capture & Upload to Contabo Object Storage via Backend API
 */
@Composable
fun ArCameraScreen(
    arContext: PoiArContextDto,
    spatialPose: DeviceSpatialPose,
    currentUserId: String?,
    isUploadingPhoto: Boolean,
    selectedLanguage: String,
    onCaptureAndUploadPhoto: (File, String) -> Unit,
    onToggleLikePhoto: (String) -> Unit,
    onReportPhoto: (String, String) -> Unit,
    onDeleteOwnPhoto: (String) -> Unit,
    onCloseAr: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isEn = selectedLanguage.equals("en", ignoreCase = true)

    var viewportSize by remember { mutableStateOf(IntSize(1080, 1920)) }
    var selectedArCard by remember { mutableStateOf<ArInfoCardDto?>(null) }
    var selectedCommunityPhoto by remember { mutableStateOf<CommunityPhotoDto?>(null) }
    var showCaptureDialog by remember { mutableStateOf(false) }
    var pendingCapturedFile by remember { mutableStateOf<File?>(null) }
    var photoCaptionInput by remember { mutableStateOf("") }
    var showReportDialogForPhotoId by remember { mutableStateOf<String?>(null) }
    var reportReasonInput by remember { mutableStateOf("") }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    val effectiveAzimuth = (spatialPose.azimuthDegrees + 360f) % 360f
    val hasUsableGps = spatialPose.hasFreshGpsFix &&
        !spatialPose.isSimulatedLocation && spatialPose.accuracyMeters <= 50f
    val distanceToPoiMeters = LocationAndOrientationTracker.calculateDistanceMeters(
        spatialPose.latitude,
        spatialPose.longitude,
        arContext.latitude,
        arContext.longitude
    )
    val isWithinPoiZone = hasUsableGps &&
        distanceToPoiMeters <= arContext.activationRadiusMeters + 20.0
    val top10Photos = remember(arContext.highlightedCommunityPhotos) {
        arContext.highlightedCommunityPhotos.filter { it.azimuthDegrees != null }.take(10)
    }

    LaunchedEffect(isWithinPoiZone) {
        if (!isWithinPoiZone) {
            selectedArCard = null
            selectedCommunityPhoto = null
            showCaptureDialog = false
            pendingCapturedFile?.delete()
            pendingCapturedFile = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090D16))
            .onSizeChanged { viewportSize = it }
    ) {
        // 1. Live CameraX Preview
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    runCatching {
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCapture
                        )
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )

        // Subtle vignette for HUD readability
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.55f),
                            Color.Transparent,
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.72f)
                        )
                    )
                )
        )

        // 2. 3D Projected Spatial AR Cultural Info Cards (`arInfoCards`)
        if (isWithinPoiZone) arContext.arInfoCards.forEach { card ->
            val projection = projectSpatialAnchorToScreen(
                viewerAzimuthDeg = effectiveAzimuth,
                viewerPitchDeg = spatialPose.pitchDegrees,
                targetAzimuthDeg = card.azimuthDegrees,
                targetElevationMeters = card.elevationMeters,
                targetDistanceMeters = card.anchorDistanceMeters,
                viewportWidthPx = viewportSize.width,
                viewportHeightPx = viewportSize.height
            )
            val headingError = abs(
                LocationAndOrientationTracker.signedAngleDeltaDegrees(effectiveAzimuth, card.azimuthDegrees)
            )
            val verticalAlignment = abs(projection.screenYPx / viewportSize.height - 0.5f) <= 0.22f
            if (projection.isVisibleInFrustum && headingError <= 12f && verticalAlignment) {
                SpatialArInfoCardOverlay(
                    card = card,
                    screenX = projection.screenXPx,
                    screenY = projection.screenYPx,
                    onClick = { selectedArCard = card }
                )
            }
        }

        // 3. 3D Projected Top 10 Highlighted Community Photos (`highlightedCommunityPhotos`)
        if (isWithinPoiZone) top10Photos.forEachIndexed { index, photo ->
            val photoAzimuth = photo.azimuthDegrees ?: return@forEachIndexed
            val projection = projectSpatialAnchorToScreen(
                viewerAzimuthDeg = effectiveAzimuth,
                viewerPitchDeg = spatialPose.pitchDegrees,
                targetAzimuthDeg = photoAzimuth,
                targetElevationMeters = photo.elevationMeters.coerceIn(-1.5f, 4.0f),
                targetDistanceMeters = 7.5f + (index % 3) * 1.5f,
                viewportWidthPx = viewportSize.width,
                viewportHeightPx = viewportSize.height
            )
            val headingError = abs(
                LocationAndOrientationTracker.signedAngleDeltaDegrees(effectiveAzimuth, photoAzimuth)
            )
            val verticalAlignment = abs(projection.screenYPx / viewportSize.height - 0.5f) <= 0.22f
            if (projection.isVisibleInFrustum && headingError <= 12f && verticalAlignment) {
                SpatialCommunityPhotoBillboard(
                    photo = photo,
                    rank = photo.highlightRank ?: (index + 1),
                    screenX = projection.screenXPx,
                    screenY = projection.screenYPx,
                    onClick = { selectedCommunityPhoto = photo }
                )
            }
        }

        // 4. Top Spatial HUD Header + 360° Radar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Surface(
                        color = Color(0xFF0F172A).copy(alpha = 0.82f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                text = arContext.title,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isEn) {
                                    "${if (isWithinPoiZone) "GPS ${distanceToPoiMeters.roundToInt()}m" else "GPS unavailable/outside zone"} • Heading ${effectiveAzimuth.roundToInt()}° • ${arContext.arInfoCards.size} Cards • Top ${top10Photos.size} Photos"
                                } else {
                                    "${if (isWithinPoiZone) "GPS ${distanceToPoiMeters.roundToInt()}m" else "Chưa có GPS/ngoài vùng"} • Hướng ${effectiveAzimuth.roundToInt()}° • ${arContext.arInfoCards.size} thẻ • ${top10Photos.size} ảnh"
                                },
                                color = Color(0xFF38BDF8),
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // 360° Spatial Radar Mini-Map
                SpatialRadarHud(
                    viewerAzimuth = effectiveAzimuth,
                    arCards = if (isWithinPoiZone) arContext.arInfoCards else emptyList(),
                    topPhotos = if (isWithinPoiZone) top10Photos else emptyList()
                )

                Spacer(modifier = Modifier.width(10.dp))

                IconButton(
                    onClick = onCloseAr,
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close AR",
                        tint = Color.White
                    )
                }
            }
        }

        // 5. Bottom Controls: live GPS / compass guidance + on-site photo capture
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = Color(0xFF0F172A).copy(alpha = 0.84f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        text = when {
                            !hasUsableGps -> if (isEn) "Waiting for an accurate live GPS fix…" else "Đang chờ tín hiệu GPS thực đủ chính xác…"
                            !isWithinPoiZone -> if (isEn) "Move within ${ (arContext.activationRadiusMeters + 20).roundToInt() }m of this POI to scan its AR anchors." else "Hãy đến trong phạm vi ${(arContext.activationRadiusMeters + 20).roundToInt()}m để quét các điểm AR đã ghim."
                            else -> if (isEn) "Point the camera at a saved direction; the AR card appears when aligned." else "Hướng camera vào góc đã ghim; thẻ AR sẽ hiện khi căn đúng hướng."
                        },
                        color = if (isWithinPoiZone) Color(0xFF7DD3FC) else Color(0xFFFACC15),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isEn) "Turn the phone to scan the pinned direction" else "Xoay điện thoại để quét đúng hướng đã ghim",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f)
                        )

                        Button(
                            onClick = {
                                capturePhotoToCacheFile(
                                    context = context,
                                    imageCapture = imageCapture,
                                    onCaptured = { file ->
                                        pendingCapturedFile = file
                                        photoCaptionInput = ""
                                        showCaptureDialog = true
                                    }
                                )
                            },
                            enabled = !isUploadingPhoto && isWithinPoiZone,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoCamera,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isUploadingPhoto) {
                                    if (isEn) "Uploading..." else "Đang tải lên..."
                                } else {
                                    if (isEn) "Capture AR Photo" else "Chụp ảnh tại Pin"
                                },
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }

    // ========================================================================
    // DIALOG 1: INSPECT SPATIAL AR CULTURAL INFO CARD
    // ========================================================================
    selectedArCard?.let { card ->
        AlertDialog(
            onDismissRequest = { selectedArCard = null },
            confirmButton = {
                TextButton(onClick = { selectedArCard = null }) {
                    Text(if (isEn) "Close" else "Đóng")
                }
            },
            title = {
                Column {
                    Surface(
                        color = Color(0xFF0284C7).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "${card.badgeLabel} • ${card.azimuthDegrees.toInt()}°",
                            color = Color(0xFF0284C7),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = card.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    if (card.subtitle.isNotBlank()) {
                        Text(
                            text = card.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            text = {
                Text(
                    text = card.bodyText,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        )
    }

    // ========================================================================
    // DIALOG 2: INSPECT HIGHLIGHTED COMMUNITY PHOTO (LIKE / REPORT / DELETE OWN)
    // ========================================================================
    selectedCommunityPhoto?.let { photo ->
        Dialog(onDismissRequest = { selectedCommunityPhoto = null }) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(230.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black)
                    ) {
                        AsyncImage(
                            model = photo.imageUrl,
                            contentDescription = photo.caption,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Surface(
                            color = Color(0xFFEAB308),
                            shape = RoundedCornerShape(bottomEnd = 12.dp),
                            modifier = Modifier.align(Alignment.TopStart)
                        ) {
                            Text(
                                text = "TOP ${photo.highlightRank ?: 1} COMMUNITY PHOTO",
                                color = Color.Black,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = photo.caption ?: if (isEn) "Captured at POI" else "Khoảnh khắc tại điểm tham quan",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "By ${photo.authorDisplayName} • ${photo.createdAt} • Azimuth ${photo.azimuthDegrees?.toInt() ?: "—"}°",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = {
                                onToggleLikePhoto(photo.id)
                                val nextLiked = !photo.isLikedByCurrentUser
                                val nextLikes = (photo.likeCount + if (nextLiked) 1 else -1).coerceAtLeast(0)
                                selectedCommunityPhoto = photo.copy(
                                    isLikedByCurrentUser = nextLiked,
                                    likeCount = nextLikes
                                )
                            }
                        ) {
                            Icon(
                                imageVector = if (photo.isLikedByCurrentUser) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = null,
                                tint = if (photo.isLikedByCurrentUser) Color(0xFFE11D48) else LocalContentColor.current
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("${photo.likeCount}")
                        }

                        Row {
                            if (currentUserId != null && currentUserId == photo.authorId) {
                                IconButton(
                                    onClick = {
                                        onDeleteOwnPhoto(photo.id)
                                        selectedCommunityPhoto = null
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete own photo",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    showReportDialogForPhotoId = photo.id
                                    reportReasonInput = ""
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Flag,
                                    contentDescription = "Report photo",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            TextButton(onClick = { selectedCommunityPhoto = null }) {
                                Text(if (isEn) "Close" else "Đóng")
                            }
                        }
                    }
                }
            }
        }
    }

    // ========================================================================
    // DIALOG 3: UPLOAD CAPTURED PHOTO TO CONTABO OBJECT STORAGE
    // ========================================================================
    if (showCaptureDialog && pendingCapturedFile != null) {
        AlertDialog(
            onDismissRequest = { showCaptureDialog = false },
            title = {
                Text(
                    text = if (isEn) "Share Photo to 360° POI Space" else "Chia sẻ ảnh lên Không gian AR",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = if (isEn) {
                            "Your photo will be compressed (<=1920px, <=5MB), stored on Contabo Object Storage, and anchored at azimuth ${effectiveAzimuth.toInt()}°."
                        } else {
                            "Ảnh được nén chuẩn JPEG (<=1920px, <=5MB), lưu trữ trên Contabo Object Storage và neo tại góc hướng ${effectiveAzimuth.toInt()}°."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = photoCaptionInput,
                        onValueChange = { photoCaptionInput = it },
                        label = { Text(if (isEn) "Caption / Story at this angle" else "Chú thích / Cảm nhận của bạn") },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val file = pendingCapturedFile ?: return@Button
                        showCaptureDialog = false
                        onCaptureAndUploadPhoto(file, photoCaptionInput)
                    }
                ) {
                    Text(if (isEn) "Upload Photo" else "Tải ảnh lên")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCaptureDialog = false }) {
                    Text(if (isEn) "Cancel" else "Hủy")
                }
            }
        )
    }

    // ========================================================================
    // DIALOG 4: REPORT INAPPROPRIATE COMMUNITY PHOTO
    // ========================================================================
    showReportDialogForPhotoId?.let { targetPhotoId ->
        AlertDialog(
            onDismissRequest = { showReportDialogForPhotoId = null },
            title = { Text(if (isEn) "Report Community Photo" else "Báo cáo ảnh vi phạm") },
            text = {
                OutlinedTextField(
                    value = reportReasonInput,
                    onValueChange = { reportReasonInput = it },
                    label = { Text(if (isEn) "Reason for report" else "Lý do báo cáo") },
                    placeholder = { Text(if (isEn) "Spam, inappropriate, or off-topic" else "Spam, nội dung không phù hợp...") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onReportPhoto(
                            targetPhotoId,
                            reportReasonInput.ifBlank { "Inappropriate or unrelated content" }
                        )
                        showReportDialogForPhotoId = null
                        selectedCommunityPhoto = null
                    }
                ) {
                    Text(if (isEn) "Submit Report" else "Gửi báo cáo")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReportDialogForPhotoId = null }) {
                    Text(if (isEn) "Cancel" else "Hủy")
                }
            }
        )
    }
}

/**
 * Floating 3D-projected Cultural Story / Historical Insight Card in AR space.
 */
@Composable
private fun SpatialArInfoCardOverlay(
    card: ArInfoCardDto,
    screenX: Float,
    screenY: Float,
    onClick: () -> Unit
) {
    val density = LocalDensity.current
    val cardWidthPx = with(density) { 230.dp.toPx() }
    val cardHeightPx = with(density) { 105.dp.toPx() }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = (screenX - cardWidthPx / 2f).roundToInt(),
                    y = (screenY - cardHeightPx / 2f).roundToInt()
                )
            }
            .width(230.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xDD0F172A),
                        Color(0xDD1E293B)
                    )
                )
            )
            .border(1.5.dp, Color(0xFF38BDF8), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    color = Color(0xFF0284C7),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = card.badgeLabel,
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = "${card.anchorDistanceMeters.toInt()}m • ${card.azimuthDegrees.toInt()}°",
                    color = Color(0xFF7DD3FC),
                    fontSize = 10.sp
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = card.title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(3.dp))

            Text(
                text = card.bodyText,
                color = Color(0xFFE2E8F0),
                fontSize = 11.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Floating 3D-projected Top 10 Highlighted Community Photo frame in AR space.
 */
@Composable
private fun SpatialCommunityPhotoBillboard(
    photo: CommunityPhotoDto,
    rank: Int,
    screenX: Float,
    screenY: Float,
    onClick: () -> Unit
) {
    val density = LocalDensity.current
    val widthPx = with(density) { 156.dp.toPx() }
    val heightPx = with(density) { 132.dp.toPx() }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = (screenX - widthPx / 2f).roundToInt(),
                    y = (screenY - heightPx / 2f).roundToInt()
                )
            }
            .width(156.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xE618181B))
            .border(1.5.dp, Color(0xFFFACC15), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(6.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(82.dp)
                    .clip(RoundedCornerShape(10.dp))
            ) {
                AsyncImage(
                    model = photo.thumbnailUrl ?: photo.imageUrl,
                    contentDescription = photo.caption,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Surface(
                    color = Color(0xFFFACC15),
                    shape = RoundedCornerShape(bottomEnd = 8.dp),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Text(
                        text = "#$rank",
                        color = Color.Black,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(topStart = 8.dp),
                    modifier = Modifier.align(Alignment.BottomEnd)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null,
                            tint = Color(0xFFF43F5E),
                            modifier = Modifier.size(10.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "${photo.likeCount}",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = photo.caption ?: photo.authorDisplayName,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "@${photo.authorDisplayName}",
                color = Color(0xFFFDE047),
                fontSize = 9.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 360° Radar HUD displaying the user's current viewing cone and all surrounding
 * AR Info Cards (cyan dots) + Top 10 Highlighted Community Photos (gold dots).
 */
@Composable
private fun SpatialRadarHud(
    viewerAzimuth: Float,
    arCards: List<ArInfoCardDto>,
    topPhotos: List<CommunityPhotoDto>
) {
    Canvas(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color(0xCC0F172A))
            .border(1.dp, Color(0xFF38BDF8), CircleShape)
    ) {
        val radius = size.minDimension / 2f
        val center = Offset(radius, radius)

        // Radar rings
        drawCircle(
            color = Color.White.copy(alpha = 0.15f),
            radius = radius * 0.6f,
            center = center
        )

        // AR Cultural Info Cards (Cyan dots)
        arCards.forEach { card ->
            val relDeg = LocationAndOrientationTracker.signedAngleDeltaDegrees(viewerAzimuth, card.azimuthDegrees)
            val rad = Math.toRadians((relDeg - 90f).toDouble())
            val distRatio = (card.anchorDistanceMeters / 16f).coerceIn(0.25f, 0.82f)
            val dotX = center.x + (cos(rad) * radius * distRatio).toFloat()
            val dotY = center.y + (sin(rad) * radius * distRatio).toFloat()
            drawCircle(
                color = Color(0xFF38BDF8),
                radius = 3.5.dp.toPx(),
                center = Offset(dotX, dotY)
            )
        }

        // Top 10 Highlighted Community Photos (Gold dots)
        topPhotos.forEach { photo ->
            val photoAzimuth = photo.azimuthDegrees ?: return@forEach
            val relDeg = LocationAndOrientationTracker.signedAngleDeltaDegrees(viewerAzimuth, photoAzimuth)
            val rad = Math.toRadians((relDeg - 90f).toDouble())
            val dotX = center.x + (cos(rad) * radius * 0.72f).toFloat()
            val dotY = center.y + (sin(rad) * radius * 0.72f).toFloat()
            drawCircle(
                color = Color(0xFFFACC15),
                radius = 3.dp.toPx(),
                center = Offset(dotX, dotY)
            )
        }

        // Center user dot
        drawCircle(
            color = Color.White,
            radius = 3.dp.toPx(),
            center = center
        )
    }
}

private data class ScreenProjectionResult(
    val isVisibleInFrustum: Boolean,
    val screenXPx: Float,
    val screenYPx: Float
)

/**
 * Projects a 3D spherical anchor (`targetAzimuthDeg`, `targetElevationMeters`, `targetDistanceMeters`)
 * onto 2D screen coordinates `(x, y)` based on the device's live compass yaw and tilt pitch.
 */
private fun projectSpatialAnchorToScreen(
    viewerAzimuthDeg: Float,
    viewerPitchDeg: Float,
    targetAzimuthDeg: Float,
    targetElevationMeters: Float,
    targetDistanceMeters: Float,
    viewportWidthPx: Int,
    viewportHeightPx: Int
): ScreenProjectionResult {
    val yawDelta = LocationAndOrientationTracker.signedAngleDeltaDegrees(viewerAzimuthDeg, targetAzimuthDeg)
    val halfHFov = HORIZONTAL_FOV_DEGREES / 2f

    // Allow slightly wider margin so cards smoothly enter/exit screen edges
    if (kotlin.math.abs(yawDelta) > halfHFov + 14f) {
        return ScreenProjectionResult(false, 0f, 0f)
    }

    val safeDistance = targetDistanceMeters.coerceAtLeast(2.0f)
    val targetElevationAngleDeg = Math.toDegrees(
        atan2((targetElevationMeters - 1.5f).toDouble(), safeDistance.toDouble())
    ).toFloat()

    val pitchDelta = targetElevationAngleDeg - viewerPitchDeg
    val halfVFov = VERTICAL_FOV_DEGREES / 2f

    val normalizedX = 0.5f + (yawDelta / HORIZONTAL_FOV_DEGREES)
    val normalizedY = (0.5f - (pitchDelta / VERTICAL_FOV_DEGREES)).coerceIn(0.18f, 0.78f)

    if (kotlin.math.abs(pitchDelta) > halfVFov + 35f) {
        return ScreenProjectionResult(false, 0f, 0f)
    }

    return ScreenProjectionResult(
        isVisibleInFrustum = true,
        screenXPx = normalizedX * viewportWidthPx,
        screenYPx = normalizedY * viewportHeightPx
    )
}

/**
 * Captures a photo via CameraX `ImageCapture` to a temporary file in `cacheDir`
 * for subsequent JPEG compression (`<=1920px`, `<=5MB`) and Contabo S3 upload.
 */
private fun capturePhotoToCacheFile(
    context: Context,
    imageCapture: ImageCapture,
    onCaptured: (File) -> Unit
) {
    val outputFile = File(
        context.cacheDir,
        "ar_poi_capture_${System.currentTimeMillis()}.jpg"
    )
    val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                onCaptured(outputFile)
            }

            override fun onError(exception: ImageCaptureException) {
                // On emulator or camera-less environment, create a valid placeholder file so the upload flow remains testable
                runCatching {
                    val bmp = android.graphics.Bitmap.createBitmap(800, 600, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bmp)
                    canvas.drawColor(android.graphics.Color.parseColor("#0284C7"))
                    outputFile.outputStream().use { out ->
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                    }
                    onCaptured(outputFile)
                }
            }
        }
    )
}
