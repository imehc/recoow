import Foundation
import Observation

@MainActor
@Observable
final class ItemLocatorViewModel {
    var items: [StoredItem] = []
    var categories: [ItemCategory] = []
    var searchText = ""
    var selectedCategoryID: String?
    var errorMessage: String?

    @ObservationIgnored private let repository: ItemLocatorRepository
    @ObservationIgnored private let syncEngine: any SyncEngine
    @ObservationIgnored private var itemObservationTask: Task<Void, Never>?
    @ObservationIgnored private var categoryObservationTask: Task<Void, Never>?

    init(repository: ItemLocatorRepository, syncEngine: any SyncEngine) {
        self.repository = repository
        self.syncEngine = syncEngine
    }

    deinit {
        itemObservationTask?.cancel()
        categoryObservationTask?.cancel()
    }

    var filteredItems: [StoredItem] {
        items.filter { item in
            let matchesCategory = selectedCategoryID == nil || item.categoryID == selectedCategoryID
            guard matchesCategory else { return false }

            let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
            guard query.isEmpty == false else { return true }

            return searchableText(for: item).localizedCaseInsensitiveContains(query)
        }
    }

    func startObserving() {
        startItemObservation()
        startCategoryObservation()
    }

    func item(id: String) -> StoredItem? {
        items.first { $0.id == id }
    }

    func loadItemIfNeeded(id: String) async {
        guard item(id: id) == nil else { return }

        do {
            if let item = try repository.fetchItem(id: id) {
                upsertItem(item)
            }
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func loadCategoriesIfNeeded() async {
        guard categories.isEmpty else { return }

        do {
            categories = try repository.fetchCategories()
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func category(id: String?) -> ItemCategory? {
        guard let id else { return nil }
        return categories.first { $0.id == id }
    }

    func categoryName(for item: StoredItem) -> String {
        category(id: item.categoryID)?.name ?? "未分类"
    }

    func makeItem(
        categoryID: String?,
        title: String,
        location: String,
        note: String?,
        tags: String?,
        searchKeywords: String?,
        imageData: Data?,
        imageAssetID: String? = nil
    ) -> StoredItem {
        StoredItem.makeNew(
            categoryID: categoryID,
            title: title,
            location: location,
            note: note,
            tags: tags,
            searchKeywords: searchKeywords,
            imageData: imageData,
            imageAssetID: imageAssetID,
            deviceID: repository.deviceID
        )
    }

    func makeCategory(name: String, note: String?) -> ItemCategory {
        ItemCategory.makeNew(name: name, note: note, deviceID: repository.deviceID)
    }

    func save(_ item: StoredItem) async {
        do {
            let savedItem = try repository.saveItem(item)
            upsertItem(savedItem)
            errorMessage = nil
            await syncEngine.enqueueScan()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func deleteItem(id: String) async {
        do {
            try repository.deleteItem(id: id)
            items.removeAll { $0.id == id }
            errorMessage = nil
            await syncEngine.enqueueScan()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func save(_ category: ItemCategory) async {
        do {
            let savedCategory = try repository.saveCategory(category)
            upsertCategory(savedCategory)
            errorMessage = nil
            await syncEngine.enqueueScan()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func deleteCategory(id: String) async {
        do {
            try repository.deleteCategory(id: id)
            if selectedCategoryID == id {
                selectedCategoryID = nil
            }
            categories.removeAll { $0.id == id }
            errorMessage = nil
            await syncEngine.enqueueScan()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func upsertItem(_ item: StoredItem) {
        if let index = items.firstIndex(where: { $0.id == item.id }) {
            items[index] = item
        } else {
            items.insert(item, at: 0)
        }
    }

    private func upsertCategory(_ category: ItemCategory) {
        if let index = categories.firstIndex(where: { $0.id == category.id }) {
            categories[index] = category
        } else {
            categories.append(category)
            categories.sort { $0.name < $1.name }
        }
    }

    private func startItemObservation() {
        guard itemObservationTask == nil else { return }

        itemObservationTask = Task { [weak self] in
            guard let self else { return }

            for await result in repository.observeItems() {
                switch result {
                case .success(let items):
                    self.items = items
                    self.errorMessage = nil
                case .failure(let error):
                    self.errorMessage = error.localizedDescription
                }
            }
        }
    }

    private func startCategoryObservation() {
        guard categoryObservationTask == nil else { return }

        categoryObservationTask = Task { [weak self] in
            guard let self else { return }

            for await result in repository.observeCategories() {
                switch result {
                case .success(let categories):
                    self.categories = categories
                    self.errorMessage = nil
                case .failure(let error):
                    self.errorMessage = error.localizedDescription
                }
            }
        }
    }

    private func searchableText(for item: StoredItem) -> String {
        [
            item.title,
            item.location,
            item.note,
            item.tags,
            item.searchKeywords,
            categoryName(for: item)
        ]
        .compactMap(\.self)
        .joined(separator: " ")
    }
}
