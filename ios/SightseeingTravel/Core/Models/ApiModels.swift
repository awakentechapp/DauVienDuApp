import Foundation

// MARK: - Standard API Error Payload
public struct ApiErrorPayload: Codable, Error, LocalizedError, Sendable {
    public let errorCode: String
    public let detail: String
    public let status: Int?
    public let traceId: String?

    public var errorDescription: String? {
        "[\(errorCode)] \(detail)"
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
public struct NearbyPoiDto: Codable, Identifiable, Equatable, Hashable, Sendable {
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
}

public struct ArInfoCardDto: Codable, Identifiable, Equatable, Hashable, Sendable {
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
}

public struct CommunityPhotoDto: Codable, Identifiable, Equatable, Hashable, Sendable {
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

    public var bestThumbnailOrImageUrl: String {
        thumbnailUrl ?? imageUrl
    }
}

public struct PoiDetailDto: Codable, Identifiable, Equatable, Sendable {
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
}

// MARK: - AR Context Response
public struct ArContextResponse: Codable, Equatable, Sendable {
    public let poiId: String
    public let poiName: String
    public let poiLatitude: Double
    public let poiLongitude: Double
    public let userDistanceMeters: Double
    public let arInfoCards: [ArInfoCardDto]
    public let highlightedCommunityPhotos: [CommunityPhotoDto]
}

// MARK: - Paginated & Action Responses
public struct PagedResponse<T: Codable & Sendable>: Codable, Sendable {
    public let items: [T]
    public let page: Int
    public let pageSize: Int
    public let totalCount: Int
}

public struct LikePhotoResponse: Codable, Sendable {
    public let photoId: String
    public let liked: Bool
    public let likesCount: Int
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
        cardType: String = "HistoryFact",
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

public struct UpsertPoiRequest: Codable, Sendable {
    public let slug: String
    public let category: String
    public let latitude: Double
    public let longitude: Double
    public let altitudeMeters: Double
    public let activationRadiusMeters: Double
    public let coverImageUrl: String?
    public let translations: [PoiTranslationInput]
    public let arInfoCards: [ArInfoCardInput]?

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
}

public struct AdminPoiDto: Codable, Identifiable, Equatable, Sendable {
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
}

public struct RejectPoiRequest: Codable, Sendable {
    public let reason: String

    public init(reason: String) {
        self.reason = reason
    }
}

public struct ReportedPhotoQueueItemDto: Codable, Identifiable, Equatable, Sendable {
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
}

public struct ModerationDecisionRequest: Codable, Sendable {
    public let decision: String // "Approve" or "Remove"
    public let note: String?

    public init(decision: String, note: String? = nil) {
        self.decision = decision
        self.note = note
    }
}
