import Foundation
import GRDB

final class HistoryRepository: @unchecked Sendable {
    private let database: AppDatabase
    private let calendar = Calendar.current

    init(database: AppDatabase) {
        self.database = database
    }

    func fetchPage(_ request: HistoryPageRequest) throws -> HistoryPage {
        try database.reader.read { db in
            var entries: [HistoryEntry] = []
            let sourceLimit = request.limit + 1

            if shouldInclude(.locationTracker, route: request.route) {
                entries += try fetchTracks(db: db, request: request, limit: sourceLimit).map(HistoryEntry.track)
            }

            if shouldInclude(.decisionMaker, route: request.route) {
                entries += try fetchDecisionRecords(db: db, request: request, limit: sourceLimit).map(HistoryEntry.decisionChoice)
            }

            if shouldInclude(.itemLocator, route: request.route) {
                entries += try fetchItems(db: db, request: request, limit: sourceLimit).map(HistoryEntry.storedItem)
            }

            if shouldInclude(.reminders, route: request.route) {
                entries += try fetchReminderHistoryRecords(db: db, request: request, limit: sourceLimit).map(HistoryEntry.reminder)
            }

            if shouldInclude(.bills, route: request.route) {
                entries += try fetchBills(db: db, request: request, limit: sourceLimit).map(HistoryEntry.bill)
            }

            if shouldInclude(.foodJournal, route: request.route) {
                entries += try fetchFoodDayGroups(db: db, request: request, limit: sourceLimit).map(HistoryEntry.foodDay)
            }

            if shouldInclude(.diary, route: request.route) {
                entries += try fetchDiaries(db: db, request: request, limit: sourceLimit).map(HistoryEntry.diary)
            }

            if shouldInclude(.anniversaries, route: request.route) {
                entries += try fetchAnniversaries(db: db, request: request, limit: sourceLimit).map(HistoryEntry.anniversary)
            }

            let sortedEntries = entries
                .filter { isAfterCursor($0, cursor: request.cursor) }
                .sorted(by: sortEntries)

            return HistoryPage(
                entries: Array(sortedEntries.prefix(request.limit)),
                hasMore: sortedEntries.count > request.limit
            )
        }
    }

    func fetchEntryCountsByDay(weekAnchorDate: Date) throws -> [Date: Int] {
        guard let interval = calendar.dateInterval(of: .weekOfYear, for: weekAnchorDate) else {
            return [:]
        }

        let start = milliseconds(for: interval.start)
        let end = milliseconds(for: interval.end)

        return try database.reader.read { db in
            let timestamps = try fetchTimestamps(
                db: db,
                table: Track.databaseTableName,
                timestampColumn: "started_at",
                start: start,
                end: end
            )
            + fetchTimestamps(
                db: db,
                table: DecisionChoiceRecord.databaseTableName,
                timestampColumn: "selected_at",
                start: start,
                end: end
            )
            + fetchTimestamps(
                db: db,
                table: StoredItem.databaseTableName,
                timestampColumn: "updated_at",
                start: start,
                end: end
            )
            + fetchReminderHistoryTimestamps(db: db, start: start, end: end)
            + fetchTimestamps(
                db: db,
                table: BillRecord.databaseTableName,
                timestampColumn: "occurred_at",
                start: start,
                end: end
            )
            + fetchFoodDayTimestamps(db: db, start: start, end: end)
            + fetchTimestamps(
                db: db,
                table: DiaryEntry.databaseTableName,
                timestampColumn: "occurred_at",
                start: start,
                end: end
            )
            + fetchTimestamps(
                db: db,
                table: AnniversaryRecord.databaseTableName,
                timestampColumn: "occurred_at",
                start: start,
                end: end
            )

            return timestamps.reduce(into: [:]) { counts, timestamp in
                let date = Date(timeIntervalSince1970: Double(timestamp) / 1000)
                counts[calendar.startOfDay(for: date), default: 0] += 1
            }
        }
    }

