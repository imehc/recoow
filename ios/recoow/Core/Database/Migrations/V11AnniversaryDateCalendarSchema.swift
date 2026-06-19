import GRDB

/// V11：纪念日支持记录日期使用的历法，旧数据默认公历。
enum V11AnniversaryDateCalendarSchema {
    static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v11_anniversary_date_calendar_schema") { db in
            try db.alter(table: "anniversaries") { t in
                t.add(column: "date_calendar", .text).notNull().defaults(to: AnniversaryDateCalendar.gregorian.rawValue)
            }
        }
    }
}
