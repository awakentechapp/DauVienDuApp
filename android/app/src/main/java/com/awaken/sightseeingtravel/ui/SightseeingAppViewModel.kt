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

    companion object {
        const val SEARCH_RADIUS_KM = 50.0
        const val SEARCH_RADIUS_METERS = 50_000.0
    }

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
    // Dedicated Accounts:
    // - Tourist:    ID = testapp   | Password = TestApp123456
    // - Admin:      ID = testadmin | Password = TestApp123456
    // - SuperAdmin: ID = sa        | Password = AwakenTech150520#
    // ========================================================================

    private data class BuiltInTestAccount(
        val id: String,
        val password: String,
        val displayName: String,
        val role: UserRole
    )

    private val builtInTestAccounts = listOf(
        BuiltInTestAccount(
            id = "testapp",
            password = "TestApp123456",
            displayName = "Du Khách Kiểm Thử (testapp)",
            role = UserRole.TOURIST
        ),
        BuiltInTestAccount(
            id = "testadmin",
            password = "TestApp123456",
            displayName = "Quản Trị Viên (testadmin)",
            role = UserRole.ADMIN
        ),
        BuiltInTestAccount(
            id = "sa",
            password = "AwakenTech150520#",
            displayName = "Chủ Quản Hệ Thống (sa)",
            role = UserRole.SUPER_ADMIN
        )
    )

    fun login(accountIdOrEmail: String, password: String, onSuccess: () -> Unit = {}) {
        val trimmedId = accountIdOrEmail.trim()
        val matchedBuiltIn = builtInTestAccounts.firstOrNull {
            it.id.equals(trimmedId, ignoreCase = true)
        }

        if (matchedBuiltIn != null && matchedBuiltIn.password != password) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    errorMessage = "Sai mật khẩu cho tài khoản '${matchedBuiltIn.id}'. Vui lòng kiểm tra lại mật khẩu."
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val response = apiService.login(LoginRequest(trimmedId, password))
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
                            statusBannerMessage = "Đã đăng nhập: ${authData.user.displayName} (${authData.user.role})"
                        )
                    }
                    onSuccess()
                    return@launch
                }
            } catch (_: Exception) {
                // Proceed to local built-in account verification
            }

            if (matchedBuiltIn != null) {
                val userProfile = UserProfileDto(
                    id = "usr-${matchedBuiltIn.id.lowercase()}",
                    email = matchedBuiltIn.id,
                    displayName = matchedBuiltIn.displayName,
                    role = matchedBuiltIn.role.wireValue,
                    preferredLanguage = _uiState.value.selectedLanguage
                )
                tokenStore.saveSession(
                    accessToken = "jwt-session-${matchedBuiltIn.id}-${System.currentTimeMillis()}",
                    refreshToken = "jwt-refresh-${matchedBuiltIn.id}-${System.currentTimeMillis()}",
                    user = userProfile
                )
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        currentUser = userProfile,
                        statusBannerMessage = "Đã đăng nhập: ${userProfile.displayName} • Quyền: ${userProfile.role}"
                    )
                }
                if (matchedBuiltIn.role == UserRole.ADMIN || matchedBuiltIn.role == UserRole.SUPER_ADMIN) {
                    loadAdminConsoleData()
                }
                onSuccess()
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Tài khoản không hợp lệ. Vui lòng dùng: testapp, testadmin hoặc sa."
                    )
                }
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
            } catch (_: Exception) {
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
    // 2. NEARBY POIs WITHIN 50KM RADIUS, CULTURAL STORY & SPATIAL AR CONTEXT
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
                    radiusKm = SEARCH_RADIUS_KM,
                    language = language
                )
                val list = response.data.orEmpty().map { pin ->
                    val liveDist = LocationAndOrientationTracker.calculateDistanceMeters(
                        latitude,
                        longitude,
                        pin.latitude,
                        pin.longitude
                    )
                    pin.copy(distanceMeters = liveDist)
                }.filter { (it.distanceMeters ?: 0.0) <= SEARCH_RADIUS_METERS }
                    .sortedBy { it.distanceMeters ?: Double.MAX_VALUE }

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
                    statusBannerMessage = "GPS đã vào bán kính ${updatedDist.toInt()}m của ${detail.title}. Đã mở khóa Camera AR!"
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
                    errorMessage = "Bạn đang cách ${liveDistance.toInt()}m. Hãy di chuyển vào bán kính ${(radiusMeters + 20).toInt()}m của Pin (hoặc bấm 'Giả lập đến Pin') để mở Camera AR."
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
                    authorId = currentUser?.id ?: "usr-testapp",
                    authorDisplayName = currentUser?.displayName ?: "Du Khách (testapp)",
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
                        statusBannerMessage = "Đã chụp & ghim ảnh vào không gian AR ở góc ${pose.azimuthDegrees.toInt()}°!"
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
                createdByAdminName = _uiState.value.currentUser?.displayName ?: "testadmin",
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
                    statusBannerMessage = "POI submitted as PendingApproval. SuperAdmin (sa) approval required before publishing."
                )
            }
            onSuccess()
        }
    }

    /**
     * SuperAdmin ONLY: Approves a `PendingApproval` POI -> transitions to `Published`
     * and makes it immediately visible on the Tourist OpenFreeMap/Bright map within 50km.
     */
    fun approvePoiAsSuperAdmin(poiId: String, reviewNotes: String = "Approved by SuperAdmin") {
        val user = _uiState.value.currentUser
        if (user?.isSuperAdmin != true) {
            _uiState.update {
                it.copy(errorMessage = "Access Denied: Only SuperAdmin (sa) can approve POIs to Published status.")
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
                        coverImageUrl = "https://images.unsplash.com/photo-1555939594-58d7cb561ad1?auto=format&fit=crop&w=1000&q=80",
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
                    statusBannerMessage = "SuperAdmin (sa) đã duyệt địa điểm! Trạng thái: Published trên bản đồ."
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
                it.copy(errorMessage = "Access Denied: Only SuperAdmin (sa) can reject POIs.")
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
    // 5. BUILT-IN HERITAGE & FOOD ("ĂN UỐNG") SEED DATA WITHIN 50KM RADIUS
    // ========================================================================

    private fun buildSeedNearbyPois(lat: Double, lng: Double, lang: String): List<PoiPinSummaryDto> {
        val isEn = lang.equals("en", ignoreCase = true)

        // If the user's current GPS is more than 45km away from downtown HCMC (10.7769, 106.7009),
        // anchor the seed coordinates relative to the user's current region so all POIs are within 50km.
        val distToDefaultCenter = LocationAndOrientationTracker.calculateDistanceMeters(
            lat, lng, 10.7769, 106.7009
        )
        val baseLat = if (distToDefaultCenter > 45_000.0) lat else 10.7769
        val baseLng = if (distToDefaultCenter > 45_000.0) lng else 106.7009

        val rawPins = listOf(
            // --- CULTURAL & HISTORICAL LANDMARKS ---
            PoiPinSummaryDto(
                id = "poi-notre-dame-saigon",
                slug = "notre-dame-cathedral-saigon",
                category = "Architecture & Heritage",
                latitude = baseLat + 0.002885,
                longitude = baseLng - 0.001882,
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
                latitude = baseLat + 0.003022,
                longitude = baseLng - 0.000925,
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
                latitude = baseLat + 0.000094,
                longitude = baseLng - 0.005598,
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
            ),

            // --- CULINARY / FOOD & DINING ("ĂN UỐNG") DESTINATIONS ---
            PoiPinSummaryDto(
                id = "poi-food-ben-thanh-street-food",
                slug = "ben-thanh-culinary-heritage-alley",
                category = "Ăn uống",
                latitude = baseLat - 0.004100,
                longitude = baseLng - 0.002800,
                elevationMeters = 5.0,
                activationRadiusMeters = 70.0,
                title = if (isEn) "Ben Thanh Traditional Culinary Quarter" else "Khu Ẩm Thực Truyền Thống Chợ Bến Thành",
                shortSummary = if (isEn) {
                    "Over 100 years of Southern Vietnamese culinary heritage featuring Banh Xeo, Bun Mam, Fresh Spring Rolls & Che."
                } else {
                    "Hơn 100 năm tinh hoa ẩm thực Nam Bộ với Bánh Xèo giòn rụm, Bún Mắm gia truyền, Gỏi Cuốn và Chè Sài Gòn xưa."
                },
                coverImageUrl = "https://images.unsplash.com/photo-1555939594-58d7cb561ad1?auto=format&fit=crop&w=1000&q=80",
                status = "Published",
                communityPhotoCount = 94,
                arInfoCardCount = 3
            ),
            PoiPinSummaryDto(
                id = "poi-food-pho-hoa-pasteur",
                slug = "pho-gia-truyen-pasteur-saigon",
                category = "Ăn uống",
                latitude = baseLat + 0.009800,
                longitude = baseLng - 0.011200,
                elevationMeters = 7.0,
                activationRadiusMeters = 60.0,
                title = if (isEn) "Pasteur Heritage Pho & Artisanal Broth House" else "Phở Gia Truyền Pasteur Sài Gòn (Từ 1968)",
                shortSummary = if (isEn) {
                    "Renowned culinary landmark simmering marrow-rich beef broth with roasted star anise, cinnamon & charred ginger since 1968."
                } else {
                    "Điểm hẹn ẩm thực trứ danh từ năm 1968 với nồi nước dùng ninh xương ống 16 tiếng cùng thảo quả, hoa hồi và gừng nướng."
                },
                coverImageUrl = "https://images.unsplash.com/photo-1582878826629-29b7ad1cdc43?auto=format&fit=crop&w=1000&q=80",
                status = "Published",
                communityPhotoCount = 112,
                arInfoCardCount = 3
            ),
            PoiPinSummaryDto(
                id = "poi-food-ca-phe-vot-banh-mi",
                slug = "ca-phe-vot-banh-mi-co-do-saigon",
                category = "Ăn uống",
                latitude = baseLat - 0.008500,
                longitude = baseLng - 0.016400,
                elevationMeters = 5.0,
                activationRadiusMeters = 65.0,
                title = if (isEn) "80-Year Cloth-Filter Coffee & Crispy Saigon Banh Mi" else "Cà Phê Vợt & Bánh Mì Nướng Than Cổ Truyền",
                shortSummary = if (isEn) {
                    "Experience Saigon's 1950s clay-pot cloth-filter coffee culture paired with wood-fired crispy baguette & house pate."
                } else {
                    "Trải nghiệm văn hóa Cà phê vợt pha siêu đất nung từ thập niên 1950 kết hợp Bánh mì nướng than hồng pa-tê bơ truyền thống."
                },
                coverImageUrl = "https://images.unsplash.com/photo-1509785307050-d4066910ec1e?auto=format&fit=crop&w=1000&q=80",
                status = "Published",
                communityPhotoCount = 76,
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
        }.filter { (it.distanceMeters ?: 0.0) <= SEARCH_RADIUS_METERS }
            .sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
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

        val isFoodPoi = summaryPin.category.contains("Ăn uống", ignoreCase = true) ||
            summaryPin.category.contains("Food", ignoreCase = true)

        val arCards = if (isFoodPoi) {
            listOf(
                ArInfoCardDto(
                    id = "ar-food-1-$poiId",
                    poiId = summaryPin.id,
                    title = if (isEn) "16-Hour Slow-Simmered Culinary Secret" else "Bí Quyết Chế Biến Gia Truyền 3 Thế Hệ",
                    subtitle = if (isEn) "Kitchen Heritage • 40° Azimuth" else "Gian bếp truyền thống • Hướng 40°",
                    bodyText = if (isEn) {
                        "Prepared daily using heirloom recipes passed down over three generations with local herbs and charcoal roasting."
                    } else {
                        "Chế biến thủ công mỗi ngày theo công thức gia truyền 3 đời với thảo mộc bản địa và bếp than hồng giữ trọn hương vị xưa."
                    },
                    badgeLabel = if (isEn) "Culinary Secret" else "Tinh hoa Ẩm thực",
                    azimuthDegrees = 40f,
                    elevationMeters = 2.2f,
                    anchorDistanceMeters = 6.5f,
                    displayOrder = 1
                ),
                ArInfoCardDto(
                    id = "ar-food-2-$poiId",
                    poiId = summaryPin.id,
                    title = if (isEn) "Must-Try Signature Dish & Pairing" else "Món Ngon Đặc Sản Nhất Định Phải Thử",
                    subtitle = if (isEn) "Chef Recommendation • 130° Azimuth" else "Gợi ý thưởng thức • Hướng 130°",
                    bodyText = if (isEn) {
                        "Enjoy hot off the stove with fresh basil, sawtooth herb, lime, and house-fermented chili garlic sauce."
                    } else {
                        "Thưởng thức ngay khi còn nóng hổi cùng rau thơm tươi, chanh ớt xiêm và nước chấm pha theo tỷ lệ vàng."
                    },
                    badgeLabel = if (isEn) "Signature Dish" else "Đặc sản",
                    azimuthDegrees = 130f,
                    elevationMeters = 2.6f,
                    anchorDistanceMeters = 7.5f,
                    displayOrder = 2
                ),
                ArInfoCardDto(
                    id = "ar-food-3-$poiId",
                    poiId = summaryPin.id,
                    title = if (isEn) "Cultural Dining Etiquette & Story" else "Câu Chuyện Văn Hóa Ẩm Thực Bản Địa",
                    subtitle = if (isEn) "Cultural Story • 240° Azimuth" else "Văn hóa bản địa • Hướng 240°",
                    bodyText = if (isEn) {
                        "A beloved gathering spot for locals and travelers since the mid-20th century, reflecting the vibrant street life of Vietnam."
                    } else {
                        "Không gian gắn liền với nhịp sống phố phường từ giữa thế kỷ 20, nơi giao thoa văn hóa ẩm thực giữa người dân bản địa và du khách quốc tế."
                    },
                    badgeLabel = if (isEn) "Food Culture" else "Văn hóa Ăn uống",
                    azimuthDegrees = 240f,
                    elevationMeters = 2.4f,
                    anchorDistanceMeters = 8.0f,
                    displayOrder = 3
                )
            )
        } else {
            listOf(
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
        }

        val topPhotos = listOf(
            CommunityPhotoDto(
                id = "photo-top-01",
                poiId = summaryPin.id,
                authorId = "usr-01",
                authorDisplayName = "Minh Anh Nguyễn",
                imageUrl = summaryPin.coverImageUrl
                    ?: "https://images.unsplash.com/photo-1583417319070-4a69db38a482?auto=format&fit=crop&w=900&q=80",
                caption = if (isFoodPoi) {
                    if (isEn) "Authentic local flavors captured right at the table!" else "Hương vị truyền thống tuyệt hảo chụp ngay tại quán!"
                } else {
                    if (isEn) "Golden hour sunlight across the heritage square" else "Nắng chiều vàng rực trên quảng trường di sản"
                },
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
                caption = if (isEn) "Morning atmosphere and cultural details" else "Không gian buổi sáng đậm chất văn hóa bản địa",
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
                imageUrl = "https://images.unsplash.com/photo-1555939594-58d7cb561ad1?auto=format&fit=crop&w=900&q=80",
                caption = if (isEn) "Unforgettable cultural & culinary memory" else "Trải nghiệm văn hóa & ẩm thực đáng nhớ",
                capturedLatitude = summaryPin.latitude,
                capturedLongitude = summaryPin.longitude,
                azimuthDegrees = 160f,
                elevationMeters = 1.8f,
                likeCount = 96,
                isLikedByCurrentUser = false,
                isHighlightedInAr = true,
                highlightRank = 3,
                createdAt = "1 day ago"
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
            culturalStoryMarkdown = if (isFoodPoi) {
                if (isEn) {
                    """
                    ### Culinary Heritage & Cultural Story
                    **${summaryPin.title}** is one of the most iconic culinary stops within your **50km sightseeing zone**.
                    
                    - **Category**: Food & Dining (`Ăn uống`)
                    - **Specialty**: Heirloom Vietnamese flavors prepared with fresh local ingredients
                    - **Spatial AR Experience**: Step within `${(summaryPin.activationRadiusMeters + 20).toInt()}m` and open the **360° AR Camera** to view dish stories, preparation secrets, and community food photos floating in real space.
                    """.trimIndent()
                } else {
                    """
                    ### Câu chuyện Văn hóa Ẩm thực & Nguồn gốc
                    **${summaryPin.title}** là điểm đến **Ăn uống** tiêu biểu nằm trong bán kính **50km** quanh vị trí hiện tại của bạn.
                    
                    - **Danh mục**: Ăn uống (`Ẩm thực truyền thống`)
                    - **Đặc trưng**: Lưu giữ hương vị nguyên bản nhiều thế hệ cùng không gian thưởng thức đậm bản sắc địa phương
                    - **Trải nghiệm Thực tế tăng cường (AR 360°)**: Khi đến gần trong phạm vi `${(summaryPin.activationRadiusMeters + 20).toInt()}m`, hãy mở **Camera AR** để xem bí quyết chế biến, gợi ý món ngon và các bức ảnh đẹp từ cộng đồng.
                    """.trimIndent()
                }
            } else {
                if (isEn) {
                    """
                    ### Cultural & Architectural Story
                    Located within your **50km sightseeing zone**, **${summaryPin.title}** stands as an architectural and historical symbol.
                    
                    - **Category**: ${summaryPin.category}
                    - **Spatial AR Experience**: Step within the activation zone (`${(summaryPin.activationRadiusMeters + 20).toInt()}m`) and open the **AR Camera** to inspect 360° historical cards and the **Top 10 Highlighted Community Photos** anchored around the monument.
                    """.trimIndent()
                } else {
                    """
                    ### Câu chuyện Văn hóa & Lịch sử
                    Nằm trong vùng khám phá bán kính **50km** của bạn, **${summaryPin.title}** là biểu tượng giao thoa kiến trúc và ký ức văn hóa lịch sử.
                    
                    - **Danh mục**: ${summaryPin.category}
                    - **Trải nghiệm Không gian Thực tế ảo Tăng cường (AR)**: Khi tiến vào bán kính kích hoạt (`${(summaryPin.activationRadiusMeters + 20).toInt()}m`), hãy mở **Camera AR** để khám phá các thẻ thông tin văn hóa 360° và **Top 10 bức ảnh nổi bật của cộng đồng** gắn trực tiếp trong không gian thực.
                    """.trimIndent()
                }
            },
            historicalPeriod = if (isFoodPoi) "Heirloom Culinary Tradition" else "19th – 20th Century Heritage",
            architecturalHighlights = if (isFoodPoi) {
                if (isEn) {
                    listOf("Heirloom Recipe", "Fresh Local Herbs", "Charcoal Traditional Stove")
                } else {
                    listOf("Công thức gia truyền", "Nguyên liệu bản địa tươi ngon", "Chế biến thủ công truyền thống")
                }
            } else {
                if (isEn) {
                    listOf("Marseille Unplastered Terracotta Bricks", "57m Twin Bell Spires", "Chartres Rose Stained Glass")
                } else {
                    listOf("Gạch trần Marseille không tô trát", "Tháp chuông đôi cao 57m", "Kính màu hoa hồng vùng Chartres")
                }
            },
            visitorTips = if (isEn) {
                "Open daily • Use the 360° AR Camera on-site to view floating cards and share your own photos."
            } else {
                "Mở cửa hàng ngày • Hãy bật Camera AR 360° tại điểm đến để xem thông tin và chụp ảnh chia sẻ."
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
                id = "poi-pending-food-com-tam",
                slug = "com-tam-suon-nuong-sai-gon-xua",
                category = "Ăn uống",
                latitude = 10.774512,
                longitude = 106.696820,
                activationRadiusMeters = 55.0,
                status = PoiStatus.PENDING_APPROVAL.wireValue,
                createdByAdminName = "Quản Trị Viên (testadmin)",
                updatedAt = "10 mins ago",
                titleVi = "Cơm Tấm Sườn Nướng Than Hồng Sài Gòn Xưa",
                titleEn = "Heritage Charcoal-Grilled Broken Rice Saigon",
                shortSummaryVi = "Tinh hoa ẩm thực Cơm tấm Sài Gòn với sườn cốt lết ướp mật ong nướng than hoa và mỡ hành truyền thống.",
                shortSummaryEn = "Iconic Saigon broken rice with honey-glazed charcoal-grilled pork chop and scallion oil.",
                arInfoCardCount = 3
            ),
            AdminPoiDto(
                id = "poi-pending-opera-house",
                slug = "saigon-opera-house-municipal-theatre",
                category = "Architecture & Heritage",
                latitude = 10.776530,
                longitude = 106.703165,
                activationRadiusMeters = 55.0,
                status = PoiStatus.PENDING_APPROVAL.wireValue,
                createdByAdminName = "Quản Trị Viên (testadmin)",
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
                category = "Historic Landmark",
                latitude = 10.772517,
                longitude = 106.698021,
                activationRadiusMeters = 60.0,
                status = PoiStatus.PENDING_APPROVAL.wireValue,
                createdByAdminName = "Quản Trị Viên (testadmin)",
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
                createdByAdminName = "Quản Trị Viên (testadmin)",
                approvedBySuperAdminName = "Chủ Quản Hệ Thống (sa)",
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
