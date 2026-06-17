import CoreLocation
import Foundation
import Observation

@MainActor
@Observable
final class LocationTrackerViewModel {
    enum State: Equatable {
        case idle
        case requestingAuthorization
        case recording
        case stopped
        case failed(String)

        var title: String {
            switch self {
            case .idle:
                "待开始"
            case .requestingAuthorization:
                "请求定位权限"
            case .recording:
                "记录中"
            case .stopped:
                "已停止"
            case .failed:
                "发生错误"
            }
        }
    }

    var selectedAccuracy: LocationAccuracy = .tenMeters
    var state: State = .idle
    var currentCoordinate: CLLocationCoordinate2D?
    var elapsedSeconds: Int64 = 0
    var pointCount = 0
    var finishedTrackID: String?
    private(set) var currentTrackID: String?
    private(set) var currentTrackName: String?
    private(set) var currentDistanceMeters: Double = 0
    private(set) var currentMaxSpeedMetersPerSecond: Double?

    @ObservationIgnored private let repository: TrackRepository
    @ObservationIgnored private let locationService: LocationService
    @ObservationIgnored private let syncEngine: any SyncEngine
    @ObservationIgnored private var recordingTask: Task<Void, Never>?
    @ObservationIgnored private var elapsedTask: Task<Void, Never>?
    @ObservationIgnored private var currentTrack: Track?
    @ObservationIgnored private var pendingPoints: [TrackPoint] = []
    @ObservationIgnored private var lastFlushDate = Date()
    @ObservationIgnored private var lastLocation: CLLocation?

    init(repository: TrackRepository, locationService: LocationService, syncEngine: any SyncEngine) {
        self.repository = repository
        self.locationService = locationService
        self.syncEngine = syncEngine
    }

    var isRecording: Bool {
        state == .recording || state == .requestingAuthorization
    }

    func start() async {
        guard recordingTask == nil else { return }

        state = .requestingAuthorization
        let authorization = await locationService.requestAuthorization()

        guard authorization == .authorizedAlways || authorization == .authorizedWhenInUse else {
            state = .failed("定位权限未开启")
            return
        }

        let track = Track.makeNew(accuracy: selectedAccuracy, deviceID: repository.deviceID)

        do {
            try repository.insertTrack(track)
            currentTrack = track
            currentTrackID = track.id
            currentTrackName = track.name
            state = .recording
            pointCount = 0
            elapsedSeconds = 0
            finishedTrackID = nil
            pendingPoints = []
            lastLocation = nil
            currentDistanceMeters = 0
            currentMaxSpeedMetersPerSecond = nil
            lastFlushDate = Date()
            startElapsedTimer(startedAt: track.startedAt)
            startLocationUpdates(trackID: track.id, accuracy: selectedAccuracy)
            await syncEngine.enqueueScan()
        } catch {
            state = .failed("创建轨迹失败: \(error.localizedDescription)")
        }
    }

    func stop() async {
        guard let track = currentTrack else { return }

        recordingTask?.cancel()
        recordingTask = nil
        elapsedTask?.cancel()
        elapsedTask = nil
        await locationService.stop()

        do {
            try flushPendingPoints()
            let endedAt = SyncableTimestamp.nowMilliseconds()
            let duration = max(1, (endedAt - track.startedAt) / 1000)
            let averageSpeed = currentDistanceMeters > 0 ? currentDistanceMeters / Double(duration) : nil
            let finished = try repository.finishTrack(
                id: track.id,
                endedAt: endedAt,
                distanceMeters: currentDistanceMeters,
                durationSeconds: duration,
                averageSpeedMetersPerSecond: averageSpeed,
                maxSpeedMetersPerSecond: currentMaxSpeedMetersPerSecond
            )
            currentTrack = finished
            finishedTrackID = finished?.id
            state = .stopped
            await syncEngine.enqueueScan()
        } catch {
            state = .failed("保存轨迹失败: \(error.localizedDescription)")
        }
    }

    private func startLocationUpdates(trackID: String, accuracy: LocationAccuracy) {
        recordingTask = Task { [weak self] in
            guard let self else { return }
            let updates = await locationService.liveUpdates(accuracy: accuracy)

            for await update in updates {
                guard Task.isCancelled == false else { break }
                guard let location = update.location else { continue }
                handle(location: location, trackID: trackID)
            }
        }
    }

    private func handle(location: CLLocation, trackID: String) {
        currentCoordinate = location.coordinate

        if let lastLocation {
            currentDistanceMeters += location.distance(from: lastLocation)
        }
        lastLocation = location

        if location.speed >= 0 {
            currentMaxSpeedMetersPerSecond = max(currentMaxSpeedMetersPerSecond ?? 0, location.speed)
        }

        let point = TrackPoint.make(trackID: trackID, location: location, deviceID: repository.deviceID)
        pendingPoints.append(point)
        pointCount += 1

        if pendingPoints.count >= 5 || Date().timeIntervalSince(lastFlushDate) >= 2 {
            do {
                try flushPendingPoints()
            } catch {
                state = .failed("写入采样点失败: \(error.localizedDescription)")
            }
        }
    }

    private func flushPendingPoints() throws {
        guard pendingPoints.isEmpty == false else { return }
        let points = pendingPoints
        pendingPoints.removeAll(keepingCapacity: true)
        try repository.appendPoints(points)
        lastFlushDate = Date()
    }

    private func startElapsedTimer(startedAt: Int64) {
        elapsedTask?.cancel()
        elapsedTask = Task { [weak self] in
            while Task.isCancelled == false {
                let now = SyncableTimestamp.nowMilliseconds()
                self?.elapsedSeconds = max(0, (now - startedAt) / 1000)
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }
}
