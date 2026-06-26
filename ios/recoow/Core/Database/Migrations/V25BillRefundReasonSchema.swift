import GRDB

/// V25: 账单增加退款原因。
enum V25BillRefundReasonSchema {
    static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v25_bill_refund_reason") { db in
            try db.alter(table: "bills") { t in
                t.add(column: "refund_reason", .text)
            }
        }
    }
}
