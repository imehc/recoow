import Foundation
import OSLog

/// 服务端拉取结果占位。接入网络层后可扩展为按实体拆分的 delta。
struct SyncDelta: Sendable {
    var receivedAtMilliseconds: Int64
}

/// 服务端 push 确认占位。serverVersion 会回写到业务表和 change_log。
struct SyncAck: Sendable {
    var changeID: Int64
    var serverVersion: Int64?
}

/// 同步引擎协议。Feature 只触发 enqueueScan，不关心具体网络实现。
protocol SyncEngine: Sendable {
    func enqueueScan() async
    func pull() async throws -> SyncDelta
    func push(_ batch: [ChangeRecord]) async throws -> [SyncAck]
}

/// 当前阶段不发起网络请求，只验证 outbox 扫描闭环。
final class NoopSyncEngine: SyncEngine {
    private let changeLogRepository: ChangeLogRepository

    init(changeLogRepository: ChangeLogRepository) {
        self.changeLogRepository = changeLogRepository
    }

    func enqueueScan() async {
        do {
            let pending = try changeLogRepository.fetchPending()
            AppLogger.sync.info("NoopSyncEngine 扫描到 \(pending.count, privacy: .public) 条待同步记录")
        } catch {
            AppLogger.sync.error("NoopSyncEngine 扫描失败: \(error.localizedDescription, privacy: .public)")
        }
    }

    func pull() async throws -> SyncDelta {
        SyncDelta(receivedAtMilliseconds: SyncableTimestamp.nowMilliseconds())
    }

    func push(_ batch: [ChangeRecord]) async throws -> [SyncAck] {
        batch.compactMap { record in
            guard let id = record.id else { return nil }
            return SyncAck(changeID: id, serverVersion: nil)
        }
    }
}

nonisolated enum SyncableTimestamp {
    static func nowMilliseconds() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}
