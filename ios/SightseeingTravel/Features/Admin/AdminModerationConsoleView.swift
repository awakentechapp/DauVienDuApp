import SwiftUI

public struct AdminModerationConsoleView: View {
    @EnvironmentObject private var appState: AppState
    @EnvironmentObject private var locationManager: LocationManager

    @State private var activeSection: Int = 0 // 0 = POI Queue, 1 = UGC Photo Moderation
    @State private var statusFilter: PoiStatus? = .pendingApproval
    @State private var adminPois: [AdminPoiDto] = []
    @State private var reportedPhotos: [ReportedPhotoQueueItemDto] = []
    @State private var isLoading: Bool = false
    @State private var showCreatePoiSheet: Bool = false
    @State private var editingPoi: AdminPoiDto?
    @State private var rejectingPoi: AdminPoiDto?
    @State private var rejectionReasonInput: String = ""
    @State private var alertMessage: String?

    public init() {}

    private var isSuperAdmin: Bool {
        appState.currentUser?.role == .superAdmin
    }

    public var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Top Role & Section Switcher
                VStack(spacing: 10) {
                    HStack {
                        if let role = appState.currentUser?.role {
                            RoleBadgeView(role: role)
                        }
                        Text(
                            isSuperAdmin
                                ? (appState.selectedLanguage == .vi
                                    ? "SuperAdmin: Toàn quyền Phê duyệt / Từ chối POI & Kiểm duyệt ảnh"
                                    : "SuperAdmin: Full POI Approval/Rejection & Photo Moderation")
                                : (appState.selectedLanguage == .vi
                                    ? "Admin: Tạo/Cập nhật POI (Chờ SuperAdmin duyệt) & Kiểm duyệt ảnh"
                                    : "Admin: Create/Edit POIs (Pending SuperAdmin Approval) & Moderate Photos")
                        )
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        Spacer()
                    }

                    Picker("Console Section", selection: $activeSection) {
                        Text(
                            appState.selectedLanguage == .vi
                                ? "Quản lý & Duyệt POI (\(adminPois.count))"
                                : "POIs & Approval (\(adminPois.count))"
                        )
                        .tag(0)

                        Text(
                            appState.selectedLanguage == .vi
                                ? "Kiểm duyệt Ảnh (\(reportedPhotos.count))"
                                : "Photo Moderation (\(reportedPhotos.count))"
                        )
                        .tag(1)
                    }
                    .pickerStyle(.segmented)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(Color(.secondarySystemBackground))

                if activeSection == 0 {
                    poiApprovalAndManagementTab
                } else {
                    photoModerationQueueTab
                }
            }
            .navigationTitle(
                isSuperAdmin
                    ? (appState.selectedLanguage == .vi ? "Trung Tâm SuperAdmin" : "SuperAdmin Console")
                    : (appState.selectedLanguage == .vi ? "Trung Tâm Quản Trị" : "Admin Console")
            )
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        Task { await reloadActiveTab() }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                }

                if activeSection == 0 {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            showCreatePoiSheet = true
                        } label: {
                            Label(
                                appState.selectedLanguage == .vi ? "Tạo POI" : "New POI",
                                systemImage: "plus.circle.fill"
                            )
                            .fontWeight(.semibold)
                        }
                    }
                }
            }
            .task(id: activeSection) {
                await reloadActiveTab()
            }
            .sheet(isPresented: $showCreatePoiSheet) {
                AdminPoiFormSheet(
                    existingPoi: nil,
                    defaultLatitude: locationManager.latitude,
                    defaultLongitude: locationManager.longitude,
                    language: appState.selectedLanguage,
                    onSaved: { _ in
                        Task { await loadAdminPois() }
                    }
                )
            }
            .sheet(item: $editingPoi) { poiToEdit in
                AdminPoiFormSheet(
                    existingPoi: poiToEdit,
                    defaultLatitude: poiToEdit.latitude,
                    defaultLongitude: poiToEdit.longitude,
                    language: appState.selectedLanguage,
                    onSaved: { _ in
                        Task { await loadAdminPois() }
                    }
                )
            }
            .sheet(item: $rejectingPoi) { targetPoi in
                rejectPoiModal(for: targetPoi)
            }
            .alert(
                appState.selectedLanguage == .vi ? "Thông báo" : "Notice",
                isPresented: Binding(
                    get: { alertMessage != nil },
                    set: { if !$0 { alertMessage = nil } }
                )
            ) {
                Button("OK", role: .cancel) { alertMessage = nil }
            } message: {
                Text(alertMessage ?? "")
            }
        }
    }

    // MARK: - Tab 1: POI Management & SuperAdmin Approval Queue

    private var poiApprovalAndManagementTab: some View {
        VStack(spacing: 0) {
            // Status Filter Bar
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    statusFilterChip(
                        title: appState.selectedLanguage == .vi ? "Chờ phê duyệt" : "Pending Approval",
                        status: .pendingApproval
                    )
                    statusFilterChip(
                        title: appState.selectedLanguage == .vi ? "Đã xuất bản" : "Published",
                        status: .published
                    )
                    statusFilterChip(
                        title: appState.selectedLanguage == .vi ? "Bị từ chối" : "Rejected",
                        status: .rejected
                    )
                    statusFilterChip(
                        title: appState.selectedLanguage == .vi ? "Tất cả" : "All POIs",
                        status: nil
                    )
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
            }

            if isLoading {
                Spacer()
                ProgressView()
                Spacer()
            } else if adminPois.isEmpty {
                ContentUnavailableView(
                    appState.selectedLanguage == .vi
                        ? "Không có điểm tham quan nào"
                        : "No POIs Matching Filter",
                    systemImage: "mappin.slash",
                    description: Text(
                        appState.selectedLanguage == .vi
                            ? "Nhấn nút '+ Tạo POI' ở góc trên bên phải để thêm điểm tham quan mới."
                            : "Tap '+ New POI' in the top-right corner to submit a new sightseeing location."
                    )
                )
            } else {
                List {
                    ForEach(adminPois) { poi in
                        AdminPoiRowCard(
                            poi: poi,
                            isSuperAdmin: isSuperAdmin,
                            language: appState.selectedLanguage,
                            onEdit: {
                                editingPoi = poi
                            },
                            onApprove: {
                                Task { await handleApprovePoi(poi) }
                            },
                            onReject: {
                                rejectionReasonInput = ""
                                rejectingPoi = poi
                            }
                        )
                    }
                }
                .listStyle(.plain)
            }
        }
    }

    private func statusFilterChip(title: String, status: PoiStatus?) -> some View {
        let isSelected = statusFilter == status
        return Button {
            statusFilter = status
            Task { await loadAdminPois() }
        } label: {
            Text(title)
                .font(.caption.weight(.bold))
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(isSelected ? Color.teal : Color.secondary.opacity(0.15))
                .foregroundStyle(isSelected ? .white : .primary)
                .clipShape(Capsule())
        }
    }

    // MARK: - Tab 2: UGC Photo Moderation Queue

    private var photoModerationQueueTab: some View {
        Group {
            if isLoading {
                Spacer()
                ProgressView()
                Spacer()
            } else if reportedPhotos.isEmpty {
                ContentUnavailableView(
                    appState.selectedLanguage == .vi
                        ? "Hàng đợi kiểm duyệt ảnh trống"
                        : "Moderation Queue is Clear",
                    systemImage: "checkmark.seal.fill",
                    description: Text(
                        appState.selectedLanguage == .vi
                            ? "Không có ảnh cộng đồng nào đang bị báo cáo vi phạm."
                            : "There are no reported community photos awaiting moderation."
                    )
                )
            } else {
                List {
                    ForEach(reportedPhotos) { item in
                        VStack(alignment: .leading, spacing: 10) {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(item.poiName ?? "POI ID: \(item.poiId)")
                                        .font(.subheadline.weight(.bold))
                                    Text("Author: \(item.authorDisplayName) • \(item.createdAt)")
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                Label("\(item.reportsCount) Reports", systemImage: "flag.fill")
                                    .font(.caption.weight(.bold))
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 4)
                                    .background(Color.red.opacity(0.15))
                                    .foregroundStyle(.red)
                                    .clipShape(Capsule())
                            }

                            AsyncImage(url: URL(string: item.thumbnailUrl ?? item.imageUrl)) { phase in
                                switch phase {
                                case .success(let image):
                                    image
                                        .resizable()
                                        .scaledToFill()
                                        .frame(height: 180)
                                        .clipped()
                                        .clipShape(RoundedRectangle(cornerRadius: 12))
                                default:
                                    RoundedRectangle(cornerRadius: 12)
                                        .fill(Color.gray.opacity(0.2))
                                        .frame(height: 140)
                                        .overlay(Image(systemName: "photo"))
                                }
                            }

                            if let reason = item.latestReportReason, !reason.isEmpty {
                                Text("Lý do báo cáo: \(reason)")
                                    .font(.caption)
                                    .foregroundStyle(.orange)
                            }

                            if let caption = item.caption, !caption.isEmpty {
                                Text("Chú thích: \"\(caption)\"")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }

                            HStack(spacing: 12) {
                                Button {
                                    Task {
                                        await handlePhotoDecision(photoId: item.id, decision: "Approve")
                                    }
                                } label: {
                                    Label(
                                        appState.selectedLanguage == .vi ? "Giữ lại (Hợp lệ)" : "Approve (Keep)",
                                        systemImage: "checkmark.circle.fill"
                                    )
                                    .font(.caption.weight(.bold))
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 8)
                                    .background(Color.green.opacity(0.18))
                                    .foregroundStyle(.green)
                                    .clipShape(RoundedRectangle(cornerRadius: 10))
                                }
                                .buttonStyle(.plain)

                                Button(role: .destructive) {
                                    Task {
                                        await handlePhotoDecision(photoId: item.id, decision: "Remove")
                                    }
                                } label: {
                                    Label(
                                        appState.selectedLanguage == .vi ? "Gỡ ảnh vi phạm" : "Remove Photo",
                                        systemImage: "trash.fill"
                                    )
                                    .font(.caption.weight(.bold))
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 8)
                                    .background(Color.red.opacity(0.18))
                                    .foregroundStyle(.red)
                                    .clipShape(RoundedRectangle(cornerRadius: 10))
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(.vertical, 6)
                    }
                }
                .listStyle(.plain)
            }
        }
    }

    // MARK: - SuperAdmin Mandatory Rejection Reason Modal

    private func rejectPoiModal(for poi: AdminPoiDto) -> some View {
        NavigationStack {
            Form {
                Section(appState.selectedLanguage == .vi ? "Điểm tham quan bị từ chối" : "POI to Reject") {
                    LabeledContent("Tên POI", value: poi.name)
                    LabeledContent("Slug", value: poi.slug)
                }

                Section(appState.selectedLanguage == .vi ? "Lý do từ chối (Bắt buộc)" : "Rejection Reason (Mandatory)") {
                    TextField(
                        appState.selectedLanguage == .vi
                            ? "Nhập lý do cụ thể để Admin chỉnh sửa lại..."
                            : "Enter mandatory reason for Admin to revise...",
                        text: $rejectionReasonInput,
                        axis: .vertical
                    )
                    .lineLimit(3...6)
                }

                Section {
                    Button(role: .destructive) {
                        Task {
                            await handleRejectPoi(poi, reason: rejectionReasonInput)
                        }
                    } label: {
                        HStack {
                            Spacer()
                            Text(appState.selectedLanguage == .vi ? "Xác Nhận Từ Chối POI" : "Confirm Rejection")
                                .fontWeight(.bold)
                            Spacer()
                        }
                    }
                    .disabled(rejectionReasonInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .navigationTitle(appState.selectedLanguage == .vi ? "Từ chối phê duyệt POI" : "Reject POI")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(appState.selectedLanguage == .vi ? "Hủy" : "Cancel") {
                        rejectingPoi = nil
                    }
                }
            }
        }
        .presentationDetents([.medium])
    }

    // MARK: - Data Actions

    private func reloadActiveTab() async {
        if activeSection == 0 {
            await loadAdminPois()
        } else {
            await loadReportedPhotos()
        }
    }

    private func loadAdminPois() async {
        isLoading = true
        defer { isLoading = false }
        do {
            adminPois = try await APIClient.shared.getAdminPois(
                status: statusFilter,
                lang: appState.selectedLanguage
            )
        } catch {
            alertMessage = error.localizedDescription
        }
    }

    private func loadReportedPhotos() async {
        isLoading = true
        defer { isLoading = false }
        do {
            reportedPhotos = try await APIClient.shared.getModerationPhotoQueue()
        } catch {
            alertMessage = error.localizedDescription
        }
    }

    private func handleApprovePoi(_ poi: AdminPoiDto) async {
        guard isSuperAdmin else { return }
        do {
            _ = try await APIClient.shared.approvePoi(poiId: poi.id)
            await loadAdminPois()
        } catch {
            alertMessage = error.localizedDescription
        }
    }

    private func handleRejectPoi(_ poi: AdminPoiDto, reason: String) async {
        guard isSuperAdmin else { return }
        let trimmed = reason.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        do {
            _ = try await APIClient.shared.rejectPoi(poiId: poi.id, reason: trimmed)
            rejectingPoi = nil
            await loadAdminPois()
        } catch {
            alertMessage = error.localizedDescription
        }
    }

    private func handlePhotoDecision(photoId: String, decision: String) async {
        do {
            try await APIClient.shared.submitPhotoModerationDecision(
                photoId: photoId,
                decision: decision,
                note: "Moderated via iOS Console"
            )
            reportedPhotos.removeAll { $0.id == photoId }
        } catch {
            alertMessage = error.localizedDescription
        }
    }
}

