import GRDB

/// V21: 饮食条目关联记一笔。
enum V21FoodEntryBillLinkSchema {
    nonisolated static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v21_food_entry_bill_link_schema") { db in
            try db.alter(table: "food_entries") { t in
                t.add(column: "bill_id", .text)
                    .references("bills", onDelete: .setNull)
            }

            try db.create(index: "idx_food_entries_bill_id", on: "food_entries", columns: ["bill_id"])
        }
    }
}
