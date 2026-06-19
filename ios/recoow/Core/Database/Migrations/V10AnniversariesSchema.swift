import GRDB

/// V10：本地纪念日记录。
enum V10AnniversariesSchema {
    static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v10_anniversaries_schema") { db in
            try db.create(table: "anniversaries") { t in
                t.syncMetadata()
                t.column("title", .text).notNull()
                t.column("note", .text)
                t.column("category", .text).notNull()
                t.column("occurred_at", .integer).notNull()
                t.column("is_yearly", .boolean).notNull().defaults(to: true)
                t.column("lead_time_minutes", .integer).notNull().defaults(to: 0)
                t.column("is_enabled", .boolean).notNull().defaults(to: true)
                t.column("reminder_time_minutes", .integer).notNull().defaults(to: 540)
            }

            try db.create(index: "idx_anniversaries_occurred_at", on: "anniversaries", columns: ["occurred_at"])
            try db.create(index: "idx_anniversaries_updated_at", on: "anniversaries", columns: ["updated_at"])
        }
    }
}
