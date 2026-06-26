import SwiftUI

struct BillHistoryEntryRow: View {
    @Environment(\.locale) private var locale

    let bill: BillRecord
    let billImageTransition: Namespace.ID

    var body: some View {
        HStack(spacing: 12) {
            if bill.imageData != nil {
                PhotoThumbnailView(imageData: bill.imageData, systemImage: "receipt.fill", size: AppDesign.historyIconSize)
                    .matchedTransitionSource(id: bill.id, in: billImageTransition)
            } else {
                BillIconView(bill: bill, size: AppDesign.historyIconSize)
            }

            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(bill.title)
                        .font(.headline)
                        .lineLimit(2)

                    Spacer(minLength: 8)

                    HistoryTypeTag(route: .bills)
                }

                Text(AppFormatters.dateTime(milliseconds: bill.occurredAt, locale: locale))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                BillMetadataLineView(bill: bill)

                Text(bill.displayAmount)
                    .font(.footnote)
                    .strikethrough(bill.isVoided)
                    .foregroundStyle(bill.billType.amountTint)

            }
        }
        .padding(.vertical, 4)
    }
}
