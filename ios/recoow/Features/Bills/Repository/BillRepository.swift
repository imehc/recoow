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
            let existingBill = try BillRecord.fetchOne(db, key: record.id)
            let operation: ChangeOperation = existingBill == nil ? .insert : .update

            if existingBill != nil {
                record.updatedAt = SyncableTimestamp.nowMilliseconds()
                record.syncStatus = .pending
                try record.update(db)
            } else {
                try record.insert(db)
            }

            try applyStoredValueBalanceChange(db: db, oldBill: existingBill, newBill: record)
            try appendChange(db: db, record: record, operation: operation)
            return record
        }
    }

    func saveStoredValueAccount(_ account: StoredValueAccount) throws -> StoredValueAccount {
        try database.writer.write { db in
            var record = account
            let exists = try StoredValueAccount.fetchOne(db, key: record.id) != nil
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

    func deleteStoredValueAccount(id: String) throws {
        try database.writer.write { db in
            let referencedCount = try BillRecord
                .filter(Column("deleted_at") == nil)
                .filter(Column("stored_value_account_id") == id)
                .fetchCount(db)

            guard referencedCount == 0 else {
                throw NSError(
                    domain: "BillRepository",
                    code: 1,
                    userInfo: [
                        NSLocalizedDescriptionKey: AppLocalization.string("储值账户已被账单使用，不能删除。")
                    ]
                )
            }

            guard var account = try StoredValueAccount.fetchOne(db, key: id),
                  account.deletedAt == nil else {
                return
            }

            let deletedAt = SyncableTimestamp.nowMilliseconds()
            account.deletedAt = deletedAt
            account.updatedAt = deletedAt
            account.syncStatus = .pending

            try account.update(db)
            try appendChange(db: db, record: account, operation: .delete)
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
                let oldBill = bill
                bill.deletedAt = deletedAt
                bill.updatedAt = deletedAt
                bill.syncStatus = .pending

                try bill.update(db)
                try applyStoredValueBalanceChange(db: db, oldBill: oldBill, newBill: bill)
                try appendChange(db: db, record: bill, operation: .delete)
            }
        }
    }

    func fetchStoredValueAccounts() throws -> [StoredValueAccount] {
        try database.reader.read { db in
            try StoredValueAccount
                .filter(Column("deleted_at") == nil)
                .order(Column("name").asc)
                .fetchAll(db)
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

    func fetchRecentBills(limit: Int = 50) throws -> [BillRecord] {
        try database.reader.read { db in
            try BillRecord
                .filter(Column("deleted_at") == nil)
                .order(Column("occurred_at").desc, Column("id").desc)
                .limit(limit)
                .fetchAll(db)
        }
    }

    func fetchBills(from startedAt: Int64, to endedAt: Int64) throws -> [BillRecord] {
        try database.reader.read { db in
            try BillRecord
                .filter(Column("deleted_at") == nil)
                .filter(Column("occurred_at") >= startedAt)
                .filter(Column("occurred_at") <= endedAt)
                .order(Column("occurred_at").asc, Column("id").asc)
                .fetchAll(db)
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

    func observeStoredValueAccounts() -> AsyncStream<Result<[StoredValueAccount], Error>> {
        AsyncStream { continuation in
            let observation = ValueObservation.tracking { db in
                try StoredValueAccount
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
                onChange: { accounts in
                    continuation.yield(.success(accounts))
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

    private func appendChange(db: Database, record: StoredValueAccount, operation: ChangeOperation) throws {
        try changeLogRepository.append(
            db: db,
            table: StoredValueAccount.databaseTableName,
            entityID: record.id,
            operation: operation,
            payload: record,
            clientTimestampMilliseconds: record.updatedAt
        )
    }

    private func applyStoredValueBalanceChange(db: Database, oldBill: BillRecord?, newBill: BillRecord) throws {
        var deltasByAccountID: [String: Int64] = [:]

        if let oldBill,
           let accountID = oldBill.storedValueAccountID {
            deltasByAccountID[accountID, default: 0] -= oldBill.storedValueBalanceDeltaCents
        }

        if let accountID = newBill.storedValueAccountID {
            deltasByAccountID[accountID, default: 0] += newBill.storedValueBalanceDeltaCents
        }

        for (accountID, deltaCents) in deltasByAccountID where deltaCents != 0 {
            guard var account = try StoredValueAccount.fetchOne(db, key: accountID),
                  account.deletedAt == nil else {
                continue
            }

            account.balanceCents += deltaCents
            account.updatedAt = SyncableTimestamp.nowMilliseconds()
            account.syncStatus = .pending
            try account.update(db)
            try appendChange(db: db, record: account, operation: .update)
        }
    }
}
