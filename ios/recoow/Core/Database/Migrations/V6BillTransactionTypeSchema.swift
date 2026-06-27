import GRDB

/// V6：账单增加收支类型，旧数据默认保持为支出。
enum V6BillTransactionTypeSchema {
    nonisolated static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v6_bill_transaction_type_schema") { db in
            try db.alter(table: "bills") { t in
                t.add(column: "transaction_type", .text)
                    .notNull()
                    .defaults(to: "expense")
            }

            try db.create(index: "idx_bills_transaction_type", on: "bills", columns: ["transaction_type"])
        }
    }
}
