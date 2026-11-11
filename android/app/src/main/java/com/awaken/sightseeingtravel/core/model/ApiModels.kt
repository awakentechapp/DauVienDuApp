package com.awaken.sightseeingtravel.core.model

import com.google.gson.annotations.SerializedName

/**
 * Standard API Response Envelope from `/api/v1/...`
 */
data class ApiEnvelope<T>(
    @SerializedName("success") val success: Boolean = true,
    @SerializedName("data") val data: T? = null,
    @SerializedName("meta") val meta: PaginationMeta? = null,
    @SerializedName("error") val error: ApiErrorDto? = null
)

data class PaginationMeta(
    @SerializedName("page") val page: Int = 1,
    @SerializedName("pageSize") val pageSize: Int = 20,
    @SerializedName("totalCount") val totalCount: Int = 0,
    @SerializedName("totalPages") val totalPages: Int = 1
)

data class ApiErrorDto(
    @SerializedName("code") val code: String? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("details") val details: List<String>? = null
)

data class CalculateRouteRequestDto(
    @SerializedName("originLatitude") val originLatitude: Double,
    @SerializedName("originLongitude") val originLongitude: Double,
    @SerializedName("destinationLatitude") val destinationLatitude: Double,
    @SerializedName("destinationLongitude") val destinationLongitude: Double,
    @SerializedName("travelMode") val travelMode: String = "car"
)

data class RouteCoordinateDto(
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double
)

data class CalculatedRouteDto(
    @SerializedName("distanceMeters") val distanceMeters: Double,
    @SerializedName("durationSeconds") val durationSeconds: Double,
    @SerializedName("coordinates") val coordinates: List<RouteCoordinateDto>,
    @SerializedName("travelMode") val travelMode: String? = null
)

/**
 * User Roles (`Tourist`, `Admin`, `SuperAdmin`)
 */
enum class UserRole(val wireValue: String) {
    TOURIST("Tourist"),
    ADMIN("Admin"),
    SUPER_ADMIN("SuperAdmin");

    companion object {
        fun fromString(raw: String?): UserRole {
            return when (raw?.trim()?.lowercase()) {
                "superadmin", "super_admin" -> SUPER_ADMIN
                "admin" -> ADMIN
                else -> TOURIST
            }
        }
    }
}

/**
 * POI Workflow Statuses (`Draft`, `PendingApproval`, `Published`, `Rejected`)
 */
enum class PoiStatus(val wireValue: String) {
    DRAFT("Draft"),
    PENDING_APPROVAL("PendingApproval"),
    PUBLISHED("Published"),
    REJECTED("Rejected");

    companion object {
        fun fromString(raw: String?): PoiStatus {
            return entries.firstOrNull { it.wireValue.equals(raw, ignoreCase = true) }
                ?: PENDING_APPROVAL
        }
    }
}

// ============================================================================
// 1. AUTHENTICATION & USER PROFILE DTOs
// ============================================================================

data class RegisterRequest(
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String,
    @SerializedName("displayName") val displayName: String,
    @SerializedName("preferredLanguage") val preferredLanguage: String = "vi"
)

data class LoginRequest(
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String
)

data class RefreshTokenRequest(
    @SerializedName("refreshToken") val refreshToken: String
)

data class LogoutRequest(
    @SerializedName("refreshToken") val refreshToken: String
)

data class AuthTokensResponse(
    @SerializedName("accessToken") val accessToken: String,
    @SerializedName("refreshToken") val refreshToken: String,
    @SerializedName("expiresAt") val expiresAt: String? = null,
    @SerializedName("user") val user: UserProfileDto
)

data class UserProfileDto(
    @SerializedName("id") val id: String,
    @SerializedName("email") val email: String,
    @SerializedName("displayName") val displayName: String,
    @SerializedName("avatarUrl") val avatarUrl: String? = null,
    @SerializedName("role") val role: String = "Tourist",
    @SerializedName("preferredLanguage") val preferredLanguage: String = "vi",
    @SerializedName("isActive") val isActive: Boolean = true,
    @SerializedName("createdAt") val createdAt: String? = null
) {
    val parsedRole: UserRole
        get() = UserRole.fromString(role)

    val isAdminOrSuperAdmin: Boolean
        get() = parsedRole == UserRole.ADMIN || parsedRole == UserRole.SUPER_ADMIN

    val isSuperAdmin: Boolean
        get() = parsedRole == UserRole.SUPER_ADMIN
}

