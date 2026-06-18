import SwiftUI

struct StatisticsContent: View {
    @Bindable var viewModel: StatisticsViewModel
    @State private var selectedBillPeriod: StatisticsBillPeriod = .week
    @State private var billDetailContext: StatisticsBillDetailSheet.Context?

    let billsViewModel: BillsViewModel?
    let openHistory: () -> Void

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
                points: viewModel.recentUsagePoints
            )

            StatisticsBillSection(
                selectedPeriod: $selectedBillPeriod,
                hasBills: viewModel.bills.isEmpty == false,
                periodBillCount: viewModel.billCount(for: selectedBillPeriod),
                totalCents: viewModel.billTotalCents(for: selectedBillPeriod),
                discountCents: viewModel.billDiscountTotalCents(for: selectedBillPeriod),
                averageCents: viewModel.billAverageCents(for: selectedBillPeriod),
                points: viewModel.billPoints(for: selectedBillPeriod),
                categoryPoints: viewModel.billCategoryPoints(for: selectedBillPeriod),
                viewBills: viewBills
            )

            Section {
                Button(action: openHistory) {
                    Label("查看历史记录", systemImage: "clock")
                }
            }
        }
        .listStyle(.insetGrouped)
        .sheet(item: $billDetailContext) { context in
            if let billsViewModel {
                StatisticsBillDetailSheet(viewModel: billsViewModel, context: context)
            } else {
                ProgressView("正在加载")
            }
        }
    }

    private func viewBills() {
        billDetailContext = StatisticsBillDetailSheet.Context(
            titleKey: selectedBillPeriod.historyFilterTitleKey,
            bills: viewModel.bills(for: selectedBillPeriod)
        )
    }
}
