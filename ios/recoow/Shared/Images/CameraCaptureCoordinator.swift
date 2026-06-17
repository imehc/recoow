import UIKit

final class CameraCaptureCoordinator: NSObject, UINavigationControllerDelegate, UIImagePickerControllerDelegate {
    private let onCapture: (Data) -> Void
    private let onDismiss: () -> Void

    init(onCapture: @escaping (Data) -> Void, onDismiss: @escaping () -> Void) {
        self.onCapture = onCapture
        self.onDismiss = onDismiss
    }

    func imagePickerController(
        _ picker: UIImagePickerController,
        didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
    ) {
        if let image = info[.originalImage] as? UIImage,
           let data = image.jpegData(compressionQuality: 0.82) {
            onCapture(data)
        }

        onDismiss()
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        onDismiss()
    }
}
