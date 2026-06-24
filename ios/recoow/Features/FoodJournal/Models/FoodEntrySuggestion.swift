import Foundation

struct FoodEntrySuggestion: Identifiable, Hashable, Sendable {
    let title: String
    let mealKind: FoodMealKind
    let portion: String?
    let useCount: Int
    let latestOccurredAt: Int64

    var id: String {
        [
            title.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(),
            mealKind.rawValue,
            portion?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
        ]
        .joined(separator: "|")
    }
}
