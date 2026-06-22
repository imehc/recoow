import SwiftUI

struct DecisionResultView: View {
    let option: DecisionOption

    var body: some View {
        HStack(spacing: 14) {
            PhotoThumbnailView(imageData: option.imageData, systemImage: "sparkles", size: AppDesign.largeThumbnailSize)

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
        .padding(.vertical, 6)
    }
}
