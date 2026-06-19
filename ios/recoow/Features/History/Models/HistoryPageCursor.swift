import Foundation

struct HistoryPageCursor: Hashable, Sendable {
    let timestamp: Int64
    let entryID: String
}
