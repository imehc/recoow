import SwiftUI

struct FeatureEntryTile: View {
    let module: ToolModule
    let isActive: Bool
    var status: ToolHomeStatus?

    var body: some View {
        HStack(spacing: HomeStyle.rowSpacing) {
            FeatureIconView(route: module.route, size: HomeStyle.iconSize)

            VStack(alignment: .leading, spacing: 4) {
                Text(module.titleKey)
                    .font(AppDesign.rowTitleFont)
                    .foregroundStyle(AppPalette.ink)
                    .fixedSize(horizontal: false, vertical: true)

                detail
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Image(systemName: "chevron.right")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.tertiary)
                .accessibilityHidden(true)
        }
        .padding(.horizontal, HomeStyle.rowPadding)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(.rect)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var detail: some View {
        if let status {
            HomeStatusLabel(title: Text(status.title), systemImage: status.systemImage, tint: status.tint)
        } else if isActive {
            HomeStatusLabel(
                title: Text("记录中"),
                systemImage: "dot.radiowaves.left.and.right",
                tint: AppPalette.green
            )
        } else {
            Text(module.subtitleKey)
                .font(AppDesign.rowSubtitleFont)
                .foregroundStyle(AppPalette.secondaryInk)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
