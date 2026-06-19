import Foundation
import SwiftUI

enum AnniversaryDateCalendar: String, CaseIterable, Identifiable, Codable, Sendable {
    case gregorian
    case chinese

    var id: String { rawValue }

    var title: String {
        switch self {
        case .gregorian:
            "公历"
        case .chinese:
            "农历"
        }
    }

    var titleKey: LocalizedStringKey {
        LocalizedStringKey(title)
    }

    var localizedTitle: String {
        AppLocalization.string(title)
    }

    var calendar: Calendar {
        switch self {
        case .gregorian:
            Calendar(identifier: .gregorian)
        case .chinese:
            Calendar(identifier: .chinese)
        }
    }
}
