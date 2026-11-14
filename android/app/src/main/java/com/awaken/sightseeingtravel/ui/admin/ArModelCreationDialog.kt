package com.awaken.sightseeingtravel.ui.admin

import android.Manifest
import android.content.pm.PackageManager
import android.view.Surface as AndroidSurface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.awaken.sightseeingtravel.ui.BrandColors
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.launch

@Composable
fun ArModelCreationDialog(
    onBuild: suspend (List<File>, String, String) -> String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val capturedFiles = remember { mutableStateListOf<File>() }
    val scope = rememberCoroutineScope()
    var hasCameraPermission by remember(context) {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var isCameraActive by remember { mutableStateOf(hasCameraPermission) }
    var activeCamera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var captureError by remember { mutableStateOf<String?>(null) }
    var modelName by remember { mutableStateOf("") }
    var modelCategory by remember { mutableStateOf("Object") }
    var isCapturing by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }
    var frameQuality by remember { mutableStateOf<FrameQuality?>(null) }
    var showQualityWarning by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
        isCameraActive = granted
        cameraError = if (granted) null else "Cần cấp quyền camera để chụp vật thể."
    }

    DisposableEffect(isCameraActive, hasCameraPermission, lifecycleOwner, previewView) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        val analysisExecutor = Executors.newSingleThreadExecutor()
        if (isCameraActive && hasCameraPermission) {
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                if (!disposed) {
                    try {
                        val cameraProvider = providerFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val capture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .setJpegQuality(95)
                            .build()
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        var lastAnalysisAt = 0L
                        analysis.setAnalyzer(analysisExecutor) { image ->
                            try {
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (now - lastAnalysisAt >= 700L) {
                                    lastAnalysisAt = now
                                    val sample = inspectFrame(image)
                                    if (sample != null) {
                                        ContextCompat.getMainExecutor(context).execute {
                                            if (!disposed) frameQuality = sample
                                        }
                                    }
                                }
                            } catch (_: Exception) {
                                // Keep camera preview/capture available if a device frame cannot be analyzed.
                            } finally {
                                image.close()
                            }
                        }
                        cameraProvider.unbindAll()
                        val boundCamera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            capture,
                            analysis
                        )
                        activeCamera = boundCamera
                        previewView.setOnTouchListener { _, event ->
                            if (event.action == android.view.MotionEvent.ACTION_UP) {
                                val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                                activeCamera?.cameraControl?.startFocusAndMetering(
                                    FocusMeteringAction.Builder(point).build()
                                )
                            }
                            true
                        }
                        provider = cameraProvider
                        imageCapture = capture
                        cameraError = null
                    } catch (error: Exception) {
                        imageCapture = null
                        cameraError = error.localizedMessage ?: "Không thể mở camera trong app."
                    }
                }
            }, ContextCompat.getMainExecutor(context))
        } else {
            imageCapture = null
        }
        onDispose {
            disposed = true
            runCatching { provider?.unbindAll() }
            previewView.setOnTouchListener(null)
            activeCamera = null
            analysisExecutor.shutdownNow()
            imageCapture = null
            frameQuality = null
        }
    }

    DisposableEffect(Unit) {
        onDispose { capturedFiles.forEach { it.delete() } }
    }

    val performCapturePhoto = {
        val capture = imageCapture
        if (capture == null || isCapturing || capturedFiles.size >= 32) {
            cameraError = "Camera chưa sẵn sàng hoặc đã đủ 32 ảnh."
        } else {
            val directory = File(context.cacheDir, "ar-model-captures").apply { mkdirs() }
            val file = File.createTempFile("ar-model-", ".jpg", directory)
            capture.targetRotation = previewView.display?.rotation ?: AndroidSurface.ROTATION_0
            isCapturing = true
            capture.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        isCapturing = false
                        if (file.length() > 0L) {
                            capturedFiles.add(file)
                            captureError = null
                            submitError = null
                        } else {
                            file.delete()
                            captureError = "Ảnh vừa chụp không hợp lệ. Hãy chụp lại."
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        isCapturing = false
                        file.delete()
                        cameraError = exception.localizedMessage ?: "Chụp ảnh thất bại. Hãy thử lại."
                    }
                }
            )
        }
    }
    val capturePhoto = {
        val qualityWarning = frameQuality?.let(::frameQualityWarning)
        if (qualityWarning != null || frameQuality == null) {
            showQualityWarning = true
        } else {
            performCapturePhoto()
        }
    }

    Dialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = BrandColors.PrimaryDeep
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (!isSubmitting) onDismiss() }, enabled = !isSubmitting) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Quay lại", tint = Color.White)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Tạo vật thể 3D", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        Text("Tạo độc lập, sau đó chọn để đặt vào thẻ AR", color = BrandColors.Cream, fontSize = 12.sp)
                    }
                }

                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = modelName,
                        onValueChange = { modelName = it.take(200) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Tên vật thể") },
                        singleLine = true,
                        enabled = !isSubmitting,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = BrandColors.PrimaryDeep,
                            unfocusedTextColor = BrandColors.PrimaryDeep,
                            disabledTextColor = BrandColors.PrimaryDeep.copy(alpha = 0.65f),
                            focusedContainerColor = BrandColors.Cream,
                            unfocusedContainerColor = BrandColors.Cream,
                            disabledContainerColor = BrandColors.Cream.copy(alpha = 0.8f),
                            focusedBorderColor = BrandColors.Cream,
                            unfocusedBorderColor = BrandColors.Cream,
                            disabledBorderColor = BrandColors.Cream.copy(alpha = 0.7f),
                            focusedLabelColor = BrandColors.PrimaryDeep,
                            unfocusedLabelColor = BrandColors.PrimaryDeep.copy(alpha = 0.8f),
                            disabledLabelColor = BrandColors.PrimaryDeep.copy(alpha = 0.65f),
                            cursorColor = BrandColors.PrimaryDeep
                        )
                    )
                    Text("Phân loại vật thể", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = modelCategory == "Food",
                            onClick = { modelCategory = "Food" },
                            enabled = !isSubmitting,
                            label = { Text("Món ăn") }
                        )
                        FilterChip(
                            selected = modelCategory == "Object",
                            onClick = { modelCategory = "Object" },
                            enabled = !isSubmitting,
                            label = { Text("Vật dụng") }
                        )
                    }

                    if (isCameraActive && hasCameraPermission) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 220.dp, max = 360.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color.Black)
                        ) {
                            AndroidView(
                                factory = { previewView },
                                modifier = Modifier.fillMaxSize()
                            )
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val guideWidth = size.width * 0.68f
                                val guideHeight = size.height * 0.68f
                                drawRoundRect(
                                    color = Color.White.copy(alpha = 0.9f),
                                    topLeft = Offset((size.width - guideWidth) / 2f, (size.height - guideHeight) / 2f),
                                    size = Size(guideWidth, guideHeight),
                                    cornerRadius = CornerRadius(16.dp.toPx()),
                                    style = Stroke(
                                        width = 2.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(), 8.dp.toPx()))
                                    )
                                )
                                val center = Offset(size.width / 2f, size.height / 2f)
                                drawLine(Color.White.copy(alpha = 0.85f), Offset(center.x - 10.dp.toPx(), center.y), Offset(center.x + 10.dp.toPx(), center.y), 1.5.dp.toPx())
                                drawLine(Color.White.copy(alpha = 0.85f), Offset(center.x, center.y - 10.dp.toPx()), Offset(center.x, center.y + 10.dp.toPx()), 1.5.dp.toPx())
                            }
                            Text(
                                "Canh giữa • vật thể chiếm khoảng 60–70% khung",
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(8.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black.copy(alpha = 0.62f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxWidth().height(140.dp),
                            color = Color.Black.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("Bật camera để ngắm và chụp vật thể", color = Color.White)
                            }
                        }
                    }

                    Text(
                        "Camera trực tiếp hiển thị để ngắm. Ảnh đã chụp không hiện preview; chỉ hiển thị số lượng để bạn chụp tiếp.",
                        color = BrandColors.Cream,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("${capturedFiles.size}/32 ảnh", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Surface(color = BrandColors.Cream, shape = RoundedCornerShape(14.dp)) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            val currentQualityWarning = frameQuality?.let(::frameQualityWarning)
                            Text(
                                when {
                                    frameQuality == null -> "Đang kiểm tra độ sáng và độ nét…"
                                    currentQualityWarning != null -> currentQualityWarning
                                    else -> "Ánh sáng và độ nét ổn. Giữ máy yên khi chụp."
                                },
                                color = if (currentQualityWarning != null) BrandColors.Terracotta else BrandColors.PrimaryDeep,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                captureGuidance(capturedFiles.size),
                                color = BrandColors.PrimaryDeep.copy(alpha = 0.86f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    if (!cameraError.isNullOrBlank()) Text(cameraError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    if (!captureError.isNullOrBlank()) Text(captureError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    if (!submitError.isNullOrBlank()) Text(submitError.orEmpty(), color = MaterialTheme.colorScheme.error)

                    if (isSubmitting) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = BrandColors.Cream)
                            Text("Đang tải ảnh lên để dựng mô hình…", color = Color.White)
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (!isCameraActive) {
                        Button(
                            onClick = {
                                if (hasCameraPermission) isCameraActive = true
                                else permissionLauncher.launch(Manifest.permission.CAMERA)
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isSubmitting
                        ) { Text(if (hasCameraPermission) "Bật camera" else "Cấp quyền camera") }
                    } else {
                        Button(
                            onClick = capturePhoto,
                            modifier = Modifier.weight(1f),
                            enabled = imageCapture != null && !isCapturing && !isSubmitting && capturedFiles.size < 32
                        ) {
                            if (isCapturing) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.size(8.dp))
                            } else {
                                Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.size(8.dp))
                            }
                            Text(if (isCapturing) "Đang chụp…" else "Chụp ảnh tiếp")
                        }
                    }
                    TextButton(
                        onClick = { capturedFiles.removeLastOrNull()?.delete() },
                        enabled = !isSubmitting && !isCapturing && capturedFiles.isNotEmpty()
                    ) { Text("Bỏ ảnh cuối", color = Color.White) }
                }

                if (capturedFiles.size >= 12) {
                    Button(
                        onClick = {
                            isCameraActive = false
                            scope.launch {
                                isSubmitting = true
                                submitError = onBuild(capturedFiles.toList(), modelName.trim(), modelCategory)
                                isSubmitting = false
                                if (submitError == null) onDismiss()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSubmitting && !isCapturing && modelName.isNotBlank()
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.size(8.dp))
                        }
                        Text(if (isSubmitting) "Đang dựng vật thể…" else "Ghép ảnh tạo vật thể 3D")
                    }
                }
            }
        }
    }

    if (showQualityWarning) {
        val warning = frameQuality?.let(::frameQualityWarning)
            ?: "Camera chưa phân tích xong. Chờ vài giây để kiểm tra độ sáng và độ nét trước khi chụp."
        AlertDialog(
            onDismissRequest = { showQualityWarning = false },
            title = { Text("Kiểm tra ảnh trước khi chụp", color = BrandColors.PrimaryDeep) },
            text = {
                Text(
                    "$warning\n\nHãy đặt vật thể trên nền đơn sắc, tránh bóng đổ và giữ máy đứng yên. Bạn vẫn có thể chụp nếu muốn.",
                    color = BrandColors.PrimaryDeep
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showQualityWarning = false
                    performCapturePhoto()
                }) { Text("Chụp vẫn tiếp", color = BrandColors.Terracotta) }
            },
            dismissButton = {
                TextButton(onClick = { showQualityWarning = false }) {
                    Text("Điều chỉnh", color = BrandColors.PrimaryDeep)
                }
            },
            containerColor = BrandColors.Cream
        )
    }
}

