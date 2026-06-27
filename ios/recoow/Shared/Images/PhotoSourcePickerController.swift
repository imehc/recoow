import PhotosUI
import SwiftUI
import UIKit

struct PhotoSourcePickerController: UIViewControllerRepresentable {
    let mode: PhotoSourcePickerMode
    let savesCameraPhotosToLibrary: Bool
    let onSelect: (PhotoSourcePickerMode, Data) -> Void
    let onCancel: () -> Void

    typealias UIViewControllerType = UIViewController

    func makeCoordinator() -> PhotoSourcePickerCoordinator {
        PhotoSourcePickerCoordinator(
            savesCameraPhotosToLibrary: savesCameraPhotosToLibrary,
            onSelect: onSelect,
            onCancel: onCancel
        )
    }

    func makeUIViewController(context: Context) -> UIViewController {
        switch mode {
        case .library:
            makePhotoLibraryPicker(context: context)
        case .camera:
            if UIImagePickerController.isSourceTypeAvailable(.camera) {
                makeImagePicker(sourceType: .camera, context: context)
            } else {
                makePhotoLibraryPicker(context: context)
            }
        case .assetLibrary:
            makePhotoLibraryPicker(context: context)
        }
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
    }

    private func makePhotoLibraryPicker(context: Context) -> PHPickerViewController {
        var configuration = PHPickerConfiguration(photoLibrary: .shared())
        configuration.filter = .images
        configuration.selectionLimit = 1
        configuration.preferredAssetRepresentationMode = .compatible

        let controller = PHPickerViewController(configuration: configuration)
        controller.delegate = context.coordinator
        return controller
    }

    private func makeImagePicker(
        sourceType: UIImagePickerController.SourceType,
        context: Context
    ) -> UIImagePickerController {
        let controller = UIImagePickerController()
        controller.sourceType = sourceType
        controller.delegate = context.coordinator
        controller.allowsEditing = false
        return controller
    }
}
