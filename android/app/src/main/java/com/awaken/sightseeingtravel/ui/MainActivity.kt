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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
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
import com.awaken.sightseeingtravel.ui.admin.SuperAdminAccountManagementScreen
import com.awaken.sightseeingtravel.ui.admin.ArModelLibraryDialog
import com.awaken.sightseeingtravel.ui.admin.ArModelCreationDialog
import com.awaken.sightseeingtravel.ui.ar.ArCameraScreen
import com.awaken.sightseeingtravel.ui.ar.ArSelfieCaptureScreen
import com.awaken.sightseeingtravel.ui.map.OpenFreeMapComposeView

private enum class AppDestination {
    MAIN_TABS,
    AR_CAMERA,
    AR_SELFIE_CAMERA,
    ADMIN_CONSOLE,
    ACCOUNT_MANAGEMENT,
    CREATE_AR_MODEL
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
                colorScheme = SightseeingColorScheme
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

private fun formatPinCount(count: Int, isEnglish: Boolean): String =
    if (isEnglish) "$count ${if (count == 1) "Pin" else "Pins"}" else "$count Pin"

private fun formatArCardCount(count: Int, isEnglish: Boolean): String =
    if (isEnglish) "$count ${if (count == 1) "AR Card" else "AR Cards"}" else "$count Thẻ AR"

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
    var accountManagementInitialRole by remember { mutableStateOf("Admin") }
    var selectedTab by remember { mutableStateOf(MainBottomTab.HOME) }
    var initialPlacesCategory by remember { mutableStateOf("ALL") }
    var recenterTrigger by remember { mutableIntStateOf(0) }
    var focusedPoiId by remember { mutableStateOf<String?>(null) }
    var mapFocusTrigger by remember { mutableIntStateOf(0) }
    var showAuthModal by remember { mutableStateOf(false) }
    var pendingCameraChoicePoiId by remember { mutableStateOf<String?>(null) }
    var selfieReturnDestination by remember { mutableStateOf(AppDestination.MAIN_TABS) }

