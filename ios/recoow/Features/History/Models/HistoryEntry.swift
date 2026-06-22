import Foundation

enum HistoryEntry: Identifiable, Hashable, Sendable {
    case track(Track)
    case decisionChoice(DecisionChoiceRecord)
    case storedItem(StoredItem)
    case reminder(ReminderHistoryRecord)
    case bill(BillRecord)
    case diary(DiaryEntry)
    case anniversary(AnniversaryRecord)

    var id: String {
        switch self {
        case .track(let track):
            "track:\(track.id)"
        case .decisionChoice(let record):
            "decisionChoice:\(record.id)"
        case .storedItem(let item):
            "storedItem:\(item.id)"
        case .reminder(let record):
            record.id
        case .bill(let bill):
            "bill:\(bill.id)"
        case .diary(let diary):
            "diary:\(diary.id)"
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
        case .reminder(let record):
            record.completedAt
        case .bill(let bill):
            bill.occurredAt
        case .diary(let diary):
            diary.occurredAt
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
        case .diary:
            .diary
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
        case .reminder(let record):
            .reminder(record.reminderID)
        case .bill(let bill):
            .bill(bill.id)
        case .diary(let diary):
            .diary(diary.id)
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
        case .reminder(let record):
            record.reminder.title
        case .bill(let bill):
            bill.title
        case .diary(let diary):
            diary.title
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
        guard case .reminder(let record) = self else { return nil }
        return record.reminderID
    }

    var reminderCompletionDeletionTarget: ReminderCompletionDeletionTarget? {
        guard case .reminder(let record) = self else { return nil }
        return record.deletionTarget
    }

    var billID: String? {
        guard case .bill(let bill) = self else { return nil }
        return bill.id
    }

    var diaryID: String? {
        guard case .diary(let diary) = self else { return nil }
        return diary.id
    }

    var anniversaryID: String? {
        guard case .anniversary(let anniversary) = self else { return nil }
        return anniversary.id
    }

    func isOnSameDay(as date: Date, calendar: Calendar = .current) -> Bool {
        calendar.isDate(self.date, inSameDayAs: date)
    }
}
