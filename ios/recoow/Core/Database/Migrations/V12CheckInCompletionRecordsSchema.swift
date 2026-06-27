import GRDB

/// V12：记录每日打卡明细，支持补签和补签备注。
enum V12CheckInCompletionRecordsSchema {
    nonisolated static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v12_check_in_completion_records_schema") { db in
            try db.alter(table: "reminders") { t in
                t.add(column: "completion_records", .text)
            }
        }
    }
}
