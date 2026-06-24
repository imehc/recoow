import SwiftUI

struct FoodJournalSummarySection: View {
    let hasEntries: Bool
    let dayCount: Int
    let todayEntryCount: Int
    let todayMealKindCount: Int
    let latestEntryDate: Date?
    let currentWeekSnackCount: Int
    let currentWeekDrinkCount: Int
    let currentWeekLateNightCount: Int
    let currentMonthMilkTeaCount: Int

    var body: some View {
        Section(AppLocalization.string("概览")) {
            HStack(spacing: 12) {
                CompactSummaryMetricView(
                    title: AppLocalization.string("记录天数"),
                    value: hasEntries ? AppLocalization.format("%d 天", dayCount) : "--",
                    systemImage: "calendar",
                    tint: .brown
                )

                CompactSummaryMetricView(
                    title: AppLocalization.string("今日饮食"),
                    value: AppLocalization.format("%d 条", todayEntryCount),
                    systemImage: "fork.knife",
                    tint: .brown
                )
            }

            HStack(spacing: 12) {
                CompactSummaryMetricView(
                    title: AppLocalization.string("今日餐别"),
                    value: AppLocalization.format("%d 类", todayMealKindCount),
                    systemImage: "square.grid.2x2",
                    tint: .indigo
                )

                CompactSummaryMetricView(
                    title: AppLocalization.string("最近记录"),
                    value: latestEntryDate.map { AppFormatters.date(milliseconds: Int64($0.timeIntervalSince1970 * 1000)) } ?? "--",
                    systemImage: "clock",
                    tint: .cyan
                )
            }

            if hasEntries {
                FoodJournalQuickStatsRow(
                    currentWeekSnackCount: currentWeekSnackCount,
                    currentWeekDrinkCount: currentWeekDrinkCount,
                    currentWeekLateNightCount: currentWeekLateNightCount,
                    currentMonthMilkTeaCount: currentMonthMilkTeaCount
                )
            }
        }
    }
}

private struct FoodJournalQuickStatsRow: View {
    let currentWeekSnackCount: Int
    let currentWeekDrinkCount: Int
    let currentWeekLateNightCount: Int
    let currentMonthMilkTeaCount: Int

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                FoodJournalStatPill(
                    title: AppLocalization.string("本周零食"),
                    value: AppLocalization.format("%d 次", currentWeekSnackCount),
                    systemImage: FoodMealKind.snack.systemImage,
                    tint: .pink
                )

                FoodJournalStatPill(
                    title: AppLocalization.string("本周饮品"),
                    value: AppLocalization.format("%d 次", currentWeekDrinkCount),
                    systemImage: FoodMealKind.drink.systemImage,
                    tint: .cyan
                )

                FoodJournalStatPill(
                    title: AppLocalization.string("本周夜宵"),
                    value: AppLocalization.format("%d 次", currentWeekLateNightCount),
                    systemImage: FoodMealKind.lateNightSnack.systemImage,
                    tint: .purple
                )

                FoodJournalStatPill(
                    title: AppLocalization.string("本月奶茶"),
                    value: AppLocalization.format("%d 次", currentMonthMilkTeaCount),
                    systemImage: "cup.and.saucer.fill",
                    tint: .brown
                )
            }
            .padding(.vertical, 2)
        }
    }
}

private struct FoodJournalStatPill: View {
    let title: String
    let value: String
    let systemImage: String
    let tint: Color

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: systemImage)
                .font(.caption.weight(.semibold))
                .foregroundStyle(tint)
                .accessibilityHidden(true)

            Text(title)
                .lineLimit(1)

            Text(value)
                .fontWeight(.semibold)
                .lineLimit(1)
        }
        .font(.caption)
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(tint.opacity(0.1), in: .rect(cornerRadius: AppDesign.cornerRadius))
        .overlay {
            RoundedRectangle(cornerRadius: AppDesign.cornerRadius)
                .stroke(tint.opacity(0.18), lineWidth: 1)
        }
    }
}
