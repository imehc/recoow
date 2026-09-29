import Foundation
import GRDB

/// “打卡”的数据访问层，记录优先保存在本地，再按需安排系统通知。
final class ReminderRepository: @unchecked Sendable {
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

    func saveReminder(_ reminder: ReminderRecord) throws -> ReminderRecord {
        try database.writer.write { db in
            var record = reminder
            let previous = try ReminderRecord.fetchOne(db, key: record.id)
            let exists = previous != nil
            let operation: ChangeOperation = exists ? .update : .insert

            if exists {
                record.updatedAt = SyncableTimestamp.nowMilliseconds()
                record.syncStatus = .pending
                try record.update(db)
            } else {
                try record.insert(db)
            }

            if self.hasAuthoredChanges(previous: previous, current: record) {
                try self.appendChange(db: db, record: record, operation: operation)
            }
            try self.synchronizeCompletions(db: db, previous: previous, current: record)
            return record
        }
    }

    func deleteReminders(ids: [String]) throws {
        guard ids.isEmpty == false else { return }

        try database.writer.write { db in
            for id in ids {
                guard var reminder = try ReminderRecord.fetchOne(db, key: id), reminder.deletedAt == nil else {
                    continue
                }

                let deletedAt = SyncableTimestamp.nowMilliseconds()
                reminder.deletedAt = deletedAt
                reminder.updatedAt = deletedAt
                reminder.syncStatus = .pending
                reminder.isEnabled = false

                try reminder.update(db)
                try appendChange(db: db, record: reminder, operation: .delete)
            }
        }
    }

    func deleteCompletionRecords(_ targets: [ReminderCompletionDeletionTarget]) throws -> [ReminderRecord] {
        guard targets.isEmpty == false else { return [] }

        let targetsByReminderID = Dictionary(grouping: targets, by: \.reminderID)

        return try database.writer.write { db in
            var savedReminders: [ReminderRecord] = []

            for (reminderID, targets) in targetsByReminderID {
                guard var reminder = try ReminderRecord.fetchOne(db, key: reminderID), reminder.deletedAt == nil else {
                    continue
                }

                let dateKeys = Set(targets.map(\.dateKey))
                let previous = reminder
                var didChange = false

                for dateKey in dateKeys {
                    didChange = reminder.clearOccurrenceCompletion(dateKey: dateKey) || didChange
                }

                guard didChange else { continue }

                reminder.updatedAt = SyncableTimestamp.nowMilliseconds()
                reminder.syncStatus = .pending

                try reminder.update(db)
                try synchronizeCompletions(db: db, previous: previous, current: reminder)
                savedReminders.append(reminder)
            }

            return savedReminders
        }
    }

    func fetchReminder(id: String) throws -> ReminderRecord? {
        try database.reader.read { db in
            try ReminderRecord
                .filter(Column("id") == id)
                .filter(Column("deleted_at") == nil)
                .fetchOne(db)
        }
    }

    func fetchRecentReminders(limit: Int = 50) throws -> [ReminderRecord] {
        try database.reader.read { db in
            try ReminderRecord
                .filter(Column("deleted_at") == nil)
                .order(Column("scheduled_at").desc)
                .limit(limit)
                .fetchAll(db)
        }
    }

    func observeReminders() -> AsyncStream<Result<[ReminderRecord], Error>> {
        AsyncStream { continuation in
            let observation = ValueObservation.tracking { db in
                try ReminderRecord
                    .filter(Column("deleted_at") == nil)
                    .order(Column("scheduled_at").desc)
                    .fetchAll(db)
            }

            let cancellable = observation.start(
                in: database.reader,
                scheduling: .immediate,
                onError: { error in
                    continuation.yield(.failure(error))
                },
                onChange: { reminders in
                    continuation.yield(.success(reminders))
                }
            )

            continuation.onTermination = { @Sendable _ in
                cancellable.cancel()
            }
        }
    }

