import SwiftUI

struct DecisionChoiceHistoryEntryRow: View {
    let record: DecisionChoiceRecord
    let choiceRecordImageTransition: Namespace.ID

    var body: some View {
        HStack(spacing: 12) {
            if let imageData = record.optionImageData {
                PhotoThumbnailView(imageData: imageData, systemImage: "sparkles", size: 64)
                    .matchedTransitionSource(id: record.id, in: choiceRecordImageTransition)
            }

            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(record.optionTitle)
                        .font(.headline)
                        .lineLimit(2)

                    Spacer(minLength: 8)

                    HistoryTypeTag(route: .decisionMaker)
                }

                Text(record.collectionTitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)

                Text(AppFormatters.dateTime(milliseconds: record.selectedAt))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}
