import SwiftUI

struct EditablePhotoInputSection: View {
    @Binding var imageData: Data?

    let placeholderSystemImage: String
    let coordinator: EditablePhotoInputCoordinator

    var body: some View {
        PhotoInputSection(
            imageData: $imageData,
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
        coordinator.previewCurrentPhoto(imageData: imageData)
    }

    private func editCurrentPhoto() {
        coordinator.editCurrentPhoto(imageData: imageData)
    }
}
