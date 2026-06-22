import SwiftUI

struct MetricTile: View {
    let title: String
    let value: String
    let systemImage: String
    let tint: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            MetadataItemView(title: title, systemImage: systemImage)
                .font(.footnote)
                .foregroundStyle(.secondary)

            Text(value)
                .font(.headline)
                .lineLimit(2)
                .minimumScaleFactor(0.75)
                .foregroundStyle(.primary)
        }
        .frame(maxWidth: .infinity, minHeight: 74, alignment: .leading)
        .padding(12)
        .background(tint.opacity(0.1), in: .rect(cornerRadius: AppDesign.cornerRadius))
    }
}

struct CompactSummaryMetricView: View {
    let title: String
    let value: String
    let systemImage: String
    let tint: Color

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            Image(systemName: systemImage)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: AppDesign.compactMetricIconSize, height: AppDesign.compactMetricIconSize)
                .background(tint.opacity(0.13), in: .rect(cornerRadius: AppDesign.iconCornerRadius))

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.82)

                Text(value)
                    .font(.subheadline)
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.68)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 50, alignment: .leading)
        .padding(.horizontal, 9)
        .padding(.vertical, 7)
        .background(.background, in: .rect(cornerRadius: AppDesign.cornerRadius))
        .overlay {
            RoundedRectangle(cornerRadius: AppDesign.cornerRadius)
                .stroke(tint.opacity(0.16), lineWidth: 1)
        }
    }
}