// MARK: - Admin POI Row Card

private struct AdminPoiRowCard: View {
    let poi: AdminPoiDto
    let isSuperAdmin: Bool
    let language: AppLanguage
    let onEdit: () -> Void
    let onApprove: () -> Void
    let onReject: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(poi.category.uppercased())
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(.teal)

                Spacer()

                statusBadge(poi.status)
            }

            Text(poi.name)
                .font(.headline)

            Text(poi.shortSummary)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(2)

            HStack(spacing: 12) {
                Label(
                    String(format: "%.4f, %.4f", poi.latitude, poi.longitude),
                    systemImage: "mappin.and.ellipse"
                )
                Label("\(Int(poi.activationRadiusMeters))m", systemImage: "wave.3.right.circle")
            }
            .font(.caption2)
            .foregroundStyle(.secondary)

            if let reason = poi.rejectionReason, !reason.isEmpty, poi.status == .rejected {
                HStack(spacing: 6) {
                    Image(systemName: "exclamationmark.bubble.fill")
                        .foregroundStyle(.red)
                    Text("Lý do từ chối: \(reason)")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.red)
                }
                .padding(8)
                .background(Color.red.opacity(0.1))
                .clipShape(RoundedRectangle(cornerRadius: 8))
            }

            HStack(spacing: 10) {
                Button(action: onEdit) {
                    Label(
                        language == .vi ? "Chỉnh sửa" : "Edit",
                        systemImage: "pencil"
                    )
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 7)
                    .background(Color.secondary.opacity(0.14))
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                }
                .buttonStyle(.plain)

                Spacer()

                if isSuperAdmin && poi.status != .published {
                    Button(role: .destructive, action: onReject) {
                        Label(
                            language == .vi ? "Từ chối" : "Reject",
                            systemImage: "xmark.circle.fill"
                        )
                        .font(.caption.weight(.bold))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 7)
                        .background(Color.red.opacity(0.15))
                        .foregroundStyle(.red)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                    }
                    .buttonStyle(.plain)

                    Button(action: onApprove) {
                        Label(
                            language == .vi ? "Phê duyệt (Publish)" : "Approve",
                            systemImage: "checkmark.seal.fill"
                        )
                        .font(.caption.weight(.bold))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 7)
                        .background(Color.green)
                        .foregroundStyle(.white)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                    }
                    .buttonStyle(.plain)
                } else if !isSuperAdmin && poi.status == .pendingApproval {
                    Text(language == .vi ? "Chờ SuperAdmin phê duyệt" : "Awaiting SuperAdmin")
                        .font(.caption2.italic())
                        .foregroundStyle(.orange)
                }
            }
        }
        .padding(.vertical, 6)
    }

    private func statusBadge(_ status: PoiStatus) -> some View {
        let (label, color): (String, Color) = {
            switch status {
            case .published: return ("PUBLISHED", .green)
            case .pendingApproval: return ("PENDING APPROVAL", .orange)
            case .rejected: return ("REJECTED", .red)
            case .draft: return ("DRAFT", .gray)
            case .archived: return ("ARCHIVED", .secondary)
            }
        }()

        return Text(label)
            .font(.system(size: 10, weight: .bold))
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(color.opacity(0.16))
            .foregroundStyle(color)
            .clipShape(Capsule())
    }
}

