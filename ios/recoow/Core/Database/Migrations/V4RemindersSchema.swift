import GRDB

/// V4：本地提醒记录和通知配置。
enum V4RemindersSchema {
    nonisolated static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v4_reminders_schema") { db in
            try db.create(table: "reminders") { t in
                t.syncMetadata()
                t.column("title", .text).notNull()
                t.column("note", .text)
                t.column("memory_icon", .text).notNull()
                t.column("image_data", .blob)
                t.column("scheduled_at", .integer).notNull()
                t.column("lead_time_minutes", .integer).notNull().defaults(to: 0)
                t.column("is_enabled", .boolean).notNull().defaults(to: true)
            }

            try db.create(index: "idx_reminders_scheduled_at", on: "reminders", columns: ["scheduled_at"])
            try db.create(index: "idx_reminders_updated_at", on: "reminders", columns: ["updated_at"])
        }
    }
}
