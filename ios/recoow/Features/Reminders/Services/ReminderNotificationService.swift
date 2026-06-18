import Foundation

final class ReminderNotificationService: @unchecked Sendable {
    private let scheduler: any AppNotificationScheduling

    init(scheduler: any AppNotificationScheduling) {
        self.scheduler = scheduler
    }

    func reschedule(_ reminder: ReminderRecord) async throws {
        cancel(reminderID: reminder.id)

        guard reminder.isEnabled else { return }

        let requests = notificationRequests(for: reminder)
        guard requests.isEmpty == false else { return }
        try await scheduler.schedule(requests)
    }

    func cancel(reminderID: String) {
        scheduler.cancel(identifiers: notificationIdentifiers(for: reminderID))
    }

    private func notificationRequests(for reminder: ReminderRecord) -> [AppNotificationRequest] {
        var requests: [AppNotificationRequest] = []

        if reminder.scheduledDate > Date() {
            requests.append(
                AppNotificationRequest(
                    identifier: mainIdentifier(for: reminder.id),
                    title: notificationTitle(for: reminder),
                    body: reminder.note,
                    scheduledDate: reminder.scheduledDate,
                    attachmentData: reminder.imageData,
                    userInfo: notificationUserInfo(for: reminder, kind: "main")
                )
            )
        }

        if reminder.leadTimeMinutes > 0 {
            let leadDate = reminder.scheduledDate.addingTimeInterval(Double(-reminder.leadTimeMinutes * 60))
            if leadDate > Date() {
                requests.append(
                    AppNotificationRequest(
                        identifier: leadIdentifier(for: reminder.id),
                        title: notificationTitle(for: reminder),
                        subtitle: reminder.leadTime.notificationSubtitle,
                        body: reminder.note,
                        scheduledDate: leadDate,
                        attachmentData: reminder.imageData,
                        userInfo: notificationUserInfo(for: reminder, kind: "lead")
                    )
                )
            }
        }

        return requests
    }

    private func notificationTitle(for reminder: ReminderRecord) -> String {
        if reminder.imageData != nil {
            return reminder.title
        }

        return "\(reminder.memoryIcon) \(reminder.title)"
    }

    private func notificationUserInfo(for reminder: ReminderRecord, kind: String) -> [String: String] {
        [
            "feature": "reminders",
            "reminderID": reminder.id,
            "kind": kind
        ]
    }

    private func notificationIdentifiers(for reminderID: String) -> [String] {
        [mainIdentifier(for: reminderID), leadIdentifier(for: reminderID)]
    }

    private func mainIdentifier(for reminderID: String) -> String {
        "reminder.\(reminderID).main"
    }

    private func leadIdentifier(for reminderID: String) -> String {
        "reminder.\(reminderID).lead"
    }
}
