import PhotosUI
import UIKit
import UniformTypeIdentifiers

final class PhotoSourcePickerCoordinator: NSObject, UINavigationControllerDelegate, UIImagePickerControllerDelegate, PHPickerViewControllerDelegate {
    private let onSelect: (Data) -> Void
    private let onCancel: () -> Void

    init(onSelect: @escaping (Data) -> Void, onCancel: @escaping () -> Void) {
        self.onSelect = onSelect
        self.onCancel = onCancel
    }

    func imagePickerController(
        _ picker: UIImagePickerController,
        didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
    ) {
        if let image = info[.originalImage] as? UIImage,
           let data = image.jpegData(compressionQuality: 0.82) {
            onSelect(data)
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

                self.onSelect(normalizedData)
            }
        }
    }

    private static func normalizedImageData(from data: Data) -> Data? {
        guard let image = UIImage(data: data) else { return nil }
        return image.jpegData(compressionQuality: 0.82)
    }
}
