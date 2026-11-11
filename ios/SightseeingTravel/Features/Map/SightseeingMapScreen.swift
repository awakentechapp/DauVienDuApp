import SwiftUI
import MapKit

public struct SightseeingMapScreen: View {
    @EnvironmentObject private var appState: AppState
    @EnvironmentObject private var locationManager: LocationManager

    @State private var cameraPosition: MapCameraPosition = .userLocation(
        fallback: .region(
            MKCoordinateRegion(
                center: CLLocationCoordinate2D(latitude: 10.7769, longitude: 106.7009),
                span: MKCoordinateSpan(latitudeDelta: 0.035, longitudeDelta: 0.035)
            )
        )
    )

    @State private var nearbyPois: [NearbyPoiDto] = []
    @State private var selectedPoi: NearbyPoiDto?
    @State private var arActivePoi: NearbyPoiDto?
    @State private var searchText: String = ""
    @State private var searchRadiusKm: Double = 50.0
    @State private var isLoading: Bool = false
    @State private var errorMessage: String?

    public init() {}

    private var visiblePois: [NearbyPoiDto] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return nearbyPois }
        return nearbyPois.filter {
            $0.name.localizedCaseInsensitiveContains(query) ||
                $0.category.localizedCaseInsensitiveContains(query)
        }
    }

    public var body: some View {
        NavigationStack {
            ZStack(alignment: .bottom) {
                // Apple MapKit Map with UserAnnotation & Custom POI Annotations
                Map(position: $cameraPosition) {
                    UserAnnotation()

                    ForEach(visiblePois) { poi in
                        let liveDist = locationManager.distanceInMeters(
                            toLatitude: poi.latitude,
                            longitude: poi.longitude
                        )
                        let isUnlocked = locationManager.hasFreshLocationFix &&
                            liveDist <= (poi.activationRadiusMeters + 20.0)

                        Annotation(
                            poi.name,
                            coordinate: CLLocationCoordinate2D(latitude: poi.latitude, longitude: poi.longitude),
                            anchor: .bottom
                        ) {
                            Button {
                                selectedPoi = poi
                            } label: {
                                PoiMapPinView(
                                    poi: poi,
                                    liveDistanceMeters: liveDist,
                                    isArUnlocked: isUnlocked
                                )
                            }
                            .buttonStyle(.plain)
                        }

                        MapCircle(
                            center: CLLocationCoordinate2D(latitude: poi.latitude, longitude: poi.longitude),
                            radius: poi.activationRadiusMeters
                        )
                        .foregroundStyle(isUnlocked ? BrandPalette.terracotta.opacity(0.16) : BrandPalette.primary.opacity(0.10))
                        .stroke(isUnlocked ? BrandPalette.terracotta : BrandPalette.primary.opacity(0.5), lineWidth: 1.5)
                    }
                }
                .mapControls {
                    MapUserLocationButton()
                    MapCompass()
                    MapScaleView()
                }
                .ignoresSafeArea(edges: .top)

                // Top Floating Controls Overlay + Bottom Nearby POI Carousel
                VStack {
                    topFloatingHeader
                        .padding(.horizontal, 14)
                        .padding(.top, 8)

                    if let locError = locationManager.locationError {
                        HStack(spacing: 8) {
                            Image(systemName: "location.slash.fill")
                                .foregroundStyle(BrandPalette.terracotta)
                            Text(locError)
                                .font(.caption)
                                .foregroundStyle(BrandPalette.text)
                            Spacer()
                        }
                        .padding(10)
                        .background(BrandPalette.paper.opacity(0.95))
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                        .padding(.horizontal, 14)
                    }

                    Spacer()

                    bottomPoiCarousel
                        .padding(.bottom, 12)
                }
            }
            .navigationBarHidden(true)
            .task(id: appState.selectedLanguage) {
                await fetchNearbyPois()
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

    // MARK: - Top Floating Header

    private var topFloatingHeader: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                Image(systemName: "map.fill")
                    .font(.headline)
                    .foregroundStyle(BrandPalette.primary)
                    .frame(width: 38, height: 38)
                    .background(BrandPalette.cream)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                VStack(alignment: .leading, spacing: 2) {
                    Text(appState.selectedLanguage == .vi ? "Khám phá" : "Explore")
                        .font(.system(size: 22, weight: .bold, design: .serif))
                        .foregroundStyle(BrandPalette.text)
                    Text("\(visiblePois.count) \(appState.selectedLanguage == .vi ? "địa điểm" : "places") • \(Int(searchRadiusKm))km")
                        .font(.caption2)
                        .foregroundStyle(BrandPalette.mutedText)
                }

                Spacer()

                Button {
                    let nextLang: AppLanguage = appState.selectedLanguage == .vi ? .en : .vi
                    Task { await appState.switchLanguage(nextLang) }
                } label: {
                    Text(appState.selectedLanguage.rawValue.uppercased())
                        .font(.caption.weight(.bold))
                        .padding(.horizontal, 9)
                        .padding(.vertical, 6)
                        .background(BrandPalette.cream)
                        .foregroundStyle(BrandPalette.primary)
                        .clipShape(Capsule())
                }

                Button { Task { await fetchNearbyPois() } } label: {
                    if isLoading { ProgressView().tint(BrandPalette.primary).frame(width: 34, height: 34) }
                    else { Image(systemName: "arrow.clockwise").font(.subheadline.weight(.semibold)).frame(width: 34, height: 34) }
                }
                .foregroundStyle(BrandPalette.primary)
                .background(BrandPalette.surface)
                .clipShape(Circle())
            }

            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(BrandPalette.primary)
                TextField(appState.selectedLanguage == .vi ? "Tìm địa điểm, di tích…" : "Search landmarks and places…", text: $searchText)
                    .font(.subheadline)
                    .tint(BrandPalette.primary)
                    .submitLabel(.search)
                if !searchText.isEmpty {
                    Button { searchText = "" } label: {
                        Image(systemName: "xmark.circle.fill").foregroundStyle(BrandPalette.mutedText)
                    }
                }
            }
            .padding(.horizontal, 12)
            .frame(height: 42)
            .background(BrandPalette.surface)
            .clipShape(RoundedRectangle(cornerRadius: 13))
            .overlay(RoundedRectangle(cornerRadius: 13).stroke(BrandPalette.border, lineWidth: 1))
        }
        .padding(13)
        .background(BrandPalette.paper.opacity(0.97))
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .shadow(color: BrandPalette.primaryDeep.opacity(0.16), radius: 10, y: 4)
    }

    // MARK: - Bottom Carousel of Nearby POIs

    private var bottomPoiCarousel: some View {
        Group {
            if visiblePois.isEmpty && !isLoading {
                HStack(spacing: 12) {
                    Image(systemName: "mappin.and.ellipse")
                        .font(.title2)
                        .foregroundStyle(BrandPalette.primary)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(
                            appState.selectedLanguage == .vi
                                ? "Chưa tìm thấy điểm tham quan nào trong bán kính \(Int(searchRadiusKm))km"
                                : "No published POIs found within \(Int(searchRadiusKm))km"
                        )
                        .font(.footnote.weight(.semibold))
                        if let errorMessage = errorMessage {
                            Text(errorMessage)
                                .font(.caption2)
                                .foregroundStyle(BrandPalette.error)
                                .lineLimit(2)
                        }
                    }
                    Spacer()
                    Button(appState.selectedLanguage == .vi ? (nearbyPois.isEmpty ? "Thử lại" : "Xóa") : (nearbyPois.isEmpty ? "Retry" : "Clear")) {
                        if nearbyPois.isEmpty { Task { await fetchNearbyPois() } }
                        else { searchText = "" }
                    }
                    .font(.caption.weight(.bold))
                    .buttonStyle(.borderedProminent)
                    .tint(BrandPalette.primary)
                }
                .padding(14)
                .background(BrandPalette.paper.opacity(0.96))
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .padding(.horizontal, 14)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 14) {
                        ForEach(visiblePois) { poi in
                            let liveDist = locationManager.distanceInMeters(
                                toLatitude: poi.latitude,
                                longitude: poi.longitude
                            )
                            let isUnlocked = locationManager.hasFreshLocationFix &&
                                liveDist <= (poi.activationRadiusMeters + 20.0)

                            NearbyPoiCarouselCard(
                                poi: poi,
                                liveDistanceMeters: liveDist,
                                isArUnlocked: isUnlocked,
                                language: appState.selectedLanguage,
                                onSelectDetail: {
                                    selectedPoi = poi
                                    withAnimation {
                                        cameraPosition = .region(
                                            MKCoordinateRegion(
                                                center: CLLocationCoordinate2D(latitude: poi.latitude, longitude: poi.longitude),
                                                span: MKCoordinateSpan(latitudeDelta: 0.01, longitudeDelta: 0.01)
                                            )
                                        )
                                    }
                                },
                                onOpenArDirectly: {
                                    arActivePoi = poi
                                }
                            )
                        }
                    }
                    .padding(.horizontal, 14)
                }
            }
        }
    }

    // MARK: - Data Loading

    private func fetchNearbyPois() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        do {
            let results = try await APIClient.shared.getNearbyPois(
                lat: locationManager.latitude,
                lng: locationManager.longitude,
                radiusKm: searchRadiusKm,
                lang: appState.selectedLanguage
            )
            nearbyPois = results
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

// MARK: - Map Pin Annotation View

private struct PoiMapPinView: View {
    let poi: NearbyPoiDto
    let liveDistanceMeters: Double
    let isArUnlocked: Bool

    var body: some View {
        VStack(spacing: 4) {
            HStack(spacing: 4) {
                Image(systemName: isArUnlocked ? "arkit" : categoryIconName(poi.category))
                    .font(.caption2.weight(.bold))
                Text(poi.name)
                    .font(.caption2.weight(.bold))
                    .lineLimit(1)
                    .frame(maxWidth: 145)
                Text("•")
                    .font(.caption2.weight(.bold))
                Text(formatDistance(liveDistanceMeters))
                    .font(.caption2.weight(.bold))
            }
            .padding(.horizontal, 9)
            .padding(.vertical, 6)
            .background(isArUnlocked ? BrandPalette.terracotta : BrandPalette.primary)
            .foregroundStyle(BrandPalette.cream)
            .clipShape(Capsule())
            .shadow(color: BrandPalette.primaryDeep.opacity(0.3), radius: 5, y: 2)

            Image(systemName: "arrowtriangle.down.fill")
                .font(.caption2)
                .foregroundStyle(isArUnlocked ? BrandPalette.terracotta : BrandPalette.primary)
                .offset(y: -3)
        }
    }

    private func categoryIconName(_ category: String) -> String {
        switch category.lowercased() {
        case "historical", "history", "heritage": return "building.columns.fill"
        case "museum": return "building.columns.fill"
        case "religious", "temple", "pagoda": return "sparkles"
        case "nature", "park": return "leaf.fill"
        default: return "mappin.circle.fill"
        }
    }

    private func formatDistance(_ meters: Double) -> String {
        if meters < 1000 {
            return "\(Int(meters))m"
        }
        return String(format: "%.1fkm", meters / 1000.0)
    }
}

// MARK: - Bottom Carousel Card

private struct NearbyPoiCarouselCard: View {
    let poi: NearbyPoiDto
    let liveDistanceMeters: Double
    let isArUnlocked: Bool
    let language: AppLanguage
    let onSelectDetail: () -> Void
    let onOpenArDirectly: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        Text(poi.category.uppercased())
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(BrandPalette.terracotta)

                        Text("•")
                            .foregroundStyle(BrandPalette.mutedText)

                        Text(liveDistanceMeters < 1000 ? "\(Int(liveDistanceMeters))m" : String(format: "%.1fkm", liveDistanceMeters / 1000.0))
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(isArUnlocked ? BrandPalette.terracotta : BrandPalette.mutedText)
                    }

                    Text(poi.name)
                        .font(.headline)
                        .foregroundStyle(BrandPalette.text)
                        .lineLimit(1)

                    Text(poi.shortSummary)
                        .font(.caption)
                        .foregroundStyle(BrandPalette.mutedText)
                        .lineLimit(2)
                }

                Spacer(minLength: 4)

                // Geofence AR Badge
                VStack(spacing: 2) {
                    Image(systemName: isArUnlocked ? "lock.open.fill" : "lock.fill")
                        .font(.caption)
                    Text(
                        isArUnlocked
                            ? (language == .vi ? "Đã mở AR" : "AR Ready")
                            : (language == .vi ? "Chưa mở AR" : "AR Locked")
                    )
                        .font(.system(size: 9, weight: .bold))
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 6)
                .background(isArUnlocked ? BrandPalette.cream : BrandPalette.sand)
                .foregroundStyle(BrandPalette.primary)
                .clipShape(RoundedRectangle(cornerRadius: 10))
            }

            HStack(spacing: 8) {
                Button(action: onSelectDetail) {
                    Label(
                        language == .vi ? "Chi tiết & Ảnh" : "Story & Photos",
                        systemImage: "book.pages.fill"
                    )
                    .font(.caption.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    .background(BrandPalette.cream)
                    .foregroundStyle(BrandPalette.primary)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                }

                Button(action: onOpenArDirectly) {
                    Label(
                        language == .vi ? "Mở AR Camera" : "Open AR",
                        systemImage: "arkit"
                    )
                    .font(.caption.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    .background(isArUnlocked ? BrandPalette.primary : BrandPalette.mutedText.opacity(0.55))
                    .foregroundStyle(BrandPalette.cream)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                }
                .disabled(!isArUnlocked)
            }
        }
        .padding(14)
        .frame(width: 300)
        .background(BrandPalette.paper.opacity(0.97))
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .shadow(color: BrandPalette.primaryDeep.opacity(0.16), radius: 8, y: 4)
    }
}
