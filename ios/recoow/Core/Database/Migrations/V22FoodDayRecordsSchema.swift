import GRDB

/// V22: 饮食日级记录。保存某一天的标题等聚合信息。
enum V22FoodDayRecordsSchema {
    static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v22_food_day_records_schema") { db in
            try db.create(table: "food_day_records") { t in
                t.syncMetadata()
                t.column("title", .text).notNull()
                t.column("day_start_at", .integer).notNull().unique()
            }

            try db.create(index: "idx_food_day_records_day_start_at", on: "food_day_records", columns: ["day_start_at"])
            try db.create(index: "idx_food_day_records_updated_at", on: "food_day_records", columns: ["updated_at"])
        }
    }
}
