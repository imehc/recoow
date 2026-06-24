import GRDB

/// V20: 饮食记录。记录单次饮食条目，界面按天聚合展示。
enum V20FoodJournalSchema {
    static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v20_food_journal_schema") { db in
            try db.create(table: "food_entries") { t in
                t.syncMetadata()
                t.column("title", .text).notNull()
                t.column("meal_kind", .text).notNull()
                t.column("portion", .text)
                t.column("note", .text)
                t.column("occurred_at", .integer).notNull()
            }

            try db.create(index: "idx_food_entries_occurred_at", on: "food_entries", columns: ["occurred_at"])
            try db.create(index: "idx_food_entries_meal_kind", on: "food_entries", columns: ["meal_kind"])
            try db.create(index: "idx_food_entries_updated_at", on: "food_entries", columns: ["updated_at"])
        }
    }
}
