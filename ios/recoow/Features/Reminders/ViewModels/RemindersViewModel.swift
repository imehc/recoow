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
            .filter(\.isUpcoming)
            .sorted { $0.scheduledAt < $1.scheduledAt }
    }

    var pastReminders: [ReminderRecord] {
        reminders
            .filter { $0.isUpcoming == false }
            .sorted { $0.scheduledAt > $1.scheduledAt }
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

    func makeReminder(
        title: String,
        note: String?,
        memoryIcon: String,
        imageData: Data?,
        scheduledDate: Date,
        leadTime: ReminderLeadTime
    ) -> ReminderRecord {
        ReminderRecord.makeNew(
            title: title,
            note: note,
            memoryIcon: memoryIcon,
            imageData: imageData,
            scheduledAt: Self.milliseconds(for: scheduledDate),
            leadTimeMinutes: leadTime.rawValue,
            deviceID: repository.deviceID
        )
    }

    func save(_ reminder: ReminderRecord) async {
        do {
            let savedReminder = try repository.saveReminder(reminder)
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
            errorMessage = nil
            await syncEngine.enqueueScan()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    static func milliseconds(for date: Date) -> Int64 {
        Int64(date.timeIntervalSince1970 * 1000)
    }
}
