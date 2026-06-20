import Foundation

enum ReminderCheckInCompletionKind: String, Codable, Sendable {
    case checkIn
    case makeUp

    var title: String {
        switch self {
        case .checkIn:
            "打卡"
        case .makeUp:
            "补签"
        }
    }

    var systemImage: String {
        switch self {
        case .checkIn:
            "checkmark.circle.fill"
        case .makeUp:
            "calendar.badge.plus"
        }
    }
}

struct ReminderCheckInCompletion: Identifiable, Codable, Hashable, Sendable {
    var id: String { dateKey }

    var dateKey: String
    var completedAt: Int64
    var kindRawValue: String
    var note: String?

    var kind: ReminderCheckInCompletionKind {
        ReminderCheckInCompletionKind(rawValue: kindRawValue) ?? .checkIn
    }

    static func make(
        dateKey: String,
        completedAt: Int64 = SyncableTimestamp.nowMilliseconds(),
        kind: ReminderCheckInCompletionKind,
        note: String?
    ) -> ReminderCheckInCompletion {
        ReminderCheckInCompletion(
            dateKey: dateKey,
            completedAt: completedAt,
            kindRawValue: kind.rawValue,
            note: note
        )
    }
}

enum ReminderCheckInStatus: Hashable, Sendable {
    case completed
    case checkedInToday
    case ready
    case broken
    case disabled
    case upcoming
    case ended

    var title: String {
        switch self {
        case .completed:
            "已完成"
        case .checkedInToday:
            "今日已打卡"
        case .ready:
            "待打卡"
        case .broken:
            "已断签"
        case .disabled:
            "已关闭"
        case .upcoming:
            "未开始"
        case .ended:
            "已结束"
        }
    }

    var systemImage: String {
        switch self {
        case .completed:
            "checkmark.circle.fill"
        case .checkedInToday:
            "checkmark.circle"
        case .ready:
            "circle"
        case .broken:
            "exclamationmark.octagon"
        case .disabled:
            "bell.slash"
        case .upcoming:
            "clock"
        case .ended:
            "clock.badge.exclamationmark"
        }
    }
}
