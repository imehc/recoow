import GRDB

/// V14：跨模块通用媒体附件，支持图片和语音。
enum V14MediaAttachmentsSchema {
    nonisolated static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v14_media_attachments_schema") { db in
            try CurrentMediaAttachmentDatabaseSchema.create(in: db)
        }
    }
}
