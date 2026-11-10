import Foundation

private struct FlexibleCodingKey: CodingKey {
    let stringValue: String
    let intValue: Int? = nil

    init(_ string: String) { stringValue = string }
    init?(stringValue: String) { self.stringValue = stringValue }
    init?(intValue: Int) { return nil }
}

private extension KeyedDecodingContainer where Key == FlexibleCodingKey {
    func first<T: Decodable>(_ type: T.Type, _ names: String...) throws -> T? {
        for name in names {
            let key = FlexibleCodingKey(name)
            if contains(key) {
                return try decodeIfPresent(type, forKey: key)
            }
        }
        return nil
    }
}

// MARK: - Standard API Error Payload
public struct ApiErrorPayload: Codable, Error, LocalizedError, Sendable {
    public let errorCode: String
    public let detail: String
    public let status: Int?
    public let traceId: String?

    public var errorDescription: String? {
        "[\(errorCode)] \(detail)"
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: FlexibleCodingKey.self)
        errorCode = try container.first(String.self, "errorCode", "code") ?? "API_ERROR"
        detail = try container.first(String.self, "detail", "message") ?? "Yêu cầu không thành công."
        status = try container.first(Int.self, "status")
        traceId = try container.first(String.self, "traceId")
    }
}

// MARK: - Role & Status Enums
public enum UserRole: String, Codable, CaseIterable, Sendable {
    case tourist = "Tourist"
    case admin = "Admin"
    case superAdmin = "SuperAdmin"

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let raw = try container.decode(String.self)
        switch raw.lowercased() {
        case "superadmin", "super_admin":
            self = .superAdmin
        case "admin":
            self = .admin
        default:
            self = .tourist
        }
    }

    public var isAdminOrAbove: Bool {
        self == .admin || self == .superAdmin
    }

    public var isSuperAdmin: Bool {
        self == .superAdmin
    }
}

public enum PoiStatus: String, Codable, CaseIterable, Sendable {
    case draft = "Draft"
    case pendingApproval = "PendingApproval"
    case published = "Published"
    case rejected = "Rejected"
    case archived = "Archived"

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let raw = try container.decode(String.self)
        switch raw.lowercased() {
        case "draft": self = .draft
        case "pendingapproval", "pending_approval": self = .pendingApproval
        case "published": self = .published
        case "rejected": self = .rejected
        case "archived": self = .archived
        default: self = .pendingApproval
        }
    }
}

public enum AppLanguage: String, Codable, CaseIterable, Identifiable, Sendable {
    case vi = "vi"
    case en = "en"

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .vi: return "Tiếng Việt (VI)"
        case .en: return "English (EN)"
        }
    }
}

// MARK: - Auth & User DTOs
public struct RegisterRequest: Codable, Sendable {
    public let email: String
    public let password: String
    public let displayName: String
    public let preferredLanguage: String

    public init(email: String, password: String, displayName: String, preferredLanguage: String = "vi") {
        self.email = email
        self.password = password
        self.displayName = displayName
        self.preferredLanguage = preferredLanguage
    }
}

public struct LoginRequest: Codable, Sendable {
    public let email: String
    public let password: String

    public init(email: String, password: String) {
        self.email = email
        self.password = password
    }
}

public struct RefreshTokenRequest: Codable, Sendable {
    public let refreshToken: String

    public init(refreshToken: String) {
        self.refreshToken = refreshToken
    }
}

public struct LogoutRequest: Codable, Sendable {
    public let refreshToken: String

    public init(refreshToken: String) {
        self.refreshToken = refreshToken
    }
}

public struct UpdateProfileRequest: Codable, Sendable {
    public let displayName: String
    public let preferredLanguage: String
    public let avatarUrl: String?

    public init(displayName: String, preferredLanguage: String, avatarUrl: String? = nil) {
        self.displayName = displayName
        self.preferredLanguage = preferredLanguage
        self.avatarUrl = avatarUrl
    }
}

public struct UserProfileDto: Codable, Identifiable, Equatable, Sendable {
    public let id: String
    public let email: String
    public let displayName: String
    public let role: UserRole
    public let preferredLanguage: String
    public let avatarUrl: String?
}

public struct AuthTokenResponse: Codable, Sendable {
    public let accessToken: String
    public let refreshToken: String
    public let expiresAt: String?
    public let user: UserProfileDto
}

