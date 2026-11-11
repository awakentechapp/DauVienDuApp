package com.awaken.sightseeingtravel.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.awaken.sightseeingtravel.BuildConfig
import coil.compose.AsyncImage
import com.awaken.sightseeingtravel.R
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
 * 2. Địa điểm (PLACES - includes Văn hóa & Ăn uống within 50km radius)
 * 3. Khám phá (EXPLORE - OpenFreeMap/Bright GPS Map + AR experience)
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
 * - 4 Bottom Tabs: Trang chủ, Địa điểm (Văn hóa & Ăn uống trong bán kính 50km), Khám phá (OpenFreeMap/Bright + AR), Thông tin
 * - User roles come from the authenticated API profile.
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
        if (viewModel.uiState.value.currentUser != null) {
            viewModel.startSensors()
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopSensors()
    }
}

private fun formatDistanceLabel(meters: Double): String {
    return if (meters < 1000.0) {
        "${meters.toInt()}m"
    } else {
        "%.1fkm".format(meters / 1000.0)
    }
}

private fun formatDurationLabel(seconds: Double, isEn: Boolean): String {
    val minutes = (seconds / 60.0).toInt().coerceAtLeast(1)
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return when {
        hours > 0 && isEn -> "${hours}h ${remainingMinutes}m"
        hours > 0 -> "${hours} giờ ${remainingMinutes} phút"
        isEn -> "$minutes min"
        else -> "$minutes phút"
    }
}

