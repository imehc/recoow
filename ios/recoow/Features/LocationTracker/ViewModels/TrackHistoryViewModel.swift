import Foundation
import Observation

@MainActor
@Observable
final class TrackHistoryViewModel {
    var tracks: [Track] = []
    var pointCounts: [String: Int] = [:]
    var errorMessage: String?

    @ObservationIgnored private let repository: TrackRepository
    @ObservationIgnored private let syncEngine: any SyncEngine
    @ObservationIgnored private var observationTask: Task<Void, Never>?

    init(repository: TrackRepository, syncEngine: any SyncEngine) {
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

            for await result in repository.observeTracks() {
                switch result {
                case .success(let tracks):
                    self.tracks = tracks
                    do {
                        self.pointCounts = try repository.fetchPointCounts(trackIDs: tracks.map(\.id))
                        self.errorMessage = nil
                    } catch {
                        self.errorMessage = error.localizedDescription
                    }
                case .failure(let error):
                    self.errorMessage = error.localizedDescription
                }
            }
        }
    }

    func pointCount(for trackID: String) -> Int {
        pointCounts[trackID, default: 0]
    }

    func deleteTracks(ids: [String]) async {
        guard ids.isEmpty == false else { return }

        do {
            try repository.deleteTracks(ids: ids)
            errorMessage = nil
            await syncEngine.enqueueScan()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func reportCannotDeleteActiveTrack() {
        errorMessage = "记录中的轨迹不能删除"
    }
}
