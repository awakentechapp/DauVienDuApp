package com.awaken.sightseeingtravel.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.awaken.sightseeingtravel.core.model.*
import com.awaken.sightseeingtravel.core.sensor.DeviceSpatialPose
import com.awaken.sightseeingtravel.core.sensor.LocationAndOrientationTracker
import com.awaken.sightseeingtravel.ui.admin.AdminApprovalAndModerationScreen
import com.awaken.sightseeingtravel.ui.ar.ArCameraScreen
import com.awaken.sightseeingtravel.ui.map.OpenFreeMapComposeView

private enum class AppDestination {
    MAIN_TABS,
    AR_CAMERA,
    ADMIN_CONSOLE
}

/**
 * 4 Main Bottom Navigation Tabs:
 * 1. Trang chủ (HOME)
 * 2. Địa điểm (PLACES)
 * 3. Khám phá (EXPLORE - current GPS Map + AR experience)
 * 4. Thông tin (INFO)
 */
private enum class MainBottomTab(
    val titleVi: String,
    val titleEn: String,
    val icon: ImageVector
) {
    HOME("Trang chủ", "Home", Icons.Default.Home),
    PLACES("Địa điểm", "Places", Icons.Default.LocationOn),
    EXPLORE("Khám phá", "Explore", Icons.Default.Explore),
    INFO("Thông tin", "Info", Icons.Default.Info)
}

/**
 * Main Entry Activity for the Sightseeing Travel AR Android Application.
 * - 4 Bottom Tabs: Trang chủ, Địa điểm, Khám phá (MapLibre + OpenFreeMap/OSM + AR), Thông tin
 * - Multilingual Cultural Story & Community Photo Gallery Sheet (`vi` / `en`)
 * - Geofence-Unlocked Spatial AR Camera (`CameraX` + `ARCore`/Sensor 3D Projection)
 * - Role-based JWT Authentication (`Tourist`, `Admin`, `SuperAdmin`) & Governance Console
 */
class MainActivity : ComponentActivity() {