    func hasAnyEntries() throws -> Bool {
        try database.reader.read { db in
            try Track.filter(Column("deleted_at") == nil).fetchCount(db) > 0
            || DecisionChoiceRecord.filter(Column("deleted_at") == nil).fetchCount(db) > 0
            || StoredItem.filter(Column("deleted_at") == nil).fetchCount(db) > 0
            || hasAnyReminderHistoryRecords(db: db)
            || BillRecord.filter(Column("deleted_at") == nil).fetchCount(db) > 0
            || FoodEntry.filter(Column("deleted_at") == nil).fetchCount(db) > 0
            || DiaryEntry.filter(Column("deleted_at") == nil).fetchCount(db) > 0
            || AnniversaryRecord.filter(Column("deleted_at") == nil).fetchCount(db) > 0
        }
    }

    func fetchPointCounts(trackIDs: [String]) throws -> [String: Int] {
        guard trackIDs.isEmpty == false else { return [:] }

        return try database.reader.read { db in
            var counts: [String: Int] = [:]

            for trackID in trackIDs {
                counts[trackID] = try TrackPoint
                    .filter(Column("track_id") == trackID)
                    .filter(Column("deleted_at") == nil)
                    .fetchCount(db)
            }

            return counts
        }
    }

    func fetchItemCategoryNames() throws -> [String: String] {
        try database.reader.read { db in
            let categories = try ItemCategory
                .filter(Column("deleted_at") == nil)
                .fetchAll(db)
            return Dictionary(uniqueKeysWithValues: categories.map { ($0.id, $0.name) })
        }
    }

    func fetchDiaryTagNames() throws -> [String: String] {
        try database.reader.read { db in
            let tags = try DiaryTag
                .filter(Column("deleted_at") == nil)
                .fetchAll(db)
            return Dictionary(uniqueKeysWithValues: tags.map { ($0.id, $0.name) })
        }
    }

    private func fetchTracks(db: Database, request: HistoryPageRequest, limit: Int) throws -> [Track] {
        var query = Track
            .filter(Column("deleted_at") == nil)

        query = applyDateFilter(query, timestampColumn: "started_at", interval: request.dateInterval)
        query = applyCursorFilter(query, timestampColumn: "started_at", cursor: request.cursor)
        query = applySearchFilter(query, columns: ["name", "note"], searchText: request.searchText)

        return try query
            .order(Column("started_at").desc, Column("id").desc)
            .limit(limit)
            .fetchAll(db)
    }

    private func fetchDecisionRecords(db: Database, request: HistoryPageRequest, limit: Int) throws -> [DecisionChoiceRecord] {
        var query = DecisionChoiceRecord
            .filter(Column("deleted_at") == nil)

        query = applyDateFilter(query, timestampColumn: "selected_at", interval: request.dateInterval)
        query = applyCursorFilter(query, timestampColumn: "selected_at", cursor: request.cursor)
        query = applySearchFilter(
            query,
            columns: ["collection_title", "option_title", "option_detail", "option_custom_info"],
            searchText: request.searchText
        )

        return try query
            .order(Column("selected_at").desc, Column("id").desc)
            .limit(limit)
            .fetchAll(db)
    }

    private func fetchItems(db: Database, request: HistoryPageRequest, limit: Int) throws -> [StoredItem] {
        var query = StoredItem
            .filter(Column("deleted_at") == nil)

        query = applyDateFilter(query, timestampColumn: "updated_at", interval: request.dateInterval)
        query = applyCursorFilter(query, timestampColumn: "updated_at", cursor: request.cursor)
        query = applySearchFilter(
            query,
            columns: ["title", "location", "note", "tags", "search_keywords"],
            searchText: request.searchText
        )

        return try query
            .order(Column("updated_at").desc, Column("id").desc)
            .limit(limit)
            .fetchAll(db)
    }

