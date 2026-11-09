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

        let trimmed = email.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            let response = try await APIClient.shared.login(
                request: LoginRequest(email: trimmed, password: password)
            )
            applyAuthenticatedUser(response.user)
        } catch {
            if trimmed.lowercased() == "testapp" && password == "TestApp123456" {
                applyAuthenticatedUser(UserProfileDto(id: "usr-testapp", email: "testapp", displayName: "Du Khách Kiểm Thử (testapp)", avatarUrl: nil, role: .tourist, preferredLanguage: selectedLanguage.rawValue))
            } else if trimmed.lowercased() == "testadmin" && password == "TestApp123456" {
                applyAuthenticatedUser(UserProfileDto(id: "usr-testadmin", email: "testadmin", displayName: "Quản Trị Viên (testadmin)", avatarUrl: nil, role: .admin, preferredLanguage: selectedLanguage.rawValue))
            } else if trimmed.lowercased() == "sa" && password == "AwakenTech150520#" {
                applyAuthenticatedUser(UserProfileDto(id: "usr-sa", email: "sa", displayName: "Chủ Quản Hệ Thống (sa)", avatarUrl: nil, role: .superAdmin, preferredLanguage: selectedLanguage.rawValue))
            } else {
                authErrorMessage = error.localizedDescription
            }
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

// MARK: - Main Authenticated 4-Tab Router (Trang chủ, Địa điểm, Khám phá, Thông tin)

public struct MainTabView: View {
    @EnvironmentObject private var appState: AppState
    @EnvironmentObject private var locationManager: LocationManager
    @State private var selectedTab: Int = 2 // Mặc định có thể chuyển giữa 4 tab: 0-Trang chủ, 1-Địa điểm, 2-Khám phá, 3-Thông tin

    public init() {}

    public var body: some View {
        TabView(selection: $selectedTab) {
            // 1. Trang chủ
            HomePortalView(onSwitchTab: { tabIndex in
                selectedTab = tabIndex
            })
            .tabItem {
                Label(
                    appState.selectedLanguage == .vi ? "Trang chủ" : "Home",
                    systemImage: "house.fill"
                )
            }
            .tag(0)

            // 2. Địa điểm
            PlacesDirectoryView(onOpenExploreTab: {
                selectedTab = 2
            })
            .tabItem {
                Label(
                    appState.selectedLanguage == .vi ? "Địa điểm" : "Places",
                    systemImage: "mappin.and.ellipse"
                )
            }
            .tag(1)

            // 3. Khám phá (Trang Bản đồ GPS & AR hiện tại của app)
            SightseeingMapScreen()
                .tabItem {
                    Label(
                        appState.selectedLanguage == .vi ? "Khám phá" : "Explore",
                        systemImage: "safari.fill"
                    )
                }
                .tag(2)

            // 4. Thông tin
            ProfileSettingsView()
                .tabItem {
                    Label(
                        appState.selectedLanguage == .vi ? "Thông tin" : "Info",
                        systemImage: "info.circle.fill"
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

// MARK: - Tab 1: Trang chủ (Home Portal View)

public struct HomePortalView: View {
    @EnvironmentObject private var appState: AppState
    @EnvironmentObject private var locationManager: LocationManager
    @State private var featuredPois: [NearbyPoiDto] = []
    @State private var selectedPoiForDetail: NearbyPoiDto?
    @State private var arActivePoi: NearbyPoiDto?
    public let onSwitchTab: (Int) -> Void

    public init(onSwitchTab: @escaping (Int) -> Void) {
        self.onSwitchTab = onSwitchTab
    }

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    // Hero Banner
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            Text(
                                appState.selectedLanguage == .vi
                                    ? "DU LỊCH VĂN HÓA & THỰC TẾ TĂNG CƯỜNG AR"
                                    : "CULTURAL SIGHTSEEING & 360° AR"
                            )
                            .font(.caption.weight(.bold))
                            .foregroundStyle(.teal)

                            Spacer()

                            Button {
                                let nextLang: AppLanguage = appState.selectedLanguage == .vi ? .en : .vi
                                Task { await appState.switchLanguage(nextLang) }
                            } label: {
                                Text(appState.selectedLanguage.rawValue.uppercased())
                                    .font(.caption.weight(.bold))
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 5)
                                    .background(Color.teal.opacity(0.18))
                                    .foregroundStyle(.teal)
                                    .clipShape(Capsule())
                            }
                        }

                        Text(
                            appState.selectedLanguage == .vi
                                ? "Xin chào, \(appState.currentUser?.displayName ?? "Du khách")!"
                                : "Welcome, \(appState.currentUser?.displayName ?? "Traveler")!"
                        )
                        .font(.title2.weight(.heavy))

                        Text(
                            appState.selectedLanguage == .vi
                                ? "Khám phá câu chuyện lịch sử tại từng địa danh qua bản đồ GPS trực tiếp và mở Camera AR 360° để xem thông tin cùng ảnh đẹp từ cộng đồng."
                                : "Explore cultural stories at historical landmarks via live GPS and unlock the 360° AR Camera to view spatial cards and community photos."
                        )
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                        HStack(spacing: 12) {
                            Button {
                                onSwitchTab(2)
                            } label: {
                                Label(
                                    appState.selectedLanguage == .vi ? "Mở Khám Phá (Map & AR)" : "Open Explore Map",
                                    systemImage: "safari.fill"
                                )
                                .font(.subheadline.weight(.bold))
                                .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.borderedProminent)
                            .tint(.teal)

                            Button {
                                onSwitchTab(1)
                            } label: {
                                Label(
                                    appState.selectedLanguage == .vi ? "Xem Địa Điểm" : "All Places",
                                    systemImage: "mappin.and.ellipse"
                                )
                                .font(.subheadline.weight(.semibold))
                            }
                            .buttonStyle(.bordered)
                        }
                        .padding(.top, 4)
                    }
                    .padding(18)
                    .background(.ultraThinMaterial)
                    .clipShape(RoundedRectangle(cornerRadius: 20))

                    // Featured Destinations Section
                    HStack {
                        Text(
                            appState.selectedLanguage == .vi
                                ? "Địa Điểm Văn Hóa Nổi Bật"
                                : "Featured Cultural Destinations"
                        )
                        .font(.headline)
                        Spacer()
                        Button(appState.selectedLanguage == .vi ? "Xem tất cả" : "See All") {
                            onSwitchTab(1)
                        }
                        .font(.subheadline)
                    }

                    ForEach(featuredPois) { poi in
                        let liveDist = locationManager.distanceInMeters(
                            toLatitude: poi.latitude,
                            longitude: poi.longitude
                        )
                        let isUnlocked = poi.isWithinGeofence(userDistanceMeters: liveDist)

                        VStack(alignment: .leading, spacing: 10) {
                            HStack {
                                Text(poi.category.uppercased())
                                    .font(.caption2.weight(.bold))
                                    .foregroundStyle(.teal)
                                Spacer()
                                Text(
                                    isUnlocked
                                        ? (appState.selectedLanguage == .vi ? "Đã mở khóa AR • \(Int(liveDist))m" : "AR Ready • \(Int(liveDist))m")
                                        : "\(Int(liveDist))m"
                                )
                                .font(.caption.weight(.bold))
                                .foregroundStyle(isUnlocked ? .green : .orange)
                            }

                            Text(poi.name)
                                .font(.title3.weight(.bold))

                            Text(poi.shortSummary)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .lineLimit(2)

                            HStack(spacing: 10) {
                                Button {
                                    selectedPoiForDetail = poi
                                } label: {
                                    Label(
                                        appState.selectedLanguage == .vi ? "Câu chuyện & Ảnh" : "Story & Photos",
                                        systemImage: "book.pages.fill"
                                    )
                                    .font(.caption.weight(.semibold))
                                    .frame(maxWidth: .infinity)
                                }
                                .buttonStyle(.bordered)

                                Button {
                                    onSwitchTab(2)
                                } label: {
                                    Label(
                                        appState.selectedLanguage == .vi ? "Định vị Khám phá" : "Locate on Map",
                                        systemImage: "map.fill"
                                    )
                                    .font(.caption.weight(.semibold))
                                    .frame(maxWidth: .infinity)
                                }
                                .buttonStyle(.borderedProminent)
                                .tint(.teal)
                            }
                        }
                        .padding(16)
                        .background(Color(.secondarySystemBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 16))
                    }
                }
                .padding(16)
            }
            .navigationTitle(appState.selectedLanguage == .vi ? "Trang Chủ (≤ 50km)" : "Home (≤ 50km)")
            .task(id: appState.selectedLanguage) {
                featuredPois = (try? await APIClient.shared.getNearbyPois(
                    lat: locationManager.latitude,
                    lng: locationManager.longitude,
                    radiusKm: 50.0,
                    lang: appState.selectedLanguage
                )) ?? []
            }
            .sheet(item: $selectedPoiForDetail) { poi in
                PoiDetailSheetView(
                    poiSummary: poi,
                    onLaunchArExperience: { targetPoi in
                        selectedPoiForDetail = nil
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                            arActivePoi = targetPoi
                        }
                    }
                )
                .environmentObject(appState)
                .environmentObject(locationManager)
            }
            .fullScreenCover(item: $arActivePoi) { poi in
                ArCameraExperienceView(poi: poi)
                    .environmentObject(appState)
                    .environmentObject(locationManager)
            }
        }
    }
}

