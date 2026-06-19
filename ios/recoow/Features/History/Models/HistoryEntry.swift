import Foundation

enum HistoryEntry: Identifiable, Hashable, Sendable {
    case track(Track)
    case decisionChoice(DecisionChoiceRecord)
    case storedItem(StoredItem)
    case reminder(ReminderRecord)
    case bill(BillRecord)
    case anniversary(AnniversaryRecord)

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
        case .bill(let bill):
            "bill:\(bill.id)"
        case .anniversary(let anniversary):
            "anniversary:\(anniversary.id)"
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
        case .bill(let bill):
            bill.occurredAt
        case .anniversary(let anniversary):
            anniversary.occurredAt
        }
    }

    var route: ToolRoute {
        switch self {
        case .track:
            .locationTracker
        case .decisionChoice:
            .decisionMaker
        case .storedItem:
            .itemLocator
        case .reminder:
            .reminders
        case .bill:
            .bills
        case .anniversary:
            .anniversaries
        }
    }

    var date: Date {
        Date(timeIntervalSince1970: Double(timestamp) / 1000)
    }

    func isOnSameDay(as date: Date, calendar: Calendar = .current) -> Bool {
        calendar.isDate(self.date, inSameDayAs: date)
    }
}