    private func fetchReminderHistoryRecords(db: Database, request: HistoryPageRequest, limit: Int) throws -> [ReminderHistoryRecord] {
        var query = ReminderRecord
            .filter(Column("deleted_at") == nil)

        query = applySearchFilter(
            query,
            columns: ["title", "note", "memory_icon", "schedule_kind"],
            searchText: request.searchText
        )

        return try query
            .fetchAll(db)
            .flatMap(\.historyRecords)
            .filter { matchesDateInterval(timestamp: $0.completedAt, interval: request.dateInterval) }
            .filter { isAfterCursor(.reminder($0), cursor: request.cursor) }
            .sorted { sortEntries(lhs: .reminder($0), rhs: .reminder($1)) }
            .prefix(limit)
            .map { $0 }
    }

    private func fetchBills(db: Database, request: HistoryPageRequest, limit: Int) throws -> [BillRecord] {
        var query = BillRecord
            .filter(Column("deleted_at") == nil)

        query = applyDateFilter(query, timestampColumn: "occurred_at", interval: request.dateInterval)
        query = applyCursorFilter(query, timestampColumn: "occurred_at", cursor: request.cursor)
        query = applySearchFilter(
            query,
            columns: ["title", "note", "transaction_type", "category", "payment_method", "start_location", "end_location", "transport_lines"],
            searchText: request.searchText
        )

        return try query
            .order(Column("occurred_at").desc, Column("id").desc)
            .limit(limit)
            .fetchAll(db)
    }

    private func fetchFoodDayGroups(db: Database, request: HistoryPageRequest, limit: Int) throws -> [FoodDayGroup] {
        var query = FoodEntry
            .filter(Column("deleted_at") == nil)

        query = applyDateFilter(query, timestampColumn: "occurred_at", interval: request.dateInterval)

        let entries = try query
            .order(Column("occurred_at").desc, Column("id").desc)
            .fetchAll(db)
        let dayRecords = try FoodDayRecord
            .filter(Column("deleted_at") == nil)
            .fetchAll(db)

        return FoodJournalViewModel
            .makeDayGroups(entries: entries, dayRecords: dayRecords, calendar: calendar)
            .filter { foodGroupMatchesSearch($0, searchText: request.searchText) }
            .filter { isAfterCursor(.foodDay($0), cursor: request.cursor) }
            .sorted {
                if $0.updatedAt == $1.updatedAt {
                    return $0.id > $1.id
                }

                return $0.updatedAt > $1.updatedAt
            }
            .prefix(limit)
            .map { $0 }
    }

    private func fetchDiaries(db: Database, request: HistoryPageRequest, limit: Int) throws -> [DiaryEntry] {
        var query = DiaryEntry
            .filter(Column("deleted_at") == nil)

        query = applyDateFilter(query, timestampColumn: "occurred_at", interval: request.dateInterval)
        query = applyCursorFilter(query, timestampColumn: "occurred_at", cursor: request.cursor)
        query = applySearchFilter(
            query,
            columns: ["title", "content", "mood", "tags_json"],
            searchText: request.searchText
        )

        return try query
            .order(Column("occurred_at").desc, Column("id").desc)
            .limit(limit)
            .fetchAll(db)
    }

    private func fetchAnniversaries(db: Database, request: HistoryPageRequest, limit: Int) throws -> [AnniversaryRecord] {
        var query = AnniversaryRecord
            .filter(Column("deleted_at") == nil)

        query = applyDateFilter(query, timestampColumn: "occurred_at", interval: request.dateInterval)
        query = applyCursorFilter(query, timestampColumn: "occurred_at", cursor: request.cursor)
        query = applySearchFilter(
            query,
            columns: ["title", "note", "category"],
            searchText: request.searchText
        )

        return try query
            .order(Column("occurred_at").desc, Column("id").desc)
            .limit(limit)
            .fetchAll(db)
    }

    private func applyDateFilter<Record>(
        _ query: QueryInterfaceRequest<Record>,
        timestampColumn: String,
        interval: DateInterval?
    ) -> QueryInterfaceRequest<Record> {
        guard let interval else { return query }
        return query
            .filter(Column(timestampColumn) >= milliseconds(for: interval.start))
            .filter(Column(timestampColumn) < milliseconds(for: interval.end))
    }

