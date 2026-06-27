import GRDB

/// V17: 交通账单增加起点和终点。
enum V17BillTransportLocationsSchema {
    nonisolated static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v17_bill_transport_locations_schema") { db in
            try db.alter(table: "bills") { t in
                t.add(column: "start_location", .text)
                t.add(column: "end_location", .text)
            }
        }
    }
}
