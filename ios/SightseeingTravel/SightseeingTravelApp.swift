import SwiftUI

@MainActor
public final class AppState: ObservableObject {
    @Published public var currentUser: UserProfileDto?
    @Published public var isAuthenticated: Bool = false
    @Published public var isInitializing: Bool = true
    @Published public var selectedLanguage: AppLanguage = .vi
    @Published public var authErrorMessage: String?
    @Published public var isBusy: Bool = false
    @Published public var myUploadedPhotos: [CommunityPhotoDto] = []

    public init() {
        Task {
            await restoreSessionIfAvailable()
        }
    }

    public func restoreSessionIfAvailable() async {
        defer { isInitializing = false }
        guard let token = KeychainTokenStore.shared.accessToken, !token.isEmpty else {
            isAuthenticated = false
            currentUser = nil
            return
        }
        do {
            let profile = try await APIClient.shared.getCurrentUser()
            applyAuthenticatedUser(profile)
        } catch {
            KeychainTokenStore.shared.clearTokens()
            isAuthenticated = false
            currentUser = nil
        }
    }

    public func login(email: String, password: String) async {
        isBusy = true
        authErrorMessage = nil
        defer { isBusy = false }

        do {
            let response = try await APIClient.shared.login(
                request: LoginRequest(email: email.trimmingCharacters(in: .whitespacesAndNewlines), password: password)
            )
            applyAuthenticatedUser(response.user)
        } catch {
            authErrorMessage = error.localizedDescription
        }
    }

    public func register(email: String, password: String, displayName: String, language: AppLanguage) async {
        isBusy = true
        authErrorMessage = nil
        defer { isBusy = false }

        do {
            let response = try await APIClient.shared.register(
                request: RegisterRequest(
                    email: email.trimmingCharacters(in: .whitespacesAndNewlines),
                    password: password,
                    displayName: displayName.trimmingCharacters(in: .whitespacesAndNewlines),
                    preferredLanguage: language.rawValue
                )
            )
            applyAuthenticatedUser(response.user)
        } catch {
            authErrorMessage = error.localizedDescription
        }
    }

    public func switchLanguage(_ language: AppLanguage) async {
        selectedLanguage = language
        await APIClient.shared.setLanguage(language)
        guard let user = currentUser else { return }
        if let updated = try? await APIClient.shared.updateCurrentUser(
            UpdateProfileRequest(
                displayName: user.displayName,
                preferredLanguage: language.rawValue,
                avatarUrl: user.avatarUrl
            )
        ) {
            currentUser = updated
        }
    }

    public func updateDisplayName(_ newName: String) async throws {
        guard let user = currentUser else { return }
        let updated = try await APIClient.shared.updateCurrentUser(
            UpdateProfileRequest(
                displayName: newName.trimmingCharacters(in: .whitespacesAndNewlines),
                preferredLanguage: selectedLanguage.rawValue,
                avatarUrl: user.avatarUrl
            )
        )
        currentUser = updated
    }

    public func recordUploadedPhoto(_ photo: CommunityPhotoDto) {
        if !myUploadedPhotos.contains(where: { $0.id == photo.id }) {
            myUploadedPhotos.insert(photo, at: 0)
        }
    }

    public func deleteOwnPhoto(_ photo: CommunityPhotoDto) async throws {
        try await APIClient.shared.deletePhoto(photoId: photo.id)
        myUploadedPhotos.removeAll { $0.id == photo.id }
    }

    public func logout() async {
        isBusy = true
        await APIClient.shared.logout()
        currentUser = nil
        isAuthenticated = false
        myUploadedPhotos = []
        isBusy = false
    }

    private func applyAuthenticatedUser(_ user: UserProfileDto) {
        currentUser = user
        if let lang = AppLanguage(rawValue: user.preferredLanguage.lowercased()) {
            selectedLanguage = lang
            Task {
                await APIClient.shared.setLanguage(lang)
            }
        }
        isAuthenticated = true
    }
}

@main
public struct SightseeingTravelApp: App {
    @StateObject private var appState = AppState()
    @StateObject private var locationManager = LocationManager.shared

    public init() {}

    public var body: some Scene {
        WindowGroup {
            Group {
                if appState.isInitializing {
                    ProgressView("Đang khởi tạo Sightseeing Travel AR...")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if appState.isAuthenticated, appState.currentUser != nil {
                    MainTabView()
                        .environmentObject(appState)
                        .environmentObject(locationManager)
                } else {
                    AuthScreen()
                        .environmentObject(appState)
                }
            }
        }
    }
}

// MARK: - Main Authenticated Tab Router

public struct MainTabView: View {
    @EnvironmentObject private var appState: AppState
    @EnvironmentObject private var locationManager: LocationManager
    @State private var selectedTab: Int = 0

