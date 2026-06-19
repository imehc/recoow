import SwiftUI

struct StatisticsOverviewSection: View {
    @Environment(\.locale) private var locale

    let totalRecordCount: Int
    let todayRecordCount: Int
    let activeDayCount: Int
    let latestRecordDate: Date?

    var body: some View {
        Section("概览") {
            LazyVGrid(columns: columns, spacing: 12) {
                StatisticsMetricTile(
                    title: AppLocalization.string("总记录"),
                    value: AppLocalization.format("%d 条", totalRecordCount),
                    systemImage: "tray.full.fill",
                    tint: .blue
                )

                StatisticsMetricTile(
                    title: AppLocalization.string("今日记录"),
                    value: AppLocalization.format("%d 条", todayRecordCount),
                    systemImage: "calendar",
                    tint: .green
                )

                StatisticsMetricTile(
                    title: AppLocalization.string("有记录天数"),
                    value: AppLocalization.format("%d 天", activeDayCount),
                    systemImage: "calendar.badge.clock",
                    tint: .orange
                )

                StatisticsMetricTile(
                    title: AppLocalization.string("最近记录"),
                    value: latestRecordText,
                    systemImage: "clock.fill",
                    tint: .purple
                )
            }
            .padding(.vertical, 2)
        }
    }

    private var columns: [GridItem] {
        [
            GridItem(.flexible(), spacing: 12),
            GridItem(.flexible(), spacing: 12)
        ]
    }

    private var latestRecordText: String {
        guard let latestRecordDate else { return "--" }

        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.setLocalizedDateFormatFromTemplate("MdHm")
        return formatter.string(from: latestRecordDate)
    }
}
