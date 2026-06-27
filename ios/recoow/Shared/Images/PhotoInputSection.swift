import SwiftUI

struct PhotoInputSection: View {
    @Binding var imageData: Data?
    @Binding var imageAssetID: String?

    let placeholderSystemImage: String
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
                systemImage: placeholderSystemImage,
                isPreparingPhoto: isPreparingPhoto,
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
                        .tint(.blue)
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
