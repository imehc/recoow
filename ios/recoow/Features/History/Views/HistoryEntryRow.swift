import SwiftUI

struct HistoryEntryRow: View {
    let entry: HistoryEntry
    let pointCount: Int
    let isActiveTrack: Bool
    let choiceRecordImageTransition: Namespace.ID
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
        }
    }
}
