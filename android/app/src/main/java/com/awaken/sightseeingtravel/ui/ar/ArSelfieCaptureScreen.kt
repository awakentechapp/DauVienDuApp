package com.awaken.sightseeingtravel.ui.ar

import android.Manifest
import android.content.pm.PackageManager
import android.view.Surface as AndroidSurface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.awaken.sightseeingtravel.core.model.PhotoAnchorSnapshot
import com.awaken.sightseeingtravel.core.model.PoiArContextDto
import com.awaken.sightseeingtravel.core.sensor.DeviceSpatialPose
import com.awaken.sightseeingtravel.core.sensor.LocationAndOrientationTracker
import com.awaken.sightseeingtravel.ui.BrandColors
import java.io.File

@Composable
fun ArSelfieCaptureScreen(
    arContext: PoiArContextDto,
    spatialPose: DeviceSpatialPose,
    isUploadingPhoto: Boolean,
    uploadErrorMessage: String?,
    selectedLanguage: String,
    onCaptureAndUploadPhoto: (File, String, PhotoAnchorSnapshot, () -> Unit) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isEn = selectedLanguage.equals("en", ignoreCase = true)
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var hasCameraPermission by remember(context) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var isCapturing by remember { mutableStateOf(false) }
    var showUploadDialog by remember { mutableStateOf(false) }
    var isFrontCamera by remember { mutableStateOf(true) }
    var pendingPhoto by remember { mutableStateOf<File?>(null) }
    var pendingAnchor by remember { mutableStateOf<PhotoAnchorSnapshot?>(null) }
    var caption by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        cameraError = if (granted) null else if (isEn) {
            "Camera permission is required to take a photo."
        } else {
            "Cần cấp quyền camera để chụp ảnh."
        }
    }

    val distanceToPoi = LocationAndOrientationTracker.calculateDistanceMeters(
        spatialPose.latitude,
        spatialPose.longitude,
        arContext.latitude,
        arContext.longitude
    )
    val hasUsableGps = spatialPose.hasFreshGpsFix &&
        !spatialPose.isSimulatedLocation && spatialPose.accuracyMeters <= 50f
    val isWithinPoiZone = hasUsableGps &&
        distanceToPoi <= arContext.activationRadiusMeters + 20.0

    DisposableEffect(hasCameraPermission, lifecycleOwner, previewView, isFrontCamera) {
        var disposed = false
        var cameraProvider: ProcessCameraProvider? = null

        if (hasCameraPermission) {
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                if (!disposed) {
                    try {
                        val provider = providerFuture.get()
                        val cameraSelector = if (isFrontCamera) {
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        } else {
                            CameraSelector.DEFAULT_BACK_CAMERA
                        }
                        check(provider.hasCamera(cameraSelector)) {
                            if (isEn) "This device does not have the selected camera."
                            else "Thiết bị không có camera đã chọn."
                        }

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val capture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .setJpegQuality(92)
                            .build()

                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, capture)
                        cameraProvider = provider
                        imageCapture = capture
                        cameraError = null
                    } catch (error: Exception) {
                        imageCapture = null
                        cameraError = error.localizedMessage ?: if (isEn) {
                            "Could not start the selected camera."
                        } else {
                            "Không thể mở camera đã chọn."
                        }
                    }
                }
            }, ContextCompat.getMainExecutor(context))
        } else {
            imageCapture = null
        }

        onDispose {
            disposed = true
            runCatching { cameraProvider?.unbindAll() }
            imageCapture = null
        }
    }

    val discardPendingPhoto = {
        pendingPhoto?.let { runCatching { it.delete() } }
        pendingPhoto = null
        pendingAnchor = null
        showUploadDialog = false
    }
    val closeScreen = {
        discardPendingPhoto()
        onClose()
    }

    BackHandler(enabled = !showUploadDialog) { closeScreen() }

    val captureSelfie: () -> Unit = {
        val capture = imageCapture
        when {
            !isWithinPoiZone -> cameraError = if (isEn) {
                "Move closer to the AR point with live GPS before taking a photo."
            } else {
                "Hãy đến gần điểm AR và chờ GPS thực trước khi chụp ảnh."
            }
            capture == null -> cameraError = if (isEn) {
                "The selected camera is not ready yet."
            } else {
                "Camera chưa sẵn sàng."
            }
            !isCapturing -> {
                val file = File(context.cacheDir, "poi_selfie_${System.currentTimeMillis()}.jpg")
                val anchor = PhotoAnchorSnapshot(
                    latitude = spatialPose.latitude,
                    longitude = spatialPose.longitude,
                    // A front-facing camera points opposite the device heading; the rear camera does not.
                    azimuthDegrees = if (isFrontCamera) {
                        (spatialPose.azimuthDegrees + 180f) % 360f
                    } else {
                        spatialPose.azimuthDegrees
                    },
                    pitchDegrees = spatialPose.pitchDegrees.coerceIn(-90f, 90f)
                )
                capture.targetRotation = previewView.display?.rotation ?: AndroidSurface.ROTATION_0
                isCapturing = true
                capture.takePicture(
                    ImageCapture.OutputFileOptions.Builder(file).build(),
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            isCapturing = false
                            pendingPhoto = file
                            pendingAnchor = anchor
                            caption = ""
                            cameraError = null
                            showUploadDialog = true
                        }

                        override fun onError(exception: ImageCaptureException) {
                            isCapturing = false
                            file.delete()
                            cameraError = exception.localizedMessage ?: if (isEn) {
                                "Could not capture the photo."
                            } else {
                                "Không thể chụp ảnh."
                            }
                        }
                    }
                )
            }
            else -> Unit
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(BrandColors.PrimaryDeep)) {
        if (hasCameraPermission) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.68f),
                            Color.Transparent,
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.82f)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                color = BrandColors.PrimaryDeep.copy(alpha = 0.82f),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = closeScreen) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (isEn) "Back" else "Quay lại",
                            tint = Color.White
                        )
                    }
                    Column(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text(
                            text = arContext.title,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = if (isEn) {
                                if (isFrontCamera) "Front camera" else "Rear camera"
                            } else {
                                if (isFrontCamera) "Camera trước" else "Camera sau"
                            },
                            color = BrandColors.Cream,
                            fontSize = 12.sp
                        )
                    }
                    IconButton(
                        onClick = {
                            isFrontCamera = !isFrontCamera
                            cameraError = null
                        },
                        enabled = hasCameraPermission && !isCapturing && !isUploadingPhoto
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = if (isEn) "Switch camera" else "Đổi camera trước/sau",
                            tint = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Surface(
                color = BrandColors.PrimaryDeep.copy(alpha = 0.9f),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = when {
                            !hasCameraPermission -> if (isEn) "Allow camera access to take a photo." else "Cấp quyền camera để chụp ảnh."
                            cameraError != null -> cameraError!!
                            imageCapture == null -> if (isEn) "Starting camera…" else "Đang khởi động camera…"
                            !hasUsableGps -> if (isEn) "Waiting for a live GPS position…" else "Đang chờ vị trí GPS thực…"
                            !isWithinPoiZone -> if (isEn) "Move within ${ (arContext.activationRadiusMeters + 20).toInt() }m of this AR point." else "Hãy đến trong phạm vi ${(arContext.activationRadiusMeters + 20).toInt()}m của điểm AR."
                            else -> if (isEn) "Your photo will be attached to your current location." else "Ảnh sẽ được gắn với vị trí hiện tại của bạn."
                        },
                        color = if (cameraError != null) BrandColors.Error else BrandColors.Cream,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )

                    if (!hasCameraPermission) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text(if (isEn) "Allow camera" else "Cấp quyền camera")
                        }
                    } else {
                        Spacer(modifier = Modifier.height(14.dp))
                        IconButton(
                            onClick = captureSelfie,
                            enabled = imageCapture != null && isWithinPoiZone && !isCapturing && !isUploadingPhoto,
                            modifier = Modifier
                                .size(72.dp)
                                .background(Color.White, CircleShape)
                                .border(3.dp, BrandColors.Cream, CircleShape)
                        ) {
                            if (isCapturing || isUploadingPhoto) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    color = BrandColors.Terracotta,
                                    strokeWidth = 3.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.PhotoCamera,
                                    contentDescription = if (isEn) "Take photo" else "Chụp ảnh",
                                    tint = BrandColors.Terracotta,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isEn) "Take photo" else "Chụp ảnh tại điểm AR",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }

    if (showUploadDialog && pendingPhoto != null) {
        AlertDialog(
            onDismissRequest = { if (!isUploadingPhoto) discardPendingPhoto() },
            title = {
                Text(
                    text = if (isEn) "Share photo to AR" else "Chia sẻ ảnh lên Không gian AR",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    AsyncImage(
                        model = pendingPhoto,
                        contentDescription = if (isEn) "Captured photo preview" else "Ảnh vừa chụp",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (isEn) {
                            "Review the captured photo, add a caption, then upload it to AR."
                        } else {
                            "Xem lại ảnh vừa chụp, thêm chú thích rồi tải lên Không gian AR."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!uploadErrorMessage.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = uploadErrorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = caption,
                        onValueChange = { caption = it },
                        label = { Text(if (isEn) "Caption" else "Chú thích / Cảm nhận của bạn") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val file = pendingPhoto ?: return@Button
                        val anchor = pendingAnchor ?: return@Button
                        onCaptureAndUploadPhoto(file, caption, anchor) {
                            discardPendingPhoto()
                        }
                    },
                    enabled = !isUploadingPhoto && pendingAnchor != null
                ) {
                    Text(
                        when {
                            isUploadingPhoto && isEn -> "Uploading…"
                            isUploadingPhoto -> "Đang tải lên…"
                            isEn -> "Upload photo"
                            else -> "Tải ảnh lên"
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { discardPendingPhoto() }, enabled = !isUploadingPhoto) {
                    Text(if (isEn) "Retake" else "Chụp lại")
                }
            }
        )
    }
}
