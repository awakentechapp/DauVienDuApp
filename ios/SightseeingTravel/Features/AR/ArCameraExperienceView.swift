import SwiftUI
import ARKit
import SceneKit

public enum SelectedArOverlayItem: Identifiable, Equatable {
    case infoCard(ArInfoCardDto)
    case communityPhoto(CommunityPhotoDto)

    public var id: String {
        switch self {
        case .infoCard(let card): return "card_\(card.id)"
        case .communityPhoto(let photo): return "photo_\(photo.id)"
        }
    }
}

public struct ArCameraExperienceView: View {
    @EnvironmentObject private var appState: AppState
    @EnvironmentObject private var locationManager: LocationManager
    @Environment(\.dismiss) private var dismiss

    public let poi: NearbyPoiDto

    @State private var arContext: ArContextResponse?
    @State private var selectedItem: SelectedArOverlayItem?
    @State private var isLoading: Bool = true
    @State private var errorMessage: String?
    @State private var capturedSnapshot: UIImage?
    @State private var showPhotoUploadSheet: Bool = false
    @State private var triggerSnapshotRequest: Bool = false

    public init(poi: NearbyPoiDto) {
        self.poi = poi
    }

    public var body: some View {
        ZStack {
            // Live ARKit SceneKit View (.gravityAndHeading)
            ArSceneKitContainerView(
                arInfoCards: arContext?.arInfoCards ?? [],
                highlightedPhotos: Array((arContext?.highlightedCommunityPhotos ?? []).prefix(10)),
                triggerSnapshotRequest: $triggerSnapshotRequest,
                onNodeTapped: { tappedItem in
                    withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
                        selectedItem = tappedItem
                    }
                },
                onSnapshotCaptured: { image in
                    capturedSnapshot = image
                    showPhotoUploadSheet = true
                }
            )
            .ignoresSafeArea()

            // Top HUD Overlay
            VStack {
                topArHudBar
                    .padding(.horizontal, 16)
                    .padding(.top, 10)

                if isLoading {
                    HStack(spacing: 10) {
                        ProgressView()
                            .tint(.white)
                        Text(
                            appState.selectedLanguage == .vi
                                ? "Đang tải không gian AR & Top 10 ảnh cộng đồng..."
                                : "Loading 3D AR Info Cards & Top 10 Community Photos..."
                        )
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.white)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(.ultraThinMaterial)
                    .clipShape(Capsule())
                }

                if let errorMessage = errorMessage {
                    HStack(spacing: 8) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundStyle(.yellow)
                        Text(errorMessage)
                            .font(.caption)
                            .foregroundStyle(.white)
                    }
                    .padding(10)
                    .background(Color.black.opacity(0.65))
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .padding(.horizontal, 16)
                }

                Spacer()

                // Selected 3D Node Detail Overlay Card (Story or Community Photo)
                if let item = selectedItem {
                    selectedNodeOverlayCard(item)
                        .padding(.horizontal, 16)
                        .padding(.bottom, 8)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }

                // Bottom AR Action Bar (Compass + Capture & Share Photo at POI)
                bottomArControlBar
                    .padding(.horizontal, 16)
                    .padding(.bottom, 16)
            }
        }
        .task(id: appState.selectedLanguage) {
            await loadArContext()
        }
        .sheet(isPresented: $showPhotoUploadSheet) {
            OnSitePhotoUploadSheet(
                poiId: poi.id,
                poiName: arContext?.poiName ?? poi.name,
                initialImage: capturedSnapshot,
                onPhotoUploaded: { newPhoto in
                    appState.recordUploadedPhoto(newPhoto)
                    Task { await loadArContext() }
                }
            )
            .environmentObject(appState)
            .environmentObject(locationManager)
        }
    }

    // MARK: - Top HUD Bar

    private var topArHudBar: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Image(systemName: "arkit")
                        .foregroundStyle(.green)
                    Text(arContext?.poiName ?? poi.name)
                        .font(.headline)
                        .foregroundStyle(.white)
                        .lineLimit(1)
                }

                let cardsCount = arContext?.arInfoCards.count ?? 0
                let photosCount = min(10, arContext?.highlightedCommunityPhotos.count ?? 0)
                Text(
                    appState.selectedLanguage == .vi
                        ? "\(cardsCount) Thẻ thuyết minh AR • Top \(photosCount) Ảnh nổi bật"
                        : "\(cardsCount) AR Story Cards • Top \(photosCount) Highlight Photos"
                )
                .font(.caption2)
                .foregroundStyle(.white.opacity(0.85))
            }

            Spacer()

            // Switch VI / EN in AR Live
            Button {
                let nextLang: AppLanguage = appState.selectedLanguage == .vi ? .en : .vi
                Task {
                    await appState.switchLanguage(nextLang)
                }
            } label: {
                Text(appState.selectedLanguage.rawValue.uppercased())
                    .font(.caption.weight(.bold))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Color.teal)
                    .foregroundStyle(.white)
                    .clipShape(Capsule())
            }

            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.title2)
                    .foregroundStyle(.white)
            }
        }
        .padding(12)
        .background(Color.black.opacity(0.55))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    // MARK: - Selected AR Node Overlay Card

    @ViewBuilder
    private func selectedNodeOverlayCard(_ item: SelectedArOverlayItem) -> some View {
        switch item {
        case .infoCard(let card):
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Label(
                        card.cardType ?? (appState.selectedLanguage == .vi ? "Thuyết minh AR" : "AR Story Card"),
                        systemImage: "sparkles.rectangle.stack.fill"
                    )
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.teal)

                    Spacer()

                    Text("\(Int(card.azimuthDegrees))° • \(String(format: "%.1fm", card.distanceMeters))")
                        .font(.caption2)
                        .foregroundStyle(.secondary)

                    Button {
                        selectedItem = nil
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(.secondary)
                    }
                }

                Text(card.title)
                    .font(.title3.weight(.bold))

                if let summary = card.summary ?? card.subtitle {
                    Text(summary)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                if let detailMd = card.detailMarkdown, !detailMd.isEmpty {
                    ScrollView {
                        Text(detailMd)
                            .font(.footnote)
                            .lineSpacing(4)
                    }
                    .frame(maxHeight: 130)
                }
            }
            .padding(16)
            .background(.regularMaterial)
            .clipShape(RoundedRectangle(cornerRadius: 18))
            .shadow(color: .black.opacity(0.25), radius: 12, y: 6)

        case .communityPhoto(let photo):
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Label(
                        photo.authorDisplayName,
                        systemImage: "camera.Macro"
                    )
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(.teal)

                    Spacer()

                    Label("\(photo.likesCount)", systemImage: "heart.fill")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.pink)

                    Button {
                        selectedItem = nil
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(.secondary)
                    }
                }

                AsyncImage(url: URL(string: photo.imageUrl)) { phase in
                    switch phase {
                    case .success(let img):
                        img.resizable()
                            .scaledToFill()
                            .frame(height: 185)
                            .clipped()
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                    default:
                        RoundedRectangle(cornerRadius: 12)
                            .fill(Color.gray.opacity(0.25))
                            .frame(height: 150)
                            .overlay(ProgressView())
                    }
                }

                if let caption = photo.caption, !caption.isEmpty {
                    Text(caption)
                        .font(.subheadline)
                }

                HStack {
                    Text(photo.createdAt)
                        .font(.caption2)
                        .foregroundStyle(.secondary)

                    Spacer()

                    Button {
                        Task {
                            if let res = try? await APIClient.shared.toggleLikePhoto(photoId: photo.id) {
                                let updated = CommunityPhotoDto(
                                    id: photo.id,
                                    poiId: photo.poiId,
                                    userId: photo.userId,
                                    authorDisplayName: photo.authorDisplayName,
                                    authorAvatarUrl: photo.authorAvatarUrl,
                                    imageUrl: photo.imageUrl,
                                    thumbnailUrl: photo.thumbnailUrl,
                                    caption: photo.caption,
                                    capturedLatitude: photo.capturedLatitude,
                                    capturedLongitude: photo.capturedLongitude,
                                    azimuthDegrees: photo.azimuthDegrees,
                                    likesCount: res.likesCount,
                                    isLikedByCurrentUser: res.liked,
                                    moderationStatus: photo.moderationStatus,
                                    createdAt: photo.createdAt
                                )
                                selectedItem = .communityPhoto(updated)
                            }
                        }
                    } label: {
                        Label(
                            appState.selectedLanguage == .vi ? "Yêu thích ảnh này" : "Like Photo",
                            systemImage: "heart.fill"
                        )
                        .font(.caption.weight(.bold))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Color.pink.opacity(0.15))
                        .foregroundStyle(.pink)
                        .clipShape(Capsule())
                    }
                }
            }
            .padding(16)
            .background(.regularMaterial)
            .clipShape(RoundedRectangle(cornerRadius: 18))
            .shadow(color: .black.opacity(0.25), radius: 12, y: 6)
        }
    }

    // MARK: - Bottom Control Bar

    private var bottomArControlBar: some View {
        HStack(spacing: 14) {
            // Compass Telemetry Badge
            HStack(spacing: 6) {
                Image(systemName: "location.north.line.fill")
                    .foregroundStyle(.teal)
                    .rotationEffect(.degrees(-locationManager.azimuthDegrees))
                Text("\(Int(locationManager.azimuthDegrees))°")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.white)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(Color.black.opacity(0.6))
            .clipShape(Capsule())

            Spacer()

            // Integrated Capture & Share Photo at POI Button
            Button {
                triggerSnapshotRequest = true
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "camera.shutter.button.fill")
                        .font(.title3)
                    Text(
                        appState.selectedLanguage == .vi
                            ? "Chụp & Ghim Ảnh AR Tại Điểm"
                            : "Capture & Share AR Photo"
                    )
                    .font(.subheadline.weight(.bold))
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 12)
                .background(
                    LinearGradient(
                        colors: [.teal, .green],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                )
                .foregroundStyle(.white)
                .clipShape(Capsule())
                .shadow(color: .teal.opacity(0.5), radius: 8, y: 4)
            }
        }
    }

    // MARK: - Load AR Context from Backend

    private func loadArContext() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        do {
            let response = try await APIClient.shared.getPoiArContext(
                poiId: poi.id,
                lat: locationManager.latitude,
                lng: locationManager.longitude,
                lang: appState.selectedLanguage
            )
            arContext = response
        } catch {
            // Fallback to POI Detail if ar-context returns an error
            if let detail = try? await APIClient.shared.getPoiDetail(
                poiId: poi.id,
                lat: locationManager.latitude,
                lng: locationManager.longitude,
                lang: appState.selectedLanguage
            ) {
                arContext = ArContextResponse(
                    poiId: detail.id,
                    poiName: detail.name,
                    poiLatitude: detail.latitude,
                    poiLongitude: detail.longitude,
                    userDistanceMeters: detail.distanceMeters ?? 0.0,
                    arInfoCards: detail.arInfoCards ?? [],
                    highlightedCommunityPhotos: detail.highlightedCommunityPhotos ?? []
                )
            } else {
                errorMessage = error.localizedDescription
            }
        }
    }
}

