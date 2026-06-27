import SwiftUI

struct ReminderHistoryEntryRow: View {
    @Environment(\.locale) private var locale

    let record: ReminderHistoryRecord
    let reminderImageTransition: Namespace.ID

    private var reminder: ReminderRecord {
        record.reminder
    }

    var body: some View {
        HStack(spacing: 12) {
            if reminder.hasImage {
                PhotoThumbnailView(imageData: reminder.resolvedImageData, systemImage: "bell.fill", size: AppDesign.historyIconSize)
                    .matchedTransitionSource(id: reminder.id, in: reminderImageTransition)
            } else {
                ReminderIconView(memoryIcon: reminder.memoryIcon, size: AppDesign.historyIconSize)
            }

            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(reminder.title)
                        .font(.headline)
                        .lineLimit(2)

                    Spacer(minLength: 8)

                    HistoryTypeTag(route: .reminders)
                }

                Text(timeText)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                MetadataLineView {
                    MetadataItemView(titleKey: reminder.scheduleKind.titleKey, systemImage: reminder.scheduleKind.systemImage)
                    MetadataItemView(title: completionKindTitle, systemImage: record.completion.kind.systemImage)
                }

                if let goalSummaryText = reminder.goalSummaryText {
                    Text(goalSummaryText)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }

                if let note = reminder.note {
                    Text(note)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
            }
        }
        .padding(.vertical, 4)
    }

    private var timeText: String {
        AppLocalization.format(
            "%@于 %@",
            completionKindTitle,
            AppFormatters.dateTime(milliseconds: record.completedAt, locale: locale)
        )
    }

    private var completionKindTitle: String {
        AppLocalization.string(record.completion.kind.title)
    }
}
