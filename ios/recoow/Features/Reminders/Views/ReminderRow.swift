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

                Text(nextTimeText)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                MetadataLineView {
                    MetadataItemView(titleKey: reminder.scheduleKind.titleKey, systemImage: reminder.scheduleKind.systemImage)

                    if reminder.leadTime != .none {
                        MetadataItemView(titleKey: reminder.leadTime.titleKey, systemImage: "clock.badge")
                    }
                }

                if let progressText = reminder.progressText,
                   let progressFraction = reminder.progressFraction {
                    VStack(alignment: .leading, spacing: 4) {
                        ProgressView(value: progressFraction)
                        Text("进度 \(progressText)")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
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

    private var nextTimeText: String {
        if reminder.isCompleted {
            return reminder.completedAt.map { "完成于 \(AppFormatters.dateTime(milliseconds: $0))" } ?? "已完成"
        }

        if let nextOccurrenceDate = reminder.nextOccurrenceDate {
            let milliseconds = RemindersViewModel.milliseconds(for: nextOccurrenceDate)
            return "下次 \(AppFormatters.dateTime(milliseconds: milliseconds))"
        }

        return reminder.scheduleTitle
    }
}
