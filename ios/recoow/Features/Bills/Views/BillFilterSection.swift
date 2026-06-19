import SwiftUI

struct BillFilterSection: View {
    @Bindable var viewModel: BillsViewModel

    var body: some View {
        Section {
            Picker("类型", selection: $viewModel.selectedBillType) {
                Text("全部").tag(nil as BillType?)

                ForEach(BillType.allCases) { type in
                    Text(type.titleKey)
                        .tag(Optional(type))
                }
            }
            .pickerStyle(.segmented)
        }
        .onChange(of: viewModel.selectedBillType) {
            if viewModel.selectedBillType == .income {
                viewModel.selectedCategory = nil
            } else {
                viewModel.selectedIncomeCategory = nil
            }
        }
    }
}
