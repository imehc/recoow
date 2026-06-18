import Foundation
import SwiftUI

enum ReminderLeadTime: Int, CaseIterable, Identifiable, Codable, Sendable {
    case none = 0
    case fiveMinutes = 5
    case tenMinutes = 10
    case thirtyMinutes = 30
    case oneHour = 60
    case oneDay = 1440

    var id: Int { rawValue }

    var title: String {
        switch self {
        case .none:
            "不提前"
        case .fiveMinutes:
            "提前 5 分钟"
        case .tenMinutes:
            "提前 10 分钟"
        case .thirtyMinutes:
            "提前 30 分钟"
        case .oneHour:
            "提前 1 小时"
        case .oneDay:
            "提前 1 天"
        }
    }

    var titleKey: LocalizedStringKey {
        LocalizedStringKey(title)
    }

    var localizedTitle: String {
        AppLocalization.string(title)
    }

    var notificationSubtitle: String? {
        switch self {
        case .none:
            nil
        default:
            localizedTitle
        }
    }
}
