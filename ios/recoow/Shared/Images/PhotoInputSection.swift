import SwiftUI

struct PhotoInputSection: View {
    @Environment(AppContainer.self) private var container
    @Binding var imageData: Data?
    @Binding var imageAssetID: String?

    static let placeholderSystemImage = "photo"

    let isPreparingPhoto: Bool
    let errorMessage: String?
    let onPreviewPhoto: () -> Void
    let onSourceRequest: () -> Void
    let onEditPhoto: () -> Void
    let onRemovePhoto: () -> Void

    var body: some View {
        Section("图片") {
            PhotoInputRowView(
                imageData: resolvedImageData,
                hasImage: hasImage,
                systemImage: Self.placeholderSystemImage,
                isPreparingPhoto: isPreparingPhoto,
                titleText: container.mediaAssetTitle(for: imageAssetID),
                accessibilityLabelText: container.mediaAssetTitle(for: imageAssetID).map { AppLocalization.format("更换图片：%@", $0) },
                onPreviewPhoto: onPreviewPhoto,
                onSourceRequest: onSourceRequest,
                onRemovePhoto: removeImage
            )
            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                if hasImage, !isPreparingPhoto {
                    Button(role: .destructive, action: removeImage) {
                        Label("删除", systemImage: "trash")
                    }

                    if imageAssetID == nil {
                        Button(action: onEditPhoto) {
                            Label("编辑", systemImage: "slider.horizontal.3")
                        }
                        .tint(AppPalette.accent)
                    }
                }
            }

            if let errorMessage {
                Text(errorMessage)
                    .font(.footnote)
                    .foregroundStyle(.red)
            }
        }
    }

    private func removeImage() {
        imageData = nil
        imageAssetID = nil
        onRemovePhoto()
    }

    private var hasImage: Bool {
        imageReference.hasImage
    }

    private var resolvedImageData: Data? {
        imageReference.resolvedData
    }

    private var imageReference: ImageReference {
        ImageReference(data: imageData, assetID: imageAssetID)
    }
}
