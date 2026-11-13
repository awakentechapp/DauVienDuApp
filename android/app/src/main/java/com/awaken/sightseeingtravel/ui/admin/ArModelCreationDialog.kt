package com.awaken.sightseeingtravel.ui.admin

import android.Manifest
import android.content.pm.PackageManager
import android.view.Surface as AndroidSurface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var captureError by remember { mutableStateOf<String?>(null) }
    var modelName by remember { mutableStateOf("") }
    var modelCategory by remember { mutableStateOf("Object") }
    var isCapturing by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
        isCameraActive = granted
        cameraError = if (granted) null else "Cần cấp quyền camera để chụp vật thể."
    }

    DisposableEffect(isCameraActive, hasCameraPermission, lifecycleOwner, previewView) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
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
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            capture
                        )
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
            imageCapture = null
        }
    }

    DisposableEffect(Unit) {
        onDispose { capturedFiles.forEach { it.delete() } }
    }

    val capturePhoto = {
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
                        enabled = !isSubmitting
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
                        AndroidView(
                            factory = { previewView },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 220.dp, max = 360.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color.Black)
                        )
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
                    Text("Chụp ít nhất 12 ảnh quanh vật thể, đủ sáng và có góc nhìn chồng lấp.", color = BrandColors.Cream, style = MaterialTheme.typography.bodySmall)

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
}
