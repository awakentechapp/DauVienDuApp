package com.awaken.sightseeingtravel.ui.ar

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.awaken.sightseeingtravel.core.model.ArInfoCardDto
import com.awaken.sightseeingtravel.core.model.CommunityPhotoDto
import com.awaken.sightseeingtravel.core.model.PoiArContextDto
import com.awaken.sightseeingtravel.core.sensor.DeviceSpatialPose
import com.awaken.sightseeingtravel.core.sensor.LocationAndOrientationTracker
import com.awaken.sightseeingtravel.ui.BrandColors
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.ImageNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberNodes
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.math.Size
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val EARTH_RADIUS_METERS = 6_371_000.0

/**
 * Spatial AR Camera Screen combining:
 * ARCore camera tracking with GPS-derived world anchors for info cards and community photos.
 * Each marker is a textured 3D plane anchored for the lifetime of the current AR session.
 */
@Composable
fun ArCameraScreen(
    arContext: PoiArContextDto,
    spatialPose: DeviceSpatialPose,
    currentUserId: String?,
    selectedLanguage: String,
    onOpenSelfieCamera: () -> Unit,
    onToggleLikePhoto: (String) -> Unit,
    onReportPhoto: (String, String) -> Unit,
    onDeleteOwnPhoto: (String) -> Unit,
    onCloseAr: () -> Unit
) {
    val context = LocalContext.current
    val isEn = selectedLanguage.equals("en", ignoreCase = true)

    var selectedArCard by remember { mutableStateOf<ArInfoCardDto?>(null) }
    var selectedCommunityPhoto by remember { mutableStateOf<CommunityPhotoDto?>(null) }
    var fullScreenPhoto by remember { mutableStateOf<CommunityPhotoDto?>(null) }
    var showReportDialogForPhotoId by remember { mutableStateOf<String?>(null) }
    var reportReasonInput by remember { mutableStateOf("") }
    var arSessionError by remember { mutableStateOf<String?>(null) }
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val childNodes = rememberNodes()
    var arTargetsInstalled by remember(arContext.poiId) { mutableStateOf(false) }
    var photoBitmaps by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var photoBitmapsLoaded by remember { mutableStateOf(false) }
    val cardModelInstances = remember { mutableStateMapOf<String, ModelInstance>() }
    var cardModelsLoaded by remember(arContext.poiId) { mutableStateOf(false) }
    val imageLoader = remember(context) { context.imageLoader }

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

    LaunchedEffect(top10Photos) {
        photoBitmapsLoaded = false
        photoBitmaps = emptyMap()
        photoBitmaps = buildMap {
            top10Photos.forEach { photo ->
                runCatching {
                    val request = ImageRequest.Builder(context)
                        .data(photo.thumbnailUrl ?: photo.imageUrl)
                        .size(640, 480)
                        .allowHardware(false)
                        .build()
                    val result = imageLoader.execute(request)
                    result.drawable?.toBitmap(config = Bitmap.Config.ARGB_8888)?.let { source ->
                        put(photo.id, createSpatialPhotoBitmap(source, photo.caption, photo.highlightRank ?: (top10Photos.indexOf(photo) + 1)))
                    }
                }
            }
        }
        photoBitmapsLoaded = true
    }

    val spatialCardBitmaps = remember(arContext.arInfoCards) {
        arContext.arInfoCards.associate { it.id to createSpatialInfoCardBitmap(it) }
    }

    LaunchedEffect(arContext.arInfoCards.map { it.id to it.modelUrl }) {
        cardModelsLoaded = false
        cardModelInstances.clear()
        arContext.arInfoCards.forEach { card ->
            val modelUrl = card.modelUrl ?: return@forEach
            runCatching { modelLoader.loadModelInstance(modelUrl) }
                .getOrNull()
                ?.let { cardModelInstances[card.id] = it }
        }
        cardModelsLoaded = true
    }

    LaunchedEffect(isWithinPoiZone) {
        if (!isWithinPoiZone) {
            childNodes.toList().forEach { runCatching { it.destroy() } }
            childNodes.clear()
            arTargetsInstalled = false
            selectedArCard = null
            selectedCommunityPhoto = null
        }
    }

    BackHandler(enabled = fullScreenPhoto != null) {
        fullScreenPhoto = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandColors.PrimaryDeep)
    ) {
        ARScene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            childNodes = childNodes,
            planeRenderer = false,
            onSessionFailed = { error -> arSessionError = error.localizedMessage ?: error.javaClass.simpleName },
            onSessionUpdated = { session, frame ->
                if (!arTargetsInstalled && isWithinPoiZone && photoBitmapsLoaded && cardModelsLoaded &&
                    frame.camera.trackingState == TrackingState.TRACKING
                ) {
                    runCatching {
                        val cameraPose = frame.camera.pose
                        val targets = buildList {
                            arContext.arInfoCards.forEach { card ->
                                val (latitude, longitude) = destinationCoordinate(
                                    arContext.latitude,
                                    arContext.longitude,
                                    card.azimuthDegrees.toDouble(),
                                    card.anchorDistanceMeters.toDouble()
                                )
                                val modelInstance = cardModelInstances[card.id]
                                if (modelInstance != null) {
                                    add(SpatialArImageTarget(
                                        "card:${card.id}", latitude, longitude, card.elevationMeters,
                                        0.88f,
                                        bitmap = null,
                                        modelInstance = modelInstance,
                                        label = card.title,
                                        modelRotationDegrees = card.modelRotationDegrees
                                    ))
                                } else {
                                    spatialCardBitmaps[card.id]?.let { bitmap ->
                                        add(SpatialArImageTarget("card:${card.id}", latitude, longitude, card.elevationMeters, 0.88f, bitmap))
                                    }
                                }
                            }
                            top10Photos.forEach { photo ->
                                val bitmap = photoBitmaps[photo.id] ?: return@forEach
                                val hasSavedCoordinates = photo.capturedLatitude in -90.0..90.0 &&
                                    photo.capturedLongitude in -180.0..180.0 &&
                                    (photo.capturedLatitude != 0.0 || photo.capturedLongitude != 0.0)
                                val (latitude, longitude) = if (hasSavedCoordinates) {
                                    photo.capturedLatitude to photo.capturedLongitude
                                } else {
                                    destinationCoordinate(
                                        arContext.latitude,
                                        arContext.longitude,
                                        photo.azimuthDegrees?.toDouble() ?: 0.0,
                                        7.5 + (top10Photos.indexOf(photo).coerceAtLeast(0) % 3) * 1.5
                                    )
                                }
                                add(SpatialArImageTarget("photo:${photo.id}", latitude, longitude, photo.elevationMeters, 0.82f, bitmap))
                            }
                        }
                        targets.forEach { target ->
                            val anchorPose = createGeographicAnchorPose(
                                cameraPose = cameraPose,
                                originLatitude = spatialPose.latitude,
                                originLongitude = spatialPose.longitude,
                                originHeadingDegrees = effectiveAzimuth,
                                target = target
                            )
                            val anchorNode = AnchorNode(engine, session.createAnchor(anchorPose))
                            anchorNode.isTouchable = false
                            val spatialNode = target.modelInstance?.let { modelInstance ->
                                ModelNode(
                                    modelInstance = modelInstance,
                                    scaleToUnits = 1.2f,
                                    centerOrigin = Position(y = -0.5f)
                                ).apply {
                                    name = target.id
                                    rotation = Rotation(
                                        y = LocationAndOrientationTracker.signedAngleDeltaDegrees(
                                            effectiveAzimuth,
                                            target.modelRotationDegrees
                                        )
                                    )
                                    isTouchable = true
                                }
                            } ?: target.bitmap?.let { bitmap ->
                                val height = (target.widthMeters * bitmap.height / bitmap.width)
                                    .coerceIn(0.28f, 0.95f)
                                ImageNode(
                                    materialLoader = materialLoader,
                                    bitmap = bitmap,
                                    size = Size(target.widthMeters, height)
                                ).apply {
                                    name = target.id
                                    isTouchable = true
                                }
                            }
                            if (spatialNode != null) anchorNode.addChildNode(spatialNode)
                            if (target.modelInstance != null && !target.label.isNullOrBlank()) {
                                val labelNode = ImageNode(
                                    materialLoader = materialLoader,
                                    bitmap = createSpatialModelLabelBitmap(target.label),
                                    size = Size(1.5f, 0.28f)
                                ).apply {
                                    position = Position(y = 1.45f)
                                    isTouchable = false
                                }
                                anchorNode.addChildNode(labelNode)
                            }
                            childNodes.add(anchorNode)
                        }
                        arTargetsInstalled = true
                    }.onFailure { arSessionError = it.localizedMessage ?: "Unable to create AR anchors" }
                }

            },
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { _, node ->
                    when {
                        node?.name?.startsWith("card:") == true -> {
                            val id = node.name!!.removePrefix("card:")
                            selectedArCard = arContext.arInfoCards.firstOrNull { it.id == id }
                        }
                        node?.name?.startsWith("photo:") == true -> {
                            val id = node.name!!.removePrefix("photo:")
                            selectedCommunityPhoto = top10Photos.firstOrNull { it.id == id }
                        }
                    }
                }
            )
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

        // Top Spatial HUD Header + 360° Radar
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
                        color = BrandColors.PrimaryDeep.copy(alpha = 0.88f),
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
                                color = BrandColors.Cream,
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

        // 5. Bottom Controls: live GPS / compass guidance + separate selfie capture
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = BrandColors.PrimaryDeep.copy(alpha = 0.9f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        text = when {
                            arSessionError != null -> arSessionError!!
                            !hasUsableGps -> if (isEn) "Waiting for an accurate live GPS fix…" else "Đang chờ tín hiệu GPS thực đủ chính xác…"
                            !isWithinPoiZone -> if (isEn) "Move within ${ (arContext.activationRadiusMeters + 20).roundToInt() }m of this POI to scan its AR anchors." else "Hãy đến trong phạm vi ${(arContext.activationRadiusMeters + 20).roundToInt()}m để quét các điểm AR đã ghim."
                            else -> if (isEn) "Turn toward a saved AR location to see its anchored 3D card or photo." else "Xoay camera về vị trí AR đã ghim để thấy thẻ hoặc ảnh neo trong không gian 3D."
                        },
                        color = when {
                            arSessionError != null -> BrandColors.Error
                            isWithinPoiZone -> BrandColors.Cream
                            else -> BrandColors.Terracotta
                        },
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
                            text = if (isEn) "Anchors stay fixed as you turn the camera" else "Các điểm AR đứng yên khi bạn xoay camera",
                            color = BrandColors.Cream.copy(alpha = 0.8f),
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f)
                        )

                        Button(
                            onClick = onOpenSelfieCamera,
                            enabled = isWithinPoiZone,
                            colors = ButtonDefaults.buttonColors(containerColor = BrandColors.Terracotta),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoCamera,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isEn) "Take a selfie" else "Chụp selfie tại Pin",
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
                        color = BrandColors.Terracotta.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "${card.badgeLabel} • ${card.azimuthDegrees.toInt()}°",
                            color = BrandColors.Cream,
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
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable { fullScreenPhoto = photo }
                        )
                        Surface(
                            color = BrandColors.Cream,
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
                        text = if (isEn) {
                            "${formatCapturedDate(photo.createdAt)} • Azimuth ${photo.azimuthDegrees?.toInt() ?: "—"}°"
                        } else {
                            "Ngày chụp ${formatCapturedDate(photo.createdAt)} • Hướng ${photo.azimuthDegrees?.toInt() ?: "—"}°"
                        },
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
                                tint = if (photo.isLikedByCurrentUser) BrandColors.Terracotta else LocalContentColor.current
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

    fullScreenPhoto?.let { photo ->
        val swipeBackThresholdPx = with(LocalDensity.current) { 88.dp.toPx() }
        Dialog(
            onDismissRequest = { fullScreenPhoto = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(photo.id, swipeBackThresholdPx) {
                        var rightwardDrag = 0f
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { _, dragAmount ->
                                if (dragAmount > 0f) rightwardDrag += dragAmount
                            },
                            onDragEnd = {
                                if (rightwardDrag >= swipeBackThresholdPx) fullScreenPhoto = null
                                rightwardDrag = 0f
                            },
                            onDragCancel = { rightwardDrag = 0f }
                        )
                    }
            ) {
                AsyncImage(
                    model = photo.imageUrl,
                    contentDescription = photo.caption ?: if (isEn) "Full-screen community photo" else "Ảnh cộng đồng toàn màn hình",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                IconButton(
                    onClick = { fullScreenPhoto = null },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(start = 12.dp, top = 8.dp)
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.58f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = if (isEn) "Back" else "Quay lại",
                        tint = Color.White
                    )
                }
            }
        }
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
            .background(BrandColors.PrimaryDeep.copy(alpha = 0.82f))
            .border(1.dp, BrandColors.Cream, CircleShape)
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
                color = BrandColors.Cream,
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
                color = BrandColors.Terracotta,
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

private data class SpatialArImageTarget(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Float,
    val widthMeters: Float,
    val bitmap: Bitmap?,
    val modelInstance: ModelInstance? = null,
    val label: String? = null,
    val modelRotationDegrees: Float = 0f
)

private fun createSpatialModelLabelBitmap(label: String): Bitmap {
    val bitmap = Bitmap.createBitmap(900, 168, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    canvas.drawRoundRect(
        RectF(8f, 8f, 892f, 160f),
        44f,
        44f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BrandColors.PrimaryDeep.copy(alpha = 0.88f).toArgb() }
    )
    canvas.drawRoundRect(
        RectF(10f, 10f, 890f, 158f),
        42f,
        42f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BrandColors.Cream.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
    )
    val safeLabel = label.trim().replace(Regex("\\s+"), " ").take(45)
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 68f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val textWidth = textPaint.measureText(safeLabel)
    if (textWidth > 800f) textPaint.textSize *= 800f / textWidth
    canvas.drawText(safeLabel, bitmap.width / 2f, 108f, textPaint)
    return bitmap
}

private fun createSpatialInfoCardBitmap(card: ArInfoCardDto): Bitmap {
    val bitmap = Bitmap.createBitmap(1000, 620, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BrandColors.PrimaryDeep.toArgb() }
    canvas.drawRoundRect(RectF(0f, 0f, 1000f, 620f), 48f, 48f, background)
    canvas.drawRoundRect(
        RectF(4f, 4f, 996f, 616f), 44f, 44f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.TRANSPARENT
            style = Paint.Style.STROKE
            strokeWidth = 8f
            color = BrandColors.Cream.toArgb()
        }
    )
    canvas.drawRoundRect(
        RectF(40f, 34f, 400f, 104f), 20f, 20f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BrandColors.Terracotta.toArgb() }
    )
    val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 32f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    canvas.drawText(card.badgeLabel.take(26), 62f, 80f, badgePaint)

    val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 56f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    drawWrappedText(canvas, card.title, 50f, 185f, 900f, headingPaint, 62f, 2)

    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BrandColors.Cream.copy(alpha = 0.92f).toArgb()
        textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    drawWrappedText(canvas, card.bodyText, 52f, 330f, 890f, bodyPaint, 48f, 4)

    val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BrandColors.Cream.toArgb()
        textSize = 30f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    canvas.drawText("${card.anchorDistanceMeters.toInt()} m  •  ${card.azimuthDegrees.toInt()}°", 54f, 570f, footerPaint)
    return bitmap
}

