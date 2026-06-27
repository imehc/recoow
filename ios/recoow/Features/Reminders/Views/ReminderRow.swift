import SwiftUI

struct ReminderRow: View {
    @Environment(\.locale) private var locale

    let reminder: ReminderRecord
    let reminderImageTransition: Namespace.ID

    var body: some View {
        HStack(spacing: 12) {
            if reminder.hasImage {
                PhotoThumbnailView(imageData: reminder.resolvedImageData, systemImage: "bell.fill", size: AppDesign.listIconSize)
                    .matchedTransitionSource(id: reminder.id, in: reminderImageTransition)
            } else {
                ReminderIconView(memoryIcon: reminder.memoryIcon, size: AppDesign.listIconSize)
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
                        Text(AppLocalization.format("进度 %@", progressText))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }

                if let goalSummaryText = reminder.goalSummaryText {
                    Text(goalSummaryText)
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

    private var nextTimeText: String {
        if reminder.isCompleted {
            return reminder.completedAt.map {
                AppLocalization.format("完成于 %@", AppFormatters.dateTime(milliseconds: $0, locale: locale))
            } ?? AppLocalization.string("已完成")
        }

        if let missedDate = reminder.firstMissedCheckInDate() {
            return AppLocalization.format(
                "需补签 %@",
                AppFormatters.date(milliseconds: RemindersViewModel.milliseconds(for: missedDate), locale: locale)
            )
        }

        if let nextOccurrenceDate = reminder.nextOccurrenceDate {
            let milliseconds = RemindersViewModel.milliseconds(for: nextOccurrenceDate)
            return AppLocalization.format("下次 %@", AppFormatters.dateTime(milliseconds: milliseconds, locale: locale))
        }

        return reminder.scheduleTitle(locale: locale)
    }
}
