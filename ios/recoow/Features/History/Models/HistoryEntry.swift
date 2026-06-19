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

    var detailRoute: HistoryDetailRoute {
        switch self {
        case .track(let track):
            .track(track.id)
        case .decisionChoice(let record):
            .decisionChoice(record.id)
        case .storedItem(let item):
            .storedItem(item.id)
        case .reminder(let reminder):
            .reminder(reminder.id)
        case .bill(let bill):
            .bill(bill.id)
        case .anniversary(let anniversary):
            .anniversary(anniversary.id)
        }
    }

    var title: String {
        switch self {
        case .track(let track):
            track.name
        case .decisionChoice(let record):
            record.optionTitle
        case .storedItem(let item):
            item.title
        case .reminder(let reminder):
            reminder.title
        case .bill(let bill):
            bill.title
        case .anniversary(let anniversary):
            anniversary.title
        }
    }

    var trackID: String? {
        guard case .track(let track) = self else { return nil }
        return track.id
    }

    var decisionRecordID: String? {
        guard case .decisionChoice(let record) = self else { return nil }
        return record.id
    }

    var storedItemID: String? {
        guard case .storedItem(let item) = self else { return nil }
        return item.id
    }

    var reminderID: String? {
        guard case .reminder(let reminder) = self else { return nil }
        return reminder.id
    }

    var billID: String? {
        guard case .bill(let bill) = self else { return nil }
        return bill.id
    }

    var anniversaryID: String? {
        guard case .anniversary(let anniversary) = self else { return nil }
        return anniversary.id
    }

    func isOnSameDay(as date: Date, calendar: Calendar = .current) -> Bool {
        calendar.isDate(self.date, inSameDayAs: date)
    }
}
