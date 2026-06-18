import SwiftUI

struct EditablePhotoInputPresentationModifier: ViewModifier {
    @Bindable var coordinator: EditablePhotoInputCoordinator
    @Binding var imageData: Data?

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
                    onPhotoPicked: coordinator.beginEditingPickedImage
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
            .sheet(item: $coordinator.previewPhoto, content: PhotoPreviewView.init)
    }

    private func saveEditedImage(_ data: Data) {
        imageData = data
        coordinator.finishPhotoEditing()
    }
}

extension View {
    func editablePhotoInputPresentation(
        coordinator: EditablePhotoInputCoordinator,
        imageData: Binding<Data?>
    ) -> some View {
        modifier(
            EditablePhotoInputPresentationModifier(
                coordinator: coordinator,
                imageData: imageData
            )
        )
    }
}