    func observeRemindersForStatistics() -> AsyncStream<Result<[ReminderRecord], Error>> {
        AsyncStream { continuation in
            let observation = ValueObservation.tracking { db in
                try ReminderRecord
                    .filter(Column("deleted_at") == nil)
                    .order(Column("scheduled_at").desc)
                    .selectingAllColumns(except: ["image_data"], db: db)
                    .fetchAll(db)
            }

            let cancellable = observation.start(
                in: database.reader,
                scheduling: .immediate,
                onError: { error in
                    continuation.yield(.failure(error))
                },
                onChange: { reminders in
                    continuation.yield(.success(reminders))
                }
            )

            continuation.onTermination = { @Sendable _ in
                cancellable.cancel()
            }
        }
    }

    private func synchronizeCompletions(db: Database, previous: ReminderRecord?, current: ReminderRecord) throws {
        let before = Dictionary((previous?.completionRecords ?? []).map { ($0.dateKey, $0) }, uniquingKeysWith: { _, latest in latest })
        let after = Dictionary(current.completionRecords.map { ($0.dateKey, $0) }, uniquingKeysWith: { _, latest in latest })
        for dateKey in Set(before.keys).union(after.keys) where before[dateKey] != after[dateKey] {
            let identifier = current.id + ":" + dateKey
            let existing = try ReminderCompletionRecord.fetchOne(db, key: identifier)
            let detail = after[dateKey] ?? before[dateKey]!
            let entry = ReminderCompletionRecord(
                id: identifier, createdAt: existing?.createdAt ?? current.updatedAt, updatedAt: current.updatedAt,
                deletedAt: after[dateKey] == nil ? current.updatedAt : nil, syncStatus: .pending,
                deviceID: deviceIdentifier.value, serverVersion: existing?.serverVersion,
                reminderID: current.id, dateKey: dateKey, completedAt: detail.completedAt, kind: detail.kindRawValue, note: detail.note
            )
            try entry.save(db)
            try changeLogRepository.append(db: db, table: ReminderCompletionRecord.databaseTableName, entityID: entry.id,
                                           operation: after[dateKey] == nil ? .delete : (existing == nil ? .insert : .update),
                                           payload: entry, clientTimestampMilliseconds: entry.updatedAt)
        }
    }

    private func hasAuthoredChanges(previous: ReminderRecord?, current: ReminderRecord) -> Bool {
        guard var previous else { return true }
        if previous.completionRecords != current.completionRecords {
            previous.completedAt = current.completedAt
        }
        previous.updatedAt = current.updatedAt
        previous.syncStatus = current.syncStatus
        previous.serverVersion = current.serverVersion
        previous.completionRecordsRawValue = current.completionRecordsRawValue
        previous.completedDateKeysRawValue = current.completedDateKeysRawValue
        return previous != current
    }

    private func appendChange(db: Database, record: ReminderRecord, operation: ChangeOperation) throws {
        try changeLogRepository.append(
            db: db,
            table: ReminderRecord.databaseTableName,
            entityID: record.id,
            operation: operation,
            payload: record,
            clientTimestampMilliseconds: record.updatedAt
        )
    }

    // MARK: - 远端回写（pull）

    /// 把一条服务端拉取到的 reminders 变更按 LWW 合并进本地，返回是否真的采纳了远端。
    func applyRemoteChange(db: Database, update: RemoteRowUpdate) throws -> Bool {
        guard update.table == ReminderRecord.databaseTableName else {
            throw CloudAPIError.invalidResponse("提醒仓库不支持实体 \(update.table)")
        }
        return try applyRemoteReminder(db: db, update: update)
    }

    private func applyRemoteReminder(db: Database, update: RemoteRowUpdate) throws -> Bool {
        let existing = try ReminderRecord.fetchOne(db, key: update.id)
        if let existing, try changeLogRepository.remoteWins(db: db, update: update, over: existing) == false {
            return false
        }
        var record: ReminderRecord
        if update.isDeleted {
            guard let existing else { return false }
            record = existing
        } else {
            record = try update.decodeRecord(ReminderRecord.self)
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
