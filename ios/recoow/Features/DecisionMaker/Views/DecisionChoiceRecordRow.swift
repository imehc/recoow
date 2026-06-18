import SwiftUI

struct DecisionChoiceRecordRow: View {
    let record: DecisionChoiceRecord
    var showsCollectionTitle = true
    var thumbnailSize: CGFloat = 64
    var choiceRecordImageTransition: Namespace.ID? = nil

    var body: some View {
        HStack(spacing: 12) {
            thumbnail

            VStack(alignment: .leading, spacing: 6) {
                Text(record.optionTitle)
                    .font(.headline)
                    .lineLimit(2)

                if showsCollectionTitle {
                    Text(record.collectionTitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                Text(AppFormatters.dateTime(milliseconds: record.selectedAt))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Spacer(minLength: 8)
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private var thumbnail: some View {
        if let imageData = record.optionImageData {
            if let choiceRecordImageTransition {
                PhotoThumbnailView(imageData: imageData, systemImage: "sparkles", size: thumbnailSize)
                    .matchedTransitionSource(id: record.id, in: choiceRecordImageTransition)
            } else {
                PhotoThumbnailView(imageData: imageData, systemImage: "sparkles", size: thumbnailSize)
            }
        }
    }
}
