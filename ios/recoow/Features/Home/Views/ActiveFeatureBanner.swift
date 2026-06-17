import SwiftUI

struct ActiveFeatureBanner: View {
    let route: ToolRoute
    let elapsedSeconds: Int64
    let pointCount: Int

    var body: some View {
        HStack(spacing: 12) {
            FeatureIconView(route: route)

            VStack(alignment: .leading, spacing: 4) {
                Label("\(route.title)进行中", systemImage: "dot.radiowaves.left.and.right")
                    .font(.headline)
                    .foregroundStyle(.green)

                Text("\(AppFormatters.duration(elapsedSeconds)) · \(pointCount) 个采样点")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }

            Spacer(minLength: 8)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}
