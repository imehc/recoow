import ImageIO
import UIKit

enum PhotoImagePreparer {
    static func editablePhoto(from data: Data) async -> EditablePhoto? {
        let image = await Task.detached(priority: .userInitiated) {
            downsampledImage(from: data, maxPixelSize: 2_400)
        }.value

        return await MainActor.run {
            guard let image = image ?? UIImage(data: data)?.normalizedForEditing else {
                return nil
            }

            return EditablePhoto(image: image)
        }
    }

    private nonisolated static func downsampledImage(from data: Data, maxPixelSize: CGFloat) -> UIImage? {
        let options = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, options) else {
            return nil
        }

        let thumbnailOptions = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize
        ] as CFDictionary

        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbnailOptions) else {
            return nil
        }

        return UIImage(cgImage: image)
    }
}
