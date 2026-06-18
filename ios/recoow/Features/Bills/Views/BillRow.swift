import SwiftUI

struct BillRow: View {
    let bill: BillRecord
    let billImageTransition: Namespace.ID

    var body: some View {
        HStack(spacing: 12) {
            if bill.imageData != nil {
                PhotoThumbnailView(imageData: bill.imageData, systemImage: "receipt.fill", size: 56)
                    .matchedTransitionSource(id: bill.id, in: billImageTransition)
            } else {
                BillCategoryIconView(category: bill.billCategory, size: 56)
            }

            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(bill.title)
                        .font(.headline)
                        .lineLimit(2)

                    Spacer(minLength: 8)

                    Text(AppFormatters.money(cents: bill.finalAmountCents))
                        .font(.headline)
                        .foregroundStyle(.primary)
                }

                HStack(spacing: 8) {
                    Label(bill.billCategory.titleKey, systemImage: bill.billCategory.systemImage)

                    Text(bill.billPaymentMethod.titleKey)
                }
                .font(.footnote)
                .foregroundStyle(.secondary)

                Text(AppFormatters.dateTime(milliseconds: bill.occurredAt))
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                if bill.hasDiscount {
                    Text(AppLocalization.format("bill.discount.amount", AppFormatters.money(cents: bill.discountAmountCents)))
                        .font(.footnote)
                        .foregroundStyle(.green)
                }
            }
        }
        .padding(.vertical, 4)
    }
}
