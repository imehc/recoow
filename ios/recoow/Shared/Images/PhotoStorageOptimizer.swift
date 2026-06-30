import ImageIO
import UIKit

enum PhotoStorageOptimizer {
    private static let maxPixelSize: CGFloat = 2_048
    private static let jpegCompressionQuality: CGFloat = 0.78

    static func normalizedJPEGData(from data: Data) -> Data? {
        guard let image = downsampledImage(from: data, maxPixelSize: maxPixelSize) ?? UIImage(data: data) else {
            return nil
        }

        return normalizedJPEGData(from: image)
    }

    static func normalizedJPEGData(from image: UIImage) -> Data? {
        let resizedImage = resizedImageIfNeeded(image.normalizedForEditing, maxPixelSize: maxPixelSize)
        return resizedImage.jpegData(compressionQuality: jpegCompressionQuality)
    }

    private static func downsampledImage(from data: Data, maxPixelSize: CGFloat) -> UIImage? {
        let sourceOptions = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, sourceOptions) else {
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

    private static func resizedImageIfNeeded(_ image: UIImage, maxPixelSize: CGFloat) -> UIImage {
        let pixelWidth = image.cgImage?.width ?? Int(image.size.width * image.scale)
        let pixelHeight = image.cgImage?.height ?? Int(image.size.height * image.scale)
        let largestSide = max(pixelWidth, pixelHeight)

        guard largestSide > Int(maxPixelSize), pixelWidth > 0, pixelHeight > 0 else {
            return image
        }

        let scale = maxPixelSize / CGFloat(largestSide)
        let outputSize = CGSize(
            width: CGFloat(pixelWidth) * scale,
            height: CGFloat(pixelHeight) * scale
        )
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true

        return UIGraphicsImageRenderer(size: outputSize, format: format).image { context in
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: outputSize))
            image.draw(in: CGRect(origin: .zero, size: outputSize))
        }
    }
}
