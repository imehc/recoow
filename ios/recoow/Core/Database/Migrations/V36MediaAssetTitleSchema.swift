import GRDB

/// 可选素材标题；旧素材及旧备份不需要补填。
enum V36MediaAssetTitleSchema {
    nonisolated static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v36_media_asset_title") { db in
            try db.alter(table: "media_assets") { t in
                t.add(column: "title", .text)
            }
        }
    }
}