// MARK: - POI & Geospatial DTOs
public struct NearbyPoiDto: Decodable, Identifiable, Equatable, Hashable, Sendable {
    public let id: String
    public let slug: String
    public let category: String
    public let name: String
    public let shortSummary: String
    public let latitude: Double
    public let longitude: Double
    public let activationRadiusMeters: Double
    public let distanceMeters: Double?
    public let canActivateAr: Bool?
    public let coverImageUrl: String?
    public let communityPhotosCount: Int?
    public let status: PoiStatus?

    public func isWithinGeofence(userDistanceMeters: Double?) -> Bool {
        if let canActivateAr = canActivateAr, canActivateAr {
            return true
        }
        let dist = userDistanceMeters ?? distanceMeters ?? Double.greatestFiniteMagnitude
        return dist <= (activationRadiusMeters + 20.0)
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: FlexibleCodingKey.self)
        id = try container.first(String.self, "id") ?? ""
        slug = try container.first(String.self, "code", "slug") ?? id
        category = try container.first(String.self, "category") ?? "Heritage"
        name = try container.first(String.self, "name", "title") ?? slug
        shortSummary = try container.first(String.self, "shortSummary") ?? ""
        latitude = try container.first(Double.self, "latitude") ?? 0
        longitude = try container.first(Double.self, "longitude") ?? 0
        activationRadiusMeters = try container.first(Double.self, "activationRadiusMeters") ?? 50
        distanceMeters = try container.first(Double.self, "distanceMeters")
        canActivateAr = try container.first(Bool.self, "canActivateAr")
        coverImageUrl = try container.first(String.self, "coverImageUrl")
        communityPhotosCount = try container.first(Int.self, "communityPhotoCount", "communityPhotosCount")
        status = try container.first(PoiStatus.self, "status") ?? .published
    }
}

public struct ArInfoCardDto: Decodable, Identifiable, Equatable, Hashable, Sendable {
    public let id: String
    public let title: String
    public let subtitle: String?
    public let summary: String?
    public let detailMarkdown: String?
    public let mediaUrl: String?
    public let audioNarrativeUrl: String?
    public let azimuthDegrees: Double
    public let elevationMeters: Double
    public let distanceMeters: Double
    public let cardType: String?
    public let sortOrder: Int?

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: FlexibleCodingKey.self)
        id = try container.first(String.self, "id") ?? ""
        title = try container.first(String.self, "title") ?? ""
        subtitle = try container.first(String.self, "subtitle")
        summary = try container.first(String.self, "summary")
        detailMarkdown = try container.first(String.self, "content", "detailMarkdown")
        mediaUrl = try container.first(String.self, "mediaUrl")
        audioNarrativeUrl = try container.first(String.self, "audioNarrativeUrl")
        azimuthDegrees = try container.first(Double.self, "azimuthDegrees") ?? 0
        elevationMeters = try container.first(Double.self, "elevationMeters") ?? 0
        distanceMeters = try container.first(Double.self, "distanceMeters") ?? 0
        cardType = try container.first(String.self, "cardType")
        sortOrder = try container.first(Int.self, "displayOrder", "sortOrder")
    }
}

public struct CommunityPhotoDto: Decodable, Identifiable, Equatable, Hashable, Sendable {
    public let id: String
    public let poiId: String
    public let userId: String?
    public let authorDisplayName: String
    public let authorAvatarUrl: String?
    public let imageUrl: String
    public let thumbnailUrl: String?
    public let caption: String?
    public let capturedLatitude: Double
    public let capturedLongitude: Double
    public let azimuthDegrees: Double?
    public let likesCount: Int
    public let isLikedByCurrentUser: Bool?
    public let moderationStatus: String?
    public let createdAt: String

    public init(
        id: String,
        poiId: String,
        userId: String?,
        authorDisplayName: String,
        authorAvatarUrl: String?,
        imageUrl: String,
        thumbnailUrl: String?,
        caption: String?,
        capturedLatitude: Double,
        capturedLongitude: Double,
        azimuthDegrees: Double?,
        likesCount: Int,
        isLikedByCurrentUser: Bool?,
        moderationStatus: String?,
        createdAt: String
    ) {
        self.id = id
        self.poiId = poiId
        self.userId = userId
        self.authorDisplayName = authorDisplayName
        self.authorAvatarUrl = authorAvatarUrl
        self.imageUrl = imageUrl
        self.thumbnailUrl = thumbnailUrl
        self.caption = caption
        self.capturedLatitude = capturedLatitude
        self.capturedLongitude = capturedLongitude
        self.azimuthDegrees = azimuthDegrees
        self.likesCount = likesCount
        self.isLikedByCurrentUser = isLikedByCurrentUser
        self.moderationStatus = moderationStatus
        self.createdAt = createdAt
    }

