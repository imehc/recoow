import SwiftUI

struct ActiveFeatureBanner: View {
    let route: ToolRoute
    let statusTitle: LocalizedStringKey
    let statusSystemImage: String
    let statusTint: Color
    let elapsedSeconds: Int64
    let pointCount: Int

    var body: some View {
        HStack(spacing: HomeStyle.rowSpacing) {
            FeatureIconView(route: route, size: HomeStyle.iconSize)

            VStack(alignment: .leading, spacing: 8) {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) {
                        title
                        statusLabel
                    }
                    .fixedSize(horizontal: true, vertical: false)

                    VStack(alignment: .leading, spacing: 6) {
                        title
                        statusLabel
                    }
                }

                Text("\(AppFormatters.duration(elapsedSeconds)) · \(AppFormatters.sampleCount(pointCount))")
                    .font(AppDesign.rowSubtitleFont)
                    .monospacedDigit()
                    .foregroundStyle(AppPalette.secondaryInk)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .layoutPriority(1)

            Spacer(minLength: 8)

            Image(systemName: "chevron.right")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.tertiary)
                .accessibilityHidden(true)
        }
        .padding(HomeStyle.rowPadding)
        .homeCard()
        .accessibilityElement(children: .combine)
    }

    private var title: some View {
        Text(route.titleKey)
            .font(AppDesign.rowTitleFont)
            .foregroundStyle(AppPalette.ink)
            .fixedSize(horizontal: false, vertical: true)
    }

    private var statusLabel: some View {
        HomeStatusLabel(title: Text(statusTitle), systemImage: statusSystemImage, tint: statusTint)
    }
}
