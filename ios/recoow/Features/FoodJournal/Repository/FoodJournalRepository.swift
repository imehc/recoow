import Foundation
import GRDB

final class FoodJournalRepository: @unchecked Sendable {
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

    func saveEntry(_ entry: FoodEntry, attachments: [MediaAttachment]) throws -> FoodEntryDetail {
        try database.writer.write { db in
            var record = entry
            let exists = try FoodEntry.fetchOne(db, key: record.id) != nil
            let operation: ChangeOperation = exists ? .update : .insert
            let now = SyncableTimestamp.nowMilliseconds()

            if exists {
                record.updatedAt = now
                record.syncStatus = .pending
                try record.update(db)
            } else {
                try record.insert(db)
            }

            try appendChange(db: db, record: record, operation: operation)
            let savedAttachments = try mediaAttachmentRepository.reconcileAttachments(
                db: db,
                ownerType: .foodEntry,
                ownerID: record.id,
                attachments: attachments,
                timestamp: now
            )

            return FoodEntryDetail(entry: record, attachments: savedAttachments)
        }
    }

    func deleteEntry(id: String) throws {
        try deleteEntries(ids: [id])
    }

    func deleteEntries(ids: [String]) throws {
        guard ids.isEmpty == false else { return }

        try database.writer.write { db in
            for id in ids {
                guard var entry = try FoodEntry.fetchOne(db, key: id), entry.deletedAt == nil else {
                    continue
                }

                let deletedAt = SyncableTimestamp.nowMilliseconds()
                entry.deletedAt = deletedAt
                entry.updatedAt = deletedAt
                entry.syncStatus = .pending

                try entry.update(db)
                try appendChange(db: db, record: entry, operation: .delete)
                try mediaAttachmentRepository.markAttachmentsDeleted(
                    db: db,
                    ownerType: .foodEntry,
                    ownerID: id,
                    timestamp: deletedAt
                )
            }
        }
    }

    func deleteDay(dayStartAt: Int64, entryIDs: [String]) throws {
        try database.writer.write { db in
            let deletedAt = SyncableTimestamp.nowMilliseconds()

            for id in entryIDs {
                guard var entry = try FoodEntry.fetchOne(db, key: id), entry.deletedAt == nil else {
                    continue
                }

                entry.deletedAt = deletedAt
                entry.updatedAt = deletedAt
                entry.syncStatus = .pending

                try entry.update(db)
                try appendChange(db: db, record: entry, operation: .delete)
                try mediaAttachmentRepository.markAttachmentsDeleted(
                    db: db,
                    ownerType: .foodEntry,
                    ownerID: id,
                    timestamp: deletedAt
                )
            }

            guard var dayRecord = try FoodDayRecord
                .filter(Column("day_start_at") == dayStartAt)
                .fetchOne(db),
                dayRecord.deletedAt == nil else {
                return
            }

            dayRecord.deletedAt = deletedAt
            dayRecord.updatedAt = deletedAt
            dayRecord.syncStatus = .pending

            try dayRecord.update(db)
            try appendChange(db: db, record: dayRecord, operation: .delete)
        }
    }

    func fetchEntry(id: String) throws -> FoodEntryDetail? {
        try database.reader.read { db in
            guard let entry = try FoodEntry
                .filter(Column("id") == id)
                .filter(Column("deleted_at") == nil)
                .fetchOne(db) else {
                return nil
            }

            return FoodEntryDetail(
                entry: entry,
                attachments: try mediaAttachmentRepository.fetchAttachments(db: db, ownerType: .foodEntry, ownerID: id)
            )
        }
    }

    func fetchAttachments(entryIDs: [String]) throws -> [String: [MediaAttachment]] {
        try mediaAttachmentRepository.fetchAttachments(ownerType: .foodEntry, ownerIDs: entryIDs)
    }

    func saveDayRecord(_ record: FoodDayRecord) throws -> FoodDayRecord {
        try database.writer.write { db in
            var record = record
            let exists = try FoodDayRecord.fetchOne(db, key: record.id) != nil
            let operation: ChangeOperation = exists ? .update : .insert
            let now = SyncableTimestamp.nowMilliseconds()

            record.updatedAt = now
            record.syncStatus = .pending
            record.deletedAt = nil

            if exists {
                try record.update(db)
            } else {
                try record.insert(db)
            }

            try appendChange(db: db, record: record, operation: operation)
            return record
        }
    }

    func fetchEntries(from startedAt: Int64, to endedAt: Int64) throws -> [FoodEntry] {
        try database.reader.read { db in
            try FoodEntry
                .filter(Column("deleted_at") == nil)
                .filter(Column("occurred_at") >= startedAt)
                .filter(Column("occurred_at") <= endedAt)
                .order(Column("occurred_at").asc, Column("id").asc)
                .fetchAll(db)
        }
    }

    func observeEntries() -> AsyncStream<Result<FoodJournalRepositorySnapshot, Error>> {
        AsyncStream { continuation in
            let observation = ValueObservation.tracking { db in
                let entries = try FoodEntry
                    .filter(Column("deleted_at") == nil)
                    .order(Column("occurred_at").desc, Column("id").desc)
                    .fetchAll(db)

                let dayRecords = try FoodDayRecord
                    .filter(Column("deleted_at") == nil)
                    .order(Column("day_start_at").desc)
                    .fetchAll(db)

                return FoodJournalRepositorySnapshot(entries: entries, dayRecords: dayRecords)
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

    private func appendChange(db: Database, record: FoodEntry, operation: ChangeOperation) throws {
        try changeLogRepository.append(
            db: db,
            table: FoodEntry.databaseTableName,
            entityID: record.id,
            operation: operation,
            payload: record,
            clientTimestampMilliseconds: record.updatedAt
        )
    }

    private func appendChange(db: Database, record: FoodDayRecord, operation: ChangeOperation) throws {
        try changeLogRepository.append(
            db: db,
            table: FoodDayRecord.databaseTableName,
            entityID: record.id,
            operation: operation,
            payload: record,
            clientTimestampMilliseconds: record.updatedAt
        )
    }
}

struct FoodJournalRepositorySnapshot: Sendable {
    let entries: [FoodEntry]
    let dayRecords: [FoodDayRecord]
}
