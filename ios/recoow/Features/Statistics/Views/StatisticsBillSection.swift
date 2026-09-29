import Charts
import SwiftUI

struct StatisticsBillSection: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Binding var selectedPeriod: StatisticsBillPeriod

    let hasBills: Bool
    let periodBillCount: Int
    let expenseTotalCents: Int64
    let incomeTotalCents: Int64
    let discountCents: Int64
    let storedValueUseCents: Int64
    let points: [StatisticsBillChartPoint]
    let categoryPoints: [StatisticsBillCategoryPoint]
    let incomeCategoryPoints: [StatisticsBillIncomeCategoryPoint]
    let viewBills: (StatisticsBillMetric) -> Void

    var body: some View {
        Section {
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
                    ForEach(StatisticsBillMetric.allCases) { metric in
                        Button {
                            viewBills(metric)
                        } label: {
                            StatisticsMetricTile(
                                title: metric.localizedTitle,
                                value: value(for: metric),
                                systemImage: metric.systemImage,
                                tint: metric.tint
                            )
                        }
                        .buttonStyle(.plain)
                        .disabled(isEmpty(metric))
                        .accessibilityHint(AppLocalization.string("查看这些账单"))
                    }
                }
                .padding(.vertical, 2)

                VStack(alignment: .leading, spacing: 8) {
                    Text(AppLocalization.string("收支趋势"))
                        .font(AppDesign.metadataFont.weight(.medium))
                        .foregroundStyle(AppPalette.secondaryInk)

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
                        AppLocalization.string("支出"): AppPalette.red,
                        AppLocalization.string("收入"): AppPalette.green
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
        } header: {
            Text("账单统计")
                .appSectionHeaderStyle()
        }
    }

    private var columns: [GridItem] {
        AppDesign.metricColumns(for: dynamicTypeSize)
    }

    private var shareColumns: [GridItem] {
        let count = [hasExpenseShare, hasIncomeShare].filter { $0 }.count
        return Array(
            repeating: GridItem(.flexible(), spacing: 12),
            count: dynamicTypeSize.isAccessibilitySize ? 1 : max(1, count)
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

    private func cents(for metric: StatisticsBillMetric) -> Int64 {
        switch metric {
        case .expense:
            expenseTotalCents
        case .income:
            incomeTotalCents
        case .count:
            0
        case .discount:
            discountCents
        case .storedValueUse:
            storedValueUseCents
        }
    }

    private func value(for metric: StatisticsBillMetric) -> String {
        switch metric {
        case .count:
            AppLocalization.format("%d 条", periodBillCount)
        default:
            moneyOrPlaceholder(cents(for: metric))
        }
    }

    private func isEmpty(_ metric: StatisticsBillMetric) -> Bool {
        metric == .count ? periodBillCount == 0 : cents(for: metric) == 0
    }
}
