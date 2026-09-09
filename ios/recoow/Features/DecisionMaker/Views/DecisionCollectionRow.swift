import SwiftUI

struct DecisionCollectionRow: View {
    let collection: DecisionCollection

    var body: some View {
        HStack(spacing: 12) {
            AppIconTileView(systemImage: "shuffle", tint: AppPalette.amber, size: AppDesign.listIconSize)

            VStack(alignment: .leading, spacing: 4) {
                Text(collection.title)
                    .font(AppDesign.rowTitleFont)

                if let note = collection.note, note.isEmpty == false {
                    Text(note)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
            }
        }
        .padding(.vertical, 4)
    }
}