data class UpdateProfileRequest(
    @SerializedName("displayName") val displayName: String,
    @SerializedName("preferredLanguage") val preferredLanguage: String,
    @SerializedName("avatarUrl") val avatarUrl: String? = null
)

// ============================================================================
// 2. POI MAP PINS, CULTURAL STORIES & SPATIAL AR CONTEXT DTOs
// ============================================================================

data class PoiPinSummaryDto(
    @SerializedName("id") val id: String,
    @SerializedName("slug") val slug: String = "",
    @SerializedName("category") val category: String = PoiCategoryLabels.HISTORICAL_SITE,
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("elevationMeters") val elevationMeters: Double = 0.0,
    @SerializedName("activationRadiusMeters") val activationRadiusMeters: Double = 50.0,
    @SerializedName("distanceMeters") val distanceMeters: Double? = null,
    @SerializedName("title") val title: String,
    @SerializedName("shortSummary") val shortSummary: String = "",
    @SerializedName("coverImageUrl") val coverImageUrl: String? = null,
    @SerializedName("audioGuideUrl") val audioGuideUrl: String? = null,
    @SerializedName("status") val status: String = "Published",
    @SerializedName("communityPhotoCount") val communityPhotoCount: Int = 0,
    @SerializedName("arInfoCardCount") val arInfoCardCount: Int = 0
) {
    /**
     * Rule: Tourist unlocks AR Camera when within `activationRadiusMeters + 20m` of the Pin.
     */
    fun canUnlockAr(currentDistanceMeters: Double?): Boolean {
        val dist = currentDistanceMeters ?: distanceMeters ?: return false
        return dist <= (activationRadiusMeters + 20.0)
    }
}

data class PoiDetailDto(
    @SerializedName("id") val id: String,
    @SerializedName("slug") val slug: String = "",
    @SerializedName("category") val category: String = PoiCategoryLabels.HISTORICAL_SITE,
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("elevationMeters") val elevationMeters: Double = 0.0,
    @SerializedName("activationRadiusMeters") val activationRadiusMeters: Double = 50.0,
    @SerializedName("distanceMeters") val distanceMeters: Double? = null,
    @SerializedName("isArUnlocked") val isArUnlocked: Boolean = false,
    @SerializedName("languageCode") val languageCode: String = "vi",
    @SerializedName("title") val title: String,
    @SerializedName("shortSummary") val shortSummary: String = "",
    @SerializedName("culturalStoryMarkdown") val culturalStoryMarkdown: String = "",
    @SerializedName("historicalPeriod") val historicalPeriod: String? = null,
    @SerializedName("architecturalHighlights") val architecturalHighlights: List<String> = emptyList(),
    @SerializedName("visitorTips") val visitorTips: String? = null,
    @SerializedName("coverImageUrl") val coverImageUrl: String? = null,
    @SerializedName("audioGuideUrl") val audioGuideUrl: String? = null,
    @SerializedName("arInfoCards") val arInfoCards: List<ArInfoCardDto> = emptyList(),
    @SerializedName("highlightedCommunityPhotos") val highlightedCommunityPhotos: List<CommunityPhotoDto> = emptyList(),
    @SerializedName("status") val status: String = "Published",
    @SerializedName("rejectionReason") val rejectionReason: String? = null
) {
    fun isWithinUnlockRadius(liveDistanceMeters: Double?): Boolean {
        val dist = liveDistanceMeters ?: distanceMeters
        return isArUnlocked || (dist != null && dist <= activationRadiusMeters + 20.0)
    }
}

/**
 * Spatial AR Info Card anchored in 360-degree space around the POI.
 */
