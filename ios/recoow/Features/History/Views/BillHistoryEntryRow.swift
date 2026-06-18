import SwiftUI

struct BillHistoryEntryRow: View {
    let bill: BillRecord
    let billImageTransition: Namespace.ID

    var body: some View {
        HStack(spacing: 12) {
            if bill.imageData != nil {
                PhotoThumbnailView(imageData: bill.imageData, systemImage: "receipt.fill", size: 64)
                    .matchedTransitionSource(id: bill.id, in: billImageTransition)
            } else {
                BillCategoryIconView(category: bill.billCategory, size: 64)
            }

            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(bill.title)
                        .font(.headline)
                        .lineLimit(2)

                    Spacer(minLength: 8)

                    HistoryTypeTag(route: .bills)
                }

                Text(AppFormatters.dateTime(milliseconds: bill.occurredAt))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                HStack(spacing: 8) {
                    Label(bill.billCategory.titleKey, systemImage: bill.billCategory.systemImage)
                    Text(bill.billPaymentMethod.titleKey)
                }
                .font(.footnote)
                .foregroundStyle(.secondary)

                Text(AppFormatters.money(cents: bill.finalAmountCents))
                    .font(.footnote)
                    .foregroundStyle(.primary)
            }
        }
        .padding(.vertical, 4)
    }
}