    private val viewModel: SightseeingAppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF0284C7),
                    secondary = Color(0xFF0F766E),
                    tertiary = Color(0xFFD97706)
                )
            ) {
                SightseeingTravelRootApp(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.startSensors()
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopSensors()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SightseeingTravelRootApp(viewModel: SightseeingAppViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val spatialPose by viewModel.spatialPose.collectAsState()

    var currentDestination by remember { mutableStateOf(AppDestination.MAIN_TABS) }
    var selectedTab by remember { mutableStateOf(MainBottomTab.EXPLORE) }
    var recenterTrigger by remember { mutableIntStateOf(0) }
    var showAuthModal by remember { mutableStateOf(false) }

    val isEn = uiState.selectedLanguage.equals("en", ignoreCase = true)

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.startSensors()
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.CAMERA
            )
        )
    }

    when (currentDestination) {
        AppDestination.AR_CAMERA -> {
            val arCtx = uiState.activeArContext
            if (arCtx != null) {
                ArCameraScreen(
                    arContext = arCtx,
                    spatialPose = spatialPose,
                    currentUserId = uiState.currentUser?.id,
                    isUploadingPhoto = uiState.isUploadingPhoto,
                    selectedLanguage = uiState.selectedLanguage,
                    onCaptureAndUploadPhoto = { file, caption ->
                        viewModel.uploadCapturedPhoto(arCtx.poiId, file, caption)
                    },
                    onToggleLikePhoto = { photoId ->
                        viewModel.toggleLikePhoto(photoId)
                    },
                    onReportPhoto = { photoId, reason ->
                        viewModel.reportCommunityPhoto(photoId, reason)
                    },
                    onDeleteOwnPhoto = { photoId ->
                        viewModel.deleteOwnPhoto(photoId)
                    },
                    onCloseAr = {
                        currentDestination = AppDestination.MAIN_TABS
                    }
                )
            } else {
                currentDestination = AppDestination.MAIN_TABS
            }
        }

        AppDestination.ADMIN_CONSOLE -> {
            AdminApprovalAndModerationScreen(
                currentUser = uiState.currentUser,
                adminPois = uiState.adminPois,
                reportedPhotos = uiState.reportedPhotos,
                selectedStatusFilter = uiState.adminStatusFilter,
                defaultLatitude = spatialPose.latitude,
                defaultLongitude = spatialPose.longitude,
                onFilterStatusChange = { status ->
                    viewModel.loadAdminConsoleData(status)
                },
                onCreatePoiSubmit = { request ->
                    viewModel.createPoiAsAdmin(request)
                },
                onSuperAdminApprovePoi = { poiId, notes ->
                    viewModel.approvePoiAsSuperAdmin(poiId, notes)
                },
                onSuperAdminRejectPoi = { poiId, reason ->
                    viewModel.rejectPoiAsSuperAdmin(poiId, reason)
                },
                onModeratePhotoDecision = { photoId, decision, notes ->
                    viewModel.moderateReportedPhoto(photoId, decision, notes)
                },
                onBackToMap = {
                    currentDestination = AppDestination.MAIN_TABS
                }
            )
        }

        AppDestination.MAIN_TABS -> {
            Scaffold(
                bottomBar = {
                    NavigationBar(
                        containerColor = Color.White,
                        tonalElevation = 8.dp
                    ) {
                        MainBottomTab.entries.forEach { tab ->
                            val isSelected = selectedTab == tab
                            NavigationBarItem(
                                selected = isSelected,
                                onClick = { selectedTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = if (isEn) tab.titleEn else tab.titleVi
                                    )
                                },
                                label = {
                                    Text(
                                        text = if (isEn) tab.titleEn else tab.titleVi,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 11.5.sp
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color(0xFF0284C7),
                                    selectedTextColor = Color(0xFF0284C7),
                                    indicatorColor = Color(0xFFE0F2FE),
                                    unselectedIconColor = Color(0xFF64748B),
                                    unselectedTextColor = Color(0xFF64748B)
                                )
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = innerPadding.calculateBottomPadding())
                ) {
                    when (selectedTab) {
                        MainBottomTab.HOME -> {
                            HomeTabScreen(
                                uiState = uiState,
                                spatialPose = spatialPose,
                                onToggleLanguage = {
                                    viewModel.setLanguage(if (isEn) "vi" else "en")
                                },
                                onOpenAccountModal = { showAuthModal = true },
                                onOpenAdminConsole = {
                                    viewModel.loadAdminConsoleData()
                                    currentDestination = AppDestination.ADMIN_CONSOLE
                                },
                                onNavigateToExploreMap = { poiId ->
                                    selectedTab = MainBottomTab.EXPLORE
                                    if (poiId != null) {
                                        viewModel.selectPoiPin(poiId, spatialPose.latitude, spatialPose.longitude)
                                    }
                                },
                                onNavigateToPlacesTab = {
                                    selectedTab = MainBottomTab.PLACES
                                },
                                onSimulateAndOpenAr = { pin ->
                                    viewModel.moveGpsNearPoi(pin.latitude, pin.longitude)
                                    viewModel.selectPoiPin(pin.id, pin.latitude, pin.longitude)
                                    viewModel.openArExperienceForPoi(pin.id) {
                                        currentDestination = AppDestination.AR_CAMERA
                                    }
                                }
                            )
                        }

                        MainBottomTab.PLACES -> {
                            PlacesDirectoryTabScreen(
                                uiState = uiState,
                                spatialPose = spatialPose,
                                onViewPoiOnExploreMap = { pin ->
                                    viewModel.selectPoiPin(pin.id, spatialPose.latitude, spatialPose.longitude)
                                    selectedTab = MainBottomTab.EXPLORE
                                    recenterTrigger++
                                },
                                onSimulateGpsNearPin = { pin ->
                                    viewModel.moveGpsNearPoi(pin.latitude, pin.longitude)
                                },
                                onOpenArForPin = { pin ->
                                    viewModel.openArExperienceForPoi(pin.id) {
                                        currentDestination = AppDestination.AR_CAMERA
                                    }
                                },
                                onRefreshPois = {
                                    viewModel.loadNearbyPois(spatialPose.latitude, spatialPose.longitude)
                                }
                            )
                        }

                        MainBottomTab.EXPLORE -> {
                            ExploreMapTabContent(
                                uiState = uiState,
                                spatialPose = spatialPose,
                                recenterTrigger = recenterTrigger,
                                onIncrementRecenter = { recenterTrigger++ },
                                onShowAuthModal = { showAuthModal = true },
                                onOpenAdminConsole = {
                                    viewModel.loadAdminConsoleData()
                                    currentDestination = AppDestination.ADMIN_CONSOLE
                                },
                                onOpenArCamera = { poiId ->
                                    viewModel.openArExperienceForPoi(poiId) {
                                        currentDestination = AppDestination.AR_CAMERA
                                    }
                                },
                                viewModel = viewModel
                            )
                        }

                        MainBottomTab.INFO -> {
                            InfoAndProfileTabScreen(
                                uiState = uiState,
                                spatialPose = spatialPose,
                                onSetLanguage = { lang -> viewModel.setLanguage(lang) },
                                onOpenAccountModal = { showAuthModal = true },
                                onOpenAdminConsole = {
                                    viewModel.loadAdminConsoleData()
                                    currentDestination = AppDestination.ADMIN_CONSOLE
                                },
                                onLogout = { viewModel.logout() }
                            )
                        }
                    }
                }
            }
        }
    }

    // Auth & Role Switcher Modal (`Tourist`, `Admin`, `SuperAdmin`) - Mandatory login gate per BR-01
    if (showAuthModal || uiState.currentUser == null) {
        AuthAndRoleDialog(
            currentUser = uiState.currentUser,
            selectedLanguage = uiState.selectedLanguage,
            onLogin = { email, password ->
                viewModel.login(email, password) { showAuthModal = false }
            },
            onRegister = { email, password, name ->
                viewModel.register(email, password, name, uiState.selectedLanguage) {
                    showAuthModal = false
                }
            },
            onLogout = {
                viewModel.logout()
                showAuthModal = false
            },
            onDismiss = { showAuthModal = false }
        )
    }
}

// ============================================================================
// TAB 1: TRANG CHỦ (HOME TAB SCREEN)
// ============================================================================

@Composable
private fun HomeTabScreen(
    uiState: SightseeingUiState,
    spatialPose: DeviceSpatialPose,
    onToggleLanguage: () -> Unit,
    onOpenAccountModal: () -> Unit,
    onOpenAdminConsole: () -> Unit,
    onNavigateToExploreMap: (String?) -> Unit,
    onNavigateToPlacesTab: () -> Unit,
    onSimulateAndOpenAr: (PoiPinSummaryDto) -> Unit
) {
    val isEn = uiState.selectedLanguage.equals("en", ignoreCase = true)
    val unlockedCount = uiState.nearbyPois.count { pin ->
        val dist = LocationAndOrientationTracker.calculateDistanceMeters(
            spatialPose.latitude,
            spatialPose.longitude,
            pin.latitude,
            pin.longitude
        )
        pin.canUnlockAr(dist)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
            .verticalScroll(rememberScrollState())
    ) {
        // Hero Header Banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF0F172A), Color(0xFF0369A1))
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 18.dp, vertical = 20.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isEn) "SIGHTSEEING TRAVEL AR" else "DU LỊCH VĂN HÓA & THỰC TẾ ẢO AR",
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (uiState.currentUser != null) {
                                if (isEn) "Hello, ${uiState.currentUser.displayName}" else "Xin chào, ${uiState.currentUser.displayName}"
                            } else {
                                if (isEn) "Discover Cultural Stories" else "Khám Phá Di Sản Việt Nam"
                            },
                            color = Color.White,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = onToggleLanguage,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text(
                                text = if (isEn) "EN" else "VI",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }

                        IconButton(
                            onClick = onOpenAccountModal,
                            modifier = Modifier
                                .size(34.dp)
                                .background(Color.White.copy(alpha = 0.2f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Account",
                                tint = Color.White
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = if (isEn) {
                        "Walk to historical landmarks on the GPS map, open your 360° AR Camera to view floating cultural stories and community photos."
                    } else {
                        "Di chuyển đến các địa danh lịch sử trên bản đồ GPS, mở Camera AR 360° để xem câu chuyện văn hóa và những bức ảnh đẹp từ cộng đồng."
                    },
                    color = Color(0xFFE0F2FE),
                    fontSize = 13.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { onNavigateToExploreMap(null) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Explore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isEn) "Open Explore Map" else "Mở Bản Đồ Khám Phá",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = onNavigateToPlacesTab,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isEn) "All Places" else "Xem Địa Điểm")
                    }
                }
            }
        }

        // Quick Telemetry & AR Status Cards
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            HomeStatCard(
                title = if (isEn) "Nearby POIs" else "Địa điểm gần bạn",
                value = "${uiState.nearbyPois.size}",
                subtitle = if (isEn) "Published pins" else "Điểm đã duyệt",
                icon = Icons.Default.Place,
                accentColor = Color(0xFF0284C7),
                modifier = Modifier.weight(1f)
            )
            HomeStatCard(
                title = if (isEn) "AR Unlocked" else "Sẵn sàng mở AR",
                value = "$unlockedCount",
                subtitle = if (isEn) "Within radius" else "Trong vùng GPS",
                icon = Icons.Default.ViewInAr,
                accentColor = Color(0xFF16A34A),
                modifier = Modifier.weight(1f)
            )
            HomeStatCard(
                title = if (isEn) "Compass" else "Hướng la bàn",
                value = "${spatialPose.azimuthDegrees.toInt()}°",
                subtitle = if (isEn) "Live heading" else "Góc nhìn 360°",
                icon = Icons.Default.CompassCalibration,
                accentColor = Color(0xFFD97706),
                modifier = Modifier.weight(1f)
            )
        }

        // Admin / SuperAdmin Quick Governance Banner (if applicable)
        if (uiState.currentUser?.isAdminOrSuperAdmin == true) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable { onOpenAdminConsole() }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (uiState.currentUser.isSuperAdmin) {
                                if (isEn) "SuperAdmin Governance Console" else "Bảng Chủ Quản (SuperAdmin) - Duyệt Địa Điểm & Ảnh"
                            } else {
                                if (isEn) "Admin POI & Moderation Console" else "Bảng Quản Trị Viên (Admin) - Tạo Pin & Kiểm Duyệt"
                            },
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp,
                            color = Color(0xFF92400E)
                        )
                        Text(
                            text = if (isEn) "Tap to manage map pins, AR cards, and community photos" else "Chạm để quản lý địa điểm (Pin), thẻ AR và duyệt ảnh cộng đồng",
                            fontSize = 12.sp,
                            color = Color(0xFFB45309)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ArrowForwardIos,
                        contentDescription = null,
                        tint = Color(0xFF92400E),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Section: Featured Sightseeing Destinations
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isEn) "Featured Cultural Destinations" else "Địa Điểm Văn Hóa Nổi Bật",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 17.sp,
                color = Color(0xFF0F172A)
            )
            TextButton(onClick = onNavigateToPlacesTab) {
                Text(if (isEn) "See All" else "Xem tất cả")
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(uiState.nearbyPois, key = { it.id }) { pin ->
                val dist = LocationAndOrientationTracker.calculateDistanceMeters(
                    spatialPose.latitude,
                    spatialPose.longitude,
                    pin.latitude,
                    pin.longitude
                )
                val isUnlocked = pin.canUnlockAr(dist)

                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    modifier = Modifier.width(285.dp)
                ) {
                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(145.dp)
                        ) {
                            AsyncImage(
                                model = pin.coverImageUrl,
                                contentDescription = pin.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Surface(
                                color = if (isUnlocked) Color(0xFF16A34A) else Color(0xFF0F172A).copy(alpha = 0.8f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = if (isUnlocked) {
                                        if (isEn) "AR Ready (${dist.toInt()}m)" else "Đã mở AR (${dist.toInt()}m)"
                                    } else {
                                        "${dist.toInt()}m"
                                    },
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = pin.category.uppercase(),
                                color = Color(0xFF0284C7),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = pin.title,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = Color(0xFF0F172A)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = pin.shortSummary,
                                fontSize = 12.5.sp,
                                color = Color(0xFF475569),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { onNavigateToExploreMap(pin.id) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isEn) "View Pin" else "Bản đồ", fontSize = 12.sp)
                                }
                                Button(
                                    onClick = { onSimulateAndOpenAr(pin) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.ViewInAr, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isEn) "Open AR" else "Mở AR 360°", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Section: How Sightseeing Travel AR Works
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (isEn) "How to Experience 360° Cultural AR" else "Trải Nghiệm Du Lịch Thực Tế Tăng Cường (AR)",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.5.sp,
                    color = Color(0xFF0F172A)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (isEn) {
                        "1. Select a destination in the 'Places' or 'Explore' tab.\n" +
                            "2. Walk within the POI's GPS radius (or tap 'Simulate GPS Here' for quick testing).\n" +
                            "3. Open the 360° AR Camera to read cultural stories floating in real space and capture photos to share with fellow travelers."
                    } else {
                        "1. Chọn một địa danh trong tab 'Địa điểm' hoặc 'Khám phá'.\n" +
                            "2. Di chuyển vào bán kính GPS của điểm đến (hoặc bấm 'Giả lập đến Pin' để kiểm thử nhanh).\n" +
                            "3. Mở Camera AR 360° để xem các thẻ câu chuyện văn hóa lơ lửng trong không gian thực và chụp ảnh chia sẻ lên cộng đồng."
                    },
                    fontSize = 13.sp,
                    color = Color(0xFF334155),
                    lineHeight = 20.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun HomeStatCard(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 19.sp,
                color = Color(0xFF0F172A)
            )
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.5.sp,
                color = Color(0xFF334155),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = Color(0xFF64748B),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ============================================================================
// TAB 2: ĐỊA ĐIỂM (PLACES DIRECTORY TAB SCREEN)
// ============================================================================

@Composable
private fun PlacesDirectoryTabScreen(
    uiState: SightseeingUiState,
    spatialPose: DeviceSpatialPose,
    onViewPoiOnExploreMap: (PoiPinSummaryDto) -> Unit,
    onSimulateGpsNearPin: (PoiPinSummaryDto) -> Unit,
    onOpenArForPin: (PoiPinSummaryDto) -> Unit,
    onRefreshPois: () -> Unit
) {
    val isEn = uiState.selectedLanguage.equals("en", ignoreCase = true)
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("ALL") }

    val categories = remember(uiState.nearbyPois) {
        listOf("ALL", "AR_READY") + uiState.nearbyPois.map { it.category }.distinct()
    }

    val filteredPois = uiState.nearbyPois.filter { pin ->
        val matchesSearch = searchQuery.isBlank() ||
            pin.title.contains(searchQuery, ignoreCase = true) ||
            pin.shortSummary.contains(searchQuery, ignoreCase = true) ||
            pin.category.contains(searchQuery, ignoreCase = true)

        val liveDist = LocationAndOrientationTracker.calculateDistanceMeters(
            spatialPose.latitude,
            spatialPose.longitude,
            pin.latitude,
            pin.longitude
        )
        val matchesCategory = when (selectedCategory) {
            "ALL" -> true
            "AR_READY" -> pin.canUnlockAr(liveDist)
            else -> pin.category.equals(selectedCategory, ignoreCase = true)
        }
        matchesSearch && matchesCategory
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
            .statusBarsPadding()
    ) {
        // Header & Search Bar
        Surface(
            color = Color.White,
            shadowElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (isEn) "Cultural Places Directory" else "Danh Sách Địa Điểm Văn Hóa",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 20.sp,
                            color = Color(0xFF0F172A)
                        )
                        Text(
                            text = if (isEn) {
                                "${filteredPois.size} destinations available for GPS & AR exploration"
                            } else {
                                "${filteredPois.size} địa danh văn hóa hỗ trợ định vị GPS & AR 360°"
                            },
                            fontSize = 12.sp,
                            color = Color(0xFF64748B)
                        )
                    }

                    IconButton(onClick = onRefreshPois) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = Color(0xFF0284C7)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            if (isEn) "Search place name, history, category..."
                            else "Tìm tên địa danh, câu chuyện lịch sử, danh mục..."
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(categories) { category ->
                        val label = when (category) {
                            "ALL" -> if (isEn) "All Places" else "Tất cả"
                            "AR_READY" -> if (isEn) "AR Unlocked" else "Đã mở khóa AR"
                            else -> category
                        }
                        FilterChip(
                            selected = selectedCategory == category,
                            onClick = { selectedCategory = category },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }
            }
        }

        // Places List
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(filteredPois, key = { it.id }) { pin ->
                val liveDist = LocationAndOrientationTracker.calculateDistanceMeters(
                    spatialPose.latitude,
                    spatialPose.longitude,
                    pin.latitude,
                    pin.longitude
                )
                val unlocked = pin.canUnlockAr(liveDist)

                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                        ) {
                            AsyncImage(
                                model = pin.coverImageUrl,
                                contentDescription = pin.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Surface(
                                color = Color(0xFF0F172A).copy(alpha = 0.78f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = pin.category,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }

                            Surface(
                                color = if (unlocked) Color(0xFF16A34A) else Color(0xFFD97706),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = if (unlocked) {
                                        if (isEn) "AR Unlocked • ${liveDist.toInt()}m" else "Mở khóa AR • ${liveDist.toInt()}m"
                                    } else {
                                        if (isEn) "${liveDist.toInt()}m away" else "Cách ${liveDist.toInt()}m"
                                    },
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = pin.title,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 18.sp,
                                color = Color(0xFF0F172A)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = pin.shortSummary,
                                fontSize = 13.5.sp,
                                color = Color(0xFF475569)
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "AR Cards: ${pin.arInfoCardCount}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF0284C7)
                                )
                                Text(
                                    text = if (isEn) "Photos: ${pin.communityPhotoCount}" else "Ảnh cộng đồng: ${pin.communityPhotoCount}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF0F766E)
                                )
                                Text(
                                    text = if (isEn) "Radius: ${pin.activationRadiusMeters.toInt()}m" else "Bán kính AR: ${pin.activationRadiusMeters.toInt()}m",
                                    fontSize = 12.sp,
                                    color = Color(0xFF64748B)
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { onViewPoiOnExploreMap(pin) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Explore, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isEn) "Explore Map" else "Bản đồ Khám phá", fontSize = 12.sp)
                                }

                                if (!unlocked) {
                                    FilledTonalButton(
                                        onClick = { onSimulateGpsNearPin(pin) }
                                    ) {
                                        Icon(Icons.Default.NearMe, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(if (isEn) "Simulate GPS" else "Giả lập đến Pin", fontSize = 12.sp)
                                    }
                                } else {
                                    Button(
                                        onClick = { onOpenArForPin(pin) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.ViewInAr, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(if (isEn) "Open 360° AR" else "Mở Camera AR", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// TAB 3: KHÁM PHÁ (EXPLORE MAP & AR TAB - CURRENT APP SCREEN)
// ============================================================================

@Composable
private fun ExploreMapTabContent(
    uiState: SightseeingUiState,
    spatialPose: DeviceSpatialPose,
    recenterTrigger: Int,
    onIncrementRecenter: () -> Unit,
    onShowAuthModal: () -> Unit,
    onOpenAdminConsole: () -> Unit,
    onOpenArCamera: (String) -> Unit,
    viewModel: SightseeingAppViewModel
) {
    val isEn = uiState.selectedLanguage.equals("en", ignoreCase = true)

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. MapLibre + OpenFreeMap / OpenStreetMap Map View
        OpenFreeMapComposeView(
            spatialPose = spatialPose,
            pois = uiState.nearbyPois,
            selectedPoiId = uiState.selectedPoiDetail?.id,
            recenterTrigger = recenterTrigger,
            onPoiMarkerClick = { tappedPin ->
                viewModel.selectPoiPin(
                    poiId = tappedPin.id,
                    latitude = spatialPose.latitude,
                    longitude = spatialPose.longitude
                )
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Top Floating Bar (Language Toggle VI/EN, Role & Auth, Admin Console Button)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.White.copy(alpha = 0.95f),
                shadowElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isEn) "Explore GPS & 360° AR" else "Khám Phá Bản Đồ GPS & AR",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp,
                            color = Color(0xFF0F172A)
                        )
                        Text(
                            text = if (uiState.currentUser != null) {
                                "${uiState.currentUser.displayName} (${uiState.currentUser.role})"
                            } else {
                                if (isEn) "OpenFreeMap • Tap a Pin to explore" else "OpenFreeMap • Chạm vào Pin để khám phá"
                            },
                            fontSize = 11.5.sp,
                            color = Color(0xFF475569)
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Language Switcher (VI / EN)
                        OutlinedButton(
                            onClick = {
                                viewModel.setLanguage(if (isEn) "vi" else "en")
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text(
                                text = if (isEn) "EN" else "VI",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }

                        // Admin / SuperAdmin Console Button
                        if (uiState.currentUser?.isAdminOrSuperAdmin == true) {
                            FilledTonalButton(
                                onClick = onOpenAdminConsole,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AdminPanelSettings,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (uiState.currentUser.isSuperAdmin) "SuperAdmin" else "Admin",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Account / Role Sign-In Button
                        IconButton(
                            onClick = onShowAuthModal,
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color(0xFFE0F2FE), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Account",
                                tint = Color(0xFF0284C7)
                            )
                        }
                    }
                }
            }

            // Status or Error Banner
            val bannerText = uiState.errorMessage ?: uiState.statusBannerMessage
            if (!bannerText.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = if (uiState.errorMessage != null) Color(0xFFFEE2E2) else Color(0xFFDCFCE7),
                    shape = RoundedCornerShape(12.dp),
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.clearBannerMessages() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = bannerText,
                            color = if (uiState.errorMessage != null) Color(0xFF991B1B) else Color(0xFF14532D),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Horizontal Quick-Select Carousel of Nearby Published POI Pins
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(uiState.nearbyPois, key = { it.id }) { pin ->
                    val liveDist = LocationAndOrientationTracker.calculateDistanceMeters(
                        spatialPose.latitude,
                        spatialPose.longitude,
                        pin.latitude,
                        pin.longitude
                    )
                    val unlocked = pin.canUnlockAr(liveDist)
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (unlocked) Color(0xFF16A34A) else Color(0xFF0F172A).copy(alpha = 0.88f),
                        shadowElevation = 3.dp,
                        modifier = Modifier.clickable {
                            viewModel.selectPoiPin(pin.id, spatialPose.latitude, spatialPose.longitude)
                        }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Icon(
                                imageVector = if (unlocked) Icons.Default.ViewInAr else Icons.Default.Place,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${pin.title} (${liveDist.toInt()}m)",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        // 3. Recenter GPS FAB
        FloatingActionButton(
            onClick = {
                onIncrementRecenter()
                viewModel.loadNearbyPois(spatialPose.latitude, spatialPose.longitude)
            },
            containerColor = Color.White,
            contentColor = Color(0xFF0284C7),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = if (uiState.selectedPoiDetail != null) 340.dp else 20.dp)
        ) {
            Icon(Icons.Default.MyLocation, contentDescription = "Recenter GPS")
        }

        // 4. Selected POI Cultural Story & Community Photos Card Sheet
        uiState.selectedPoiDetail?.let { detail ->
            val liveDistance = LocationAndOrientationTracker.calculateDistanceMeters(
                spatialPose.latitude,
                spatialPose.longitude,
                detail.latitude,
                detail.longitude
            )
            val isUnlocked = detail.isWithinUnlockRadius(liveDistance)

            PoiCulturalStoryCard(
                detail = detail,
                liveDistanceMeters = liveDistance,
                isArUnlocked = isUnlocked,
                communityPhotos = uiState.poiCommunityPhotos,
                photoSortBy = uiState.photoSortBy,
                selectedLanguage = uiState.selectedLanguage,
                onSortChanged = { sortBy ->
                    viewModel.loadCommunityPhotos(detail.id, sortBy)
                },
                onSimulateGpsArrival = {
                    viewModel.moveGpsNearPoi(detail.latitude, detail.longitude)
                },
                onOpenArCamera = {
                    onOpenArCamera(detail.id)
                },
                onToggleLikePhoto = { photoId ->
                    viewModel.toggleLikePhoto(photoId)
                },
                onClose = {
                    viewModel.clearSelectedPoi()
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

// ============================================================================
// TAB 4: THÔNG TIN (INFO & PROFILE TAB SCREEN)
// ============================================================================

@Composable
private fun InfoAndProfileTabScreen(
    uiState: SightseeingUiState,
    spatialPose: DeviceSpatialPose,
    onSetLanguage: (String) -> Unit,
    onOpenAccountModal: () -> Unit,
    onOpenAdminConsole: () -> Unit,
    onLogout: () -> Unit
) {
    val isEn = uiState.selectedLanguage.equals("en", ignoreCase = true)
    val user = uiState.currentUser

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = if (isEn) "Information & Account" else "Thông Tin & Tài Khoản",
            fontWeight = FontWeight.ExtraBold,
            fontSize = 22.sp,
            color = Color(0xFF0F172A)
        )

        // 1. User Profile & Role Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(54.dp)
                            .background(Color(0xFFE0F2FE), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = Color(0xFF0284C7),
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = user?.displayName ?: (if (isEn) "Guest" else "Khách du lịch"),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 18.sp,
                            color = Color(0xFF0F172A)
                        )
                        Text(
                            text = user?.email ?: "",
                            fontSize = 13.sp,
                            color = Color(0xFF64748B)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            color = Color(0xFFDCFCE7),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Role: ${user?.role ?: "Tourist"}",
                                color = Color(0xFF15803D),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onOpenAccountModal,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.SwitchAccount, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isEn) "Switch Role / Account" else "Đổi Tài Khoản / Vai Trò")
                    }

                    if (user != null) {
                        OutlinedButton(
                            onClick = onLogout,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626))
                        ) {
                            Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isEn) "Sign Out" else "Đăng xuất")
                        }
                    }
                }
            }
        }

        // 2. Admin / SuperAdmin Console Entry (if Admin or SuperAdmin)
        if (user?.isAdminOrSuperAdmin == true) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (user.isSuperAdmin) {
                            if (isEn) "SuperAdmin Approval & Moderation" else "Quản Trị Cấp Cao (SuperAdmin)"
                        } else {
                            if (isEn) "Admin Content Management" else "Quản Trị Nội Dung (Admin)"
                        },
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp,
                        color = Color(0xFF92400E)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isEn) {
                            "Create and manage POI pins, cultural stories, 360° AR cards, approve pending POIs, and moderate community photos."
                        } else {
                            "Tạo và quản lý địa điểm (Pin), câu chuyện văn hóa, cấu hình thẻ AR 360°, phê duyệt địa điểm và kiểm duyệt ảnh người dùng."
                        },
                        fontSize = 12.5.sp,
                        color = Color(0xFFB45309)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onOpenAdminConsole,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706))
                    ) {
                        Icon(Icons.Default.AdminPanelSettings, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isEn) "Open Governance Console" else "Mở Bảng Quản Trị & Kiểm Duyệt")
                    }
                }
            }
        }

        // 3. Language Preferences Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (isEn) "Cultural Guide Language" else "Ngôn Ngữ Thuyết Minh Văn Hóa",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color(0xFF0F172A)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = !isEn,
                        onClick = { onSetLanguage("vi") },
                        label = { Text("Tiếng Việt (VI)") }
                    )
                    FilterChip(
                        selected = isEn,
                        onClick = { onSetLanguage("en") },
                        label = { Text("English (EN)") }
                    )
                }
            }
        }

        // 4. Live GPS & Spatial Sensor Telemetry Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (isEn) "Live GPS & AR Spatial Sensors" else "Thông Số Định Vị GPS & Cảm Biến AR",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color(0xFF0F172A)
                )
                Text(
                    text = "Latitude: ${"%.6f".format(spatialPose.latitude)}",
                    fontSize = 13.sp,
                    color = Color(0xFF334155)
                )
                Text(
                    text = "Longitude: ${"%.6f".format(spatialPose.longitude)}",
                    fontSize = 13.sp,
                    color = Color(0xFF334155)
                )
                Text(
                    text = if (isEn) {
                        "Compass Azimuth: ${"%.1f".format(spatialPose.azimuthDegrees)}° | Pitch: ${"%.1f".format(spatialPose.pitchDegrees)}°"
                    } else {
                        "Góc La bàn (Azimuth): ${"%.1f".format(spatialPose.azimuthDegrees)}° | Độ nghiêng (Pitch): ${"%.1f".format(spatialPose.pitchDegrees)}°"
                    },
                    fontSize = 13.sp,
                    color = Color(0xFF334155)
                )
            }
        }

        // 5. About Sightseeing Travel Platform
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (isEn) "About Sightseeing Travel" else "Giới Thiệu Ứng Dụng Sightseeing Travel",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color(0xFF0F172A)
                )
                Text(
                    text = if (isEn) {
                        "Version 1.0.0 • Cultural Sightseeing, Live GPS Map (OpenFreeMap / Apple MapKit), 360° Spatial AR Camera & Community Photo Sharing."
                    } else {
                        "Phiên bản 1.0.0 • Nền tảng khám phá văn hóa du lịch, định vị bản đồ GPS (OpenFreeMap / Apple MapKit), Camera Thực tế tăng cường (AR) 360° và chia sẻ ảnh cộng đồng."
                    },
                    fontSize = 13.sp,
                    color = Color(0xFF475569)
                )
            }
        }
    }
}

