import Foundation

struct ReminderHistoryRecord: Identifiable, Hashable, Sendable {
    let reminder: ReminderRecord
    let completion: ReminderCheckInCompletion

    var id: String {
        "reminder:\(reminder.id):\(completion.dateKey)"
    }

    var reminderID: String {
        reminder.id
    }

    var dateKey: String {
        completion.dateKey
    }

    var completedAt: Int64 {
        completion.completedAt
    }

    var deletionTarget: ReminderCompletionDeletionTarget {
        ReminderCompletionDeletionTarget(reminderID: reminderID, dateKey: dateKey)
    }
}

struct ReminderCompletionDeletionTarget: Hashable, Sendable {
    let reminderID: String
    let dateKey: String
}
