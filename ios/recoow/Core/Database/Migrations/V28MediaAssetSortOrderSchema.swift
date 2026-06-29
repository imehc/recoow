import GRDB

/// V28: add library-level manual ordering for reusable media assets.
enum V28MediaAssetSortOrderSchema {
    nonisolated static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v28_media_asset_sort_order_schema") { db in
            let columnRows = try Row.fetchAll(db, sql: "PRAGMA table_info(media_assets)")
            guard columnRows.isEmpty == false else { return }

            let hasSortOrder = columnRows.contains { row in
                let name: String = row["name"]
                return name == "sort_order"
            }

            guard hasSortOrder == false else { return }

            try db.alter(table: "media_assets") { t in
                t.add(column: "sort_order", .integer).notNull().defaults(to: 0)
            }

            let rows = try Row.fetchAll(
                db,
                sql: """
                    SELECT id
                    FROM media_assets
                    WHERE deleted_at IS NULL
                    ORDER BY updated_at DESC, created_at DESC, id ASC
                    """
            )

            for (index, row) in rows.enumerated() {
                let id: String = row["id"]
                try db.execute(
                    sql: "UPDATE media_assets SET sort_order = ? WHERE id = ?",
                    arguments: [index, id]
                )
            }

            try db.create(index: "idx_media_assets_sort_order", on: "media_assets", columns: ["sort_order"], ifNotExists: true)
        }
    }
}
