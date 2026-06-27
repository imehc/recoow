import SwiftUI

struct EditablePhotoInputSection: View {
    @Binding var imageData: Data?
    @Binding var imageAssetID: String?

    let placeholderSystemImage: String
    let coordinator: EditablePhotoInputCoordinator

    var body: some View {
        PhotoInputSection(
            imageData: $imageData,
            imageAssetID: $imageAssetID,
            placeholderSystemImage: placeholderSystemImage,
            isPreparingPhoto: coordinator.isPreparingPhoto,
            errorMessage: coordinator.imageErrorMessage,
            onPreviewPhoto: previewCurrentPhoto,
            onSourceRequest: coordinator.showPhotoSourcePicker,
            onEditPhoto: editCurrentPhoto,
            onRemovePhoto: coordinator.removePhoto
        )
    }

    private func previewCurrentPhoto() {
        coordinator.previewCurrentPhoto(imageData: resolvedImageData)
    }

    private func editCurrentPhoto() {
        coordinator.editCurrentPhoto(imageData: imageData)
    }

    private var resolvedImageData: Data? {
        imageReference.resolvedData
    }

    private var imageReference: ImageReference {
        ImageReference(data: imageData, assetID: imageAssetID)
    }
}
