import SwiftUI

struct EditablePhotoInputPresentationModifier: ViewModifier {
    @Bindable var coordinator: EditablePhotoInputCoordinator
    @Binding var imageData: Data?
    @Binding var imageAssetID: String?
    var mediaAssetRepository: MediaAssetRepository?

    func body(content: Content) -> some View {
        content
            .navigationDestination(isPresented: $coordinator.isShowingPhotoEditor) {
                if let editablePhoto = coordinator.editablePhoto {
                    PhotoEditorView(
                        image: editablePhoto.image,
                        onCancel: coordinator.cancelPhotoEditing,
                        onSave: saveEditedImage
                    )
                } else {
                    ContentUnavailableView("无法编辑图片", systemImage: "photo")
                }
            }
            .fullScreenCover(
                isPresented: $coordinator.isShowingPhotoSourcePicker,
                onDismiss: coordinator.presentPendingEditorIfNeeded
            ) {
                PhotoSourcePickerView(
                    onPhotoPicked: coordinator.beginEditingPickedImage,
                    mediaAssetRepository: mediaAssetRepository,
                    onMediaAssetPicked: beginEditingPickedAsset
                )
            }
            .onChange(of: coordinator.isShowingPhotoSourcePicker) { _, isShowing in
                if isShowing == false {
                    coordinator.presentPendingEditorIfNeeded()
                }
            }
            .onChange(of: coordinator.pendingEditablePhoto?.id) {
                coordinator.presentPendingEditorIfNeeded()
            }
            .fullScreenCover(item: $coordinator.previewPhoto) { item in
                PhotoPreviewView(item: item)
            }
    }

    private func saveEditedImage(_ data: Data) {
        imageData = data
        imageAssetID = nil
        coordinator.finishPhotoEditing()
    }

    private func beginEditingPickedAsset(_ asset: MediaAsset) {
        guard mediaAssetRepository?.data(for: asset) != nil else {
            coordinator.imageErrorMessage = AppLocalization.string("无法准备照片，请重试")
            return
        }

        imageData = nil
        imageAssetID = asset.id
        coordinator.finishPhotoEditing()
    }
}

extension View {
    func editablePhotoInputPresentation(
        coordinator: EditablePhotoInputCoordinator,
        imageData: Binding<Data?>,
        imageAssetID: Binding<String?>,
        mediaAssetRepository: MediaAssetRepository? = nil
    ) -> some View {
        modifier(
            EditablePhotoInputPresentationModifier(
                coordinator: coordinator,
                imageData: imageData,
                imageAssetID: imageAssetID,
                mediaAssetRepository: mediaAssetRepository
            )
        )
    }
}