    val isEn = uiState.selectedLanguage.equals("en", ignoreCase = true)
    val selectPoiForMap: (PoiPinSummaryDto) -> Unit = { pin ->
        viewModel.clearNavigation()
        focusedPoiId = pin.id
        mapFocusTrigger++
        viewModel.selectPoiPin(pin.id, spatialPose.latitude, spatialPose.longitude)
    }
    val requestCameraChoice: (String) -> Unit = { poiId ->
        pendingCameraChoicePoiId = poiId
    }
    val launchArMode: (String, AppDestination) -> Unit = { poiId, destination ->
        pendingCameraChoicePoiId = null
        if (destination == AppDestination.AR_SELFIE_CAMERA) {
            selfieReturnDestination = AppDestination.MAIN_TABS
        }
        viewModel.openArExperienceForPoi(poiId) {
            currentDestination = destination
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.startSensors()
    }

    LaunchedEffect(uiState.currentUser?.id) {
        if (uiState.currentUser != null) {
            selectedTab = MainBottomTab.HOME
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
            noticeMessage = uiState.statusBannerMessage,
            onLanguageChange = viewModel::setLanguage,
            onClearError = viewModel::clearBannerMessages,
            onLogin = { email, password -> viewModel.login(email, password) },
            onRegister = { email, password, name ->
                viewModel.register(email, password, name, uiState.selectedLanguage)
            },
            onRequestPasswordReset = viewModel::requestPasswordReset,
            onConfirmPasswordReset = { email, code, password, onSuccess ->
                viewModel.confirmPasswordReset(email, code, password, onSuccess)
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
                    selectedLanguage = uiState.selectedLanguage,
                    onOpenSelfieCamera = {
                        selfieReturnDestination = AppDestination.AR_CAMERA
                        currentDestination = AppDestination.AR_SELFIE_CAMERA
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

        AppDestination.AR_SELFIE_CAMERA -> {
            val arCtx = uiState.activeArContext
            if (arCtx != null) {
                ArSelfieCaptureScreen(
                    arContext = arCtx,
                    spatialPose = spatialPose,
                    isUploadingPhoto = uiState.isUploadingPhoto,
                    uploadErrorMessage = uiState.errorMessage,
                    selectedLanguage = uiState.selectedLanguage,
                    onCaptureAndUploadPhoto = { file, caption, anchor, onSuccess ->
                        viewModel.uploadCapturedPhoto(arCtx.poiId, file, caption, anchor, onSuccess)
                    },
                    onClose = { currentDestination = selfieReturnDestination }
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
                onBuildArModel = viewModel::buildAdminArModel,
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

        AppDestination.ACCOUNT_MANAGEMENT -> {
            SuperAdminAccountManagementScreen(
                currentUser = uiState.currentUser,
                selectedLanguage = uiState.selectedLanguage,
                initialRole = accountManagementInitialRole,
                onLoadUsers = viewModel::loadManagedAccounts,
                onCreateUser = viewModel::createManagedAccount,
                onSetUserActive = viewModel::setManagedAccountActive,
                onBack = { currentDestination = AppDestination.MAIN_TABS }
            )
        }

        AppDestination.CREATE_AR_MODEL -> {
            ArModelCreationDialog(
                onBuild = viewModel::buildAdminArModel,
                onDismiss = { currentDestination = AppDestination.MAIN_TABS }
            )
        }

        AppDestination.MAIN_TABS -> {
            Scaffold(
                bottomBar = {
                    NavigationBar(
                        containerColor = BrandColors.Paper,
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
                                    selectedIconColor = BrandColors.Primary,
                                    selectedTextColor = BrandColors.Primary,
                                    indicatorColor = BrandColors.Cream,
                                    unselectedIconColor = BrandColors.MutedText,
                                    unselectedTextColor = BrandColors.MutedText
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
                                onOpenArForPin = { pin -> requestCameraChoice(pin.id) }
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
                                onOpenArForPin = { pin -> requestCameraChoice(pin.id) },
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
                                onOpenArCamera = requestCameraChoice,
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
                                onOpenArModelCreation = {
                                    currentDestination = AppDestination.CREATE_AR_MODEL
                                },
                                onOpenAccountManagement = { role ->
                                    accountManagementInitialRole = role
                                    currentDestination = AppDestination.ACCOUNT_MANAGEMENT
                                },
                                onLoadArModelLibrary = viewModel::loadAdminArModelLibrary,
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

    pendingCameraChoicePoiId?.let { poiId ->
        val cameraPoiLocation = uiState.selectedPoiDetail?.takeIf { it.id == poiId }?.let {
            Triple(it.latitude, it.longitude, it.activationRadiusMeters)
        } ?: uiState.nearbyPois.firstOrNull { it.id == poiId }?.let {
            Triple(it.latitude, it.longitude, it.activationRadiusMeters)
        }
        val distanceMeters = cameraPoiLocation?.let {
            LocationAndOrientationTracker.calculateDistanceMeters(
                spatialPose.latitude,
                spatialPose.longitude,
                it.first,
                it.second
            )
        }
        val hasAccurateLiveGps = spatialPose.hasFreshGpsFix &&
            !spatialPose.isSimulatedLocation && spatialPose.accuracyMeters <= 50f
        val isCameraAvailableAtPoi = cameraPoiLocation != null && hasAccurateLiveGps &&
            distanceMeters != null && distanceMeters <= cameraPoiLocation.third + 20.0
        val cameraAvailabilityMessage = when {
            cameraPoiLocation == null -> if (isEn) "Could not load this place." else "Không tải được thông tin địa điểm."
            !spatialPose.hasFreshGpsFix || spatialPose.isSimulatedLocation -> if (isEn) {
                "Turn on live GPS to use either camera mode. Emulator locations do not unlock AR."
            } else {
                "Cần bật GPS thực để dùng camera. Vị trí mô phỏng trên emulator không mở khóa AR."
            }
            spatialPose.accuracyMeters > 50f -> if (isEn) {
                "GPS accuracy is too low (${spatialPose.accuracyMeters.toInt()}m). Wait for a more accurate fix."
            } else {
                "GPS chưa đủ chính xác (${spatialPose.accuracyMeters.toInt()}m). Hãy chờ tín hiệu tốt hơn."
            }
            distanceMeters != null && distanceMeters > cameraPoiLocation.third + 20.0 -> if (isEn) {
                "You are ${formatDistanceLabel(distanceMeters)} away. Move within ${(cameraPoiLocation.third + 20).toInt()}m to open the camera."
            } else {
                "Bạn đang cách ${formatDistanceLabel(distanceMeters)}. Hãy đến trong ${(cameraPoiLocation.third + 20).toInt()}m để mở camera."
            }
            else -> if (isEn) "You are in the AR zone. Choose a camera mode." else "Bạn đã vào vùng AR. Hãy chọn chế độ camera."
        }
        ArCameraModeDialog(
            isEnglish = isEn,
            canOpenCamera = isCameraAvailableAtPoi,
            availabilityMessage = cameraAvailabilityMessage,
            onViewAr = { launchArMode(poiId, AppDestination.AR_CAMERA) },
            onTakeSelfie = { launchArMode(poiId, AppDestination.AR_SELFIE_CAMERA) },
            onDismiss = { pendingCameraChoicePoiId = null }
        )
    }
}

@Composable
private fun ArCameraModeDialog(
    isEnglish: Boolean,
    canOpenCamera: Boolean,
    availabilityMessage: String,
    onViewAr: () -> Unit,
    onTakeSelfie: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (isEnglish) "Choose camera mode" else "Chọn chế độ camera")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (isEnglish) {
                        "View the AR cards and 3D objects, or open the front camera to take a selfie at this point."
                    } else {
                        "Xem thẻ AR và vật thể 3D, hoặc mở camera trước để chụp selfie tại điểm này."
                    },
                    color = BrandColors.MutedText
                )
                Surface(
                    color = if (canOpenCamera) BrandColors.Cream else BrandColors.Cream,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = availabilityMessage,
                        color = if (canOpenCamera) BrandColors.Primary else BrandColors.Primary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Button(
                    onClick = onViewAr,
                    enabled = canOpenCamera,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ViewInAr, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isEnglish) "Open camera to view AR" else "Mở camera để xem AR")
                }
                OutlinedButton(
                    onClick = onTakeSelfie,
                    enabled = canOpenCamera,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isEnglish) "Open camera to take selfie" else "Mở camera để chụp selfie")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isEnglish) "Cancel" else "Đóng")
            }
        }
    )
}

// ============================================================================
// TAB 1: TRANG CHỦ (HOME TAB SCREEN)
// ============================================================================

@Composable
private fun HomeTabScreen(
    uiState: SightseeingUiState,
    spatialPose: DeviceSpatialPose,
    onToggleLanguage: () -> Unit,
    onOpenAdminConsole: () -> Unit,
    onNavigateToExploreMap: (String?) -> Unit,
    onNavigateToPlacesTab: (String) -> Unit,
    onOpenArForPin: (PoiPinSummaryDto) -> Unit
) {
    val isEn = uiState.selectedLanguage.equals("en", ignoreCase = true)
    val culturalPois = uiState.nearbyPois.filterNot { isFoodCategory(it.category) }
    val foodPois = uiState.nearbyPois.filter { isFoodCategory(it.category) }
    val featuredPoi = culturalPois.firstOrNull() ?: uiState.nearbyPois.firstOrNull()
    val featuredDistance = featuredPoi?.let {
        LocationAndOrientationTracker.calculateDistanceMeters(
            spatialPose.latitude,
            spatialPose.longitude,
            it.latitude,
            it.longitude
        )
    }
    val featuredUnlocked = featuredPoi != null && featuredDistance != null &&
        spatialPose.hasFreshGpsFix && !spatialPose.isSimulatedLocation &&
        spatialPose.accuracyMeters <= 50f && featuredPoi.canUnlockAr(featuredDistance)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandColors.Paper)
            .verticalScroll(rememberScrollState())
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BrandColors.Paper)
                .statusBarsPadding()
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = R.drawable.logo_ngang_do_gach),
                    contentDescription = "Dấu Viễn Du",
                    modifier = Modifier.height(38.dp).wrapContentWidth(Alignment.Start)
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = onToggleLanguage,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = BrandColors.Cream,
                            contentColor = BrandColors.Primary
                        )
                    ) {
                        Text(if (isEn) "EN" else "VI", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = if (isEn) "CULTURAL DISCOVERY" else "KHÁM PHÁ GẦN BẠN",
                color = BrandColors.Terracotta,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp
            )
            Text(
                text = if (isEn) "Vietnam's\nCultural Heritage" else "Di sản\nViệt Nam",
                color = BrandColors.Primary,
                fontSize = 34.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (uiState.currentUser != null) {
                    if (isEn) "A story is waiting nearby, ${uiState.currentUser.displayName}." else "Một câu chuyện đang chờ bạn khám phá, ${uiState.currentUser.displayName}."
                } else {
                    if (isEn) "Places rich in character, stories that stay with you." else "Những điểm đến giàu bản sắc, những câu chuyện còn mãi."
                },
                color = BrandColors.MutedText,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }

        if (featuredPoi != null) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = BrandColors.Surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column {
                    Box(modifier = Modifier.fillMaxWidth().height(224.dp)) {
                        AsyncImage(
                            model = featuredPoi.coverImageUrl,
                            contentDescription = featuredPoi.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Surface(
                            color = BrandColors.Primary.copy(alpha = 0.92f),
                            shape = RoundedCornerShape(bottomEnd = 14.dp),
                            modifier = Modifier.align(Alignment.TopStart)
                        ) {
                            Text(
                                text = if (isEn) "NEAR YOU" else "GẦN BẠN",
                                color = BrandColors.Cream,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }

                    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = PoiCategoryLabels.label(featuredPoi.category, if (isEn) "en" else "vi").uppercase(),
                                color = BrandColors.Terracotta,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp,
                                modifier = Modifier.weight(1f)
                            )
                            if (featuredDistance != null) {
                                Text(formatDistanceLabel(featuredDistance), color = BrandColors.MutedText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = featuredPoi.title,
                            color = BrandColors.PrimaryDeep,
                            fontSize = 26.sp,
                            lineHeight = 30.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Serif
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = featuredPoi.shortSummary,
                            color = BrandColors.MutedText,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (featuredUnlocked) {
                            Text(
                                text = if (isEn) "AR experience available here" else "Có trải nghiệm AR tại điểm đến",
                                color = BrandColors.Terracotta,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 10.dp)
                            )
                        }
                        Button(
                            onClick = { onNavigateToExploreMap(featuredPoi.id) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BrandColors.Primary,
                                contentColor = BrandColors.Paper
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
                        ) {
                            Text(if (isEn) "Explore this story" else "Khám phá câu chuyện", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = BrandColors.Cream,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationSearching, contentDescription = null, tint = BrandColors.Primary)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (isEn) "Nearby cultural places will appear here." else "Các địa điểm văn hóa quanh bạn sẽ xuất hiện tại đây.",
                        color = BrandColors.PrimaryDeep,
                        fontSize = 13.sp
                    )
                }
            }
        }

        // Admin / SuperAdmin Quick Governance Banner (if applicable)
        if (uiState.currentUser?.isAdminOrSuperAdmin == true) {
            Card(
                colors = CardDefaults.cardColors(containerColor = BrandColors.Cream),
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
                            color = BrandColors.Primary
                        )
                        Text(
                            text = if (isEn) "Tap to manage map pins, AR cards, and community photos" else "Chạm để quản lý địa điểm (Pin), thẻ AR và duyệt ảnh cộng đồng",
                            fontSize = 12.sp,
                            color = BrandColors.PrimaryDeep
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = null,
                        tint = BrandColors.Primary,
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
                color = BrandColors.Text
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
                color = BrandColors.Text
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
        colors = CardDefaults.cardColors(containerColor = BrandColors.Surface),
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
                    color = if (isUnlocked) BrandColors.Terracotta else if (isFood) BrandColors.Terracotta else BrandColors.PrimaryDeep.copy(alpha = 0.82f),
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
                    color = if (isFood) BrandColors.Terracotta else BrandColors.Primary,
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
                    color = BrandColors.PrimaryDeep
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = pin.shortSummary,
                    fontSize = 12.5.sp,
                    color = BrandColors.MutedText,
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
                        colors = ButtonDefaults.buttonColors(containerColor = BrandColors.Terracotta),
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
        colors = CardDefaults.cardColors(containerColor = BrandColors.Surface),
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
                color = BrandColors.PrimaryDeep
            )
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.5.sp,
                color = BrandColors.PrimaryDeep,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = BrandColors.MutedText,
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
            .background(BrandColors.Paper)
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
                            color = BrandColors.PrimaryDeep
                        )
                        Text(
                            text = if (isEn) {
                                "Showing ${filteredPois.size} places near you"
                            } else {
                                "Hiển thị ${filteredPois.size} điểm gần bạn"
                            },
                            fontSize = 11.5.sp,
                            color = BrandColors.Primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    IconButton(onClick = onRefreshPois) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = BrandColors.Primary
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
                    colors = CardDefaults.cardColors(containerColor = BrandColors.Surface),
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
                                color = if (isFood) BrandColors.Terracotta else BrandColors.PrimaryDeep.copy(alpha = 0.82f),
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
                                color = if (unlocked) BrandColors.Terracotta else BrandColors.Terracotta,
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
                                color = BrandColors.PrimaryDeep
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = pin.shortSummary,
                                fontSize = 13.5.sp,
                                color = BrandColors.MutedText
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = formatArCardCount(pin.arInfoCardCount, isEn),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BrandColors.Primary
                                )
                                Text(
                                    text = if (isEn) "Photos: ${pin.communityPhotoCount}" else "Ảnh cộng đồng: ${pin.communityPhotoCount}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BrandColors.Terracotta
                                )
                                Text(
                                    text = if (isEn) "Radius: ${pin.activationRadiusMeters.toInt()}m" else "Bán kính AR: ${pin.activationRadiusMeters.toInt()}m",
                                    fontSize = 12.sp,
                                    color = BrandColors.MutedText
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
                                        colors = ButtonDefaults.buttonColors(containerColor = BrandColors.Terracotta),
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
    var searchQuery by remember { mutableStateOf("") }
    var hasAutoSelectedNearbyPoi by remember { mutableStateOf(false) }
    val visiblePois = uiState.nearbyPois.filter { poi ->
        searchQuery.isBlank() || poi.title.contains(searchQuery, ignoreCase = true) ||
            PoiCategoryLabels.label(poi.category, uiState.selectedLanguage).contains(searchQuery, ignoreCase = true)
    }

    LaunchedEffect(uiState.nearbyPois.firstOrNull()?.id) {
        if (!hasAutoSelectedNearbyPoi && uiState.selectedPoiDetail == null) {
            val firstNearbyPoi = uiState.nearbyPois.minByOrNull { poi ->
                LocationAndOrientationTracker.calculateDistanceMeters(
                    spatialPose.latitude,
                    spatialPose.longitude,
                    poi.latitude,
                    poi.longitude
                )
            }
            if (firstNearbyPoi != null) {
                hasAutoSelectedNearbyPoi = true
                onSelectPoi(firstNearbyPoi)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. MapLibre + OpenFreeMap/Bright Vector Tile Map View
        OpenFreeMapComposeView(
            spatialPose = spatialPose,
            pois = visiblePois,
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

        // 2. Map-first search and language controls.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = BrandColors.Paper.copy(alpha = 0.97f),
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.tem_do_gach),
                            contentDescription = "Dấu Viễn Du",
                            modifier = Modifier.size(34.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Dấu Viễn Du", color = BrandColors.Primary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(
                                if (isEn) "${visiblePois.size} nearby places" else "${visiblePois.size} địa điểm quanh bạn",
                                color = BrandColors.MutedText,
                                fontSize = 11.sp
                            )
                        }
                        TextButton(
                            onClick = { viewModel.setLanguage(if (isEn) "vi" else "en") },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                        ) {
                            Text(if (isEn) "EN" else "VI", color = BrandColors.Primary, fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(if (isEn) "Search places in this area" else "Tìm địa điểm trong khu vực") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = BrandColors.Primary) },
                        trailingIcon = if (searchQuery.isNotEmpty()) {
                            {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = if (isEn) "Clear search" else "Xóa tìm kiếm")
                                }
                            }
                        } else null,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BrandColors.Primary,
                            unfocusedBorderColor = BrandColors.Border,
                            focusedContainerColor = BrandColors.Surface,
                            unfocusedContainerColor = BrandColors.Surface,
                            cursorColor = BrandColors.Primary
                        )
                    )
                }
            }

            // Status or Error Banner
            val bannerText = uiState.errorMessage ?: uiState.statusBannerMessage
            if (!bannerText.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = if (uiState.errorMessage != null) BrandColors.Error.copy(alpha = 0.12f) else BrandColors.Cream,
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
                            color = if (uiState.errorMessage != null) BrandColors.Error else BrandColors.Primary,
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

        }

        // 3. Recenter GPS FAB
        FloatingActionButton(
            onClick = {
                onIncrementRecenter()
                viewModel.loadNearbyPois(spatialPose.latitude, spatialPose.longitude)
            },
            containerColor = Color.White,
            contentColor = BrandColors.Primary,
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
                            tint = BrandColors.Terracotta,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isEn) "Route to pin" else "Dẫn đường đến pin",
                                fontSize = 12.sp,
                                color = BrandColors.MutedText,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = target.title,
                                fontSize = 16.sp,
                                color = BrandColors.PrimaryDeep,
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
                                color = BrandColors.Terracotta
                            )
                            Text(
                                text = if (isEn) "Calculating the route…" else "Đang tính tuyến đường…",
                                fontSize = 12.sp,
                                color = BrandColors.MutedText
                            )
                        }
                        uiState.navigationError != null -> {
                            Text(
                                text = uiState.navigationError,
                                fontSize = 12.sp,
                                color = BrandColors.Error
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
                                        color = BrandColors.MutedText
                                    )
                                    Text(
                                        formatDistanceLabel(route.distanceMeters),
                                        fontWeight = FontWeight.Bold,
                                        color = BrandColors.PrimaryDeep
                                    )
                                }
                                Column {
                                    Text(
                                        if (isEn) "EST. TIME" else "THỜI GIAN ƯỚC TÍNH",
                                        fontSize = 10.sp,
                                        color = BrandColors.MutedText
                                    )
                                    Text(
                                        formatDurationLabel(route.durationSeconds, isEn),
                                        fontWeight = FontWeight.Bold,
                                        color = BrandColors.PrimaryDeep
                                    )
                                }
                            }
                        }
                        else -> Text(
                            text = if (isEn) "Preparing route…" else "Đang chuẩn bị tuyến…",
                            fontSize = 12.sp,
                            color = BrandColors.MutedText
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
                color = if (isSelected) BrandColors.Cream else BrandColors.Sand,
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
                        tint = if (isSelected) BrandColors.Primary else BrandColors.MutedText,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) BrandColors.Primary else BrandColors.MutedText,
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
    onOpenArModelCreation: () -> Unit,
    onOpenAccountManagement: (String) -> Unit,
    onLoadArModelLibrary: suspend () -> List<ArModelLibraryItemDto>,
    onLogout: () -> Unit
) {
    val isEn = uiState.selectedLanguage.equals("en", ignoreCase = true)
    val user = uiState.currentUser
    var showArModelLibrary by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandColors.Paper)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = if (isEn) "Information & Account" else "Thông Tin & Tài Khoản",
            fontWeight = FontWeight.ExtraBold,
            fontSize = 22.sp,
            color = BrandColors.PrimaryDeep
        )

        // 1. User Profile & Role Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = BrandColors.Surface),
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
                            .background(BrandColors.Sand, RoundedCornerShape(12.dp))
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
                            color = BrandColors.PrimaryDeep
                        )
                        Text(
                            text = "ID: ${user?.email ?: "-"}",
                            fontSize = 13.sp,
                            color = BrandColors.MutedText
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            color = BrandColors.Cream,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Role: ${user?.role ?: "Tourist"}",
                                color = BrandColors.Terracotta,
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
                    color = BrandColors.MutedText
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
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandColors.Error)
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
                colors = CardDefaults.cardColors(containerColor = BrandColors.Cream),
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
                        color = BrandColors.Primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isEn) {
                            "Create and manage Cultural & Food ('Ăn uống') pins, 360° AR cards, approve pending POIs, and moderate community photos."
                        } else {
                            "Tạo và quản lý địa điểm Văn hóa & Ăn uống (Pin), cấu hình thẻ AR 360°, phê duyệt địa điểm và kiểm duyệt ảnh người dùng."
                        },
                        fontSize = 12.5.sp,
                        color = BrandColors.Terracotta
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onOpenAdminConsole,
                        colors = ButtonDefaults.buttonColors(containerColor = BrandColors.Terracotta)
                    ) {
                        Icon(Icons.Default.AdminPanelSettings, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isEn) "Open Governance Console" else "Mở Bảng Quản Trị & Kiểm Duyệt")
                    }
                }
            }

            if (user.isSuperAdmin) {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = BrandColors.Surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = if (isEn) "Account management" else "Quản lý tài khoản",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp,
                            color = BrandColors.PrimaryDeep
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { onOpenAccountManagement("Admin") },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.AdminPanelSettings, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(if (isEn) "Admins" else "Tài khoản Admin", maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = { onOpenAccountManagement("Tourist") },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.People, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(if (isEn) "Tourists" else "Tài khoản Tourist", maxLines = 1)
                            }
                        }
                    }
                }
            }

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = BrandColors.Surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (isEn) "AR 3D Objects" else "Vật Thể AR 3D",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp,
                        color = BrandColors.PrimaryDeep
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isEn) {
                            "Create a reusable 3D object here first, then choose it when placing an object into a POI's AR scene."
                        } else {
                            "Tạo vật thể 3D độc lập tại đây trước, sau đó chọn vật thể trong kho khi đặt vào AR của POI."
                        },
                        fontSize = 12.5.sp,
                        color = BrandColors.MutedText
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onOpenArModelCreation,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = BrandColors.Terracotta)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(if (isEn) "Create 3D" else "Tạo vật thể 3D", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { showArModelLibrary = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ViewInAr, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(if (isEn) "3D Library" else "Kho vật thể 3D", maxLines = 1)
                        }
                    }
                }
            }
        }

        // 3. Language Preferences Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = BrandColors.Surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (isEn) "Cultural Guide Language" else "Ngôn Ngữ Thuyết Minh Văn Hóa",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = BrandColors.PrimaryDeep
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
            colors = CardDefaults.cardColors(containerColor = BrandColors.Sand),
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

    if (showArModelLibrary && user?.isAdminOrSuperAdmin == true) {
        ArModelLibraryDialog(
            isEnglish = isEn,
            onLoadLibrary = onLoadArModelLibrary,
            onDismiss = { showArModelLibrary = false }
        )
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
        color = BrandColors.Surface,
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
                            color = if (isFood) BrandColors.Sand else BrandColors.Cream,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = PoiCategoryLabels.label(detail.category, selectedLanguage),
                                color = if (isFood) BrandColors.Terracotta else BrandColors.Primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${formatDistanceLabel(liveDistanceMeters)} (AR <= ${(detail.activationRadiusMeters + 20).toInt()}m)",
                            fontSize = 11.5.sp,
                            color = if (isArUnlocked) BrandColors.Terracotta else BrandColors.MutedText,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = detail.title,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 19.sp,
                        color = BrandColors.Text
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
                        containerColor = if (isArUnlocked) BrandColors.Terracotta else BrandColors.Primary
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
                color = BrandColors.Text
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = detail.culturalStoryMarkdown,
                fontSize = 13.sp,
                color = BrandColors.MutedText
            )

            if (detail.architecturalHighlights.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(detail.architecturalHighlights) { highlight ->
                        Surface(
                            color = BrandColors.Sand,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = "• $highlight",
                                fontSize = 11.5.sp,
                                color = BrandColors.PrimaryDeep,
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

            when {
                communityPhotos.isEmpty() -> Text(
                    text = if (isEn) "No community photos shared here yet." else "Chưa có ảnh cộng đồng được chia sẻ tại đây.",
                    color = BrandColors.MutedText,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
                communityPhotos.size == 1 -> CommunityPhotoTile(
                    photo = communityPhotos.first(),
                    isEnglish = isEn,
                    onToggleLikePhoto = onToggleLikePhoto,
                    modifier = Modifier.fillMaxWidth().height(198.dp)
                )
                else -> Row(
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    CommunityPhotoTile(
                        photo = communityPhotos[0],
                        isEnglish = isEn,
                        onToggleLikePhoto = onToggleLikePhoto,
                        modifier = Modifier.weight(1.15f).fillMaxHeight()
                    )
                    Column(
                        modifier = Modifier.weight(0.85f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        communityPhotos.drop(1).take(2).forEach { photo ->
                            CommunityPhotoTile(
                                photo = photo,
                                isEnglish = isEn,
                                onToggleLikePhoto = onToggleLikePhoto,
                                modifier = Modifier.weight(1f).fillMaxWidth()
                            )
                        }
                    }
                }
            }

            if (communityPhotos.size > 3) {
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    items(communityPhotos.drop(3), key = { it.id }) { photo ->
                        CommunityPhotoTile(
                            photo = photo,
                            isEnglish = isEn,
                            onToggleLikePhoto = onToggleLikePhoto,
                            modifier = Modifier.width(178.dp).height(132.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommunityPhotoTile(
    photo: CommunityPhotoDto,
    isEnglish: Boolean,
    onToggleLikePhoto: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.clip(RoundedCornerShape(16.dp))) {
        AsyncImage(
            model = photo.imageUrl,
            contentDescription = photo.caption ?: photo.authorDisplayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(76.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, BrandColors.PrimaryDeep.copy(alpha = 0.9f))
                    )
                )
        )
        Surface(
            color = BrandColors.PrimaryDeep.copy(alpha = 0.9f),
            shape = RoundedCornerShape(50),
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).clickable { onToggleLikePhoto(photo.id) }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (photo.isLikedByCurrentUser) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isEnglish) "Like photo" else "Thích ảnh",
                    tint = BrandColors.Cream,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("${photo.likeCount}", color = BrandColors.Paper, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp)
        ) {
            Text(
                text = photo.caption?.takeIf { it.isNotBlank() } ?: photo.authorDisplayName,
                color = BrandColors.Paper,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (isEnglish) "by ${photo.authorDisplayName}" else "Ảnh của ${photo.authorDisplayName}",
                color = BrandColors.Cream,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
                        color = BrandColors.Error.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorMessage,
                            color = BrandColors.Error,
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
