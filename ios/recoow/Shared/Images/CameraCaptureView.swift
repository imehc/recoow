import SwiftUI
import UIKit

struct CameraCaptureView: UIViewControllerRepresentable {
    @Environment(\.dismiss) private var dismiss

    let onCapture: (Data) -> Void

    func makeCoordinator() -> CameraCaptureCoordinator {
        CameraCaptureCoordinator(
            onCapture: onCapture,
            onDismiss: {
                dismiss()
            }
        )
    }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let controller = UIImagePickerController()
        controller.sourceType = .camera
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {
    }
}