data class ArInfoCardDto(
    @SerializedName("id") val id: String,
    @SerializedName("poiId") val poiId: String = "",
    @SerializedName("title") val title: String,
    @SerializedName("subtitle") val subtitle: String = "",
    @SerializedName("bodyText") val bodyText: String = "",
    @SerializedName("badgeLabel") val badgeLabel: String = "Cultural Insight",
    @SerializedName("mediaUrl") val mediaUrl: String? = null,
    @SerializedName("audioSnippetUrl") val audioSnippetUrl: String? = null,
    @SerializedName("azimuthDegrees") val azimuthDegrees: Float = 0f,
    @SerializedName("elevationMeters") val elevationMeters: Float = 2.0f,
    @SerializedName("anchorDistanceMeters") val anchorDistanceMeters: Float = 8.0f,
    @SerializedName("displayOrder") val displayOrder: Int = 0,
    @SerializedName("modelUrl") val modelUrl: String? = null,
    @SerializedName("modelRotationDegrees") val modelRotationDegrees: Float = 0f
)

/**
 * Response from `GET /api/v1/pois/{poiId}/ar-context?lat={lat}&lng={lng}&lang={vi|en}`
 * Provides Spatial AR Info Cards + Top 10 Highlighted Community Photos at the POI.
 */
data class PoiArContextDto(
    @SerializedName("poiId") val poiId: String,
    @SerializedName("title") val title: String,
    @SerializedName("category") val category: String = "Heritage",
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("activationRadiusMeters") val activationRadiusMeters: Double = 50.0,
    @SerializedName("distanceMeters") val distanceMeters: Double = 0.0,
    @SerializedName("isArUnlocked") val isArUnlocked: Boolean = true,
    @SerializedName("arInfoCards") val arInfoCards: List<ArInfoCardDto> = emptyList(),
    @SerializedName("highlightedCommunityPhotos") val highlightedCommunityPhotos: List<CommunityPhotoDto> = emptyList()
)

// ============================================================================
// 3. COMMUNITY PHOTOS & MODERATION DTOs
// ============================================================================

/**
 * Community Photo stored on Contabo Object Storage (S3-compatible) and served via CDN.
 */
data class CommunityPhotoDto(
    @SerializedName("id") val id: String,
    @SerializedName("poiId") val poiId: String,
    @SerializedName("authorId") val authorId: String,
    @SerializedName("authorDisplayName") val authorDisplayName: String,
    @SerializedName("authorAvatarUrl") val authorAvatarUrl: String? = null,
    @SerializedName("imageUrl") val imageUrl: String,
    @SerializedName("thumbnailUrl") val thumbnailUrl: String? = null,
    @SerializedName("caption") val caption: String? = null,
    @SerializedName("capturedLatitude") val capturedLatitude: Double = 0.0,
    @SerializedName("capturedLongitude") val capturedLongitude: Double = 0.0,
    @SerializedName("azimuthDegrees") val azimuthDegrees: Float? = null,
    @SerializedName("pitchDegrees") val pitchDegrees: Float? = null,
    @SerializedName("elevationMeters") val elevationMeters: Float = 1.5f,
    @SerializedName("likeCount") val likeCount: Int = 0,
    @SerializedName("isLikedByCurrentUser") val isLikedByCurrentUser: Boolean = false,
    @SerializedName("isHighlightedInAr") val isHighlightedInAr: Boolean = false,
    @SerializedName("highlightRank") val highlightRank: Int? = null,
    @SerializedName("createdAt") val createdAt: String = ""
)

data class LikePhotoResponseDto(
    @SerializedName("photoId") val photoId: String,
    @SerializedName("likeCount") val likeCount: Int,
    @SerializedName("isLikedByCurrentUser") val isLikedByCurrentUser: Boolean
)

data class ReportPhotoRequest(
    @SerializedName("reason") val reason: String,
    @SerializedName("details") val details: String? = null
)

data class ReportPhotoResponseDto(
    @SerializedName("photoId") val photoId: String,
    @SerializedName("reportId") val reportId: String? = null,
    @SerializedName("status") val status: String = "PendingModeration"
)

data class DeletePhotoResponseDto(
    @SerializedName("photoId") val photoId: String,
    @SerializedName("deleted") val deleted: Boolean = true
)

// ============================================================================
// 4. ADMIN & SUPERADMIN POI APPROVAL & PHOTO MODERATION DTOs
// ============================================================================

