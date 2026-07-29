import GRDB

/// V31: records an explicit early end for continuous challenges without changing check-in history.
enum V31ReminderEarlyEndSchema {
    nonisolated static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v31_reminder_early_end_schema") { db in
            try db.alter(table: "reminders") { t in
                t.add(column: "ended_early_at", .integer)
                t.add(column: "early_end_reason", .text)
            }
        }
    }
}
