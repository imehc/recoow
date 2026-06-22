import SwiftUI

struct FeatureEntryTile: View {
    let module: ToolModule
    let isActive: Bool
    var status: ToolHomeStatus?

    var body: some View {
        HStack(spacing: 12) {
            FeatureIconView(route: module.route)

            VStack(alignment: .leading, spacing: 4) {
                Text(module.titleKey)
                    .font(.headline)

                Text(module.subtitleKey)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }

            Spacer(minLength: 8)

            if let status {
                if let systemImage = status.systemImage {
                    MetadataItemView(title: status.title, systemImage: systemImage)
                        .font(.footnote)
                        .foregroundStyle(status.tint)
                } else {
                    Text(status.title)
                        .font(.footnote)
                        .foregroundStyle(status.tint)
                }
            } else if isActive {
                MetadataItemView(titleKey: "记录中", systemImage: "dot.radiowaves.left.and.right")
                    .font(.footnote)
                    .foregroundStyle(.green)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}
