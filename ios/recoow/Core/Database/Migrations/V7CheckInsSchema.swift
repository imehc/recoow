import GRDB

/// V7：将提醒扩展为打卡，支持规则、时间段和完成状态。
enum V7CheckInsSchema {
    static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v7_check_ins_schema") { db in
            try db.alter(table: "reminders") { t in
                t.add(column: "schedule_kind", .text).notNull().defaults(to: "single")
                t.add(column: "end_at", .integer)
                t.add(column: "reminder_time_minutes", .integer).notNull().defaults(to: 0)
                t.add(column: "weekdays", .text)
                t.add(column: "continuous_days", .integer).notNull().defaults(to: 30)
                t.add(column: "completed_at", .integer)
            }

            try db.create(index: "idx_reminders_completed_at", on: "reminders", columns: ["completed_at"])
        }
    }
}