private data class FrameQuality(val averageLuminance: Double, val sharpness: Double)

private fun inspectFrame(image: ImageProxy): FrameQuality? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer.duplicate()
    val width = image.width
    val height = image.height
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val start = buffer.position()
    if (width < 8 || height < 8 || pixelStride <= 0) return null

    fun luminanceAt(x: Int, y: Int): Int {
        val index = start + y * rowStride + x * pixelStride
        if (index !in 0 until buffer.limit()) return 0
        return buffer.get(index).toInt() and 0xff
    }

    val left = (width * 0.16f).toInt().coerceAtLeast(2)
    val right = (width * 0.84f).toInt().coerceAtMost(width - 2)
    val top = (height * 0.16f).toInt().coerceAtLeast(2)
    val bottom = (height * 0.84f).toInt().coerceAtMost(height - 2)
    val stepX = maxOf(4, (right - left) / 100)
    val stepY = maxOf(4, (bottom - top) / 100)
    var luminanceSum = 0L
    var luminanceCount = 0
    var edgeEnergySum = 0.0
    var edgeCount = 0
    for (y in top until bottom step stepY) {
        for (x in left until right step stepX) {
            val center = luminanceAt(x, y)
            luminanceSum += center
            luminanceCount++
            val laplacian = luminanceAt(x - 2, y) + luminanceAt(x + 2, y) +
                luminanceAt(x, y - 2) + luminanceAt(x, y + 2) - 4 * center
            edgeEnergySum += laplacian * laplacian
            edgeCount++
        }
    }
    if (luminanceCount == 0 || edgeCount == 0) return null
    return FrameQuality(
        averageLuminance = luminanceSum.toDouble() / luminanceCount,
        sharpness = edgeEnergySum / edgeCount
    )
}