    public var bestThumbnailOrImageUrl: String {
        thumbnailUrl ?? imageUrl
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: FlexibleCodingKey.self)
        id = try container.first(String.self, "id") ?? ""
        poiId = try container.first(String.self, "poiId") ?? ""
        userId = try container.first(String.self, "userId")
        authorDisplayName = try container.first(String.self, "authorDisplayName") ?? ""
        authorAvatarUrl = try container.first(String.self, "authorAvatarUrl")
        imageUrl = try container.first(String.self, "imageUrl") ?? ""
        thumbnailUrl = try container.first(String.self, "thumbnailUrl")
        caption = try container.first(String.self, "caption")
        capturedLatitude = try container.first(Double.self, "capturedLatitude") ?? 0
        capturedLongitude = try container.first(Double.self, "capturedLongitude") ?? 0
        azimuthDegrees = try container.first(Double.self, "azimuthDegrees")
        likesCount = try container.first(Int.self, "likeCount", "likesCount") ?? 0
        isLikedByCurrentUser = try container.first(Bool.self, "isLikedByCurrentUser")
        moderationStatus = try container.first(String.self, "status", "moderationStatus")
        createdAt = try container.first(String.self, "createdAt") ?? ""
    }
}

public struct PoiDetailDto: Decodable, Identifiable, Equatable, Sendable {
    public let id: String
    public let slug: String
    public let category: String
    public let status: PoiStatus?
    public let name: String
    public let shortSummary: String
    public let historicalStoryMarkdown: String
    public let audioGuideUrl: String?
    public let latitude: Double
    public let longitude: Double
    public let altitudeMeters: Double?
    public let activationRadiusMeters: Double
    public let distanceMeters: Double?
    public let canActivateAr: Bool?
    public let coverImageUrl: String?
    public let model3dAssetUrl: String?
    public let arInfoCards: [ArInfoCardDto]?
    public let highlightedCommunityPhotos: [CommunityPhotoDto]?

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: FlexibleCodingKey.self)
        id = try container.first(String.self, "id") ?? ""
        slug = try container.first(String.self, "code", "slug") ?? id
        category = try container.first(String.self, "category") ?? "Heritage"
        status = try container.first(PoiStatus.self, "status")
        name = try container.first(String.self, "name", "title") ?? slug
        shortSummary = try container.first(String.self, "shortSummary") ?? ""
        historicalStoryMarkdown = try container.first(String.self, "culturalStoryMarkdown", "historicalStoryMarkdown") ?? ""
        audioGuideUrl = try container.first(String.self, "audioGuideUrl")
        latitude = try container.first(Double.self, "latitude") ?? 0
        longitude = try container.first(Double.self, "longitude") ?? 0
        altitudeMeters = try container.first(Double.self, "altitudeMeters", "elevationMeters")
        activationRadiusMeters = try container.first(Double.self, "activationRadiusMeters") ?? 50
        distanceMeters = try container.first(Double.self, "distanceMeters")
        canActivateAr = try container.first(Bool.self, "canActivateAr")
        coverImageUrl = try container.first(String.self, "coverImageUrl")
        model3dAssetUrl = try container.first(String.self, "model3dAssetUrl")
        arInfoCards = try container.first([ArInfoCardDto].self, "arInfoCards")
        highlightedCommunityPhotos = try container.first([CommunityPhotoDto].self, "highlightedCommunityPhotos")
    }
}

// MARK: - AR Context Response
public struct ArContextResponse: Decodable, Equatable, Sendable {
    public let poiId: String
    public let poiName: String
    public let poiLatitude: Double
    public let poiLongitude: Double
    public let userDistanceMeters: Double
    public let arInfoCards: [ArInfoCardDto]
    public let highlightedCommunityPhotos: [CommunityPhotoDto]
}

// MARK: - Paginated & Action Responses
public struct PagedResponse<T: Decodable & Sendable>: Decodable, Sendable {
    public let items: [T]
    public let page: Int
    public let pageSize: Int
    public let totalCount: Int
}

public struct LikePhotoResponse: Decodable, Sendable {
    public let photoId: String
    public let liked: Bool
    public let likesCount: Int

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: FlexibleCodingKey.self)
        photoId = try container.first(String.self, "photoId") ?? ""
        liked = try container.first(Bool.self, "isLiked", "liked") ?? false
        likesCount = try container.first(Int.self, "likeCount", "likesCount") ?? 0
    }
}

