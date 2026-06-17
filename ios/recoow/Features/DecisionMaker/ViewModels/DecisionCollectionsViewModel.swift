import Foundation
import Observation

@MainActor
@Observable
final class DecisionCollectionsViewModel {
    var collections: [DecisionCollection] = []
    var errorMessage: String?

    @ObservationIgnored private let repository: DecisionRepository
    @ObservationIgnored private let syncEngine: any SyncEngine
    @ObservationIgnored private var observationTask: Task<Void, Never>?

    init(repository: DecisionRepository, syncEngine: any SyncEngine) {
        self.repository = repository
        self.syncEngine = syncEngine
    }

    deinit {
        observationTask?.cancel()
    }

    func startObserving() {
        guard observationTask == nil else { return }

        observationTask = Task { [weak self] in
            guard let self else { return }

            for await result in repository.observeCollections() {
                switch result {
                case .success(let collections):
                    self.collections = collections
                    self.errorMessage = nil
                case .failure(let error):
                    self.errorMessage = error.localizedDescription
                }
            }
        }
    }

    func makeCollection(title: String, note: String?) -> DecisionCollection {
        DecisionCollection.makeNew(title: title, note: note, deviceID: repository.deviceID)
    }

    func save(_ collection: DecisionCollection) async {
        do {
            _ = try repository.saveCollection(collection)
            errorMessage = nil
            await syncEngine.enqueueScan()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func delete(id: String) async {
        do {
            try repository.deleteCollection(id: id)
            errorMessage = nil
            await syncEngine.enqueueScan()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