// MARK: - Create / Edit POI Form Sheet (Admin & SuperAdmin)

private struct AdminPoiFormSheet: View {
    @Environment(\.dismiss) private var dismiss

    let existingPoi: AdminPoiDto?
    let defaultLatitude: Double
    let defaultLongitude: Double
    let language: AppLanguage
    let onSaved: (AdminPoiDto) -> Void

    @State private var slug: String = ""
    @State private var category: String = "Historical"
    @State private var latitudeString: String = ""
    @State private var longitudeString: String = ""
    @State private var activationRadius: Double = 80.0
    @State private var coverImageUrl: String = ""

    // Vietnamese Content
    @State private var nameVi: String = ""
    @State private var summaryVi: String = ""
    @State private var storyMarkdownVi: String = ""

    // English Content
    @State private var nameEn: String = ""
    @State private var summaryEn: String = ""
    @State private var storyMarkdownEn: String = ""

    // Spatial AR Info Cards
    @State private var arCards: [ArInfoCardInput] = [
        ArInfoCardInput(
            azimuthDegrees: 45,
            elevationMeters: 1.5,
            distanceMeters: 4.0,
            cardType: "HistoryFact",
            titleVi: "Kiến trúc đặc trưng",
            summaryVi: "Điểm nhấn lịch sử và nghệ thuật kiến trúc độc đáo tại di tích.",
            titleEn: "Signature Architecture",
            summaryEn: "Historical highlight and unique architectural heritage on site."
        )
    ]

