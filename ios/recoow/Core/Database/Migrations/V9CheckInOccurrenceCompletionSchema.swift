import GRDB

/// V9：记录打卡每天的完成日期，支持连续/时间段逐日累计。
enum V9CheckInOccurrenceCompletionSchema {
    static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v9_check_in_occurrence_completion_schema") { db in
            try db.alter(table: "reminders") { t in
                t.add(column: "completed_date_keys", .text)
            }
        }
    }
}
