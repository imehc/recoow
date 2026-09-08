import Foundation
import GRDB
import Network
import OSLog

final class RemoteSyncEngine: SyncEngine, @unchecked Sendable {
    private let repository: FullSyncRepository
    private let media: SyncMediaTransfer
    private let notifications: SyncNotificationDelivery
    private let config: RecoowSyncConfig
    private let api: RecoowAPIClient
    private let monitor = NWPathMonitor()
    private let gate = RunGate()

    private actor RunGate {
        private var running = false
        private var requested = false
        private var retry: Task<Void, Never>?

        func claim() -> Bool {
            if running { requested = true; return false }
            running = true
            retry?.cancel()
            retry = nil
            return true
        }

        func finish(repeatRequested: Bool) -> Bool {
            if requested && repeatRequested { requested = false; return true }
            requested = false
            running = false
            return false
        }

        func schedule(after delay: TimeInterval, action: @escaping @Sendable () async -> Void) {
            retry?.cancel()
            retry = Task {
                do {
                    try await Task.sleep(for: .seconds(delay))
                    await action()
                } catch {}
            }
        }
    }

    init(database: AppDatabase, config: RecoowSyncConfig, api: RecoowAPIClient, scheduler: any AppNotificationScheduling) {
        repository = FullSyncRepository(database: database)
        media = SyncMediaTransfer(database: database)
        notifications = SyncNotificationDelivery(database: database, scheduler: scheduler)
        self.config = config
        self.api = api
        monitor.pathUpdateHandler = { [weak self] path in
            guard path.status == .satisfied, let self, self.config.isSyncEnabled else { return }
            Task {
                try? await self.repository.database.writer.write { db in
                    try db.execute(sql: "UPDATE sync_targets SET next_attempt_at = 0")
                }
                await self.enqueueScan()
            }
        }
        monitor.start(queue: DispatchQueue(label: "recoow.sync.network"))
    }

    deinit { monitor.cancel() }

    func enqueueScan() async {
        guard await gate.claim() else { return }
        var again = true
        while again {
            let retryDelay = await synchronize()
            again = await gate.finish(repeatRequested: retryDelay == nil)
            if let retryDelay {
                await gate.schedule(after: retryDelay) { [weak self] in await self?.enqueueScan() }
            }
        }
    }

