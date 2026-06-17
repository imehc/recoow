import Foundation
import GRDB

/// 本地同步状态。0 表示已进入 outbox，等待未来服务端同步。
enum SyncStatus: Int, Codable, Sendable {
    case pending = 0
    case synced = 1
    case conflicted = 2
}

/// 所有可同步业务表共享的元数据契约。
protocol SyncableRecord {
    var id: String { get }
    var createdAt: Int64 { get }
    var updatedAt: Int64 { get set }
    var deletedAt: Int64? { get set }
    var syncStatus: SyncStatus { get set }
    var deviceID: String { get }
    var serverVersion: Int64? { get set }
}

extension SyncableRecord {
    static func nowMilliseconds() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}

extension TableDefinition {
    /// 一行式声明同步列。建新表时复用该方法，避免不同模块 schema 漂移。
    func syncMetadata() {
        column("id", .text).notNull().primaryKey()
        // SQLite 允许表达式默认值，但表达式必须包在外层括号中。
        column("created_at", .integer).notNull().defaults(sql: "(CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER))")
        column("updated_at", .integer).notNull().defaults(sql: "(CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER))")
        column("deleted_at", .integer)
        column("sync_status", .integer).notNull().defaults(to: SyncStatus.pending.rawValue)
        column("device_id", .text).notNull()
        column("server_version", .integer)
    }
}
