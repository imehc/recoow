import Foundation
import Observation

@MainActor
@Observable
final class EditablePhotoInputCoordinator {
    var isShowingPhotoSourcePicker = false
    var isPreparingPhoto = false
    var imageErrorMessage: String?
    var previewPhoto: PhotoPreviewItem?
    var pendingEditablePhoto: EditablePhoto?
    var editablePhoto: EditablePhoto?
    var isShowingPhotoEditor = false

    func showPhotoSourcePicker() {
        guard !isPreparingPhoto else { return }
        imageErrorMessage = nil
        isShowingPhotoSourcePicker = true
    }

    func beginEditingPickedImage(_ data: Data) {
        imageErrorMessage = nil
        isPreparingPhoto = true

        Task {
            defer { isPreparingPhoto = false }

            guard let photo = await PhotoImagePreparer.editablePhoto(from: data) else {
                imageErrorMessage = AppLocalization.string("无法准备照片，请重试")
                return
            }

            pendingEditablePhoto = photo
            presentPendingEditorIfPossible()
        }
    }

    func presentPendingEditorIfNeeded() {
        presentPendingEditorIfPossible()
    }

    func presentPendingEditorIfPossible() {
        guard let photo = pendingEditablePhoto,
              !isShowingPhotoSourcePicker
        else { return }

        pendingEditablePhoto = nil
        editablePhoto = photo
        isShowingPhotoEditor = true
    }

    func editCurrentPhoto(imageData: Data?) {
        guard let imageData else { return }
        imageErrorMessage = nil
        isPreparingPhoto = true

        Task {
            defer { isPreparingPhoto = false }

            guard let photo = await PhotoImagePreparer.editablePhoto(from: imageData) else {
                imageErrorMessage = AppLocalization.string("无法编辑当前图片，请重试")
                return
            }

            pendingEditablePhoto = photo
            presentPendingEditorIfPossible()
        }
    }

    func removePhoto() {
        imageErrorMessage = nil
        previewPhoto = nil
        pendingEditablePhoto = nil
        editablePhoto = nil
        isShowingPhotoEditor = false
    }

    func previewCurrentPhoto(imageData: Data?) {
        guard let imageData else { return }
        previewPhoto = PhotoPreviewItem(imageData: imageData)
    }

    func cancelPhotoEditing() {
        isShowingPhotoEditor = false
    }

    func finishPhotoEditing() {
        isShowingPhotoEditor = false
        editablePhoto = nil
    }
}
