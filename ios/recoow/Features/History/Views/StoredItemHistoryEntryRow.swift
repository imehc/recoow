import SwiftUI

struct StoredItemHistoryEntryRow: View {
    @Environment(\.locale) private var locale

    let item: StoredItem
    let categoryName: String
    let itemImageTransition: Namespace.ID

    var body: some View {
        HStack(spacing: 12) {
            if item.imageData != nil {
                PhotoThumbnailView(imageData: item.imageData, systemImage: "shippingbox")
                    .matchedTransitionSource(id: item.id, in: itemImageTransition)
            }

            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(item.title)
                        .font(.headline)
                        .lineLimit(2)

                    Spacer(minLength: 8)

                    HistoryTypeTag(route: .itemLocator)
                }

                Text(item.location)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)

                MetadataLineView {
                    MetadataItemView(title: categoryName, systemImage: "folder")
                }

                Text(AppFormatters.dateTime(milliseconds: item.updatedAt, locale: locale))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}
