import GRDB

enum V26MediaAssetsSchema {
    nonisolated static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v26_media_assets_schema") { db in
            try CurrentMediaAttachmentDatabaseSchema.createMediaAssets(in: db)

            if try db.tableExists("media_attachments"),
               try db.columns(in: "media_attachments").contains(where: { $0.name == "asset_id" }) == false {
                try db.alter(table: "media_attachments") { t in
                    t.add(column: "asset_id", .text)
                }
                try db.create(index: "idx_media_attachments_asset", on: "media_attachments", columns: ["asset_id"])
            }
        }
    }
}
