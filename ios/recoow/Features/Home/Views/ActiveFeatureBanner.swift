import SwiftUI

struct ActiveFeatureBanner: View {
    let route: ToolRoute
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

            MetadataItemView(titleKey: "记录中", systemImage: "dot.radiowaves.left.and.right")
                .font(.footnote)
                .foregroundStyle(.green)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}
