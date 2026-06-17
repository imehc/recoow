import Foundation
import Observation

@MainActor
@Observable
final class DecisionOptionsViewModel {
    var collection: DecisionCollection?
    var options: [DecisionOption] = []
    var selectedOption: DecisionOption?
    var selectedChoiceRecord: DecisionChoiceRecord?
    var choiceRecords: [DecisionChoiceRecord] = []
    var errorMessage: String?

    @ObservationIgnored private let collectionID: String
    @ObservationIgnored private let repository: DecisionRepository
    @ObservationIgnored private let syncEngine: any SyncEngine
    @ObservationIgnored private var optionsObservationTask: Task<Void, Never>?
    @ObservationIgnored private var historyObservationTask: Task<Void, Never>?

    init(collectionID: String, repository: DecisionRepository, syncEngine: any SyncEngine) {
        self.collectionID = collectionID
        self.repository = repository
        self.syncEngine = syncEngine
    }

    deinit {
        optionsObservationTask?.cancel()
        historyObservationTask?.cancel()
    }

    var enabledOptions: [DecisionOption] {
        options.filter { $0.isEnabled }
    }

    func startObserving() {
        guard optionsObservationTask == nil else { return }

        loadCollection()
        optionsObservationTask = Task { [weak self] in
            guard let self else { return }

            for await result in repository.observeOptions(collectionID: collectionID) {
                switch result {
                case .success(let options):
                    self.options = options
                    self.errorMessage = nil
                case .failure(let error):
                    self.errorMessage = error.localizedDescription
                }
            }
        }

        historyObservationTask = Task { [weak self] in
            guard let self else { return }

            for await result in repository.observeChoiceRecords(collectionID: collectionID) {
                switch result {
                case .success(let records):
                    self.choiceRecords = records
                    self.errorMessage = nil
                case .failure(let error):
                    self.errorMessage = error.localizedDescription
                }
            }
        }
    }

    func makeOption(
        title: String,
        detail: String?,
        customInfo: String?,
        imageData: Data?,
        weight: Int,
        isEnabled: Bool
    ) -> DecisionOption {
        DecisionOption.makeNew(
            collectionID: collectionID,
            title: title,
            detail: detail,
            customInfo: customInfo,
            imageData: imageData,
            weight: weight,
            isEnabled: isEnabled,
            deviceID: repository.deviceID
        )
    }

    func save(_ option: DecisionOption) async {
        do {
            _ = try repository.saveOption(option)
            errorMessage = nil
            await syncEngine.enqueueScan()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func delete(id: String) async {
        do {
            try repository.deleteOption(id: id)
            errorMessage = nil
            await syncEngine.enqueueScan()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func chooseRandomOption() async {
        let candidates = enabledOptions
        guard candidates.isEmpty == false else {
            selectedOption = nil
            selectedChoiceRecord = nil
            errorMessage = "请先添加至少一个启用的选项"
            return
        }

        let totalWeight = candidates.reduce(0) { partialResult, option in
            partialResult + max(1, option.weight)
        }
        var target = Int.random(in: 1...totalWeight)

        for option in candidates {
            target -= max(1, option.weight)

            if target <= 0 {
                await recordChoice(option)
                return
            }
        }

        if let option = candidates.last {
            await recordChoice(option)
        }
    }

    func deleteChoiceRecord(id: String) async {
        do {
            try repository.deleteChoiceRecords(ids: [id])
            errorMessage = nil
            await syncEngine.enqueueScan()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func loadCollection() {
        do {
            collection = try repository.fetchCollection(id: collectionID)
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func recordChoice(_ option: DecisionOption) async {
        selectedOption = option

        let record = DecisionChoiceRecord.makeNew(
            collectionID: collectionID,
            collectionTitle: collection?.title ?? "选什么",
            option: option,
            deviceID: repository.deviceID
        )

        do {
            selectedChoiceRecord = try repository.insertChoiceRecord(record)
            errorMessage = nil
            await syncEngine.enqueueScan()
        } catch {
            selectedChoiceRecord = nil
            errorMessage = error.localizedDescription
        }
    }
}
