import Foundation

enum HistoryEntry: Identifiable, Hashable {
    case track(Track)
    case decisionChoice(DecisionChoiceRecord)
    case storedItem(StoredItem)
    case reminder(ReminderRecord)

    var id: String {
        switch self {
        case .track(let track):
            "track:\(track.id)"
        case .decisionChoice(let record):
            "decisionChoice:\(record.id)"
        case .storedItem(let item):
            "storedItem:\(item.id)"
        case .reminder(let reminder):
            "reminder:\(reminder.id)"
        }
    }

    var timestamp: Int64 {
        switch self {
        case .track(let track):
            track.startedAt
        case .decisionChoice(let record):
            record.selectedAt
        case .storedItem(let item):
            item.updatedAt
        case .reminder(let reminder):
            reminder.scheduledAt
        }
    }

    var date: Date {
        Date(timeIntervalSince1970: Double(timestamp) / 1000)
    }

    func isOnSameDay(as date: Date, calendar: Calendar = .current) -> Bool {
        calendar.isDate(self.date, inSameDayAs: date)
    }
}