private fun frameQualityWarning(quality: FrameQuality): String? = when {
    quality.averageLuminance < 58.0 -> "Ảnh đang thiếu sáng. Hãy tăng ánh sáng đều, tránh để vật thể bị bóng đổ."
    quality.averageLuminance > 228.0 -> "Khung hình đang quá sáng. Tránh ánh đèn chiếu trực tiếp làm mất chi tiết."
    quality.sharpness < 38.0 -> "Vùng giữa ít chi tiết hoặc có thể bị mờ. Chạm vào vật thể để lấy nét rồi giữ máy yên."
    else -> null
}

private fun captureGuidance(capturedCount: Int): String = when {
    capturedCount == 0 -> "Bắt đầu: đặt vật thể trên nền đơn sắc, dễ phân biệt; lau sạch ống kính và đưa vật thể vào giữa khung. Chạm vào vật thể để lấy nét."
    capturedCount < 12 -> "Ảnh ${capturedCount + 1}/12: đi vòng quanh vật thể khoảng 30° mỗi ảnh; giữ khoảng cách ổn định và chồng lấp góc nhìn với ảnh trước."
    capturedCount < 24 -> "Đã có vòng ngang. Chụp vòng thứ hai từ góc cao hơn hoặc thấp hơn để ghi lại mặt trên, mặt dưới và các chi tiết khuất."
    else -> "Rà lại các góc còn thiếu; tránh chụp lặp một hướng. Cần ít nhất 12 ảnh rõ, đủ sáng và có góc nhìn chồng lấp."
}
