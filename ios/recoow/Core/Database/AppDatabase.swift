import Foundation
import GRDB

/// 应用数据库门面。Feature 层只接触 reader/writer，不直接决定数据库文件位置。
final class AppDatabase: @unchecked Sendable {
    nonisolated let dbQueue: DatabaseQueue

    nonisolated var reader: DatabaseReader { dbQueue }
    nonisolated var writer: DatabaseWriter { dbQueue }

    private init(dbQueue: DatabaseQueue) throws {
        self.dbQueue = dbQueue
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
        return try AppDatabase(dbQueue: DatabaseQueue(path: url.path(percentEncoded: false)))
    }

    static func makeInMemory() throws -> AppDatabase {
        try AppDatabase(dbQueue: DatabaseQueue())
    }
}
