import SwiftUI

struct PhotoInputSection: View {
    @Binding var imageData: Data?

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
                imageData: imageData,
                systemImage: placeholderSystemImage,
                isPreparingPhoto: isPreparingPhoto,
                onPreviewPhoto: onPreviewPhoto,
                onSourceRequest: onSourceRequest
            )
            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                if imageData != nil, !isPreparingPhoto {
                    Button(role: .destructive, action: removeImage) {
                        Label("删除", systemImage: "trash")
                    }

                    Button(action: onEditPhoto) {
                        Label("编辑", systemImage: "slider.horizontal.3")
                    }
                    .tint(.blue)
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
        onRemovePhoto()
    }
}
