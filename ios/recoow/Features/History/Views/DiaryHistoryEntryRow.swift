import SwiftUI

struct DiaryHistoryEntryRow: View {
    @Environment(\.locale) private var locale

    let entry: DiaryEntry

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            DiaryMoodIconView(mood: entry.diaryMood, size: AppDesign.historyIconSize)

            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(entry.title)
                        .font(.headline)
                        .lineLimit(2)

                    Spacer(minLength: 8)

                    HistoryTypeTag(route: .diary)
                }

                Text(entry.previewText)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)

                Text(AppFormatters.dateTime(milliseconds: entry.occurredAt, locale: locale))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}
