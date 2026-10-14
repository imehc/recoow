import Lightbox
import SwiftUI
import UIKit

@MainActor
struct PhotoPreviewView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(AppContainer.self) private var container
    @State private var currentPage: Int
    @State private var captionHeight: CGFloat = 40

    private let previewItems: [PhotoPreviewDisplayItem]
    private let startIndex: Int

    init(item: PhotoPreviewItem) {
        self.init(items: [item], initialID: item.id)
    }

    init(items: [PhotoPreviewItem], initialID: String? = nil) {
        let displayItems = items.compactMap(PhotoPreviewDisplayItem.init(item:))
        self.previewItems = displayItems
        self.startIndex = initialID.flatMap { id in
            displayItems.firstIndex { $0.id == id }
        } ?? 0
        _currentPage = State(initialValue: self.startIndex)
    }

    var body: some View {
        Group {
            if previewItems.isEmpty {
                unavailableView
            } else {
                VStack(spacing: 0) {
                    LightboxPhotoPreviewController(
                        items: previewItems,
                        startIndex: startIndex,
                        onDismiss: close,
                        onPageChange: { currentPage = $0 }
                    )
                    .ignoresSafeArea(edges: currentCaption == nil ? .all : [.top, .horizontal])

                    if let caption = currentCaption {
                        ScrollView {
                            Text(caption)
                                .font(AppDesign.rowSubtitleFont)
                                .foregroundStyle(.white)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .textSelection(.enabled)
                                .padding(.horizontal, AppDesign.pagePadding)
                                .padding(.vertical, 8)
                                .fixedSize(horizontal: false, vertical: true)
                                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { captionHeight = $0 }
                        }
                        .frame(height: min(captionHeight, 180))
                    }
                }
            }
        }
        .background(Color.black.ignoresSafeArea())
        .preferredColorScheme(.dark)
        .statusBarHidden(true)
    }

    private var currentCaption: String? {
        guard previewItems.indices.contains(currentPage) else { return nil }
        let item = previewItems[currentPage]
        let assetTitle = container.mediaAssetTitle(for: item.assetID) ?? item.assetTitleFallback
        let title = MediaAsset.normalizedTitle(item.title)
        let parts = [title, assetTitle].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: "\n")
    }

    private var unavailableView: some View {
        VStack(spacing: 12) {
            Image(systemName: "photo")
                .font(.largeTitle)
                .accessibilityHidden(true)

            Text(AppLocalization.string("无法预览图片"))
                .font(.headline)
        }
        .foregroundStyle(.white)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func close() {
        dismiss()
    }
}

private struct LightboxPhotoPreviewController: UIViewControllerRepresentable {
    let items: [PhotoPreviewDisplayItem]
    let startIndex: Int
    let onDismiss: () -> Void
    let onPageChange: (Int) -> Void

    func makeUIViewController(context: Context) -> LightboxController {
        configureLightboxStyle()

        let controller = LightboxController(
            images: items.map { LightboxImage(image: $0.image) },
            startIndex: startIndex
        )
        controller.dynamicBackground = false
        controller.dismissalDelegate = context.coordinator
        controller.pageDelegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: LightboxController, context: Context) {
        context.coordinator.onDismiss = onDismiss
        context.coordinator.onPageChange = onPageChange
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(onDismiss: onDismiss, onPageChange: onPageChange)
    }

    private func configureLightboxStyle() {
        LightboxConfig.hideStatusBar = true
        LightboxConfig.imageBackgroundColor = .black
        LightboxConfig.preload = 0
        LightboxConfig.Zoom.maximumScale = 4

        LightboxConfig.CloseButton.enabled = true
        LightboxConfig.CloseButton.text = AppLocalization.string("关闭")
        LightboxConfig.CloseButton.size = CGSize(width: 56, height: AppDesign.touchIconSize)
        LightboxConfig.CloseButton.image = nil
        LightboxConfig.CloseButton.textAttributes = [
            .font: UIFont.systemFont(ofSize: 16, weight: .semibold),
            .foregroundColor: UIColor.white,
            .paragraphStyle: centeredParagraphStyle
        ]

        LightboxConfig.DeleteButton.enabled = false
        LightboxConfig.InfoLabel.enabled = false
        LightboxConfig.PageIndicator.enabled = items.count > 1
        LightboxConfig.PageIndicator.separatorColor = .clear
        LightboxConfig.PageIndicator.textAttributes = [
            .font: UIFont.monospacedDigitSystemFont(ofSize: 13, weight: .semibold),
            .foregroundColor: UIColor.white.withAlphaComponent(0.92),
            .paragraphStyle: centeredParagraphStyle
        ]
    }

    private var centeredParagraphStyle: NSMutableParagraphStyle {
        let style = NSMutableParagraphStyle()
        style.alignment = .center
        return style
    }

    final class Coordinator: NSObject, LightboxControllerDismissalDelegate, LightboxControllerPageDelegate {
        var onDismiss: () -> Void
        var onPageChange: (Int) -> Void
        private var didRequestDismiss = false

        init(onDismiss: @escaping () -> Void, onPageChange: @escaping (Int) -> Void) {
            self.onDismiss = onDismiss
            self.onPageChange = onPageChange
        }

        func lightboxControllerWillDismiss(_ controller: LightboxController) {
            guard didRequestDismiss == false else { return }
            didRequestDismiss = true
            onDismiss()
        }

        func lightboxController(_ controller: LightboxController, didMoveToPage page: Int) {
            didRequestDismiss = false
            onPageChange(page)
        }
    }
}

private struct PhotoPreviewDisplayItem: Identifiable {
    let id: String
    let image: UIImage
    let title: String?
    let assetID: String?
    let assetTitleFallback: String?

    init?(item: PhotoPreviewItem) {
        guard let image = UIImage(data: item.imageData) else { return nil }
        self.id = item.id
        self.image = image
        self.title = item.title
        self.assetID = item.assetID
        self.assetTitleFallback = item.assetTitleFallback
    }
}
