import Foundation
import OSLog

/// 同步引擎协议。Feature 只触发 enqueueScan，不关心具体网络实现。
protocol SyncEngine: Sendable {
    func enqueueScan() async
    func cancelScan() async
}

extension SyncEngine { func cancelScan() async {} }

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

}

nonisolated enum SyncableTimestamp {
    static func nowMilliseconds() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}
