import SwiftUI

struct StatisticsFeatureUsageRow: View {
    @Environment(\.locale) private var locale

    let summary: StatisticsFeatureSummary

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: summary.route.systemImage)
                .font(.headline)
                .foregroundStyle(summary.route.tint)
                .frame(width: 34, height: 34)
                .background(summary.route.tint.opacity(0.12), in: .rect(cornerRadius: 8))

            VStack(alignment: .leading, spacing: 4) {
                Text(summary.route.titleKey)
                    .font(.headline)

                Text(latestText)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Spacer(minLength: 12)

            VStack(alignment: .trailing, spacing: 4) {
                Text(AppLocalization.format("%d 条", summary.count))
                    .font(.headline)

                Text(AppLocalization.format("今日 %d 条", summary.todayCount))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
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
