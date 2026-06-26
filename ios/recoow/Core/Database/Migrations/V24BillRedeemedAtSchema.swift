import GRDB

/// V24: 账单增加核销时间。
enum V24BillRedeemedAtSchema {
    static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v24_bill_redeemed_at") { db in
            try db.alter(table: "bills") { t in
                t.add(column: "redeemed_at", .integer)
            }
        }
    }
}
