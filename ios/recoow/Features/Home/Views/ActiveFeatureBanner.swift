import SwiftUI

struct ActiveFeatureBanner: View {
    let route: ToolRoute
    let statusTitle: LocalizedStringKey
    let statusSystemImage: String
    let statusTint: Color
    let elapsedSeconds: Int64
    let pointCount: Int

    var body: some View {
        HStack(spacing: 12) {
            FeatureIconView(route: route)

            VStack(alignment: .leading, spacing: 4) {
                Text(route.titleKey)
                    .font(.headline)

                Text("\(AppFormatters.duration(elapsedSeconds)) · \(AppFormatters.sampleCount(pointCount))")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .layoutPriority(1)

            Spacer(minLength: 8)

            MetadataItemView(titleKey: statusTitle, systemImage: statusSystemImage)
                .font(.footnote)
                .foregroundStyle(statusTint)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}
