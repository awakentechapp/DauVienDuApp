package com.awaken.sightseeingtravel.ui.admin

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.awaken.sightseeingtravel.core.model.*

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
    defaultLatitude: Double,
    defaultLongitude: Double,
    onFilterStatusChange: (String?) -> Unit,
    onCreatePoiSubmit: (CreateOrUpdatePoiRequest) -> Unit,
    onSuperAdminApprovePoi: (String, String) -> Unit,
    onSuperAdminRejectPoi: (String, String) -> Unit,
    onModeratePhotoDecision: (String, String, String?) -> Unit,
    onBackToMap: () -> Unit
) {
    val isSuperAdmin = currentUser?.isSuperAdmin == true
    var activeTabIndex by remember { mutableIntStateOf(0) }
    var showCreatePoiDialog by remember { mutableStateOf(false) }
    var poiIdPendingRejection by remember { mutableStateOf<AdminPoiDto?>(null) }
    var rejectionReasonInput by remember { mutableStateOf("") }

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
                    onFilterStatusChange = onFilterStatusChange,
                    onApproveClick = { poi ->
                        onSuperAdminApprovePoi(poi.id, "Verified & approved by SuperAdmin")
                    },
                    onRejectClick = { poi ->
                        poiIdPendingRejection = poi
                        rejectionReasonInput = ""
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
            defaultLatitude = defaultLatitude,
            defaultLongitude = defaultLongitude,
            onDismiss = { showCreatePoiDialog = false },
            onSubmit = { request ->
                showCreatePoiDialog = false
                onCreatePoiSubmit(request)
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
    selectedStatusFilter: String?,
    onFilterStatusChange: (String?) -> Unit,
    onApproveClick: (AdminPoiDto) -> Unit,
    onRejectClick: (AdminPoiDto) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Role explanation banner
        Surface(
            color = if (isSuperAdmin) Color(0xFFDCFCE7) else Color(0xFFE0F2FE),
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
                    tint = if (isSuperAdmin) Color(0xFF15803D) else Color(0xFF0369A1)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (isSuperAdmin) {
                        "SuperAdmin Mode: You can Approve (Publish to Map) or Reject POIs submitted by Admins."
                    } else {
                        "Admin Mode: New or edited POIs enter 'PendingApproval' and require SuperAdmin approval before appearing on the Tourist Map."
                    },
                    color = if (isSuperAdmin) Color(0xFF14532D) else Color(0xFF0C4A6E),
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

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(adminPois, key = { it.id }) { poi ->
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
                                text = "Radius: ${poi.activationRadiusMeters.toInt()}m • ${poi.arInfoCardCount} AR Cards",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = poi.titleVi.ifBlank { poi.slug },
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        if (poi.titleEn.isNotBlank()) {
                            Text(
                                text = "EN: ${poi.titleEn}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = poi.shortSummaryVi.ifBlank { poi.shortSummaryEn },
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

@Composable
private fun CreatePoiDialog(
    defaultLatitude: Double,
    defaultLongitude: Double,
    onDismiss: () -> Unit,
    onSubmit: (CreateOrUpdatePoiRequest) -> Unit
) {
    var titleVi by remember { mutableStateOf("") }
    var titleEn by remember { mutableStateOf("") }
    var summaryVi by remember { mutableStateOf("") }
    var summaryEn by remember { mutableStateOf("") }
    var storyVi by remember { mutableStateOf("") }
    var storyEn by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Heritage Landmark") }
    var latitudeText by remember { mutableStateOf("%.5f".format(defaultLatitude)) }
    var longitudeText by remember { mutableStateOf("%.5f".format(defaultLongitude)) }
    var radiusText by remember { mutableStateOf("60") }
    var arCardTitle by remember { mutableStateOf("Architectural Highlight") }
    var arCardBody by remember { mutableStateOf("360° Spatial AR Cultural Note anchored at this landmark.") }
    var arCardAzimuth by remember { mutableStateOf("45") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Heritage POI (PendingApproval)", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category") },
                    modifier = Modifier.fillMaxWidth()
                )
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
                Divider(modifier = Modifier.padding(vertical = 4.dp))
                Text("Spatial AR Info Card", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                OutlinedTextField(
                    value = arCardTitle,
                    onValueChange = { arCardTitle = it },
                    label = { Text("AR Card Title") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = arCardAzimuth,
                    onValueChange = { arCardAzimuth = it },
                    label = { Text("AR Card Azimuth Degrees (0..360)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = arCardBody,
                    onValueChange = { arCardBody = it },
                    label = { Text("AR Card Description") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val slug = (titleEn.ifBlank { titleVi }).lowercase()
                        .replace(Regex("[^a-z0-9]+"), "-")
                        .trim('-')
                        .ifBlank { "poi-${System.currentTimeMillis()}" }

                    val lat = latitudeText.toDoubleOrNull() ?: defaultLatitude
                    val lng = longitudeText.toDoubleOrNull() ?: defaultLongitude
                    val radius = radiusText.toDoubleOrNull() ?: 60.0
                    val azimuth = arCardAzimuth.toFloatOrNull() ?: 45f

                    onSubmit(
                        CreateOrUpdatePoiRequest(
                            slug = slug,
                            category = category,
                            latitude = lat,
                            longitude = lng,
                            activationRadiusMeters = radius,
                            translations = listOf(
                                PoiTranslationInputDto(
                                    languageCode = "vi",
                                    title = titleVi.ifBlank { titleEn },
                                    shortSummary = summaryVi.ifBlank { "Điểm tham quan văn hóa lịch sử." },
                                    culturalStoryMarkdown = storyVi.ifBlank { summaryVi }
                                ),
                                PoiTranslationInputDto(
                                    languageCode = "en",
                                    title = titleEn.ifBlank { titleVi },
                                    shortSummary = summaryEn.ifBlank { "Cultural and historical sightseeing landmark." },
                                    culturalStoryMarkdown = storyEn.ifBlank { summaryEn }
                                )
                            ),
                            arInfoCards = listOf(
                                ArInfoCardInputDto(
                                    languageCode = "vi",
                                    title = arCardTitle,
                                    subtitle = "Azimuth ${azimuth.toInt()}°",
                                    bodyText = arCardBody,
                                    azimuthDegrees = azimuth
                                )
                            )
                        )
                    )
                },
                enabled = titleVi.isNotBlank() || titleEn.isNotBlank()
            ) {
                Text("Submit for SuperAdmin Approval")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
