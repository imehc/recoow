import Foundation
import Observation

@MainActor
@Observable
final class FoodJournalViewModel {
    var entries: [FoodEntry] = []
    var dayRecords: [FoodDayRecord] = []
    var attachmentsByEntryID: [String: [MediaAttachment]] = [:]
    var searchText = ""
    var errorMessage: String?

    @ObservationIgnored private let repository: FoodJournalRepository
    @ObservationIgnored private let syncEngine: any SyncEngine
    @ObservationIgnored private var observationTask: Task<Void, Never>?
    private let calendar = Calendar.current

    init(repository: FoodJournalRepository, syncEngine: any SyncEngine) {
        self.repository = repository
        self.syncEngine = syncEngine
    }

    deinit {
        observationTask?.cancel()
    }

    var filteredEntries: [FoodEntry] {
        guard normalizedSearchText.isEmpty == false else { return entries }

        return entries.filter { entry in
            searchableText(for: entry).localizedCaseInsensitiveContains(normalizedSearchText)
        }
    }

    var dayGroups: [FoodDayGroup] {
        guard normalizedSearchText.isEmpty == false else { return allDayGroups }

        return allDayGroups.filter { group in
            groupMatchesSearch(group)
        }
    }

    var allDayGroups: [FoodDayGroup] {
        Self.makeDayGroups(entries: entries, dayRecords: dayRecords, calendar: calendar)
    }

    var todayEntries: [FoodEntry] {
        entries.filter { calendar.isDateInToday($0.occurredDate) }
    }

    var todayMealKindCount: Int {
        Set(todayEntries.map(\.mealKind)).count
    }

    var currentWeekSnackCount: Int {
        entries(in: .weekOfYear).filter { $0.foodMealKind == .snack }.count
    }

    var currentWeekDrinkCount: Int {
        entries(in: .weekOfYear).filter { $0.foodMealKind == .drink }.count
    }

    var currentWeekLateNightCount: Int {
        entries(in: .weekOfYear).filter { $0.foodMealKind == .lateNightSnack }.count
    }

    var currentMonthMilkTeaCount: Int {
        entries(in: .month).filter {
            $0.title.localizedCaseInsensitiveContains("奶茶")
                || $0.title.localizedCaseInsensitiveContains("milk tea")
        }.count
    }

    var latestEntryDate: Date? {
        entries.map(\.occurredDate).max()
    }

    var deviceID: String {
        repository.deviceID
    }

