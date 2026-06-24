import SwiftUI

struct FoodBillSelectionView: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var billsViewModel: BillsViewModel
    @Binding var selectedBillID: String?
    @State private var searchText = ""

    var body: some View {
        List {
            if let errorMessage = billsViewModel.errorMessage {
                Section {
                    Label(errorMessage, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                }
            }

            if filteredBills.isEmpty {
                ContentUnavailableView(
                    emptyTitle,
                    systemImage: "receipt",
                    description: Text(AppLocalization.string("可关联的账单会显示在这里"))
                )
            } else {
                Section(AppLocalization.string("账单")) {
                    if selectedBillID != nil {
                        Button(AppLocalization.string("移除账单"), systemImage: "minus.circle", role: .destructive) {
                            selectedBillID = nil
                            dismiss()
                        }
                    }

                    ForEach(filteredBills) { bill in
                        Button {
                            selectedBillID = bill.id
                            dismiss()
                        } label: {
                            FoodSelectedBillRow(
                                bill: bill,
                                isSelected: selectedBillID == bill.id
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(AppLocalization.string("选择账单"))
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $searchText, prompt: Text(AppLocalization.string("搜索账单")))
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(AppLocalization.string("完成"), action: done)
            }
        }
        .task {
            billsViewModel.startObserving()
        }
    }

    private var filteredBills: [BillRecord] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard query.isEmpty == false else { return billsViewModel.bills }

        return billsViewModel.bills.filter { bill in
            [
                bill.title,
                bill.displayAmount,
                bill.billType.localizedTitle,
                bill.billType == .expense ? bill.billCategory.localizedTitle : bill.billIncomeCategory.localizedTitle,
                bill.billPaymentMethod.localizedTitle,
                bill.note,
                AppFormatters.date(milliseconds: bill.occurredAt)
            ]
            .compactMap(\.self)
            .joined(separator: " ")
            .localizedCaseInsensitiveContains(query)
        }
    }

    private var emptyTitle: String {
        searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? AppLocalization.string("暂无可关联账单")
            : AppLocalization.string("没有匹配账单")
    }

    private func done() {
        dismiss()
    }
}

struct FoodSelectedBillRow: View {
    let bill: BillRecord
    var isSelected = false

    var body: some View {
        HStack(spacing: 12) {
            if bill.imageData != nil {
                PhotoThumbnailView(
                    imageData: bill.imageData,
                    systemImage: "receipt.fill",
                    size: AppDesign.compactIconSize
                )
            } else {
                BillIconView(bill: bill, size: AppDesign.compactIconSize)
            }

            VStack(alignment: .leading, spacing: 5) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(bill.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(2)

                    Spacer(minLength: 8)

                    Text(bill.displayAmount)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(bill.billType.amountTint)
                }

                BillMetadataLineView(bill: bill)
                    .font(.caption)

                Text(AppFormatters.dateTime(milliseconds: bill.occurredAt))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            if isSelected {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(.blue)
                    .imageScale(.large)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}
