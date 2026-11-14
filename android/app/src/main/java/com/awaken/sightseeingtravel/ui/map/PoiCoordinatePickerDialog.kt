package com.awaken.sightseeingtravel.ui.map

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.awaken.sightseeingtravel.ui.BrandColors
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.util.Locale

@Composable
fun PoiCoordinatePickerDialog(
    initialLatitude: Double,
    initialLongitude: Double,
    onDismiss: () -> Unit,
    onConfirm: (Double, Double) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember(context) {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply { onCreate(Bundle()) }
    }
    var selectedLatitude by remember { mutableStateOf(initialLatitude) }
    var selectedLongitude by remember { mutableStateOf(initialLongitude) }
    var isMapReady by remember { mutableStateOf(false) }

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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = BrandColors.Cream) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại", tint = BrandColors.PrimaryDeep)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Ghim vị trí POI", color = BrandColors.PrimaryDeep, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        Text("Kéo bản đồ hoặc chạm điểm muốn đặt pin", color = BrandColors.PrimaryDeep.copy(alpha = 0.72f), fontSize = 13.sp)
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.LightGray)
                ) {
                    AndroidView(
                        factory = { mapView },
                        modifier = Modifier.fillMaxSize()
                    )

                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = "Vị trí POI được chọn",
                        tint = BrandColors.Terracotta,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(y = (-23).dp)
                            .size(46.dp)
                    )

                    if (!isMapReady) {
                        Surface(
                            modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                            color = BrandColors.PrimaryDeep.copy(alpha = 0.88f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = BrandColors.Cream)
                                Text("Đang tải bản đồ…", color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                }

                Surface(color = BrandColors.PrimaryDeep, shape = RoundedCornerShape(16.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Tọa độ tại đầu pin", color = BrandColors.Cream, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${formatCoordinate(selectedLatitude)}, ${formatCoordinate(selectedLongitude)}",
                            color = Color.White,
                            fontSize = 15.sp
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("Hủy", color = BrandColors.PrimaryDeep)
                    }
                    Button(
                        onClick = { onConfirm(selectedLatitude, selectedLongitude) },
                        enabled = isMapReady,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Dùng vị trí này")
                    }
                }
            }
        }
    }

    DisposableEffect(mapView, initialLatitude, initialLongitude) {
        var disposed = false
        mapView.getMapAsync { map ->
            if (disposed) return@getMapAsync
            map.uiSettings.isCompassEnabled = true
            map.uiSettings.isRotateGesturesEnabled = true
            map.uiSettings.isTiltGesturesEnabled = false
            map.uiSettings.isAttributionEnabled = true
            mapView.addOnDidFailLoadingMapListener {
                map.setStyle(Style.Builder().fromJson(buildOpenStreetMapFallbackStyleJson())) {
                    if (!disposed) isMapReady = true
                }
            }
            map.addOnCameraIdleListener {
                val target = map.cameraPosition.target ?: return@addOnCameraIdleListener
                selectedLatitude = target.latitude
                selectedLongitude = target.longitude
            }
            map.addOnMapClickListener { point ->
                map.animateCamera(CameraUpdateFactory.newLatLng(point), 280)
                true
            }
            map.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(initialLatitude, initialLongitude))
                        .zoom(17.5)
                        .bearing(0.0)
                        .tilt(0.0)
                        .build()
                )
            )
            map.setStyle(Style.Builder().fromUri(OPENFREEMAP_3D_STYLE_URL)) {
                if (disposed) return@setStyle
                isMapReady = true
            }
        }
        onDispose {
            disposed = true
        }
    }
}

private fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.6f", value)
