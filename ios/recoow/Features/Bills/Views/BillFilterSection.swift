import SwiftUI

struct BillFilterSection: View {
    @Bindable var viewModel: BillsViewModel

    var body: some View {
        Section("筛选") {
            Picker("分类", selection: $viewModel.selectedCategory) {
                Text("全部分类").tag(nil as BillCategory?)

                ForEach(BillCategory.allCases) { category in
                    Label(category.titleKey, systemImage: category.systemImage)
                        .tag(Optional(category))
                }
            }

            Picker("支付方式", selection: $viewModel.selectedPaymentMethod) {
                Text("全部方式").tag(nil as BillPaymentMethod?)

                ForEach(BillPaymentMethod.allCases) { method in
                    Label(method.titleKey, systemImage: method.systemImage)
                        .tag(Optional(method))
                }
            }
        }
    }
}
