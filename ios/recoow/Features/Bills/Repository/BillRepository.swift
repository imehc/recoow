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
            try prepareBalanceEffects(db: db, previous: existingBill, record: &record)

            if existingBill != nil {
                record.updatedAt = SyncableTimestamp.nowMilliseconds()
                record.syncStatus = .pending
                try record.update(db)
            } else {
                try record.insert(db)
            }

            try SyncAggregateProjection.refreshBalances(db: db)
            try appendChange(db: db, record: record, operation: operation)
            return record
        }
    }

    func saveStoredValueAccount(_ account: StoredValueAccount) throws -> StoredValueAccount {
        try database.writer.write { db in
            var record = account
            let existing = try StoredValueAccount.fetchOne(db, key: record.id)
            let exists = existing != nil
            let operation: ChangeOperation = exists ? .update : .insert

            if exists {
                record.updatedAt = SyncableTimestamp.nowMilliseconds()
                record.syncStatus = .pending
                try record.update(db)
            } else {
                try record.insert(db)
            }

            try appendChange(db: db, record: record, operation: operation)
            let adjustment = record.balanceCents.subtractingReportingOverflow(existing?.balanceCents ?? 0)
            guard !adjustment.overflow else { throw CloudAPIError.syncRejected("储值调整金额超出范围") }
            if !exists || adjustment.partialValue != 0 {
                let entry = StoredValueAdjustment(
                    id: exists ? UUID().uuidString : "opening:" + record.id,
                    createdAt: record.updatedAt, updatedAt: record.updatedAt, deletedAt: nil, syncStatus: .pending,
                    deviceID: deviceIdentifier.value, serverVersion: nil, accountID: record.id,
                    amountCents: adjustment.partialValue, reason: exists ? "manual" : "opening"
                )
                try entry.insert(db)
                try changeLogRepository.append(db: db, table: StoredValueAdjustment.databaseTableName, entityID: entry.id,
                                               operation: .insert, payload: entry, clientTimestampMilliseconds: entry.updatedAt)
            }
            try SyncAggregateProjection.refreshBalances(db: db)
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
                bill.deletedAt = deletedAt
                bill.updatedAt = deletedAt
                bill.syncStatus = .pending

                let previous = try BillRecord.fetchOne(db, key: bill.id)
                try prepareBalanceEffects(db: db, previous: previous, record: &bill)
                try bill.update(db)
                try SyncAggregateProjection.refreshBalances(db: db)
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

    private func prepareBalanceEffects(db: Database, previous: BillRecord?, record: inout BillRecord) throws {
        var effects = try StoredValueBalanceEffects.decode(previous?.storedValueEffectsRawValue)
        var deltas: [String: Int64] = [:]
        if let previous, let accountID = previous.storedValueAccountID {
            let delta = Int64.zero.subtractingReportingOverflow(previous.storedValueBalanceDeltaCents)
            guard !delta.overflow else { throw CloudAPIError.syncRejected("储值余额超出范围") }
            try StoredValueBalanceEffects.adding(delta.partialValue, accountID: accountID, to: &deltas)
        }
        if let accountID = record.storedValueAccountID {
            try StoredValueBalanceEffects.adding(record.storedValueBalanceDeltaCents, accountID: accountID, to: &deltas)
        }
        for (accountID, amount) in deltas {
            guard let account = try StoredValueAccount.fetchOne(db, key: accountID), account.deletedAt == nil else { continue }
            try StoredValueBalanceEffects.adding(amount, accountID: accountID, to: &effects)
        }
        record.storedValueEffectsRawValue = try StoredValueBalanceEffects.encode(effects)
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

    // MARK: - 远端回写（pull）

    /// 把一条服务端拉取到的变更按 LWW 合并进本地，返回是否真的采纳了远端。
    func applyRemoteChange(db: Database, update: RemoteRowUpdate) throws -> Bool {
        switch update.table {
        case StoredValueAccount.databaseTableName:
            return try applyRemoteStoredValueAccount(db: db, update: update)
        case BillRecord.databaseTableName:
            return try applyRemoteBill(db: db, update: update)
        default:
            throw CloudAPIError.invalidResponse("账单仓库不支持实体 \(update.table)")
        }
    }

    private func applyRemoteStoredValueAccount(db: Database, update: RemoteRowUpdate) throws -> Bool {
        let existing = try StoredValueAccount.fetchOne(db, key: update.id)
        if let existing, try changeLogRepository.remoteWins(db: db, update: update, over: existing) == false {
            return false
        }
        var record: StoredValueAccount
        if update.isDeleted {
            guard let existing else { return false }
            record = existing
        } else {
            record = try update.decodeRecord(StoredValueAccount.self)
        }
        record.updatedAt = update.updatedAtMs
        record.deletedAt = update.deletedAtMs
        record.syncStatus = .synced
        record.serverVersion = update.version
        record.deviceID = update.deviceID

        if existing == nil {
            try record.insert(db)
        } else {
            try record.update(db)
        }
        try changeLogRepository.resolvePending(
            db: db, table: update.table, entityID: update.id, serverVersion: update.version
        )
        return true
    }

    private func applyRemoteBill(db: Database, update: RemoteRowUpdate) throws -> Bool {
        let existing = try BillRecord.fetchOne(db, key: update.id)
        if let existing, try changeLogRepository.remoteWins(db: db, update: update, over: existing) == false {
            return false
        }
        var record: BillRecord
        if update.isDeleted {
            guard let existing else { return false }
            record = existing
        } else {
            record = try update.decodeRecord(BillRecord.self)
            record.imageData = existing?.imageData
        }
        record.updatedAt = update.updatedAtMs
        record.deletedAt = update.deletedAtMs
        record.syncStatus = .synced
        record.serverVersion = update.version
        record.deviceID = update.deviceID

        if existing == nil {
            try record.insert(db)
        } else {
            try record.update(db)
        }
        try changeLogRepository.resolvePending(
            db: db, table: update.table, entityID: update.id, serverVersion: update.version
        )
        return true
    }
}
