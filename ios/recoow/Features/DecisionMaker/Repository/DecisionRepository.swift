import Foundation
import GRDB

/// “选什么”的数据访问层。
final class DecisionRepository: @unchecked Sendable {
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

    func saveCollection(_ collection: DecisionCollection) throws -> DecisionCollection {
        try database.writer.write { db in
            var record = collection
            let exists = try DecisionCollection.fetchOne(db, key: record.id) != nil
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

    func deleteCollection(id: String) throws {
        try database.writer.write { db in
            guard var collection = try DecisionCollection.fetchOne(db, key: id), collection.deletedAt == nil else {
                return
            }

            let deletedAt = SyncableTimestamp.nowMilliseconds()
            collection.deletedAt = deletedAt
            collection.updatedAt = deletedAt
            collection.syncStatus = .pending
            try collection.update(db)
            try appendChange(db: db, record: collection, operation: .delete)

            let options = try DecisionOption
                .filter(Column("collection_id") == id)
                .filter(Column("deleted_at") == nil)
                .fetchAll(db)

            for var option in options {
                option.deletedAt = deletedAt
                option.updatedAt = deletedAt
                option.syncStatus = .pending
                try option.update(db)
                try appendChange(db: db, record: option, operation: .delete)
            }
        }
    }

    func saveOption(_ option: DecisionOption) throws -> DecisionOption {
        try database.writer.write { db in
            var record = option
            let exists = try DecisionOption.fetchOne(db, key: record.id) != nil
            let operation: ChangeOperation = exists ? .update : .insert

            if exists {
                record.updatedAt = SyncableTimestamp.nowMilliseconds()
                record.syncStatus = .pending
                record.weight = max(1, record.weight)
                try record.update(db)
            } else {
                record.weight = max(1, record.weight)
                try record.insert(db)
            }

            try appendChange(db: db, record: record, operation: operation)
            return record
        }
    }

    func deleteOption(id: String) throws {
        try database.writer.write { db in
            guard var option = try DecisionOption.fetchOne(db, key: id), option.deletedAt == nil else {
                return
            }

            let deletedAt = SyncableTimestamp.nowMilliseconds()
            option.deletedAt = deletedAt
            option.updatedAt = deletedAt
            option.syncStatus = .pending
            try option.update(db)
            try appendChange(db: db, record: option, operation: .delete)
        }
    }

    func insertChoiceRecord(_ record: DecisionChoiceRecord) throws -> DecisionChoiceRecord {
        try database.writer.write { db in
            try record.insert(db)
            try appendChange(db: db, record: record, operation: .insert)
            return record
        }
    }

    func deleteChoiceRecords(ids: [String]) throws {
        guard ids.isEmpty == false else { return }

        try database.writer.write { db in
            for id in ids {
                guard var record = try DecisionChoiceRecord.fetchOne(db, key: id), record.deletedAt == nil else {
                    continue
                }

                let deletedAt = SyncableTimestamp.nowMilliseconds()
                record.deletedAt = deletedAt
                record.updatedAt = deletedAt
                record.syncStatus = .pending

                try record.update(db)
                try appendChange(db: db, record: record, operation: .delete)
            }
        }
    }

    func fetchCollection(id: String) throws -> DecisionCollection? {
        try database.reader.read { db in
            try DecisionCollection
                .filter(Column("id") == id)
                .filter(Column("deleted_at") == nil)
                .fetchOne(db)
        }
    }

    func fetchChoiceRecord(id: String) throws -> DecisionChoiceRecord? {
        try database.reader.read { db in
            try DecisionChoiceRecord
                .filter(Column("id") == id)
                .filter(Column("deleted_at") == nil)
                .fetchOne(db)
        }
    }

    func fetchRecentChoiceRecords(limit: Int = 50) throws -> [DecisionChoiceRecord] {
        try database.reader.read { db in
            try DecisionChoiceRecord
                .filter(Column("deleted_at") == nil)
                .order(Column("selected_at").desc)
                .limit(limit)
                .fetchAll(db)
        }
    }

    func observeCollections() -> AsyncStream<Result<[DecisionCollection], Error>> {
        AsyncStream { continuation in
            let observation = ValueObservation.tracking { db in
                try DecisionCollection
                    .filter(Column("deleted_at") == nil)
                    .order(Column("updated_at").desc)
                    .fetchAll(db)
            }

            let cancellable = observation.start(
                in: database.reader,
                scheduling: .immediate,
                onError: { error in
                    continuation.yield(.failure(error))
                },
                onChange: { collections in
                    continuation.yield(.success(collections))
                }
            )

            continuation.onTermination = { @Sendable _ in
                cancellable.cancel()
            }
        }
    }

    func observeOptions(collectionID: String) -> AsyncStream<Result<[DecisionOption], Error>> {
        AsyncStream { continuation in
            let observation = ValueObservation.tracking { db in
                try DecisionOption
                    .filter(Column("collection_id") == collectionID)
                    .filter(Column("deleted_at") == nil)
                    .order(Column("updated_at").desc)
                    .fetchAll(db)
            }

            let cancellable = observation.start(
                in: database.reader,
                scheduling: .immediate,
                onError: { error in
                    continuation.yield(.failure(error))
                },
                onChange: { options in
                    continuation.yield(.success(options))
                }
            )

            continuation.onTermination = { @Sendable _ in
                cancellable.cancel()
            }
        }
    }

    func observeChoiceRecords(collectionID: String? = nil) -> AsyncStream<Result<[DecisionChoiceRecord], Error>> {
        AsyncStream { continuation in
            let observation = ValueObservation.tracking { db in
                var request = DecisionChoiceRecord
                    .filter(Column("deleted_at") == nil)

                if let collectionID {
                    request = request.filter(Column("collection_id") == collectionID)
                }

                return try request
                    .order(Column("selected_at").desc)
                    .fetchAll(db)
            }

            let cancellable = observation.start(
                in: database.reader,
                scheduling: .immediate,
                onError: { error in
                    continuation.yield(.failure(error))
                },
                onChange: { records in
                    continuation.yield(.success(records))
                }
            )

            continuation.onTermination = { @Sendable _ in
                cancellable.cancel()
            }
        }
    }

    private func appendChange(db: Database, record: DecisionCollection, operation: ChangeOperation) throws {
        try changeLogRepository.append(
            db: db,
            table: DecisionCollection.databaseTableName,
            entityID: record.id,
            operation: operation,
            payload: record,
            clientTimestampMilliseconds: record.updatedAt
        )
    }

    private func appendChange(db: Database, record: DecisionOption, operation: ChangeOperation) throws {
        try changeLogRepository.append(
            db: db,
            table: DecisionOption.databaseTableName,
            entityID: record.id,
            operation: operation,
            payload: record,
            clientTimestampMilliseconds: record.updatedAt
        )
    }

    private func appendChange(db: Database, record: DecisionChoiceRecord, operation: ChangeOperation) throws {
        try changeLogRepository.append(
            db: db,
            table: DecisionChoiceRecord.databaseTableName,
            entityID: record.id,
            operation: operation,
            payload: record,
            clientTimestampMilliseconds: record.updatedAt
        )
    }
}
