import SwiftUI
import PhotosUI

public struct PoiDetailSheetView: View {
    @EnvironmentObject private var appState: AppState
    @EnvironmentObject private var locationManager: LocationManager
    @Environment(\.dismiss) private var dismiss

    public let poiSummary: NearbyPoiDto
    public let onLaunchArExperience: (NearbyPoiDto) -> Void

    @State private var detail: PoiDetailDto?
    @State private var communityPhotos: [CommunityPhotoDto] = []
    @State private var photoSortBy: String = "popular"
    @State private var isLoadingDetail: Bool = false
    @State private var isLoadingPhotos: Bool = false
    @State private var showUploadSheet: Bool = false
    @State private var reportingPhoto: CommunityPhotoDto?
    @State private var reportReason: String = "InappropriateContent"
    @State private var reportDetails: String = ""
    @State private var errorMessage: String?

    public init(
        poiSummary: NearbyPoiDto,
        onLaunchArExperience: @escaping (NearbyPoiDto) -> Void
    ) {
        self.poiSummary = poiSummary
        self.onLaunchArExperience = onLaunchArExperience
    }

    private var liveDistanceMeters: Double {
        locationManager.distanceInMeters(
            toLatitude: detail?.latitude ?? poiSummary.latitude,
            longitude: detail?.longitude ?? poiSummary.longitude
        )
    }

    private var isWithinGeofence: Bool {
        locationManager.hasFreshLocationFix &&
            liveDistanceMeters <= (poiSummary.activationRadiusMeters + 20.0)
    }

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    // Header Cover & Geofence Banner
                    headerSection

                    // Primary On-Site Actions (AR Camera & Capture Photo)
                    onSiteActionButtons

                    Divider()

                    // Cultural & Historical Story (Multilingual VI / EN Markdown)
                    historicalStorySection

                    // AR Info Cards Preview
                    if let cards = detail?.arInfoCards, !cards.isEmpty {
                        arInfoCardsPreviewSection(cards)
                    }

                    Divider()

