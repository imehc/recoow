import SwiftUI

struct StoredItemRow: View {
    let item: StoredItem
    let categoryName: String

    var body: some View {
        HStack(spacing: 12) {
            PhotoThumbnailView(imageData: item.imageData, systemImage: "shippingbox")

            VStack(alignment: .leading, spacing: 4) {
                Text(item.title)
                    .font(.headline)
                    .lineLimit(2)

                Text(item.location)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)

                Label(categoryName, systemImage: "folder")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}
