import Foundation
import GRDB

final class DiaryRepository: @unchecked Sendable {
    private let database: AppDatabase
    private let changeLogRepository: ChangeLogRepository
    private let mediaAttachmentRepository: MediaAttachmentRepository
    private let deviceIdentifier: DeviceIdentifier

    init(
        database: AppDatabase,
        changeLogRepository: ChangeLogRepository,
        mediaAttachmentRepository: MediaAttachmentRepository,
        deviceIdentifier: DeviceIdentifier
    ) {
        self.database = database
        self.changeLogRepository = changeLogRepository
        self.mediaAttachmentRepository = mediaAttachmentRepository
        self.deviceIdentifier = deviceIdentifier
    }

    var deviceID: String {
        deviceIdentifier.value
    }

    func saveTag(_ tag: DiaryTag) throws -> DiaryTag {
        try database.writer.write { db in
            var record = tag
            let exists = try DiaryTag.fetchOne(db, key: record.id) != nil
            let operation: ChangeOperation = exists ? .update : .insert

            if exists {
                record.updatedAt = SyncableTimestamp.nowMilliseconds()
                record.syncStatus = .pending
                try record.update(db)
            } else {
                try record.insert(db)
            }

            try appendTagChange(db: db, record: record, operation: operation)
            return record
        }
    }

    func deleteTag(id: String) throws {
        try database.writer.write { db in
            guard var tag = try DiaryTag.fetchOne(db, key: id), tag.deletedAt == nil else {
                return
            }

            let deletedAt = SyncableTimestamp.nowMilliseconds()
            tag.deletedAt = deletedAt
            tag.updatedAt = deletedAt
            tag.syncStatus = .pending
            try tag.update(db)
            try appendTagChange(db: db, record: tag, operation: .delete)
        }
    }

    func saveEntry(
        _ entry: DiaryEntry,
        links: [DiaryLink],
        attachments: [MediaAttachment]
    ) throws -> DiaryEntryDetail {
        try database.writer.write { db in
            var record = entry
            let exists = try DiaryEntry.fetchOne(db, key: record.id) != nil
            let operation: ChangeOperation = exists ? .update : .insert
            let now = SyncableTimestamp.nowMilliseconds()

            if exists {
                record.updatedAt = now
                record.syncStatus = .pending
                try record.update(db)
            } else {
                try record.insert(db)
            }

            try appendEntryChange(db: db, record: record, operation: operation)
            let savedLinks = try reconcileLinks(db: db, diaryID: record.id, links: links, timestamp: now)
            let savedAttachments = try mediaAttachmentRepository.reconcileAttachments(
                db: db,
                ownerType: .diary,
                ownerID: record.id,
                attachments: attachments,
                timestamp: now
            )

            return DiaryEntryDetail(entry: record, links: savedLinks, attachments: savedAttachments)
        }
    }

    func deleteEntries(ids: [String]) throws {
        guard ids.isEmpty == false else { return }

        try database.writer.write { db in
            for id in ids {
                guard var entry = try DiaryEntry.fetchOne(db, key: id), entry.deletedAt == nil else {
                    continue
                }

                let deletedAt = SyncableTimestamp.nowMilliseconds()
                entry.deletedAt = deletedAt
                entry.updatedAt = deletedAt
                entry.syncStatus = .pending

                try entry.update(db)
                try appendEntryChange(db: db, record: entry, operation: .delete)

                let links = try DiaryLink
                    .filter(Column("diary_id") == id)
                    .filter(Column("deleted_at") == nil)
                    .fetchAll(db)

                for var link in links {
                    link.deletedAt = deletedAt
                    link.updatedAt = deletedAt
                    link.syncStatus = .pending

                    try link.update(db)
                    try appendLinkChange(db: db, record: link, operation: .delete)
                }

                try mediaAttachmentRepository.markAttachmentsDeleted(
                    db: db,
                    ownerType: .diary,
                    ownerID: id,
                    timestamp: deletedAt
                )
            }
        }
    }

    func fetchEntry(id: String) throws -> DiaryEntryDetail? {
        try database.reader.read { db in
            guard let entry = try DiaryEntry
                .filter(Column("id") == id)
                .filter(Column("deleted_at") == nil)
                .fetchOne(db) else {
                return nil
            }

            return DiaryEntryDetail(
                entry: entry,
                links: try fetchLinks(db: db, diaryID: id),
                attachments: try mediaAttachmentRepository.fetchAttachments(db: db, ownerType: .diary, ownerID: id)
            )
        }
    }

    func fetchAttachments(diaryIDs: [String]) throws -> [String: [MediaAttachment]] {
        try mediaAttachmentRepository.fetchAttachments(ownerType: .diary, ownerIDs: diaryIDs)
    }

    func fetchTags() throws -> [DiaryTag] {
        try database.reader.read { db in
            try DiaryTag
                .filter(Column("deleted_at") == nil)
                .order(Column("name").asc)
                .fetchAll(db)
        }
    }

