import GRDB

/// V5：本地账单记录。
enum V5BillsSchema {
    static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v5_bills_schema") { db in
            try db.create(table: "bills") { t in
                t.syncMetadata()
                t.column("title", .text).notNull()
                t.column("original_amount_cents", .integer).notNull()
                t.column("discount_amount_cents", .integer).notNull().defaults(to: 0)
                t.column("final_amount_cents", .integer).notNull()
                t.column("category", .text).notNull()
                t.column("payment_method", .text).notNull()
                t.column("note", .text)
                t.column("occurred_at", .integer).notNull()
                t.column("image_data", .blob)
            }

            try db.create(index: "idx_bills_occurred_at", on: "bills", columns: ["occurred_at"])
            try db.create(index: "idx_bills_updated_at", on: "bills", columns: ["updated_at"])
        }
    }
}
