import GRDB

/// V30：储值账户与账单储值账户关联。
enum V30StoredValueAccountsSchema {
    nonisolated static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v30_stored_value_accounts_schema") { db in
            try db.create(table: "stored_value_accounts") { t in
                t.syncMetadata()
                t.column("name", .text).notNull()
                t.column("kind", .text).notNull()
                t.column("balance_cents", .integer).notNull().defaults(to: 0)
                t.column("note", .text)
            }

            try db.create(index: "idx_stored_value_accounts_updated_at", on: "stored_value_accounts", columns: ["updated_at"])

            try db.alter(table: "bills") { t in
                t.add(column: "stored_value_account_id", .text)
            }

            try db.create(index: "idx_bills_stored_value_account_id", on: "bills", columns: ["stored_value_account_id"])
        }
    }
}
