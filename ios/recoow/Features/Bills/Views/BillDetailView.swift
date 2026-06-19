import SwiftUI

struct BillDetailView: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var viewModel: BillsViewModel
    @State private var billForEditing: BillRecord?
    @State private var billPendingDeletion: BillRecord?

    let billID: String
    let billImageTransition: Namespace.ID?

    var body: some View {
        Group {
            if let bill = viewModel.bill(id: billID) {
                content(for: bill)
            } else {
                ContentUnavailableView("账单不存在", systemImage: "receipt")
            }
        }
        .navigationTitle("账单详情")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $billForEditing) { bill in
            NavigationStack {
                BillFormView(bill: bill, viewModel: viewModel)
            }
        }
        .task(id: billID) {
            await viewModel.loadBillIfNeeded(id: billID)
        }
    }

    @ViewBuilder
    private func content(for bill: BillRecord) -> some View {
        if bill.imageData != nil, let billImageTransition {
            form(for: bill)
                .navigationTransition(.zoom(sourceID: billID, in: billImageTransition))
        } else {
            form(for: bill)
        }
    }

    private func form(for bill: BillRecord) -> some View {
        List {
            if bill.imageData != nil {
                Section("图片") {
                    PhotoSquareImageView(imageData: bill.imageData, systemImage: "receipt.fill")
                }
            }

            Section("金额") {
                if bill.billType == .expense {
                    LabeledContent("原价", value: AppFormatters.money(cents: bill.originalAmountCents))
                    LabeledContent("优惠", value: AppFormatters.money(cents: bill.discountAmountCents))
                    LabeledContent("实付", value: AppFormatters.money(cents: bill.finalAmountCents))
                } else {
                    LabeledContent("金额", value: AppFormatters.money(cents: bill.finalAmountCents))
                }
            }

            Section("账单") {
                LabeledContent("标题", value: bill.title)
                LabeledContent("类型", value: bill.billType.localizedTitle)
                LabeledContent("日期", value: AppFormatters.dateTime(milliseconds: bill.occurredAt))

                if bill.billType == .expense {
                    LabeledContent("分类", value: bill.billCategory.localizedTitle)
                    LabeledContent("支付方式", value: bill.billPaymentMethod.localizedTitle)
                } else {
                    LabeledContent("收入类型", value: bill.billIncomeCategory.localizedTitle)
                    LabeledContent("收入渠道", value: bill.billPaymentMethod.localizedTitle)
                }

                if let note = bill.note {
                    LabeledContent("备注", value: note)
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("编辑", systemImage: "pencil") {
                    billForEditing = bill
                }
            }

            ToolbarItem(placement: .bottomBar) {
                Button("删除", systemImage: "trash", role: .destructive) {
                    billPendingDeletion = bill
                }
            }
        }
        .alert(item: $billPendingDeletion) { bill in
            Alert(
                title: Text(AppLocalization.format("删除“%@”？", bill.title)),
                message: Text(AppLocalization.string("删除后该记录会从历史中移除。")),
                primaryButton: .destructive(Text("删除")) {
                    deleteBill(id: bill.id)
                },
                secondaryButton: .cancel(Text("取消")) {
                    billPendingDeletion = nil
                }
            )
        }
    }

    private func deleteBill(id: String) {
        billPendingDeletion = nil

        Task {
            await viewModel.deleteBill(id: id)
            dismiss()
        }
    }
}
