package com.awaken.sightseeingtravel.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.awaken.sightseeingtravel.core.model.ArModelLibraryItemDto
import com.awaken.sightseeingtravel.ui.BrandColors
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes

@Composable
fun ArModelLibraryDialog(
    isEnglish: Boolean,
    onLoadLibrary: suspend () -> List<ArModelLibraryItemDto>,
    onDismiss: () -> Unit
) {
    var library by remember { mutableStateOf<List<ArModelLibraryItemDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var previewItem by remember { mutableStateOf<ArModelLibraryItemDto?>(null) }

    LaunchedEffect(reloadToken) {
        isLoading = true
        errorMessage = null
        runCatching { onLoadLibrary() }
            .onSuccess { library = it }
            .onFailure { errorMessage = it.localizedMessage ?: if (isEnglish) "Could not load the 3D library." else "Không thể tải kho vật thể 3D." }
        isLoading = false
    }

    val filteredLibrary = library
        .filter { selectedCategory == null || it.category.equals(selectedCategory, ignoreCase = true) }
        .sortedBy { it.name.lowercase() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.88f),
            shape = RoundedCornerShape(24.dp),
            color = BrandColors.Paper,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isEnglish) "3D Object Library" else "Kho vật thể 3D",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 21.sp,
                            color = BrandColors.PrimaryDeep
                        )
                        Text(
                            text = if (isEnglish) "${filteredLibrary.size} ready objects" else "${filteredLibrary.size} vật thể đã sẵn sàng",
                            color = BrandColors.MutedText,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    IconButton(onClick = { reloadToken++ }, enabled = !isLoading) {
                        Icon(Icons.Default.Refresh, contentDescription = if (isEnglish) "Reload" else "Tải lại")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = if (isEnglish) "Close" else "Đóng")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null },
                        label = { Text(if (isEnglish) "All" else "Tất cả") }
                    )
                    FilterChip(
                        selected = selectedCategory == "Food",
                        onClick = { selectedCategory = "Food" },
                        label = { Text(if (isEnglish) "Food" else "Món ăn") }
                    )
                    FilterChip(
                        selected = selectedCategory == "Object",
                        onClick = { selectedCategory = "Object" },
                        label = { Text(if (isEnglish) "Objects" else "Vật dụng") }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                when {
                    isLoading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = BrandColors.Terracotta)
                    }
                    errorMessage != null -> Column(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = { reloadToken++ }) {
                            Text(if (isEnglish) "Try again" else "Thử lại")
                        }
                    }
                    filteredLibrary.isEmpty() -> Box(
                        Modifier.fillMaxWidth().weight(1f).padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (library.isEmpty()) {
                                if (isEnglish) "No 3D objects yet. Create one from an AR card." else "Kho chưa có vật thể 3D. Hãy tạo vật thể từ một thẻ AR."
                            } else {
                                if (isEnglish) "No objects in this category." else "Không có vật thể trong phân loại này."
                            },
                            color = BrandColors.MutedText
                        )
                    }
                    else -> LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredLibrary, key = { it.sourceCardId }) { item ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = BrandColors.Surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = BrandColors.Cream
                                    ) {
                                        Icon(
                                            Icons.Default.ViewInAr,
                                            contentDescription = null,
                                            tint = BrandColors.Terracotta,
                                            modifier = Modifier.padding(10.dp).size(22.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(item.name, fontWeight = FontWeight.Bold, color = BrandColors.PrimaryDeep)
                                        Text(
                                            text = modelCategoryLabel(item.category, isEnglish),
                                            color = BrandColors.Terracotta,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                        Text(
                                            text = if (isEnglish) "Ready • AR card ${item.sourceCardId.take(8)}" else "Sẵn sàng • Thẻ AR ${item.sourceCardId.take(8)}",
                                            color = BrandColors.MutedText,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            text = if (isEnglish) "Rotation: ${item.modelRotationDegrees.toInt()}°" else "Góc xoay: ${item.modelRotationDegrees.toInt()}°",
                                            color = BrandColors.MutedText,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        OutlinedButton(
                                            onClick = { previewItem = item },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)
                                        ) {
                                            Icon(Icons.Default.ViewInAr, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(if (isEnglish) "Preview 3D" else "Xem thử 3D")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(if (isEnglish) "Close" else "Đóng kho")
                }
            }
        }
    }

    previewItem?.let { item ->
        ArModelPreviewDialog(
            item = item,
            isEnglish = isEnglish,
            onDismiss = { previewItem = null }
        )
    }
}

@Composable
private fun ArModelPreviewDialog(
    item: ArModelLibraryItemDto,
    isEnglish: Boolean,
    onDismiss: () -> Unit
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val childNodes = rememberNodes()
    var isLoading by remember(item.sourceCardId) { mutableStateOf(true) }
    var loadError by remember(item.sourceCardId) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.sourceCardId, item.modelUrl) {
        isLoading = true
        loadError = null
        runCatching { modelLoader.loadModelInstance(item.modelUrl) }
            .onSuccess { modelInstance ->
                if (modelInstance == null) {
                    loadError = if (isEnglish) "The 3D model could not be read." else "Không đọc được mô hình 3D."
                } else {
                    childNodes.clear()
                    childNodes.add(
                        ModelNode(
                            modelInstance = modelInstance,
                            scaleToUnits = 1.2f,
                            centerOrigin = Position(y = -0.5f)
                        )
                    )
                }
            }
            .onFailure { loadError = it.localizedMessage ?: if (isEnglish) "Could not load the 3D model." else "Không thể tải mô hình 3D." }
        isLoading = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.82f),
            shape = RoundedCornerShape(24.dp),
            color = BrandColors.Paper,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
                Text(item.name, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, color = BrandColors.PrimaryDeep)
                Text(modelCategoryLabel(item.category, isEnglish), color = BrandColors.Terracotta)
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                ) {
                    Scene(
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                        engine = engine,
                        modelLoader = modelLoader,
                        childNodes = childNodes
                    )
                    if (isLoading) {
                        Surface(color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(12.dp), modifier = Modifier.align(Alignment.Center)) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (isEnglish) "Loading model…" else "Đang tải mô hình…", color = Color.White)
                            }
                        }
                    }
                    loadError?.let { message ->
                        Surface(color = Color.Black.copy(alpha = 0.72f), shape = RoundedCornerShape(12.dp), modifier = Modifier.align(Alignment.Center)) {
                            Text(message, color = Color.White, modifier = Modifier.padding(14.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isEnglish) "Drag to rotate • pinch to zoom" else "Kéo để xoay • chụm để phóng to/thu nhỏ",
                    color = BrandColors.MutedText,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(if (isEnglish) "Back to library" else "Quay lại kho")
                }
            }
        }
    }
}

fun modelCategoryLabel(category: String, isEnglish: Boolean): String = when (category.lowercase()) {
    "food" -> if (isEnglish) "Food" else "Món ăn"
    else -> if (isEnglish) "Object" else "Vật dụng"
}
