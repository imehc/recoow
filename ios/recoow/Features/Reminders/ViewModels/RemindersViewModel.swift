import Foundation
import Observation
import SwiftUI

@MainActor
@Observable
final class RemindersViewModel {
    var reminders: [ReminderRecord] = []
    var errorMessage: String?
    var notificationMessage: String?
    /// 每次成功打卡自增，供视图触发成功触觉反馈。
    private(set) var checkInSuccessTick = 0
    /// 首次观察数据是否已到达；加载完成前视图显示加载态而非空状态，避免闪现「暂无打卡任务」。
    private(set) var hasLoadedReminders = false

    @ObservationIgnored private let repository: ReminderRepository
    @ObservationIgnored private let notificationService: ReminderNotificationService
    @ObservationIgnored private let syncEngine: any SyncEngine
    @ObservationIgnored private var observationTask: Task<Void, Never>?

    /// 「进行中」区当前的展示顺序（reminder id）。
    ///
    /// 打卡 / 撤销**不会**立刻重排：List 的行在「移动 + 内容同时改变（状态标签、下次时间、进度条 → 行高变化）」时
    /// 不走平滑滑动，而会退化成「旧位置收起 + 新位置展开」的两段跳。所以顺序只在进入列表时归位
    /// （`settleActiveOrder()`），打卡那一行原地变为已打卡，交互全程平滑。
    private var activeOrderSnapshot: [String] = []

    init(
        repository: ReminderRepository,
        notificationService: ReminderNotificationService,
        syncEngine: any SyncEngine
    ) {
        self.repository = repository
        self.notificationService = notificationService
        self.syncEngine = syncEngine
    }

    deinit {
        observationTask?.cancel()
    }

    var activeReminders: [ReminderRecord] {
        let active = reminders.filter { $0.isCompleted == false && $0.isPaused == false }

        guard activeOrderSnapshot.isEmpty == false else {
            return active.sorted(by: activeReminderOrder)
        }

        var rankByID: [String: Int] = [:]
        rankByID.reserveCapacity(activeOrderSnapshot.count)
        for (index, id) in activeOrderSnapshot.enumerated() {
            rankByID[id] = index
        }

        return active.sorted { lhs, rhs in
            let lhsRank = rankByID[lhs.id]
            let rhsRank = rankByID[rhs.id]

            if let lhsRank, let rhsRank {
                return lhsRank < rhsRank
            }

            if lhsRank == nil, rhsRank == nil {
                return activeReminderOrder(lhs: lhs, rhs: rhs)
            }

            // 快照之外的（新建、刚从暂停恢复）置顶，保证待打卡的任务始终在上面。
            return lhsRank == nil
        }
    }

    var pausedReminders: [ReminderRecord] {
        reminders
            .filter { $0.isCompleted == false && $0.isPaused }
            .sorted(by: stableReminderOrder)
    }

    var pastReminders: [ReminderRecord] {
        reminders
            .filter(\.isCompleted)
            .sorted { ($0.completedAt ?? $0.updatedAt) > ($1.completedAt ?? $1.updatedAt) }
    }

    var todayCheckIns: [ReminderRecord] {
        todayCheckIns(on: Date())
    }

    func todayCheckIns(on date: Date) -> [ReminderRecord] {
        reminders
            .filter { $0.needsCheckIn(on: date) }
            .sorted {
                ($0.occurrenceDate(on: date) ?? $0.scheduledDate) < ($1.occurrenceDate(on: date) ?? $1.scheduledDate)
            }
    }

