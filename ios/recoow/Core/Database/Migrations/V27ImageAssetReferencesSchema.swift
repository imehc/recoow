import GRDB

enum V27ImageAssetReferencesSchema {
    nonisolated static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v27_image_asset_references_schema") { db in
            try addImageAssetReference(to: "reminders", in: db)
            try addImageAssetReference(to: "bills", in: db)
            try addImageAssetReference(to: "stored_items", in: db)
            try addImageAssetReference(to: "decision_options", in: db)

            if try db.tableExists("decision_choice_records"),
               try db.columns(in: "decision_choice_records").contains(where: { $0.name == "option_image_asset_id" }) == false {
                try db.alter(table: "decision_choice_records") { t in
                    t.add(column: "option_image_asset_id", .text)
                }
                try db.create(
                    index: "idx_decision_choice_records_option_image_asset",
                    on: "decision_choice_records",
                    columns: ["option_image_asset_id"]
                )
            }
        }
    }

    private nonisolated static func addImageAssetReference(to table: String, in db: Database) throws {
        guard try db.tableExists(table),
              try db.columns(in: table).contains(where: { $0.name == "image_asset_id" }) == false else {
            return
        }

        try db.alter(table: table) { t in
            t.add(column: "image_asset_id", .text)
        }
        try db.create(index: "idx_\(table)_image_asset", on: table, columns: ["image_asset_id"])
    }
}
