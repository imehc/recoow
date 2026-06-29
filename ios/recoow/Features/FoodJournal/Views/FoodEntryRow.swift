import SwiftUI

struct FoodEntryRow: View {
    @Environment(\.locale) private var locale

    let entry: FoodEntry
    var attachments: [MediaAttachment] = []
    var linkedBills: [BillRecord] = []
    var iconSize: CGFloat = AppDesign.listIconSize

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            if let coverPhoto {
                PhotoThumbnailView(
                    imageData: coverPhoto.resolvedData,
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

                    if entry.hasLinkedBills {
                        MetadataItemView(title: linkedBillMetadataTitle, systemImage: "receipt")
                    }
                }

                if linkedBills.isEmpty == false {
                    FoodLinkedBillsInlineView(bills: linkedBills, totalCount: entry.linkedBillCount)
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

    private var linkedBillMetadataTitle: String {
        entry.linkedBillCount == 1
            ? AppLocalization.string("已关联账单")
            : AppLocalization.format("%d 个账单", entry.linkedBillCount)
    }
}

private struct FoodLinkedBillsInlineView: View {
    let bills: [BillRecord]
    let totalCount: Int

    var body: some View {
        if let firstBill = bills.first {
            HStack(spacing: 6) {
                Image(systemName: "receipt")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.teal)
                    .accessibilityHidden(true)

                Text(firstBill.title)
                    .lineLimit(1)

                Text(firstBill.displayAmount)
                    .strikethrough(firstBill.isVoided)
                    .foregroundStyle(firstBill.billType.amountTint)
                    .lineLimit(1)

                let remainingCount = max(0, totalCount - 1)
                if remainingCount > 0 {
                    Text("+\(remainingCount)")
                        .lineLimit(1)
                }
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
        }
    }
}
