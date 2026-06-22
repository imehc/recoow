import SwiftUI

struct HistoryEntryRow: View {
    let entry: HistoryEntry
    let pointCount: Int
    let isActiveTrack: Bool
    let choiceRecordImageTransition: Namespace.ID
    let itemImageTransition: Namespace.ID
    let reminderImageTransition: Namespace.ID
    let billImageTransition: Namespace.ID
    let itemCategoryName: String
    let activeElapsedSeconds: Int64
    let activeDistanceMeters: Double

    var body: some View {
        switch entry {
        case .track(let track):
            TrackHistoryEntryRow(
                track: track,
                pointCount: pointCount,
                isActive: isActiveTrack,
                activeElapsedSeconds: activeElapsedSeconds,
                activeDistanceMeters: activeDistanceMeters
            )
        case .decisionChoice(let record):
            DecisionChoiceHistoryEntryRow(
                record: record,
                choiceRecordImageTransition: choiceRecordImageTransition
            )
        case .storedItem(let item):
            StoredItemHistoryEntryRow(
                item: item,
                categoryName: itemCategoryName,
                itemImageTransition: itemImageTransition
            )
        case .reminder(let reminder):
            ReminderHistoryEntryRow(
                reminder: reminder,
                reminderImageTransition: reminderImageTransition
            )
        case .bill(let bill):
            BillHistoryEntryRow(
                bill: bill,
                billImageTransition: billImageTransition
            )
        case .diary(let diary):
            DiaryHistoryEntryRow(entry: diary)
        case .anniversary(let anniversary):
            AnniversaryHistoryEntryRow(anniversary: anniversary)
        }
    }
}