private fun isFoodCategory(category: String): Boolean {
    return PoiCategoryLabels.isFood(category)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SightseeingTravelRootApp(viewModel: SightseeingAppViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val spatialPose by viewModel.spatialPose.collectAsState()

    var currentDestination by remember { mutableStateOf(AppDestination.MAIN_TABS) }
    var selectedTab by remember { mutableStateOf(MainBottomTab.EXPLORE) }
    var initialPlacesCategory by remember { mutableStateOf("ALL") }
    var recenterTrigger by remember { mutableIntStateOf(0) }
    var focusedPoiId by remember { mutableStateOf<String?>(null) }
    var mapFocusTrigger by remember { mutableIntStateOf(0) }
    var showAuthModal by remember { mutableStateOf(false) }

    val isEn = uiState.selectedLanguage.equals("en", ignoreCase = true)
    val selectPoiForMap: (PoiPinSummaryDto) -> Unit = { pin ->
        viewModel.clearNavigation()
        focusedPoiId = pin.id
        mapFocusTrigger++
        viewModel.selectPoiPin(pin.id, spatialPose.latitude, spatialPose.longitude)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.startSensors()
    }

    LaunchedEffect(uiState.currentUser?.id) {
        if (uiState.currentUser != null) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.CAMERA
                )
            )
        }
    }

    if (uiState.currentUser == null) {
        AuthScreen(
            selectedLanguage = uiState.selectedLanguage,
            isLoading = uiState.isLoading,
            errorMessage = uiState.errorMessage,
            onLanguageChange = viewModel::setLanguage,
            onClearError = viewModel::clearBannerMessages,
            onLogin = { email, password -> viewModel.login(email, password) },
            onRegister = { email, password, name ->
                viewModel.register(email, password, name, uiState.selectedLanguage)
            }
        )
    } else {
    when (currentDestination) {
        AppDestination.AR_CAMERA -> {
            val arCtx = uiState.activeArContext
            if (arCtx != null) {
                ArCameraScreen(
                    arContext = arCtx,
                    spatialPose = spatialPose,
                    currentUserId = uiState.currentUser?.id,
                    isUploadingPhoto = uiState.isUploadingPhoto,
                    uploadErrorMessage = uiState.errorMessage,
                    selectedLanguage = uiState.selectedLanguage,
                    onCaptureAndUploadPhoto = { file, caption, anchor, onSuccess ->
                        viewModel.uploadCapturedPhoto(arCtx.poiId, file, caption, anchor, onSuccess)
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
                selectedLanguage = uiState.selectedLanguage,
                defaultLatitude = spatialPose.latitude,
                defaultLongitude = spatialPose.longitude,
                hasFreshGpsFix = spatialPose.hasFreshGpsFix && !spatialPose.isSimulatedLocation && spatialPose.accuracyMeters <= 50f,
                gpsAccuracyMeters = spatialPose.accuracyMeters,
                currentAzimuthDegrees = spatialPose.azimuthDegrees,
                adminArCardsByPoi = uiState.adminArCardsByPoi,
                onCreateFixedArCard = viewModel::createAdminArInfoCard,
                onLoadArModelLibrary = viewModel::loadAdminArModelLibrary,
                onPlaceExistingArModel = viewModel::placeExistingArModel,
                onUpdateArInfoCard = viewModel::updateAdminArInfoCard,
                onDeleteArInfoCard = viewModel::deleteAdminArInfoCard,
                onBuildArCard3dModel = viewModel::buildAdminArCard3dModel,
                onFilterStatusChange = { status ->
                    viewModel.loadAdminConsoleData(status)
                },
                onCreatePoiSubmit = { request, coverImageUri ->
                    viewModel.createPoiAsAdmin(request, coverImageUri)
                },
                onUpdatePoiSubmit = { poiId, request, coverImageUri ->
                    viewModel.updatePoiAsAdmin(poiId, request, coverImageUri)
                },
                onDeletePoi = viewModel::deletePoiAsSuperAdmin,
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
                                        focusedPoiId = poiId
                                        mapFocusTrigger++
                                        viewModel.selectPoiPin(poiId, spatialPose.latitude, spatialPose.longitude)
                                    }
                                },
                                onNavigateToPlacesTab = { categoryFilter ->
                                    initialPlacesCategory = categoryFilter
                                    selectedTab = MainBottomTab.PLACES
                                },
                                onOpenArForPin = { pin ->
                                    viewModel.selectPoiPin(pin.id, spatialPose.latitude, spatialPose.longitude)
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
                                initialCategory = initialPlacesCategory,
                                onCategoryChanged = { initialPlacesCategory = it },
                                onViewPoiOnExploreMap = { pin ->
                                    selectPoiForMap(pin)
                                    selectedTab = MainBottomTab.EXPLORE
                                },
                                onDirectionsToPin = { pin ->
                                    selectedTab = MainBottomTab.EXPLORE
                                    focusedPoiId = pin.id
                                    mapFocusTrigger++
                                    viewModel.calculateRouteToPin(
                                        pin.id,
                                        pin.title,
                                        pin.latitude,
                                        pin.longitude
                                    )
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
                                focusedPoiId = focusedPoiId,
                                mapFocusTrigger = mapFocusTrigger,
                                onIncrementRecenter = { recenterTrigger++ },
                                onSelectPoi = selectPoiForMap,
                                onDirectionsToPin = { poiId, title, latitude, longitude ->
                                    viewModel.calculateRouteToPin(poiId, title, latitude, longitude)
                                },
                                onSelectNavigationMode = viewModel::selectNavigationTravelMode,
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
    }

    // Authentication modal.
    if (showAuthModal && uiState.currentUser != null) {
        AuthAndRoleDialog(
            currentUser = uiState.currentUser,
            selectedLanguage = uiState.selectedLanguage,
            errorMessage = uiState.errorMessage,
            onLogin = { accountId, password ->
                viewModel.login(accountId, password) { showAuthModal = false }
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
    onNavigateToPlacesTab: (String) -> Unit,
    onOpenArForPin: (PoiPinSummaryDto) -> Unit
) {
    val isEn = uiState.selectedLanguage.equals("en", ignoreCase = true)
    val culturalPois = uiState.nearbyPois.filterNot { isFoodCategory(it.category) }
    val foodPois = uiState.nearbyPois.filter { isFoodCategory(it.category) }

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
                    Image(
                        painter = painterResource(id = R.drawable.logo_ngang_kem_dao),
                    contentDescription = "Dấu Viễn Du",
                        modifier = Modifier
                            .height(38.dp)
                            .wrapContentWidth(Alignment.Start)
                    )

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
                    text = if (uiState.currentUser != null) {
                        if (isEn) "Hello, ${uiState.currentUser.displayName}" else "Xin chào, ${uiState.currentUser.displayName}"
                    } else {
                        if (isEn) "Discover Cultural & Culinary Stories • 50km Radius" else "Khám Phá Văn Hóa & Ẩm Thực • Bán Kính 50km"
                    },
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = if (isEn) {
                        "Discover cultural landmarks and dining near you."
                    } else {
                        "Khám phá địa danh văn hóa và điểm Ăn uống gần bạn."
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
                            text = if (isEn) "Explore 50km Map" else "Bản Đồ Khám Phá",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = { onNavigateToPlacesTab("FOOD") },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.Restaurant, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isEn) "Food & Dining" else "Điểm Ăn Uống")
                    }
                }
            }
        }

        // Quick Telemetry & 50km Zone Cards
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            HomeStatCard(
                title = if (isEn) "Within 50km" else "Trong vùng 50km",
                value = "${uiState.nearbyPois.size}",
                subtitle = if (isEn) "Total places" else "Tổng địa điểm",
                icon = Icons.Default.Place,
                accentColor = Color(0xFF0284C7),
                modifier = Modifier.weight(1f)
            )
            HomeStatCard(
                title = if (isEn) "Food & Dining" else "Điểm Ăn uống",
                value = "${foodPois.size}",
                subtitle = if (isEn) "Within 50km" else "Ẩm thực 50km",
                icon = Icons.Default.Restaurant,
                accentColor = Color(0xFFE11D48),
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
                                if (isEn) "SuperAdmin Governance Console" else "Bảng Chủ Quản - Duyệt Địa Điểm & Kiểm Duyệt Ảnh"
                            } else {
                                if (isEn) "Admin POI & Moderation Console" else "Bảng Quản Trị Viên - Tạo Pin & Kiểm Duyệt"
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
                        imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = null,
                        tint = Color(0xFF92400E),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Section 1: Food & Dining ("Ăn uống") within 50km
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isEn) "Food & Dining Within 50km (Ăn uống)" else "Địa Điểm Ăn Uống Quanh Bạn (≤ 50km)",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 17.sp,
                color = Color(0xFF0F172A)
            )
            TextButton(onClick = { onNavigateToPlacesTab("FOOD") }) {
                Text(if (isEn) "See All" else "Xem tất cả")
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(foodPois, key = { it.id }) { pin ->
                PoiFeaturedHorizontalCard(
                    pin = pin,
                    spatialPose = spatialPose,
                    isEn = isEn,
                    onViewPin = { onNavigateToExploreMap(pin.id) },
                    onOpenAr = { onOpenArForPin(pin) }
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Section 2: Cultural & Heritage Landmarks within 50km
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isEn) "Cultural Heritage Within 50km" else "Địa Danh Văn Hóa & Lịch Sử (≤ 50km)",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 17.sp,
                color = Color(0xFF0F172A)
            )
            TextButton(onClick = { onNavigateToPlacesTab("ALL") }) {
                Text(if (isEn) "See All" else "Xem tất cả")
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(culturalPois, key = { it.id }) { pin ->
                PoiFeaturedHorizontalCard(
                    pin = pin,
                    spatialPose = spatialPose,
                    isEn = isEn,
                    onViewPin = { onNavigateToExploreMap(pin.id) },
                    onOpenAr = { onOpenArForPin(pin) }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun PoiFeaturedHorizontalCard(
    pin: PoiPinSummaryDto,
    spatialPose: DeviceSpatialPose,
    isEn: Boolean,
    onViewPin: () -> Unit,
    onOpenAr: () -> Unit
) {
    val dist = LocationAndOrientationTracker.calculateDistanceMeters(
        spatialPose.latitude,
        spatialPose.longitude,
        pin.latitude,
        pin.longitude
    )
    val isUnlocked = spatialPose.hasFreshGpsFix &&
        !spatialPose.isSimulatedLocation && spatialPose.accuracyMeters <= 50f && pin.canUnlockAr(dist)
    val isFood = isFoodCategory(pin.category)

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
                    .height(140.dp)
            ) {
                AsyncImage(
                    model = pin.coverImageUrl,
                    contentDescription = pin.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Surface(
                    color = if (isUnlocked) Color(0xFF16A34A) else if (isFood) Color(0xFFE11D48) else Color(0xFF0F172A).copy(alpha = 0.82f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                ) {
                    Text(
                        text = if (isUnlocked) {
                            if (isEn) "AR Ready (${formatDistanceLabel(dist)})" else "Mở AR (${formatDistanceLabel(dist)})"
                        } else {
                            "${formatDistanceLabel(dist)} • ≤50km"
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
                    text = PoiCategoryLabels.label(pin.category, if (isEn) "en" else "vi").uppercase(),
                    color = if (isFood) Color(0xFFE11D48) else Color(0xFF0284C7),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = pin.title,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.5.sp,
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
                        onClick = onViewPin,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isEn) "Map" else "Bản đồ", fontSize = 12.sp)
                    }
                    Button(
                        onClick = onOpenAr,
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
// TAB 2: ĐỊA ĐIỂM (PLACES DIRECTORY TAB - VĂN HÓA & ĂN UỐNG TRONG BÁN KÍNH 50KM)
// ============================================================================

@Composable
private fun PlacesDirectoryTabScreen(
    uiState: SightseeingUiState,
    spatialPose: DeviceSpatialPose,
    initialCategory: String,
    onCategoryChanged: (String) -> Unit,
    onViewPoiOnExploreMap: (PoiPinSummaryDto) -> Unit,
    onDirectionsToPin: (PoiPinSummaryDto) -> Unit,
    onOpenArForPin: (PoiPinSummaryDto) -> Unit,
    onRefreshPois: () -> Unit
) {
    val isEn = uiState.selectedLanguage.equals("en", ignoreCase = true)
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember(initialCategory) { mutableStateOf(initialCategory) }

    val filterKeys = listOf("ALL", "FOOD", "CULTURE", "AR_READY")

    // Filter all POIs strictly within 50km (50,000 meters) of current location
    val poisWithin50Km = uiState.nearbyPois.map { pin ->
        val dist = LocationAndOrientationTracker.calculateDistanceMeters(
            spatialPose.latitude,
            spatialPose.longitude,
            pin.latitude,
            pin.longitude
        )
        pin.copy(distanceMeters = dist)
    }.filter { (it.distanceMeters ?: 0.0) <= SightseeingAppViewModel.SEARCH_RADIUS_METERS }
        .sortedBy { it.distanceMeters ?: Double.MAX_VALUE }

    val filteredPois = poisWithin50Km.filter { pin ->
        val matchesSearch = searchQuery.isBlank() ||
            pin.title.contains(searchQuery, ignoreCase = true) ||
            pin.shortSummary.contains(searchQuery, ignoreCase = true) ||
            pin.category.contains(searchQuery, ignoreCase = true)

        val liveDist = pin.distanceMeters ?: 0.0
        val matchesCategory = when (selectedCategory) {
            "ALL" -> true
            "FOOD" -> isFoodCategory(pin.category)
            "CULTURE" -> !isFoodCategory(pin.category)
            "AR_READY" -> spatialPose.hasFreshGpsFix && !spatialPose.isSimulatedLocation &&
                spatialPose.accuracyMeters <= 50f && pin.canUnlockAr(liveDist)
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isEn) "Places & Dining" else "Địa Điểm & Ăn Uống",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 19.sp,
                            color = Color(0xFF0F172A)
                        )
                        Text(
                            text = if (isEn) {
                                "Showing ${filteredPois.size} places near you"
                            } else {
                                "Hiển thị ${filteredPois.size} điểm gần bạn"
                            },
                            fontSize = 11.5.sp,
                            color = Color(0xFF0284C7),
                            fontWeight = FontWeight.SemiBold
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
                            if (isEn) "Search food, heritage..."
                            else "Tìm Ăn Uống, Di sản..."
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
                    items(filterKeys) { key ->
                        val label = when (key) {
                            "ALL" -> if (isEn) "All (≤ 50km)" else "Tất cả (≤ 50km)"
                            "FOOD" -> if (isEn) "🍽️ Food & Dining (Ăn uống)" else "🍽️ Ăn uống"
                            "CULTURE" -> if (isEn) "🏛️ Cultural & Heritage" else "🏛️ Di sản & Văn hóa"
                            "AR_READY" -> if (isEn) "✨ AR Unlocked" else "✨ Đã mở khóa AR"
                            else -> key
                        }
                        FilterChip(
                            selected = selectedCategory == key,
                            onClick = {
                                selectedCategory = key
                                onCategoryChanged(key)
                            },
                            label = { Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                        )
                    }
                }
            }
        }

        // Places List within 50km
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(filteredPois, key = { it.id }) { pin ->
                val liveDist = pin.distanceMeters ?: 0.0
                val unlocked = spatialPose.hasFreshGpsFix && !spatialPose.isSimulatedLocation &&
                    spatialPose.accuracyMeters <= 50f && pin.canUnlockAr(liveDist)
                val isFood = isFoodCategory(pin.category)

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
                                color = if (isFood) Color(0xFFE11D48) else Color(0xFF0F172A).copy(alpha = 0.82f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isFood) Icons.Default.Restaurant else Icons.Default.AccountBalance,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = PoiCategoryLabels.label(pin.category, if (isEn) "en" else "vi"),
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
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
                                        if (isEn) "AR Unlocked • ${formatDistanceLabel(liveDist)}" else "Mở khóa AR • ${formatDistanceLabel(liveDist)}"
                                    } else {
                                        if (isEn) "${formatDistanceLabel(liveDist)} (≤ 50km)" else "Cách ${formatDistanceLabel(liveDist)} (≤ 50km)"
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
                                    Text(if (isEn) "Explore Map" else "Xem trên Bản đồ", fontSize = 12.sp)
                                }

                                FilledTonalButton(
                                    onClick = { onDirectionsToPin(pin) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                                ) {
                                    Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isEn) "Directions" else "Dẫn đường đến Pin", fontSize = 11.sp)
                                }

                                if (unlocked) {
                                    Button(
                                        onClick = { onOpenArForPin(pin) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                                    ) {
                                        Icon(Icons.Default.ViewInAr, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(if (isEn) "Open AR" else "Mở Camera AR", fontSize = 11.sp)
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
// TAB 3: KHÁM PHÁ (EXPLORE MAP & AR TAB - OPENFREEMAP/BRIGHT + 50KM RADIUS)
// ============================================================================

@Composable
private fun ExploreMapTabContent(
    uiState: SightseeingUiState,
    spatialPose: DeviceSpatialPose,
    recenterTrigger: Int,
    focusedPoiId: String?,
    mapFocusTrigger: Int,
    onIncrementRecenter: () -> Unit,
    onSelectPoi: (PoiPinSummaryDto) -> Unit,
    onDirectionsToPin: (poiId: String, title: String, latitude: Double, longitude: Double) -> Unit,
    onSelectNavigationMode: (NavigationTravelMode) -> Unit,
    onOpenArCamera: (String) -> Unit,
    viewModel: SightseeingAppViewModel
) {
    val isEn = uiState.selectedLanguage.equals("en", ignoreCase = true)

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. MapLibre + OpenFreeMap/Bright Vector Tile Map View
        OpenFreeMapComposeView(
            spatialPose = spatialPose,
            pois = uiState.nearbyPois,
            selectedPoiId = focusedPoiId ?: uiState.selectedPoiDetail?.id,
            recenterTrigger = recenterTrigger,
            focusedPoiId = focusedPoiId,
            focusTrigger = mapFocusTrigger,
            navigationRoute = uiState.activeNavigationRoute,
            navigationTravelMode = uiState.navigationTravelMode,
            isNavigationActive = uiState.navigationTarget != null,
            navigationSessionKey = uiState.navigationTarget?.let {
                "${it.poiId}:${uiState.navigationTravelMode.apiValue}"
            },
            onPoiMarkerClick = { tappedPin ->
                onSelectPoi(tappedPin)
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Top Floating Bar (Language, Role & Auth)
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
                            text = if (uiState.currentUser != null) {
                                "${uiState.currentUser.displayName} • ${uiState.nearbyPois.size} Pins"
                            } else {
                                if (isEn) "Tap any Cultural or Food Pin within 50km" else "Chạm vào Pin Văn hóa hoặc Ăn uống trong 50km"
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
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

            // Horizontal Quick-Select Carousel of Nearby Published POI Pins within 50km
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(uiState.nearbyPois, key = { it.id }) { pin ->
                    val liveDist = LocationAndOrientationTracker.calculateDistanceMeters(
                        spatialPose.latitude,
                        spatialPose.longitude,
                        pin.latitude,
                        pin.longitude
                    )
                    val unlocked = spatialPose.hasFreshGpsFix &&
                        !spatialPose.isSimulatedLocation && spatialPose.accuracyMeters <= 50f &&
                        pin.canUnlockAr(liveDist)
                    val isFood = isFoodCategory(pin.category)
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = when {
                            unlocked -> Color(0xFF16A34A)
                            isFood -> Color(0xFFE11D48)
                            else -> Color(0xFF0F172A).copy(alpha = 0.88f)
                        },
                        shadowElevation = 3.dp,
                        modifier = Modifier.clickable {
                            onSelectPoi(pin)
                        }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Icon(
                                imageVector = when {
                                    unlocked -> Icons.Default.ViewInAr
                                    isFood -> Icons.Default.Restaurant
                                    else -> Icons.Default.Place
                                },
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${pin.title} (${formatDistanceLabel(liveDist)})",
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
                .padding(
                    end = 16.dp,
                    bottom = when {
                        uiState.navigationTarget != null -> 300.dp
                        uiState.selectedPoiDetail != null -> 340.dp
                        else -> 20.dp
                    }
                )
        ) {
            Icon(Icons.Default.MyLocation, contentDescription = "Recenter GPS")
        }

        uiState.navigationTarget?.let { target ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                shadowElevation = 12.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Directions,
                            contentDescription = null,
                            tint = Color(0xFF2563EB),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isEn) "Route to pin" else "Dẫn đường đến pin",
                                fontSize = 12.sp,
                                color = Color(0xFF64748B),
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = target.title,
                                fontSize = 16.sp,
                                color = Color(0xFF0F172A),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = viewModel::clearNavigation,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = if (isEn) "End route" else "Kết thúc dẫn đường"
                            )
                        }
                    }

                    NavigationTravelModeSelector(
                        selectedMode = uiState.navigationTravelMode,
                        isEnglish = isEn,
                        onSelect = onSelectNavigationMode
                    )

                    when {
                        uiState.isCalculatingRoute -> {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(0xFF2563EB)
                            )
                            Text(
                                text = if (isEn) "Calculating the route…" else "Đang tính tuyến đường…",
                                fontSize = 12.sp,
                                color = Color(0xFF475569)
                            )
                        }
                        uiState.navigationError != null -> {
                            Text(
                                text = uiState.navigationError,
                                fontSize = 12.sp,
                                color = Color(0xFFB91C1C)
                            )
                        }
                        uiState.activeNavigationRoute != null -> {
                            val route = uiState.activeNavigationRoute
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(20.dp)
                            ) {
                                Column {
                                    Text(
                                        if (isEn) "DISTANCE" else "QUÃNG ĐƯỜNG",
                                        fontSize = 10.sp,
                                        color = Color(0xFF64748B)
                                    )
                                    Text(
                                        formatDistanceLabel(route.distanceMeters),
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF0F172A)
                                    )
                                }
                                Column {
                                    Text(
                                        if (isEn) "EST. TIME" else "THỜI GIAN ƯỚC TÍNH",
                                        fontSize = 10.sp,
                                        color = Color(0xFF64748B)
                                    )
                                    Text(
                                        formatDurationLabel(route.durationSeconds, isEn),
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF0F172A)
                                    )
                                }
                            }
                        }
                        else -> Text(
                            text = if (isEn) "Preparing route…" else "Đang chuẩn bị tuyến…",
                            fontSize = 12.sp,
                            color = Color(0xFF475569)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = viewModel::recalculateNavigationRoute,
                            enabled = !uiState.isCalculatingRoute
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isEn) "Recalculate" else "Tính lại tuyến")
                        }
                    }
                }
            }
        }

        // 4. Selected POI Cultural Story & Community Photos Card Sheet
        if (uiState.navigationTarget == null) uiState.selectedPoiDetail?.let { detail ->
            val liveDistance = LocationAndOrientationTracker.calculateDistanceMeters(
                spatialPose.latitude,
                spatialPose.longitude,
                detail.latitude,
                detail.longitude
            )
            val isUnlocked = spatialPose.hasFreshGpsFix &&
                !spatialPose.isSimulatedLocation && spatialPose.accuracyMeters <= 50f &&
                detail.isWithinUnlockRadius(liveDistance)

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
                onDirectionsToPin = {
                    onDirectionsToPin(detail.id, detail.title, detail.latitude, detail.longitude)
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

@Composable
private fun NavigationTravelModeSelector(
    selectedMode: NavigationTravelMode,
    isEnglish: Boolean,
    onSelect: (NavigationTravelMode) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        NavigationTravelMode.entries.forEach { mode ->
            val (icon, label) = when (mode) {
                NavigationTravelMode.WALKING -> Icons.AutoMirrored.Filled.DirectionsWalk to if (isEnglish) "Walk" else "Đi bộ"
                NavigationTravelMode.CAR -> Icons.Default.DirectionsCar to if (isEnglish) "Car" else "Ô tô"
                NavigationTravelMode.MOTORCYCLE -> Icons.Default.TwoWheeler to if (isEnglish) "Motorbike" else "Xe máy"
            }
            val isSelected = selectedMode == mode
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isSelected) Color(0xFFDBEAFE) else Color(0xFFF1F5F9),
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(mode) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isSelected) Color(0xFF1D4ED8) else Color(0xFF475569),
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) Color(0xFF1D4ED8) else Color(0xFF475569),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

// ============================================================================
// TAB 4: THÔNG TIN (INFO & PROFILE TAB SCREEN)
// ============================================================================

@Composable
private fun InfoAndProfileTabScreen(
    uiState: SightseeingUiState,
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
                            .background(Color(0xFFF7EDD7), RoundedCornerShape(12.dp))
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.tem_do_gach),
                            contentDescription = "Dấu viễn du",
                            modifier = Modifier.size(38.dp)
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
                            text = "ID: ${user?.email ?: "-"}",
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

                Text(
                    text = if (isEn) "Sign in with the email and password registered on this server." else "Đăng nhập bằng email và mật khẩu đã đăng ký trên máy chủ.",
                    fontSize = 12.5.sp,
                    color = Color(0xFF64748B)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onOpenAccountModal,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.SwitchAccount, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isEn) "Enter Email & Password" else "Nhập Email và mật khẩu")
                    }

                    if (user != null) {
                        OutlinedButton(
                            onClick = onLogout,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626))
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
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
                                if (isEn) "SuperAdmin Approval & Moderation" else "Quản Trị Chủ Quản - SuperAdmin"
                        } else {
                                if (isEn) "Admin Content Management" else "Quản Trị Nội Dung - Admin"
                        },
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp,
                        color = Color(0xFF92400E)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isEn) {
                            "Create and manage Cultural & Food ('Ăn uống') pins, 360° AR cards, approve pending POIs, and moderate community photos."
                        } else {
                            "Tạo và quản lý địa điểm Văn hóa & Ăn uống (Pin), cấu hình thẻ AR 360°, phê duyệt địa điểm và kiểm duyệt ảnh người dùng."
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

        // 4. Official App Brand Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF7EDD7)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.tem_do_gach),
                        contentDescription = "Dấu Viễn Du",
                    modifier = Modifier.size(46.dp)
                )
                Column {
                    Text(
                        text = "Dấu Viễn Du",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color(0xFF622821)
                    )
                    Text(
                        text = if (isEn) {
                            "Vintage Heritage Stamp Edition • v${BuildConfig.VERSION_NAME}"
                        } else {
                            "Dấu viễn du • Phiên bản v${BuildConfig.VERSION_NAME}"
                        },
                        fontSize = 12.5.sp,
                        color = Color(0xFF622821).copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

/**
 * Interactive Bottom Sheet Card showing the POI's Cultural/Culinary Story,
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
    onDirectionsToPin: () -> Unit,
    onOpenArCamera: () -> Unit,
    onToggleLikePhoto: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isEn = selectedLanguage.equals("en", ignoreCase = true)
    val isFood = isFoodCategory(detail.category)

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
                            color = if (isFood) Color(0xFFFFE4E6) else Color(0xFFE0F2FE),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = PoiCategoryLabels.label(detail.category, selectedLanguage),
                                color = if (isFood) Color(0xFFE11D48) else Color(0xFF0369A1),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${formatDistanceLabel(liveDistanceMeters)} (AR <= ${(detail.activationRadiusMeters + 20).toInt()}m)",
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

            // Primary actions: open AR when available or request directions to the pin.
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
                            if (isEn) "AR Locked (${formatDistanceLabel(liveDistanceMeters)})" else "Chưa vào vùng AR (${formatDistanceLabel(liveDistanceMeters)})"
                        },
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onDirectionsToPin,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isEn) "Directions" else "Dẫn đường đến Pin",
                        fontSize = 11.sp
                    )
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
            HorizontalDivider()
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
 * JWT authentication dialog for server-managed user accounts.
 */
@Composable
private fun AuthAndRoleDialog(
    currentUser: UserProfileDto?,
    selectedLanguage: String,
    errorMessage: String?,
    onLogin: (String, String) -> Unit,
    onRegister: (String, String, String) -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit
) {
    val isEn = selectedLanguage.equals("en", ignoreCase = true)
    var isRegisterMode by remember { mutableStateOf(false) }
    var accountId by remember { mutableStateOf(currentUser?.email ?: "") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("Du Khách") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Image(
                    painter = painterResource(id = R.drawable.logo_doc_do_gach),
                    contentDescription = "Logo Dấu Viễn Du",
                    modifier = Modifier
                        .height(84.dp)
                        .padding(bottom = 6.dp)
                )
                Text(
                    text = if (currentUser != null) {
                        if (isEn) "Account (${currentUser.role})" else "Tài khoản (${currentUser.role})"
                    } else if (isRegisterMode) {
                        if (isEn) "Register Tourist Account" else "Đăng ký Du khách"
                    } else {
                        if (isEn) "Sign In (Dấu Viễn Du)" else "Đăng Nhập Hệ Thống"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (currentUser != null) {
                    Text("${if (isEn) "Signed in as" else "Đang đăng nhập"}: ${currentUser.displayName}")
                    Text("ID: ${currentUser.email}")
                    Text("Role: ${currentUser.role}", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (!errorMessage.isNullOrBlank()) {
                    Surface(
                        color = Color(0xFFFEE2E2),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorMessage,
                            color = Color(0xFF991B1B),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                if (isRegisterMode) {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text(if (isEn) "Display Name" else "Tên hiển thị") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = accountId,
                    onValueChange = { accountId = it },
                    label = { Text(if (isEn) "Email" else "Email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(if (isEn) "Password" else "Mật khẩu") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isRegisterMode) {
                        onRegister(accountId, password, displayName)
                    } else {
                        onLogin(accountId, password)
                    }
                }
            ) {
                Text(if (isRegisterMode) (if (isEn) "Register" else "Đăng ký") else (if (isEn) "Sign In" else "Đăng nhập"))
            }
        },
        dismissButton = {
            Row {
                if (currentUser != null) {
                    TextButton(onClick = onLogout) {
                        Text(if (isEn) "Sign Out" else "Đăng xuất", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = onDismiss) {
                        Text(if (isEn) "Close" else "Đóng")
                    }
                }
            }
        }
    )
}
