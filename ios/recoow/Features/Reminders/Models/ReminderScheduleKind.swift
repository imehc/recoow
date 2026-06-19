import Foundation
import SwiftUI

enum ReminderScheduleKind: String, CaseIterable, Identifiable, Codable, Sendable {
    case single
    case dateRange
    case weekdays
    case weekly
    case continuousDays

    var id: String { rawValue }

    var title: String {
        switch self {
        case .single:
            "单次"
        case .dateRange:
            "时间段"
        case .weekdays:
            "工作日"
        case .weekly:
            "每周几"
        case .continuousDays:
            "连续天数"
        }
    }

    var titleKey: LocalizedStringKey {
        LocalizedStringKey(title)
    }

    var systemImage: String {
        switch self {
        case .single:
            "calendar"
        case .dateRange:
            "calendar.badge.clock"
        case .weekdays:
            "briefcase"
        case .weekly:
            "calendar.day.timeline.left"
        case .continuousDays:
            "flame"
        }
    }
}
