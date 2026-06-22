import Foundation

struct HistoryDeletionPlan {
    let entries: [HistoryEntry]
    let trackIDs: [String]
    let decisionRecordIDs: [String]
    let itemIDs: [String]
    let reminderIDs: [String]
    let billIDs: [String]
    let diaryIDs: [String]
    let anniversaryIDs: [String]

    init(entries: [HistoryEntry]) {
        self.entries = entries
        trackIDs = entries.compactMap(\.trackID)
        decisionRecordIDs = entries.compactMap(\.decisionRecordID)
        itemIDs = entries.compactMap(\.storedItemID)
        reminderIDs = entries.compactMap(\.reminderID)
        billIDs = entries.compactMap(\.billID)
        diaryIDs = entries.compactMap(\.diaryID)
        anniversaryIDs = entries.compactMap(\.anniversaryID)
    }

    var entryIDs: [String] {
        entries.map(\.id)
    }
}
