import GRDB

/// V8：支持导入打卡历史进度。
enum V8CheckInImportedProgressSchema {
    nonisolated static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v8_check_in_imported_progress_schema") { db in
            try db.alter(table: "reminders") { t in
                t.add(column: "imported_completed_days", .integer).notNull().defaults(to: 0)
            }
        }
    }
}