                    // Community Photos Gallery (Like, Report, Delete Own)
                    communityGallerySection
                }
                .padding(18)
            }
            .navigationTitle(detail?.name ?? poiSummary.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        let nextLang: AppLanguage = appState.selectedLanguage == .vi ? .en : .vi
                        Task {
                            await appState.switchLanguage(nextLang)
                            await loadPoiDetailAndPhotos()
                        }
                    } label: {
                        Text(appState.selectedLanguage.rawValue.uppercased())
                            .font(.caption.weight(.bold))
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(Color.teal.opacity(0.15))
                            .clipShape(Capsule())
                    }
                }

                ToolbarItem(placement: .topBarTrailing) {
                    Button(appState.selectedLanguage == .vi ? "Đóng" : "Close") {
                        dismiss()
                    }
                }
            }
            .task {
                await loadPoiDetailAndPhotos()
            }
            .sheet(isPresented: $showUploadSheet) {
                OnSitePhotoUploadSheet(
                    poiId: poiSummary.id,
                    poiName: detail?.name ?? poiSummary.name,
                    onPhotoUploaded: { uploadedPhoto in
                        appState.recordUploadedPhoto(uploadedPhoto)
                        communityPhotos.insert(uploadedPhoto, at: 0)
                    }
                )
                .environmentObject(appState)
                .environmentObject(locationManager)
            }
            .sheet(item: $reportingPhoto) { targetPhoto in
                reportPhotoModal(for: targetPhoto)
            }
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

    // MARK: - Header Section

    private var headerSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let coverUrl = detail?.coverImageUrl ?? poiSummary.coverImageUrl,
               let url = URL(string: coverUrl) {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image):
                        image
                            .resizable()
                            .scaledToFill()
                            .frame(height: 200)
                            .clipped()
                            .clipShape(RoundedRectangle(cornerRadius: 16))
                    default:
                        placeholderBanner
                    }
                }
            } else {
                placeholderBanner
            }

            HStack(spacing: 10) {
                Text((detail?.category ?? poiSummary.category).uppercased())
                    .font(.caption.weight(.bold))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(Color.teal.opacity(0.15))
                    .foregroundStyle(.teal)
                    .clipShape(Capsule())

                Label(
                    liveDistanceMeters < 1000
                        ? "\(Int(liveDistanceMeters))m"
                        : String(format: "%.2f km", liveDistanceMeters / 1000.0),
                    systemImage: "location.fill"
                )
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)

                Spacer()

                // Geofence Activation Status Pill
                HStack(spacing: 4) {
                    Image(systemName: isWithinGeofence ? "checkmark.seal.fill" : "lock.fill")
                    Text(
                        isWithinGeofence
                            ? (appState.selectedLanguage == .vi ? "Trong vùng AR" : "In AR Zone")
                            : (appState.selectedLanguage == .vi ? "Cách điểm > \(Int(poiSummary.activationRadiusMeters))m" : "Outside \(Int(poiSummary.activationRadiusMeters))m")
                    )
                }
                .font(.caption.weight(.bold))
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(isWithinGeofence ? Color.green.opacity(0.16) : Color.orange.opacity(0.16))
                .foregroundStyle(isWithinGeofence ? .green : .orange)
                .clipShape(Capsule())
            }

            Text(detail?.shortSummary ?? poiSummary.shortSummary)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }

    private var placeholderBanner: some View {
        ZStack {
            LinearGradient(
                colors: [.teal.opacity(0.8), .blue.opacity(0.75)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            VStack(spacing: 8) {
                Image(systemName: "building.columns.fill")
                    .font(.system(size: 40))
                    .foregroundStyle(.white)
                Text(detail?.name ?? poiSummary.name)
                    .font(.headline)
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16)
            }
        }
        .frame(height: 165)
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    // MARK: - On-Site AR & Photo Capture Buttons

    private var onSiteActionButtons: some View {
        VStack(spacing: 10) {
            HStack(spacing: 12) {
                Button {
                    onLaunchArExperience(poiSummary)
                } label: {
                    HStack {
                        Image(systemName: "arkit")
                        Text(
                            appState.selectedLanguage == .vi
                                ? "Khám phá AR 3D"
                                : "Launch AR View"
                        )
                        .fontWeight(.bold)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(isWithinGeofence ? Color.green : Color.gray.opacity(0.4))
                    .foregroundStyle(.white)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                }
                .disabled(!isWithinGeofence)

                Button {
                    showUploadSheet = true
                } label: {
                    HStack {
                        Image(systemName: "camera.fill")
                        Text(
                            appState.selectedLanguage == .vi
                                ? "Chụp & Chia sẻ"
                                : "Capture & Share"
                        )
                        .fontWeight(.bold)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(isWithinGeofence ? Color.teal : Color.gray.opacity(0.4))
                    .foregroundStyle(.white)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                }
                .disabled(!isWithinGeofence)
            }

            if !isWithinGeofence {
                HStack(spacing: 6) {
                    Image(systemName: "info.circle.fill")
                        .foregroundStyle(.orange)
                    Text(
                        appState.selectedLanguage == .vi
                            ? "Hãy di chuyển lại gần điểm tham quan (trong phạm vi \(Int(poiSummary.activationRadiusMeters + 20))m) để mở khóa Camera AR và chụp ảnh lưu niệm tại chỗ."
                            : "Move within \(Int(poiSummary.activationRadiusMeters + 20))m of this POI to unlock the AR Camera and on-site photo sharing."
                    )
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                }
            }
        }
    }

    // MARK: - Cultural & Historical Story Section

    private var historicalStorySection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label(
                    appState.selectedLanguage == .vi
                        ? "Câu Chuyện Văn Hóa & Lịch Sử"
                        : "Cultural & Historical Story",
                    systemImage: "book.closed.fill"
                )
                .font(.headline)
                .foregroundStyle(.teal)

                Spacer()

                if isLoadingDetail {
                    ProgressView()
                }
            }

            if let markdownText = detail?.historicalStoryMarkdown, !markdownText.isEmpty {
                if let attributed = try? AttributedString(
                    markdown: markdownText,
                    options: AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
                ) {
                    Text(attributed)
                        .font(.body)
                        .lineSpacing(5)
                } else {
                    Text(markdownText)
                        .font(.body)
                        .lineSpacing(5)
                }
            } else {
                Text(detail?.shortSummary ?? poiSummary.shortSummary)
                    .font(.body)
            }
        }
        .padding(16)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }

    // MARK: - AR Info Cards Preview

    private func arInfoCardsPreviewSection(_ cards: [ArInfoCardDto]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(
                appState.selectedLanguage == .vi
                    ? "Các Thẻ Thông Tin AR Xung Quanh Điểm (\(cards.count))"
                    : "Spatial AR Info Cards Around POI (\(cards.count))",
                systemImage: "cube.transparent.fill"
            )
            .font(.headline)

            ForEach(cards) { card in
                HStack(alignment: .top, spacing: 12) {
                    VStack(spacing: 2) {
                        Image(systemName: "safari.fill")
                            .foregroundStyle(.teal)
                        Text("\(Int(card.azimuthDegrees))°")
                            .font(.caption2.weight(.bold))
                    }
                    .frame(width: 44, height: 44)
                    .background(Color.teal.opacity(0.12))
                    .clipShape(RoundedRectangle(cornerRadius: 10))

                    VStack(alignment: .leading, spacing: 4) {
                        Text(card.title)
                            .font(.subheadline.weight(.semibold))
                        if let summary = card.summary ?? card.subtitle {
                            Text(summary)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    Spacer()
                }
                .padding(10)
                .background(Color(.tertiarySystemBackground))
                .clipShape(RoundedRectangle(cornerRadius: 10))
            }
        }
    }

    // MARK: - Community Photo Gallery Section

    private var communityGallerySection: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Label(
                    appState.selectedLanguage == .vi
                        ? "Ảnh Cộng Đồng Chụp Tại Điểm"
                        : "Traveler Photos Captured Here",
                    systemImage: "photo.stack.fill"
                )
                .font(.headline)

                Spacer()

                Picker("Sort", selection: $photoSortBy) {
                    Text(appState.selectedLanguage == .vi ? "Nổi bật" : "Popular").tag("popular")
                    Text(appState.selectedLanguage == .vi ? "Mới nhất" : "Latest").tag("latest")
                }
                .pickerStyle(.segmented)
                .frame(width: 170)
                .onChange(of: photoSortBy) { _, _ in
                    Task { await loadPhotosOnly() }
                }
            }

            if isLoadingPhotos {
                HStack {
                    Spacer()
                    ProgressView()
                    Spacer()
                }
                .padding(.vertical, 20)
            } else if communityPhotos.isEmpty {
                Text(
                    appState.selectedLanguage == .vi
                        ? "Chưa có ảnh nào được chia sẻ tại địa điểm này. Hãy là du khách đầu tiên chụp ảnh!"
                        : "No traveler photos shared at this POI yet. Be the first to capture and share!"
                )
                .font(.footnote)
                .foregroundStyle(.secondary)
                .padding(.vertical, 12)
            } else {
                ForEach(communityPhotos) { photo in
                    CommunityPhotoCardView(
                        photo: photo,
                        currentUserId: appState.currentUser?.id,
                        language: appState.selectedLanguage,
                        onToggleLike: {
                            Task { await handleToggleLike(photo) }
                        },
                        onReport: {
                            reportingPhoto = photo
                        },
                        onDeleteOwn: {
                            Task { await handleDeletePhoto(photo) }
                        }
                    )
                }
            }
        }
    }

    // MARK: - Report Photo Sheet

    private func reportPhotoModal(for photo: CommunityPhotoDto) -> some View {
        NavigationStack {
            Form {
                Section(appState.selectedLanguage == .vi ? "Lý do báo cáo vi phạm" : "Reason for reporting") {
                    Picker(appState.selectedLanguage == .vi ? "Lý do" : "Reason", selection: $reportReason) {
                        Text(appState.selectedLanguage == .vi ? "Nội dung không phù hợp / Phản cảm" : "Inappropriate Content").tag("InappropriateContent")
                        Text(appState.selectedLanguage == .vi ? "Spam / Quảng cáo" : "Spam or Advertising").tag("Spam")
                        Text(appState.selectedLanguage == .vi ? "Không chụp tại địa điểm này" : "Off-Topic / Wrong Location").tag("WrongLocation")
                    }

                    TextField(
                        appState.selectedLanguage == .vi ? "Chi tiết bổ sung (tùy chọn)" : "Additional details (optional)",
                        text: $reportDetails,
                        axis: .vertical
                    )
                    .lineLimit(3...5)
                }

                Section {
                    Button(role: .destructive) {
                        Task {
                            do {
                                try await APIClient.shared.reportPhoto(
                                    photoId: photo.id,
                                    reason: reportReason,
                                    details: reportDetails
                                )
                                reportingPhoto = nil
                                reportDetails = ""
                            } catch {
                                errorMessage = error.localizedDescription
                            }
                        }
                    } label: {
                        HStack {
                            Spacer()
                            Text(appState.selectedLanguage == .vi ? "Gửi Báo Cáo Kiểm Duyệt" : "Submit Report")
                                .fontWeight(.bold)
                            Spacer()
                        }
                    }
                }
            }
            .navigationTitle(appState.selectedLanguage == .vi ? "Báo cáo ảnh vi phạm" : "Report Photo")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(appState.selectedLanguage == .vi ? "Hủy" : "Cancel") {
                        reportingPhoto = nil
                    }
                }
            }
        }
        .presentationDetents([.medium])
    }

    // MARK: - API Calls

    private func loadPoiDetailAndPhotos() async {
        isLoadingDetail = true
        defer { isLoadingDetail = false }

        do {
            let fetchedDetail = try await APIClient.shared.getPoiDetail(
                poiId: poiSummary.id,
                lat: locationManager.latitude,
                lng: locationManager.longitude,
                lang: appState.selectedLanguage
            )
            detail = fetchedDetail
            if let highlighted = fetchedDetail.highlightedCommunityPhotos, !highlighted.isEmpty {
                communityPhotos = highlighted
            }
        } catch {
            errorMessage = error.localizedDescription
        }

        await loadPhotosOnly()
    }

    private func loadPhotosOnly() async {
        isLoadingPhotos = true
        defer { isLoadingPhotos = false }
        if let fetchedPhotos = try? await APIClient.shared.getPoiPhotos(
            poiId: poiSummary.id,
            sortBy: photoSortBy,
            page: 1,
            pageSize: 20
        ) {
            communityPhotos = fetchedPhotos
        }
    }

    private func handleToggleLike(_ photo: CommunityPhotoDto) async {
        do {
            let result = try await APIClient.shared.toggleLikePhoto(photoId: photo.id)
            if let idx = communityPhotos.firstIndex(where: { $0.id == photo.id }) {
                let old = communityPhotos[idx]
                communityPhotos[idx] = CommunityPhotoDto(
                    id: old.id,
                    poiId: old.poiId,
                    userId: old.userId,
                    authorDisplayName: old.authorDisplayName,
                    authorAvatarUrl: old.authorAvatarUrl,
                    imageUrl: old.imageUrl,
                    thumbnailUrl: old.thumbnailUrl,
                    caption: old.caption,
                    capturedLatitude: old.capturedLatitude,
                    capturedLongitude: old.capturedLongitude,
                    azimuthDegrees: old.azimuthDegrees,
                    likesCount: result.likesCount,
                    isLikedByCurrentUser: result.liked,
                    moderationStatus: old.moderationStatus,
                    createdAt: old.createdAt
                )
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func handleDeletePhoto(_ photo: CommunityPhotoDto) async {
        do {
            try await appState.deleteOwnPhoto(photo)
            communityPhotos.removeAll { $0.id == photo.id }
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

// MARK: - Single Community Photo Card

private struct CommunityPhotoCardView: View {
    let photo: CommunityPhotoDto
    let currentUserId: String?
    let language: AppLanguage
    let onToggleLike: () -> Void
    let onReport: () -> Void
    let onDeleteOwn: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: "person.circle.fill")
                    .font(.title3)
                    .foregroundStyle(.teal)
                VStack(alignment: .leading, spacing: 2) {
                    Text(photo.authorDisplayName)
                        .font(.subheadline.weight(.semibold))
                    Text(photo.createdAt)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if let azimuth = photo.azimuthDegrees {
                    Label("\(Int(azimuth))°", systemImage: "safari")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }

            AsyncImage(url: URL(string: photo.imageUrl)) { phase in
                switch phase {
                case .success(let image):
                    image
                        .resizable()
                        .scaledToFill()
                        .frame(maxHeight: 240)
                        .clipped()
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                default:
                    ZStack {
                        RoundedRectangle(cornerRadius: 12)
                            .fill(Color.gray.opacity(0.15))
                            .frame(height: 180)
                        Image(systemName: "photo")
                            .font(.largeTitle)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            if let caption = photo.caption, !caption.isEmpty {
                Text(caption)
                    .font(.subheadline)
            }

            HStack(spacing: 16) {
                Button(action: onToggleLike) {
                    HStack(spacing: 5) {
                        Image(systemName: (photo.isLikedByCurrentUser ?? false) ? "heart.fill" : "heart")
                            .foregroundStyle(.pink)
                        Text("\(photo.likesCount)")
                            .font(.caption.weight(.bold))
                    }
                }
                .buttonStyle(.plain)

                Spacer()

                if let currentUserId = currentUserId, photo.userId == currentUserId {
                    Button(role: .destructive, action: onDeleteOwn) {
                        Label(
                            language == .vi ? "Xóa ảnh" : "Delete",
                            systemImage: "trash"
                        )
                        .font(.caption)
                    }
                    .buttonStyle(.plain)
                } else {
                    Button(action: onReport) {
                        Label(
                            language == .vi ? "Báo cáo" : "Report",
                            systemImage: "flag"
                        )
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(14)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }
}

// MARK: - On-Site Photo Capture & Upload Sheet

public struct OnSitePhotoUploadSheet: View {
    @EnvironmentObject private var appState: AppState
    @EnvironmentObject private var locationManager: LocationManager
    @Environment(\.dismiss) private var dismiss

    public let poiId: String
    public let poiName: String
    public let initialImage: UIImage?
    public let onPhotoUploaded: (CommunityPhotoDto) -> Void

    @State private var selectedItem: PhotosPickerItem?
    @State private var previewImage: UIImage?
    @State private var caption: String = ""
    @State private var isUploading: Bool = false
    @State private var uploadError: String?

    public init(
        poiId: String,
        poiName: String,
        initialImage: UIImage? = nil,
        onPhotoUploaded: @escaping (CommunityPhotoDto) -> Void
    ) {
        self.poiId = poiId
        self.poiName = poiName
        self.initialImage = initialImage
        self.onPhotoUploaded = onPhotoUploaded
        _previewImage = State(initialValue: initialImage)
    }

    public var body: some View {
        NavigationStack {
            Form {
                Section(appState.selectedLanguage == .vi ? "Điểm tham quan & Tọa độ GPS thực tế" : "POI & Live Spatial Telemetry") {
                    LabeledContent(appState.selectedLanguage == .vi ? "Địa điểm" : "POI", value: poiName)
                    LabeledContent(
                        "GPS",
                        value: String(format: "%.5f, %.5f", locationManager.latitude, locationManager.longitude)
                    )
                    LabeledContent(
                        appState.selectedLanguage == .vi ? "Hướng chụp (Azimuth)" : "Camera Heading",
                        value: String(format: "%.1f°", locationManager.azimuthDegrees)
                    )
                }

                Section(appState.selectedLanguage == .vi ? "Ảnh chụp tại điểm (<= 1920px, <= 5MB JPEG)" : "Captured Photo") {
                    if let image = previewImage {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                            .frame(maxHeight: 260)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                    }

                    PhotosPicker(
                        selection: $selectedItem,
                        matching: .images,
                        photoLibrary: .shared()
                    ) {
                        Label(
                            appState.selectedLanguage == .vi
                                ? "Chọn hoặc chụp ảnh mới"
                                : "Select or Capture Photo",
                            systemImage: "camera.on.rectangle.fill"
                        )
                    }
                    .onChange(of: selectedItem) { _, newItem in
                        guard let newItem = newItem else { return }
                        Task {
                            if let data = try? await newItem.loadTransferable(type: Data.self),
                               let uiImg = UIImage(data: data) {
                                previewImage = uiImg
                            }
                        }
                    }
                }

                Section(appState.selectedLanguage == .vi ? "Cảm nghĩ / Chú thích ảnh" : "Caption") {
                    TextField(
                        appState.selectedLanguage == .vi
                            ? "Chia sẻ khoảnh khắc của bạn tại điểm tham quan..."
                            : "Share your experience at this landmark...",
                        text: $caption,
                        axis: .vertical
                    )
                    .lineLimit(2...4)
                }

                if let uploadError = uploadError {
                    Section {
                        Text(uploadError)
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                }

                Section {
                    Button {
                        Task { await performUpload() }
                    } label: {
                        HStack {
                            Spacer()
                            if isUploading {
                                ProgressView()
                            } else {
                                Label(
                                    appState.selectedLanguage == .vi
                                        ? "Tải ảnh lên & Ghim vào không gian AR"
                                        : "Upload & Pin to AR Space",
                                    systemImage: "icloud.and.arrow.up.fill"
                                )
                                .fontWeight(.bold)
                            }
                            Spacer()
                        }
                    }
                    .disabled(previewImage == nil || isUploading)
                }
            }
            .navigationTitle(appState.selectedLanguage == .vi ? "Chia sẻ ảnh tại điểm" : "Share On-Site Photo")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(appState.selectedLanguage == .vi ? "Đóng" : "Cancel") {
                        dismiss()
                    }
                }
            }
        }
    }

    private func performUpload() async {
        guard let image = previewImage else { return }
        isUploading = true
        uploadError = nil
        defer { isUploading = false }

        do {
            let uploaded = try await APIClient.shared.uploadCommunityPhoto(
                poiId: poiId,
                image: image,
                capturedLatitude: locationManager.latitude,
                capturedLongitude: locationManager.longitude,
                azimuthDegrees: locationManager.azimuthDegrees,
                caption: caption
            )
            onPhotoUploaded(uploaded)
            dismiss()
        } catch {
            uploadError = error.localizedDescription
        }
    }
}