    private var normalizedSearchText: String {
        searchText.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    func startObserving() {
        guard observationTask == nil else { return }

        observationTask = Task { [weak self] in
            guard let self else { return }

            for await result in repository.observeEntries() {
                switch result {
                case .success(let snapshot):
                    self.entries = snapshot.entries
                    self.dayRecords = snapshot.dayRecords
                    do {
                        self.attachmentsByEntryID = try repository.fetchAttachments(entryIDs: snapshot.entries.map(\.id))
                        self.errorMessage = nil
                    } catch {
                        self.errorMessage = error.localizedDescription
                    }
                case .failure(let error):
                    self.errorMessage = error.localizedDescription
                }
            }
        }
    }

    func entry(id: String) -> FoodEntry? {
        entries.first { $0.id == id }
    }

    func dayGroup(for date: Date) -> FoodDayGroup? {
        let dayStart = calendar.startOfDay(for: date)
        return allDayGroups.first { calendar.isDate($0.date, inSameDayAs: dayStart) }
    }

    func dayRecord(for date: Date) -> FoodDayRecord? {
        let dayStartAt = Self.milliseconds(for: calendar.startOfDay(for: date))
        return dayRecords.first { $0.dayStartAt == dayStartAt }
    }

    func dayTitle(for date: Date) -> String? {
        dayRecord(for: date)?.normalizedTitle
    }

    func entries(for date: Date) -> [FoodEntry] {
        dayGroup(for: date)?.sortedEntries ?? []
    }

    func attachments(for entryID: String) -> [MediaAttachment] {
        attachmentsByEntryID[entryID, default: []]
    }

    func attachmentCount(for group: FoodDayGroup) -> Int {
        group.entries.reduce(0) { count, entry in
            count + attachments(for: entry.id).filter { $0.kind == .photo }.count
        }
    }

    func recentFoodSuggestions(limit: Int = 8, excluding entryID: String? = nil) -> [FoodEntrySuggestion] {
        var suggestionsByID: [String: FoodEntrySuggestion] = [:]

        for entry in entries where entry.id != entryID {
            let title = entry.title.trimmingCharacters(in: .whitespacesAndNewlines)
            guard title.isEmpty == false else { continue }

            let suggestion = FoodEntrySuggestion(
                title: title,
                mealKind: entry.foodMealKind,
                portion: entry.normalizedPortion,
                useCount: 1,
                latestOccurredAt: entry.occurredAt
            )

            if let existing = suggestionsByID[suggestion.id] {
                suggestionsByID[suggestion.id] = FoodEntrySuggestion(
                    title: existing.title,
                    mealKind: existing.mealKind,
                    portion: existing.portion,
                    useCount: existing.useCount + 1,
                    latestOccurredAt: max(existing.latestOccurredAt, entry.occurredAt)
                )
            } else {
                suggestionsByID[suggestion.id] = suggestion
            }
        }

        return suggestionsByID.values.sorted {
            if $0.useCount == $1.useCount {
                return $0.latestOccurredAt > $1.latestOccurredAt
            }

            return $0.useCount > $1.useCount
        }
        .prefix(limit)
        .map { $0 }
    }

    func loadEntryIfNeeded(id: String) async {
        guard entry(id: id) == nil || attachmentsByEntryID[id] == nil else { return }

        do {
            if let detail = try repository.fetchEntry(id: id) {
                upsertEntry(detail.entry)
                attachmentsByEntryID[id] = detail.attachments
            }
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func makeEntry(
        title: String,
        mealKind: FoodMealKind,
        portion: String?,
        note: String?,
        billIDs: [String],
        occurredDate: Date
    ) -> FoodEntry {
        FoodEntry.makeNew(
            title: title,
            mealKind: mealKind,
            portion: portion,
            note: note,
            billIDs: billIDs,
            occurredAt: Self.milliseconds(for: occurredDate),
            deviceID: repository.deviceID
        )
    }

    func save(_ entry: FoodEntry, attachments: [MediaAttachment]) async -> Bool {
        do {
            let detail = try repository.saveEntry(entry, attachments: attachments)
            upsertEntry(detail.entry)
            attachmentsByEntryID[detail.entry.id] = detail.attachments
            errorMessage = nil
            await syncEngine.enqueueScan()
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    func saveDayTitle(dayStart: Date, title: String) async -> Bool {
        let normalizedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let dayStartAt = Self.milliseconds(for: calendar.startOfDay(for: dayStart))

        guard normalizedTitle.isEmpty == false || dayRecord(for: dayStart) != nil else {
            return true
        }

        var record = dayRecord(for: dayStart)
            ?? FoodDayRecord.makeNew(
                title: normalizedTitle,
                dayStartAt: dayStartAt,
                deviceID: repository.deviceID
            )
        record.title = normalizedTitle

        do {
            let savedRecord = try repository.saveDayRecord(record)
            upsertDayRecord(savedRecord)
            errorMessage = nil
            await syncEngine.enqueueScan()
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    func deleteDay(dayStart: Date) async {
        let normalizedDayStart = calendar.startOfDay(for: dayStart)
        let dayStartAt = Self.milliseconds(for: normalizedDayStart)
        let ids = entries(for: normalizedDayStart).map(\.id)

        do {
            try repository.deleteDay(dayStartAt: dayStartAt, entryIDs: ids)
            entries.removeAll { ids.contains($0.id) }
            dayRecords.removeAll { $0.dayStartAt == dayStartAt }
            for id in ids {
                attachmentsByEntryID[id] = nil
            }
            errorMessage = nil
            await syncEngine.enqueueScan()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func deleteEntry(id: String) async {
        await deleteEntries(ids: [id])
    }

    func deleteEntries(ids: [String]) async {
        guard ids.isEmpty == false else { return }

        do {
            try repository.deleteEntries(ids: ids)
            entries.removeAll { ids.contains($0.id) }
            for id in ids {
                attachmentsByEntryID[id] = nil
            }
            errorMessage = nil
            await syncEngine.enqueueScan()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    nonisolated static func makeDayGroups(
        entries: [FoodEntry],
        dayRecords: [FoodDayRecord] = [],
        calendar: Calendar = .current
    ) -> [FoodDayGroup] {
        let recordsByDayStartAt = Dictionary(uniqueKeysWithValues: dayRecords.map { ($0.dayStartAt, $0) })

        return Dictionary(grouping: entries) { entry in
            calendar.startOfDay(for: entry.occurredDate)
        }
        .map { day, entries in
            let dayStartAt = milliseconds(for: day)
            return FoodDayGroup(
                date: day,
                entries: entries,
                dayRecord: recordsByDayStartAt[dayStartAt]
            )
        }
        .sorted {
            if $0.date == $1.date {
                return $0.id > $1.id
            }

            return $0.date > $1.date
        }
    }

    nonisolated static func milliseconds(for date: Date) -> Int64 {
        Int64(date.timeIntervalSince1970 * 1000)
    }

    private func searchableText(for entry: FoodEntry) -> String {
        [
            entry.title,
            entry.foodMealKind.localizedTitle,
            entry.portion,
            entry.note,
            entry.hasLinkedBills ? AppLocalization.string("已关联账单") : nil,
            attachments(for: entry.id).isEmpty ? nil : AppLocalization.string("照片")
        ]
        .compactMap(\.self)
        .joined(separator: " ")
    }

    private func groupMatchesSearch(_ group: FoodDayGroup) -> Bool {
        let normalized = normalizedSearchText
        guard normalized.isEmpty == false else { return true }

        if let title = group.title, title.localizedCaseInsensitiveContains(normalized) {
            return true
        }

        return group.entries.contains { entry in
            searchableText(for: entry).localizedCaseInsensitiveContains(normalized)
        }
    }

    private func upsertEntry(_ entry: FoodEntry) {
        if let index = entries.firstIndex(where: { $0.id == entry.id }) {
            entries[index] = entry
        } else {
            entries.insert(entry, at: 0)
        }

        entries.sort { lhs, rhs in
            if lhs.occurredAt == rhs.occurredAt {
                return lhs.id > rhs.id
            }

            return lhs.occurredAt > rhs.occurredAt
        }
    }

    private func upsertDayRecord(_ record: FoodDayRecord) {
        if let index = dayRecords.firstIndex(where: { $0.id == record.id }) {
            dayRecords[index] = record
        } else {
            dayRecords.append(record)
        }

        dayRecords.sort { lhs, rhs in
            if lhs.dayStartAt == rhs.dayStartAt {
                return lhs.id > rhs.id
            }

            return lhs.dayStartAt > rhs.dayStartAt
        }
    }

    private func entries(in component: Calendar.Component) -> [FoodEntry] {
        guard let interval = calendar.dateInterval(of: component, for: Date()) else { return [] }
        return entries.filter { interval.contains($0.occurredDate) }
    }
}