private fun createSpatialPhotoBitmap(source: Bitmap, caption: String?, rank: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(900, 650, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    canvas.drawRoundRect(
        RectF(0f, 0f, 900f, 650f), 36f, 36f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BrandColors.PrimaryDeep.toArgb() }
    )
    val imageBounds = RectF(24f, 24f, 876f, 510f)
    val scale = min(imageBounds.width() / source.width, imageBounds.height() / source.height)
    val width = source.width * scale
    val height = source.height * scale
    val destination = RectF(
        imageBounds.centerX() - width / 2f,
        imageBounds.centerY() - height / 2f,
        imageBounds.centerX() + width / 2f,
        imageBounds.centerY() + height / 2f
    )
    canvas.drawBitmap(source, null, destination, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    canvas.drawRoundRect(
        RectF(30f, 30f, 150f, 108f), 20f, 20f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BrandColors.Cream.toArgb() }
    )
    canvas.drawText(
        "#$rank", 52f, 82f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BrandColors.PrimaryDeep.toArgb()
            textSize = 40f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    )
    canvas.drawRect(
        RectF(20f, 520f, 880f, 640f),
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BrandColors.PrimaryDeep.toArgb() }
    )
    if (!caption.isNullOrBlank()) {
        val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        drawWrappedText(canvas, caption, 42f, 572f, 810f, captionPaint, 40f, 2)
    }
    return bitmap
}

