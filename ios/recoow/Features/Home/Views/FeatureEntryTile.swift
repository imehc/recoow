import SwiftUI

struct FeatureEntryTile: View {
    let route: ToolRoute
    let isActive: Bool

    var body: some View {
        HStack(spacing: 12) {
            FeatureIconView(route: route)

            VStack(alignment: .leading, spacing: 4) {
                Text(route.title)
                    .font(.headline)

                Text(route.subtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }

            Spacer(minLength: 8)

            if isActive {
                Label("进行中", systemImage: "dot.radiowaves.left.and.right")
                    .font(.footnote)
                    .foregroundStyle(.green)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}