    func startObserving() {
        guard observationTask == nil else { return }

        observationTask = Task { [weak self] in
            guard let self else { return }

            for await result in repository.observeReminders() {
                switch result {
                case .success(let reminders):
                    // 快照为空说明没有需要保持的顺序（首次加载 / 此前没有进行中任务），直接归位、不做动画，
                    // 避免整列表「飞入」。否则保持现有顺序、只更新内容，不让观察流回推把行挪位。
                    if self.activeOrderSnapshot.isEmpty {
                        self.reminders = reminders
                        self.settleActiveOrder()
                    } else {
                        withAnimation {
                            self.reminders = reminders
                        }
                    }
                    self.errorMessage = nil
                case .failure(let error):
                    self.errorMessage = error.localizedDescription
                }
                self.hasLoadedReminders = true
            }
        }
    }

    /// 把「进行中」区顺序归位到排序规则：待打卡置顶、今日已完成下沉。
    ///
    /// 只在列表出现时调用（首次加载、从详情/历史页返回）。此时重排发生在页面转场之下，用户看不到跳动，
    /// 而回到列表时看到的顺序已经是正确的——既保证「待打卡在上面」，又不会在打卡当下产生突兀的位移。
    func settleActiveOrder() {
        activeOrderSnapshot = reminders
            .filter { $0.isCompleted == false && $0.isPaused == false }
            .sorted(by: activeReminderOrder)
            .map(\.id)
    }

    func reminder(id: String) -> ReminderRecord? {
        reminders.first { $0.id == id }
    }

