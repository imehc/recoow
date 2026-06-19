import SwiftUI

enum ToolRoute: String, CaseIterable, Identifiable, Hashable, Sendable {
    case locationTracker
    case decisionMaker
    case itemLocator
    case reminders
    case bills
    case anniversaries

    var id: String { rawValue }

    var titleKey: LocalizedStringKey {
        LocalizedStringKey(title)
    }

    var title: String {
        switch self {
        case .locationTracker:
            "轨迹记录"
        case .decisionMaker:
            "选什么"
        case .itemLocator:
            "在哪里"
        case .reminders:
            "打卡"
        case .bills:
            "记一笔"
        case .anniversaries:
            "纪念日"
        }
    }

    var subtitleKey: LocalizedStringKey {
        LocalizedStringKey(subtitle)
    }

    var subtitle: String {
        switch self {
        case .locationTracker:
            "记录路线、距离与速度"
        case .decisionMaker:
            "管理选项并随机选择"
        case .itemLocator:
            "记录物品放在哪里"
        case .reminders:
            "打卡、日期规则与定时提醒"
        case .bills:
            "记录价格、优惠与备注"
        case .anniversaries:
            "倒计时、周年与日期提醒"
        }
    }

    var systemImage: String {
        switch self {
        case .locationTracker:
            "location.fill"
        case .decisionMaker:
            "shuffle"
        case .itemLocator:
            "shippingbox.fill"
        case .reminders:
            "checkmark.circle.fill"
        case .bills:
            "receipt.fill"
        case .anniversaries:
            "calendar"
        }
    }

    var tint: Color {
        switch self {
        case .locationTracker:
            .green
        case .decisionMaker:
            .orange
        case .itemLocator:
            .blue
        case .reminders:
            .purple
        case .bills:
            .teal
        case .anniversaries:
            .pink
        }
    }
}
