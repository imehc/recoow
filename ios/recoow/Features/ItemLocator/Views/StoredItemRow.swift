import SwiftUI

struct StoredItemRow: View {
    let item: StoredItem
    let categoryName: String
    let itemImageTransition: Namespace.ID

    var body: some View {
        HStack(spacing: 12) {
            if item.hasImage {
                PhotoThumbnailView(imageData: item.resolvedImageData, systemImage: "shippingbox")
                    .matchedTransitionSource(id: item.id, in: itemImageTransition)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(item.title)
                    .font(.headline)
                    .lineLimit(2)

                Text(item.location)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)

                MetadataLineView {
                    MetadataItemView(title: categoryName, systemImage: "folder")
                }
            }
        }
        .padding(.vertical, 4)
    }
}
