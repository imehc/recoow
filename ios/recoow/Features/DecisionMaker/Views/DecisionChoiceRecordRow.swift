import SwiftUI

struct DecisionChoiceRecordRow: View {
    let record: DecisionChoiceRecord
    var showsCollectionTitle = true

    var body: some View {
        HStack(spacing: 12) {
            if let imageData = record.optionImageData {
                PhotoThumbnailView(imageData: imageData, systemImage: "sparkles", size: AppDesign.rowIconSize)
            }

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
}
