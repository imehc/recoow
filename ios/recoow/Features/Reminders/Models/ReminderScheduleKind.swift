import Foundation
import SwiftUI

enum ReminderScheduleKind: String, CaseIterable, Identifiable, Codable, Sendable {
    case single
    case dateRange
    case weekdays
    case weekly
    case continuousDays
    case dailyGoal

    var id: String { rawValue }

    var title: String {
        switch self {
        case .single:
            "单次打卡"
        case .dateRange:
            "阶段打卡"
        case .weekdays:
            "工作日打卡"
        case .weekly:
            "每周打卡"
        case .continuousDays:
            "连续挑战"
        case .dailyGoal:
            "坚持目标"
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
        case .dailyGoal:
            "target"
        }
    }
}
