import SwiftUI

struct BillDetailView: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var viewModel: BillsViewModel
    @State private var billForEditing: BillRecord?
    @State private var billPendingDeletion: BillRecord?
    @State private var billPendingRefund: BillRecord?

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
        .sheet(item: $billPendingRefund) { bill in
            RefundReasonSheet(bill: bill, viewModel: viewModel)
        }
        .task(id: billID) {
            await viewModel.loadBillIfNeeded(id: billID)
        }
    }

    @ViewBuilder
    private func content(for bill: BillRecord) -> some View {
        if bill.hasImage, let billImageTransition {
            form(for: bill)
                .navigationTransition(.zoom(sourceID: billID, in: billImageTransition))
        } else {
            form(for: bill)
        }
    }

    private func form(for bill: BillRecord) -> some View {
        List {
            if bill.hasImage {
                Section("图片") {
                    PhotoSquareImageView(imageData: bill.resolvedImageData, systemImage: "receipt.fill")
                }
            }

            Section("金额") {
                if bill.billType == .expense {
                    LabeledContent("原价", value: AppFormatters.money(cents: bill.originalAmountCents))
                    LabeledContent("优惠", value: AppFormatters.money(cents: bill.discountAmountCents))
                    LabeledContent("实付") {
                        amountText(cents: bill.finalAmountCents, voided: bill.isVoided)
                    }
                } else {
                    LabeledContent("金额") {
                        amountText(cents: bill.finalAmountCents, voided: bill.isVoided)
                    }
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

                if bill.billType == .expense, bill.billCategory == .transport {
                    if let transportLines = bill.normalizedTransportLines {
                        LabeledContent("线路") {
                            Text(transportLines)
                                .multilineTextAlignment(.trailing)
                        }
                    }

                    if let startLocation = bill.normalizedStartLocation {
                        LabeledContent("起点", value: startLocation)
                    }

                    if let endLocation = bill.normalizedEndLocation {
                        LabeledContent("终点", value: endLocation)
                    }
                }

                if bill.isGroupBuy {
                    if let validUntil = bill.groupBuyValidUntil {
                        LabeledContent("有效期", value: AppFormatters.dateTime(milliseconds: validUntil))
                    }

                    LabeledContent("核销状态") {
                        if bill.billSettlementStatus == .redeemed {
                            BillStatusBadge(title: "已核销", systemImage: "checkmark.seal.fill", tint: .green)
                        } else {
                            BillStatusBadge(title: "未核销", systemImage: "checkmark.seal", tint: .secondary)
                        }
                    }

                    if let redeemedAt = bill.redeemedAt {
                        LabeledContent("核销时间", value: AppFormatters.dateTime(milliseconds: redeemedAt))
                    }
                }

                if bill.lifecycleState == .refunded {
                    LabeledContent("退款状态") {
                        BillStatusBadge(title: "已退款", systemImage: "arrow.uturn.backward.circle.fill", tint: .red)
                    }

                    if let reason = bill.refundReason, reason.isEmpty == false {
                        LabeledContent("退款原因", value: reason)
                    }
                }

                if let note = bill.note {
                    LabeledContent("备注", value: note)
                }
            }
        }
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button("删除", systemImage: "trash", role: .destructive) {
                    billPendingDeletion = bill
                }
                .tint(.red)

                Button("编辑", systemImage: "square.and.pencil") {
                    billForEditing = bill
                }
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if hasSettlementAction(for: bill) {
                settlementBottomActions(for: bill)
            }
        }
        .alert(
            billPendingDeletion.map { AppLocalization.format("删除“%@”？", $0.title) } ?? "",
            isPresented: .isPresent($billPendingDeletion),
            presenting: billPendingDeletion
        ) { bill in
            Button("删除", role: .destructive) {
                deleteBill(id: bill.id)
            }
            Button("取消", role: .cancel) {
                billPendingDeletion = nil
            }
        } message: { _ in
            Text(AppLocalization.string("删除后该记录会从历史中移除。"))
        }
    }

    // MARK: - Settlement bottom actions (check-in task style)

    private func hasSettlementAction(for bill: BillRecord) -> Bool {
        bill.lifecycleState == .normal
    }

    @ViewBuilder
    private func settlementBottomActions(for bill: BillRecord) -> some View {
        if bill.isGroupBuy {
            bottomActionContainer {
                Button("退款", systemImage: "arrow.uturn.backward") {
                    billPendingRefund = bill
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
                .tint(.orange)

                Button("核销", systemImage: "checkmark.circle.fill") {
                    redeem(bill)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .tint(.green)
            }
        } else {
            bottomActionContainer {
                Button("退款", systemImage: "arrow.uturn.backward") {
                    billPendingRefund = bill
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
                .tint(.orange)
            }
        }
    }

    private func bottomActionContainer<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        HStack(spacing: 12) {
            content()
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .padding(.horizontal)
        .padding(.vertical, 10)
        .background(.bar)
    }

    // MARK: - Helpers

    private func amountText(cents: Int64, voided: Bool) -> some View {
        Text(AppFormatters.money(cents: cents))
            .strikethrough(voided)
    }

    private func redeem(_ bill: BillRecord) {
        Task {
            await viewModel.redeem(bill)
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

// MARK: - Refund Reason Sheet

struct RefundReasonSheet: View {
    @Environment(\.dismiss) private var dismiss
    let bill: BillRecord
    let viewModel: BillsViewModel
    @State private var reason = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("退款原因") {
                    TextField("请输入退款原因（可选）", text: $reason, axis: .vertical)
                        .lineLimit(3...)
                }
            }
            .navigationTitle("退款")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") {
                        dismiss()
                    }
                }

                ToolbarItem(placement: .confirmationAction) {
                    Button("确定", role: .destructive) {
                        let trimmed = reason.trimmingCharacters(in: .whitespacesAndNewlines)
                        Task {
                            await viewModel.refund(bill, reason: trimmed.isEmpty ? nil : trimmed)
                            dismiss()
                        }
                    }
                }
            }
        }
        .presentationDetents([.height(200)])
        .presentationDragIndicator(.visible)
    }
}

// MARK: - Status Badge

private struct BillStatusBadge: View {
    let title: LocalizedStringKey
    let systemImage: String
    let tint: Color

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: systemImage)
                .imageScale(.small)
            Text(title)
        }
        .foregroundStyle(tint)
    }
}
