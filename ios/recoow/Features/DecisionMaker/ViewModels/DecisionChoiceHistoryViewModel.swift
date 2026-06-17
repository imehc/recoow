import Foundation
import Observation

@MainActor
@Observable
final class DecisionChoiceHistoryViewModel {
    var records: [DecisionChoiceRecord] = []
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

            for await result in repository.observeChoiceRecords() {
                switch result {
                case .success(let records):
                    self.records = records
                    self.errorMessage = nil
                case .failure(let error):
                    self.errorMessage = error.localizedDescription
                }
            }
        }
    }

    func deleteRecord(id: String) async {
        await deleteRecords(ids: [id])
    }

    func deleteRecords(ids: [String]) async {
        guard ids.isEmpty == false else { return }

        do {
            try repository.deleteChoiceRecords(ids: ids)
            errorMessage = nil
            await syncEngine.enqueueScan()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