    private func applyCursorFilter<Record>(
        _ query: QueryInterfaceRequest<Record>,
        timestampColumn: String,
        cursor: HistoryPageCursor?
    ) -> QueryInterfaceRequest<Record> {
        guard let cursor else { return query }
        return query.filter(Column(timestampColumn) <= cursor.timestamp)
    }

    private func applySearchFilter<Record>(
        _ query: QueryInterfaceRequest<Record>,
        columns: [String],
        searchText: String
    ) -> QueryInterfaceRequest<Record> {
        let normalized = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard normalized.isEmpty == false else { return query }

        let pattern = "%\(normalized)%"
        let expression = columns
            .map { Column($0).like(pattern) }
            .reduce(nil as SQLExpression?) { result, expression in
                guard let result else { return expression }
                return result || expression
            }

        guard let expression else { return query }
        return query.filter(expression)
    }

    private func foodGroupMatchesSearch(_ group: FoodDayGroup, searchText: String) -> Bool {
        let normalized = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard normalized.isEmpty == false else { return true }

        if let title = group.title, title.localizedCaseInsensitiveContains(normalized) {
            return true
        }

        return group.entries.contains { entry in
            [
                entry.title,
                entry.foodMealKind.localizedTitle,
                entry.portion,
                entry.note
            ]
            .compactMap(\.self)
            .joined(separator: " ")
            .localizedCaseInsensitiveContains(normalized)
        }
    }

    private func matchesDateInterval(timestamp: Int64, interval: DateInterval?) -> Bool {
        guard let interval else { return true }
        return timestamp >= milliseconds(for: interval.start) && timestamp < milliseconds(for: interval.end)
    }

    private func fetchTimestamps(
        db: Database,
        table: String,
        timestampColumn: String,
        start: Int64,
        end: Int64
    ) throws -> [Int64] {
        try Int64.fetchAll(
            db,
            sql: """
            SELECT \(timestampColumn)
            FROM \(table)
            WHERE deleted_at IS NULL
              AND \(timestampColumn) >= ?
              AND \(timestampColumn) < ?
            """,
            arguments: [start, end]
        )
    }

    private func fetchReminderHistoryTimestamps(db: Database, start: Int64, end: Int64) throws -> [Int64] {
        try ReminderRecord
            .filter(Column("deleted_at") == nil)
            .fetchAll(db)
            .flatMap(\.historyRecords)
            .map(\.completedAt)
            .filter { $0 >= start && $0 < end }
    }

    private func fetchFoodDayTimestamps(db: Database, start: Int64, end: Int64) throws -> [Int64] {
        let timestamps = try FoodEntry
            .filter(Column("deleted_at") == nil)
            .filter(Column("occurred_at") >= start)
            .filter(Column("occurred_at") < end)
            .fetchAll(db)
            .map(\.occurredDate)
            .map { calendar.startOfDay(for: $0) }

        return Set(timestamps).map { milliseconds(for: $0) }
    }

    private func hasAnyReminderHistoryRecords(db: Database) throws -> Bool {
        try ReminderRecord
            .filter(Column("deleted_at") == nil)
            .fetchAll(db)
            .contains { $0.historyRecords.isEmpty == false }
    }

    private func shouldInclude(_ route: ToolRoute, route selectedRoute: ToolRoute?) -> Bool {
        selectedRoute == nil || selectedRoute == route
    }

    private func isAfterCursor(_ entry: HistoryEntry, cursor: HistoryPageCursor?) -> Bool {
        guard let cursor else { return true }

        if entry.timestamp == cursor.timestamp {
            return entry.id < cursor.entryID
        }

        return entry.timestamp < cursor.timestamp
    }

    private func sortEntries(lhs: HistoryEntry, rhs: HistoryEntry) -> Bool {
        if lhs.timestamp == rhs.timestamp {
            return lhs.id > rhs.id
        }

        return lhs.timestamp > rhs.timestamp
    }

    private func milliseconds(for date: Date) -> Int64 {
        Int64(date.timeIntervalSince1970 * 1000)
    }
}
