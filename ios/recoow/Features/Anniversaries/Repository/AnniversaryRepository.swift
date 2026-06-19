import Foundation
import GRDB

/// “纪念日”的数据访问层。
final class AnniversaryRepository: @unchecked Sendable {
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

    func saveAnniversary(_ anniversary: AnniversaryRecord) throws -> AnniversaryRecord {
        try database.writer.write { db in
            var record = anniversary
            let exists = try AnniversaryRecord.fetchOne(db, key: record.id) != nil
            let operation: ChangeOperation = exists ? .update : .insert

            if exists {
                record.updatedAt = SyncableTimestamp.nowMilliseconds()
                record.syncStatus = .pending
                try record.update(db)
            } else {
                try record.insert(db)
            }

            try appendChange(db: db, record: record, operation: operation)
            return record
        }
    }

    func deleteAnniversary(id: String) throws {
        try deleteAnniversaries(ids: [id])
    }

    func deleteAnniversaries(ids: [String]) throws {
        guard ids.isEmpty == false else { return }

        try database.writer.write { db in
            for id in ids {
                guard var anniversary = try AnniversaryRecord.fetchOne(db, key: id),
                      anniversary.deletedAt == nil else {
                    continue
                }

                let deletedAt = SyncableTimestamp.nowMilliseconds()
                anniversary.deletedAt = deletedAt
                anniversary.updatedAt = deletedAt
                anniversary.syncStatus = .pending
                anniversary.isEnabled = false

                try anniversary.update(db)
                try appendChange(db: db, record: anniversary, operation: .delete)
            }
        }
    }

    func fetchAnniversary(id: String) throws -> AnniversaryRecord? {
        try database.reader.read { db in
            try AnniversaryRecord
                .filter(Column("id") == id)
                .filter(Column("deleted_at") == nil)
                .fetchOne(db)
        }
    }

    func observeAnniversaries() -> AsyncStream<Result<[AnniversaryRecord], Error>> {
        AsyncStream { continuation in
            let observation = ValueObservation.tracking { db in
                try AnniversaryRecord
                    .filter(Column("deleted_at") == nil)
                    .order(Column("occurred_at").desc)
                    .fetchAll(db)
            }

            let cancellable = observation.start(
                in: database.reader,
                scheduling: .immediate,
                onError: { error in
                    continuation.yield(.failure(error))
                },
                onChange: { anniversaries in
                    continuation.yield(.success(anniversaries))
                }
            )

            continuation.onTermination = { @Sendable _ in
                cancellable.cancel()
            }
        }
    }

    private func appendChange(db: Database, record: AnniversaryRecord, operation: ChangeOperation) throws {
        try changeLogRepository.append(
            db: db,
            table: AnniversaryRecord.databaseTableName,
            entityID: record.id,
            operation: operation,
            payload: record,
            clientTimestampMilliseconds: record.updatedAt
        )
    }
}
