import SwiftUI
import UIKit

struct PhotoSourcePickerController: UIViewControllerRepresentable {
    let mode: PhotoSourcePickerMode
    let onSelect: (Data) -> Void
    let onCancel: () -> Void

    func makeCoordinator() -> PhotoSourcePickerCoordinator {
        PhotoSourcePickerCoordinator(
            onSelect: onSelect,
            onCancel: onCancel
        )
    }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let controller = UIImagePickerController()
        controller.sourceType = resolvedSourceType
        controller.delegate = context.coordinator
        controller.allowsEditing = false
        return controller
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {
    }

    private var resolvedSourceType: UIImagePickerController.SourceType {
        UIImagePickerController.isSourceTypeAvailable(mode.sourceType) ? mode.sourceType : .photoLibrary
    }
}
