import GRDB

enum CurrentMediaAttachmentDatabaseSchema {
    static let tableNames = [
        "media_attachments"
    ]

    static func create(in db: Database) throws {
        try db.create(table: "media_attachments") { t in
            t.syncMetadata()
            t.column("owner_type", .text).notNull()
            t.column("owner_id", .text).notNull()
            t.column("kind", .text).notNull()
            t.column("title", .text)
            t.column("data", .blob).notNull()
            t.column("mime_type", .text).notNull()
            t.column("duration_seconds", .double)
            t.column("width", .integer)
            t.column("height", .integer)
            t.column("sort_order", .integer).notNull().defaults(to: 0)
        }

        try db.create(index: "idx_media_attachments_owner", on: "media_attachments", columns: ["owner_type", "owner_id"])
        try db.create(index: "idx_media_attachments_updated_at", on: "media_attachments", columns: ["updated_at"])
    }

    static func drop(in db: Database) throws {
        try db.execute(sql: "DROP TABLE IF EXISTS media_attachments")
    }
}
