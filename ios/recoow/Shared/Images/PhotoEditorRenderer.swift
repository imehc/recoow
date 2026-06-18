import UIKit

enum PhotoEditorRenderer {
    static func render(
        image: UIImage,
        cropLength: CGFloat,
        scale: CGFloat,
        offset: CGSize,
        rotationDegrees: Double
    ) -> UIImage {
        let normalizedImage = image.normalizedForEditing
        let sourceSize = normalizedImage.size
        let baseScale = max(cropLength / sourceSize.width, cropLength / sourceSize.height)
        let outputSide = min(1_600, max(800, min(sourceSize.width, sourceSize.height)))
        let outputSize = CGSize(width: outputSide, height: outputSide)
        let pointScale = outputSide / cropLength

        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true

        let renderer = UIGraphicsImageRenderer(size: outputSize, format: format)
        return renderer.image { context in
            UIColor.systemBackground.setFill()
            context.fill(CGRect(origin: .zero, size: outputSize))

            let graphicsContext = context.cgContext
            graphicsContext.translateBy(
                x: outputSide / 2 + offset.width * pointScale,
                y: outputSide / 2 + offset.height * pointScale
            )
            graphicsContext.rotate(by: rotationDegrees * .pi / 180)
            graphicsContext.scaleBy(
                x: baseScale * scale * pointScale,
                y: baseScale * scale * pointScale
            )

            normalizedImage.draw(
                in: CGRect(
                    x: -sourceSize.width / 2,
                    y: -sourceSize.height / 2,
                    width: sourceSize.width,
                    height: sourceSize.height
                )
            )
        }
    }
}
