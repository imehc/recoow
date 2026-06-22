import Foundation
import Observation

@MainActor
@Observable
final class DiaryViewModel {
    var entries: [DiaryEntry] = []
    var tags: [DiaryTag] = []
    var linksByDiaryID: [String: [DiaryLink]] = [:]
    var attachmentsByDiaryID: [String: [MediaAttachment]] = [:]
    var searchText = ""
    var selectedTag: String?
    var selectedDate: Date?
    var errorMessage: String?

    @ObservationIgnored private let repository: DiaryRepository
    @ObservationIgnored private let syncEngine: any SyncEngine
    @ObservationIgnored private var observationTask: Task<Void, Never>?
    @ObservationIgnored private var tagObservationTask: Task<Void, Never>?

    init(repository: DiaryRepository, syncEngine: any SyncEngine) {
        self.repository = repository
        self.syncEngine = syncEngine
    }

    deinit {
        observationTask?.cancel()
        tagObservationTask?.cancel()
    }

    var filteredEntries: [DiaryEntry] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)

        return entries.filter { entry in
            if let selectedTag,
               entry.tagReferences.contains(where: { $0.key == selectedTag }) == false {
                return false
            }

            if let selectedDate,
               Calendar.current.isDate(entry.occurredDate, inSameDayAs: selectedDate) == false {
                return false
            }

            guard query.isEmpty == false else { return true }
            return searchableText(for: entry).localizedCaseInsensitiveContains(query)
        }
    }

    var availableTagReferences: [DiaryTagReference] {
        let managedReferences = tags.map { DiaryTagReference(key: $0.id, value: $0.name) }
        let entryReferences = entries.flatMap(\.tagReferences)
        var referencesByKey: [String: DiaryTagReference] = [:]

        for reference in entryReferences {
            referencesByKey[reference.key] = reference
        }

        for reference in managedReferences {
            referencesByKey[reference.key] = reference
        }

        return referencesByKey.values.sorted {
            $0.value.localizedCaseInsensitiveCompare($1.value) == .orderedAscending
        }
    }

    var availableTags: [String] {
        availableTagReferences.map(\.value)
    }

    var todayEntryCount: Int {
        entries.filter { Calendar.current.isDateInToday($0.occurredDate) }.count
    }

    var activeDayCount: Int {
        Set(entries.map { Calendar.current.startOfDay(for: $0.occurredDate) }).count
    }

    var currentStreakDays: Int {
        let calendar = Calendar.current
        let days = Set(entries.map { calendar.startOfDay(for: $0.occurredDate) })
        guard days.isEmpty == false else { return 0 }

        var cursor = calendar.startOfDay(for: Date())
        if days.contains(cursor) == false,
           let yesterday = calendar.date(byAdding: .day, value: -1, to: cursor) {
            cursor = yesterday
        }

        var count = 0
        while days.contains(cursor) {
            count += 1
            guard let previousDay = calendar.date(byAdding: .day, value: -1, to: cursor) else {
                break
            }
            cursor = previousDay
        }

        return count
    }

    var deviceID: String {
        repository.deviceID
    }

    func startObserving() {
        startEntryObservation()
        startTagObservation()
    }

    private func startEntryObservation() {
        guard observationTask == nil else { return }

        observationTask = Task { [weak self] in
            guard let self else { return }

            for await result in repository.observeEntries() {
                switch result {
                case .success(let entries):
                    self.entries = entries
                    do {
                        self.linksByDiaryID = try repository.fetchLinks(diaryIDs: entries.map(\.id))
                        self.attachmentsByDiaryID = try repository.fetchAttachments(diaryIDs: entries.map(\.id))
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

    private func startTagObservation() {
        guard tagObservationTask == nil else { return }

        tagObservationTask = Task { [weak self] in
            guard let self else { return }

            for await result in repository.observeTags() {
                switch result {
                case .success(let tags):
                    self.tags = tags
                    self.errorMessage = nil
                case .failure(let error):
                    self.errorMessage = error.localizedDescription
                }
            }
        }
    }

    func entry(id: String) -> DiaryEntry? {
        entries.first { $0.id == id }
    }

    func links(for diaryID: String) -> [DiaryLink] {
        linksByDiaryID[diaryID, default: []]
    }

    func attachments(for diaryID: String) -> [MediaAttachment] {
        attachmentsByDiaryID[diaryID, default: []]
    }

    func loadEntryIfNeeded(id: String) async {
        guard entry(id: id) == nil || linksByDiaryID[id] == nil || attachmentsByDiaryID[id] == nil else { return }

        do {
            if let detail = try repository.fetchEntry(id: id) {
                upsertEntry(detail.entry)
                linksByDiaryID[id] = detail.links
                attachmentsByDiaryID[id] = detail.attachments
            }
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func makeEntry(
        title: String,
        content: String,
        mood: DiaryMood,
        tags: [DiaryTagReference],
        occurredDate: Date,
        latitude: Double? = nil,
        longitude: Double? = nil,
        horizontalAccuracy: Double? = nil
    ) -> DiaryEntry {
        DiaryEntry.makeNew(
            title: title,
            content: content,
            mood: mood,
            tags: tags,
            occurredAt: Self.milliseconds(for: occurredDate),
            latitude: latitude,
            longitude: longitude,
            horizontalAccuracy: horizontalAccuracy,
            deviceID: repository.deviceID
        )
    }

    func makeTag(name: String, note: String?) -> DiaryTag {
        DiaryTag.makeNew(name: name, note: note, deviceID: repository.deviceID)
    }

    func tagReference(for tag: DiaryTag) -> DiaryTagReference {
        DiaryTagReference(key: tag.id, value: tag.name)
    }

    func resolvedTagReferences(for entry: DiaryEntry) -> [DiaryTagReference] {
        entry.tagReferences.map { reference in
            resolvedTagReference(reference)
        }
    }

    func resolvedTagReference(_ reference: DiaryTagReference) -> DiaryTagReference {
        if let tag = tags.first(where: { $0.id == reference.key }) {
            return DiaryTagReference(key: reference.key, value: tag.name)
        }

        return reference
    }

    func tagTitle(forKey key: String) -> String {
        tags.first { $0.id == key }?.name
            ?? entries.flatMap(\.tagReferences).first { $0.key == key }?.value
            ?? key
    }

    func makeLink(diaryID: String, record: DiaryLinkedRecord) -> DiaryLink {
        record.makeLink(diaryID: diaryID, deviceID: repository.deviceID)
    }

    func save(_ entry: DiaryEntry, links: [DiaryLink], attachments: [MediaAttachment]) async -> Bool {
        do {
            let detail = try repository.saveEntry(entry, links: links, attachments: attachments)
            upsertEntry(detail.entry)
            linksByDiaryID[detail.entry.id] = detail.links
            attachmentsByDiaryID[detail.entry.id] = detail.attachments
            errorMessage = nil
            await syncEngine.enqueueScan()
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    func save(_ tag: DiaryTag) async -> Bool {
        do {
            let savedTag = try repository.saveTag(tag)
            upsertTag(savedTag)
            errorMessage = nil
            await syncEngine.enqueueScan()
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    func deleteTag(id: String) async {
        do {
            try repository.deleteTag(id: id)
            tags.removeAll { $0.id == id }
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
                linksByDiaryID[id] = nil
                attachmentsByDiaryID[id] = nil
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

    private func searchableText(for entry: DiaryEntry) -> String {
        let links = links(for: entry.id)

        return [
            entry.title,
            entry.content,
            AppLocalization.string(entry.diaryMood.title),
            resolvedTagReferences(for: entry).map(\.value).joined(separator: " "),
            links.map(\.sourceTitle).joined(separator: " "),
            links.compactMap(\.sourceSubtitle).joined(separator: " "),
            attachments(for: entry.id).map(\.displayTitle).joined(separator: " ")
        ]
        .joined(separator: " ")
    }

    private func upsertEntry(_ entry: DiaryEntry) {
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

    private func upsertTag(_ tag: DiaryTag) {
        if let index = tags.firstIndex(where: { $0.id == tag.id }) {
            tags[index] = tag
        } else {
            tags.append(tag)
        }

        tags.sort {
            $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
    }
}
