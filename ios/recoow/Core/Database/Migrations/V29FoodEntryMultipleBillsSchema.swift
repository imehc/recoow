import GRDB

/// V29: 饮食条目支持关联多个账单，保留 bill_id 作为旧数据兼容字段。
enum V29FoodEntryMultipleBillsSchema {
    nonisolated static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v29_food_entry_multiple_bills_schema") { db in
            try db.alter(table: "food_entries") { t in
                t.add(column: "bill_ids_json", .text)
                    .notNull()
                    .defaults(to: "[]")
            }

            try db.execute(sql: """
                UPDATE food_entries
                SET bill_ids_json = json_array(bill_id)
                WHERE bill_id IS NOT NULL
                  AND bill_ids_json = '[]'
                """
            )
        }
    }
}
