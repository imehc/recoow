import SwiftUI

struct BillFilterSheetView: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var viewModel: BillsViewModel

    var body: some View {
        Form {
            Section("筛选") {
                if viewModel.selectedBillType == .income {
                    Picker("收入类型", selection: $viewModel.selectedIncomeCategory) {
                        Text("全部收入类型").tag(nil as BillIncomeCategory?)

                        ForEach(BillIncomeCategory.allCases) { category in
                            Label(category.titleKey, systemImage: category.systemImage)
                                .tag(Optional(category))
                        }
                    }
                } else {
                    Picker("分类", selection: $viewModel.selectedCategory) {
                        Text("全部分类").tag(nil as BillCategory?)

                        ForEach(BillCategory.allCases) { category in
                            Label(category.titleKey, systemImage: category.systemImage)
                                .tag(Optional(category))
                        }
                    }
                }

                Picker(viewModel.selectedBillType == .income ? "收入渠道" : "支付方式", selection: $viewModel.selectedPaymentMethod) {
                    Text(viewModel.selectedBillType == .income ? "全部渠道" : "全部方式")
                        .tag(nil as BillPaymentMethod?)

                    ForEach(BillPaymentMethod.allCases) { method in
                        Label(method.titleKey, systemImage: method.systemImage)
                            .tag(Optional(method))
                    }
                }
            }
        }
        .navigationTitle("筛选")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("清除筛选", systemImage: "xmark.circle", action: clearFilters)
                    .disabled(hasActiveFilters == false)
            }

            ToolbarItem(placement: .confirmationAction) {
                Button("完成", action: dismiss.callAsFunction)
            }
        }
    }

    private var hasActiveFilters: Bool {
        viewModel.selectedCategory != nil
        || viewModel.selectedIncomeCategory != nil
        || viewModel.selectedPaymentMethod != nil
    }

    private func clearFilters() {
        viewModel.selectedCategory = nil
        viewModel.selectedIncomeCategory = nil
        viewModel.selectedPaymentMethod = nil
    }
}
