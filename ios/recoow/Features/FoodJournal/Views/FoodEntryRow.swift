import SwiftUI

struct FoodEntryRow: View {
    @Environment(\.locale) private var locale

    let entry: FoodEntry
    var attachments: [MediaAttachment] = []
    var linkedBill: BillRecord?
    var iconSize: CGFloat = AppDesign.listIconSize

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            if let coverPhoto {
                PhotoThumbnailView(
                    imageData: coverPhoto.data,
                    systemImage: "photo.fill",
                    size: iconSize
                )
            } else {
                FoodMealKindIconView(kind: entry.foodMealKind, size: iconSize)
            }

            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(entry.title)
                        .font(.headline)
                        .lineLimit(2)

                    Spacer(minLength: 8)

                    Text(AppFormatters.time(milliseconds: entry.occurredAt, locale: locale))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                MetadataLineView {
                    MetadataItemView(title: entry.foodMealKind.localizedTitle, systemImage: entry.foodMealKind.systemImage)

                    if let portion = entry.normalizedPortion {
                        MetadataItemView(title: portion, systemImage: "scalemass")
                    }

                    if photoCount > 0 {
                        MetadataItemView(title: AppLocalization.format("%d 张照片", photoCount), systemImage: "photo.on.rectangle")
                    }

                    if entry.billID != nil {
                        MetadataItemView(title: AppLocalization.string("已关联账单"), systemImage: "receipt")
                    }
                }

                if let linkedBill {
                    FoodLinkedBillInlineView(bill: linkedBill)
                }

                if let note = entry.normalizedNote {
                    Text(note)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }

    private var coverPhoto: MediaAttachment? {
        attachments.first { $0.kind == .photo }
    }

    private var photoCount: Int {
        attachments.filter { $0.kind == .photo }.count
    }
}

private struct FoodLinkedBillInlineView: View {
    let bill: BillRecord

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "receipt")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.teal)
                .accessibilityHidden(true)

            Text(bill.title)
                .lineLimit(1)

            Text(bill.displayAmount)
                .strikethrough(bill.isVoided)
                .foregroundStyle(bill.billType.amountTint)
                .lineLimit(1)
        }
        .font(.footnote)
        .foregroundStyle(.secondary)
    }
}
