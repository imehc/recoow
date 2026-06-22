import SwiftUI
import UIKit

struct PhotoPreviewView: View {
    let item: PhotoPreviewItem

    var body: some View {
        AdaptivePhotoPreviewView(
            title: AppLocalization.string("图片预览"),
            imageData: item.imageData
        )
    }
}

struct AdaptivePhotoPreviewView: View {
    @Environment(\.dismiss) private var dismiss

    let title: String
    let imageData: Data

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Text(title)
                    .font(.headline)
                    .lineLimit(1)

                Spacer(minLength: 8)

                Button(action: close) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title3)
                        .symbolRenderingMode(.hierarchical)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
                .frame(width: AppDesign.touchIconSize, height: AppDesign.touchIconSize)
                .accessibilityLabel(AppLocalization.string("完成"))
            }
            .padding(.leading, 16)
            .padding(.trailing, 8)
            .padding(.top, 8)
            .padding(.bottom, 4)

            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: .infinity, maxHeight: imageMaxHeight)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 16)
            } else {
                ContentUnavailableView(AppLocalization.string("无法预览图片"), systemImage: "photo")
                    .frame(maxWidth: .infinity, minHeight: imageMaxHeight)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 16)
            }
        }
        .background(Color(.systemBackground))
        .presentationDetents([.height(preferredSheetHeight)])
        .presentationDragIndicator(.visible)
    }

    private var image: UIImage? {
        UIImage(data: imageData)
    }

    private var preferredSheetHeight: CGFloat {
        let screenSize = UIScreen.main.bounds.size
        let sheetWidth = min(screenSize.width, 600) - 32
        let headerHeight: CGFloat = 64
        let bottomPadding: CGFloat = 16
        let maxHeight = max(320, screenSize.height * 0.82)

        guard let image else {
            return min(360, maxHeight)
        }

        let aspectRatio = max(0.1, image.size.width / max(image.size.height, 1))
        let imageHeight = sheetWidth / aspectRatio
        let fittingHeight = imageHeight + headerHeight + bottomPadding
        return min(max(240, fittingHeight), maxHeight)
    }

    private var imageMaxHeight: CGFloat {
        max(140, preferredSheetHeight - 80)
    }

    private func close() {
        dismiss()
    }
}
