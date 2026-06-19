import Foundation
import Observation

@MainActor
@Observable
final class AnniversariesViewModel {
    var anniversaries: [AnniversaryRecord] = []
    var errorMessage: String?
    var notificationMessage: String?

    @ObservationIgnored private let repository: AnniversaryRepository
    @ObservationIgnored private let notificationService: AnniversaryNotificationService
    @ObservationIgnored private let syncEngine: any SyncEngine
    @ObservationIgnored private var observationTask: Task<Void, Never>?

    init(
        repository: AnniversaryRepository,
        notificationService: AnniversaryNotificationService,
        syncEngine: any SyncEngine
    ) {
        self.repository = repository
        self.notificationService = notificationService
        self.syncEngine = syncEngine
    }

    deinit {
        observationTask?.cancel()
    }

    var upcomingAnniversaries: [AnniversaryRecord] {
        upcomingAnniversaries(from: Date())
    }

    var pastAnniversaries: [AnniversaryRecord] {
        anniversaries
            .filter(\.isExpired)
            .sorted { $0.occurredAt > $1.occurredAt }
    }

    func upcomingAnniversaries(from date: Date) -> [AnniversaryRecord] {
        anniversaries
            .filter { $0.nextOccurrenceDate(from: date) != nil }
            .sorted {
                ($0.nextOccurrenceDate(from: date) ?? $0.occurredDate) < ($1.nextOccurrenceDate(from: date) ?? $1.occurredDate)
            }
    }

    func homeAnniversaries(on date: Date) -> [AnniversaryRecord] {
        upcomingAnniversaries(from: date).filter { anniversary in
            guard let days = anniversary.daysUntilNext(from: date) else { return false }
            return (0...7).contains(days)
        }
    }

    func startObserving() {
        guard observationTask == nil else { return }

        observationTask = Task { [weak self] in
            guard let self else { return }

            for await result in repository.observeAnniversaries() {
                switch result {
                case .success(let anniversaries):
                    self.anniversaries = anniversaries
                    self.errorMessage = nil
                case .failure(let error):
                    self.errorMessage = error.localizedDescription
                }
            }
        }
    }

    func anniversary(id: String) -> AnniversaryRecord? {
        anniversaries.first { $0.id == id }
    }

    func loadAnniversaryIfNeeded(id: String) async {
        guard anniversary(id: id) == nil else { return }

        do {
            if let anniversary = try repository.fetchAnniversary(id: id) {
                upsertAnniversary(anniversary)
            }
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func makeAnniversary(
        title: String,
        note: String?,
        category: AnniversaryCategory,
        occurredDate: Date,
        dateCalendar: AnniversaryDateCalendar,
        isYearly: Bool,
        leadTime: ReminderLeadTime,
        isEnabled: Bool,
        reminderTimeMinutes: Int
    ) -> AnniversaryRecord {
        AnniversaryRecord.makeNew(
            title: title,
            note: note,
            category: category,
            occurredAt: Self.milliseconds(for: occurredDate),
            dateCalendar: dateCalendar,
            isYearly: isYearly,
            leadTime: leadTime,
            isEnabled: isEnabled,
            reminderTimeMinutes: reminderTimeMinutes,
            deviceID: repository.deviceID
        )
    }

    func save(_ anniversary: AnniversaryRecord) async {
        do {
            let savedAnniversary = try repository.saveAnniversary(anniversary)
            upsertAnniversary(savedAnniversary)
            errorMessage = nil
            await syncEngine.enqueueScan()

            do {
                try await notificationService.reschedule(savedAnniversary)
                notificationMessage = nil
            } catch {
                notificationMessage = error.localizedDescription
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func deleteAnniversary(id: String) async {
        await deleteAnniversaries(ids: [id])
    }

    func deleteAnniversaries(ids: [String]) async {
        guard ids.isEmpty == false else { return }

        do {
            try repository.deleteAnniversaries(ids: ids)
            for id in ids {
                notificationService.cancel(anniversaryID: id)
            }
            anniversaries.removeAll { ids.contains($0.id) }
            errorMessage = nil
            await syncEngine.enqueueScan()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    static func milliseconds(for date: Date) -> Int64 {
        Int64(date.timeIntervalSince1970 * 1000)
    }

    private func upsertAnniversary(_ anniversary: AnniversaryRecord) {
        if let index = anniversaries.firstIndex(where: { $0.id == anniversary.id }) {
            anniversaries[index] = anniversary
        } else {
            anniversaries.insert(anniversary, at: 0)
        }
    }
}
