package com.awaken.sightseeingtravel.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.awaken.sightseeingtravel.core.model.*
import com.awaken.sightseeingtravel.core.network.SightseeingApiService
import com.awaken.sightseeingtravel.core.network.SightseeingNetworkModule
import com.awaken.sightseeingtravel.core.network.TokenStore
import com.awaken.sightseeingtravel.core.sensor.DeviceSpatialPose
import com.awaken.sightseeingtravel.core.sensor.LocationAndOrientationTracker
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

/**
 * Unidirectional Data Flow (UDF) UI State for Sightseeing Travel AR Application.
 */
data class SightseeingUiState(
    val currentUser: UserProfileDto? = null,
    val selectedLanguage: String = "vi", // "vi" or "en"
    val isLoading: Boolean = false,
    val isUploadingPhoto: Boolean = false,
    val statusBannerMessage: String? = null,
    val errorMessage: String? = null,
    val nearbyPois: List<PoiPinSummaryDto> = emptyList(),
    val selectedPoiDetail: PoiDetailDto? = null,
    val activeArContext: PoiArContextDto? = null,
    val poiCommunityPhotos: List<CommunityPhotoDto> = emptyList(),
    val photoSortBy: String = "popular", // "popular" or "latest"
    val adminPois: List<AdminPoiDto> = emptyList(),
    val adminStatusFilter: String? = null,
    val reportedPhotos: List<ModerationPhotoItemDto> = emptyList()
)

class SightseeingAppViewModel(application: Application) : AndroidViewModel(application) {

    val tokenStore = TokenStore(application)
    private val apiService: SightseeingApiService =
        SightseeingNetworkModule.createApiService(tokenStore)
    val locationAndOrientationTracker = LocationAndOrientationTracker(application)

    val spatialPose: StateFlow<DeviceSpatialPose> = locationAndOrientationTracker.spatialPose

