import SwiftUI

struct DecisionOptionRow: View {
    let option: DecisionOption

    var body: some View {
        HStack(spacing: 12) {
            PhotoThumbnailView(imageData: option.imageData, systemImage: "questionmark.circle")

            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline) {
                    Text(option.title)
                        .font(.headline)

                    if option.isEnabled == false {
                        Text("停用")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }

                if let detail = option.detail, detail.isEmpty == false {
                    Text(detail)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }

                MetadataItemView(
                    title: AppLocalization.format("option.weight.value", option.weight),
                    systemImage: "dial.low"
                )
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}
