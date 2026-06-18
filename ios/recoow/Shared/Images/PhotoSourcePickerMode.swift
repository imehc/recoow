import UIKit

enum PhotoSourcePickerMode: Hashable {
    case library
    case camera

    var sourceType: UIImagePickerController.SourceType {
        switch self {
        case .library:
            .photoLibrary
        case .camera:
            .camera
        }
    }
}
