import Foundation

final class ReminderNotificationService: @unchecked Sendable {
    private let maxScheduledOccurrences = 32
    private let maxIdentifierSlots = 64
    private let scheduler: any AppNotificationScheduling

    init(scheduler: any AppNotificationScheduling) {
        self.scheduler = scheduler
    }

    func reschedule(_ reminder: ReminderRecord) async throws {
        cancel(reminderID: reminder.id)

        guard reminder.isEnabled, reminder.isCompleted == false else { return }

        let requests = notificationRequests(for: reminder)
        guard requests.isEmpty == false else { return }
        try await scheduler.schedule(requests)
    }

    func cancel(reminderID: String) {
        scheduler.cancel(identifiers: notificationIdentifiers(for: reminderID))
    }

    private func notificationRequests(for reminder: ReminderRecord) -> [AppNotificationRequest] {
        var requests: [AppNotificationRequest] = []
        let occurrenceDates = reminder.occurrenceDates(maxCount: maxScheduledOccurrences)
        let attachmentData = reminder.resolvedImageData

        for (index, scheduledDate) in occurrenceDates.enumerated() {
            requests.append(
                AppNotificationRequest(
                    identifier: mainIdentifier(for: reminder.id, index: index),
                    title: notificationTitle(for: reminder),
                    body: reminder.note,
                    scheduledDate: scheduledDate,
                    attachmentData: attachmentData,
                    userInfo: notificationUserInfo(for: reminder, kind: "main", occurrenceIndex: index)
                )
            )

            if reminder.leadTimeMinutes > 0 {
                let leadDate = scheduledDate.addingTimeInterval(Double(-reminder.leadTimeMinutes * 60))
                if leadDate <= Date() {
                    continue
                }

                requests.append(
                    AppNotificationRequest(
                        identifier: leadIdentifier(for: reminder.id, index: index),
                        title: notificationTitle(for: reminder),
                        subtitle: reminder.leadTime.notificationSubtitle,
                        body: reminder.note,
                        scheduledDate: leadDate,
                        attachmentData: attachmentData,
                        userInfo: notificationUserInfo(for: reminder, kind: "lead", occurrenceIndex: index)
                    )
                )
            }
        }

        return requests
    }

    private func notificationTitle(for reminder: ReminderRecord) -> String {
        if reminder.hasImage {
            return reminder.title
        }

        return "\(reminder.memoryIcon) \(reminder.title)"
    }

    private func notificationUserInfo(for reminder: ReminderRecord, kind: String, occurrenceIndex: Int) -> [String: String] {
        [
            "feature": "reminders",
            "reminderID": reminder.id,
            "kind": kind,
            "occurrenceIndex": "\(occurrenceIndex)"
        ]
    }

    private func notificationIdentifiers(for reminderID: String) -> [String] {
        var identifiers = [legacyMainIdentifier(for: reminderID), legacyLeadIdentifier(for: reminderID)]

        for index in 0..<maxIdentifierSlots {
            identifiers.append(mainIdentifier(for: reminderID, index: index))
            identifiers.append(leadIdentifier(for: reminderID, index: index))
        }

        return identifiers
    }

    private func legacyMainIdentifier(for reminderID: String) -> String {
        "reminder.\(reminderID).main"
    }

    private func legacyLeadIdentifier(for reminderID: String) -> String {
        "reminder.\(reminderID).lead"
    }

    private func mainIdentifier(for reminderID: String, index: Int) -> String {
        "reminder.\(reminderID).main.\(index)"
    }

    private func leadIdentifier(for reminderID: String, index: Int) -> String {
        "reminder.\(reminderID).lead.\(index)"
    }
}
