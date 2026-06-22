import SwiftUI

struct BillRow: View {
    let bill: BillRecord
    let billImageTransition: Namespace.ID

    var body: some View {
        HStack(spacing: 12) {
            if bill.imageData != nil {
                PhotoThumbnailView(imageData: bill.imageData, systemImage: "receipt.fill", size: AppDesign.listIconSize)
                    .matchedTransitionSource(id: bill.id, in: billImageTransition)
            } else {
                BillIconView(bill: bill, size: AppDesign.listIconSize)
            }

            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(bill.title)
                        .font(.headline)
                        .lineLimit(2)

                    Spacer(minLength: 8)

                    Text(bill.displayAmount)
                        .font(.headline)
                        .foregroundStyle(bill.billType.amountTint)
                }

                BillMetadataLineView(bill: bill)

                Text(AppFormatters.dateTime(milliseconds: bill.occurredAt))
                    .font(.footnote)
                    .foregroundStyle(.secondary)

            }
        }
        .padding(.vertical, 4)
    }
}