private fun drawWrappedText(
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
    text.trim().split(Regex("\\s+")).forEach { word ->
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

private fun destinationCoordinate(
    latitude: Double,
    longitude: Double,
    bearingDegrees: Double,
    distanceMeters: Double
): Pair<Double, Double> {
    val angularDistance = distanceMeters / EARTH_RADIUS_METERS
    val bearing = Math.toRadians(bearingDegrees)
    val lat1 = Math.toRadians(latitude)
    val lon1 = Math.toRadians(longitude)
    val lat2 = kotlin.math.asin(
        sin(lat1) * cos(angularDistance) + cos(lat1) * sin(angularDistance) * cos(bearing)
    )
    val lon2 = lon1 + kotlin.math.atan2(
        sin(bearing) * sin(angularDistance) * cos(lat1),
        cos(angularDistance) - sin(lat1) * sin(lat2)
    )
    return Math.toDegrees(lat2) to ((Math.toDegrees(lon2) + 540.0) % 360.0 - 180.0)
}

private fun createGeographicAnchorPose(
    cameraPose: Pose,
    originLatitude: Double,
    originLongitude: Double,
    originHeadingDegrees: Float,
    target: SpatialArImageTarget
): Pose {
    val forward = cameraPose.rotateVector(floatArrayOf(0f, 0f, -1f))
    val right = cameraPose.rotateVector(floatArrayOf(1f, 0f, 0f))
    val forwardLength = kotlin.math.sqrt(forward[0] * forward[0] + forward[2] * forward[2]).coerceAtLeast(0.001f)
    val rightLength = kotlin.math.sqrt(right[0] * right[0] + right[2] * right[2]).coerceAtLeast(0.001f)
    val fx = forward[0] / forwardLength
    val fz = forward[2] / forwardLength
    val rx = right[0] / rightLength
    val rz = right[2] / rightLength
    val heading = Math.toRadians(originHeadingDegrees.toDouble())
    val northX = fx * cos(heading) - rx * sin(heading)
    val northZ = fz * cos(heading) - rz * sin(heading)
    val eastX = fx * sin(heading) + rx * cos(heading)
    val eastZ = fz * sin(heading) + rz * cos(heading)

    val meanLatitude = Math.toRadians((originLatitude + target.latitude) / 2.0)
    val northMeters = Math.toRadians(target.latitude - originLatitude) * EARTH_RADIUS_METERS
    val eastMeters = Math.toRadians(target.longitude - originLongitude) * EARTH_RADIUS_METERS * cos(meanLatitude)
    val translation = cameraPose.translation
    val worldX = translation[0] + (northMeters * northX + eastMeters * eastX).toFloat()
    val worldZ = translation[2] + (northMeters * northZ + eastMeters * eastZ).toFloat()
    val worldY = translation[1] + target.elevationMeters - 1.5f

    return Pose(floatArrayOf(worldX, worldY, worldZ), cameraPose.rotationQuaternion)
}

private fun formatCapturedDate(timestamp: String): String {
    if (timestamp.isBlank()) return "—"
    val localDate = runCatching {
        OffsetDateTime.parse(timestamp).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
    }.getOrNull()
        ?: runCatching { Instant.parse(timestamp).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(timestamp).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDate.parse(timestamp.take(10)) }.getOrNull()
    return localDate?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)) ?: timestamp.take(10)
}
