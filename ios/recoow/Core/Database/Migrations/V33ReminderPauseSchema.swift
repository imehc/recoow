import GRDB

/// V33：坚持目标支持暂停，记录暂停开始时间、累计暂停时长和暂停原因。
enum V33ReminderPauseSchema {
    nonisolated static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v33_reminder_pause_schema") { db in
            try db.alter(table: "reminders") { t in
                t.add(column: "paused_at", .integer)
                t.add(column: "pause_reason", .text)
                t.add(column: "total_paused_milliseconds", .integer)
            }
        }
    }
}
