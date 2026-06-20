import SwiftUI

struct ReminderHistoryEntryRow: View {
    @Environment(\.locale) private var locale

    let reminder: ReminderRecord
    let reminderImageTransition: Namespace.ID

    var body: some View {
        HStack(spacing: 12) {
            if reminder.imageData != nil {
                PhotoThumbnailView(imageData: reminder.imageData, systemImage: "bell.fill", size: 64)
                    .matchedTransitionSource(id: reminder.id, in: reminderImageTransition)
            } else {
                ReminderIconView(memoryIcon: reminder.memoryIcon, size: 64)
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
                    MetadataItemView(title: statusText, systemImage: statusSystemImage)
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
        if let completedAt = reminder.completedAt {
            return AppLocalization.format("完成于 %@", AppFormatters.dateTime(milliseconds: completedAt, locale: locale))
        }

        if let missedDate = reminder.firstMissedCheckInDate() {
            return AppLocalization.format(
                "需补签 %@",
                AppFormatters.date(milliseconds: RemindersViewModel.milliseconds(for: missedDate), locale: locale)
            )
        }

        if let nextOccurrenceDate = reminder.nextOccurrenceDate {
            return AppLocalization.format(
                "下次 %@",
                AppFormatters.dateTime(milliseconds: RemindersViewModel.milliseconds(for: nextOccurrenceDate), locale: locale)
            )
        }

        return reminder.scheduleTitle(locale: locale)
    }

    private var statusText: String {
        AppLocalization.string(status.title)
    }

    private var statusSystemImage: String {
        status.systemImage
    }

    private var status: ReminderCheckInStatus {
        reminder.checkInStatus()
    }
}
