import SwiftUI

struct StatisticsMetricTile: View {
    let title: String
    let value: String
    let systemImage: String
    let tint: Color

    var body: some View {
        HStack(alignment: .center, spacing: 9) {
            Image(systemName: systemImage)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: AppDesign.metricIconSize, height: AppDesign.metricIconSize)
                .background(tint.opacity(0.13), in: .rect(cornerRadius: AppDesign.iconCornerRadius))

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)

                Text(value)
                    .font(.headline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.62)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 58, alignment: .leading)
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(.background, in: .rect(cornerRadius: AppDesign.cornerRadius))
        .overlay {
            RoundedRectangle(cornerRadius: AppDesign.cornerRadius)
                .stroke(tint.opacity(0.16), lineWidth: 1)
        }
    }
}