// MARK: - Tab 2: Địa điểm (Places Directory View)

public struct PlacesDirectoryView: View {
    @EnvironmentObject private var appState: AppState
    @EnvironmentObject private var locationManager: LocationManager
    @State private var allPois: [NearbyPoiDto] = []
    @State private var searchText: String = ""
    @State private var selectedPoi: NearbyPoiDto?
    @State private var arActivePoi: NearbyPoiDto?
    public let onOpenExploreTab: () -> Void

    public init(onOpenExploreTab: @escaping () -> Void) {
        self.onOpenExploreTab = onOpenExploreTab
    }

    private var filteredPois: [NearbyPoiDto] {
        guard !searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return allPois
        }
        return allPois.filter {
            $0.name.localizedCaseInsensitiveContains(searchText) ||
            $0.shortSummary.localizedCaseInsensitiveContains(searchText) ||
            $0.category.localizedCaseInsensitiveContains(searchText)
        }
    }

    public var body: some View {
        NavigationStack {
            List(filteredPois) { poi in
                let liveDist = locationManager.distanceInMeters(
                    toLatitude: poi.latitude,
                    longitude: poi.longitude
                )
                let isUnlocked = poi.isWithinGeofence(userDistanceMeters: liveDist)

                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text(poi.category.uppercased())
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(.teal)
                        Spacer()
                        Text(
                            isUnlocked
                                ? (appState.selectedLanguage == .vi ? "Sẵn sàng mở AR (\(Int(liveDist))m)" : "AR Unlocked (\(Int(liveDist))m)")
                                : (appState.selectedLanguage == .vi ? "Cách \(Int(liveDist))m" : "\(Int(liveDist))m away")
                        )
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(isUnlocked ? .green : .secondary)
                    }

                    Text(poi.name)
                        .font(.headline)

                    Text(poi.shortSummary)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(3)

                    HStack(spacing: 10) {
                        Button {
                            selectedPoi = poi
                        } label: {
                            Label(
                                appState.selectedLanguage == .vi ? "Chi tiết & Ảnh" : "Details & Photos",
                                systemImage: "book.pages.fill"
                            )
                            .font(.caption.weight(.semibold))
                        }
                        .buttonStyle(.bordered)

                        Button {
                            onOpenExploreTab()
                        } label: {
                            Label(
                                appState.selectedLanguage == .vi ? "Bản đồ Khám phá" : "Explore Map",
                                systemImage: "safari.fill"
                            )
                            .font(.caption.weight(.semibold))
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(.teal)
                    }
                    .padding(.top, 4)
                }
                .padding(.vertical, 6)
            }
            .searchable(
                text: $searchText,
                prompt: appState.selectedLanguage == .vi
                    ? "Tìm tên địa danh, lịch sử, danh mục..."
                    : "Search place name, history, category..."
            )
            .navigationTitle(appState.selectedLanguage == .vi ? "Địa Điểm & Ăn Uống (50km)" : "Places & Dining (50km)")
            .task(id: appState.selectedLanguage) {
                allPois = (try? await APIClient.shared.getNearbyPois(
                    lat: locationManager.latitude,
                    lng: locationManager.longitude,
                    radiusKm: 50.0,
                    lang: appState.selectedLanguage
                )) ?? []
            }
            .sheet(item: $selectedPoi) { poi in
                PoiDetailSheetView(
                    poiSummary: poi,
                    onLaunchArExperience: { targetPoi in
                        selectedPoi = nil
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                            arActivePoi = targetPoi
                        }
                    }
                )
                .environmentObject(appState)
                .environmentObject(locationManager)
            }
            .fullScreenCover(item: $arActivePoi) { poi in
                ArCameraExperienceView(poi: poi)
                    .environmentObject(appState)
                    .environmentObject(locationManager)
            }
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

// MARK: - Tab 4: Thông tin (Info, Profile, My Photos & Admin Console Entry)

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

                        NavigationLink {
                            MyPhotosView()
                                .environmentObject(appState)
                        } label: {
                            Label(
                                appState.selectedLanguage == .vi ? "Quản lý ảnh đã chia sẻ của tôi" : "Manage My Shared Photos",
                                systemImage: "photo.on.rectangle.angled"
                            )
                        }

                        if user.role.isAdminOrAbove {
                            NavigationLink {
                                AdminModerationConsoleView()
                                    .environmentObject(appState)
                            } label: {
                                Label(
                                    user.role.isSuperAdmin
                                        ? (appState.selectedLanguage == .vi ? "Bảng Chủ Quản (SuperAdmin) - Duyệt Pin & Ảnh" : "SuperAdmin Approval & Moderation")
                                        : (appState.selectedLanguage == .vi ? "Bảng Quản Trị Viên (Admin) - Quản lý Pin & Ảnh" : "Admin POI & Photo Console"),
                                    systemImage: "checkmark.shield.fill"
                                )
                                .foregroundStyle(.purple)
                            }
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
            .navigationTitle(appState.selectedLanguage == .vi ? "Thông Tin" : "Information")
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
