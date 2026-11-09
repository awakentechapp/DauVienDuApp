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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.awaken.sightseeingtravel.core.model.*
import com.awaken.sightseeingtravel.core.sensor.LocationAndOrientationTracker
import com.awaken.sightseeingtravel.ui.admin.AdminApprovalAndModerationScreen
import com.awaken.sightseeingtravel.ui.ar.ArCameraScreen
import com.awaken.sightseeingtravel.ui.map.OpenFreeMapComposeView

private enum class AppDestination {
    MAP_EXPLORER,
    AR_CAMERA,
    ADMIN_CONSOLE
}

/**
 * Main Entry Activity for the Sightseeing Travel AR Android Application.
 * - Integrates MapLibre + OpenFreeMap/OpenStreetMap Explorer
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

    var currentDestination by remember { mutableStateOf(AppDestination.MAP_EXPLORER) }
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
                        currentDestination = AppDestination.MAP_EXPLORER
                    }
                )
            } else {
                currentDestination = AppDestination.MAP_EXPLORER
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
                    currentDestination = AppDestination.MAP_EXPLORER
                }
            )
        }

        AppDestination.MAP_EXPLORER -> {
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
                                    text = "Sightseeing Travel AR",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 16.sp,
                                    color = Color(0xFF0F172A)
                                )
                                Text(
                                    text = if (uiState.currentUser != null) {
                                        "${uiState.currentUser?.displayName} (${uiState.currentUser?.role})"
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
                                        onClick = {
                                            viewModel.loadAdminConsoleData()
                                            currentDestination = AppDestination.ADMIN_CONSOLE
                                        },
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
                                            text = if (uiState.currentUser?.isSuperAdmin == true) "SuperAdmin" else "Admin",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                // Account / Role Sign-In Button
                                IconButton(
                                    onClick = { showAuthModal = true },
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
                        recenterTrigger++
                        viewModel.loadNearbyPois(spatialPose.latitude, spatialPose.longitude)
                    },
                    containerColor = Color.White,
                    contentColor = Color(0xFF0284C7),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 16.dp, bottom = if (uiState.selectedPoiDetail != null) 340.dp else 24.dp)
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
                            viewModel.openArExperienceForPoi(detail.id) {
                                currentDestination = AppDestination.AR_CAMERA
                            }
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
                },
                fontWeight = FontWeight.Bold
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
