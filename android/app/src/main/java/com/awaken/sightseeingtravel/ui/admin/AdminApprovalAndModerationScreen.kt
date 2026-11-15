package com.awaken.sightseeingtravel.ui.admin

import android.Manifest
import android.graphics.Bitmap
import android.net.Uri
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import coil.compose.AsyncImage
import com.awaken.sightseeingtravel.core.model.*
import com.awaken.sightseeingtravel.core.sensor.LocationAndOrientationTracker
import com.awaken.sightseeingtravel.ui.BrandColors
import com.awaken.sightseeingtravel.ui.map.PoiCoordinatePickerDialog
import com.awaken.sightseeingtravel.ui.ar.ArAnchorPlacementDialog
import com.awaken.sightseeingtravel.ui.ar.createArCardPlacementBitmap
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import java.io.File

private const val UNCLASSIFIED_POI_AREA_FILTER = "__unclassified__"

/**
 * Role-Aware Admin & SuperAdmin Management Console:
 * - `Admin`: Can create/edit POIs + multilingual stories + AR Info Cards (status = `PendingApproval`)
 *   and moderate reported community photos (`Keep` / `Remove`).
 * - `SuperAdmin`: Has ALL `Admin` permissions PLUS exclusive authority to **Approve (`Published`)**
 *   or **Reject (with mandatory rejection reason)** POIs in `PendingApproval` status.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminApprovalAndModerationScreen(
    currentUser: UserProfileDto?,
    adminPois: List<AdminPoiDto>,
    reportedPhotos: List<ModerationPhotoItemDto>,
    selectedStatusFilter: String?,
    selectedLanguage: String,
    defaultLatitude: Double,
    defaultLongitude: Double,
    hasFreshGpsFix: Boolean,
    gpsAccuracyMeters: Float,
    currentAzimuthDegrees: Float,
    adminArCardsByPoi: Map<String, List<AdminArInfoCardDto>>,
    onCreateFixedArCard: suspend (String, UpsertArInfoCardRequest) -> String?,
    onLoadArModelLibrary: suspend () -> List<ArModelLibraryItemDto>,
    onPlaceExistingArModel: suspend (String, PlaceArModelRequest) -> String?,
    onUpdateArInfoCard: suspend (String, UpsertArInfoCardRequest) -> String?,
    onDeleteArInfoCard: suspend (String) -> String?,
    onBuildArModel: suspend (List<File>, String, String) -> String?,
    onFilterStatusChange: (String?) -> Unit,
    onCreatePoiSubmit: (CreateOrUpdatePoiRequest, Uri?) -> Unit,
    onUpdatePoiSubmit: (String, CreateOrUpdatePoiRequest, Uri?) -> Unit,
    onDeletePoi: (String) -> Unit,
    onSuperAdminApprovePoi: (String, String) -> Unit,
    onSuperAdminRejectPoi: (String, String) -> Unit,
    onModeratePhotoDecision: (String, String, String?) -> Unit,
    onBackToMap: () -> Unit
) {
    val isSuperAdmin = currentUser?.isSuperAdmin == true
    var activeTabIndex by remember { mutableIntStateOf(0) }
    var showCreatePoiDialog by remember { mutableStateOf(false) }
    var poiForEdit by remember { mutableStateOf<AdminPoiDto?>(null) }
    var poiForFixedArCard by remember { mutableStateOf<AdminPoiDto?>(null) }
    var poiForModelPlacement by remember { mutableStateOf<AdminPoiDto?>(null) }
    var poiWaitingForModelCameraPermission by remember { mutableStateOf<AdminPoiDto?>(null) }
    var modelPlacementPermissionError by remember { mutableStateOf(false) }
    var arCardForEdit by remember { mutableStateOf<AdminArInfoCardDto?>(null) }
    var poiIdPendingRejection by remember { mutableStateOf<AdminPoiDto?>(null) }
    var poiPendingDeletion by remember { mutableStateOf<AdminPoiDto?>(null) }
    var arCardPendingDeletion by remember { mutableStateOf<AdminArInfoCardDto?>(null) }
    var arCardDeleteError by remember { mutableStateOf<String?>(null) }
    var isDeletingArCard by remember { mutableStateOf(false) }
    var showArModelCreationDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var rejectionReasonInput by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
        if (granted) {
            val placementPoi = poiWaitingForModelCameraPermission
            if (placementPoi != null) {
                poiWaitingForModelCameraPermission = null
                poiForModelPlacement = placementPoi
            }
        } else {
            if (poiWaitingForModelCameraPermission != null) {
                poiWaitingForModelCameraPermission = null
                modelPlacementPermissionError = true
            }
        }
    }

    val startModelPlacement: (AdminPoiDto) -> Unit = { poi ->
        if (hasCameraPermission) {
            poiForModelPlacement = poi
        } else {
            poiWaitingForModelCameraPermission = poi
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (isSuperAdmin) "SuperAdmin Governance Console" else "Admin Content Console",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = "${currentUser?.displayName ?: "Staff"} • Role: ${currentUser?.role ?: "Admin"}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackToMap) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back to Map")
                    }
                },
                actions = {
                    OutlinedButton(
                        onClick = { showArModelCreationDialog = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.ViewInAr, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Tạo vật thể 3D", fontSize = 12.sp, maxLines = 1)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Button(
                        onClick = { showCreatePoiDialog = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.AddLocationAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("New POI", fontSize = 13.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(selectedTabIndex = activeTabIndex) {
                Tab(
                    selected = activeTabIndex == 0,
                    onClick = { activeTabIndex = 0 },
                    text = {
                        val pendingCount = adminPois.count { it.parsedStatus == PoiStatus.PENDING_APPROVAL }
                        Text("POI Approval Queue ($pendingCount Pending)")
                    }
                )
                Tab(
                    selected = activeTabIndex == 1,
                    onClick = { activeTabIndex = 1 },
                    text = {
                        Text("Photo Moderation (${reportedPhotos.size})")
                    }
                )
            }

            if (activeTabIndex == 0) {
                PoiApprovalWorkflowTab(
                    isSuperAdmin = isSuperAdmin,
                    adminPois = adminPois,
                    selectedStatusFilter = selectedStatusFilter,
                    selectedLanguage = selectedLanguage,
                    adminArCardsByPoi = adminArCardsByPoi,
                    onFilterStatusChange = onFilterStatusChange,
                    onApproveClick = { poi ->
                        onSuperAdminApprovePoi(poi.id, "Verified & approved by SuperAdmin")
                    },
                    onRejectClick = { poi ->
                        poiIdPendingRejection = poi
                        rejectionReasonInput = ""
                    },
                    onCreateFixedArCard = { poi -> poiForFixedArCard = poi },
                    onPlace3dModel = startModelPlacement,
                    onEditPoi = { poi -> poiForEdit = poi },
                    onEditArCard = { card -> arCardForEdit = card },
                    onDeletePoi = { poi -> poiPendingDeletion = poi },
                    onDeleteArCard = { card ->
                        arCardDeleteError = null
                        arCardPendingDeletion = card
                    }
                )
            } else {
                ReportedPhotoModerationTab(
                    reportedPhotos = reportedPhotos,
                    onDecision = onModeratePhotoDecision
                )
            }
        }
    }

    // ========================================================================
    // DIALOG 1: CREATE NEW POI WITH MULTILINGUAL STORY & SPATIAL AR CARD
    // ========================================================================
    if (showCreatePoiDialog) {
        CreatePoiDialog(
            knownPoiAreas = adminPois,
            selectedLanguage = selectedLanguage,
            defaultLatitude = defaultLatitude,
            defaultLongitude = defaultLongitude,
            hasFreshGpsFix = hasFreshGpsFix,
            gpsAccuracyMeters = gpsAccuracyMeters,
            currentAzimuthDegrees = currentAzimuthDegrees,
            onDismiss = { showCreatePoiDialog = false },
            onSubmit = { request, coverImageUri ->
                showCreatePoiDialog = false
                onCreatePoiSubmit(request, coverImageUri)
            }
        )
    }

    poiForEdit?.let { poi ->
        CreatePoiDialog(
            initialPoi = poi,
            knownPoiAreas = adminPois,
            selectedLanguage = selectedLanguage,
            defaultLatitude = defaultLatitude,
            defaultLongitude = defaultLongitude,
            hasFreshGpsFix = hasFreshGpsFix,
            gpsAccuracyMeters = gpsAccuracyMeters,
            currentAzimuthDegrees = currentAzimuthDegrees,
            onDismiss = { poiForEdit = null },
            onSubmit = { request, coverImageUri ->
                poiForEdit = null
                onUpdatePoiSubmit(poi.id, request, coverImageUri)
            }
        )
    }

    poiForFixedArCard?.let { poi ->
        CreateFixedArCardDialog(
            poi = poi,
            selectedLanguage = selectedLanguage,
            currentLatitude = defaultLatitude,
            currentLongitude = defaultLongitude,
            hasFreshGpsFix = hasFreshGpsFix,
            gpsAccuracyMeters = gpsAccuracyMeters,
            currentAzimuthDegrees = currentAzimuthDegrees,
            onDismiss = { poiForFixedArCard = null },
            onSubmit = onCreateFixedArCard
        )
    }

    poiForModelPlacement?.let { poi ->
        ArModelPlacementDialog(
            poi = poi,
            currentLatitude = defaultLatitude,
            currentLongitude = defaultLongitude,
            hasFreshGpsFix = hasFreshGpsFix,
            gpsAccuracyMeters = gpsAccuracyMeters,
            currentAzimuthDegrees = currentAzimuthDegrees,
            onLoadLibrary = onLoadArModelLibrary,
            onSave = { request -> onPlaceExistingArModel(poi.id, request) },
            onDismiss = { poiForModelPlacement = null }
        )
    }

    if (modelPlacementPermissionError) {
        AlertDialog(
            onDismissRequest = { modelPlacementPermissionError = false },
            title = { Text("Cần quyền camera") },
            text = { Text("Hãy cấp quyền camera để mở chế độ đặt vật thể 3D trong AR.") },
            confirmButton = {
                TextButton(onClick = { modelPlacementPermissionError = false }) { Text("Đã hiểu") }
            }
        )
    }

    arCardForEdit?.let { card ->
        EditArInfoCardDialog(
            card = card,
            selectedLanguage = selectedLanguage,
            onDismiss = { arCardForEdit = null },
            onSubmit = onUpdateArInfoCard
        )
    }

    if (showArModelCreationDialog) {
        ArModelCreationDialog(
            onBuild = onBuildArModel,
            onDismiss = { showArModelCreationDialog = false }
        )
    }

    poiPendingDeletion?.let { poi ->
        AlertDialog(
            onDismissRequest = { poiPendingDeletion = null },
            title = { Text("Xóa POI?") },
            text = {
                Text("Bạn sắp xóa vĩnh viễn “${poi.titleFor("vi")}” cùng các thẻ AR thuộc địa điểm này.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeletePoi(poi.id)
                        poiPendingDeletion = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Xóa POI") }
            },
            dismissButton = {
                TextButton(onClick = { poiPendingDeletion = null }) { Text("Hủy") }
            }
        )
    }

    arCardPendingDeletion?.let { card ->
        AlertDialog(
            onDismissRequest = { if (!isDeletingArCard) arCardPendingDeletion = null },
            title = { Text("Xóa thẻ AR?") },
            text = {
                Column {
                    Text("Thẻ “${card.localizations.firstOrNull { it.languageCode.equals("vi", true) }?.title ?: "AR ${card.displayOrder}"}” sẽ bị xóa khỏi POI.")
                    if (!arCardDeleteError.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(arCardDeleteError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            isDeletingArCard = true
                            arCardDeleteError = onDeleteArInfoCard(card.id)
                            isDeletingArCard = false
                            if (arCardDeleteError == null) arCardPendingDeletion = null
                        }
                    },
                    enabled = !isDeletingArCard,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(if (isDeletingArCard) "Đang xóa…" else "Xóa thẻ") }
            },
            dismissButton = {
                TextButton(
                    onClick = { arCardPendingDeletion = null },
                    enabled = !isDeletingArCard
                ) { Text("Hủy") }
            }
        )
    }

    // ========================================================================
    // DIALOG 2: SUPERADMIN MANDATORY REJECTION REASON DIALOG
    // ========================================================================
    poiIdPendingRejection?.let { targetPoi ->
        AlertDialog(
            onDismissRequest = { poiIdPendingRejection = null },
            title = {
                Text(
                    text = "Reject POI: ${targetPoi.titleVi.ifBlank { targetPoi.titleEn }}",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = "SuperAdmin must provide a clear rejection reason so the Admin can revise the POI content or coordinates.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = rejectionReasonInput,
                        onValueChange = { rejectionReasonInput = it },
                        label = { Text("Mandatory Rejection Reason") },
                        placeholder = { Text("e.g., Inaccurate historical date or GPS coordinates off by 100m") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (rejectionReasonInput.isNotBlank()) {
                            onSuperAdminRejectPoi(targetPoi.id, rejectionReasonInput.trim())
                            poiIdPendingRejection = null
                        }
                    },
                    enabled = rejectionReasonInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Confirm Reject")
                }
            },
            dismissButton = {
                TextButton(onClick = { poiIdPendingRejection = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun PoiApprovalWorkflowTab(
    isSuperAdmin: Boolean,
    adminPois: List<AdminPoiDto>,
    selectedLanguage: String,
    adminArCardsByPoi: Map<String, List<AdminArInfoCardDto>>,
    selectedStatusFilter: String?,
    onFilterStatusChange: (String?) -> Unit,
    onApproveClick: (AdminPoiDto) -> Unit,
    onRejectClick: (AdminPoiDto) -> Unit,
    onCreateFixedArCard: (AdminPoiDto) -> Unit,
    onPlace3dModel: (AdminPoiDto) -> Unit,
    onEditPoi: (AdminPoiDto) -> Unit,
    onEditArCard: (AdminArInfoCardDto) -> Unit,
    onDeletePoi: (AdminPoiDto) -> Unit,
    onDeleteArCard: (AdminArInfoCardDto) -> Unit
) {
    val isEn = selectedLanguage.equals("en", ignoreCase = true)
    var searchQuery by remember { mutableStateOf("") }
    var selectedProvince by remember { mutableStateOf<String?>(null) }
    var selectedWard by remember { mutableStateOf<String?>(null) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    val provinceValues = adminPois.mapNotNull { it.province?.takeIf(String::isNotBlank) }
        .distinctBy { it.lowercase() }
        .sortedBy { it.lowercase() }
    val wardScope = adminPois.filter { poi ->
        selectedProvince != null && when (selectedProvince) {
            UNCLASSIFIED_POI_AREA_FILTER -> poi.province.isNullOrBlank()
            else -> poi.province.equals(selectedProvince, ignoreCase = true)
        }
    }
    val wardValues = wardScope.mapNotNull { it.ward?.takeIf(String::isNotBlank) }
        .distinctBy { it.lowercase() }
        .sortedBy { it.lowercase() }
    val provinceChoices = buildList {
        add(null to if (isEn) "All provinces/cities" else "Tất cả Tỉnh/Thành")
        if (adminPois.any { it.province.isNullOrBlank() }) {
            add(UNCLASSIFIED_POI_AREA_FILTER to if (isEn) "Unclassified province" else "Chưa phân loại Tỉnh/Thành")
        }
        provinceValues.forEach { add(it to it) }
    }
    val wardChoices = buildList {
        add(null to if (selectedProvince == null) {
            if (isEn) "Select province first" else "Chọn Tỉnh/Thành trước"
        } else {
            if (isEn) "All wards/communes" else "Tất cả Phường/Xã"
        })
        if (wardScope.any { it.ward.isNullOrBlank() }) {
            add(UNCLASSIFIED_POI_AREA_FILTER to if (isEn) "Unclassified ward" else "Chưa phân loại Phường/Xã")
        }
        wardValues.forEach { add(it to it) }
    }
    val categoryChoices = buildList {
        add(null to if (isEn) "All categories" else "Tất cả danh mục")
        PoiCategoryLabels.values.forEach { category ->
            add(category to PoiCategoryLabels.label(category, selectedLanguage))
        }
    }
    val visiblePois = adminPois.filter { poi ->
        val matchesProvince = when (selectedProvince) {
            null -> true
            UNCLASSIFIED_POI_AREA_FILTER -> poi.province.isNullOrBlank()
            else -> poi.province.equals(selectedProvince, ignoreCase = true)
        }
        val matchesWard = when (selectedWard) {
            null -> true
            UNCLASSIFIED_POI_AREA_FILTER -> poi.ward.isNullOrBlank()
            else -> poi.ward.equals(selectedWard, ignoreCase = true)
        }
        val matchesCategory = selectedCategory == null ||
            PoiCategoryLabels.normalize(poi.category) == selectedCategory
        val searchableText = listOf(
            poi.titleFor("vi"), poi.titleFor("en"), poi.slug, poi.address.orEmpty(),
            poi.province.orEmpty(), poi.ward.orEmpty()
        ).joinToString(" ")
        matchesProvince && matchesWard && matchesCategory &&
            (searchQuery.isBlank() || searchableText.contains(searchQuery.trim(), ignoreCase = true))
    }.sortedWith(
        compareBy<AdminPoiDto> { it.province.orEmpty().lowercase() }
            .thenBy { it.ward.orEmpty().lowercase() }
            .thenBy { PoiCategoryLabels.normalize(it.category) }
            .thenBy { it.titleFor("vi").lowercase() }
    )

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Role explanation banner
        Surface(
            color = if (isSuperAdmin) BrandColors.Cream.copy(alpha = 0.62f) else BrandColors.Sand,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isSuperAdmin) Icons.Default.VerifiedUser else Icons.Default.Info,
                    contentDescription = null,
                    tint = if (isSuperAdmin) BrandColors.Primary else BrandColors.Terracotta
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (isSuperAdmin) {
                        "SuperAdmin Mode: You can Approve (Publish to Map) or Reject POIs submitted by Admins."
                    } else {
                        "Admin Mode: New or edited POIs enter 'PendingApproval' and require SuperAdmin approval before appearing on the Tourist Map."
                    },
                    color = BrandColors.PrimaryDeep,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Filter chips
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val filters = listOf(
                null to "All",
                PoiStatus.PENDING_APPROVAL.wireValue to "PendingApproval",
                PoiStatus.PUBLISHED.wireValue to "Published",
                PoiStatus.REJECTED.wireValue to "Rejected"
            )
            filters.forEach { (statusValue, label) ->
                FilterChip(
                    selected = selectedStatusFilter == statusValue,
                    onClick = { onFilterStatusChange(statusValue) },
                    label = { Text(label, fontSize = 12.sp) }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(if (isEn) "Search POI" else "Tìm POI") },
            placeholder = { Text(if (isEn) "Name, address or code" else "Tên, địa chỉ hoặc mã POI") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = if (searchQuery.isNotBlank()) {
                {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = if (isEn) "Clear" else "Xóa")
                    }
                }
            } else null
        )

        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            AdminPoiFilterDropdown(
                label = if (isEn) "Province/city" else "Tỉnh/Thành",
                selectedValue = selectedProvince,
                choices = provinceChoices,
                onValueSelected = {
                    selectedProvince = it
                    selectedWard = null
                },
                modifier = Modifier.weight(1f)
            )
            AdminPoiFilterDropdown(
                label = if (isEn) "Ward/commune" else "Phường/Xã",
                selectedValue = selectedWard,
                choices = wardChoices,
                onValueSelected = { selectedWard = it },
                enabled = selectedProvince != null,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        AdminPoiFilterDropdown(
            label = if (isEn) "POI category" else "Danh mục POI",
            selectedValue = selectedCategory,
            choices = categoryChoices,
            onValueSelected = { selectedCategory = it },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = if (isEn) "Showing ${visiblePois.size} of ${adminPois.size} POIs" else "Hiển thị ${visiblePois.size}/${adminPois.size} POI",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            if (visiblePois.isEmpty()) {
                item {
                    Text(
                        text = if (isEn) "No POIs match these filters." else "Không có POI phù hợp với bộ lọc.",
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(visiblePois, key = { it.id }) { poi ->
                val poiArCards = adminArCardsByPoi[poi.id].orEmpty()
                val arCardCount = adminArCardsByPoi[poi.id]?.size ?: poi.arInfoCardCount
                Card(
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusBadge(status = poi.parsedStatus)
                            Text(
                                text = "Radius: ${poi.activationRadiusMeters.toInt()}m • $arCardCount AR Cards",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (isSuperAdmin) {
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedButton(
                                onClick = { onDeletePoi(poi) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Xóa POI", fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = poi.titleFor("vi"),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        val provinceLabel = poi.province?.takeIf(String::isNotBlank)
                            ?: if (isEn) "Unclassified province/city" else "Chưa phân loại Tỉnh/Thành"
                        val wardLabel = poi.ward?.takeIf(String::isNotBlank)
                            ?: if (isEn) "Unclassified ward/commune" else "Chưa phân loại Phường/Xã"
                        Text(
                            text = "$provinceLabel  ›  $wardLabel  ›  ${PoiCategoryLabels.label(poi.category, selectedLanguage)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = BrandColors.Primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (poi.titleFor("en").isNotBlank() && poi.titleFor("en") != poi.titleFor("vi")) {
                            Text(
                                text = "EN: ${poi.titleFor("en")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = poi.summaryFor(selectedLanguage),
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "GPS: ${"%.5f".format(poi.latitude)}, ${"%.5f".format(poi.longitude)} • Submitted by ${poi.createdByAdminName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (!poi.rejectionReason.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                color = Color(0xFFFEE2E2),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Rejection Reason: ${poi.rejectionReason}",
                                    color = Color(0xFF991B1B),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = { onEditPoi(poi) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Chỉnh sửa", fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = { onCreateFixedArCard(poi) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Tạo thẻ AR", fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Button(
                            onClick = { onPlace3dModel(poi) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = BrandColors.Primary)
                        ) {
                            Icon(Icons.Default.ViewInAr, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Đặt vật thể 3D bằng camera AR", fontSize = 13.sp)
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Thẻ AR của POI ($arCardCount)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        if (poiArCards.isEmpty()) {
                            Text(
                                text = if (adminArCardsByPoi.containsKey(poi.id)) "Chưa có thẻ AR." else "Danh sách thẻ AR chưa tải được.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Spacer(modifier = Modifier.height(6.dp))
                            poiArCards.sortedBy { it.displayOrder }.forEach { arCard ->
                                ArInfoCardAdminItem(
                                    card = arCard,
                                    selectedLanguage = selectedLanguage,
                                    onEdit = { onEditArCard(arCard) },
                                    onDelete = { onDeleteArCard(arCard) }
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }

                        if (poi.parsedStatus == PoiStatus.PENDING_APPROVAL) {
                            Spacer(modifier = Modifier.height(12.dp))
                            if (isSuperAdmin) {
                                Row(
                                    horizontalArrangement = Arrangement.End,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    OutlinedButton(
                                        onClick = { onRejectClick(poi) },
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        )
                                    ) {
                                        Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Reject")
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Button(
                                        onClick = { onApproveClick(poi) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A))
                                    ) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Approve & Publish")
                                    }
                                }
                            } else {
                                Text(
                                    text = "⏳ Waiting for SuperAdmin review & approval to publish on map.",
                                    color = Color(0xFFB45309),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminPoiFilterDropdown(
    label: String,
    selectedValue: String?,
    choices: List<Pair<String?, String>>,
    onValueSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var expanded by remember(label) { mutableStateOf(false) }
    val selectedLabel = choices.firstOrNull { it.first == selectedValue }?.second ?: label

    Box(modifier = modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), enabled = enabled) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(
                    selectedLabel,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp)
        ) {
            choices.forEach { (value, title) ->
                DropdownMenuItem(
                    text = { Text(title) },
                    onClick = {
                        onValueSelected(value)
                        expanded = false
                    },
                    trailingIcon = {
                        if (value == selectedValue) Icon(Icons.Default.Check, contentDescription = null)
                    }
                )
            }
        }
    }
}

@Composable
private fun AdminPoiSelectionDropdown(
    label: String,
    value: String,
    placeholder: String,
    choices: List<String>,
    onValueSelected: (String) -> Unit
) {
    var expanded by remember(label) { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(
                    value.ifBlank { placeholder },
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 360.dp)
        ) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice) },
                    onClick = {
                        onValueSelected(choice)
                        expanded = false
                    },
                    trailingIcon = {
                        if (choice == value) Icon(Icons.Default.Check, contentDescription = null)
                    }
                )
            }
        }
    }
}

@Composable
private fun PoiAreaAutocompleteField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    knownValues: List<String>
) {
    var expanded by remember(label) { mutableStateOf(false) }
    val suggestions = knownValues
        .filter { value.isBlank() || it.contains(value.trim(), ignoreCase = true) }
        .distinctBy { it.lowercase() }
        .take(50)

    Box {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                if (suggestions.isNotEmpty()) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                }
            }
        )
        DropdownMenu(
            expanded = expanded && suggestions.isNotEmpty(),
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 280.dp)
        ) {
            suggestions.forEach { suggestion ->
                DropdownMenuItem(
                    text = { Text(suggestion) },
                    onClick = {
                        onValueChange(suggestion)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ArInfoCardAdminItem(
    card: AdminArInfoCardDto,
    selectedLanguage: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val vi = card.localizations.firstOrNull { it.languageCode.equals("vi", true) }
    val en = card.localizations.firstOrNull { it.languageCode.equals("en", true) }
    val primary = if (selectedLanguage.equals("en", true)) en ?: vi else vi ?: en
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = primary?.title ?: "Thẻ AR ${card.displayOrder}",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    if (vi != null && en != null) {
                        Text("EN: ${en.title}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Sửa")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Xóa thẻ AR",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            if (vi != null) {
                Text("VI: ${vi.content}", style = MaterialTheme.typography.bodySmall)
            }
            if (en != null) {
                Text("EN: ${en.content}", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = "${card.cardType} • ${card.azimuthDegrees.roundToInt()}° • ${card.distanceMeters.roundToInt()}m • ${card.elevationMeters}m • ${if (card.isActive) "Đang bật" else "Đang tắt"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val modelStatusLabel = when (card.modelStatus.lowercase()) {
                    "ready" -> "Mô hình 3D đã sẵn sàng"
                    "processing" -> "Đang dựng mô hình 3D…"
                    "failed" -> "Dựng 3D thất bại${card.modelError?.let { ": $it" }.orEmpty()}"
                    else -> "Chưa có vật thể 3D"
                }
                Text(modelStatusLabel, style = MaterialTheme.typography.labelSmall)
            }
            if (!card.modelName.isNullOrBlank()) {
                Text(
                    text = "${card.modelName} • ${modelCategoryLabel(card.modelCategory, selectedLanguage.equals("en", true))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = BrandColors.Terracotta
                )
            }
        }
    }
}

@Composable
private fun ReportedPhotoModerationTab(
    reportedPhotos: List<ModerationPhotoItemDto>,
    onDecision: (String, String, String?) -> Unit
) {
    if (reportedPhotos.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No reported community photos pending moderation.")
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(reportedPhotos, key = { it.photoId }) { item ->
            Card(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = item.imageUrl,
                            contentDescription = item.caption,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(92.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.DarkGray)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.poiTitle,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "Author: @${item.authorDisplayName} • Reports: ${item.reportCount}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Reason: ${item.latestReportReason}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (!item.caption.isNullOrBlank()) {
                                Text(
                                    text = "Caption: \"${item.caption}\"",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = { onDecision(item.photoId, "Keep", "Reviewed safe by moderator") }
                        ) {
                            Text("Keep Photo")
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Button(
                            onClick = { onDecision(item.photoId, "Remove", "Violates community guidelines") },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Remove from Storage")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: PoiStatus) {
    val (bg, fg, label) = when (status) {
        PoiStatus.PUBLISHED -> Triple(Color(0xFFDCFCE7), Color(0xFF15803D), "Published")
        PoiStatus.PENDING_APPROVAL -> Triple(Color(0xFFFEF3C7), Color(0xFFB45309), "PendingApproval")
        PoiStatus.REJECTED -> Triple(Color(0xFFFEE2E2), Color(0xFFB91C1C), "Rejected")
        PoiStatus.DRAFT -> Triple(Color(0xFFE2E8F0), Color(0xFF475569), "Draft")
    }
    Surface(color = bg, shape = RoundedCornerShape(8.dp)) {
        Text(
            text = label,
            color = fg,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

private data class CapturedArPlacement(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val azimuthDegrees: Float,
    val distanceMeters: Double
)

@Composable
private fun CreateFixedArCardDialog(
    poi: AdminPoiDto,
    selectedLanguage: String,
    currentLatitude: Double,
    currentLongitude: Double,
    hasFreshGpsFix: Boolean,
    gpsAccuracyMeters: Float,
    currentAzimuthDegrees: Float,
    onDismiss: () -> Unit,
    onSubmit: suspend (String, UpsertArInfoCardRequest) -> String?
) {
    val isEn = selectedLanguage.equals("en", ignoreCase = true)
    val coroutineScope = rememberCoroutineScope()
    var titleVi by remember { mutableStateOf("") }
    var titleEn by remember { mutableStateOf("") }
    var contentVi by remember { mutableStateOf("") }
    var contentEn by remember { mutableStateOf("") }
    var azimuthText by remember { mutableStateOf("${((currentAzimuthDegrees % 360f) + 360f) % 360f}") }
    var distanceText by remember { mutableStateOf("4") }
    var elevationText by remember { mutableStateOf("1.5") }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var capturedPlacement by remember(poi.id) { mutableStateOf<CapturedArPlacement?>(null) }
    var showPlacementCamera by remember(poi.id) { mutableStateOf(false) }
    var hasCameraPlacement by remember(poi.id) { mutableStateOf(false) }
    var placementScaleFactor by remember(poi.id) { mutableFloatStateOf(1f) }
    var placementPreviewBitmap by remember(poi.id) { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(placementPreviewBitmap) {
        val bitmap = placementPreviewBitmap
        onDispose {
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    val distanceToPoi = LocationAndOrientationTracker.calculateDistanceMeters(
        currentLatitude,
        currentLongitude,
        poi.latitude,
        poi.longitude
    )
    val isAtPoi = hasFreshGpsFix && distanceToPoi <= poi.activationRadiusMeters + 20.0
    val canCapturePlacement = isAtPoi && gpsAccuracyMeters <= 15f && distanceToPoi <= 50.0
    val azimuth = azimuthText.replace(',', '.').toDoubleOrNull()
    val anchorDistance = distanceText.replace(',', '.').toDoubleOrNull()
    val elevation = elevationText.replace(',', '.').toDoubleOrNull()
    val hasContent = (titleVi.isNotBlank() || titleEn.isNotBlank()) &&
        (contentVi.isNotBlank() || contentEn.isNotBlank())
    val canSubmit = capturedPlacement != null && hasCameraPlacement && hasContent && azimuth != null && azimuth in 0.0..360.0 &&
        anchorDistance != null && anchorDistance in 1.0..50.0 &&
        elevation != null && elevation in -5.0..20.0 && !isSubmitting

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(if (isEn) "Create fixed AR card" else "Tạo thẻ AR cố định")
                Text(
                    text = poi.titleVi.ifBlank { poi.titleEn.ifBlank { poi.slug } },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = capturedPlacement?.let { placement ->
                        if (hasCameraPlacement) {
                            if (isEn) "Camera placement saved • GPS ±${placement.accuracyMeters.roundToInt()}m • ${placement.distanceMeters.roundToInt()}m from POI • size ${"%.2f".format(placementScaleFactor)}×."
                            else "Đã đặt bằng camera AR • GPS ±${placement.accuracyMeters.roundToInt()}m • cách POI ${placement.distanceMeters.roundToInt()}m • cỡ ${"%.2f".format(placementScaleFactor)}×."
                        } else {
                            if (isEn) "A live AR camera placement is required before saving this card."
                            else "Cần mở camera AR và chọn vị trí trước khi lưu thẻ này."
                        }
                    } ?: if (canCapturePlacement) {
                        if (isEn) "Move near the POI, open the AR camera and tap the surface where the card should appear."
                        else "Đến gần POI, mở camera AR rồi chạm vào mặt phẳng tại nơi muốn hiển thị thẻ."
                    } else if (!hasFreshGpsFix) {
                        if (isEn) "Waiting for an accurate live GPS fix before saving an AR position."
                        else "Cần GPS thực đủ chính xác trước khi lưu vị trí AR."
                    } else if (gpsAccuracyMeters > 15f) {
                        if (isEn) "Improve GPS accuracy to 15m or better before opening AR placement."
                        else "Cần chờ GPS đạt sai số 15 m hoặc tốt hơn trước khi mở chế độ đặt AR."
                    } else if (distanceToPoi > 50.0) {
                        if (isEn) "The AR position must be within 50m of this POI."
                        else "Vị trí AR cần nằm trong phạm vi 50m quanh POI này."
                    } else {
                        if (isEn) "Move inside the active area of this POI to save an AR position."
                        else "Hãy vào vùng hoạt động của POI để lưu vị trí AR."
                    },
                    color = if (capturedPlacement != null || canCapturePlacement) Color(0xFF15803D) else Color(0xFFB45309),
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = titleVi,
                    onValueChange = { titleVi = it },
                    label = { Text("Tiêu đề thẻ (VI)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = contentVi,
                    onValueChange = { contentVi = it },
                    label = { Text("Nội dung thẻ (VI)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                OutlinedTextField(
                    value = titleEn,
                    onValueChange = { titleEn = it },
                    label = { Text("AR card title (EN)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = contentEn,
                    onValueChange = { contentEn = it },
                    label = { Text("AR card content (EN)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                FilledTonalButton(
                    onClick = {
                        placementPreviewBitmap = createArCardPlacementBitmap(
                            title = if (isEn) titleEn.ifBlank { titleVi } else titleVi.ifBlank { titleEn },
                            content = if (isEn) contentEn.ifBlank { contentVi } else contentVi.ifBlank { contentEn }
                        )
                        showPlacementCamera = true
                    },
                    enabled = canCapturePlacement && hasContent && !isSubmitting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ViewInAr, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        when {
                            hasCameraPlacement -> if (isEn) "Adjust AR placement in camera" else "Điều chỉnh vị trí trong camera AR"
                            isEn -> "Open AR camera to place card"
                            else -> "Mở camera AR để đặt thẻ"
                        }
                    )
                }
                capturedPlacement?.let { placement ->
                    Text(
                        text = if (isEn) {
                            "Saved coordinates: ${formatGpsCoordinate(placement.latitude)}, ${formatGpsCoordinate(placement.longitude)} • Bearing ${placement.azimuthDegrees.roundToInt()}°"
                        } else {
                            "Tọa độ đã lưu: ${formatGpsCoordinate(placement.latitude)}, ${formatGpsCoordinate(placement.longitude)} • Hướng ${placement.azimuthDegrees.roundToInt()}°"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedTextField(
                    value = azimuthText,
                    onValueChange = { azimuthText = it; hasCameraPlacement = false },
                    label = { Text(if (isEn) "Pinned compass bearing (0–360°)" else "Hướng la bàn đã ghim (0–360°)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = distanceText,
                        onValueChange = { distanceText = it; hasCameraPlacement = false },
                        label = { Text(if (isEn) "Distance (1–50m)" else "Khoảng cách (1–50m)") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = elevationText,
                        onValueChange = { elevationText = it; hasCameraPlacement = false },
                        label = { Text(if (isEn) "Height (−5–20m)" else "Độ cao (−5–20m)") },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (errorMessage != null) {
                    Text(errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = canSubmit,
                onClick = {
                    val cardAzimuth = azimuth ?: return@Button
                    val cardDistance = anchorDistance ?: return@Button
                    val cardElevation = elevation ?: return@Button
                    coroutineScope.launch {
                        isSubmitting = true
                        errorMessage = null
                        val request = UpsertArInfoCardRequest(
                            cardType = "HistoricalFact",
                            azimuthDegrees = cardAzimuth.toFloat(),
                            distanceMeters = cardDistance.toFloat(),
                            elevationMeters = cardElevation.toFloat(),
                            displayOrder = 1,
                            isActive = true,
                            scaleFactor = placementScaleFactor,
                            localizations = listOf(
                                ArCardLocalizationInputDto(
                                    languageCode = "vi",
                                    title = titleVi.ifBlank { titleEn }.trim(),
                                    content = contentVi.ifBlank { contentEn }.trim()
                                ),
                                ArCardLocalizationInputDto(
                                    languageCode = "en",
                                    title = titleEn.ifBlank { titleVi }.trim(),
                                    content = contentEn.ifBlank { contentVi }.trim()
                                )
                            )
                        )
                        val error = onSubmit(poi.id, request)
                        isSubmitting = false
                        if (error == null) onDismiss() else errorMessage = error
                    }
                }
            ) {
                Text(if (isSubmitting) (if (isEn) "Saving…" else "Đang lưu…") else (if (isEn) "Create AR card" else "Tạo thẻ AR"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) {
                Text(if (isEn) "Cancel" else "Hủy")
            }
        }
    )

    if (showPlacementCamera && placementPreviewBitmap != null) {
        ArAnchorPlacementDialog(
            title = if (isEn) "Place AR card" else "Đặt thẻ AR",
            poiLatitude = poi.latitude,
            poiLongitude = poi.longitude,
            currentLatitude = currentLatitude,
            currentLongitude = currentLongitude,
            hasFreshGpsFix = hasFreshGpsFix,
            gpsAccuracyMeters = gpsAccuracyMeters,
            currentAzimuthDegrees = currentAzimuthDegrees,
            maxDistanceFromPoiMeters = 50.0,
            minDistanceFromPoiMeters = 1.0,
            previewBitmap = placementPreviewBitmap!!,
            previewWidthMeters = 0.88f,
            initialScaleFactor = placementScaleFactor,
            selectedLanguage = selectedLanguage,
            onDismiss = {
                showPlacementCamera = false
                placementPreviewBitmap = null
            },
            onConfirm = { placement ->
                capturedPlacement = CapturedArPlacement(
                    latitude = placement.latitude,
                    longitude = placement.longitude,
                    accuracyMeters = gpsAccuracyMeters,
                    azimuthDegrees = placement.poiAzimuthDegrees,
                    distanceMeters = placement.distanceFromPoiMeters.toDouble()
                )
                azimuthText = placement.poiAzimuthDegrees.toString()
                distanceText = java.lang.String.format(java.util.Locale.US, "%.1f", placement.distanceFromPoiMeters)
                elevationText = placement.elevationMeters.toString()
                placementScaleFactor = placement.scaleFactor
                hasCameraPlacement = true
                showPlacementCamera = false
                placementPreviewBitmap = null
                errorMessage = null
            }
        )
    }
}

@Composable
private fun EditArInfoCardDialog(
    card: AdminArInfoCardDto,
    selectedLanguage: String,
    onDismiss: () -> Unit,
    onSubmit: suspend (String, UpsertArInfoCardRequest) -> String?
) {
    val isEn = selectedLanguage.equals("en", ignoreCase = true)
    val scope = rememberCoroutineScope()
    val vi = remember(card.id) { card.localizations.firstOrNull { it.languageCode.equals("vi", true) } }
    val en = remember(card.id) { card.localizations.firstOrNull { it.languageCode.equals("en", true) } }
    var titleVi by remember(card.id) { mutableStateOf(vi?.title.orEmpty()) }
    var titleEn by remember(card.id) { mutableStateOf(en?.title.orEmpty()) }
    var subtitleVi by remember(card.id) { mutableStateOf(vi?.subtitle.orEmpty()) }
    var subtitleEn by remember(card.id) { mutableStateOf(en?.subtitle.orEmpty()) }
    var contentVi by remember(card.id) { mutableStateOf(vi?.content.orEmpty()) }
    var contentEn by remember(card.id) { mutableStateOf(en?.content.orEmpty()) }
    var cardType by remember(card.id) { mutableStateOf(card.cardType) }
    var azimuthText by remember(card.id) { mutableStateOf(card.azimuthDegrees.toString()) }
    var distanceText by remember(card.id) { mutableStateOf(card.distanceMeters.toString()) }
    var elevationText by remember(card.id) { mutableStateOf(card.elevationMeters.toString()) }
    var scaleFactorText by remember(card.id) { mutableStateOf(card.scaleFactor.toString()) }
    var displayOrderText by remember(card.id) { mutableStateOf(card.displayOrder.toString()) }
    var isActive by remember(card.id) { mutableStateOf(card.isActive) }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val azimuth = azimuthText.replace(',', '.').toDoubleOrNull()
    val distance = distanceText.replace(',', '.').toDoubleOrNull()
    val elevation = elevationText.replace(',', '.').toDoubleOrNull()
    val scaleFactor = scaleFactorText.replace(',', '.').toDoubleOrNull()
    val displayOrder = displayOrderText.toIntOrNull()
    val hasContent = (titleVi.isNotBlank() || titleEn.isNotBlank()) &&
        (contentVi.isNotBlank() || contentEn.isNotBlank())
    val canSubmit = hasContent && cardType.isNotBlank() && azimuth != null && azimuth in 0.0..360.0 &&
        distance != null && distance in 1.0..50.0 && elevation != null && elevation in -5.0..20.0 &&
        scaleFactor != null && scaleFactor in 0.5..3.0 &&
        displayOrder != null && displayOrder >= 1 && !isSubmitting

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = { Text(if (isEn) "Edit AR card" else "Chỉnh sửa thẻ AR") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(cardType, { cardType = it }, label = { Text("Loại thẻ") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(titleVi, { titleVi = it }, label = { Text("Tiêu đề (VI)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(subtitleVi, { subtitleVi = it }, label = { Text("Phụ đề (VI)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(contentVi, { contentVi = it }, label = { Text("Nội dung (VI)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(titleEn, { titleEn = it }, label = { Text("Title (EN)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(subtitleEn, { subtitleEn = it }, label = { Text("Subtitle (EN)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(contentEn, { contentEn = it }, label = { Text("Content (EN)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(azimuthText, { azimuthText = it }, label = { Text("Hướng la bàn (0–360°)") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(distanceText, { distanceText = it }, label = { Text("Khoảng cách (1–50m)") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(elevationText, { elevationText = it }, label = { Text("Độ cao (−5–20m)") }, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(scaleFactorText, { scaleFactorText = it }, label = { Text(if (isEn) "AR size (0.5–3×)" else "Kích thước AR (0,5–3×)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(displayOrderText, { displayOrderText = it }, label = { Text("Thứ tự hiển thị") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isEn) "Active" else "Kích hoạt", modifier = Modifier.weight(1f))
                    Switch(checked = isActive, onCheckedChange = { isActive = it })
                }
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                enabled = canSubmit,
                onClick = {
                    val az = azimuth ?: return@Button
                    val dist = distance ?: return@Button
                    val elev = elevation ?: return@Button
                    val visualScale = scaleFactor ?: return@Button
                    val order = displayOrder ?: return@Button
                    val request = UpsertArInfoCardRequest(
                        cardType = cardType.trim(),
                        azimuthDegrees = az.toFloat(),
                        distanceMeters = dist.toFloat(),
                        elevationMeters = elev.toFloat(),
                        displayOrder = order,
                        isActive = isActive,
                        scaleFactor = visualScale.toFloat(),
                        localizations = listOf(
                            ArCardLocalizationInputDto(
                                languageCode = "vi",
                                title = titleVi.ifBlank { titleEn }.trim(),
                                subtitle = subtitleVi.ifBlank { subtitleEn }.trim().ifBlank { null },
                                content = contentVi.ifBlank { contentEn }.trim()
                            ),
                            ArCardLocalizationInputDto(
                                languageCode = "en",
                                title = titleEn.ifBlank { titleVi }.trim(),
                                subtitle = subtitleEn.ifBlank { subtitleVi }.trim().ifBlank { null },
                                content = contentEn.ifBlank { contentVi }.trim()
                            )
                        )
                    )
                    scope.launch {
                        isSubmitting = true
                        errorMessage = null
                        val error = onSubmit(card.id, request)
                        isSubmitting = false
                        if (error == null) onDismiss() else errorMessage = error
                    }
                }
            ) { Text(if (isSubmitting) "Đang lưu…" else "Lưu thay đổi") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text("Hủy") }
        }
    )

}

@Composable
private fun CreatePoiDialog(
    initialPoi: AdminPoiDto? = null,
    knownPoiAreas: List<AdminPoiDto>,
    selectedLanguage: String,
    defaultLatitude: Double,
    defaultLongitude: Double,
    hasFreshGpsFix: Boolean,
    gpsAccuracyMeters: Float,
    currentAzimuthDegrees: Float,
    onDismiss: () -> Unit,
    onSubmit: (CreateOrUpdatePoiRequest, Uri?) -> Unit
) {
    val context = LocalContext.current
    val administrativeAreas = remember { VietnamAdministrativeAreaCatalog.load(context) }
    val officialProvinceNames = remember(administrativeAreas) {
        administrativeAreas.map { it.province }.distinct()
    }
    val initialVi = remember(initialPoi?.id) { initialPoi?.localizations?.firstOrNull { it.languageCode.equals("vi", true) } }
    val initialEn = remember(initialPoi?.id) { initialPoi?.localizations?.firstOrNull { it.languageCode.equals("en", true) } }
    var titleVi by remember(initialPoi?.id) { mutableStateOf(initialVi?.title.orEmpty()) }
    var titleEn by remember(initialPoi?.id) { mutableStateOf(initialEn?.title.orEmpty()) }
    var address by remember(initialPoi?.id) { mutableStateOf(initialPoi?.address.orEmpty()) }
    var province by remember(initialPoi?.id) { mutableStateOf(initialPoi?.province.orEmpty()) }
    var ward by remember(initialPoi?.id) { mutableStateOf(initialPoi?.ward.orEmpty()) }
    var summaryVi by remember(initialPoi?.id) { mutableStateOf(initialVi?.shortSummary.orEmpty()) }
    var summaryEn by remember(initialPoi?.id) { mutableStateOf(initialEn?.shortSummary.orEmpty()) }
    var storyVi by remember(initialPoi?.id) { mutableStateOf(initialVi?.culturalStoryMarkdown.orEmpty()) }
    var storyEn by remember(initialPoi?.id) { mutableStateOf(initialEn?.culturalStoryMarkdown.orEmpty()) }
    var category by remember(initialPoi?.id) {
        mutableStateOf(PoiCategoryLabels.normalize(initialPoi?.category))
    }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var showCoordinatePicker by remember(initialPoi?.id) { mutableStateOf(false) }
    var latitudeText by remember(initialPoi?.id) { mutableStateOf(initialPoi?.latitude?.let(::formatGpsCoordinate) ?: if (hasFreshGpsFix) formatGpsCoordinate(defaultLatitude) else "") }
    var longitudeText by remember(initialPoi?.id) { mutableStateOf(initialPoi?.longitude?.let(::formatGpsCoordinate) ?: if (hasFreshGpsFix) formatGpsCoordinate(defaultLongitude) else "") }
    var radiusText by remember(initialPoi?.id) { mutableStateOf(initialPoi?.activationRadiusMeters?.toString() ?: "60") }
    var arCardTitle by remember { mutableStateOf("Architectural Highlight") }
    var arCardBody by remember { mutableStateOf("360° Spatial AR Cultural Note anchored at this landmark.") }
    var arCardAzimuth by remember { mutableStateOf("${((currentAzimuthDegrees % 360f) + 360f) % 360f}") }
    var coverImageUri by remember(initialPoi?.id) { mutableStateOf<Uri?>(null) }
    val provinceChoices = remember(initialPoi?.province, officialProvinceNames) {
        (officialProvinceNames + listOfNotNull(
            initialPoi?.province?.takeIf { current ->
                current.isNotBlank() && officialProvinceNames.none { it.equals(current, ignoreCase = true) }
            }
        )).distinctBy { it.lowercase() }
    }
    val officialWardChoices = remember(administrativeAreas, province) {
        administrativeAreas.asSequence()
            .filter { it.province.equals(province.trim(), ignoreCase = true) }
            .map { it.commune }
            .distinct()
            .sortedBy { it.lowercase() }
            .toList()
    }
    val wardChoices = remember(officialWardChoices, knownPoiAreas, province) {
        (officialWardChoices + knownPoiAreas.asSequence()
            .filter { it.province.equals(province.trim(), ignoreCase = true) }
            .mapNotNull { it.ward?.takeIf(String::isNotBlank) }
            .toList())
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
    }
    val pickCoverImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        coverImageUri = uri
    }

    val parsedLatitude = latitudeText.replace(',', '.').toDoubleOrNull()
    val parsedLongitude = longitudeText.replace(',', '.').toDoubleOrNull()
    val parsedRadius = radiusText.toDoubleOrNull()
    val parsedAzimuth = arCardAzimuth.replace(',', '.').toFloatOrNull()
    val hasValidCoordinates = parsedLatitude != null && parsedLatitude in -90.0..90.0 &&
        parsedLongitude != null && parsedLongitude in -180.0..180.0
    val canSubmit = (titleVi.isNotBlank() || titleEn.isNotBlank()) && hasValidCoordinates &&
        parsedRadius != null && parsedRadius > 0.0 &&
        (ward.isBlank() || province.isNotBlank()) &&
        (initialPoi != null || (
            provinceChoices.any { it.equals(province.trim(), ignoreCase = true) } &&
                wardChoices.any { it.equals(ward.trim(), ignoreCase = true) }
            )) &&
        (initialPoi != null || (parsedAzimuth != null && parsedAzimuth >= 0f && parsedAzimuth < 360f))

    LaunchedEffect(hasFreshGpsFix, defaultLatitude, defaultLongitude) {
        if (hasFreshGpsFix && latitudeText.isBlank() && longitudeText.isBlank()) {
            latitudeText = formatGpsCoordinate(defaultLatitude)
            longitudeText = formatGpsCoordinate(defaultLongitude)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialPoi == null) "Tạo địa điểm mới" else "Chỉnh sửa địa điểm", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Ảnh thumbnail trang Địa điểm", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val previewImage: Any? = coverImageUri ?: initialPoi?.coverImageUrl
                    if (previewImage != null) {
                        AsyncImage(
                            model = previewImage,
                            contentDescription = "POI thumbnail preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(76.dp).clip(RoundedCornerShape(12.dp)).background(Color.LightGray)
                        )
                    } else {
                        Surface(
                            modifier = Modifier.size(76.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        OutlinedButton(onClick = { pickCoverImage.launch("image/*") }) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (coverImageUri == null) "Chọn ảnh" else "Đổi ảnh")
                        }
                        Text("Ảnh tự nén còn khoảng 300 KB trước khi tải lên.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedTextField(
                    value = titleVi,
                    onValueChange = { titleVi = it },
                    label = { Text("Title (Vietnamese - VI)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = titleEn,
                    onValueChange = { titleEn = it },
                    label = { Text("Title (English - EN)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it.take(500) },
                    label = { Text(if (selectedLanguage.equals("en", true)) "Address" else "Địa chỉ") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2
                )
                AdminPoiSelectionDropdown(
                    value = province,
                    label = if (selectedLanguage.equals("en", true)) "Province / city" else "Tỉnh / Thành phố",
                    placeholder = if (selectedLanguage.equals("en", true)) "Choose a province/city" else "Chọn Tỉnh/Thành phố",
                    choices = provinceChoices,
                    onValueSelected = { selectedProvince ->
                        if (!province.equals(selectedProvince, ignoreCase = true)) ward = ""
                        province = selectedProvince
                    }
                )
                PoiAreaAutocompleteField(
                    value = ward,
                    onValueChange = { ward = it.take(150) },
                    label = if (selectedLanguage.equals("en", true)) "Ward / commune / special zone" else "Phường / Xã / Đặc khu",
                    placeholder = if (selectedLanguage.equals("en", true)) "Type to search, then choose a ward/commune" else "Nhập để tìm rồi chọn Phường/Xã/Đặc khu",
                    knownValues = wardChoices
                )
                if (initialPoi == null && province.isBlank()) {
                    Text(
                        text = if (selectedLanguage.equals("en", true)) "Choose a province/city to classify this POI." else "Hãy chọn Tỉnh/Thành phố để phân loại POI.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (initialPoi == null && ward.isBlank()) {
                    Text(
                        text = if (selectedLanguage.equals("en", true)) "Select a ward/commune from the official list to finish the location hierarchy." else "Hãy chọn Phường/Xã/Đặc khu trong danh sách để hoàn tất phân cấp địa chỉ.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (initialPoi == null && wardChoices.none { it.equals(ward.trim(), ignoreCase = true) }) {
                    Text(
                        text = if (selectedLanguage.equals("en", true)) "Choose a matching result from the list so POI filters stay consistent." else "Hãy chọn kết quả phù hợp trong danh sách để bộ lọc POI luôn thống nhất.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (ward.isNotBlank() && province.isBlank()) {
                    Text(
                        text = if (selectedLanguage.equals("en", true)) "Enter the province/city before the ward/commune." else "Hãy nhập Tỉnh/Thành phố trước khi nhập Phường/Xã.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    text = if (selectedLanguage.equals("en", true)) "Category" else "Danh mục",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { categoryMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = PoiCategoryLabels.label(category, selectedLanguage),
                            modifier = Modifier.weight(1f)
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = categoryMenuExpanded,
                        onDismissRequest = { categoryMenuExpanded = false },
                        modifier = Modifier.fillMaxWidth(0.92f)
                    ) {
                        PoiCategoryLabels.values.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(PoiCategoryLabels.label(option, selectedLanguage)) },
                                onClick = {
                                    category = option
                                    categoryMenuExpanded = false
                                },
                                trailingIcon = {
                                    if (category == option) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                }
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = latitudeText,
                        onValueChange = { latitudeText = it },
                        label = { Text("Latitude") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = longitudeText,
                        onValueChange = { longitudeText = it },
                        label = { Text("Longitude") },
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedButton(
                    onClick = { showCoordinatePicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Chọn vị trí trên bản đồ")
                }
                FilledTonalButton(
                    onClick = {
                        latitudeText = formatGpsCoordinate(defaultLatitude)
                        longitudeText = formatGpsCoordinate(defaultLongitude)
                    },
                    enabled = hasFreshGpsFix,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (hasFreshGpsFix) "Lấy tọa độ GPS hiện tại (±${gpsAccuracyMeters.roundToInt()}m)"
                        else "Đang chờ vị trí GPS thực"
                    )
                }
                OutlinedTextField(
                    value = radiusText,
                    onValueChange = { radiusText = it },
                    label = { Text("AR Activation Radius (meters)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = summaryVi,
                    onValueChange = { summaryVi = it },
                    label = { Text("Cultural Story Summary (VI)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                OutlinedTextField(
                    value = summaryEn,
                    onValueChange = { summaryEn = it },
                    label = { Text("Cultural Story Summary (EN)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                OutlinedTextField(
                    value = storyVi,
                    onValueChange = { storyVi = it },
                    label = { Text("Câu chuyện văn hóa (VI)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                OutlinedTextField(
                    value = storyEn,
                    onValueChange = { storyEn = it },
                    label = { Text("Cultural story (EN)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                if (initialPoi == null) {
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("Thẻ thông tin AR được ghim tại địa điểm", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    OutlinedTextField(
                        value = arCardTitle,
                        onValueChange = { arCardTitle = it },
                        label = { Text("AR Card Title") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = arCardAzimuth,
                        onValueChange = { arCardAzimuth = it },
                        label = { Text("Hướng AR (độ, 0–359)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    FilledTonalButton(
                        onClick = {
                            arCardAzimuth = "${((currentAzimuthDegrees % 360f) + 360f) % 360f}"
                        },
                        enabled = hasFreshGpsFix,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Explore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Ghim theo hướng camera hiện tại (${currentAzimuthDegrees.roundToInt()}°)")
                    }
                    OutlinedTextField(
                        value = arCardBody,
                        onValueChange = { arCardBody = it },
                        label = { Text("AR Card Description") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val slug = initialPoi?.slug ?: (titleEn.ifBlank { titleVi }).lowercase()
                        .replace(Regex("[^a-z0-9]+"), "-")
                        .trim('-')
                        .ifBlank { "poi-${System.currentTimeMillis()}" }

                    val lat = parsedLatitude ?: return@Button
                    val lng = parsedLongitude ?: return@Button
                    val radius = parsedRadius ?: return@Button
                    val azimuthInput = parsedAzimuth ?: 0f
                    val azimuth = ((azimuthInput % 360f) + 360f) % 360f

                    onSubmit(
                        CreateOrUpdatePoiRequest(
                            slug = slug,
                            category = category,
                            latitude = lat,
                            longitude = lng,
                            activationRadiusMeters = radius,
                            address = address.trim(),
                            province = province.trim(),
                            ward = ward.trim(),
                            coverImageUrl = initialPoi?.coverImageUrl,
                            translations = listOf(
                                PoiTranslationInputDto(
                                    languageCode = "vi",
                                    title = titleVi.ifBlank { titleEn },
                                    shortSummary = summaryVi.ifBlank { "Điểm tham quan văn hóa lịch sử." },
                                    culturalStoryMarkdown = storyVi.ifBlank { summaryVi },
                                    historicalPeriod = initialVi?.historicalPeriod
                                ),
                                PoiTranslationInputDto(
                                    languageCode = "en",
                                    title = titleEn.ifBlank { titleVi },
                                    shortSummary = summaryEn.ifBlank { "Cultural and historical sightseeing landmark." },
                                    culturalStoryMarkdown = storyEn.ifBlank { summaryEn },
                                    historicalPeriod = initialEn?.historicalPeriod
                                )
                            ),
                            arInfoCards = if (initialPoi == null) listOf(
                                ArInfoCardInputDto(
                                    languageCode = "vi",
                                    title = arCardTitle,
                                    subtitle = "Azimuth ${azimuth.toInt()}°",
                                    bodyText = arCardBody,
                                    azimuthDegrees = azimuth
                                )
                            ) else emptyList()
                        ),
                        coverImageUri
                    )
                },
                enabled = canSubmit
            ) {
                Text(if (initialPoi == null) "Gửi phê duyệt" else "Lưu thay đổi")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )

    if (showCoordinatePicker) {
        val pickerLatitude = parsedLatitude?.takeIf { it in -90.0..90.0 }
            ?: defaultLatitude.takeIf { it.isFinite() && it in -90.0..90.0 }
            ?: 10.7769
        val pickerLongitude = parsedLongitude?.takeIf { it in -180.0..180.0 }
            ?: defaultLongitude.takeIf { it.isFinite() && it in -180.0..180.0 }
            ?: 106.7009
        PoiCoordinatePickerDialog(
            initialLatitude = pickerLatitude,
            initialLongitude = pickerLongitude,
            onDismiss = { showCoordinatePicker = false },
            onConfirm = { latitude, longitude ->
                latitudeText = formatGpsCoordinate(latitude)
                longitudeText = formatGpsCoordinate(longitude)
                showCoordinatePicker = false
            }
        )
    }
}

private fun formatGpsCoordinate(value: Double): String =
    java.lang.String.format(java.util.Locale.US, "%.6f", value)
