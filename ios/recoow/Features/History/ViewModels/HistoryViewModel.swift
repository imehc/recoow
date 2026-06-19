import Foundation
import Observation

@MainActor
@Observable
final class HistoryViewModel {
    var entries: [HistoryEntry] = []
    var entryCountsByDay: [Date: Int] = [:]
    var pointCounts: [String: Int] = [:]
    var itemCategoryNames: [String: String] = [:]
    var hasAnyEntries = false
    var hasMoreEntries = false
    var isLoading = false
    var isLoadingMore = false
    var errorMessage: String?

    @ObservationIgnored private let repository: HistoryRepository
    @ObservationIgnored private var currentRequest: HistoryPageRequest?
    @ObservationIgnored private let pageSize = 30

    init(repository: HistoryRepository) {
        self.repository = repository
    }

    func reload(
        selectedDate: Date,
        activeFilter: HistoryFilter?,
        selectedRouteFilter: ToolRoute?,
        searchText: String
    ) async {
        isLoading = true
        defer { isLoading = false }

        let request = makeRequest(
            selectedDate: selectedDate,
            activeFilter: activeFilter,
            selectedRouteFilter: selectedRouteFilter,
            searchText: searchText,
            cursor: nil
        )

        do {
            let page = try repository.fetchPage(request)
            entries = page.entries
            hasMoreEntries = page.hasMore
            currentRequest = request
            hasAnyEntries = try repository.hasAnyEntries()
            try refreshSupplementaryData(for: page.entries)
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func refreshCounts(weekAnchorDate: Date) async {
        do {
            entryCountsByDay = try repository.fetchEntryCountsByDay(weekAnchorDate: weekAnchorDate)
            hasAnyEntries = try repository.hasAnyEntries()
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func loadMoreIfNeeded(currentEntry: HistoryEntry) async {
        guard entries.last?.id == currentEntry.id else { return }
        await loadMore()
    }

    func loadMore() async {
        guard hasMoreEntries,
              isLoading == false,
              isLoadingMore == false,
              let currentRequest,
              let lastEntry = entries.last else {
            return
        }

        isLoadingMore = true
        defer { isLoadingMore = false }

        let request = HistoryPageRequest(
            route: currentRequest.route,
            dateInterval: currentRequest.dateInterval,
            searchText: currentRequest.searchText,
            cursor: HistoryPageCursor(timestamp: lastEntry.timestamp, entryID: lastEntry.id),
            limit: currentRequest.limit
        )

        do {
            let page = try repository.fetchPage(request)
            entries.append(contentsOf: page.entries)
            hasMoreEntries = page.hasMore
            self.currentRequest = request
            try refreshSupplementaryData(for: entries)
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func pointCount(for trackID: String) -> Int {
        pointCounts[trackID, default: 0]
    }

    func itemCategoryName(for item: StoredItem) -> String {
        guard let categoryID = item.categoryID else { return AppLocalization.string("未分类") }
        return itemCategoryNames[categoryID, default: AppLocalization.string("未分类")]
    }

    func entry(id: String) -> HistoryEntry? {
        entries.first { $0.id == id }
    }

    func removeEntries(ids: [String]) {
        guard ids.isEmpty == false else { return }
        entries.removeAll { ids.contains($0.id) }
    }

    private func makeRequest(
        selectedDate: Date,
        activeFilter: HistoryFilter?,
        selectedRouteFilter: ToolRoute?,
        searchText: String,
        cursor: HistoryPageCursor?
    ) -> HistoryPageRequest {
        let route = selectedRouteFilter ?? activeFilter?.route
        let interval = activeFilter?.dateInterval ?? Self.dayInterval(for: selectedDate)

        return HistoryPageRequest(
            route: route,
            dateInterval: interval,
            searchText: searchText,
            cursor: cursor,
            limit: pageSize
        )
    }

    private func refreshSupplementaryData(for entries: [HistoryEntry]) throws {
        let trackIDs = entries.compactMap { entry in
            if case .track(let track) = entry {
                return track.id
            }
            return nil
        }

        pointCounts = try repository.fetchPointCounts(trackIDs: trackIDs)
        itemCategoryNames = try repository.fetchItemCategoryNames()
    }

    private static func dayInterval(for date: Date, calendar: Calendar = .current) -> DateInterval? {
        calendar.dateInterval(of: .day, for: date)
    }
}
