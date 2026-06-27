import GRDB

/// V23: 账单增加结算状态与团购有效期，支持核销 / 退款。
enum V23BillSettlementSchema {
    nonisolated static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v23_bill_settlement_schema") { db in
            try db.alter(table: "bills") { t in
                t.add(column: "settlement_status", .text).notNull().defaults(to: "active")
                t.add(column: "group_buy_valid_until", .integer)
            }
        }
    }
}
