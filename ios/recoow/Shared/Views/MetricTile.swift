import SwiftUI

struct MetricTile: View {
    let title: String
    let value: String
    let systemImage: String
    let tint: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            MetadataItemView(title: title, systemImage: systemImage)
                .font(AppDesign.metadataFont)
                .foregroundStyle(.secondary)

            Text(value)
                .font(AppDesign.rowTitleFont)
                .monospacedDigit()
                .lineLimit(2)
                .minimumScaleFactor(0.75)
                .foregroundStyle(.primary)
        }
        .frame(maxWidth: .infinity, minHeight: 74, alignment: .leading)
        .padding(12)
        .background(AppPalette.background, in: .rect(cornerRadius: AppDesign.iconCornerRadius))
    }
}

struct CompactSummaryMetricView: View {
    let title: String
    let value: String
    let systemImage: String
    let tint: Color

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            AppIconTileView(systemImage: systemImage, tint: tint, size: AppDesign.compactMetricIconSize)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(AppDesign.summaryTitleFont)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)

                Text(value)
                    .font(AppDesign.summaryValueFont)
                    .monospacedDigit()
                    .foregroundStyle(AppPalette.ink)
                    .lineLimit(2)
                    .minimumScaleFactor(0.85)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 50, alignment: .leading)
        .padding(.horizontal, 9)
        .padding(.vertical, 7)
        .background(AppPalette.background, in: .rect(cornerRadius: AppDesign.iconCornerRadius))
        .accessibilityElement(children: .combine)
    }
}
