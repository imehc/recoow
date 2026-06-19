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
            let exists = try ReminderRecord.fetchOne(db, key: record.id) != nil
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

    func fetchReminder(id: String) throws -> ReminderRecord? {
        try database.reader.read { db in
            try ReminderRecord
                .filter(Column("id") == id)
                .filter(Column("deleted_at") == nil)
                .fetchOne(db)
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
}
