import GRDB

/// V31：为连续挑战记录显式的提前结束时间，且不改动打卡历史。
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
