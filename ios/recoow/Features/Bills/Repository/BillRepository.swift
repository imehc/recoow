import Foundation
import GRDB

/// “记一笔”的数据访问层。
final class BillRepository: @unchecked Sendable {
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

    func saveBill(_ bill: BillRecord) throws -> BillRecord {
        try database.writer.write { db in
            var record = bill
            let exists = try BillRecord.fetchOne(db, key: record.id) != nil
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

    func deleteBill(id: String) throws {
        try deleteBills(ids: [id])
    }

    func deleteBills(ids: [String]) throws {
        guard ids.isEmpty == false else { return }

        try database.writer.write { db in
            for id in ids {
                guard var bill = try BillRecord.fetchOne(db, key: id), bill.deletedAt == nil else {
                    continue
                }

                let deletedAt = SyncableTimestamp.nowMilliseconds()
                bill.deletedAt = deletedAt
                bill.updatedAt = deletedAt
                bill.syncStatus = .pending

                try bill.update(db)
                try appendChange(db: db, record: bill, operation: .delete)
            }
        }
    }

    func fetchBill(id: String) throws -> BillRecord? {
        try database.reader.read { db in
            try BillRecord
                .filter(Column("id") == id)
                .filter(Column("deleted_at") == nil)
                .fetchOne(db)
        }
    }

    func observeBills() -> AsyncStream<Result<[BillRecord], Error>> {
        AsyncStream { continuation in
            let observation = ValueObservation.tracking { db in
                try BillRecord
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
                onChange: { bills in
                    continuation.yield(.success(bills))
                }
            )

            continuation.onTermination = { @Sendable _ in
                cancellable.cancel()
            }
        }
    }

    private func appendChange(db: Database, record: BillRecord, operation: ChangeOperation) throws {
        try changeLogRepository.append(
            db: db,
            table: BillRecord.databaseTableName,
            entityID: record.id,
            operation: operation,
            payload: record,
            clientTimestampMilliseconds: record.updatedAt
        )
    }
}
