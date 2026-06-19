import SwiftUI

struct StatisticsBillDetailSheet: View {
    struct Context: Identifiable {
        let id = UUID()
        let titleKey: String
        let bills: [BillRecord]
    }

    @Environment(\.dismiss) private var dismiss
    @Bindable var viewModel: BillsViewModel
    @Namespace private var billImageTransition

    let context: Context

    var body: some View {
        NavigationStack {
            List {
                Section("概览") {
                    LabeledContent("账单数量", value: AppLocalization.format("%d 条", context.bills.count))
                    LabeledContent("支出", value: AppFormatters.money(cents: expenseTotalCents))
                    LabeledContent("收入", value: AppFormatters.money(cents: incomeTotalCents))
                    LabeledContent("优惠", value: AppFormatters.money(cents: discountCents))
                }

                if context.bills.isEmpty {
                    ContentUnavailableView("暂无账单统计", systemImage: "receipt")
                } else {
                    Section("账单") {
                        ForEach(context.bills) { bill in
                            NavigationLink(value: BillRoute(id: bill.id)) {
                                BillRow(bill: bill, billImageTransition: billImageTransition)
                            }
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(AppLocalization.string(context.titleKey))
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
                    viewModel.bills = context.bills
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private var expenseTotalCents: Int64 {
        context.bills
            .filter { $0.billType == .expense }
            .reduce(0) { $0 + $1.finalAmountCents }
    }

    private var incomeTotalCents: Int64 {
        context.bills
            .filter { $0.billType == .income }
            .reduce(0) { $0 + $1.finalAmountCents }
    }

    private var discountCents: Int64 {
        context.bills
            .filter { $0.billType == .expense }
            .reduce(0) { $0 + $1.discountAmountCents }
    }
}
