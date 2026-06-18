import Charts
import SwiftUI

struct StatisticsBillSection: View {
    @Binding var selectedPeriod: StatisticsBillPeriod

    let hasBills: Bool
    let periodBillCount: Int
    let totalCents: Int64
    let discountCents: Int64
    let averageCents: Int64
    let points: [StatisticsBillChartPoint]
    let categoryPoints: [StatisticsBillCategoryPoint]
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
                        title: periodTotalTitle,
                        value: moneyOrPlaceholder(totalCents),
                        systemImage: "creditcard.fill",
                        tint: .teal
                    )

                    Button(action: viewBills) {
                        StatisticsMetricTile(
                            title: AppLocalization.string("账单数量"),
                            value: AppLocalization.format("records.count", periodBillCount),
                            systemImage: "number",
                            tint: .blue
                        )
                    }
                    .buttonStyle(.plain)
                    .disabled(periodBillCount == 0)
                    .accessibilityHint(AppLocalization.string("查看这些账单"))

                    StatisticsMetricTile(
                        title: AppLocalization.string("平均每笔"),
                        value: moneyOrPlaceholder(averageCents),
                        systemImage: "divide.circle.fill",
                        tint: .orange
                    )

                    StatisticsMetricTile(
                        title: AppLocalization.string("优惠"),
                        value: moneyOrPlaceholder(discountCents),
                        systemImage: "tag.fill",
                        tint: .green
                    )
                }
                .padding(.vertical, 2)

                VStack(alignment: .leading, spacing: 8) {
                    Text(AppLocalization.string("支出趋势"))
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.secondary)

                    Chart(points) { point in
                        BarMark(
                            x: .value(AppLocalization.string("日期"), point.label),
                            y: .value(AppLocalization.string("金额"), Double(point.totalCents) / 100)
                        )
                        .foregroundStyle(.teal.gradient)
                        .clipShape(.rect(cornerRadius: 4))
                    }
                    .frame(height: 132)
                    .chartYAxis {
                        AxisMarks(position: .leading)
                    }
                    .accessibilityLabel(AppLocalization.string("账单统计"))
                }
                .padding(.vertical, 2)

                if categoryPoints.isEmpty == false && totalCents > 0 {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(AppLocalization.string("分类占比"))
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(.secondary)

                        Chart(categoryPoints) { point in
                            SectorMark(
                                angle: .value(AppLocalization.string("金额"), Double(point.totalCents) / 100),
                                innerRadius: .ratio(0.6),
                                angularInset: 1.5
                            )
                            .foregroundStyle(by: .value(AppLocalization.string("分类"), point.category.localizedTitle))
                        }
                        .frame(height: 164)
                        .chartLegend(position: .bottom, alignment: .center, spacing: 6)
                        .accessibilityLabel(AppLocalization.string("分类占比"))
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

    private var periodTotalTitle: String {
        switch selectedPeriod {
        case .week:
            AppLocalization.string("本周支出")
        case .month:
            AppLocalization.string("本月支出")
        case .year:
            AppLocalization.string("本年支出")
        }
    }

    private func moneyOrPlaceholder(_ cents: Int64) -> String {
        periodBillCount == 0 ? "--" : AppFormatters.money(cents: cents)
    }
}
