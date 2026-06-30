import Foundation
import Observation

@MainActor
@Observable
final class RemindersViewModel {
    var reminders: [ReminderRecord] = []
    var errorMessage: String?
    var notificationMessage: String?

    @ObservationIgnored private let repository: ReminderRepository
    @ObservationIgnored private let notificationService: ReminderNotificationService
    @ObservationIgnored private let syncEngine: any SyncEngine
    @ObservationIgnored private var observationTask: Task<Void, Never>?

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

    var upcomingReminders: [ReminderRecord] {
        reminders
            .filter { $0.isCompleted == false }
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
                    self.reminders = reminders
                    self.errorMessage = nil
                case .failure(let error):
                    self.errorMessage = error.localizedDescription
                }
            }
        }
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
