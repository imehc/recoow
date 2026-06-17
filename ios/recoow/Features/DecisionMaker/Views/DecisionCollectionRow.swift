import SwiftUI

struct DecisionCollectionRow: View {
    let collection: DecisionCollection

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 4) {
                Text(collection.title)
                    .font(.headline)

                if let note = collection.note, note.isEmpty == false {
                    Text(note)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
            }
        } icon: {
            Image(systemName: "shuffle")
                .foregroundStyle(.orange)
        }
        .padding(.vertical, 4)
    }
}
