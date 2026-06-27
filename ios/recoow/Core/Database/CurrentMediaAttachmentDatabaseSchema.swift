import GRDB

enum CurrentMediaAttachmentDatabaseSchema {
    nonisolated static let tableNames = [
        "media_assets",
        "media_attachments"
    ]

    nonisolated static func create(in db: Database) throws {
        try createMediaAssets(in: db)

        try db.create(table: "media_attachments") { t in
            t.syncMetadata()
            t.column("owner_type", .text).notNull()
            t.column("owner_id", .text).notNull()
            t.column("kind", .text).notNull()
            t.column("title", .text)
            t.column("asset_id", .text)
            t.column("data", .blob).notNull()
            t.column("mime_type", .text).notNull()
            t.column("duration_seconds", .double)
            t.column("width", .integer)
            t.column("height", .integer)
            t.column("sort_order", .integer).notNull().defaults(to: 0)
        }

        try db.create(index: "idx_media_attachments_owner", on: "media_attachments", columns: ["owner_type", "owner_id"])
        try db.create(index: "idx_media_attachments_asset", on: "media_attachments", columns: ["asset_id"])
        try db.create(index: "idx_media_attachments_updated_at", on: "media_attachments", columns: ["updated_at"])
    }

    nonisolated static func createMediaAssets(in db: Database) throws {
        try db.create(table: "media_assets", ifNotExists: true) { t in
            t.syncMetadata()
            t.column("kind", .text).notNull()
            t.column("storage_backend", .text).notNull()
            t.column("storage_key", .text).notNull()
            t.column("mime_type", .text).notNull()
            t.column("byte_count", .integer).notNull()
            t.column("checksum", .text).notNull()
            t.column("width", .integer)
            t.column("height", .integer)
            // 运行时优先读对象文件；inline_data 只用于单文件备份/导入时携带完整图片数据。
            t.column("inline_data", .blob)
        }

        try db.create(index: "idx_media_assets_checksum", on: "media_assets", columns: ["checksum"], ifNotExists: true)
        try db.create(index: "idx_media_assets_updated_at", on: "media_assets", columns: ["updated_at"], ifNotExists: true)
    }

    nonisolated static func drop(in db: Database) throws {
        try db.execute(sql: "DROP TABLE IF EXISTS media_attachments")
        try db.execute(sql: "DROP TABLE IF EXISTS media_assets")
    }
}
