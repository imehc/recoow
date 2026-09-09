import SwiftUI

struct StatisticsFeatureUsageRow: View {
    @Environment(\.locale) private var locale

    let summary: StatisticsFeatureSummary

    var body: some View {
        HStack(spacing: 12) {
            AppIconTileView(
                systemImage: summary.route.systemImage,
                tint: summary.route.tint,
                size: AppDesign.compactIconSize
            )

            VStack(alignment: .leading, spacing: 4) {
                Text(summary.route.titleKey)
                    .font(AppDesign.rowTitleFont)
                    .foregroundStyle(AppPalette.ink)

                Text(latestText)
                    .font(AppDesign.metadataFont)
                    .foregroundStyle(AppPalette.secondaryInk)
            }

            Spacer(minLength: 12)

            VStack(alignment: .trailing, spacing: 4) {
                Text(AppLocalization.format("%d 条", summary.count))
                    .font(AppDesign.rowTitleFont)
                    .monospacedDigit()
                    .foregroundStyle(AppPalette.ink)

                Text(AppLocalization.format("今日 %d 条", summary.todayCount))
                    .font(AppDesign.metadataFont)
                    .foregroundStyle(AppPalette.secondaryInk)
            }
        }
        .padding(.vertical, 4)
    }

    private var latestText: String {
        guard let latestDate = summary.latestDate else {
            return AppLocalization.string("暂无记录")
        }

        let formattedDate = latestDate.formatted(
            Date.FormatStyle(date: .abbreviated, time: .shortened)
                .locale(locale)
        )
        return AppLocalization.format("最近 %@", formattedDate)
    }
}
