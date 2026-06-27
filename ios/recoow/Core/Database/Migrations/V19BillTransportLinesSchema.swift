import GRDB

/// V19: 交通账单增加线路，支持多条线路文本。
enum V19BillTransportLinesSchema {
    nonisolated static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v19_bill_transport_lines_schema") { db in
            try db.alter(table: "bills") { t in
                t.add(column: "transport_lines", .text)
            }
        }
    }
}
