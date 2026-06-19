import Foundation
import SwiftUI

enum ReminderMemoryIcon: String, CaseIterable, Identifiable, Sendable {
    case bell = "🔔"
    case calendar = "📅"
    case pin = "📌"
    case medicine = "💊"
    case key = "🔑"
    case wallet = "👛"
    case gift = "🎁"
    case document = "📄"
    case home = "🏠"
    case work = "💼"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .bell:
            "打卡"
        case .calendar:
            "日程"
        case .pin:
            "标记"
        case .medicine:
            "服药"
        case .key:
            "钥匙"
        case .wallet:
            "钱包"
        case .gift:
            "礼物"
        case .document:
            "文件"
        case .home:
            "家"
        case .work:
            "工作"
        }
    }

    var titleKey: LocalizedStringKey {
        LocalizedStringKey(title)
    }

    static func resolved(from rawValue: String?) -> ReminderMemoryIcon {
        guard let rawValue,
              let icon = ReminderMemoryIcon(rawValue: rawValue)
        else {
            return .bell
        }

        return icon
    }
}
