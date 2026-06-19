import SwiftUI

struct FeatureEntryTile: View {
    let route: ToolRoute
    let isActive: Bool
    var statusTitle: String?
    var statusSystemImage: String?
    var statusTint: Color = .green

    var body: some View {
        HStack(spacing: 12) {
            FeatureIconView(route: route)

            VStack(alignment: .leading, spacing: 4) {
                Text(route.titleKey)
                    .font(.headline)

                Text(route.subtitleKey)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }

            Spacer(minLength: 8)

            if let statusTitle {
                if let statusSystemImage {
                    MetadataItemView(title: statusTitle, systemImage: statusSystemImage)
                        .font(.footnote)
                        .foregroundStyle(statusTint)
                } else {
                    Text(statusTitle)
                        .font(.footnote)
                        .foregroundStyle(statusTint)
                }
            } else if isActive {
                MetadataItemView(titleKey: "进行中", systemImage: "dot.radiowaves.left.and.right")
                    .font(.footnote)
                    .foregroundStyle(statusTint)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}
