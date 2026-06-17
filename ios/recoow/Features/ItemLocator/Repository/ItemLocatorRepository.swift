import Foundation
import GRDB

/// “在哪里”的数据访问层。
final class ItemLocatorRepository: @unchecked Sendable {
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

    func saveCategory(_ category: ItemCategory) throws -> ItemCategory {
        try database.writer.write { db in
            var record = category
            let exists = try ItemCategory.fetchOne(db, key: record.id) != nil
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

    func deleteCategory(id: String) throws {
        try database.writer.write { db in
            guard var category = try ItemCategory.fetchOne(db, key: id), category.deletedAt == nil else {
                return
            }

            let deletedAt = SyncableTimestamp.nowMilliseconds()
            category.deletedAt = deletedAt
            category.updatedAt = deletedAt
            category.syncStatus = .pending
            try category.update(db)
            try appendChange(db: db, record: category, operation: .delete)
        }
    }

    func saveItem(_ item: StoredItem) throws -> StoredItem {
        try database.writer.write { db in
            var record = item
            let exists = try StoredItem.fetchOne(db, key: record.id) != nil
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

    func deleteItem(id: String) throws {
        try database.writer.write { db in
            guard var item = try StoredItem.fetchOne(db, key: id), item.deletedAt == nil else {
                return
            }

            let deletedAt = SyncableTimestamp.nowMilliseconds()
            item.deletedAt = deletedAt
            item.updatedAt = deletedAt
            item.syncStatus = .pending
            try item.update(db)
            try appendChange(db: db, record: item, operation: .delete)
        }
    }

    func fetchItem(id: String) throws -> StoredItem? {
        try database.reader.read { db in
            try StoredItem
                .filter(Column("id") == id)
                .filter(Column("deleted_at") == nil)
                .fetchOne(db)
        }
    }

    func observeCategories() -> AsyncStream<Result<[ItemCategory], Error>> {
        AsyncStream { continuation in
            let observation = ValueObservation.tracking { db in
                try ItemCategory
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
                onChange: { categories in
                    continuation.yield(.success(categories))
                }
            )

            continuation.onTermination = { @Sendable _ in
                cancellable.cancel()
            }
        }
    }

    func observeItems() -> AsyncStream<Result<[StoredItem], Error>> {
        AsyncStream { continuation in
            let observation = ValueObservation.tracking { db in
                try StoredItem
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
                onChange: { items in
                    continuation.yield(.success(items))
                }
            )

            continuation.onTermination = { @Sendable _ in
                cancellable.cancel()
            }
        }
    }

    private func appendChange(db: Database, record: ItemCategory, operation: ChangeOperation) throws {
        try changeLogRepository.append(
            db: db,
            table: ItemCategory.databaseTableName,
            entityID: record.id,
            operation: operation,
            payload: record,
            clientTimestampMilliseconds: record.updatedAt
        )
    }

    private func appendChange(db: Database, record: StoredItem, operation: ChangeOperation) throws {
        try changeLogRepository.append(
            db: db,
            table: StoredItem.databaseTableName,
            entityID: record.id,
            operation: operation,
            payload: record,
            clientTimestampMilliseconds: record.updatedAt
        )
    }
}