public struct ReportPhotoRequest: Codable, Sendable {
    public let reason: String
    public let details: String?

    public init(reason: String, details: String? = nil) {
        self.reason = reason
        self.details = details
    }
}

public struct GenericMessageResponse: Codable, Sendable {
    public let message: String?
    public let status: String?
}

// MARK: - Admin & SuperAdmin DTOs
public struct PoiTranslationInput: Codable, Equatable, Sendable {
    public var languageCode: String
    public var name: String
    public var shortSummary: String
    public var historicalStoryMarkdown: String
    public var audioGuideUrl: String?

    private enum CodingKeys: String, CodingKey {
        case languageCode
        case name = "name"
        case shortSummary
        case historicalStoryMarkdown = "culturalStoryMarkdown"
        case audioGuideUrl
    }

    public init(
        languageCode: String,
        name: String,
        shortSummary: String,
        historicalStoryMarkdown: String,
        audioGuideUrl: String? = nil
    ) {
        self.languageCode = languageCode
        self.name = name
        self.shortSummary = shortSummary
        self.historicalStoryMarkdown = historicalStoryMarkdown
        self.audioGuideUrl = audioGuideUrl
    }
}

public struct ArInfoCardInput: Codable, Equatable, Identifiable, Sendable {
    public var id = UUID()
    public var azimuthDegrees: Double
    public var elevationMeters: Double
    public var distanceMeters: Double
    public var cardType: String
    public var mediaUrl: String?
    public var titleVi: String
    public var summaryVi: String
    public var titleEn: String
    public var summaryEn: String

    enum CodingKeys: String, CodingKey {
        case azimuthDegrees
        case elevationMeters
        case distanceMeters
        case cardType
        case mediaUrl
        case titleVi
        case summaryVi
        case titleEn
        case summaryEn
    }

    public init(
        azimuthDegrees: Double = 0,
        elevationMeters: Double = 1.6,
        distanceMeters: Double = 4.0,
        cardType: String = "HistoricalFact",
        mediaUrl: String? = nil,
        titleVi: String = "",
        summaryVi: String = "",
        titleEn: String = "",
        summaryEn: String = ""
    ) {
        self.azimuthDegrees = azimuthDegrees
        self.elevationMeters = elevationMeters
        self.distanceMeters = distanceMeters
        self.cardType = cardType
        self.mediaUrl = mediaUrl
        self.titleVi = titleVi
        self.summaryVi = summaryVi
        self.titleEn = titleEn
        self.summaryEn = summaryEn
    }
}

public struct ArCardLocalizationInput: Codable, Sendable {
    public let languageCode: String
    public let title: String
    public let subtitle: String?
    public let content: String
}

public struct UpsertArInfoCardRequest: Encodable, Sendable {
    public let cardType: String
    public let azimuthDegrees: Double
    public let distanceMeters: Double
    public let elevationMeters: Double
    public let displayOrder: Int
    public let isActive: Bool
    public let localizations: [ArCardLocalizationInput]
}

public struct AdminArInfoCardDto: Decodable, Sendable {
    public let id: String
    public let poiId: String
    public let cardType: String
    public let azimuthDegrees: Double
    public let distanceMeters: Double
    public let elevationMeters: Double
    public let displayOrder: Int
    public let isActive: Bool
    public let localizations: [ArCardLocalizationInput]
}

public struct UpsertPoiRequest: Encodable, Sendable {
    public let slug: String
    public let category: String
    public let latitude: Double
    public let longitude: Double
    public let altitudeMeters: Double
    public let activationRadiusMeters: Double
    public let coverImageUrl: String?
    public let translations: [PoiTranslationInput]
    public let arInfoCards: [ArInfoCardInput]?

    private enum CodingKeys: String, CodingKey {
        case slug = "code"
        case latitude
        case longitude
        case activationRadiusMeters
        case coverImageUrl
        case translations = "localizations"
        case saveAsDraft
    }

    public init(
        slug: String,
        category: String,
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double = 10.0,
        activationRadiusMeters: Double = 80.0,
        coverImageUrl: String? = nil,
        translations: [PoiTranslationInput],
        arInfoCards: [ArInfoCardInput]? = nil
    ) {
        self.slug = slug
        self.category = category
        self.latitude = latitude
        self.longitude = longitude
        self.altitudeMeters = altitudeMeters
        self.activationRadiusMeters = activationRadiusMeters
        self.coverImageUrl = coverImageUrl
        self.translations = translations
        self.arInfoCards = arInfoCards
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(slug, forKey: .slug)
        try container.encode(latitude, forKey: .latitude)
        try container.encode(longitude, forKey: .longitude)
        try container.encode(activationRadiusMeters, forKey: .activationRadiusMeters)
        try container.encodeIfPresent(coverImageUrl, forKey: .coverImageUrl)
        try container.encode(translations, forKey: .translations)
        try container.encode(false, forKey: .saveAsDraft)
    }
}

