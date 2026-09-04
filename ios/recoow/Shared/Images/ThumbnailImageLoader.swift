import ImageIO
import UIKit

/// Decodes JPEG/PNG/HEIC image data at most once per unique payload and caches
/// the result, downsampled so small list thumbnails never decode full-resolution
/// bitmaps. The cache is keyed by content + target pixel size, so callers do not
/// need a stable identifier and no stale entry survives an actual data change.
enum ThumbnailImageLoader {
    private static let cache: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 300
        cache.totalCostLimit = 64 * 1024 * 1024
        return cache
    }()

    static func image(data: Data?, preferredPixelSize: Int) -> UIImage? {
        guard let data, data.isEmpty == false else { return nil }
        let pixelSize = max(1, preferredPixelSize)
        let key = "\(pixelSize)|\(data.count)|\(data.hashValue)" as NSString

        if let cached = cache.object(forKey: key) {
            return cached
        }

        guard let decoded = decode(data: data, maxPixelSize: pixelSize) else {
            return nil
        }

        if let cgImage = decoded.cgImage {
            cache.setObject(decoded, forKey: key, cost: cgImage.width * cgImage.height * 4)
        } else {
            cache.setObject(decoded, forKey: key)
        }

        return decoded
    }

    private static func decode(data: Data, maxPixelSize: Int) -> UIImage? {
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

        if let image = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbnailOptions) {
            return UIImage(cgImage: image)
        }

        return UIImage(data: data)
    }
}