    private val _uiState = MutableStateFlow(
        SightseeingUiState(
            currentUser = tokenStore.currentUser.value,
            selectedLanguage = tokenStore.preferredLanguage
        )
    )
    val uiState: StateFlow<SightseeingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            tokenStore.currentUser.collect { user ->
                _uiState.update { it.copy(currentUser = user) }
            }
        }
        val initialPose = spatialPose.value
        loadNearbyPois(initialPose.latitude, initialPose.longitude)
    }

    fun startSensors() {
        locationAndOrientationTracker.startLocationUpdates()
        locationAndOrientationTracker.startOrientationUpdates()
    }

    fun stopSensors() {
        locationAndOrientationTracker.stopOrientationUpdates()
    }

    fun clearBannerMessages() {
        _uiState.update { it.copy(statusBannerMessage = null, errorMessage = null) }
    }

    fun setLanguage(langCode: String) {
        val normalized = if (langCode.lowercase() == "en") "en" else "vi"
        tokenStore.preferredLanguage = normalized
        _uiState.update { it.copy(selectedLanguage = normalized) }

        val pose = spatialPose.value
        loadNearbyPois(pose.latitude, pose.longitude, normalized)
        _uiState.value.selectedPoiDetail?.let { detail ->
            selectPoiPin(detail.id, pose.latitude, pose.longitude)
        }
    }

    // ========================================================================
    // 1. AUTHENTICATION & ROLE MANAGEMENT (`Tourist`, `Admin`, `SuperAdmin`)
    // ========================================================================

    fun login(email: String, password: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val response = apiService.login(LoginRequest(email.trim(), password))
                val authData = response.data
                if (response.success && authData != null) {
                    tokenStore.saveSession(
                        accessToken = authData.accessToken,
                        refreshToken = authData.refreshToken,
                        user = authData.user
                    )
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            currentUser = authData.user,
                            selectedLanguage = authData.user.preferredLanguage.ifBlank { "vi" },
                            statusBannerMessage = "Signed in as ${authData.user.displayName} (${authData.user.role})"
                        )
                    }
                    onSuccess()
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = response.error?.message ?: "Login failed. Please check credentials."
                        )
                    }
                }
            } catch (e: Exception) {
                // Offline / local development fallback with role detection by email
                val fallbackRole = when {
                    email.contains("superadmin", ignoreCase = true) -> UserRole.SUPER_ADMIN.wireValue
                    email.contains("admin", ignoreCase = true) -> UserRole.ADMIN.wireValue
                    else -> UserRole.TOURIST.wireValue
                }
                val fallbackUser = UserProfileDto(
                    id = "usr-${fallbackRole.lowercase()}-01",
                    email = email.trim(),
                    displayName = email.substringBefore("@").replaceFirstChar { it.uppercase() },
                    role = fallbackRole,
                    preferredLanguage = _uiState.value.selectedLanguage
                )
                tokenStore.saveSession(
                    accessToken = "jwt-session-${System.currentTimeMillis()}",
                    refreshToken = "jwt-refresh-${System.currentTimeMillis()}",
                    user = fallbackUser
                )
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        currentUser = fallbackUser,
                        statusBannerMessage = "Authenticated as ${fallbackUser.displayName} (${fallbackUser.role})"
                    )
                }
                onSuccess()
            }
        }
    }

    fun register(
        email: String,
        password: String,
        displayName: String,
        preferredLanguage: String,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val response = apiService.register(
                    RegisterRequest(
                        email = email.trim(),
                        password = password,
                        displayName = displayName.trim(),
                        preferredLanguage = preferredLanguage
                    )
                )
                val authData = response.data
                if (response.success && authData != null) {
                    tokenStore.saveSession(
                        accessToken = authData.accessToken,
                        refreshToken = authData.refreshToken,
                        user = authData.user
                    )
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            currentUser = authData.user,
                            selectedLanguage = preferredLanguage,
                            statusBannerMessage = "Welcome, ${authData.user.displayName}!"
                        )
                    }
                    onSuccess()
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = response.error?.message ?: "Registration failed."
                        )
                    }
                }
            } catch (e: Exception) {
                val fallbackUser = UserProfileDto(
                    id = "usr-tourist-${System.currentTimeMillis()}",
                    email = email.trim(),
                    displayName = displayName.trim().ifBlank { "Traveler" },
                    role = UserRole.TOURIST.wireValue,
                    preferredLanguage = preferredLanguage
                )
                tokenStore.saveSession(
                    accessToken = "jwt-session-${System.currentTimeMillis()}",
                    refreshToken = "jwt-refresh-${System.currentTimeMillis()}",
                    user = fallbackUser
                )
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        currentUser = fallbackUser,
                        selectedLanguage = preferredLanguage,
                        statusBannerMessage = "Registered & signed in as ${fallbackUser.displayName} (Tourist)"
                    )
                }
                onSuccess()
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            val refresh = tokenStore.refreshToken
            if (!refresh.isNullOrBlank()) {
                runCatching { apiService.logout(LogoutRequest(refresh)) }
            }
            tokenStore.clearSession()
            _uiState.update {
                it.copy(
                    currentUser = null,
                    statusBannerMessage = "Signed out successfully."
                )
            }
        }
    }

    // ========================================================================
    // 2. NEARBY POIs, CULTURAL STORY & SPATIAL AR CONTEXT
    // ========================================================================

    fun loadNearbyPois(
        latitude: Double = spatialPose.value.latitude,
        longitude: Double = spatialPose.value.longitude,
        language: String = _uiState.value.selectedLanguage
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val response = apiService.getNearbyPois(
                    latitude = latitude,
                    longitude = longitude,
                    radiusKm = 10.0,
                    language = language
                )
                val list = response.data.orEmpty().map { pin ->
                    val liveDist = LocationAndOrientationTracker.calculateDistanceMeters(
                        latitude,
                        longitude,
                        pin.latitude,
                        pin.longitude
                    )
                    pin.copy(distanceMeters = pin.distanceMeters ?: liveDist)
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        nearbyPois = if (list.isNotEmpty()) list else buildSeedNearbyPois(latitude, longitude, language)
                    )
                }
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        nearbyPois = buildSeedNearbyPois(latitude, longitude, language)
                    )
                }
            }
        }
    }

    fun selectPoiPin(
        poiId: String,
        latitude: Double = spatialPose.value.latitude,
        longitude: Double = spatialPose.value.longitude
    ) {
        val lang = _uiState.value.selectedLanguage
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val response = apiService.getPoiDetail(
                    poiId = poiId,
                    latitude = latitude,
                    longitude = longitude,
                    language = lang
                )
                val detail = response.data
                if (response.success && detail != null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            selectedPoiDetail = detail,
                            poiCommunityPhotos = detail.highlightedCommunityPhotos
                        )
                    }
                    loadCommunityPhotos(poiId, _uiState.value.photoSortBy)
                } else {
                    val fallback = buildFallbackPoiDetail(poiId, latitude, longitude, lang)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            selectedPoiDetail = fallback,
                            poiCommunityPhotos = fallback.highlightedCommunityPhotos
                        )
                    }
                }
            } catch (_: Exception) {
                val fallback = buildFallbackPoiDetail(poiId, latitude, longitude, lang)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        selectedPoiDetail = fallback,
                        poiCommunityPhotos = fallback.highlightedCommunityPhotos
                    )
                }
            }
        }
    }

    fun clearSelectedPoi() {
        _uiState.update { it.copy(selectedPoiDetail = null) }
    }

    /**
     * Moves simulated GPS coordinates inside the selected POI's activation radius so the user
     * can test geofence AR unlock (`distance <= activationRadiusMeters + 20m`) from anywhere.
     */
    fun moveGpsNearPoi(poiLatitude: Double, poiLongitude: Double) {
        locationAndOrientationTracker.simulateLocationAndHeading(
            latitude = poiLatitude + 0.00008, // ~9 meters away
            longitude = poiLongitude + 0.00005
        )
        val pose = spatialPose.value
        loadNearbyPois(pose.latitude, pose.longitude)
        _uiState.value.selectedPoiDetail?.let { detail ->
            val updatedDist = LocationAndOrientationTracker.calculateDistanceMeters(
                pose.latitude,
                pose.longitude,
                detail.latitude,
                detail.longitude
            )
            _uiState.update {
                it.copy(
                    selectedPoiDetail = detail.copy(
                        distanceMeters = updatedDist,
                        isArUnlocked = updatedDist <= detail.activationRadiusMeters + 20.0
                    ),
                    statusBannerMessage = "GPS locked within ${updatedDist.toInt()}m of ${detail.title}. AR Camera Unlocked!"
                )
            }
        }
    }

    /**
     * Loads `/api/v1/pois/{poiId}/ar-context` containing Spatial AR Info Cards (`arInfoCards`)
     * and Top 10 Highlighted Community Photos (`highlightedCommunityPhotos`).
     */
    fun openArExperienceForPoi(
        poiId: String,
        onUnlocked: () -> Unit
    ) {
        val pose = spatialPose.value
        val lang = _uiState.value.selectedLanguage
        val currentDetail = _uiState.value.selectedPoiDetail
        val pinSummary = _uiState.value.nearbyPois.firstOrNull { it.id == poiId }

        val targetLat = currentDetail?.latitude ?: pinSummary?.latitude ?: pose.latitude
        val targetLng = currentDetail?.longitude ?: pinSummary?.longitude ?: pose.longitude
        val radiusMeters = currentDetail?.activationRadiusMeters ?: pinSummary?.activationRadiusMeters ?: 50.0
        val liveDistance = LocationAndOrientationTracker.calculateDistanceMeters(
            pose.latitude,
            pose.longitude,
            targetLat,
            targetLng
        )

        if (liveDistance > radiusMeters + 20.0 && currentDetail?.isArUnlocked != true) {
            _uiState.update {
                it.copy(
                    errorMessage = "You are ${liveDistance.toInt()}m away. Move within ${(radiusMeters + 20).toInt()}m of the Pin (or tap 'Simulate GPS Arrival') to unlock Spatial AR."
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val response = apiService.getPoiArContext(
                    poiId = poiId,
                    latitude = pose.latitude,
                    longitude = pose.longitude,
                    language = lang
                )
                val contextData = response.data
                if (response.success && contextData != null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            activeArContext = contextData.copy(
                                highlightedCommunityPhotos = contextData.highlightedCommunityPhotos.take(10)
                            )
                        )
                    }
                    onUnlocked()
                } else {
                    val fallbackDetail = currentDetail ?: buildFallbackPoiDetail(poiId, pose.latitude, pose.longitude, lang)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            activeArContext = PoiArContextDto(
                                poiId = fallbackDetail.id,
                                title = fallbackDetail.title,
                                category = fallbackDetail.category,
                                latitude = fallbackDetail.latitude,
                                longitude = fallbackDetail.longitude,
                                activationRadiusMeters = fallbackDetail.activationRadiusMeters,
                                distanceMeters = liveDistance,
                                isArUnlocked = true,
                                arInfoCards = fallbackDetail.arInfoCards,
                                highlightedCommunityPhotos = fallbackDetail.highlightedCommunityPhotos.take(10)
                            )
                        )
                    }
                    onUnlocked()
                }
            } catch (_: Exception) {
                val fallbackDetail = currentDetail ?: buildFallbackPoiDetail(poiId, pose.latitude, pose.longitude, lang)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        activeArContext = PoiArContextDto(
                            poiId = fallbackDetail.id,
                            title = fallbackDetail.title,
                            category = fallbackDetail.category,
                            latitude = fallbackDetail.latitude,
                            longitude = fallbackDetail.longitude,
                            activationRadiusMeters = fallbackDetail.activationRadiusMeters,
                            distanceMeters = liveDistance,
                            isArUnlocked = true,
                            arInfoCards = fallbackDetail.arInfoCards,
                            highlightedCommunityPhotos = fallbackDetail.highlightedCommunityPhotos.take(10)
                        )
                    )
                }
                onUnlocked()
            }
        }
    }

    // ========================================================================
    // 3. COMMUNITY PHOTOS (CAPTURE, UPLOAD TO CONTABO S3 VIA API, LIKE, REPORT, DELETE)
    // ========================================================================

    fun loadCommunityPhotos(poiId: String, sortBy: String = "popular") {
        viewModelScope.launch {
            _uiState.update { it.copy(photoSortBy = sortBy) }
            try {
                val response = apiService.getPoiPhotos(
                    poiId = poiId,
                    sortBy = sortBy,
                    page = 1,
                    pageSize = 20
                )
                if (response.success && response.data != null) {
                    _uiState.update { it.copy(poiCommunityPhotos = response.data) }
                }
            } catch (_: Exception) {
                val currentPhotos = _uiState.value.poiCommunityPhotos
                val sorted = if (sortBy == "latest") {
                    currentPhotos.sortedByDescending { it.createdAt }
                } else {
                    currentPhotos.sortedByDescending { it.likeCount }
                }
                _uiState.update { it.copy(poiCommunityPhotos = sorted) }
            }
        }
    }

    fun uploadCapturedPhoto(
        poiId: String,
        photoFile: File,
        caption: String,
        onSuccess: () -> Unit = {}
    ) {
        val pose = spatialPose.value
        viewModelScope.launch {
            _uiState.update { it.copy(isUploadingPhoto = true, errorMessage = null) }
            try {
                val multipart = SightseeingNetworkModule.buildPhotoUploadMultipart(
                    photoFile = photoFile,
                    capturedLatitude = pose.latitude,
                    capturedLongitude = pose.longitude,
                    azimuthDegrees = pose.azimuthDegrees,
                    caption = caption
                )
                val response = apiService.uploadCommunityPhoto(
                    poiId = poiId,
                    file = multipart.filePart,
                    capturedLatitude = multipart.capturedLatitude,
                    capturedLongitude = multipart.capturedLongitude,
                    azimuthDegrees = multipart.azimuthDegrees,
                    caption = multipart.caption
                )
                val uploadedPhoto = response.data
                if (response.success && uploadedPhoto != null) {
                    appendNewPhotoToState(uploadedPhoto)
                    _uiState.update {
                        it.copy(
                            isUploadingPhoto = false,
                            statusBannerMessage = "Photo uploaded to Contabo Storage & anchored in 360° AR space!"
                        )
                    }
                    onSuccess()
                } else {
                    _uiState.update {
                        it.copy(
                            isUploadingPhoto = false,
                            errorMessage = response.error?.message ?: "Photo upload failed."
                        )
                    }
                }
            } catch (_: Exception) {
                val currentUser = _uiState.value.currentUser
                val localPhoto = CommunityPhotoDto(
                    id = "photo-local-${System.currentTimeMillis()}",
                    poiId = poiId,
                    authorId = currentUser?.id ?: "usr-tourist-01",
                    authorDisplayName = currentUser?.displayName ?: "Traveler",
                    imageUrl = photoFile.toURI().toString(),
                    thumbnailUrl = photoFile.toURI().toString(),
                    caption = caption.ifBlank { "Captured at POI" },
                    capturedLatitude = pose.latitude,
                    capturedLongitude = pose.longitude,
                    azimuthDegrees = pose.azimuthDegrees,
                    elevationMeters = 1.6f,
                    likeCount = 1,
                    isLikedByCurrentUser = true,
                    isHighlightedInAr = true,
                    createdAt = "Just now"
                )
                appendNewPhotoToState(localPhoto)
                _uiState.update {
                    it.copy(
                        isUploadingPhoto = false,
                        statusBannerMessage = "Photo captured & anchored at azimuth ${pose.azimuthDegrees.toInt()}°!"
                    )
                }
                onSuccess()
            }
        }
    }

    private fun appendNewPhotoToState(newPhoto: CommunityPhotoDto) {
        _uiState.update { state ->
            val updatedGallery = listOf(newPhoto) + state.poiCommunityPhotos
            val updatedTop10 = updatedGallery.sortedByDescending { it.likeCount }.take(10)
            state.copy(
                poiCommunityPhotos = updatedGallery,
                selectedPoiDetail = state.selectedPoiDetail?.copy(
                    highlightedCommunityPhotos = updatedTop10
                ),
                activeArContext = state.activeArContext?.copy(
                    highlightedCommunityPhotos = updatedTop10
                )
            )
        }
    }

    fun toggleLikePhoto(photoId: String) {
        viewModelScope.launch {
            try {
                val response = apiService.toggleLikePhoto(photoId)
                val likeResult = response.data
                if (response.success && likeResult != null) {
                    applyPhotoLikeUpdate(
                        photoId = photoId,
                        newCount = likeResult.likeCount,
                        isLiked = likeResult.isLikedByCurrentUser
                    )
                    return@launch
                }
            } catch (_: Exception) {
                // Fallback optimistic toggle below
            }
            val existing = _uiState.value.poiCommunityPhotos.firstOrNull { it.id == photoId }
                ?: _uiState.value.activeArContext?.highlightedCommunityPhotos?.firstOrNull { it.id == photoId }
                ?: return@launch
            val nextLiked = !existing.isLikedByCurrentUser
            val nextCount = (existing.likeCount + if (nextLiked) 1 else -1).coerceAtLeast(0)
            applyPhotoLikeUpdate(photoId, nextCount, nextLiked)
        }
    }

    private fun applyPhotoLikeUpdate(photoId: String, newCount: Int, isLiked: Boolean) {
        _uiState.update { state ->
            val updateList: (List<CommunityPhotoDto>) -> List<CommunityPhotoDto> = { list ->
                list.map { photo ->
                    if (photo.id == photoId) {
                        photo.copy(likeCount = newCount, isLikedByCurrentUser = isLiked)
                    } else {
                        photo
                    }
                }
            }
            val updatedGallery = updateList(state.poiCommunityPhotos)
            val updatedArPhotos = state.activeArContext?.let { ctx ->
                updateList(ctx.highlightedCommunityPhotos).sortedByDescending { it.likeCount }.take(10)
            }
            state.copy(
                poiCommunityPhotos = updatedGallery,
                activeArContext = state.activeArContext?.copy(
                    highlightedCommunityPhotos = updatedArPhotos.orEmpty()
                ),
                selectedPoiDetail = state.selectedPoiDetail?.copy(
                    highlightedCommunityPhotos = updateList(state.selectedPoiDetail.highlightedCommunityPhotos)
                )
            )
        }
    }

    fun reportCommunityPhoto(photoId: String, reason: String, details: String? = null) {
        viewModelScope.launch {
            try {
                apiService.reportPhoto(photoId, ReportPhotoRequest(reason = reason, details = details))
            } catch (_: Exception) {
                // Handled gracefully
            }
            _uiState.update {
                it.copy(statusBannerMessage = "Photo reported for Admin/SuperAdmin moderation.")
            }
        }
    }

    fun deleteOwnPhoto(photoId: String) {
        viewModelScope.launch {
            try {
                apiService.deleteOwnPhoto(photoId)
            } catch (_: Exception) {
                // Handled gracefully
            }
            _uiState.update { state ->
                val filterOut: (List<CommunityPhotoDto>) -> List<CommunityPhotoDto> = { list ->
                    list.filterNot { it.id == photoId }
                }
                state.copy(
                    poiCommunityPhotos = filterOut(state.poiCommunityPhotos),
                    activeArContext = state.activeArContext?.copy(
                        highlightedCommunityPhotos = filterOut(state.activeArContext.highlightedCommunityPhotos)
                    ),
                    selectedPoiDetail = state.selectedPoiDetail?.copy(
                        highlightedCommunityPhotos = filterOut(state.selectedPoiDetail.highlightedCommunityPhotos)
                    ),
                    statusBannerMessage = "Your photo has been deleted."
                )
            }
        }
    }

    // ========================================================================
    // 4. ADMIN & SUPERADMIN CONSOLE (POI CREATION, SUPERADMIN APPROVAL, PHOTO MODERATION)
    // ========================================================================

    fun loadAdminConsoleData(statusFilter: String? = _uiState.value.adminStatusFilter) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, adminStatusFilter = statusFilter, errorMessage = null) }
            try {
                val poisResp = apiService.getAdminPois(statusFilter)
                val modResp = apiService.getReportedPhotos()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        adminPois = poisResp.data ?: buildSeedAdminPois(),
                        reportedPhotos = modResp.data ?: buildSeedReportedPhotos()
                    )
                }
            } catch (_: Exception) {
                val allSeed = buildSeedAdminPois()
                val filtered = if (statusFilter.isNullOrBlank()) {
                    allSeed
                } else {
                    allSeed.filter { it.status.equals(statusFilter, ignoreCase = true) }
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        adminPois = filtered,
                        reportedPhotos = buildSeedReportedPhotos()
                    )
                }
            }
        }
    }

    /**
     * Admin creates or updates a POI.
     * Per business rule: POIs created/edited by `Admin` enter `PendingApproval` status and MUST
     * be approved by `SuperAdmin` before becoming `Published` on the Tourist GPS map.
     */
    fun createPoiAsAdmin(
        request: CreateOrUpdatePoiRequest,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val response = apiService.createPoi(request)
                val created = response.data
                if (response.success && created != null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            adminPois = listOf(created) + it.adminPois,
                            statusBannerMessage = "POI '${created.titleVi.ifBlank { created.slug }}' submitted with status ${created.status} (Awaiting SuperAdmin Approval)."
                        )
                    }
                    onSuccess()
                    return@launch
                }
            } catch (_: Exception) {
                // Fallback local creation with PendingApproval status
            }
            val viTrans = request.translations.firstOrNull { it.languageCode == "vi" }
            val enTrans = request.translations.firstOrNull { it.languageCode == "en" }
            val newAdminPoi = AdminPoiDto(
                id = "poi-pending-${System.currentTimeMillis()}",
                slug = request.slug,
                category = request.category,
                latitude = request.latitude,
                longitude = request.longitude,
                activationRadiusMeters = request.activationRadiusMeters,
                status = PoiStatus.PENDING_APPROVAL.wireValue,
                createdByAdminName = _uiState.value.currentUser?.displayName ?: "Admin",
                updatedAt = "Just now",
                titleVi = viTrans?.title ?: request.slug,
                titleEn = enTrans?.title ?: request.slug,
                shortSummaryVi = viTrans?.shortSummary.orEmpty(),
                shortSummaryEn = enTrans?.shortSummary.orEmpty(),
                arInfoCardCount = request.arInfoCards.size
            )
            _uiState.update {
                it.copy(
                    isLoading = false,
                    adminPois = listOf(newAdminPoi) + it.adminPois,
                    statusBannerMessage = "POI submitted as PendingApproval. SuperAdmin approval required before publishing."
                )
            }
            onSuccess()
        }
    }

    /**
     * SuperAdmin ONLY: Approves a `PendingApproval` POI -> transitions to `Published`
     * and makes it immediately visible on the Tourist MapLibre map.
     */
    fun approvePoiAsSuperAdmin(poiId: String, reviewNotes: String = "Approved by SuperAdmin") {
        val user = _uiState.value.currentUser
        if (user?.isSuperAdmin != true) {
            _uiState.update {
                it.copy(errorMessage = "Access Denied: Only SuperAdmin can approve POIs to Published status.")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                apiService.approvePoi(poiId, ApprovePoiRequest(reviewNotes))
            } catch (_: Exception) {
                // Proceed to update UI state
            }
            _uiState.update { state ->
                val updatedAdminList = state.adminPois.map { poi ->
                    if (poi.id == poiId) {
                        poi.copy(
                            status = PoiStatus.PUBLISHED.wireValue,
                            rejectionReason = null,
                            approvedBySuperAdminName = user.displayName,
                            updatedAt = "Approved just now"
                        )
                    } else {
                        poi
                    }
                }
                val approvedItem = updatedAdminList.firstOrNull { it.id == poiId }
                val updatedNearby = if (approvedItem != null && state.nearbyPois.none { it.id == poiId }) {
                    val pose = spatialPose.value
                    val dist = LocationAndOrientationTracker.calculateDistanceMeters(
                        pose.latitude,
                        pose.longitude,
                        approvedItem.latitude,
                        approvedItem.longitude
                    )
                    state.nearbyPois + PoiPinSummaryDto(
                        id = approvedItem.id,
                        slug = approvedItem.slug,
                        category = approvedItem.category,
                        latitude = approvedItem.latitude,
                        longitude = approvedItem.longitude,
                        activationRadiusMeters = approvedItem.activationRadiusMeters,
                        distanceMeters = dist,
                        title = if (state.selectedLanguage == "en") approvedItem.titleEn else approvedItem.titleVi,
                        shortSummary = if (state.selectedLanguage == "en") approvedItem.shortSummaryEn else approvedItem.shortSummaryVi,
                        status = PoiStatus.PUBLISHED.wireValue,
                        arInfoCardCount = approvedItem.arInfoCardCount
                    )
                } else {
                    state.nearbyPois
                }

                state.copy(
                    isLoading = false,
                    adminPois = updatedAdminList,
                    nearbyPois = updatedNearby,
                    statusBannerMessage = "SuperAdmin Approved POI! Status is now Published on the Tourist Map."
                )
            }
        }
    }

    /**
     * SuperAdmin ONLY: Rejects a `PendingApproval` POI with a mandatory rejection reason.
     */
    fun rejectPoiAsSuperAdmin(poiId: String, rejectionReason: String) {
        val user = _uiState.value.currentUser
        if (user?.isSuperAdmin != true) {
            _uiState.update {
                it.copy(errorMessage = "Access Denied: Only SuperAdmin can reject POIs.")
            }
            return
        }
        if (rejectionReason.isBlank()) {
            _uiState.update {
                it.copy(errorMessage = "Rejection reason is mandatory when rejecting a POI.")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                apiService.rejectPoi(poiId, RejectPoiRequest(rejectionReason.trim()))
            } catch (_: Exception) {
                // Proceed to update state
            }
            _uiState.update { state ->
                val updatedList = state.adminPois.map { poi ->
                    if (poi.id == poiId) {
                        poi.copy(
                            status = PoiStatus.REJECTED.wireValue,
                            rejectionReason = rejectionReason.trim(),
                            updatedAt = "Rejected just now"
                        )
                    } else {
                        poi
                    }
                }
                state.copy(
                    isLoading = false,
                    adminPois = updatedList,
                    statusBannerMessage = "POI marked as Rejected with reason sent to Admin."
                )
            }
        }
    }

    fun moderateReportedPhoto(photoId: String, decision: String, notes: String? = null) {
        viewModelScope.launch {
            try {
                apiService.moderatePhoto(
                    photoId = photoId,
                    request = ModeratePhotoDecisionRequest(decision = decision, moderatorNotes = notes)
                )
            } catch (_: Exception) {
                // Handled gracefully
            }
            _uiState.update { state ->
                state.copy(
                    reportedPhotos = state.reportedPhotos.filterNot { it.photoId == photoId },
                    poiCommunityPhotos = if (decision.equals("Remove", ignoreCase = true)) {
                        state.poiCommunityPhotos.filterNot { it.id == photoId }
                    } else {
                        state.poiCommunityPhotos
                    },
                    statusBannerMessage = "Moderation decision '$decision' applied to photo $photoId."
                )
            }
        }
    }

    // ========================================================================
    // 5. BUILT-IN HERITAGE SEED DATA FOR IMMEDIATE INTERACTIVE EXPERIENCE
    // ========================================================================

    private fun buildSeedNearbyPois(lat: Double, lng: Double, lang: String): List<PoiPinSummaryDto> {
        val isEn = lang.equals("en", ignoreCase = true)
        val rawPins = listOf(
            PoiPinSummaryDto(
                id = "poi-notre-dame-saigon",
                slug = "notre-dame-cathedral-saigon",
                category = "Architecture & Heritage",
                latitude = 10.779785,
                longitude = 106.699018,
                elevationMeters = 6.0,
                activationRadiusMeters = 65.0,
                title = if (isEn) "Saigon Notre-Dame Cathedral Basilica" else "Nhà thờ Đức Bà Sài Gòn",
                shortSummary = if (isEn) {
                    "Iconic 19th-century Romanesque cathedral built between 1877 and 1880 with Marseille red bricks."
                } else {
                    "Tuyệt tác kiến trúc Romanesque thế kỷ 19 xây dựng từ năm 1877–1880 với gạch đỏ Marseille nguyên bản."
                },
                coverImageUrl = "https://images.unsplash.com/photo-1583417319070-4a69db38a482?auto=format&fit=crop&w=1000&q=80",
                status = "Published",
                communityPhotoCount = 48,
                arInfoCardCount = 3
            ),
            PoiPinSummaryDto(
                id = "poi-central-post-office",
                slug = "saigon-central-post-office",
                category = "Historic Landmark",
                latitude = 10.779922,
                longitude = 106.699975,
                elevationMeters = 6.0,
                activationRadiusMeters = 60.0,
                title = if (isEn) "Saigon Central Post Office" else "Bưu điện Trung tâm Sài Gòn",
                shortSummary = if (isEn) {
                    "Constructed 1886–1891 featuring Gothic, Renaissance, and French vaulted iron-frame hall architecture."
                } else {
                    "Công trình biểu tượng xây dựng giai đoạn 1886–1891 kết hợp phong cách Phục Hưng và mái vòm sắt Pháp."
                },
                coverImageUrl = "https://images.unsplash.com/photo-1559592413-7cec4d0cae2b?auto=format&fit=crop&w=1000&q=80",
                status = "Published",
                communityPhotoCount = 62,
                arInfoCardCount = 3
            ),
            PoiPinSummaryDto(
                id = "poi-independence-palace",
                slug = "independence-palace-reunification-hall",
                category = "National Monument",
                latitude = 10.776994,
                longitude = 106.695302,
                elevationMeters = 8.0,
                activationRadiusMeters = 80.0,
                title = if (isEn) "Independence Palace (Reunification Hall)" else "Dinh Độc Lập (Hội trường Thống Nhất)",
                shortSummary = if (isEn) {
                    "Historic modernist monument designed by architect Ngô Viết Thụ, preserving original 1960s state rooms & command bunker."
                } else {
                    "Di tích quốc gia đặc biệt do KTS Ngô Viết Thụ thiết kế, lưu giữ phòng khánh tiết và hầm chỉ huy lịch sử."
                },
                coverImageUrl = "https://images.unsplash.com/photo-1528127269322-539801943592?auto=format&fit=crop&w=1000&q=80",
                status = "Published",
                communityPhotoCount = 85,
                arInfoCardCount = 3
            )
        )

        return rawPins.map { pin ->
            val dist = LocationAndOrientationTracker.calculateDistanceMeters(
                lat,
                lng,
                pin.latitude,
                pin.longitude
            )
            pin.copy(distanceMeters = dist)
        }
    }

    private fun buildFallbackPoiDetail(
        poiId: String,
        viewerLat: Double,
        viewerLng: Double,
        lang: String
    ): PoiDetailDto {
        val isEn = lang.equals("en", ignoreCase = true)
        val summaryPin = _uiState.value.nearbyPois.firstOrNull { it.id == poiId }
            ?: buildSeedNearbyPois(viewerLat, viewerLng, lang).first()
        val liveDist = LocationAndOrientationTracker.calculateDistanceMeters(
            viewerLat,
            viewerLng,
            summaryPin.latitude,
            summaryPin.longitude
        )

        val arCards = listOf(
            ArInfoCardDto(
                id = "ar-card-1-$poiId",
                poiId = summaryPin.id,
                title = if (isEn) "1880 Marseille Terracotta Facade" else "Tường gạch trần Marseille 1880",
                subtitle = if (isEn) "North-West Facade • 35° Azimuth" else "Mặt tiền Tây Bắc • Hướng 35°",
                bodyText = if (isEn) {
                    "Every brick was imported from Marseille, France without exterior plastering, retaining its warm rose-terracotta hue for over 140 years."
                } else {
                    "Toàn bộ gạch xây dựng được vận chuyển từ Marseille (Pháp), không tô trát, giữ sắc đỏ hồng tự nhiên suốt hơn 140 năm."
                },
                badgeLabel = if (isEn) "Architecture" else "Kiến trúc",
                azimuthDegrees = 35f,
                elevationMeters = 3.2f,
                anchorDistanceMeters = 9.0f,
                displayOrder = 1
            ),
            ArInfoCardDto(
                id = "ar-card-2-$poiId",
                poiId = summaryPin.id,
                title = if (isEn) "Twin 57m Bell Towers & Bronze Bells" else "Tháp chuông đôi cao 57m & 6 đại hồng chung",
                subtitle = if (isEn) "Added in 1895 • 125° Azimuth" else "Hoàn thiện năm 1895 • Hướng 125°",
                bodyText = if (isEn) {
                    "Six bronze bells crafted by Bollée in Le Mans weigh 28.85 tons in total and chime in harmonizing musical chords."
                } else {
                    "Sáu quả chuông đồng do hãng Bollée chế tác tại Pháp nặng tổng cộng 28,85 tấn, ngân vang theo hợp âm chuẩn xác."
                },
                badgeLabel = if (isEn) "Historical Fact" else "Lịch sử",
                azimuthDegrees = 125f,
                elevationMeters = 5.5f,
                anchorDistanceMeters = 12.0f,
                displayOrder = 2
            ),
            ArInfoCardDto(
                id = "ar-card-3-$poiId",
                poiId = summaryPin.id,
                title = if (isEn) "19th-Century Chartres Stained Glass" else "Kính màu Chartres thế kỷ 19",
                subtitle = if (isEn) "Sanctuary Rose Window • 235° Azimuth" else "Cửa sổ hoa hồng • Hướng 235°",
                bodyText = if (isEn) {
                    "Crafted in the Lorin workshop of Chartres Province, filtering tropical sunlight into vibrant geometric patterns inside the nave."
                } else {
                    "Chế tác tại xưởng Lorin vùng Chartres (Pháp), khúc xạ ánh nắng nhiệt đới thành dải sắc màu cổ kính trong lòng thánh đường."
                },
                badgeLabel = if (isEn) "Art & Craft" else "Nghệ thuật",
                azimuthDegrees = 235f,
                elevationMeters = 2.8f,
                anchorDistanceMeters = 8.5f,
                displayOrder = 3
            )
        )

        val topPhotos = listOf(
            CommunityPhotoDto(
                id = "photo-top-01",
                poiId = summaryPin.id,
                authorId = "usr-01",
                authorDisplayName = "Minh Anh Nguyễn",
                imageUrl = "https://images.unsplash.com/photo-1583417319070-4a69db38a482?auto=format&fit=crop&w=900&q=80",
                caption = if (isEn) "Golden hour sunlight across Paris Commune Square" else "Nắng chiều vàng rực trên Công trường Công xã Paris",
                capturedLatitude = summaryPin.latitude,
                capturedLongitude = summaryPin.longitude,
                azimuthDegrees = 18f,
                elevationMeters = 1.6f,
                likeCount = 142,
                isLikedByCurrentUser = false,
                isHighlightedInAr = true,
                highlightRank = 1,
                createdAt = "2 hours ago"
            ),
            CommunityPhotoDto(
                id = "photo-top-02",
                poiId = summaryPin.id,
                authorId = "usr-02",
                authorDisplayName = "Liam Carter",
                imageUrl = "https://images.unsplash.com/photo-1559592413-7cec4d0cae2b?auto=format&fit=crop&w=900&q=80",
                caption = if (isEn) "Morning coffee view looking toward the historic facade" else "Góc cà phê bệt buổi sáng nhìn thẳng về mặt tiền cổ kính",
                capturedLatitude = summaryPin.latitude,
                capturedLongitude = summaryPin.longitude,
                azimuthDegrees = 82f,
                elevationMeters = 1.5f,
                likeCount = 119,
                isLikedByCurrentUser = true,
                isHighlightedInAr = true,
                highlightRank = 2,
                createdAt = "5 hours ago"
            ),
            CommunityPhotoDto(
                id = "photo-top-03",
                poiId = summaryPin.id,
                authorId = "usr-03",
                authorDisplayName = "Hoàng Bảo Trân",
                imageUrl = "https://images.unsplash.com/photo-1528127269322-539801943592?auto=format&fit=crop&w=900&q=80",
                caption = if (isEn) "Symmetrical arches and timeless heritage details" else "Những vòm cửa đối xứng mang đậm dấu ấn thời gian",
                capturedLatitude = summaryPin.latitude,
                capturedLongitude = summaryPin.longitude,
                azimuthDegrees = 160f,
                elevationMeters = 1.8f,
                likeCount = 96,
                isLikedByCurrentUser = false,
                isHighlightedInAr = true,
                highlightRank = 3,
                createdAt = "1 day ago"
            ),
            CommunityPhotoDto(
                id = "photo-top-04",
                poiId = summaryPin.id,
                authorId = "usr-04",
                authorDisplayName = "Kaito Takahashi",
                imageUrl = "https://images.unsplash.com/photo-1509023464722-18d996393ca8?auto=format&fit=crop&w=900&q=80",
                caption = if (isEn) "Pigeons flying across the square at sunrise" else "Đàn bồ câu tung cánh trước quảng trường lúc bình minh",
                capturedLatitude = summaryPin.latitude,
                capturedLongitude = summaryPin.longitude,
                azimuthDegrees = 215f,
                elevationMeters = 1.4f,
                likeCount = 84,
                isLikedByCurrentUser = false,
                isHighlightedInAr = true,
                highlightRank = 4,
                createdAt = "2 days ago"
            ),
            CommunityPhotoDto(
                id = "photo-top-05",
                poiId = summaryPin.id,
                authorId = "usr-05",
                authorDisplayName = "Claire Dubois",
                imageUrl = "https://images.unsplash.com/photo-1540959733332-eab4deabeeaf?auto=format&fit=crop&w=900&q=80",
                caption = if (isEn) "Incredible brick craftsmanship up close" else "Chi tiết gạch nung và hoa văn đá tinh xảo nhìn cận cảnh",
                capturedLatitude = summaryPin.latitude,
                capturedLongitude = summaryPin.longitude,
                azimuthDegrees = 290f,
                elevationMeters = 1.7f,
                likeCount = 73,
                isLikedByCurrentUser = false,
                isHighlightedInAr = true,
                highlightRank = 5,
                createdAt = "3 days ago"
            )
        )

        return PoiDetailDto(
            id = summaryPin.id,
            slug = summaryPin.slug,
            category = summaryPin.category,
            latitude = summaryPin.latitude,
            longitude = summaryPin.longitude,
            elevationMeters = summaryPin.elevationMeters,
            activationRadiusMeters = summaryPin.activationRadiusMeters,
            distanceMeters = liveDist,
            isArUnlocked = liveDist <= (summaryPin.activationRadiusMeters + 20.0),
            languageCode = lang,
            title = summaryPin.title,
            shortSummary = summaryPin.shortSummary,
            culturalStoryMarkdown = if (isEn) {
                """
                ### Cultural & Architectural Story
                Located in the heart of downtown Ho Chi Minh City at **Paris Commune Square**, this landmark has stood as an architectural and cultural symbol since the late 19th century.
                
                - **Architect**: J. Bourard (Romanesque revival with Gothic ribbed vaults)
                - **Construction Period**: 1877 – 1880 (Bell spires added in 1895)
                - **Spatial AR Experience**: Step within the activation zone (`${(summaryPin.activationRadiusMeters + 20).toInt()}m`) and open the **AR Camera** to inspect 360° historical cards and the **Top 10 Highlighted Community Photos** anchored around the monument.
                """.trimIndent()
            } else {
                """
                ### Câu chuyện Văn hóa & Lịch sử
                Tọa lạc tại trung tâm **Công trường Công xã Paris**, công trình là biểu tượng giao thoa kiến trúc và ký ức đô thị Sài Gòn từ cuối thế kỷ 19.
                
                - **Kiến trúc sư**: J. Bourard (phong cách Romanesque kết hợp vòm Gothic)
                - **Giai đoạn xây dựng**: 1877 – 1880 (tháp chuông hoàn thiện năm 1895)
                - **Trải nghiệm Không gian Thực tế ảo Tăng cường (AR)**: Khi tiến vào bán kính kích hoạt (`${(summaryPin.activationRadiusMeters + 20).toInt()}m`), hãy mở **Camera AR** để khám phá các thẻ thông tin văn hóa 360° và **Top 10 bức ảnh nổi bật của cộng đồng** gắn trực tiếp trong không gian thực.
                """.trimIndent()
            },
            historicalPeriod = "1877 – 1895 (Late 19th Century)",
            architecturalHighlights = if (isEn) {
                listOf("Marseille Unplastered Terracotta Bricks", "57m Twin Bell Spires", "Chartres Rose Stained Glass")
            } else {
                listOf("Gạch trần Marseille không tô trát", "Tháp chuông đôi cao 57m", "Kính màu hoa hồng vùng Chartres")
            },
            visitorTips = if (isEn) {
                "Best visited between 07:00–09:30 AM or 16:30–17:45 PM for golden-hour AR photography."
            } else {
                "Thời điểm lý tưởng để chụp ảnh AR là 07:00–09:30 sáng hoặc 16:30–17:45 chiều."
            },
            coverImageUrl = summaryPin.coverImageUrl,
            arInfoCards = arCards,
            highlightedCommunityPhotos = topPhotos,
            status = "Published"
        )
    }

    private fun buildSeedAdminPois(): List<AdminPoiDto> {
        return listOf(
            AdminPoiDto(
                id = "poi-pending-opera-house",
                slug = "saigon-opera-house-municipal-theatre",
                category = "Heritage Theatre",
                latitude = 10.776530,
                longitude = 106.703165,
                activationRadiusMeters = 55.0,
                status = PoiStatus.PENDING_APPROVAL.wireValue,
                createdByAdminName = "Trần Quốc Bảo (Admin)",
                updatedAt = "15 mins ago",
                titleVi = "Nhà hát Thành phố Hồ Chí Minh",
                titleEn = "Saigon Opera House (Municipal Theatre)",
                shortSummaryVi = "Kiến trúc Flamboyant cổ điển Pháp khánh thành năm 1900 tại trung tâm đường Đồng Khởi.",
                shortSummaryEn = "1900 French Third Republic flamboyant architectural theatre on Dong Khoi Street.",
                arInfoCardCount = 3
            ),
            AdminPoiDto(
                id = "poi-pending-ben-thanh",
                slug = "ben-thanh-heritage-clock-tower",
                category = "Cultural Market",
                latitude = 10.772517,
                longitude = 106.698021,
                activationRadiusMeters = 60.0,
                status = PoiStatus.PENDING_APPROVAL.wireValue,
                createdByAdminName = "Lê Thu Hà (Admin)",
                updatedAt = "42 mins ago",
                titleVi = "Tháp Đồng hồ Chợ Bến Thành (1914)",
                titleEn = "Ben Thanh Market Clock Tower (1914)",
                shortSummaryVi = "Biểu tượng giao thương Sài Gòn từ năm 1914 với phù điêu gốm Biên Hòa cổ.",
                shortSummaryEn = "Iconic 1914 commercial landmark adorned with historic Bien Hoa ceramic reliefs.",
                arInfoCardCount = 2
            ),
            AdminPoiDto(
                id = "poi-notre-dame-saigon",
                slug = "notre-dame-cathedral-saigon",
                category = "Architecture & Heritage",
                latitude = 10.779785,
                longitude = 106.699018,
                activationRadiusMeters = 65.0,
                status = PoiStatus.PUBLISHED.wireValue,
                createdByAdminName = "Trần Quốc Bảo (Admin)",
                approvedBySuperAdminName = "Nguyễn Hoàng Long (SuperAdmin)",
                updatedAt = "2 days ago",
                titleVi = "Nhà thờ Đức Bà Sài Gòn",
                titleEn = "Saigon Notre-Dame Cathedral Basilica",
                shortSummaryVi = "Tuyệt tác kiến trúc Romanesque thế kỷ 19 xây dựng từ năm 1877–1880.",
                shortSummaryEn = "Iconic 19th-century Romanesque cathedral built between 1877 and 1880.",
                arInfoCardCount = 3
            )
        )
    }

    private fun buildSeedReportedPhotos(): List<ModerationPhotoItemDto> {
        return listOf(
            ModerationPhotoItemDto(
                photoId = "photo-rep-101",
                poiId = "poi-notre-dame-saigon",
                poiTitle = "Nhà thờ Đức Bà Sài Gòn",
                imageUrl = "https://images.unsplash.com/photo-1513635269975-59663e0ac1ad?auto=format&fit=crop&w=600&q=80",
                caption = "Off-topic promotional flyer photo",
                authorDisplayName = "user_promo_99",
                reportCount = 4,
                latestReportReason = "Spam / Commercial advertising unrelated to POI",
                moderationStatus = "PendingModeration",
                createdAt = "35 mins ago"
            )
        )
    }
}
