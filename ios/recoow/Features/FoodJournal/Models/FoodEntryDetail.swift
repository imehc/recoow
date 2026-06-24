import Foundation

struct FoodEntryDetail: Hashable, Sendable {
    let entry: FoodEntry
    let attachments: [MediaAttachment]
}
