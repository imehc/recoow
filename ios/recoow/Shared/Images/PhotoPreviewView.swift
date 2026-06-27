import Lightbox
import SwiftUI
import UIKit

@MainActor
struct PhotoPreviewView: View {
    @Environment(\.dismiss) private var dismiss

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
    }

    var body: some View {
        Group {
            if previewItems.isEmpty {
                unavailableView
            } else {
                LightboxPhotoPreviewController(
                    items: previewItems,
                    startIndex: startIndex,
                    onDismiss: close
                )
                .ignoresSafeArea()
            }
        }
        .background(Color.black.ignoresSafeArea())
        .preferredColorScheme(.dark)
        .statusBarHidden(true)
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
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(onDismiss: onDismiss)
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
        private var didRequestDismiss = false

        init(onDismiss: @escaping () -> Void) {
            self.onDismiss = onDismiss
        }

        func lightboxControllerWillDismiss(_ controller: LightboxController) {
            guard didRequestDismiss == false else { return }
            didRequestDismiss = true
            onDismiss()
        }

        func lightboxController(_ controller: LightboxController, didMoveToPage page: Int) {
            didRequestDismiss = false
        }
    }
}

private struct PhotoPreviewDisplayItem: Identifiable {
    let id: String
    let image: UIImage

    init?(item: PhotoPreviewItem) {
        guard let image = UIImage(data: item.imageData) else { return nil }
        self.id = item.id
        self.image = image
    }
}
