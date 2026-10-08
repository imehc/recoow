import GRDB

/// V35: 生活缴费账单增加可选的缴费号码，以文本保留前导零。
enum V35BillUtilityAccountNumberSchema {
    nonisolated static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v35_bill_utility_account_number") { db in
            try db.alter(table: "bills") { t in
                t.add(column: "utility_account_number", .text)
            }
        }
    }
}
