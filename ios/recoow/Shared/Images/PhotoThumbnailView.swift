import SwiftUI
import UIKit

struct PhotoThumbnailView: View {
    let imageData: Data?
    let systemImage: String
    var size: CGFloat = AppDesign.listIconSize

    var body: some View {
        Group {
            if let imageData, let image = ThumbnailImageLoader.image(data: imageData, preferredPixelSize: preferredPixelSize) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                Image(systemName: systemImage)
                    .font(.system(size: size * 0.46, weight: .medium))
                    .foregroundStyle(AppPalette.neutral)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(AppPalette.background)
            }
        }
        .frame(width: size, height: size)
        .clipShape(.rect(cornerRadius: AppDesign.iconCornerRadius))
        .accessibilityHidden(true)
    }

    private var preferredPixelSize: Int {
        let scale = UIScreen.main.scale
        return max(1, min(Int((size * scale * 2).rounded()), 1024))
    }
}
