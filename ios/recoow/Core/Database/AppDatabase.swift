import Foundation
import GRDB

/// 应用数据库门面。Feature 层只接触 reader/writer，不直接决定数据库文件位置。
final class AppDatabase: @unchecked Sendable {
    nonisolated let dbQueue: DatabaseQueue
    nonisolated let databaseURL: URL?

    nonisolated var reader: DatabaseReader { dbQueue }
    nonisolated var writer: DatabaseWriter { dbQueue }

    private init(dbQueue: DatabaseQueue, databaseURL: URL? = nil) throws {
        self.dbQueue = dbQueue
        self.databaseURL = databaseURL
        try AppMigrator.migrate(dbQueue)
    }

    static func makeDefault() throws -> AppDatabase {
        let fileManager = FileManager.default
        let appSupport = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let directory = appSupport.appending(path: "Database", directoryHint: .isDirectory)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appending(path: "recoow.sqlite")
        // SQLite 需要真实文件系统路径，不能使用带 %20 的 percent-encoded 路径。
        return try AppDatabase(
            dbQueue: DatabaseQueue(path: url.path(percentEncoded: false)),
            databaseURL: url
        )
    }

    static func makeInMemory() throws -> AppDatabase {
        try AppDatabase(dbQueue: DatabaseQueue())
    }

    nonisolated func migrate() throws {
        try AppMigrator.migrate(dbQueue)
    }

    nonisolated func backup(to destinationURL: URL) throws {
        let fileManager = FileManager.default
        let directory = destinationURL.deletingLastPathComponent()
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)

        if fileManager.fileExists(atPath: destinationURL.path(percentEncoded: false)) {
            try fileManager.removeItem(at: destinationURL)
        }

        let destination = try DatabaseQueue(path: destinationURL.path(percentEncoded: false))
        defer {
            try? destination.close()
        }

        try dbQueue.backup(to: destination)
    }

    nonisolated func restore(from sourceURL: URL) throws {
        let source = try DatabaseQueue(path: sourceURL.path(percentEncoded: false))
        defer {
            try? source.close()
        }

        try source.backup(to: dbQueue)
    }

    nonisolated func close() throws {
        try dbQueue.close()
    }

    nonisolated func verifyIntegrity() throws {
        try reader.read { db in
            let result = try String.fetchOne(db, sql: "PRAGMA integrity_check") ?? ""
            guard result == "ok" else {
                throw DatabaseError(message: "SQLite integrity check failed: \(result)")
            }
        }
    }
}