    func observeTags() -> AsyncStream<Result<[DiaryTag], Error>> {
        AsyncStream { continuation in
            let observation = ValueObservation.tracking { db in
                try DiaryTag
                    .filter(Column("deleted_at") == nil)
                    .order(Column("name").asc)
                    .fetchAll(db)
            }

            let cancellable = observation.start(
                in: database.reader,
                scheduling: .immediate,
                onError: { error in
                    continuation.yield(.failure(error))
                },
                onChange: { tags in
                    continuation.yield(.success(tags))
                }
            )

            continuation.onTermination = { @Sendable _ in
                cancellable.cancel()
            }
        }
    }

    func fetchLinks(diaryIDs: [String]) throws -> [String: [DiaryLink]] {
        guard diaryIDs.isEmpty == false else { return [:] }

        return try database.reader.read { db in
            let links = try DiaryLink
                .filter(diaryIDs.contains(Column("diary_id")))
                .filter(Column("deleted_at") == nil)
                .order(Column("created_at").asc)
                .fetchAll(db)

            return Dictionary(grouping: links, by: \.diaryID)
        }
    }

    func observeEntries() -> AsyncStream<Result<[DiaryEntry], Error>> {
        AsyncStream { continuation in
            let observation = ValueObservation.tracking { db in
                try DiaryEntry
                    .filter(Column("deleted_at") == nil)
                    .order(Column("occurred_at").desc, Column("id").desc)
                    .fetchAll(db)
            }

            let cancellable = observation.start(
                in: database.reader,
                scheduling: .immediate,
                onError: { error in
                    continuation.yield(.failure(error))
                },
                onChange: { entries in
                    continuation.yield(.success(entries))
                }
            )

            continuation.onTermination = { @Sendable _ in
                cancellable.cancel()
            }
        }
    }

    private func reconcileLinks(
        db: Database,
        diaryID: String,
        links: [DiaryLink],
        timestamp: Int64
    ) throws -> [DiaryLink] {
        let existingLinks = try DiaryLink
            .filter(Column("diary_id") == diaryID)
            .filter(Column("deleted_at") == nil)
            .fetchAll(db)
        let existingBySource = Dictionary(uniqueKeysWithValues: existingLinks.map { ($0.sourceKey, $0) })
        let incomingLinks = links.map { link -> DiaryLink in
            var normalized = link
            normalized.diaryID = diaryID
            return normalized
        }
        let incomingKeys = Set(incomingLinks.map(\.sourceKey))
        var savedLinks: [DiaryLink] = []

        for incoming in incomingLinks {
            if var existing = existingBySource[incoming.sourceKey] {
                existing.sourceTitle = incoming.sourceTitle
                existing.sourceSubtitle = incoming.sourceSubtitle
                existing.sourceIcon = incoming.sourceIcon
                existing.sourceOccurredAt = incoming.sourceOccurredAt
                existing.snapshotJSON = incoming.snapshotJSON
                existing.updatedAt = timestamp
                existing.syncStatus = .pending

                try existing.update(db)
                try appendLinkChange(db: db, record: existing, operation: .update)
                savedLinks.append(existing)
            } else {
                var newLink = incoming
                newLink.updatedAt = timestamp
                newLink.syncStatus = .pending

                try newLink.insert(db)
                try appendLinkChange(db: db, record: newLink, operation: .insert)
                savedLinks.append(newLink)
            }
        }

        for var existing in existingLinks where incomingKeys.contains(existing.sourceKey) == false {
            existing.deletedAt = timestamp
            existing.updatedAt = timestamp
            existing.syncStatus = .pending

            try existing.update(db)
            try appendLinkChange(db: db, record: existing, operation: .delete)
        }

        return savedLinks.sorted { $0.createdAt < $1.createdAt }
    }

    private func fetchLinks(db: Database, diaryID: String) throws -> [DiaryLink] {
        try DiaryLink
            .filter(Column("diary_id") == diaryID)
            .filter(Column("deleted_at") == nil)
            .order(Column("created_at").asc)
            .fetchAll(db)
    }

    private func appendEntryChange(db: Database, record: DiaryEntry, operation: ChangeOperation) throws {
        try changeLogRepository.append(
            db: db,
            table: DiaryEntry.databaseTableName,
            entityID: record.id,
            operation: operation,
            payload: record,
            clientTimestampMilliseconds: record.updatedAt
        )
    }

    private func appendTagChange(db: Database, record: DiaryTag, operation: ChangeOperation) throws {
        try changeLogRepository.append(
            db: db,
            table: DiaryTag.databaseTableName,
            entityID: record.id,
            operation: operation,
            payload: record,
            clientTimestampMilliseconds: record.updatedAt
        )
    }

    private func appendLinkChange(db: Database, record: DiaryLink, operation: ChangeOperation) throws {
        try changeLogRepository.append(
            db: db,
            table: DiaryLink.databaseTableName,
            entityID: record.id,
            operation: operation,
            payload: record,
            clientTimestampMilliseconds: record.updatedAt
        )
    }
}
