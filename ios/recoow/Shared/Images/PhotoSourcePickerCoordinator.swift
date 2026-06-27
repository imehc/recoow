import Photos
import PhotosUI
import UIKit
import UniformTypeIdentifiers

final class PhotoSourcePickerCoordinator: NSObject, UINavigationControllerDelegate, UIImagePickerControllerDelegate, PHPickerViewControllerDelegate {
    private let savesCameraPhotosToLibrary: Bool
    private let onSelect: (PhotoSourcePickerMode, Data) -> Void
    private let onCancel: () -> Void

    init(
        savesCameraPhotosToLibrary: Bool,
        onSelect: @escaping (PhotoSourcePickerMode, Data) -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.savesCameraPhotosToLibrary = savesCameraPhotosToLibrary
        self.onSelect = onSelect
        self.onCancel = onCancel
    }

    func imagePickerController(
        _ picker: UIImagePickerController,
        didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
    ) {
        if let image = info[.originalImage] as? UIImage,
           let data = image.jpegData(compressionQuality: 0.82) {
            let source: PhotoSourcePickerMode = picker.sourceType == .camera ? .camera : .library
            if source == .camera, savesCameraPhotosToLibrary {
                PhotoLibraryImageSaver.save(image)
            }
            onSelect(source, data)
        } else {
            onCancel()
        }
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        onCancel()
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        guard let provider = results.first?.itemProvider else {
            onCancel()
            return
        }

        let imageTypeIdentifier = provider.registeredTypeIdentifiers.first { identifier in
            UTType(identifier)?.conforms(to: .image) == true
        } ?? UTType.image.identifier

        provider.loadDataRepresentation(forTypeIdentifier: imageTypeIdentifier) { [weak self] data, _ in
            guard let self else { return }

            DispatchQueue.main.async {
                guard let data,
                      let normalizedData = Self.normalizedImageData(from: data) else {
                    self.onCancel()
                    return
                }

                self.onSelect(.library, normalizedData)
            }
        }
    }

    private static func normalizedImageData(from data: Data) -> Data? {
        guard let image = UIImage(data: data) else { return nil }
        return image.jpegData(compressionQuality: 0.82)
    }
}

private enum PhotoLibraryImageSaver {
    static func save(_ image: UIImage) {
        PHPhotoLibrary.shared().performChanges {
            PHAssetChangeRequest.creationRequestForAsset(from: image)
        } completionHandler: { _, _ in
        }
    }
}
