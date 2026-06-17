import SwiftUI
import UIKit

struct PhotoThumbnailView: View {
    let imageData: Data?
    let systemImage: String
    var size: CGFloat = 64

    var body: some View {
        Group {
            if let imageData, let image = UIImage(data: imageData) {
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
        .clipShape(.rect(cornerRadius: AppDesign.cornerRadius))
        .accessibilityHidden(true)
    }
}
