import Foundation
import SwiftUI

enum DiaryLinkSourceType: String, CaseIterable, Identifiable, Codable, Sendable {
    case track
    case bill
    case reminder
    case anniversary
    case storedItem
    case decisionChoice

    var id: String { rawValue }

    var title: String {
        switch self {
        case .track:
            "轨迹记录"
        case .bill:
            "记一笔"
        case .reminder:
            "打卡任务"
        case .anniversary:
            "纪念日"
        case .storedItem:
            "在哪里"
        case .decisionChoice:
            "选什么"
        }
    }

    var localizedTitle: String {
        AppLocalization.string(title)
    }

    var systemImage: String {
        switch self {
        case .track:
            "location.fill"
        case .bill:
            "receipt.fill"
        case .reminder:
            "checkmark.circle.fill"
        case .anniversary:
            "calendar"
        case .storedItem:
            "shippingbox.fill"
        case .decisionChoice:
            "shuffle"
        }
    }

    var tint: Color {
        switch self {
        case .track:
            .green
        case .bill:
            .teal
        case .reminder:
            .purple
        case .anniversary:
            .pink
        case .storedItem:
            .blue
        case .decisionChoice:
            .orange
        }
    }

    var toolRoute: ToolRoute {
        switch self {
        case .track:
            .locationTracker
        case .bill:
            .bills
        case .reminder:
            .reminders
        case .anniversary:
            .anniversaries
        case .storedItem:
            .itemLocator
        case .decisionChoice:
            .decisionMaker
        }
    }
}
