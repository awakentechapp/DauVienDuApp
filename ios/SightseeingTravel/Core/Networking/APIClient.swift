import Foundation
import UIKit

public enum NetworkError: Error, LocalizedError, Sendable {
    case invalidURL
    case unauthorized
    case forbidden(String)
    case apiError(ApiErrorPayload)
    case imageCompressionFailed
    case invalidResponse(Int)
    case decodingError(String)

    public var errorDescription: String? {
        switch self {
        case .invalidURL:
            return "Địa chỉ máy chủ API không hợp lệ."
        case .unauthorized:
            return "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại."
        case .forbidden(let reason):
            return reason
        case .apiError(let payload):
            return payload.detail
        case .imageCompressionFailed:
            return "Không thể xử lý và nén ảnh JPEG (tối đa 1920px, 5MB)."
        case .invalidResponse(let code):
            return "Máy chủ trả về mã lỗi HTTP \(code)."
        case .decodingError(let message):
            return "Lỗi giải mã dữ liệu từ máy chủ: \(message)"
        }
    }
}

public actor APIClient {
    public static let shared = APIClient()

    private var baseURLString: String
    private var preferredLanguage: String = "vi"
    private let session: URLSession
    private let decoder: JSONDecoder
    private let encoder: JSONEncoder
    private var isRefreshing = false

    public init(baseURLString: String? = nil) {
        if let customBase = baseURLString {
            self.baseURLString = customBase
        } else if let envUrl = ProcessInfo.processInfo.environment["SIGHTSEEING_API_BASE_URL"], !envUrl.isEmpty {
            self.baseURLString = envUrl
        } else {
            self.baseURLString = "http://localhost:5000/api/v1"
        }

        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 60
        self.session = URLSession(configuration: config)

        let jsonDecoder = JSONDecoder()
        jsonDecoder.keyDecodingStrategy = .convertFromSnakeCase
        self.decoder = jsonDecoder

        let jsonEncoder = JSONEncoder()
        self.encoder = jsonEncoder
    }

    public func setLanguage(_ lang: AppLanguage) {
        self.preferredLanguage = lang.rawValue
    }

    public func setBaseURL(_ urlString: String) {
        self.baseURLString = urlString.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }

    // MARK: - Auth Endpoints

    public func register(request: RegisterRequest) async throws -> AuthTokenResponse {
        let response: AuthTokenResponse = try await sendRequest(
            path: "/auth/register",
            method: "POST",
            body: request,
            requiresAuth: false
        )
        KeychainTokenStore.shared.saveTokens(
            accessToken: response.accessToken,
            refreshToken: response.refreshToken
        )
        return response
    }

    public func login(request: LoginRequest) async throws -> AuthTokenResponse {
        let response: AuthTokenResponse = try await sendRequest(
            path: "/auth/login",
            method: "POST",
            body: request,
            requiresAuth: false
        )
        KeychainTokenStore.shared.saveTokens(
            accessToken: response.accessToken,
            refreshToken: response.refreshToken
        )
        return response
    }

    public func refreshToken() async throws -> AuthTokenResponse {
        guard let currentRefresh = KeychainTokenStore.shared.refreshToken, !currentRefresh.isEmpty else {
            throw NetworkError.unauthorized
        }
        let payload = RefreshTokenRequest(refreshToken: currentRefresh)
        let response: AuthTokenResponse = try await sendRequest(
            path: "/auth/refresh",
            method: "POST",
            body: payload,
            requiresAuth: false,
            allowRetryOn401: false
        )
        KeychainTokenStore.shared.saveTokens(
            accessToken: response.accessToken,
            refreshToken: response.refreshToken
        )
        return response
    }

    public func logout() async {
        if let refreshToken = KeychainTokenStore.shared.refreshToken {
            let payload = LogoutRequest(refreshToken: refreshToken)
            let _: GenericMessageResponse? = try? await sendRequest(
                path: "/auth/logout",
                method: "POST",
                body: payload,
                requiresAuth: true,
                allowRetryOn401: false
            )
        }
        KeychainTokenStore.shared.clearTokens()
    }

    public func getCurrentUser() async throws -> UserProfileDto {
        try await sendRequest(path: "/users/me", method: "GET")
    }

    public func updateCurrentUser(_ request: UpdateProfileRequest) async throws -> UserProfileDto {
        try await sendRequest(path: "/users/me", method: "PUT", body: request)
    }

    // MARK: - POI & Geospatial Endpoints

    public func getNearbyPois(
        lat: Double,
        lng: Double,
        radiusKm: Double = 10.0,
        lang: AppLanguage
    ) async throws -> [NearbyPoiDto] {
        let queryItems = [
            URLQueryItem(name: "lat", value: String(format: "%.6f", lat)),
            URLQueryItem(name: "lng", value: String(format: "%.6f", lng)),
            URLQueryItem(name: "radiusKm", value: String(format: "%.1f", radiusKm)),
            URLQueryItem(name: "lang", value: lang.rawValue)
        ]
        return try await sendFlexibleListRequest(path: "/pois/nearby", queryItems: queryItems)
    }

    public func getPoiDetail(
        poiId: String,
        lat: Double?,
        lng: Double?,
        lang: AppLanguage
    ) async throws -> PoiDetailDto {
        var queryItems = [URLQueryItem(name: "lang", value: lang.rawValue)]
        if let lat = lat, let lng = lng {
            queryItems.append(URLQueryItem(name: "lat", value: String(format: "%.6f", lat)))
            queryItems.append(URLQueryItem(name: "lng", value: String(format: "%.6f", lng)))
        }
        return try await sendRequest(path: "/pois/\(poiId)", method: "GET", queryItems: queryItems)
    }

    public func getPoiArContext(
        poiId: String,
        lat: Double,
        lng: Double,
        lang: AppLanguage
    ) async throws -> ArContextResponse {
        let queryItems = [
            URLQueryItem(name: "lat", value: String(format: "%.6f", lat)),
            URLQueryItem(name: "lng", value: String(format: "%.6f", lng)),
            URLQueryItem(name: "lang", value: lang.rawValue)
        ]
        return try await sendRequest(path: "/pois/\(poiId)/ar-context", method: "GET", queryItems: queryItems)
    }

    // MARK: - Community Photos & Social Endpoints

    public func getPoiPhotos(
        poiId: String,
        sortBy: String = "popular",
        page: Int = 1,
        pageSize: Int = 20
    ) async throws -> [CommunityPhotoDto] {
        let queryItems = [
            URLQueryItem(name: "sortBy", value: sortBy),
            URLQueryItem(name: "page", value: String(page)),
            URLQueryItem(name: "pageSize", value: String(pageSize))
        ]
        return try await sendFlexibleListRequest(path: "/pois/\(poiId)/photos", queryItems: queryItems)
    }

    public func uploadCommunityPhoto(
        poiId: String,
        image: UIImage,
        capturedLatitude: Double,
        capturedLongitude: Double,
        azimuthDegrees: Double?,
        caption: String?
    ) async throws -> CommunityPhotoDto {
        guard let compressedJpegData = Self.compressImageForUpload(image) else {
            throw NetworkError.imageCompressionFailed
        }

        let boundary = "Boundary-\(UUID().uuidString)"
        var bodyData = Data()

        func appendFormField(name: String, value: String) {
            bodyData.append("--\(boundary)\r\n".data(using: .utf8)!)
            bodyData.append("Content-Disposition: form-data; name=\"\(name)\"\r\n\r\n".data(using: .utf8)!)
            bodyData.append("\(value)\r\n".data(using: .utf8)!)
        }

        appendFormField(name: "capturedLatitude", value: String(format: "%.6f", capturedLatitude))
        appendFormField(name: "capturedLongitude", value: String(format: "%.6f", capturedLongitude))
        if let azimuth = azimuthDegrees {
            appendFormField(name: "azimuthDegrees", value: String(format: "%.2f", azimuth))
        }
        if let caption = caption, !caption.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            appendFormField(name: "caption", value: caption.trimmingCharacters(in: .whitespacesAndNewlines))
        }

        let filename = "poi_\(poiId)_\(Int(Date().timeIntervalSince1970)).jpg"
        bodyData.append("--\(boundary)\r\n".data(using: .utf8)!)
        bodyData.append("Content-Disposition: form-data; name=\"file\"; filename=\"\(filename)\"\r\n".data(using: .utf8)!)
        bodyData.append("Content-Type: image/jpeg\r\n\r\n".data(using: .utf8)!)
        bodyData.append(compressedJpegData)
        bodyData.append("\r\n".data(using: .utf8)!)
        bodyData.append("--\(boundary)--\r\n".data(using: .utf8)!)

        return try await sendRawDataRequest(
            path: "/pois/\(poiId)/photos",
            method: "POST",
            contentType: "multipart/form-data; boundary=\(boundary)",
            bodyData: bodyData
        )
    }

    public func toggleLikePhoto(photoId: String) async throws -> LikePhotoResponse {
        try await sendRequest(path: "/photos/\(photoId)/like", method: "POST")
    }

    public func reportPhoto(photoId: String, reason: String, details: String? = nil) async throws {
        let payload = ReportPhotoRequest(reason: reason, details: details)
        let _: GenericMessageResponse? = try await sendRequest(
            path: "/photos/\(photoId)/report",
            method: "POST",
            body: payload
        )
    }

    public func deletePhoto(photoId: String) async throws {
        let _: GenericMessageResponse? = try await sendRequest(
            path: "/photos/\(photoId)",
            method: "DELETE"
        )
    }

    // MARK: - Admin & SuperAdmin Endpoints

    public func getAdminPois(status: PoiStatus? = nil, lang: AppLanguage = .vi) async throws -> [AdminPoiDto] {
        var queryItems = [URLQueryItem(name: "lang", value: lang.rawValue)]
        if let status = status {
            queryItems.append(URLQueryItem(name: "status", value: status.rawValue))
        }
        return try await sendFlexibleListRequest(path: "/admin/pois", queryItems: queryItems)
    }

    public func createAdminPoi(_ request: UpsertPoiRequest) async throws -> AdminPoiDto {
        try await sendRequest(path: "/admin/pois", method: "POST", body: request)
    }

    public func updateAdminPoi(poiId: String, request: UpsertPoiRequest) async throws -> AdminPoiDto {
        try await sendRequest(path: "/admin/pois/\(poiId)", method: "PUT", body: request)
    }

    public func approvePoi(poiId: String) async throws -> AdminPoiDto {
        try await sendRequest(path: "/admin/pois/\(poiId)/approve", method: "POST")
    }

    public func rejectPoi(poiId: String, reason: String) async throws -> AdminPoiDto {
        let payload = RejectPoiRequest(reason: reason)
        return try await sendRequest(path: "/admin/pois/\(poiId)/reject", method: "POST", body: payload)
    }

    public func getModerationPhotoQueue() async throws -> [ReportedPhotoQueueItemDto] {
        try await sendFlexibleListRequest(path: "/admin/moderation/photos", queryItems: [])
    }

    public func submitPhotoModerationDecision(
        photoId: String,
        decision: String,
        note: String? = nil
    ) async throws {
        let payload = ModerationDecisionRequest(decision: decision, note: note)
        let _: GenericMessageResponse? = try await sendRequest(
            path: "/admin/moderation/photos/\(photoId)/decision",
            method: "POST",
            body: payload
        )
    }

    // MARK: - Image Compression Helper (<= 1920px, <= 5MB JPEG)

    public static func compressImageForUpload(_ image: UIImage) -> Data? {
        let maxDimension: CGFloat = 1920.0
        let maxBytes = 5 * 1024 * 1024 // 5 MB

        var targetImage = image
        let size = image.size
        if max(size.width, size.height) > maxDimension {
            let ratio = maxDimension / max(size.width, size.height)
            let newSize = CGSize(width: floor(size.width * ratio), height: floor(size.height * ratio))
            let format = UIGraphicsImageRendererFormat.default()
            format.scale = 1.0
            let renderer = UIGraphicsImageRenderer(size: newSize, format: format)
            targetImage = renderer.image { _ in
                image.draw(in: CGRect(origin: .zero, size: newSize))
            }
        }

        var compressionQuality: CGFloat = 0.85
        while compressionQuality >= 0.3 {
            if let data = targetImage.jpegData(compressionQuality: compressionQuality),
               data.count <= maxBytes {
                return data
            }
            compressionQuality -= 0.15
        }
        return targetImage.jpegData(compressionQuality: 0.25)
    }

    // MARK: - Core HTTP Request Execution with Automatic 401 Refresh

    private func sendFlexibleListRequest<T: Codable & Sendable>(
        path: String,
        queryItems: [URLQueryItem]
    ) async throws -> [T] {
        let data = try await performRawRequest(
            path: path,
            method: "GET",
            queryItems: queryItems,
            contentType: "application/json",
            bodyData: nil,
            requiresAuth: true,
            allowRetryOn401: true
        )
        if let directArray = try? decoder.decode([T].self, from: data) {
            return directArray
        }
        if let paged = try? decoder.decode(PagedResponse<T>.self, from: data) {
            return paged.items
        }
        throw NetworkError.decodingError("Expected array or PagedResponse for \(T.self)")
    }

    private func sendRequest<Response: Codable & Sendable>(
        path: String,
        method: String,
        queryItems: [URLQueryItem] = [],
        requiresAuth: Bool = true,
        allowRetryOn401: Bool = true
    ) async throws -> Response {
        let data = try await performRawRequest(
            path: path,
            method: method,
            queryItems: queryItems,
            contentType: "application/json",
            bodyData: nil,
            requiresAuth: requiresAuth,
            allowRetryOn401: allowRetryOn401
        )
        if data.isEmpty, let emptyMessage = GenericMessageResponse(message: "OK", status: "OK") as? Response {
            return emptyMessage
        }
        return try decoder.decode(Response.self, from: data)
    }

    private func sendRequest<Body: Encodable & Sendable, Response: Codable & Sendable>(
        path: String,
        method: String,
        queryItems: [URLQueryItem] = [],
        body: Body,
        requiresAuth: Bool = true,
        allowRetryOn401: Bool = true
    ) async throws -> Response {
        let bodyData = try encoder.encode(body)
        let data = try await performRawRequest(
            path: path,
            method: method,
            queryItems: queryItems,
            contentType: "application/json",
            bodyData: bodyData,
            requiresAuth: requiresAuth,
            allowRetryOn401: allowRetryOn401
        )
        if data.isEmpty, let emptyMessage = GenericMessageResponse(message: "OK", status: "OK") as? Response {
            return emptyMessage
        }
        return try decoder.decode(Response.self, from: data)
    }

    private func sendRawDataRequest<Response: Codable & Sendable>(
        path: String,
        method: String,
        contentType: String,
        bodyData: Data
    ) async throws -> Response {
        let data = try await performRawRequest(
            path: path,
            method: method,
            queryItems: [],
            contentType: contentType,
            bodyData: bodyData,
            requiresAuth: true,
            allowRetryOn401: true
        )
        return try decoder.decode(Response.self, from: data)
    }

    private func performRawRequest(
        path: String,
        method: String,
        queryItems: [URLQueryItem],
        contentType: String,
        bodyData: Data?,
        requiresAuth: Bool,
        allowRetryOn401: Bool
    ) async throws -> Data {
        let fullPath = path.hasPrefix("/") ? "\(baseURLString)\(path)" : "\(baseURLString)/\(path)"
        guard var components = URLComponents(string: fullPath) else {
            throw NetworkError.invalidURL
        }
        if !queryItems.isEmpty {
            components.queryItems = queryItems
        }
        guard let url = components.url else {
            throw NetworkError.invalidURL
        }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(preferredLanguage, forHTTPHeaderField: "Accept-Language")

        if requiresAuth, let token = KeychainTokenStore.shared.accessToken, !token.isEmpty {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        request.httpBody = bodyData

        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw NetworkError.invalidResponse(0)
        }

        switch httpResponse.statusCode {
        case 200...299:
            return data
        case 401:
            if requiresAuth && allowRetryOn401 && !isRefreshing {
                isRefreshing = true
                defer { isRefreshing = false }
                do {
                    _ = try await refreshToken()
                    return try await performRawRequest(
                        path: path,
                        method: method,
                        queryItems: queryItems,
                        contentType: contentType,
                        bodyData: bodyData,
                        requiresAuth: requiresAuth,
                        allowRetryOn401: false
                    )
                } catch {
                    KeychainTokenStore.shared.clearTokens()
                    throw NetworkError.unauthorized
                }
            }
            throw NetworkError.unauthorized
        case 403:
            if let apiErr = try? decoder.decode(ApiErrorPayload.self, from: data) {
                throw NetworkError.apiError(apiErr)
            }
            throw NetworkError.forbidden("Bạn không có quyền thực hiện thao tác này hoặc chưa nằm trong phạm vi GPS của điểm tham quan.")
        default:
            if let apiErr = try? decoder.decode(ApiErrorPayload.self, from: data) {
                throw NetworkError.apiError(apiErr)
            }
            throw NetworkError.invalidResponse(httpResponse.statusCode)
        }
    }
}
