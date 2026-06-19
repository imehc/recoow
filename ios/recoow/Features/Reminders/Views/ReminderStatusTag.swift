import SwiftUI

struct ReminderStatusTag: View {
    let reminder: ReminderRecord

    var body: some View {
        MetadataItemView(titleKey: LocalizedStringKey(title), systemImage: systemImage)
            .font(.footnote)
            .foregroundStyle(tint)
    }

    private var title: String {
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