data class PoiTranslationInputDto(
    @SerializedName("languageCode") val languageCode: String, // "vi" or "en"
    @SerializedName(value = "name", alternate = ["title"]) val title: String,
    @SerializedName("shortSummary") val shortSummary: String,
    @SerializedName("culturalStoryMarkdown") val culturalStoryMarkdown: String,
    @SerializedName("historicalPeriod") val historicalPeriod: String? = null,
    @SerializedName("visitorTips") val visitorTips: String? = null
)

data class ArInfoCardInputDto(
    @SerializedName("languageCode") val languageCode: String, // "vi" or "en"
    @SerializedName("title") val title: String,
    @SerializedName("subtitle") val subtitle: String,
    @SerializedName("bodyText") val bodyText: String,
    @SerializedName("badgeLabel") val badgeLabel: String = "Cultural Insight",
    @SerializedName("azimuthDegrees") val azimuthDegrees: Float,
    @SerializedName("elevationMeters") val elevationMeters: Float = 2.0f,
    @SerializedName("anchorDistanceMeters") val anchorDistanceMeters: Float = 8.0f,
    @SerializedName("displayOrder") val displayOrder: Int = 1
)

data class ArCardLocalizationInputDto(
    @SerializedName("languageCode") val languageCode: String,
    @SerializedName("title") val title: String,
    @SerializedName("subtitle") val subtitle: String? = null,
    @SerializedName("content") val content: String
)

data class UpsertArInfoCardRequest(
    @SerializedName("cardType") val cardType: String,
    @SerializedName("azimuthDegrees") val azimuthDegrees: Float,
    @SerializedName("distanceMeters") val distanceMeters: Float,
    @SerializedName("elevationMeters") val elevationMeters: Float,
    @SerializedName("displayOrder") val displayOrder: Int,
    @SerializedName("isActive") val isActive: Boolean,
    @SerializedName("localizations") val localizations: List<ArCardLocalizationInputDto>
)

data class AdminArInfoCardDto(
    @SerializedName("id") val id: String,
    @SerializedName("poiId") val poiId: String,
    @SerializedName("cardType") val cardType: String,
    @SerializedName("azimuthDegrees") val azimuthDegrees: Double,
    @SerializedName("distanceMeters") val distanceMeters: Double,
    @SerializedName("elevationMeters") val elevationMeters: Double,
    @SerializedName("displayOrder") val displayOrder: Int,
    @SerializedName("isActive") val isActive: Boolean,
    @SerializedName("localizations") val localizations: List<ArCardLocalizationInputDto> = emptyList(),
    @SerializedName("modelStatus") val modelStatus: String = "None",
    @SerializedName("modelError") val modelError: String? = null,
    @SerializedName("modelRotationDegrees") val modelRotationDegrees: Double = 0.0,
    @SerializedName("modelName") val modelName: String? = null,
    @SerializedName("modelCategory") val modelCategory: String = "Object"
)

data class ArModelLibraryItemDto(
    @SerializedName("sourceCardId") val sourceCardId: String,
    @SerializedName("sourcePoiId") val sourcePoiId: String,
    @SerializedName("name") val name: String,
    @SerializedName("modelUrl") val modelUrl: String,
    @SerializedName("modelRotationDegrees") val modelRotationDegrees: Float = 0f,
    @SerializedName("category") val category: String = "Object"
)

data class PlaceArModelRequest(
    @SerializedName("sourceCardId") val sourceCardId: String,
    @SerializedName("name") val name: String,
    @SerializedName("azimuthDegrees") val azimuthDegrees: Float,
    @SerializedName("distanceMeters") val distanceMeters: Float,
    @SerializedName("elevationMeters") val elevationMeters: Float,
    @SerializedName("modelRotationDegrees") val modelRotationDegrees: Float
)

data class ArModelBuildSubmissionDto(
    @SerializedName("cardId") val cardId: String,
    @SerializedName("status") val status: String,
    @SerializedName("message") val message: String
)

data class ArModelBuildStatusDto(
    @SerializedName("cardId") val cardId: String,
    @SerializedName("status") val status: String,
    @SerializedName("errorMessage") val errorMessage: String? = null,
    @SerializedName("modelName") val modelName: String? = null,
    @SerializedName("modelCategory") val modelCategory: String = "Object"
)

