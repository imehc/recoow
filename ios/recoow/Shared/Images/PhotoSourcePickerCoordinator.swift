import UIKit

final class PhotoSourcePickerCoordinator: NSObject, UINavigationControllerDelegate, UIImagePickerControllerDelegate {
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
}
