import SwiftUI

struct ReminderRow: View {
    let reminder: ReminderRecord
    let reminderImageTransition: Namespace.ID

    var body: some View {
        HStack(spacing: 12) {
            if reminder.imageData != nil {
                PhotoThumbnailView(imageData: reminder.imageData, systemImage: "bell.fill", size: 56)
                    .matchedTransitionSource(id: reminder.id, in: reminderImageTransition)
            } else {
                ReminderIconView(memoryIcon: reminder.memoryIcon, size: 56)
            }

            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(reminder.title)
                        .font(.headline)
                        .lineLimit(2)

                    Spacer(minLength: 8)

                    ReminderStatusTag(reminder: reminder)
                }

                Text(AppFormatters.dateTime(milliseconds: reminder.scheduledAt))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                if reminder.leadTime != .none {
                    Label(reminder.leadTime.title, systemImage: "clock.badge")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
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
}