/**
 * Interactive Bottom Sheet Card showing the POI's Cultural Story, Historical Period,
 * Geofence AR Unlock button (`distance <= activationRadiusMeters + 20m`), and Community Photos.
 */
@Composable
private fun PoiCulturalStoryCard(
    detail: PoiDetailDto,
    liveDistanceMeters: Double,
    isArUnlocked: Boolean,
    communityPhotos: List<CommunityPhotoDto>,
    photoSortBy: String,
    selectedLanguage: String,
    onSortChanged: (String) -> Unit,
    onSimulateGpsArrival: () -> Unit,
    onOpenArCamera: () -> Unit,
    onToggleLikePhoto: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isEn = selectedLanguage.equals("en", ignoreCase = true)

    Surface(
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = Color.White,
        shadowElevation = 16.dp,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 430.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = Color(0xFFE0F2FE),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = detail.category,
                                color = Color(0xFF0369A1),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${liveDistanceMeters.toInt()}m away (Unlock <= ${(detail.activationRadiusMeters + 20).toInt()}m)",
                            fontSize = 11.5.sp,
                            color = if (isArUnlocked) Color(0xFF15803D) else Color(0xFFB45309),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = detail.title,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 19.sp,
                        color = Color(0xFF0F172A)
                    )
                }

                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Primary Action Row: Open Spatial AR Camera or Simulate GPS Arrival
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onOpenArCamera,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isArUnlocked) Color(0xFF16A34A) else Color(0xFF0284C7)
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ViewInAr, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isArUnlocked) {
                            if (isEn) "Open 360° AR Camera" else "Mở Camera Thực tế ảo AR"
                        } else {
                            if (isEn) "AR Locked (${liveDistanceMeters.toInt()}m)" else "Chưa vào vùng AR (${liveDistanceMeters.toInt()}m)"
                        },
                        fontWeight = FontWeight.Bold
                    )
                }

                if (!isArUnlocked) {
                    OutlinedButton(onClick = onSimulateGpsArrival) {
                        Icon(Icons.Default.NearMe, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isEn) "Simulate GPS Here" else "Giả lập đến Pin",
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Cultural Story Content
            Text(
                text = detail.shortSummary,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = Color(0xFF1E293B)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = detail.culturalStoryMarkdown,
                fontSize = 13.sp,
                color = Color(0xFF475569)
            )

            if (detail.architecturalHighlights.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(detail.architecturalHighlights) { highlight ->
                        Surface(
                            color = Color(0xFFF1F5F9),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = "• $highlight",
                                fontSize = 11.5.sp,
                                color = Color(0xFF334155),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Divider()
            Spacer(modifier = Modifier.height(10.dp))

            // Highlighted Community Photos Carousel
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isEn) {
                        "Highlighted Community Photos (${communityPhotos.size})"
                    } else {
                        "Ảnh Cộng đồng Nổi bật (${communityPhotos.size})"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = photoSortBy == "popular",
                        onClick = { onSortChanged("popular") },
                        label = { Text(if (isEn) "Top Liked" else "Nổi bật", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = photoSortBy == "latest",
                        onClick = { onSortChanged("latest") },
                        label = { Text(if (isEn) "Latest" else "Mới nhất", fontSize = 11.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(communityPhotos, key = { it.id }) { photo ->
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.width(180.dp)
                    ) {
                        Column {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(110.dp)
                            ) {
                                AsyncImage(
                                    model = photo.imageUrl,
                                    contentDescription = photo.caption,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Surface(
                                    color = Color.Black.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(bottomStart = 10.dp),
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .clickable { onToggleLikePhoto(photo.id) }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (photo.isLikedByCurrentUser) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                            contentDescription = "Like",
                                            tint = if (photo.isLikedByCurrentUser) Color(0xFFF43F5E) else Color.White,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "${photo.likeCount}",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = photo.caption ?: photo.authorDisplayName,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "by ${photo.authorDisplayName}",
                                    fontSize = 10.5.sp,
                                    color = Color(0xFF64748B)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * JWT Authentication & Quick Role Switcher Dialog supporting `Tourist`, `Admin`, and `SuperAdmin`.
 */
@Composable
private fun AuthAndRoleDialog(
    currentUser: UserProfileDto?,
    selectedLanguage: String,
    onLogin: (String, String) -> Unit,
    onRegister: (String, String, String) -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit
) {
    val isEn = selectedLanguage.equals("en", ignoreCase = true)
    var isRegisterMode by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf(currentUser?.email ?: "tourist@awaken.vn") }
    var password by remember { mutableStateOf("Travel@2026") }
    var displayName by remember { mutableStateOf("Minh Traveler") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (currentUser != null) {
                    "Account (${currentUser.role})"
                } else if (isRegisterMode) {
                    "Register Tourist Account"
                } else {
                    "Sign In (JWT Auth)"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (currentUser != null) {
                    Text("Signed in as: ${currentUser.displayName}")
                    Text("Email: ${currentUser.email}")
                    Text("Role: ${currentUser.role}", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Text(
                    text = if (isEn) "Quick Role Sign-In Presets:" else "Chọn nhanh vai trò kiểm thử:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AssistChip(
                        onClick = { onLogin("tourist@awaken.vn", "Travel@2026") },
                        label = { Text("Tourist", fontSize = 11.sp) }
                    )
                    AssistChip(
                        onClick = { onLogin("admin@awaken.vn", "Admin@2026") },
                        label = { Text("Admin", fontSize = 11.sp) }
                    )
                    AssistChip(
                        onClick = { onLogin("superadmin@awaken.vn", "SuperAdmin@2026") },
                        label = { Text("SuperAdmin", fontSize = 11.sp) }
                    )
                }

                Divider(modifier = Modifier.padding(vertical = 4.dp))

                if (isRegisterMode) {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("Display Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                TextButton(onClick = { isRegisterMode = !isRegisterMode }) {
                    Text(
                        if (isRegisterMode) "Already have an account? Sign In"
                        else "New traveler? Create a Tourist account"
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isRegisterMode) {
                        onRegister(email, password, displayName)
                    } else {
                        onLogin(email, password)
                    }
                }
            ) {
                Text(if (isRegisterMode) "Register" else "Sign In")
            }
        },
        dismissButton = {
            Row {
                if (currentUser != null) {
                    TextButton(onClick = onLogout) {
                        Text("Sign Out", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    )
}
