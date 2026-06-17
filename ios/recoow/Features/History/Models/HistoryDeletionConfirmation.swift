import Foundation

struct HistoryDeletionConfirmation: Identifiable {
    let id = UUID()
    let entries: [HistoryEntry]
}
