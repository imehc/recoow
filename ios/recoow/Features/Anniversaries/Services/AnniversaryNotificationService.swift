import Foundation

final class AnniversaryNotificationService: @unchecked Sendable {
    private let scheduler: any AppNotificationScheduling

    init(scheduler: any AppNotificationScheduling) {
        self.scheduler = scheduler
    }

    func reschedule(_ anniversary: AnniversaryRecord) async throws {
        cancel(anniversaryID: anniversary.id)

        guard anniversary.isEnabled,
              let nextDate = anniversary.nextOccurrenceDate,
              nextDate > Date() else {
            return
        }

        var requests = [
            AppNotificationRequest(
                identifier: mainIdentifier(for: anniversary.id),
                title: notificationTitle(for: anniversary),
                body: anniversary.note,
                scheduledDate: nextDate,
                userInfo: notificationUserInfo(for: anniversary, kind: "main")
            )
        ]

        if anniversary.leadTimeMinutes > 0 {
            let leadDate = nextDate.addingTimeInterval(Double(-anniversary.leadTimeMinutes * 60))
            if leadDate > Date() {
                requests.append(
                    AppNotificationRequest(
                        identifier: leadIdentifier(for: anniversary.id),
                        title: notificationTitle(for: anniversary),
                        subtitle: anniversary.leadTime.notificationSubtitle,
                        body: anniversary.note,
                        scheduledDate: leadDate,
                        userInfo: notificationUserInfo(for: anniversary, kind: "lead")
                    )
                )
            }
        }

        try await scheduler.schedule(requests)
    }

    func cancel(anniversaryID: String) {
        scheduler.cancel(identifiers: [
            mainIdentifier(for: anniversaryID),
            leadIdentifier(for: anniversaryID)
        ])
    }

    private func notificationTitle(for anniversary: AnniversaryRecord) -> String {
        "\(anniversary.category.title) · \(anniversary.title)"
    }

    private func notificationUserInfo(for anniversary: AnniversaryRecord, kind: String) -> [String: String] {
        [
            "feature": "anniversaries",
            "anniversaryID": anniversary.id,
            "kind": kind
        ]
    }

    private func mainIdentifier(for anniversaryID: String) -> String {
        "anniversary.\(anniversaryID).main"
    }

    private func leadIdentifier(for anniversaryID: String) -> String {
        "anniversary.\(anniversaryID).lead"
    }
}
