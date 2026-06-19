import Foundation

struct HistoryPage: Sendable {
    let entries: [HistoryEntry]
    let hasMore: Bool
}