// MARK: - UIViewRepresentable ARSCNView Wrapper

private struct ArSceneKitContainerView: UIViewRepresentable {
    let arInfoCards: [ArInfoCardDto]
    let highlightedPhotos: [CommunityPhotoDto]
    @Binding var triggerSnapshotRequest: Bool
    let onNodeTapped: (SelectedArOverlayItem) -> Void
    let onSnapshotCaptured: (UIImage) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onNodeTapped: onNodeTapped)
    }

    func makeUIView(context: Context) -> ARSCNView {
        let sceneView = ARSCNView(frame: .zero)
        sceneView.delegate = context.coordinator
        sceneView.autoenablesDefaultLighting = true
        sceneView.automaticallyUpdatesLighting = true
        sceneView.scene = SCNScene()

        let tapGesture = UITapGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleSceneTap(_:))
        )
        sceneView.addGestureRecognizer(tapGesture)
        context.coordinator.sceneView = sceneView

        let config = ARWorldTrackingConfiguration()
        if ARWorldTrackingConfiguration.isSupported {
            config.worldAlignment = .gravityAndHeading
            config.planeDetection = [.horizontal]
            sceneView.session.run(config, options: [.resetTracking, .removeExistingAnchors])
        }

        return sceneView
    }

    func updateUIView(_ uiView: ARSCNView, context: Context) {
        context.coordinator.onNodeTapped = onNodeTapped
        context.coordinator.syncSpatialNodes(
            infoCards: arInfoCards,
            highlightedPhotos: highlightedPhotos
        )

        if triggerSnapshotRequest {
            let snapshot = uiView.snapshot()
            DispatchQueue.main.async {
                triggerSnapshotRequest = false
                onSnapshotCaptured(snapshot)
            }
        }
    }

    static func dismantleUIView(_ uiView: ARSCNView, coordinator: Coordinator) {
        uiView.session.pause()
    }

    // MARK: - Coordinator Managing 3D Billboards & Tap Hit-Testing

    final class Coordinator: NSObject, ARSCNViewDelegate {
        weak var sceneView: ARSCNView?
        var onNodeTapped: (SelectedArOverlayItem) -> Void

        private var renderedSignature: String = ""
        private var nodeMap: [String: SelectedArOverlayItem] = [:]

        init(onNodeTapped: @escaping (SelectedArOverlayItem) -> Void) {
            self.onNodeTapped = onNodeTapped
        }

        func syncSpatialNodes(
            infoCards: [ArInfoCardDto],
            highlightedPhotos: [CommunityPhotoDto]
        ) {
            guard let sceneView = sceneView else { return }
            let newSignature = infoCards.map(\.id).joined(separator: ",") + "|" + highlightedPhotos.map(\.id).joined(separator: ",")
            guard newSignature != renderedSignature else { return }
            renderedSignature = newSignature

            // Remove previous spatial nodes
            sceneView.scene.rootNode.childNodes
                .filter { $0.name?.hasPrefix("ar_item_") == true }
                .forEach { $0.removeFromParentNode() }
            nodeMap.removeAll()

            // 1. Place 3D AR Cultural Info Cards using azimuthDegrees, distanceMeters, elevationMeters
            for card in infoCards {
                let nodeName = "ar_item_card_\(card.id)"
                nodeMap[nodeName] = .infoCard(card)

                let cardImage = Self.renderInfoCardTexture(card: card)
                let plane = SCNPlane(width: 1.35, height: 0.78)
                plane.cornerRadius = 0.08
                plane.firstMaterial?.diffuse.contents = cardImage
                plane.firstMaterial?.isDoubleSided = true
                plane.firstMaterial?.lightingModel = .constant

                let node = SCNNode(geometry: plane)
                node.name = nodeName

                let billboard = SCNBillboardConstraint()
                billboard.freeAxes = [.Y]
                node.constraints = [billboard]

                let clampedDist = max(2.2, min(12.0, card.distanceMeters))
                let rad = Float(card.azimuthDegrees * .pi / 180.0)
                let x = Float(clampedDist) * sin(rad)
                let z = -Float(clampedDist) * cos(rad)
                let y = Float(card.elevationMeters)

                node.position = SCNVector3(x, y, z)
                sceneView.scene.rootNode.addChildNode(node)
            }

            // 2. Place Floating 3D Frames for Top 10 Highlighted Community Photos around the user
            for (index, photo) in highlightedPhotos.enumerated() {
                let nodeName = "ar_item_photo_\(photo.id)"
                nodeMap[nodeName] = .communityPhoto(photo)

                let fallbackAzimuth = Double(index) * (360.0 / Double(max(1, highlightedPhotos.count))) + 18.0
                let azimuth = photo.azimuthDegrees ?? fallbackAzimuth
                let distance: Double = 3.4
                let elevation: Double = (index % 2 == 0) ? 0.55 : -0.25

                let placeholderImg = Self.renderPhotoBillboardTexture(photo: photo, downloadedImage: nil)
                let plane = SCNPlane(width: 1.05, height: 1.15)
                plane.cornerRadius = 0.07
                plane.firstMaterial?.diffuse.contents = placeholderImg
                plane.firstMaterial?.isDoubleSided = true
                plane.firstMaterial?.lightingModel = .constant

                let node = SCNNode(geometry: plane)
                node.name = nodeName

                let billboard = SCNBillboardConstraint()
                billboard.freeAxes = [.Y]
                node.constraints = [billboard]

                let rad = Float(azimuth * .pi / 180.0)
                let x = Float(distance) * sin(rad)
                let z = -Float(distance) * cos(rad)
                node.position = SCNVector3(x, Float(elevation), z)

                sceneView.scene.rootNode.addChildNode(node)

                // Asynchronously load the community photo image into the 3D AR frame
                if let url = URL(string: photo.bestThumbnailOrImageUrl) {
                    Task.detached { [weak node] in
                        if let (data, _) = try? await URLSession.shared.data(from: url),
                           let downloadedUIImage = UIImage(data: data) {
                            await MainActor.run {
                                let updatedTexture = Self.renderPhotoBillboardTexture(
                                    photo: photo,
                                    downloadedImage: downloadedUIImage
                                )
                                node?.geometry?.firstMaterial?.diffuse.contents = updatedTexture
                            }
                        }
                    }
                }
            }
        }

        @objc func handleSceneTap(_ recognizer: UITapGestureRecognizer) {
            guard let sceneView = sceneView else { return }
            let location = recognizer.location(in: sceneView)
            let hitResults = sceneView.hitTest(
                location,
                options: [.searchMode: SCNHitTestSearchMode.all.rawValue]
            )

            for hit in hitResults {
                var current: SCNNode? = hit.node
                while let candidate = current {
                    if let name = candidate.name, let matchedItem = nodeMap[name] {
                        DispatchQueue.main.async {
                            self.onNodeTapped(matchedItem)
                        }
                        return
                    }
                    current = candidate.parent
                }
            }
        }

        // MARK: - 2D-to-3D Texture Renderers for AR Billboards

        private static func renderInfoCardTexture(card: ArInfoCardDto) -> UIImage {
            let size = CGSize(width: 540, height: 310)
            let renderer = UIGraphicsImageRenderer(size: size)
            return renderer.image { ctx in
                let rect = CGRect(origin: .zero, size: size)
                let path = UIBezierPath(roundedRect: rect, cornerRadius: 28)
                UIColor(red: 0.04, green: 0.16, blue: 0.22, alpha: 0.92).setFill()
                path.fill()

                UIColor(red: 0.0, green: 0.78, blue: 0.72, alpha: 1.0).setStroke()
                path.lineWidth = 6
                path.stroke()

                let badgeAttrs: [NSAttributedString.Key: Any] = [
                    .font: UIFont.boldSystemFont(ofSize: 20),
                    .foregroundColor: UIColor(red: 0.2, green: 0.95, blue: 0.85, alpha: 1.0)
                ]
                let badgeText = "✦ AR STORY • \(Int(card.azimuthDegrees))°"
                badgeText.draw(in: CGRect(x: 28, y: 24, width: 484, height: 28), withAttributes: badgeAttrs)

                let titleAttrs: [NSAttributedString.Key: Any] = [
                    .font: UIFont.boldSystemFont(ofSize: 30),
                    .foregroundColor: UIColor.white
                ]
                card.title.draw(in: CGRect(x: 28, y: 62, width: 484, height: 76), withAttributes: titleAttrs)

                let summaryText = card.summary ?? card.subtitle ?? card.detailMarkdown ?? ""
                let bodyAttrs: [NSAttributedString.Key: Any] = [
                    .font: UIFont.systemFont(ofSize: 22, weight: .regular),
                    .foregroundColor: UIColor(white: 0.9, alpha: 1.0)
                ]
                summaryText.draw(in: CGRect(x: 28, y: 146, width: 484, height: 130), withAttributes: bodyAttrs)
            }
        }

        private static func renderPhotoBillboardTexture(
            photo: CommunityPhotoDto,
            downloadedImage: UIImage?
        ) -> UIImage {
            let size = CGSize(width: 420, height: 460)
            let renderer = UIGraphicsImageRenderer(size: size)
            return renderer.image { _ in
                let rect = CGRect(origin: .zero, size: size)
                let outerPath = UIBezierPath(roundedRect: rect, cornerRadius: 26)
                UIColor(white: 0.12, alpha: 0.94).setFill()
                outerPath.fill()

                UIColor.systemTeal.setStroke()
                outerPath.lineWidth = 5
                outerPath.stroke()

                let imageRect = CGRect(x: 18, y: 18, width: 384, height: 340)
                let clipPath = UIBezierPath(roundedRect: imageRect, cornerRadius: 18)
                clipPath.addClip()

                if let img = downloadedImage {
                    img.draw(in: imageRect)
                } else {
                    UIColor.darkGray.setFill()
                    UIRectFill(imageRect)
                }

                UIGraphicsGetCurrentContext()?.resetClip()

                let authorAttrs: [NSAttributedString.Key: Any] = [
                    .font: UIFont.boldSystemFont(ofSize: 22),
                    .foregroundColor: UIColor.white
                ]
                let footerTitle = "📸 \(photo.authorDisplayName)   ♥ \(photo.likesCount)"
                footerTitle.draw(in: CGRect(x: 22, y: 372, width: 376, height: 32), withAttributes: authorAttrs)

                if let caption = photo.caption, !caption.isEmpty {
                    let capAttrs: [NSAttributedString.Key: Any] = [
                        .font: UIFont.systemFont(ofSize: 18),
                        .foregroundColor: UIColor.lightGray
                    ]
                    caption.draw(in: CGRect(x: 22, y: 406, width: 376, height: 36), withAttributes: capAttrs)
                }
            }
        }
    }
}
