import Foundation

@MainActor
struct DiaryLinkSourceProvider: Identifiable {
    let sourceType: DiaryLinkSourceType
    let fetchRecords: @MainActor (AppContainer) throws -> [DiaryLinkedRecord]

    var id: DiaryLinkSourceType { sourceType }
}

@MainActor
enum DiaryLinkSourceRegistry {
    static let providers: [DiaryLinkSourceProvider] = [
        DiaryLinkSourceProvider(sourceType: .track) { container in
            try container.trackRepository.fetchRecentTracks().map(DiaryLinkedRecord.track)
        },
        DiaryLinkSourceProvider(sourceType: .bill) { container in
            try container.billRepository.fetchRecentBills().map(DiaryLinkedRecord.bill)
        },
        DiaryLinkSourceProvider(sourceType: .reminder) { container in
            try container.reminderRepository.fetchRecentReminders().map(DiaryLinkedRecord.reminder)
        },
        DiaryLinkSourceProvider(sourceType: .anniversary) { container in
            try container.anniversaryRepository.fetchRecentAnniversaries().map(DiaryLinkedRecord.anniversary)
        },
        DiaryLinkSourceProvider(sourceType: .storedItem) { container in
            let categories = try container.itemLocatorRepository.fetchCategories()
            let categoryNamesByID = Dictionary(uniqueKeysWithValues: categories.map { ($0.id, $0.name) })

            return try container.itemLocatorRepository.fetchRecentItems().map { item in
                DiaryLinkedRecord.storedItem(item, categoryName: item.categoryID.flatMap { categoryNamesByID[$0] })
            }
        },
        DiaryLinkSourceProvider(sourceType: .decisionChoice) { container in
            try container.decisionRepository.fetchRecentChoiceRecords().map(DiaryLinkedRecord.decisionChoice)
        }
    ]

    static func provider(for sourceType: DiaryLinkSourceType) -> DiaryLinkSourceProvider? {
        providers.first { $0.sourceType == sourceType }
    }
}