    func loadReminderIfNeeded(id: String) async {
        guard reminder(id: id) == nil else { return }

        do {
            if let reminder = try repository.fetchReminder(id: id) {
                upsertReminder(reminder)
            }
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func makeReminder(
        title: String,
        note: String?,
        memoryIcon: String,
        imageData: Data?,
        imageAssetID: String? = nil,
        scheduledDate: Date,
        leadTime: ReminderLeadTime
    ) -> ReminderRecord {
        ReminderRecord.makeNew(
            title: title,
            note: note,
            memoryIcon: memoryIcon,
            imageData: imageData,
            imageAssetID: imageAssetID,
            scheduledAt: Self.milliseconds(for: scheduledDate),
            leadTimeMinutes: leadTime.rawValue,
            deviceID: repository.deviceID
        )
    }

    func save(_ reminder: ReminderRecord) async {
        do {
            let savedReminder = try repository.saveReminder(reminder)
            upsertReminder(savedReminder)
            errorMessage = nil
            await syncEngine.enqueueScan()

            do {
                try await notificationService.reschedule(savedReminder)
                notificationMessage = nil
            } catch {
                notificationMessage = error.localizedDescription
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func setCompleted(_ reminder: ReminderRecord, isCompleted: Bool) async {
        var record = reminder

        if isCompleted {
            guard record.canCheckIn() else {
                errorMessage = AppLocalization.string("当前不能打卡")
                return
            }

            record.markOccurrenceCompleted(kind: .checkIn)
            checkInSuccessTick += 1
        } else {
            guard record.canRestoreCompletion else {
                return
            }

            record.clearCompletion()
        }

        await persist(record)
    }

    func makeUp(_ reminder: ReminderRecord, date: Date, note: String?) async {
        var record = reminder
        guard let missedDate = record.firstMissedCheckInDate(),
              Calendar.current.isDate(missedDate, inSameDayAs: date) else {
            errorMessage = AppLocalization.string("请先补签最早断签日期")
            return
        }

        record.markOccurrenceCompleted(on: date, kind: .makeUp, note: note)
        await persist(record)
    }

    func undoTodayCheckIn(_ reminder: ReminderRecord) async {
        var record = reminder
        guard record.clearOccurrenceCompletion() else {
            errorMessage = AppLocalization.string("今天没有可撤销的打卡")
            return
        }

        await persist(record)
    }

    func endEarly(_ reminder: ReminderRecord, reason: String) async {
        var record = reminder
        guard record.endEarly(reason: reason) else {
            errorMessage = AppLocalization.string("请输入提前结束原因")
            return
        }

        await persist(record)
    }

    func pause(_ reminder: ReminderRecord, reason: String) async {
        var record = reminder
        guard record.pause(reason: reason) else {
            errorMessage = AppLocalization.string("请输入暂停原因")
            return
        }

        await persist(record)
    }

    func resume(_ reminder: ReminderRecord) async {
        var record = reminder
        guard record.resume() else {
            errorMessage = AppLocalization.string("恢复失败")
            return
        }

        await persist(record)
    }

    func deleteReminder(id: String) async {
        await deleteReminders(ids: [id])
    }

    func deleteReminders(ids: [String]) async {
        guard ids.isEmpty == false else { return }

        do {
            try repository.deleteReminders(ids: ids)
            for id in ids {
                notificationService.cancel(reminderID: id)
            }
            reminders.removeAll { ids.contains($0.id) }
            errorMessage = nil
            await syncEngine.enqueueScan()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func deleteCompletionRecords(_ targets: [ReminderCompletionDeletionTarget]) async {
        guard targets.isEmpty == false else { return }

        do {
            let savedReminders = try repository.deleteCompletionRecords(targets)

            for reminder in savedReminders {
                upsertReminder(reminder)
            }

            errorMessage = nil
            await syncEngine.enqueueScan()

            for reminder in savedReminders {
                do {
                    try await notificationService.reschedule(reminder)
                    notificationMessage = nil
                } catch {
                    notificationMessage = error.localizedDescription
                }
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    static func milliseconds(for date: Date) -> Int64 {
        Int64(date.timeIntervalSince1970 * 1000)
    }

    private func upsertReminder(_ reminder: ReminderRecord) {
        if let index = reminders.firstIndex(where: { $0.id == reminder.id }) {
            reminders[index] = reminder
        } else {
            reminders.insert(reminder, at: 0)
        }
    }

    private func persist(_ reminder: ReminderRecord) async {
        let previous = self.reminder(id: reminder.id)

        // 乐观更新：先落到内存，那一行立刻原地变为已打卡（状态标签、下次时间、进度条），无需等待磁盘写入。
        // 顺序由 activeOrderSnapshot 冻结，所以这里只动画行内内容，不会产生位移。
        withAnimation {
            upsertReminder(reminder)
        }

        do {
            let savedReminder = try repository.saveReminder(reminder)
            // 权威值（updatedAt / syncStatus 等）由数据库观察流稍后统一回填；此处不再手动 upsert，
            // 避免在动画进行中替换数组、把动画打断成瞬变。
            errorMessage = nil
            await syncEngine.enqueueScan()

            do {
                try await notificationService.reschedule(savedReminder)
                notificationMessage = nil
            } catch {
                notificationMessage = error.localizedDescription
            }
        } catch {
            // 落库失败：回滚乐观更新（同样原地、带动画），避免 UI 停留在未持久化的状态。
            withAnimation {
                if let previous {
                    upsertReminder(previous)
                } else {
                    reminders.removeAll { $0.id == reminder.id }
                }
            }
            errorMessage = error.localizedDescription
        }
    }

    private func activeReminderOrder(lhs: ReminderRecord, rhs: ReminderRecord) -> Bool {
        let lhsTier = activeReminderTier(lhs)
        let rhsTier = activeReminderTier(rhs)

        if lhsTier != rhsTier {
            return lhsTier < rhsTier
        }

        return stableReminderOrder(lhs: lhs, rhs: rhs)
    }

    private func activeReminderTier(_ reminder: ReminderRecord) -> Int {
        if reminder.isTodayCompleted {
            return 1
        }

        if reminder.needsCheckInToday {
            return 0
        }

        return 2
    }

    private func stableReminderOrder(lhs: ReminderRecord, rhs: ReminderRecord) -> Bool {
        if lhs.scheduledAt != rhs.scheduledAt {
            return lhs.scheduledAt > rhs.scheduledAt
        }

        if lhs.createdAt != rhs.createdAt {
            return lhs.createdAt > rhs.createdAt
        }

        return lhs.id < rhs.id
    }
}
