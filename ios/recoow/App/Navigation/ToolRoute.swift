import SwiftUI

enum ToolRoute: String, CaseIterable, Identifiable, Hashable {
    case locationTracker
    case decisionMaker
    case itemLocator

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
        }
    }
}
