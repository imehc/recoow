import SwiftUI

struct StatisticsBillDetailSheet: View {
    struct Context: Identifiable {
        let id = UUID()
        let titleKey: String
        let metric: StatisticsBillMetric
        /// 与统计页卡片同口径的合计；`.count` 页面展示五项汇总，不使用它。
        let metricTotalCents: Int64
        /// 周期全量账单，已按发生时间倒序。
        let periodBills: [BillRecord]

        /// 列表展示子集，概览不基于它。
        var bills: [BillRecord] {
            metric.bills(from: periodBills)
        }
    }

    @Environment(\.dismiss) private var dismiss
    @Bindable var viewModel: BillsViewModel
    @Namespace private var billImageTransition
    @State private var sortOption: StatisticsBillSortOption = .date

    let context: Context

    var body: some View {
        NavigationStack {
            List {
                Section("概览") {
                    if context.metric == .count {
                        LabeledContent("账单数量", value: AppLocalization.format("%d 条", context.periodBills.count))
                        LabeledContent("支出", value: AppFormatters.money(cents: expenseTotalCents))
                        LabeledContent("收入", value: AppFormatters.money(cents: incomeTotalCents))
                        LabeledContent("优惠", value: AppFormatters.money(cents: discountCents))
                        LabeledContent("储值消费", value: AppFormatters.money(cents: storedValueUseCents))
                    } else {
                        LabeledContent(
                            context.metric.localizedTitle,
                            value: AppFormatters.money(cents: context.metricTotalCents)
                        )
                    }
                }

                if displayedBills.isEmpty {
                    ContentUnavailableView(
                        context.periodBills.isEmpty
                            ? AppLocalization.string("暂无账单统计")
                            : AppLocalization.string("暂无匹配记录"),
                        systemImage: "receipt"
                    )
                } else {
                    Section {
                        ForEach(displayedBills) { bill in
                            NavigationLink(value: BillRoute(id: bill.id)) {
                                BillRow(bill: bill, billImageTransition: billImageTransition)
                            }
                        }
                    } header: {
                        HStack(spacing: 8) {
                            Text(context.metric.localizedListSectionTitle)

                            Spacer(minLength: 12)

                            Menu {
                                Picker("排序", selection: $sortOption) {
                                    ForEach(StatisticsBillSortOption.allCases) { option in
                                        Label(option.titleKey, systemImage: option.systemImage).tag(option)
                                    }
                                }
                            } label: {
                                MetadataItemView(
                                    title: sortOption.localizedTitle,
                                    systemImage: sortOption.systemImage
                                )
                                    .font(AppDesign.metadataFont)
                                    .foregroundStyle(
                                        sortOption == .date
                                            ? AppPalette.secondaryInk
                                            : AppPalette.accent
                                    )
                                    .padding(.vertical, 8)
                                    .contentShape(.rect)
                            }
                            .accessibilityLabel(
                                AppLocalization.string("排序") + " " + sortOption.localizedTitle
                            )
                        }
                        .textCase(nil)
                    }
                }
            }
            .appGroupedListStyle()
            .navigationTitle(AppLocalization.string(context.titleKey))
            .appNavigationStyle()
            .navigationDestination(for: BillRoute.self) { route in
                BillDetailView(
                    viewModel: viewModel,
                    billID: route.id,
                    billImageTransition: billImageTransition
                )
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成", action: dismiss.callAsFunction)
                }
            }
            .onAppear {
                if viewModel.bills.isEmpty {
                    viewModel.bills = context.periodBills
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private var displayedBills: [BillRecord] {
        sortOption.applying(to: context.bills, amount: context.metric.amountCents(of:))
    }

    // 五项概览仅在「账单数量」详情页展示，且始终基于周期全量，不随指标筛选变化。
    private var expenseTotalCents: Int64 {
        context.periodBills
            .filter { $0.billType == .expense }
            .reduce(0) { $0 + $1.countedAmountCents }
    }

    private var incomeTotalCents: Int64 {
        context.periodBills
            .filter { $0.billType == .income }
            .reduce(0) { $0 + $1.countedAmountCents }
    }

    private var discountCents: Int64 {
        context.periodBills
            .filter { $0.billType == .expense }
            .reduce(0) { $0 + $1.countedDiscountCents }
    }

    private var storedValueUseCents: Int64 {
        context.periodBills
            .filter { $0.billType == .storedValueUse }
            .reduce(0) { $0 + $1.countedAmountCents }
    }
}
