import Charts
import SwiftUI

struct StatisticsBillSection: View {
    @Binding var selectedPeriod: StatisticsBillPeriod

    let hasBills: Bool
    let periodBillCount: Int
    let expenseTotalCents: Int64
    let incomeTotalCents: Int64
    let discountCents: Int64
    let points: [StatisticsBillChartPoint]
    let categoryPoints: [StatisticsBillCategoryPoint]
    let incomeCategoryPoints: [StatisticsBillIncomeCategoryPoint]
    let viewBills: () -> Void

    var body: some View {
        Section("账单统计") {
            Picker("周期", selection: $selectedPeriod) {
                ForEach(StatisticsBillPeriod.allCases) { period in
                    Text(AppLocalization.string(period.title)).tag(period)
                }
            }
            .pickerStyle(.segmented)

            if hasBills == false {
                ContentUnavailableView("暂无账单统计", systemImage: "receipt")
            } else {
                LazyVGrid(columns: columns, spacing: 12) {
                    StatisticsMetricTile(
                        title: AppLocalization.string("支出"),
                        value: moneyOrPlaceholder(expenseTotalCents),
                        systemImage: "arrow.up.right.circle.fill",
                        tint: .red
                    )

                    StatisticsMetricTile(
                        title: AppLocalization.string("收入"),
                        value: moneyOrPlaceholder(incomeTotalCents),
                        systemImage: "arrow.down.left.circle.fill",
                        tint: .green
                    )

                    Button(action: viewBills) {
                        StatisticsMetricTile(
                            title: AppLocalization.string("账单数量"),
                            value: AppLocalization.format("%d 条", periodBillCount),
                            systemImage: "number",
                            tint: .blue
                        )
                    }
                    .buttonStyle(.plain)
                    .disabled(periodBillCount == 0)
                    .accessibilityHint(AppLocalization.string("查看这些账单"))

                    StatisticsMetricTile(
                        title: AppLocalization.string("优惠"),
                        value: moneyOrPlaceholder(discountCents),
                        systemImage: "tag.fill",
                        tint: .orange
                    )
                }
                .padding(.vertical, 2)

                VStack(alignment: .leading, spacing: 8) {
                    Text(AppLocalization.string("收支趋势"))
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.secondary)

                    Chart(points) { point in
                        BarMark(
                            x: .value(AppLocalization.string("日期"), point.label),
                            y: .value(AppLocalization.string("金额"), Double(point.expenseCents) / 100)
                        )
                        .foregroundStyle(by: .value(AppLocalization.string("类型"), AppLocalization.string("支出")))
                        .position(by: .value(AppLocalization.string("类型"), AppLocalization.string("支出")))
                        .clipShape(.rect(cornerRadius: 4))

                        BarMark(
                            x: .value(AppLocalization.string("日期"), point.label),
                            y: .value(AppLocalization.string("金额"), Double(point.incomeCents) / 100)
                        )
                        .foregroundStyle(by: .value(AppLocalization.string("类型"), AppLocalization.string("收入")))
                        .position(by: .value(AppLocalization.string("类型"), AppLocalization.string("收入")))
                        .clipShape(.rect(cornerRadius: 4))
                    }
                    .frame(height: 132)
                    .chartForegroundStyleScale([
                        AppLocalization.string("支出"): Color.red,
                        AppLocalization.string("收入"): Color.green
                    ])
                    .chartYAxis {
                        AxisMarks(position: .leading)
                    }
                    .accessibilityLabel(AppLocalization.string("账单统计"))
                }
                .padding(.vertical, 2)

                if hasExpenseShare || hasIncomeShare {
                    LazyVGrid(columns: shareColumns, spacing: 12) {
                        if hasExpenseShare {
                            StatisticsBillExpenseShareChart(points: categoryPoints)
                        }

                        if hasIncomeShare {
                            StatisticsBillIncomeShareChart(points: incomeCategoryPoints)
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
        }
    }

    private var columns: [GridItem] {
        [
            GridItem(.flexible(), spacing: 12),
            GridItem(.flexible(), spacing: 12)
        ]
    }

    private var shareColumns: [GridItem] {
        let count = [hasExpenseShare, hasIncomeShare].filter { $0 }.count
        return Array(
            repeating: GridItem(.flexible(), spacing: 12),
            count: max(1, count)
        )
    }

    private var hasExpenseShare: Bool {
        categoryPoints.isEmpty == false && expenseTotalCents > 0
    }

    private var hasIncomeShare: Bool {
        incomeCategoryPoints.isEmpty == false && incomeTotalCents > 0
    }

    private func moneyOrPlaceholder(_ cents: Int64) -> String {
        periodBillCount == 0 ? "--" : AppFormatters.money(cents: cents)
    }
}