    @State private var isSaving: Bool = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section(language == .vi ? "Định danh & Tọa độ GPS" : "Identity & GPS Coordinates") {
                    TextField("Slug (vd: nha-tho-duc-ba)", text: $slug)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()

                    Picker(language == .vi ? "Danh mục" : "Category", selection: $category) {
                        Text("Historical").tag("Historical")
                        Text("Museum").tag("Museum")
                        Text("Religious").tag("Religious")
                        Text("Architecture").tag("Architecture")
                        Text("Nature").tag("Nature")
                    }

                    HStack {
                        TextField("Latitude", text: $latitudeString)
                            .keyboardType(.decimalPad)
                        TextField("Longitude", text: $longitudeString)
                            .keyboardType(.decimalPad)
                    }

                    VStack(alignment: .leading) {
                        Text(
                            language == .vi
                                ? "Bán kính kích hoạt AR: \(Int(activationRadius))m"
                                : "AR Activation Radius: \(Int(activationRadius))m"
                        )
                        .font(.caption)
                        Slider(value: $activationRadius, in: 20...300, step: 10)
                    }

                    TextField("Cover Image URL (Optional)", text: $coverImageUrl)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                }

                Section("Nội dung Tiếng Việt (VI)") {
                    TextField("Tên địa điểm (VI)", text: $nameVi)
                    TextField("Tóm tắt ngắn (VI)", text: $summaryVi, axis: .vertical)
                        .lineLimit(2...3)
                    TextField("Câu chuyện văn hóa - lịch sử Markdown (VI)", text: $storyMarkdownVi, axis: .vertical)
                        .lineLimit(4...8)
                }