data class CreateOrUpdatePoiRequest(
    @SerializedName(value = "code", alternate = ["slug"]) val slug: String,
    @SerializedName("category") val category: String,
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("elevationMeters") val elevationMeters: Double = 0.0,
    @SerializedName("activationRadiusMeters") val activationRadiusMeters: Double = 50.0,
    @SerializedName("coverImageUrl") val coverImageUrl: String? = null,
    @SerializedName("audioGuideUrl") val audioGuideUrl: String? = null,
    @SerializedName(value = "localizations", alternate = ["translations"]) val translations: List<PoiTranslationInputDto>,
    @SerializedName("arInfoCards") val arInfoCards: List<ArInfoCardInputDto> = emptyList(),
    @SerializedName("saveAsDraft") val saveAsDraft: Boolean = false
)

data class ApprovePoiRequest(
    @SerializedName("reviewNotes") val reviewNotes: String? = "Approved by SuperAdmin for public map & AR"
)

data class RejectPoiRequest(
    @SerializedName(value = "reason", alternate = ["rejectionReason"]) val rejectionReason: String
)

data class AdminPoiDto(
    @SerializedName("id") val id: String,
    @SerializedName(value = "slug", alternate = ["code"]) val slug: String,
    @SerializedName("category") val category: String = PoiCategoryLabels.HISTORICAL_SITE,
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("activationRadiusMeters") val activationRadiusMeters: Double = 50.0,
    @SerializedName("status") val status: String, // Draft, PendingApproval, Published, Rejected
    @SerializedName("rejectionReason") val rejectionReason: String? = null,
    @SerializedName("createdByAdminName") val createdByAdminName: String = "Admin",
    @SerializedName("approvedBySuperAdminName") val approvedBySuperAdminName: String? = null,
    @SerializedName("updatedAt") val updatedAt: String = "",
    @SerializedName("titleVi") val titleVi: String = "",
    @SerializedName("titleEn") val titleEn: String = "",
    @SerializedName("shortSummaryVi") val shortSummaryVi: String = "",
    @SerializedName("shortSummaryEn") val shortSummaryEn: String = "",
    @SerializedName("arInfoCardCount") val arInfoCardCount: Int = 0,
    @SerializedName("coverImageUrl") val coverImageUrl: String? = null,
    @SerializedName("localizations") val localizations: List<PoiTranslationInputDto> = emptyList()
) {
    val parsedStatus: PoiStatus
        get() = PoiStatus.fromString(status)

    fun titleFor(languageCode: String): String =
        localizations.firstOrNull { it.languageCode.equals(languageCode, ignoreCase = true) }?.title
            ?.takeIf(String::isNotBlank)
            ?: if (languageCode.equals("en", ignoreCase = true)) titleEn.ifBlank { titleVi.ifBlank { slug } }
            else titleVi.ifBlank { titleEn.ifBlank { slug } }

    fun summaryFor(languageCode: String): String =
        localizations.firstOrNull { it.languageCode.equals(languageCode, ignoreCase = true) }?.shortSummary
            ?.takeIf(String::isNotBlank)
            ?: if (languageCode.equals("en", ignoreCase = true)) shortSummaryEn.ifBlank { shortSummaryVi }
            else shortSummaryVi.ifBlank { shortSummaryEn }
}

data class ModerationPhotoItemDto(
    @SerializedName("photoId") val photoId: String,
    @SerializedName("poiId") val poiId: String,
    @SerializedName("poiTitle") val poiTitle: String,
    @SerializedName("imageUrl") val imageUrl: String,
    @SerializedName("caption") val caption: String? = null,
    @SerializedName("authorDisplayName") val authorDisplayName: String,
    @SerializedName("reportCount") val reportCount: Int = 1,
    @SerializedName("latestReportReason") val latestReportReason: String = "Inappropriate content",
    @SerializedName("moderationStatus") val moderationStatus: String = "PendingModeration",
    @SerializedName("createdAt") val createdAt: String = ""
)

data class ModeratePhotoDecisionRequest(
    @SerializedName(value = "action", alternate = ["decision"]) val decision: String, // "Approve" or "Remove"
    @SerializedName(value = "note", alternate = ["moderatorNotes"]) val moderatorNotes: String? = null,
    @SerializedName("hardDeleteFromStorage") val hardDeleteFromStorage: Boolean = false
)