    public init() {}

    public var body: some View {
        TabView(selection: $selectedTab) {
            SightseeingMapScreen()
                .tabItem {
                    Label(
                        appState.selectedLanguage == .vi ? "Bản đồ & AR" : "Map & AR",
                        systemImage: "map.fill"
                    )
                }
                .tag(0)

            MyPhotosView()
                .tabItem {
                    Label(
                        appState.selectedLanguage == .vi ? "Ảnh của tôi" : "My Photos",
                        systemImage: "photo.on.rectangle.angled"
                    )
                }
                .tag(1)

            if let role = appState.currentUser?.role, role.isAdminOrAbove {
                AdminModerationConsoleView()
                    .tabItem {
                        Label(
                            role.isSuperAdmin
                                ? (appState.selectedLanguage == .vi ? "Duyệt & Quản trị" : "SuperAdmin")
                                : (appState.selectedLanguage == .vi ? "Quản trị POI" : "Admin Console"),
                            systemImage: "checkmark.shield.fill"
                        )
                    }
                    .tag(2)
            }

            ProfileSettingsView()
                .tabItem {
                    Label(
                        appState.selectedLanguage == .vi ? "Tài khoản" : "Profile",
                        systemImage: "person.crop.circle.fill"
                    )
                }
                .tag(3)
        }
        .tint(.teal)
        .onAppear {
            locationManager.requestPermissionsAndStartTracking()
        }
    }
}

// MARK: - My Uploaded Photos Screen

public struct MyPhotosView: View {
    @EnvironmentObject private var appState: AppState
    @State private var errorMessage: String?
    @State private var deletingPhotoId: String?

    public init() {}

    public var body: some View {
        NavigationStack {
            Group {
                if appState.myUploadedPhotos.isEmpty {
                    ContentUnavailableView(
                        appState.selectedLanguage == .vi
                            ? "Chưa có ảnh kỷ niệm nào"
                            : "No Captured Photos Yet",
                        systemImage: "camera.viewfinder",
                        description: Text(
                            appState.selectedLanguage == .vi
                                ? "Hãy di chuyển đến các điểm tham quan trên bản đồ, mở khóa trải nghiệm AR hoặc chụp ảnh tại điểm để chia sẻ cùng cộng đồng!"
                                : "Visit published POIs on the map, unlock the AR camera, and capture on-site photos to share with fellow travelers!"
                        )
                    )
                } else {
                    List {
                        ForEach(appState.myUploadedPhotos) { photo in
                            HStack(spacing: 14) {
                                AsyncImage(url: URL(string: photo.bestThumbnailOrImageUrl)) { phase in
                                    switch phase {
                                    case .success(let img):
                                        img.resizable().scaledToFill()
                                    default:
                                        ZStack {
                                            Color.gray.opacity(0.18)
                                            Image(systemName: "photo")
                                                .foregroundStyle(.secondary)
                                        }
                                    }
                                }
                                .frame(width: 84, height: 84)
                                .clipShape(RoundedRectangle(cornerRadius: 12))

                                VStack(alignment: .leading, spacing: 6) {
                                    Text(photo.caption ?? (appState.selectedLanguage == .vi ? "Ảnh chụp tại điểm tham quan" : "Captured at POI"))
                                        .font(.headline)
                                        .lineLimit(2)

                                    HStack(spacing: 12) {
                                        Label("\(photo.likesCount)", systemImage: "heart.fill")
                                            .font(.caption)
                                            .foregroundStyle(.pink)

                                        if let azimuth = photo.azimuthDegrees {
                                            Label("\(Int(azimuth))°", systemImage: "safari")
                                                .font(.caption)
                                                .foregroundStyle(.secondary)
                                        }
                                    }

                                    Text(photo.createdAt)
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                }

                                Spacer()

                                Button(role: .destructive) {
                                    Task {
                                        deletingPhotoId = photo.id
                                        do {
                                            try await appState.deleteOwnPhoto(photo)
                                        } catch {
                                            errorMessage = error.localizedDescription
                                        }
                                        deletingPhotoId = nil
                                    }
                                } label: {
                                    if deletingPhotoId == photo.id {
                                        ProgressView()
                                    } else {
                                        Image(systemName: "trash")
                                    }
                                }
                                .buttonStyle(.borderless)
                            }
                            .padding(.vertical, 4)
                        }
                    }
                }
            }
            .navigationTitle(appState.selectedLanguage == .vi ? "Ảnh Đã Chia Sẻ" : "My Shared Photos")
            .alert(
                appState.selectedLanguage == .vi ? "Thông báo" : "Notice",
                isPresented: Binding(
                    get: { errorMessage != nil },
                    set: { if !$0 { errorMessage = nil } }
                )
            ) {
                Button("OK", role: .cancel) { errorMessage = nil }
            } message: {
                Text(errorMessage ?? "")
            }
        }
    }
}

// MARK: - Profile & Language Switcher View

public struct ProfileSettingsView: View {
    @EnvironmentObject private var appState: AppState
    @EnvironmentObject private var locationManager: LocationManager
    @State private var editedDisplayName: String = ""
    @State private var customApiUrl: String = "http://localhost:5000/api/v1"
    @State private var statusBanner: String?