                Section("English Translation (EN)") {
                    TextField("POI Name (EN)", text: $nameEn)
                    TextField("Short Summary (EN)", text: $summaryEn, axis: .vertical)
                        .lineLimit(2...3)
                    TextField("Historical Story Markdown (EN)", text: $storyMarkdownEn, axis: .vertical)
                        .lineLimit(4...8)
                }

                Section(language == .vi ? "Thẻ thông tin 3D AR (AR Info Cards)" : "3D AR Spatial Info Cards") {
                    ForEach($arCards) { $card in
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Text("Azimuth: \(Int(card.azimuthDegrees))°")
                                    .font(.caption.weight(.bold))
                                Slider(value: $card.azimuthDegrees, in: 0...359, step: 5)
                            }
                            TextField("Tiêu đề thẻ AR (VI)", text: $card.titleVi)
                            TextField("Nội dung tóm tắt AR (VI)", text: $card.summaryVi)
                            TextField("AR Card Title (EN)", text: $card.titleEn)
                            TextField("AR Card Summary (EN)", text: $card.summaryEn)
                        }
                        .padding(.vertical, 4)
                    }

                    Button {
                        arCards.append(
                            ArInfoCardInput(
                                azimuthDegrees: Double((arCards.count * 60) % 360),
                                elevationMeters: 1.5,
                                distanceMeters: 4.0
                            )
                        )
                    } label: {
                        Label(
                            language == .vi ? "Thêm thẻ thông tin AR" : "Add AR Info Card",
                            systemImage: "plus.circle"
                        )
                    }
                }

                if let errorMessage = errorMessage {
                    Section {
                        Text(errorMessage)
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle(
                existingPoi == nil
                    ? (language == .vi ? "Tạo Điểm Tham Quan Mới" : "Create New POI")
                    : (language == .vi ? "Cập Nhật Điểm Tham Quan" : "Update POI")
            )
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(language == .vi ? "Hủy" : "Cancel") {
                        dismiss()
                    }
                }

                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        Task { await savePoi() }
                    } label: {
                        if isSaving {
                            ProgressView()
                        } else {
                            Text(language == .vi ? "Lưu & Gửi Duyệt" : "Submit")
                                .fontWeight(.bold)
                        }
                    }
                    .disabled(isSaving || slug.isEmpty || nameVi.isEmpty)
                }
            }
            .onAppear {
                if let poi = existingPoi {
                    slug = poi.slug
                    category = poi.category
                    latitudeString = String(format: "%.6f", poi.latitude)
                    longitudeString = String(format: "%.6f", poi.longitude)
                    activationRadius = poi.activationRadiusMeters
                    nameVi = poi.name
                    summaryVi = poi.shortSummary
                    storyMarkdownVi = poi.shortSummary
                    nameEn = poi.name
                    summaryEn = poi.shortSummary
                    storyMarkdownEn = poi.shortSummary
                } else {
                    latitudeString = String(format: "%.6f", defaultLatitude)
                    longitudeString = String(format: "%.6f", defaultLongitude)
                }
            }
        }
    }

    private func savePoi() async {
        guard let lat = Double(latitudeString), let lng = Double(longitudeString) else {
            errorMessage = "Tọa độ Latitude / Longitude không hợp lệ."
            return
        }

        isSaving = true
        errorMessage = nil
        defer { isSaving = false }

        let translations: [PoiTranslationInput] = [
            PoiTranslationInput(
                languageCode: "vi",
                name: nameVi,
                shortSummary: summaryVi.isEmpty ? nameVi : summaryVi,
                historicalStoryMarkdown: storyMarkdownVi.isEmpty ? summaryVi : storyMarkdownVi
            ),
            PoiTranslationInput(
                languageCode: "en",
                name: nameEn.isEmpty ? nameVi : nameEn,
                shortSummary: summaryEn.isEmpty ? summaryVi : summaryEn,
                historicalStoryMarkdown: storyMarkdownEn.isEmpty ? storyMarkdownVi : storyMarkdownEn
            )
        ]

        let request = UpsertPoiRequest(
            slug: slug.trimmingCharacters(in: .whitespacesAndNewlines),
            category: category,
            latitude: lat,
            longitude: lng,
            altitudeMeters: 10.0,
            activationRadiusMeters: activationRadius,
            coverImageUrl: coverImageUrl.isEmpty ? nil : coverImageUrl,
            translations: translations,
            arInfoCards: arCards
        )

        do {
            let saved: AdminPoiDto
            if let existing = existingPoi {
                saved = try await APIClient.shared.updateAdminPoi(poiId: existing.id, request: request)
            } else {
                saved = try await APIClient.shared.createAdminPoi(request)
            }
            onSaved(saved)
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
