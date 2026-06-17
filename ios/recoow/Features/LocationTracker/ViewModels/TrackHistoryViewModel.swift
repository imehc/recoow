import Foundation
import Observation

@MainActor
@Observable
final class TrackHistoryViewModel {
    var tracks: [Track] = []
    var errorMessage: String?

    @ObservationIgnored private let repository: TrackRepository
    @ObservationIgnored private var observationTask: Task<Void, Never>?

    init(repository: TrackRepository) {
        self.repository = repository
    }

    deinit {
        observationTask?.cancel()
    }

    func startObserving() {
        guard observationTask == nil else { return }

        observationTask = Task { [weak self] in
            guard let self else { return }

            for await result in repository.observeTracks() {
                switch result {
                case .success(let tracks):
                    self.tracks = tracks
                    self.errorMessage = nil
                case .failure(let error):
                    self.errorMessage = error.localizedDescription
                }
            }
        }
    }
}