    public init() {}

    public var body: some View {
        NavigationStack {
            Form {
                if let user = appState.currentUser {
                    Section(appState.selectedLanguage == .vi ? "Thông tin tài khoản" : "Account Information") {
                        HStack(spacing: 14) {
                            Image(systemName: "person.crop.circle.badge.checkmark")
                                .font(.system(size: 44))
                                .foregroundStyle(.teal)

                            VStack(alignment: .leading, spacing: 4) {
                                Text(user.displayName)
                                    .font(.headline)
                                Text(user.email)
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                                RoleBadgeView(role: user.role)
                            }
                        }
                        .padding(.vertical, 4)

                        HStack {
                            TextField(
                                appState.selectedLanguage == .vi ? "Tên hiển thị" : "Display Name",
                                text: $editedDisplayName
                            )
                            Button(appState.selectedLanguage == .vi ? "Lưu" : "Save") {
                                Task {
                                    do {
                                        try await appState.updateDisplayName(editedDisplayName)
                                        statusBanner = appState.selectedLanguage == .vi
                                            ? "Đã cập nhật hồ sơ thành công."
                                            : "Profile updated successfully."
                                    } catch {
                                        statusBanner = error.localizedDescription
                                    }
                                }
                            }
                            .disabled(editedDisplayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        }
                    }
                }

                Section(appState.selectedLanguage == .vi ? "Ngôn ngữ thuyết minh (VI / EN)" : "Cultural Story Language") {
                    Picker(
                        appState.selectedLanguage == .vi ? "Ngôn ngữ" : "Language",
                        selection: Binding(
                            get: { appState.selectedLanguage },
                            set: { newLang in
                                Task { await appState.switchLanguage(newLang) }
                            }
                        )
                    ) {
                        ForEach(AppLanguage.allCases) { lang in
                            Text(lang.displayName).tag(lang)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                Section(appState.selectedLanguage == .vi ? "Trạng thái định vị GPS & La bàn" : "Live GPS & Compass Telemetry") {
                    LabeledContent("Latitude", value: String(format: "%.6f", locationManager.latitude))
                    LabeledContent("Longitude", value: String(format: "%.6f", locationManager.longitude))
                    LabeledContent(
                        appState.selectedLanguage == .vi ? "Hướng La bàn (Azimuth)" : "Compass Heading",
                        value: String(format: "%.1f°", locationManager.azimuthDegrees)
                    )
                }

                Section(appState.selectedLanguage == .vi ? "Cấu hình Máy chủ Backend" : "Backend Server Endpoint") {
                    TextField("API Base URL", text: $customApiUrl)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button(appState.selectedLanguage == .vi ? "Áp dụng URL Máy chủ" : "Apply Server URL") {
                        Task {
                            await APIClient.shared.setBaseURL(customApiUrl)
                            statusBanner = "Đã cập nhật API URL: \(customApiUrl)"
                        }
                    }
                }

                if let banner = statusBanner {
                    Section {
                        Text(banner)
                            .font(.footnote)
                            .foregroundStyle(.teal)
                    }
                }

                Section {
                    Button(role: .destructive) {
                        Task {
                            await appState.logout()
                        }
                    } label: {
                        HStack {
                            Spacer()
                            Label(
                                appState.selectedLanguage == .vi ? "Đăng xuất khỏi thiết bị" : "Sign Out",
                                systemImage: "rectangle.portrait.and.arrow.right"
                            )
                            .fontWeight(.semibold)
                            Spacer()
                        }
                    }
                }
            }
            .navigationTitle(appState.selectedLanguage == .vi ? "Tài khoản & Cài đặt" : "Profile & Settings")
            .onAppear {
                if let user = appState.currentUser, editedDisplayName.isEmpty {
                    editedDisplayName = user.displayName
                }
            }
        }
    }
}

public struct RoleBadgeView: View {
    public let role: UserRole

    public init(role: UserRole) {
        self.role = role
    }

    public var body: some View {
        Text(role.rawValue.uppercased())
            .font(.caption2.weight(.bold))
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(badgeColor.opacity(0.16))
            .foregroundStyle(badgeColor)
            .clipShape(Capsule())
    }

    private var badgeColor: Color {
        switch role {
        case .superAdmin: return .purple
        case .admin: return .orange
        case .tourist: return .teal
        }
    }
}
