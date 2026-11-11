package com.awaken.sightseeingtravel.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.awaken.sightseeingtravel.BuildConfig
import com.awaken.sightseeingtravel.core.model.*
import com.awaken.sightseeingtravel.core.network.SightseeingApiService
import com.awaken.sightseeingtravel.core.network.SightseeingNetworkModule
import com.awaken.sightseeingtravel.core.network.TokenStore
import com.awaken.sightseeingtravel.core.sensor.DeviceSpatialPose
import com.awaken.sightseeingtravel.core.sensor.LocationAndOrientationTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import retrofit2.HttpException
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
    val navigationTarget: NavigationTarget? = null,
    val navigationTravelMode: NavigationTravelMode = NavigationTravelMode.CAR,
    val activeNavigationRoute: ActiveNavigationRoute? = null,
    val isCalculatingRoute: Boolean = false,
    val navigationError: String? = null,
    val activeArContext: PoiArContextDto? = null,
    val poiCommunityPhotos: List<CommunityPhotoDto> = emptyList(),
    val photoSortBy: String = "popular", // "popular" or "latest"
    val adminPois: List<AdminPoiDto> = emptyList(),
    val adminArCardsByPoi: Map<String, List<AdminArInfoCardDto>> = emptyMap(),
    val adminStatusFilter: String? = null,
    val reportedPhotos: List<ModerationPhotoItemDto> = emptyList()
)

data class NavigationTarget(
    val poiId: String,
    val title: String,
    val latitude: Double,
    val longitude: Double
)

enum class NavigationTravelMode(val apiValue: String) {
    WALKING("walking"),
    CAR("car"),
    MOTORCYCLE("motorcycle")
}

data class ActiveNavigationRoute(
    val routeId: Long,
    val target: NavigationTarget,
    val travelMode: NavigationTravelMode,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val coordinates: List<RouteCoordinateDto>
)

class SightseeingAppViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val SEARCH_RADIUS_KM = 50.0
        const val SEARCH_RADIUS_METERS = 50_000.0
    }

    val tokenStore = TokenStore(application)
    private val apiService: SightseeingApiService =
        SightseeingNetworkModule.createApiService(tokenStore)
    private var navigationJob: Job? = null
    private var lastNavigationRequestLocation: Pair<Double, Double>? = null
    private var lastNavigationRequestAtMillis: Long = 0L
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
        if (!tokenStore.accessToken.isNullOrBlank()) {
            loadNearbyPois(initialPose.latitude, initialPose.longitude)
        } else if (tokenStore.currentUser.value != null) {
            // A profile without its access token is not an authenticated session.
            tokenStore.clearSession()
        }

        viewModelScope.launch {
            spatialPose
                .map { it.latitude to it.longitude }
                .distinctUntilChanged()
                .collect {
                    maybeRefreshNavigationRoute()
                }
        }
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
    fun login(accountIdOrEmail: String, password: String, onSuccess: () -> Unit = {}) {
        val trimmedId = accountIdOrEmail.trim()
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
                    loadNearbyPois()
                    onSuccess()
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = response.error?.message ?: "Máy chủ không trả về phiên đăng nhập hợp lệ."
                    )
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Không thể đăng nhập. Vui lòng kiểm tra kết nối và thông tin tài khoản."
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
                    loadNearbyPois()
                    onSuccess()
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = response.error?.message ?: "Registration failed."
                        )
                    }
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Không thể đăng ký tài khoản. Vui lòng thử lại."
                    )
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            clearNavigation()
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
        if (tokenStore.accessToken.isNullOrBlank()) {
            _uiState.update { it.copy(isLoading = false) }
            return
        }

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
                        nearbyPois = if (list.isNotEmpty() || !BuildConfig.DEBUG) {
                            list
                        } else {
                            buildSeedNearbyPois(latitude, longitude, language)
                        }
                    )
                }
            } catch (error: Exception) {
                if ((error as? retrofit2.HttpException)?.code() == 401) {
                    tokenStore.clearSession()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            nearbyPois = emptyList(),
                            errorMessage = "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại."
                        )
                    }
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        nearbyPois = if (BuildConfig.DEBUG) buildSeedNearbyPois(latitude, longitude, language) else it.nearbyPois,
                        errorMessage = error.message ?: "Không thể tải địa điểm từ máy chủ."
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
                } else if (BuildConfig.DEBUG) {
                    val fallback = buildFallbackPoiDetail(poiId, latitude, longitude, lang)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            selectedPoiDetail = fallback,
                            poiCommunityPhotos = fallback.highlightedCommunityPhotos
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(isLoading = false, selectedPoiDetail = null, errorMessage = "Máy chủ không trả về chi tiết địa điểm.")
                    }
                }
            } catch (error: Exception) {
                if (BuildConfig.DEBUG) {
                    val fallback = buildFallbackPoiDetail(poiId, latitude, longitude, lang)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            selectedPoiDetail = fallback,
                            poiCommunityPhotos = fallback.highlightedCommunityPhotos
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(isLoading = false, selectedPoiDetail = null, errorMessage = error.message ?: "Không thể tải chi tiết địa điểm.")
                    }
                }
            }
        }
    }

    fun clearSelectedPoi() {
        _uiState.update { it.copy(selectedPoiDetail = null) }
    }

    fun calculateRouteToPin(
        poiId: String,
        title: String,
        latitude: Double,
        longitude: Double,
        travelMode: NavigationTravelMode = _uiState.value.navigationTravelMode
    ) {
        navigationJob?.cancel()
        val target = NavigationTarget(poiId, title, latitude, longitude)
        val pose = spatialPose.value
        val isEn = _uiState.value.selectedLanguage == "en"
        _uiState.update {
            it.copy(
                selectedPoiDetail = null,
                navigationTarget = target,
                navigationTravelMode = travelMode,
                activeNavigationRoute = null,
                isCalculatingRoute = false,
                navigationError = null
            )
        }

        if (!pose.hasFreshGpsFix || pose.isSimulatedLocation) {
            _uiState.update {
                it.copy(
                    navigationError = if (isEn) {
                        "Waiting for a real GPS location. Try again when the location dot appears."
                    } else {
                        "Đang chờ vị trí GPS thật. Hãy thử lại khi chấm vị trí của bạn xuất hiện trên bản đồ."
                    }
                )
            }
            return
        }

        if (tokenStore.accessToken.isNullOrBlank()) {
            _uiState.update {
                it.copy(navigationError = if (isEn) {
                    "Please sign in to calculate a route."
                } else {
                    "Vui lòng đăng nhập để tính tuyến đường."
                })
            }
            return
        }

        lastNavigationRequestLocation = pose.latitude to pose.longitude
        lastNavigationRequestAtMillis = System.currentTimeMillis()
        navigationJob = viewModelScope.launch {
            _uiState.update { it.copy(isCalculatingRoute = true, navigationError = null) }
            try {
                val response = apiService.calculateRoute(
                    CalculateRouteRequestDto(
                        originLatitude = pose.latitude,
                        originLongitude = pose.longitude,
                        destinationLatitude = target.latitude,
                        destinationLongitude = target.longitude,
                        travelMode = travelMode.apiValue
                    )
                )
                val route = response.data
                if (!response.success || route == null || route.coordinates.size < 2) {
                    _uiState.update {
                        it.copy(
                            isCalculatingRoute = false,
                            navigationError = if (it.selectedLanguage == "en") {
                                "No route was found for this travel mode."
                            } else {
                                "Không tìm thấy tuyến phù hợp cho phương tiện này."
                            }
                        )
                    }
                    return@launch
                }

                if (route.travelMode?.lowercase() != travelMode.apiValue &&
                    (route.travelMode != null || travelMode != NavigationTravelMode.CAR)
                ) {
                    _uiState.update {
                        it.copy(
                            isCalculatingRoute = false,
                            navigationError = if (it.selectedLanguage == "en") {
                                "The server needs the latest routing update for this travel mode."
                            } else {
                                "Máy chủ cần được cập nhật để tính tuyến cho phương tiện này."
                            }
                        )
                    }
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        isCalculatingRoute = false,
                        navigationError = null,
                        activeNavigationRoute = ActiveNavigationRoute(
                            routeId = System.currentTimeMillis(),
                            target = target,
                            travelMode = travelMode,
                            distanceMeters = route.distanceMeters,
                            durationSeconds = route.durationSeconds,
                            coordinates = route.coordinates
                        )
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val statusCode = (error as? retrofit2.HttpException)?.code()
                val useEnglishMessage = _uiState.value.selectedLanguage == "en"
                val message = when (statusCode) {
                    404 -> if (useEnglishMessage) "The routing API is unavailable on the server. Please update the server and try again." else "API định tuyến chưa có trên máy chủ. Vui lòng cập nhật máy chủ rồi thử lại."
                    422 -> if (useEnglishMessage) "No route was found for this travel mode." else "Không tìm thấy tuyến phù hợp cho phương tiện này."
                    503 -> if (useEnglishMessage) "The routing service is starting or temporarily unavailable. Please retry shortly." else "Dịch vụ tính tuyến đang khởi động hoặc tạm gián đoạn. Vui lòng thử lại sau."
                    401 -> if (useEnglishMessage) "Your session has expired. Please sign in again." else "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại."
                    else -> if (useEnglishMessage) "Could not calculate a route. Check your connection and try again." else "Chưa thể tính tuyến. Hãy kiểm tra kết nối rồi thử lại."
                }
                _uiState.update { it.copy(isCalculatingRoute = false, navigationError = message) }
            }
        }
    }

    fun selectNavigationTravelMode(travelMode: NavigationTravelMode) {
        val state = _uiState.value
        if (state.navigationTravelMode == travelMode) return

        _uiState.update {
            it.copy(
                navigationTravelMode = travelMode,
                activeNavigationRoute = null,
                navigationError = null
            )
        }

        val target = state.navigationTarget ?: return
        calculateRouteToPin(
            poiId = target.poiId,
            title = target.title,
            latitude = target.latitude,
            longitude = target.longitude,
            travelMode = travelMode
        )
    }

    private fun maybeRefreshNavigationRoute() {
        val state = _uiState.value
        val target = state.navigationTarget ?: return
        if (state.activeNavigationRoute == null || state.isCalculatingRoute) return

        val pose = spatialPose.value
        if (!pose.hasFreshGpsFix || pose.isSimulatedLocation) return

        val origin = lastNavigationRequestLocation ?: return
        val movedMeters = LocationAndOrientationTracker.calculateDistanceMeters(
            origin.first,
            origin.second,
            pose.latitude,
            pose.longitude
        )
        val now = System.currentTimeMillis()
        if (movedMeters < 120.0 || now - lastNavigationRequestAtMillis < 30_000L) return

        calculateRouteToPin(
            poiId = target.poiId,
            title = target.title,
            latitude = target.latitude,
            longitude = target.longitude,
            travelMode = state.navigationTravelMode
        )
    }

    fun recalculateNavigationRoute() {
        val target = _uiState.value.navigationTarget ?: return
        calculateRouteToPin(
            target.poiId,
            target.title,
            target.latitude,
            target.longitude,
            _uiState.value.navigationTravelMode
        )
    }

    fun clearNavigation() {
        navigationJob?.cancel()
        navigationJob = null
        lastNavigationRequestLocation = null
        lastNavigationRequestAtMillis = 0L
        _uiState.update {
            it.copy(
                navigationTarget = null,
                activeNavigationRoute = null,
                isCalculatingRoute = false,
                navigationError = null
            )
        }
    }

    /**
     * Moves simulated GPS coordinates inside the selected POI's activation radius so the user
     * can test geofence AR unlock (`distance <= activationRadiusMeters + 20m`) from anywhere.
     */
    fun moveGpsNearPoi(poiLatitude: Double, poiLongitude: Double) {
        if (!BuildConfig.DEBUG) return
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

        if (!pose.hasFreshGpsFix || pose.isSimulatedLocation) {
            _uiState.update {
                it.copy(
                    errorMessage = if (lang.equals("en", ignoreCase = true)) {
                        "Enable live GPS to open AR at this POI."
                    } else {
                        "Cần có tín hiệu GPS thực để mở AR tại địa điểm này. Hãy bật Vị trí và thử lại."
                    }
                )
            }
            return
        }

        if (liveDistance > radiusMeters + 20.0) {
            _uiState.update {
                it.copy(
                    errorMessage = if (lang.equals("en", ignoreCase = true)) {
                        "You are ${liveDistance.toInt()}m away. Move within ${(radiusMeters + 20).toInt()}m of this POI to open AR."
                    } else {
                        "Bạn đang cách ${liveDistance.toInt()}m. Hãy di chuyển vào bán kính ${(radiusMeters + 20).toInt()}m của điểm tham quan để mở Camera AR."
                    }
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
                } else if (BuildConfig.DEBUG) {
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
                } else {
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = response.error?.message ?: "Máy chủ không trả về dữ liệu AR.")
                    }
                }
            } catch (error: Exception) {
                if (!BuildConfig.DEBUG) {
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = error.message ?: "Không thể tải dữ liệu AR từ máy chủ.")
                    }
                    return@launch
                }
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
            } catch (error: Exception) {
                if (!BuildConfig.DEBUG) {
                    _uiState.update { it.copy(errorMessage = error.message ?: "Không thể tải ảnh từ máy chủ.") }
                    return@launch
                }
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
        anchor: PhotoAnchorSnapshot,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isUploadingPhoto = true, errorMessage = null) }
            try {
                val multipart = withContext(Dispatchers.IO) {
                    SightseeingNetworkModule.buildPhotoUploadMultipart(
                        photoFile = photoFile,
                        capturedLatitude = anchor.latitude,
                        capturedLongitude = anchor.longitude,
                        azimuthDegrees = anchor.azimuthDegrees,
                        pitchDegrees = anchor.pitchDegrees,
                        caption = caption
                    )
                }
                val response = apiService.uploadCommunityPhoto(
                    poiId = poiId,
                    file = multipart.filePart,
                    capturedLatitude = multipart.capturedLatitude,
                    capturedLongitude = multipart.capturedLongitude,
                    azimuthDegrees = multipart.azimuthDegrees,
                    pitchDegrees = multipart.pitchDegrees,
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
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isUploadingPhoto = false,
                        errorMessage = photoUploadErrorMessage(error)
                    )
                }
            }
        }
    }

    private fun photoUploadErrorMessage(error: Exception): String {
        val httpError = error as? HttpException
            ?: return error.message ?: "Không thể tải ảnh lên máy chủ."
        val responseBody = runCatching { httpError.response()?.errorBody()?.string() }.getOrNull()
        val serverMessage = runCatching {
            val problem = JSONObject(responseBody.orEmpty())
            problem.optString("detail")
                .ifBlank { problem.optString("message") }
                .ifBlank { problem.optString("title") }
        }.getOrNull()

        return serverMessage?.takeIf { it.isNotBlank() }
            ?: "Tải ảnh lên thất bại (HTTP ${httpError.code()})."
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
            } catch (error: Exception) {
                _uiState.update { it.copy(errorMessage = error.message ?: "Không thể cập nhật lượt thích.") }
                return@launch
            }
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
            } catch (error: Exception) {
                _uiState.update { it.copy(errorMessage = error.message ?: "Không thể gửi báo cáo ảnh.") }
                return@launch
            }
            _uiState.update {
                it.copy(statusBannerMessage = "Photo reported for Admin/SuperAdmin moderation.")
            }
        }
    }

    fun deleteOwnPhoto(photoId: String) {
        viewModelScope.launch {
            try {
                val response = apiService.deleteOwnPhoto(photoId)
                if (!response.isSuccessful) {
                    _uiState.update { it.copy(errorMessage = "Không thể xóa ảnh (HTTP ${response.code()}).") }
                    return@launch
                }
            } catch (error: Exception) {
                _uiState.update { it.copy(errorMessage = error.message ?: "Không thể xóa ảnh.") }
                return@launch
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
                val loadedPois = poisResp.data ?: emptyList()
                val cardsByPoi = coroutineScope {
                    loadedPois.map { poi ->
                        async {
                            val cards = try {
                                apiService.getAdminArInfoCards(poi.id).data.orEmpty()
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                emptyList()
                            }
                            poi.id to cards
                        }
                    }.awaitAll().toMap()
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        adminPois = loadedPois.map { poi ->
                            poi.copy(arInfoCardCount = cardsByPoi[poi.id]?.size ?: poi.arInfoCardCount)
                        },
                        adminArCardsByPoi = cardsByPoi,
                        reportedPhotos = modResp.data ?: emptyList()
                    )
                }
            } catch (error: Exception) {
                if (!BuildConfig.DEBUG) {
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = error.message ?: "Không thể tải dữ liệu quản trị.")
                    }
                    return@launch
                }
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
                        adminArCardsByPoi = emptyMap(),
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
        coverImageUri: Uri? = null,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val response = apiService.createPoi(request)
                val created = response.data
                if (response.success && created != null) {
                    val createdPoi: AdminPoiDto = created
                    val createdPoiId = createdPoi.id
                    val arCardError = createArCardsForPoi(createdPoiId, request.arInfoCards)
                    var savedPoi: AdminPoiDto = createdPoi
                    var coverError: String? = null
                    if (coverImageUri != null) {
                        try {
                            savedPoi = uploadPoiCover(createdPoiId, coverImageUri)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            coverError = "Địa điểm đã tạo nhưng ảnh thumbnail chưa tải lên: ${error.message ?: "lỗi máy chủ"}"
                        }
                    }
                    val cards = try {
                        apiService.getAdminArInfoCards(createdPoiId).data.orEmpty()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        emptyList()
                    }
                    val followUpError = listOfNotNull(coverError, arCardError).joinToString("\n").ifBlank { null }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            adminPois = listOf(savedPoi.copy(arInfoCardCount = cards.size)) + it.adminPois,
                            adminArCardsByPoi = it.adminArCardsByPoi + (createdPoiId to cards),
                            statusBannerMessage = if (followUpError == null) {
                                "POI '${savedPoi.titleFor("vi")}' submitted with status ${savedPoi.status} (Awaiting SuperAdmin Approval)."
                            } else {
                                "POI '${savedPoi.titleFor("vi")}' was created; review the upload warning below."
                            },
                            errorMessage = followUpError
                        )
                    }
                    onSuccess()
                    return@launch
                }
            } catch (error: Exception) {
                if (!BuildConfig.DEBUG) {
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = error.message ?: "Không thể tạo địa điểm.")
                    }
                    return@launch
                }
            }
            if (!BuildConfig.DEBUG) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "Máy chủ không trả về địa điểm đã tạo.")
                }
                return@launch
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
                arInfoCardCount = request.arInfoCards.size,
                localizations = request.translations
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

    suspend fun createAdminArInfoCard(
        poiId: String,
        request: UpsertArInfoCardRequest
    ): String? {
        if (_uiState.value.currentUser?.isAdminOrSuperAdmin != true) {
            return "Chỉ tài khoản Admin hoặc Super Admin mới được tạo thẻ AR."
        }

        return try {
            val existingResponse = apiService.getAdminArInfoCards(poiId)
            if (!existingResponse.success || existingResponse.data == null) {
                return existingResponse.error?.message ?: "Không thể tải thứ tự các thẻ AR hiện có."
            }
            val nextDisplayOrder = (existingResponse.data.maxOfOrNull { it.displayOrder } ?: 0) + 1
            val response = apiService.createArInfoCard(
                poiId,
                request.copy(displayOrder = nextDisplayOrder)
            )
            if (!response.isSuccessful) {
                return "Không thể lưu thẻ AR (HTTP ${response.code()})."
            }

            val refreshedCards = apiService.getAdminArInfoCards(poiId).data ?:
                (existingResponse.data + AdminArInfoCardDto(
                    id = "pending-${System.currentTimeMillis()}",
                    poiId = poiId,
                    cardType = request.cardType,
                    azimuthDegrees = request.azimuthDegrees.toDouble(),
                    distanceMeters = request.distanceMeters.toDouble(),
                    elevationMeters = request.elevationMeters.toDouble(),
                    displayOrder = nextDisplayOrder,
                    isActive = request.isActive,
                    localizations = request.localizations
                ))
            _uiState.update { state ->
                state.copy(
                    adminArCardsByPoi = state.adminArCardsByPoi + (poiId to refreshedCards),
                    adminPois = state.adminPois.map { poi ->
                        if (poi.id == poiId) poi.copy(arInfoCardCount = refreshedCards.size) else poi
                    },
                    statusBannerMessage = "Đã tạo thẻ AR cố định cho địa điểm."
                )
            }
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error.message ?: "Không thể tạo thẻ AR. Vui lòng thử lại."
        }
    }

    suspend fun loadAdminArModelLibrary(): List<ArModelLibraryItemDto> {
        if (_uiState.value.currentUser?.isAdminOrSuperAdmin != true) {
            throw SecurityException("Chỉ tài khoản Admin hoặc Super Admin mới được dùng kho vật thể AR 3D.")
        }

        val response = apiService.getAdminArModelLibrary()
        if (!response.isSuccessful) {
            throw IllegalStateException("Không thể tải kho vật thể 3D (HTTP ${response.code()}).")
        }
        return response.body().orEmpty()
    }

    suspend fun placeExistingArModel(
        poiId: String,
        request: PlaceArModelRequest
    ): String? {
        if (_uiState.value.currentUser?.isAdminOrSuperAdmin != true) {
            return "Chỉ tài khoản Admin hoặc Super Admin mới được đặt vật thể AR 3D."
        }

        return try {
            val response = apiService.placeExistingArModel(poiId, request)
            if (!response.isSuccessful) {
                return "Không thể lưu vị trí vật thể 3D (HTTP ${response.code()})."
            }
            val createdCard = response.body()
                ?: return "Máy chủ không trả về thẻ AR đã tạo."
            val refreshedCards = apiService.getAdminArInfoCards(poiId).data
                ?: (_uiState.value.adminArCardsByPoi[poiId].orEmpty() + createdCard)
            _uiState.update { state ->
                state.copy(
                    adminArCardsByPoi = state.adminArCardsByPoi + (poiId to refreshedCards),
                    adminPois = state.adminPois.map { poi ->
                        if (poi.id == poiId) poi.copy(arInfoCardCount = refreshedCards.size) else poi
                    },
                    statusBannerMessage = "Đã đặt vật thể 3D và tạo thẻ AR cho địa điểm."
                )
            }
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error.message ?: "Không thể đặt vật thể 3D. Vui lòng thử lại."
        }
    }

    suspend fun buildAdminArCard3dModel(cardId: String, imageFiles: List<File>): String? {
        if (_uiState.value.currentUser?.isAdminOrSuperAdmin != true) {
            return "Chỉ tài khoản Admin hoặc Super Admin mới được dựng mô hình AR 3D."
        }
        if (imageFiles.size !in 12..32) {
            return "Cần chụp từ 12 đến 32 ảnh trước khi dựng mô hình."
        }
        val totalBytes = imageFiles.sumOf { it.length() }
        if (totalBytes > 120L * 1024 * 1024) {
            return "Tổng dung lượng ảnh vượt 120 MB. Hãy bỏ bớt ảnh hoặc chụp lại ở độ phân giải thấp hơn."
        }
        if (imageFiles.any { !it.exists() || it.length() == 0L }) {
            return "Một số ảnh chụp không còn hợp lệ. Hãy chụp lại các ảnh đó."
        }

        return try {
            val imageParts = imageFiles.map { image ->
                val mediaType = if (image.extension.equals("png", ignoreCase = true)) {
                    "image/png".toMediaTypeOrNull()
                } else {
                    "image/jpeg".toMediaTypeOrNull()
                }
                MultipartBody.Part.createFormData(
                    "images",
                    image.name,
                    image.asRequestBody(mediaType)
                )
            }
            val response = apiService.submitArCard3dModel(cardId, imageParts)
            if (!response.isSuccessful) {
                return "Không gửi được ảnh để dựng mô hình (HTTP ${response.code()})."
            }

            val body = response.body()
                ?: return "Máy chủ đã nhận yêu cầu nhưng không trả về trạng thái dựng mô hình."
            _uiState.update { state ->
                state.copy(
                    adminArCardsByPoi = state.adminArCardsByPoi.mapValues { (_, cards) ->
                        cards.map { card ->
                            if (card.id == cardId) card.copy(modelStatus = body.status, modelError = null) else card
                        }
                    },
                    statusBannerMessage = body.message
                )
            }

            viewModelScope.launch {
                repeat(180) {
                    delay(10_000)
                    try {
                        val statusResponse = apiService.getArCard3dModelStatus(cardId)
                        val status = statusResponse.body() ?: return@repeat
                        _uiState.update { state ->
                            state.copy(
                                adminArCardsByPoi = state.adminArCardsByPoi.mapValues { (_, cards) ->
                                    cards.map { card ->
                                        if (card.id == cardId) {
                                            card.copy(modelStatus = status.status, modelError = status.errorMessage)
                                        } else card
                                    }
                                },
                                statusBannerMessage = when (status.status.lowercase()) {
                                    "ready" -> "Mô hình 3D đã sẵn sàng trong AR."
                                    "failed" -> status.errorMessage ?: "Không dựng được mô hình 3D."
                                    else -> state.statusBannerMessage
                                }
                            )
                        }
                        if (status.status.equals("Ready", true) || status.status.equals("Failed", true)) {
                            return@launch
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // Continue polling; temporary network errors should not cancel the server-side build.
                    }
                }
            }
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error.message ?: "Không thể gửi ảnh để dựng mô hình 3D."
        }
    }

    suspend fun updateAdminArInfoCard(
        cardId: String,
        request: UpsertArInfoCardRequest
    ): String? {
        if (_uiState.value.currentUser?.isAdminOrSuperAdmin != true) {
            return "Chỉ tài khoản Admin hoặc Super Admin mới được chỉnh sửa thẻ AR."
        }

        return try {
            val response = apiService.updateArInfoCard(cardId, request)
            if (!response.isSuccessful) return "Không thể cập nhật thẻ AR (HTTP ${response.code()})."
            val updatedCard = response.body() ?: return "Máy chủ không trả về thẻ AR đã cập nhật."
            _uiState.update { state ->
                val cards = (state.adminArCardsByPoi[updatedCard.poiId].orEmpty()
                    .filterNot { it.id == cardId } + updatedCard)
                    .sortedBy { it.displayOrder }
                state.copy(
                    adminArCardsByPoi = state.adminArCardsByPoi + (updatedCard.poiId to cards),
                    adminPois = state.adminPois.map { poi ->
                        if (poi.id == updatedCard.poiId) poi.copy(arInfoCardCount = cards.size) else poi
                    },
                    statusBannerMessage = "Đã cập nhật thẻ AR."
                )
            }
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error.message ?: "Không thể cập nhật thẻ AR. Vui lòng thử lại."
        }
    }

    suspend fun deleteAdminArInfoCard(cardId: String): String? {
        if (_uiState.value.currentUser?.isAdminOrSuperAdmin != true) {
            return "Chỉ tài khoản Admin hoặc Super Admin mới được xóa thẻ AR."
        }

        return try {
            val response = apiService.deleteArInfoCard(cardId)
            if (!response.isSuccessful) return "Không thể xóa thẻ AR (HTTP ${response.code()})."

            _uiState.update { state ->
                val updatedCardsByPoi = state.adminArCardsByPoi.mapValues { (_, cards) ->
                    cards.filterNot { it.id == cardId }
                }
                val countsByPoi = updatedCardsByPoi.mapValues { it.value.size }
                state.copy(
                    adminArCardsByPoi = updatedCardsByPoi,
                    adminPois = state.adminPois.map { poi ->
                        countsByPoi[poi.id]?.let { poi.copy(arInfoCardCount = it) } ?: poi
                    },
                    statusBannerMessage = "Đã xóa thẻ AR."
                )
            }
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error.message ?: "Không thể xóa thẻ AR. Vui lòng thử lại."
        }
    }

    fun updatePoiAsAdmin(
        poiId: String,
        request: CreateOrUpdatePoiRequest,
        coverImageUri: Uri? = null,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val response = apiService.updatePoi(poiId, request)
                var updatedPoi = response.data
                    ?: throw IllegalStateException(response.error?.message ?: "Máy chủ không trả về POI đã cập nhật.")
                if (coverImageUri != null) {
                    updatedPoi = uploadPoiCover(poiId, coverImageUri)
                }
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        adminPois = state.adminPois.map { if (it.id == poiId) updatedPoi.copy(
                            arInfoCardCount = state.adminArCardsByPoi[poiId]?.size ?: it.arInfoCardCount
                        ) else it },
                        statusBannerMessage = "Đã cập nhật POI '${updatedPoi.titleFor("vi")}'."
                    )
                }
                onSuccess()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = error.message ?: "Không thể cập nhật POI.")
                }
            }
        }
    }

    fun deletePoiAsSuperAdmin(poiId: String) {
        if (_uiState.value.currentUser?.isSuperAdmin != true) {
            _uiState.update { it.copy(errorMessage = "Chỉ Super Admin mới có quyền xóa POI.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val response = apiService.deletePoi(poiId)
                if (!response.isSuccessful) {
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = "Không thể xóa POI (HTTP ${response.code()}).")
                    }
                    return@launch
                }

                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        adminPois = state.adminPois.filterNot { it.id == poiId },
                        adminArCardsByPoi = state.adminArCardsByPoi - poiId,
                        nearbyPois = state.nearbyPois.filterNot { it.id == poiId },
                        selectedPoiDetail = state.selectedPoiDetail?.takeUnless { it.id == poiId },
                        activeArContext = state.activeArContext?.takeUnless { it.poiId == poiId },
                        navigationTarget = state.navigationTarget?.takeUnless { it.poiId == poiId },
                        statusBannerMessage = "Đã xóa POI và các thẻ AR liên quan."
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = error.message ?: "Không thể xóa POI.")
                }
            }
        }
    }

    private suspend fun uploadPoiCover(poiId: String, uri: Uri): AdminPoiDto {
        val imageFile = withContext(Dispatchers.IO) {
            val file = File(getApplication<Application>().cacheDir, "poi-cover-${System.currentTimeMillis()}.jpg")
            try {
                val input = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("Không thể mở ảnh đã chọn.")
                input.use { source -> file.outputStream().use(source::copyTo) }
                file
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }
        return try {
            val part = withContext(Dispatchers.IO) {
                SightseeingNetworkModule.buildPoiCoverUploadPart(imageFile)
            }
            val response = apiService.uploadPoiCoverImage(poiId, part)
            if (!response.isSuccessful) {
                throw IllegalStateException("Không thể tải ảnh thumbnail lên (HTTP ${response.code()}).")
            }
            response.body() ?: throw IllegalStateException("Máy chủ không trả về POI sau khi tải ảnh.")
        } finally {
            imageFile.delete()
        }
    }

    private suspend fun createArCardsForPoi(poiId: String, inputs: List<ArInfoCardInputDto>): String? {
        val cardGroups = inputs.groupBy {
            listOf(it.azimuthDegrees, it.elevationMeters, it.anchorDistanceMeters, it.displayOrder)
        }
        for (group in cardGroups.values) {
            val first = group.first()
            val payload = UpsertArInfoCardRequest(
                cardType = "HistoricalFact",
                azimuthDegrees = first.azimuthDegrees,
                distanceMeters = first.anchorDistanceMeters,
                elevationMeters = first.elevationMeters,
                displayOrder = first.displayOrder,
                isActive = true,
                localizations = group.distinctBy { it.languageCode.lowercase() }.map {
                    ArCardLocalizationInputDto(
                        languageCode = it.languageCode,
                        title = it.title,
                        subtitle = it.subtitle.ifBlank { null },
                        content = it.bodyText
                    )
                }
            )
            try {
                val response = apiService.createArInfoCard(poiId, payload)
                if (!response.isSuccessful) {
                    return "Địa điểm đã tạo nhưng lưu thẻ AR thất bại (HTTP ${response.code()})."
                }
            } catch (error: Exception) {
                return "Địa điểm đã tạo nhưng lưu thẻ AR thất bại: ${error.message ?: "lỗi máy chủ"}"
            }
        }
        return null
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
                val response = apiService.approvePoi(poiId)
                if (!response.success || response.data == null) {
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = response.error?.message ?: "Máy chủ không xác nhận phê duyệt.")
                    }
                    return@launch
                }
            } catch (error: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "Không thể phê duyệt địa điểm.") }
                return@launch
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
                val response = apiService.rejectPoi(poiId, RejectPoiRequest(rejectionReason.trim()))
                if (!response.success || response.data == null) {
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = response.error?.message ?: "Máy chủ không xác nhận từ chối địa điểm.")
                    }
                    return@launch
                }
            } catch (error: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "Không thể từ chối địa điểm.") }
                return@launch
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
                val response = apiService.moderatePhoto(
                    photoId = photoId,
                    request = ModeratePhotoDecisionRequest(
                        decision = if (decision.equals("Keep", ignoreCase = true)) "Approve" else decision,
                        moderatorNotes = notes,
                        hardDeleteFromStorage = decision.equals("Remove", ignoreCase = true)
                    )
                )
                if (!response.success || response.data == null) {
                    _uiState.update {
                        it.copy(errorMessage = response.error?.message ?: "Máy chủ không xác nhận quyết định kiểm duyệt.")
                    }
                    return@launch
                }
            } catch (error: Exception) {
                _uiState.update { it.copy(errorMessage = error.message ?: "Không thể gửi quyết định kiểm duyệt.") }
                return@launch
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

        val isFoodPoi = PoiCategoryLabels.isFood(summaryPin.category)

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
