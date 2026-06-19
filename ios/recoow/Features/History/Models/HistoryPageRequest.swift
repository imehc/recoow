import Foundation

struct HistoryPageRequest: Sendable {
    let route: ToolRoute?
    let dateInterval: DateInterval?
    let searchText: String
    let cursor: HistoryPageCursor?
    let limit: Int
}
