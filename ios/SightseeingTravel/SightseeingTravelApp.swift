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
                    ProgressView("Đang khởi tạo Dấu Viễn Du...")
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
        .tint(BrandPalette.primary)
        .toolbarBackground(BrandPalette.paper, for: .tabBar)
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
                VStack(alignment: .leading, spacing: 22) {
                    HStack {
                        Image("LogoHorizontalDark")
                            .resizable()
                            .scaledToFit()
                            .frame(height: 34)

                        Spacer()

                        Button {
                            let nextLang: AppLanguage = appState.selectedLanguage == .vi ? .en : .vi
                            Task { await appState.switchLanguage(nextLang) }
                        } label: {
                            Text(appState.selectedLanguage.rawValue.uppercased())
                                .font(.caption.weight(.bold))
                                .padding(.horizontal, 11)
                                .padding(.vertical, 7)
                                .background(BrandPalette.cream)
                                .foregroundStyle(BrandPalette.primary)
                                .clipShape(Capsule())
                        }
                    }
                    .padding(.top, 6)

                    VStack(alignment: .leading, spacing: 6) {
                        Text(appState.selectedLanguage == .vi ? "Xin chào, \(appState.currentUser?.displayName ?? "Du khách")" : "Welcome, \(appState.currentUser?.displayName ?? "Traveler")")
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(BrandPalette.mutedText)
                        Text(appState.selectedLanguage == .vi ? "Chạm vào di sản" : "Meet the heritage")
                            .font(.system(size: 30, weight: .bold, design: .serif))
                            .foregroundStyle(BrandPalette.text)
                        Text(appState.selectedLanguage == .vi ? "Mỗi hành trình mở ra một câu chuyện Việt Nam." : "Every journey opens a story of Vietnam.")
                            .font(.subheadline)
                            .foregroundStyle(BrandPalette.mutedText)
                    }

                    homeFeaturedHero

                    HStack(spacing: 12) {
                        homeShortcut(
                            title: appState.selectedLanguage == .vi ? "Khám phá bản đồ" : "Explore map",
                            subtitle: appState.selectedLanguage == .vi ? "Tìm điểm đến quanh bạn" : "Find places nearby",
                            icon: "map.fill",
                            action: { onSwitchTab(2) }
                        )
                        homeShortcut(
                            title: appState.selectedLanguage == .vi ? "Địa điểm" : "Places",
                            subtitle: appState.selectedLanguage == .vi ? "Xem bộ sưu tập" : "Browse the collection",
                            icon: "building.columns.fill",
                            action: { onSwitchTab(1) }
                        )
                    }

                    HStack(alignment: .firstTextBaseline) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(appState.selectedLanguage == .vi ? "Gần bạn" : "Near you")
                                .font(.system(size: 24, weight: .bold, design: .serif))
                                .foregroundStyle(BrandPalette.text)
                            Text(appState.selectedLanguage == .vi ? "Những nơi đáng dừng chân hôm nay" : "Places worth a stop today")
                                .font(.caption)
                                .foregroundStyle(BrandPalette.mutedText)
                        }
                        Spacer()
                        Button(appState.selectedLanguage == .vi ? "Tất cả" : "All") { onSwitchTab(1) }
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(BrandPalette.primary)
                    }

                    ForEach(featuredPois) { poi in
                        homePoiRow(poi)
                    }
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 12)
            }
            .background(BrandPalette.paper.ignoresSafeArea())
            .toolbar(.hidden, for: .navigationBar)
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

    private var homeFeaturedHero: some View {
        let featuredPoi = featuredPois.first
        return ZStack(alignment: .bottomLeading) {
            Group {
                if let coverUrl = featuredPoi?.coverImageUrl, let url = URL(string: coverUrl) {
                    AsyncImage(url: url) { phase in
                        if let image = phase.image {
                            image.resizable().scaledToFill()
                        } else {
                            heroPlaceholder
                        }
                    }
                } else {
                    heroPlaceholder
                }
            }
            .frame(maxWidth: .infinity)
            .frame(height: 350)
            .clipped()

            LinearGradient(colors: [.clear, BrandPalette.primaryDeep.opacity(0.88)], startPoint: .center, endPoint: .bottom)

            VStack(alignment: .leading, spacing: 10) {
                Text(featuredPoi?.category.uppercased() ?? (appState.selectedLanguage == .vi ? "HÀNH TRÌNH DI SẢN" : "HERITAGE JOURNEY"))
                    .font(.caption2.weight(.bold))
                    .tracking(1.4)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(BrandPalette.cream.opacity(0.2))
                    .clipShape(Capsule())
                Text(featuredPoi?.name ?? (appState.selectedLanguage == .vi ? "Việt Nam qua từng dấu chân" : "Vietnam, one story at a time"))
                    .font(.system(size: 28, weight: .bold, design: .serif))
                    .lineLimit(2)
                Text(featuredPoi?.shortSummary ?? (appState.selectedLanguage == .vi ? "Khám phá di sản, văn hóa và những câu chuyện địa phương." : "Discover heritage, culture, and stories from local places."))
                    .font(.subheadline)
                    .lineLimit(2)
                    .foregroundStyle(.white.opacity(0.88))
                Button {
                    if let featuredPoi { selectedPoiForDetail = featuredPoi }
                    else { onSwitchTab(2) }
                } label: {
                    Label(appState.selectedLanguage == .vi ? "Khám phá câu chuyện" : "Explore the story", systemImage: "arrow.right")
                        .font(.subheadline.weight(.bold))
                        .padding(.horizontal, 16)
                        .padding(.vertical, 11)
                        .foregroundStyle(BrandPalette.primaryDeep)
                        .background(BrandPalette.cream)
                        .clipShape(Capsule())
                }
                .padding(.top, 2)
            }
            .foregroundStyle(.white)
            .padding(20)
        }
        .frame(height: 350)
        .clipShape(RoundedRectangle(cornerRadius: 24))
        .shadow(color: BrandPalette.primaryDeep.opacity(0.16), radius: 14, y: 8)
    }

    private var heroPlaceholder: some View {
        LinearGradient(colors: [BrandPalette.terracotta, BrandPalette.primaryDeep], startPoint: .topLeading, endPoint: .bottomTrailing)
            .overlay(alignment: .topTrailing) {
                Image(systemName: "building.columns.fill")
                    .font(.system(size: 110, weight: .light))
                    .foregroundStyle(BrandPalette.cream.opacity(0.2))
                    .padding(28)
            }
    }

    private func homeShortcut(title: String, subtitle: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 11) {
                Image(systemName: icon)
                    .font(.headline)
                    .foregroundStyle(BrandPalette.primary)
                    .frame(width: 38, height: 38)
                    .background(BrandPalette.cream.opacity(0.7))
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.subheadline.weight(.bold)).foregroundStyle(BrandPalette.text)
                    Text(subtitle).font(.caption).foregroundStyle(BrandPalette.mutedText).lineLimit(2)
                }
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(13)
            .background(BrandPalette.surface)
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(BrandPalette.border.opacity(0.75), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    private func homePoiRow(_ poi: NearbyPoiDto) -> some View {
        let distance = locationManager.distanceInMeters(toLatitude: poi.latitude, longitude: poi.longitude)
        return Button { selectedPoiForDetail = poi } label: {
            HStack(spacing: 13) {
                AsyncImage(url: URL(string: poi.coverImageUrl ?? "")) { phase in
                    if let image = phase.image { image.resizable().scaledToFill() }
                    else { BrandPalette.sand.overlay(Image(systemName: "building.columns.fill").foregroundStyle(BrandPalette.primary)) }
                }
                .frame(width: 88, height: 84)
                .clipShape(RoundedRectangle(cornerRadius: 14))
                VStack(alignment: .leading, spacing: 6) {
                    Text(poi.category.uppercased()).font(.caption2.weight(.bold)).tracking(0.8).foregroundStyle(BrandPalette.terracotta)
                    Text(poi.name).font(.headline).foregroundStyle(BrandPalette.text).lineLimit(2)
                    Label(distance < 1000 ? "\(Int(distance))m" : String(format: "%.1fkm", distance / 1000), systemImage: "location.fill")
                        .font(.caption).foregroundStyle(BrandPalette.mutedText)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.caption.weight(.bold)).foregroundStyle(BrandPalette.primary)
            }
            .padding(10)
            .background(BrandPalette.surface)
            .clipShape(RoundedRectangle(cornerRadius: 18))
            .overlay(RoundedRectangle(cornerRadius: 18).stroke(BrandPalette.border.opacity(0.75), lineWidth: 1))
        }
        .buttonStyle(.plain)
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
                            .foregroundStyle(BrandPalette.terracotta)
                        Spacer()
                        Text(
                            isUnlocked
                                ? (appState.selectedLanguage == .vi ? "Sẵn sàng mở AR (\(Int(liveDist))m)" : "AR Unlocked (\(Int(liveDist))m)")
                                : (appState.selectedLanguage == .vi ? "Cách \(Int(liveDist))m" : "\(Int(liveDist))m away")
                        )
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(isUnlocked ? BrandPalette.terracotta : BrandPalette.mutedText)
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
                        .tint(BrandPalette.primary)
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
                                        BrandPalette.sand
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
                                        .foregroundStyle(BrandPalette.terracotta)

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

    private var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
    }

    public init() {}

    public var body: some View {
        NavigationStack {
            Form {
                if let user = appState.currentUser {
                    Section(appState.selectedLanguage == .vi ? "Thông tin tài khoản" : "Account Information") {
                        HStack(spacing: 14) {
                            Image("StampBadge")
                                .resizable()
                                .scaledToFit()
                                .frame(width: 52, height: 52)
                                .clipShape(RoundedRectangle(cornerRadius: 10))

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
                            .foregroundStyle(BrandPalette.primary)
                    }
                }

                Section(appState.selectedLanguage == .vi ? "Về ứng dụng" : "About App") {
                    HStack(spacing: 14) {
                        Image("StampBadge")
                            .resizable()
                            .scaledToFit()
                            .frame(width: 44, height: 44)

                        VStack(alignment: .leading, spacing: 2) {
                            Text("Dấu Viễn Du")
                                .font(.subheadline.weight(.bold))
                                .foregroundStyle(BrandPalette.primary)
                            Text(appState.selectedLanguage == .vi
                                 ? "Dấu viễn du · Phiên bản v\(appVersion)"
                                 : "Vintage Heritage Stamp · v\(appVersion)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.vertical, 4)
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
        case .superAdmin: return BrandPalette.primaryDeep
        case .admin: return BrandPalette.terracotta
        case .tourist: return BrandPalette.primary
        }
    }
}
