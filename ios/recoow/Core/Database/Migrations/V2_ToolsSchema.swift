import GRDB

/// V2：随机选择工具和物品位置工具。
enum V2ToolsSchema {
    static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v2_tools_schema") { db in
            try db.create(table: "decision_collections") { t in
                t.syncMetadata()
                t.column("title", .text).notNull()
                t.column("note", .text)
            }

            try db.create(index: "idx_decision_collections_updated_at", on: "decision_collections", columns: ["updated_at"])

            try db.create(table: "decision_options") { t in
                t.syncMetadata()
                t.column("collection_id", .text)
                    .notNull()
                    .references("decision_collections", onDelete: .cascade)
                t.column("title", .text).notNull()
                t.column("detail", .text)
                t.column("custom_info", .text)
                t.column("image_data", .blob)
                t.column("weight", .integer).notNull().defaults(to: 1)
                t.column("is_enabled", .boolean).notNull().defaults(to: true)
            }

            try db.create(index: "idx_decision_options_collection_id", on: "decision_options", columns: ["collection_id"])

            try db.create(table: "item_categories") { t in
                t.syncMetadata()
                t.column("name", .text).notNull()
                t.column("note", .text)
            }

            try db.create(index: "idx_item_categories_name", on: "item_categories", columns: ["name"])

            try db.create(table: "stored_items") { t in
                t.syncMetadata()
                t.column("category_id", .text)
                    .references("item_categories", onDelete: .setNull)
                t.column("title", .text).notNull()
                t.column("location", .text).notNull()
                t.column("note", .text)
                t.column("tags", .text)
                t.column("search_keywords", .text)
                t.column("image_data", .blob)
            }

            try db.create(index: "idx_stored_items_category_id", on: "stored_items", columns: ["category_id"])
            try db.create(index: "idx_stored_items_updated_at", on: "stored_items", columns: ["updated_at"])
        }
    }
}
