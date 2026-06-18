import SwiftUI

struct ReminderStatusTag: View {
    let reminder: ReminderRecord

    var body: some View {
        Label(LocalizedStringKey(title), systemImage: systemImage)
            .font(.footnote)
            .foregroundStyle(tint)
            .labelStyle(.titleAndIcon)
    }

    private var title: String {
        if reminder.isUpcoming {
            return "待提醒"
        }

        if reminder.isEnabled {
            return "已到期"
        }

        return "已关闭"
    }

    private var systemImage: String {
        if reminder.isUpcoming {
            return "bell.fill"
        }

        if reminder.isEnabled {
            return "checkmark.circle"
        }

        return "bell.slash"
    }

    private var tint: Color {
        if reminder.isUpcoming {
            return .purple
        }

        if reminder.isEnabled {
            return .green
        }

        return .secondary
    }
}
