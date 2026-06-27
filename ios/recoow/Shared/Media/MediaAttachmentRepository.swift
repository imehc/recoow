import Foundation
import GRDB

final class MediaAttachmentRepository: @unchecked Sendable {
    private let database: AppDatabase
    private let changeLogRepository: ChangeLogRepository
    private let deviceIdentifier: DeviceIdentifier

    init(
        database: AppDatabase,
        changeLogRepository: ChangeLogRepository,
        deviceIdentifier: DeviceIdentifier
    ) {
        self.database = database
        self.changeLogRepository = changeLogRepository
        self.deviceIdentifier = deviceIdentifier
    }

    var deviceID: String {
        deviceIdentifier.value
    }

    func fetchAttachments(ownerType: MediaAttachmentOwnerType, ownerID: String) throws -> [MediaAttachment] {
        try database.reader.read { db in
            try fetchAttachments(db: db, ownerType: ownerType, ownerID: ownerID)
        }
    }

    func fetchAttachments(ownerType: MediaAttachmentOwnerType, ownerIDs: [String]) throws -> [String: [MediaAttachment]] {
        guard ownerIDs.isEmpty == false else { return [:] }

        return try database.reader.read { db in
            try fetchAttachments(db: db, ownerType: ownerType, ownerIDs: ownerIDs)
        }
    }

    func reconcileAttachments(
        db: Database,
        ownerType: MediaAttachmentOwnerType,
        ownerID: String,
        attachments: [MediaAttachment],
        timestamp: Int64
    ) throws -> [MediaAttachment] {
        let existingAttachments = try MediaAttachment
            .filter(Column("owner_type") == ownerType.rawValue)
            .filter(Column("owner_id") == ownerID)
            .filter(Column("deleted_at") == nil)
            .fetchAll(db)
        let existingByID = Dictionary(uniqueKeysWithValues: existingAttachments.map { ($0.id, $0) })
        let incomingAttachments = attachments.enumerated().map { index, attachment in
            var normalized = attachment.normalized(ownerType: ownerType, ownerID: ownerID)
            normalized.sortOrder = index
            return normalized
        }
        let incomingIDs = Set(incomingAttachments.map(\.id))
        var savedAttachments: [MediaAttachment] = []

        for incoming in incomingAttachments {
            if var existing = existingByID[incoming.id] {
                existing.kindRawValue = incoming.kindRawValue
                existing.title = incoming.title
                existing.assetID = incoming.assetID
                existing.data = incoming.data
                existing.mimeType = incoming.mimeType
                existing.durationSeconds = incoming.durationSeconds
                existing.width = incoming.width
                existing.height = incoming.height
                existing.sortOrder = incoming.sortOrder
                existing.updatedAt = timestamp
                existing.syncStatus = .pending

                try existing.update(db)
                try appendChange(db: db, record: existing, operation: .update)
                savedAttachments.append(existing)
            } else {
                var newAttachment = incoming
                newAttachment.updatedAt = timestamp
                newAttachment.syncStatus = .pending

                try newAttachment.insert(db)
                try appendChange(db: db, record: newAttachment, operation: .insert)
                savedAttachments.append(newAttachment)
            }
        }

        for var existing in existingAttachments where incomingIDs.contains(existing.id) == false {
            existing.deletedAt = timestamp
            existing.updatedAt = timestamp
            existing.syncStatus = .pending

            try existing.update(db)
            try appendChange(db: db, record: existing, operation: .delete)
        }

        return savedAttachments.sorted(by: sortAttachments)
    }

    func markAttachmentsDeleted(
        db: Database,
        ownerType: MediaAttachmentOwnerType,
        ownerID: String,
        timestamp: Int64
    ) throws {
        let attachments = try MediaAttachment
            .filter(Column("owner_type") == ownerType.rawValue)
            .filter(Column("owner_id") == ownerID)
            .filter(Column("deleted_at") == nil)
            .fetchAll(db)

        for var attachment in attachments {
            attachment.deletedAt = timestamp
            attachment.updatedAt = timestamp
            attachment.syncStatus = .pending

            try attachment.update(db)
            try appendChange(db: db, record: attachment, operation: .delete)
        }
    }

    func fetchAttachments(
        db: Database,
        ownerType: MediaAttachmentOwnerType,
        ownerID: String
    ) throws -> [MediaAttachment] {
        try MediaAttachment
            .filter(Column("owner_type") == ownerType.rawValue)
            .filter(Column("owner_id") == ownerID)
            .filter(Column("deleted_at") == nil)
            .order(Column("sort_order").asc, Column("created_at").asc)
            .fetchAll(db)
    }

    func fetchAttachments(
        db: Database,
        ownerType: MediaAttachmentOwnerType,
        ownerIDs: [String]
    ) throws -> [String: [MediaAttachment]] {
        guard ownerIDs.isEmpty == false else { return [:] }

        let attachments = try MediaAttachment
            .filter(Column("owner_type") == ownerType.rawValue)
            .filter(ownerIDs.contains(Column("owner_id")))
            .filter(Column("deleted_at") == nil)
            .order(Column("owner_id").asc, Column("sort_order").asc, Column("created_at").asc)
            .fetchAll(db)

        return Dictionary(grouping: attachments, by: \.ownerID)
    }

    private func sortAttachments(_ lhs: MediaAttachment, _ rhs: MediaAttachment) -> Bool {
        if lhs.sortOrder != rhs.sortOrder {
            return lhs.sortOrder < rhs.sortOrder
        }

        return lhs.createdAt < rhs.createdAt
    }

    private func appendChange(db: Database, record: MediaAttachment, operation: ChangeOperation) throws {
        try changeLogRepository.append(
            db: db,
            table: MediaAttachment.databaseTableName,
            entityID: record.id,
            operation: operation,
            payload: record,
            clientTimestampMilliseconds: record.updatedAt
        )
    }
}
