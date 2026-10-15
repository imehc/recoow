import SwiftUI
import UIKit

struct PhotoSquareImageView: View {
    let imageData: Data?
    let systemImage: String

    var body: some View {
        squareImage
    }

    private var squareImage: some View {
        Group {
            if let imageData, let image = ThumbnailImageLoader.image(data: imageData, preferredPixelSize: preferredPixelSize) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                Image(systemName: systemImage)
                    .font(.largeTitle)
                    .foregroundStyle(AppPalette.neutral)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(AppPalette.background)
            }
        }
        .frame(maxWidth: .infinity)
        .aspectRatio(1, contentMode: .fit)
        .clipShape(.rect(cornerRadius: AppDesign.cornerRadius))
        .accessibilityHidden(true)
    }

    private var preferredPixelSize: Int {
        let bounds = UIScreen.main.bounds
        return max(1, min(Int((bounds.width * UIScreen.main.scale).rounded()), 1536))
    }
}
