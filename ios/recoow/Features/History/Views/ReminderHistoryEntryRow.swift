import SwiftUI

struct ReminderHistoryEntryRow: View {
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
                    MetadataItemView(titleKey: LocalizedStringKey(statusText), systemImage: statusSystemImage)
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
            return "完成于 \(AppFormatters.dateTime(milliseconds: completedAt))"
        }

        if let nextOccurrenceDate = reminder.nextOccurrenceDate {
            return "下次 \(AppFormatters.dateTime(milliseconds: RemindersViewModel.milliseconds(for: nextOccurrenceDate)))"
        }

        return reminder.scheduleTitle
    }

    private var statusText: String {
        if reminder.isCompleted {
            return "已完成"
        }

        if reminder.isTodayCompleted {
            return "今日已打卡"
        }

        if reminder.isEnabled == false {
            return "已关闭"
        }

        if reminder.isUpcoming {
            return "待打卡"
        }

        return "已结束"
    }

    private var statusSystemImage: String {
        if reminder.isCompleted {
            return "checkmark.circle.fill"
        }

        if reminder.isTodayCompleted {
            return "checkmark.circle"
        }

        if reminder.isEnabled == false {
            return "bell.slash"
        }

        if reminder.isUpcoming {
            return "circle"
        }

        return "clock.badge.exclamationmark"
    }
}
