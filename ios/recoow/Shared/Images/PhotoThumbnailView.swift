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
                    .font(.title2)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(.secondary.opacity(0.10))
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
