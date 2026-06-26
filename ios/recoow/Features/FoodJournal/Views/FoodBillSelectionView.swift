import SwiftUI

struct FoodBillSelectionView: View {
    static let minimumPresentationHeight: CGFloat = 320

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
        .presentationDetents([.height(preferredPresentationHeight)])
        .presentationDragIndicator(.visible)
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

    private var preferredPresentationHeight: CGFloat {
        let rowCount = max(1, min(filteredBills.count, 6))
        let baseHeight: CGFloat = 180
        let fittingHeight = baseHeight + CGFloat(rowCount) * 58
        let maxHeight = max(Self.minimumPresentationHeight, UIScreen.main.bounds.height * 0.82)
        return min(max(Self.minimumPresentationHeight, fittingHeight), maxHeight)
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
                AppIconTileView(
                    systemImage: "receipt",
                    tint: .teal,
                    size: AppDesign.compactIconSize,
                    backgroundOpacity: 0.12
                )
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(bill.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(2)

                Text(bill.displayAmount)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: 8)

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
