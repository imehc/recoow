import Foundation

struct FoodDayGroup: Identifiable, Hashable, Sendable {
    let date: Date
    let entries: [FoodEntry]
    var dayRecord: FoodDayRecord? = nil

    nonisolated var id: String {
        String(Int64(date.timeIntervalSince1970 * 1000))
    }

    nonisolated var timestamp: Int64 {
        Int64(date.timeIntervalSince1970 * 1000)
    }

    nonisolated var updatedAt: Int64 {
        max(
            dayRecord?.updatedAt ?? 0,
            entries.map(\.updatedAt).max() ?? timestamp
        )
    }

    nonisolated var title: String? {
        dayRecord?.normalizedTitle
    }

    nonisolated var entryCount: Int {
        entries.count
    }

    nonisolated var mealKinds: [FoodMealKind] {
        FoodMealKind.allCases.filter { kind in
            entries.contains { $0.foodMealKind == kind }
        }
    }

    nonisolated var sortedEntries: [FoodEntry] {
        entries.sorted {
            if $0.occurredAt == $1.occurredAt {
                return $0.id < $1.id
            }

            return $0.occurredAt < $1.occurredAt
        }
    }

    nonisolated func entries(for kind: FoodMealKind) -> [FoodEntry] {
        sortedEntries.filter { $0.foodMealKind == kind }
    }
}
