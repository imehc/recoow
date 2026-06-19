import SwiftUI

struct ReminderStatusTag: View {
    let reminder: ReminderRecord

    var body: some View {
        MetadataItemView(title: title, systemImage: systemImage)
            .font(.footnote)
            .foregroundStyle(tint)
    }

    private var title: String {
        if reminder.isCompleted {
            return AppLocalization.string("已完成")
        }

        if reminder.isTodayCompleted {
            return AppLocalization.string("今日已打卡")
        }

        if reminder.isEnabled == false {
            return AppLocalization.string("已关闭")
        }

        if reminder.isUpcoming {
            return AppLocalization.string("待打卡")
        }

        return AppLocalization.string("已结束")
    }

    private var systemImage: String {
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

    private var tint: Color {
        if reminder.isCompleted {
            return .green
        }

        if reminder.isTodayCompleted {
            return .green
        }

        if reminder.isEnabled == false {
            return .secondary
        }

        if reminder.isUpcoming {
            return .purple
        }

        return .orange
    }
}