    private func synchronize() async -> TimeInterval? {
        guard config.isConfigured, config.isSyncEnabled, let accountID = config.accountID,
              let accessToken = repository.database.syncAccessToken else { return nil }
        let database = repository.database
        let connectionID = config.connectionID
        let client = api.frozenSession()
        let isCurrent: @Sendable () -> Bool = { [config, database] in
            config.isConfigured && config.isSyncEnabled && config.connectionID == connectionID && database.syncAccessToken == accessToken
        }
        let repository = FullSyncRepository(database: database, isCurrent: isCurrent)
        var scope: String?
        do {
            let capabilities = try await client.capabilities()
            try capabilities.validate(accountID: accountID)
            guard isCurrent() else { throw CloudAPIError.syncRejected("连接或本地数据库已改变") }
            var target = try repository.prepareTarget(connectionID: connectionID, capabilities: capabilities)
            scope = target.scope
            config.activeScope = target.scope
            let remainingDelay = Double(target.nextAttemptAt - SyncableTimestamp.nowMilliseconds()) / 1000
            if remainingDelay > 0 { return remainingDelay }
            var received = 0
            if !target.bootstrapComplete {
                received += try await receivePages(target: target, repository: repository, api: client, isCurrent: isCurrent)
            }
            var confirmed = 0
            var conflicts = 0
            var uploadFailure: Error?
            while isCurrent() {
                let pending = try repository.pending(scope: target.scope)
                if pending.isEmpty { break }
                do {
                    _ = try await media.transfer(scope: target.scope, direction: "upload", api: client, isCurrent: isCurrent)
                    let response = try await client.push(items: pending.map(\.item), generation: target.generation)
                    guard isCurrent() else { throw CloudAPIError.syncRejected("连接或本地数据库已改变，保留待确认修改") }
                    conflicts += try repository.acknowledge(scope: target.scope, pending: pending, response: response)
                    let accepted = response.results.filter { [.applied, .duplicate, .conflict].contains($0.status) }.count
                    confirmed += accepted
                    if accepted < pending.count {
                        throw CloudAPIError.syncRejected("部分变更未确认：\(response.results.filter { ![.applied, .duplicate, .conflict].contains($0.status) }.map { $0.status.rawValue }.joined(separator: ", "))")
                    }
                } catch {
                    if case CloudAPIError.badStatus(400, let message) = error, message.contains("media_not_ready") {
                        let mediaScope = target.scope
                        try await repository.database.writer.write { db in
                            try repository.requireCurrent()
                            try db.execute(sql: "UPDATE sync_media_jobs SET state = 'pending' WHERE scope = ? AND direction = 'upload'", arguments: [mediaScope])
                        }
                    }
                    uploadFailure = error
                    break
                }
                try Task.checkCancellation()
            }
            guard isCurrent() else { throw CloudAPIError.syncRejected("连接或本地数据库已改变") }
            target = try repository.target(scope: target.scope)
            received += try await receivePages(target: target, repository: repository, api: client, isCurrent: isCurrent)
            let downloaded = try await media.transfer(scope: target.scope, direction: "download", api: client, isCurrent: isCurrent)
            let blocked = try repository.unappliedCount(scope: target.scope)
            if blocked > 0 { throw CloudAPIError.syncRejected("\(blocked) 条数据仍在 inbox 等待依赖，未推进应用游标") }
            if let uploadFailure { throw uploadFailure }
            var notificationMessage = ""
            do { try await notifications.flush() } catch { notificationMessage = "；通知更新待重试：\(error.localizedDescription)" }
            try repository.markSuccess(scope: target.scope)
            let latest = try repository.target(scope: target.scope)
            config.syncCursor = latest.appliedCursor
            config.lastSyncedAt = Date()
            let unresolved = try repository.conflicts(scope: target.scope).count
            let summary = unresolved == 0 ? "同步完成" : "同步结束，存在待处理冲突"
            config.lastSyncMessage = "\(summary)：确认 \(confirmed) 条，接收 \(received) 条，媒体 \(downloaded) 个；\(unresolved) 条冲突保留本地内容，等待手动确认\(notificationMessage)"
            AppLogger.sync.info("全量同步完成，确认 \(confirmed, privacy: .public) 条，冲突 \(conflicts, privacy: .public) 条")
            return notificationMessage.isEmpty ? nil : 30
        } catch {
            guard isCurrent() else { return nil }
            config.lastSyncMessage = "同步未完成：\(error.localizedDescription)；数据保留，将自动重试"
            AppLogger.sync.error("同步失败: \(error.localizedDescription, privacy: .public)")
            if let scope {
                if case CloudAPIError.badStatus(409, let message) = error, message == "reset_required" {
                    try? await repository.database.writer.write { db in
                        try repository.requireCurrent()
                        try FullSyncRepository.reset(db: db, scope: scope, watermark: 0)
                    }
                }
                return (try? repository.markFailure(scope: scope, message: error.localizedDescription)) ?? 10
            }
            return 30
        }
    }

    private func receivePages(target: SyncTarget, repository: FullSyncRepository, api: RecoowAPIClient, isCurrent: @Sendable () -> Bool) async throws -> Int {
        var target = target
        var received = 0
        while true {
            guard isCurrent() else { throw CloudAPIError.syncRejected("连接已改变") }
            let data = try await api.fetchSyncPage(target: target)
            guard isCurrent() else { throw CloudAPIError.syncRejected("连接已改变，未应用旧响应") }
            let more = try repository.receive(data, target: target)
            received += try repository.applyInbox(scope: target.scope)
            if !more { break }
            target = try repository.target(scope: target.scope)
        }
        return received
    }
}
