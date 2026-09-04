import SwiftUI
import UIKit

struct PhotoSquareImageView: View {
    let imageData: Data?
    let systemImage: String

    var body: some View {
        Group {
            if let imageData, let image = ThumbnailImageLoader.image(data: imageData, preferredPixelSize: preferredPixelSize) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                Image(systemName: systemImage)
                    .font(.largeTitle)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(.secondary.opacity(0.10))
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
