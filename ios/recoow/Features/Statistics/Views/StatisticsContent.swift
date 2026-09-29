import SwiftUI

struct StatisticsContent: View {
    @Environment(\.locale) private var locale
    @Bindable var viewModel: StatisticsViewModel
    @State private var selectedBillPeriod: StatisticsBillPeriod = .week
    @State private var billDetailContext: StatisticsBillDetailSheet.Context?

    let billsViewModel: BillsViewModel?
    @Binding private var tabBarVisibility: Visibility

    init(
        viewModel: StatisticsViewModel,
        billsViewModel: BillsViewModel?,
        tabBarVisibility: Binding<Visibility> = .constant(.visible)
    ) {
        self.viewModel = viewModel
        self.billsViewModel = billsViewModel
        _tabBarVisibility = tabBarVisibility
    }

    var body: some View {
        List {
            ForEach(viewModel.errorMessages, id: \.self) { errorMessage in
                Section {
                    Label(errorMessage, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                }
            }

            StatisticsOverviewSection(
                totalRecordCount: viewModel.totalRecordCount,
                todayRecordCount: viewModel.todayRecordCount,
                activeDayCount: viewModel.activeDayCount,
                latestRecordDate: viewModel.latestRecordDate
            )

            StatisticsFeatureUsageSection(summaries: viewModel.featureSummaries)

            StatisticsRecentUsageChartSection(
                totalRecordCount: viewModel.totalRecordCount,
                points: viewModel.recentUsagePoints(locale: locale)
            )

            if viewModel.continuousCheckInProgresses.isEmpty == false {
                StatisticsCheckInProgressSection(progresses: viewModel.continuousCheckInProgresses)
            }

            StatisticsBillSection(
                selectedPeriod: $selectedBillPeriod,
                hasBills: viewModel.billCount(for: selectedBillPeriod) > 0,
                periodBillCount: viewModel.billCount(for: selectedBillPeriod),
                expenseTotalCents: viewModel.billExpenseTotalCents(for: selectedBillPeriod),
                incomeTotalCents: viewModel.billIncomeTotalCents(for: selectedBillPeriod),
                discountCents: viewModel.billDiscountTotalCents(for: selectedBillPeriod),
                storedValueUseCents: viewModel.billStoredValueUseTotalCents(for: selectedBillPeriod),
                points: viewModel.billPoints(for: selectedBillPeriod, locale: locale),
                categoryPoints: viewModel.billCategoryPoints(for: selectedBillPeriod),
                incomeCategoryPoints: viewModel.billIncomeCategoryPoints(for: selectedBillPeriod),
                viewBills: viewBills
            )
        }
        .id(locale.identifier)
        .appGroupedListStyle(topContentMargin: AppDesign.tabContentTopMargin)
        .reportsTabBarVisibilityWhenScrolling($tabBarVisibility)
        .appSheet(item: $billDetailContext) { context in
            if let billsViewModel {
                StatisticsBillDetailSheet(viewModel: billsViewModel, context: context)
            } else {
                ProgressView("正在加载")
            }
        }
    }

    private func viewBills(_ metric: StatisticsBillMetric) {
        billDetailContext = StatisticsBillDetailSheet.Context(
            titleKey: selectedBillPeriod.historyFilterTitleKey,
            metric: metric,
            metricTotalCents: totalCents(for: metric),
            periodBills: viewModel.bills(for: selectedBillPeriod)
        )
    }

    // 与卡片数值同源，保证「卡片显示多少，详情页概览就是多少」。
    private func totalCents(for metric: StatisticsBillMetric) -> Int64 {
        switch metric {
        case .expense:
            viewModel.billExpenseTotalCents(for: selectedBillPeriod)
        case .income:
            viewModel.billIncomeTotalCents(for: selectedBillPeriod)
        case .count:
            0
        case .discount:
            viewModel.billDiscountTotalCents(for: selectedBillPeriod)
        case .storedValueUse:
            viewModel.billStoredValueUseTotalCents(for: selectedBillPeriod)
        }
    }
}
