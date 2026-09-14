import SwiftUI

struct DecisionResultView: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let option: DecisionOption

    var body: some View {
        Group {
            if dynamicTypeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: AppDesign.gridSpacing) {
                    thumbnail
                    details
                }
            } else {
                HStack(alignment: .top, spacing: AppDesign.gridSpacing) {
                    thumbnail
                    details
                }
            }
        }
        .padding(.vertical, 6)
    }

    private var thumbnail: some View {
        PhotoThumbnailView(imageData: option.resolvedImageData, systemImage: "sparkles", size: AppDesign.largeThumbnailSize)
    }

    private var details: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(option.title)
                .font(.title3)
                .bold()

            if let detail = option.detail, detail.isEmpty == false {
                Text(detail)
                    .font(.body)
                    .foregroundStyle(.secondary)
            }

            if let customInfo = option.customInfo, customInfo.isEmpty == false {
                Text(customInfo)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
    }
}