public struct AdminPoiDto: Decodable, Identifiable, Equatable, Sendable {
    public let id: String
    public let slug: String
    public let category: String
    public let status: PoiStatus
    public let name: String
    public let shortSummary: String
    public let latitude: Double
    public let longitude: Double
    public let activationRadiusMeters: Double
    public let createdByUserId: String?
    public let createdByName: String?
    public let approvedByUserId: String?
    public let rejectionReason: String?
    public let updatedAt: String?

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: FlexibleCodingKey.self)
        let localizations = try container.first([PoiLocalizationWire].self, "localizations") ?? []
        let vi = localizations.first { $0.languageCode.lowercased() == "vi" }
        let en = localizations.first { $0.languageCode.lowercased() == "en" }
        let fallback = localizations.first

        id = try container.first(String.self, "id") ?? ""
        slug = try container.first(String.self, "code", "slug") ?? id
        category = try container.first(String.self, "category") ?? "Heritage"
        status = try container.first(PoiStatus.self, "status") ?? .draft
        name = try container.first(String.self, "name") ?? vi?.name ?? en?.name ?? fallback?.name ?? slug
        shortSummary = try container.first(String.self, "shortSummary") ?? vi?.shortSummary ?? en?.shortSummary ?? fallback?.shortSummary ?? ""
        latitude = try container.first(Double.self, "latitude") ?? 0
        longitude = try container.first(Double.self, "longitude") ?? 0
        activationRadiusMeters = try container.first(Double.self, "activationRadiusMeters") ?? 50
        createdByUserId = try container.first(String.self, "createdBy", "createdByUserId")
        createdByName = try container.first(String.self, "createdByName") ?? createdByUserId
        approvedByUserId = try container.first(String.self, "approvedBy", "approvedByUserId")
        rejectionReason = try container.first(String.self, "rejectionReason")
        updatedAt = try container.first(String.self, "updatedAt", "createdAt")
    }
}

private struct PoiLocalizationWire: Decodable {
    let languageCode: String
    let name: String
    let shortSummary: String
}

public struct RejectPoiRequest: Codable, Sendable {
    public let reason: String

    public init(reason: String) {
        self.reason = reason
    }
}

public struct ReportedPhotoQueueItemDto: Decodable, Identifiable, Equatable, Sendable {
    public let id: String
    public let poiId: String
    public let poiName: String?
    public let authorDisplayName: String
    public let imageUrl: String
    public let thumbnailUrl: String?
    public let caption: String?
    public let reportsCount: Int
    public let latestReportReason: String?
    public let moderationStatus: String
    public let createdAt: String

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: FlexibleCodingKey.self)
        let reports = try container.first([PhotoReportWire].self, "reports") ?? []
        id = try container.first(String.self, "photoId", "id") ?? ""
        poiId = try container.first(String.self, "poiId") ?? ""
        poiName = try container.first(String.self, "poiName")
        authorDisplayName = try container.first(String.self, "authorDisplayName") ?? ""
        imageUrl = try container.first(String.self, "imageUrl") ?? ""
        thumbnailUrl = try container.first(String.self, "thumbnailUrl")
        caption = try container.first(String.self, "caption")
        reportsCount = try container.first(Int.self, "reportCount", "reportsCount") ?? reports.count
        latestReportReason = try container.first(String.self, "latestReportReason") ?? reports.last?.reason
        moderationStatus = try container.first(String.self, "status", "moderationStatus") ?? "Flagged"
        createdAt = try container.first(String.self, "createdAt") ?? ""
    }
}

private struct PhotoReportWire: Decodable {
    let reason: String
}

public struct ModerationDecisionRequest: Encodable, Sendable {
    public let decision: String // "Approve" or "Remove"
    public let note: String?
    public let hardDeleteFromStorage: Bool

    private enum CodingKeys: String, CodingKey {
        case decision = "action"
        case note
        case hardDeleteFromStorage
    }

    public init(decision: String, note: String? = nil, hardDeleteFromStorage: Bool = false) {
        self.decision = decision
        self.note = note
        self.hardDeleteFromStorage = hardDeleteFromStorage
    }
}
