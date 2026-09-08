import Foundation
import GRDB

final class SyncNotificationDelivery: @unchecked Sendable {
    private let database: AppDatabase
    private let reminders: ReminderNotificationService
    private let anniversaries: AnniversaryNotificationService

    init(database: AppDatabase, scheduler: any AppNotificationScheduling) {
        self.database = database
        reminders = ReminderNotificationService(scheduler: scheduler)
        anniversaries = AnniversaryNotificationService(scheduler: scheduler)
    }

    func flush() async throws {
        let jobs = try await database.reader.read { db in try Row.fetchAll(db, sql: "SELECT * FROM sync_notification_jobs") }
        for job in jobs {
            let table: String = job["table_name"]
            let id: String = job["entity_id"]
            let version: Int64 = job["version"]
            if table == "reminders" {
                let record = try await database.reader.read { db in try ReminderRecord.fetchOne(db, key: id) }
                if let record, record.deletedAt == nil { try await reminders.reschedule(record) }
                else { reminders.cancel(reminderID: id) }
            } else if table == "anniversaries" {
                let record = try await database.reader.read { db in try AnniversaryRecord.fetchOne(db, key: id) }
                if let record, record.deletedAt == nil { try await anniversaries.reschedule(record) }
                else { anniversaries.cancel(anniversaryID: id) }
            }
            try await database.writer.write { db in
                try db.execute(sql: "DELETE FROM sync_notification_jobs WHERE table_name = ? AND entity_id = ? AND version = ?",
                               arguments: [table, id, version])
            }
        }
    }
}
