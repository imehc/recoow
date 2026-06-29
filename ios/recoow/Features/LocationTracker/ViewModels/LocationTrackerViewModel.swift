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
        case paused
        case stopped
        case failed(String)

        var title: String {
            switch self {
            case .idle:
                AppLocalization.string("待开始")
            case .requestingAuthorization:
                AppLocalization.string("请求定位权限")
            case .recording:
                AppLocalization.string("记录中")
            case .paused:
                AppLocalization.string("已暂停")
            case .stopped:
                AppLocalization.string("已停止")
            case .failed:
                AppLocalization.string("发生错误")
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
    @ObservationIgnored private var activeBaseElapsedSeconds: Int64 = 0
    @ObservationIgnored private var activeStartedAt: Int64?

    init(repository: TrackRepository, locationService: LocationService, syncEngine: any SyncEngine) {
        self.repository = repository
        self.locationService = locationService
        self.syncEngine = syncEngine
    }

    var isRecording: Bool {
        state == .recording || state == .requestingAuthorization
    }

    var isPaused: Bool {
        state == .paused
    }

    func start() async {
        guard recordingTask == nil else { return }

        state = .requestingAuthorization
        let authorization = await locationService.requestAuthorization()

        guard authorization == .authorizedAlways || authorization == .authorizedWhenInUse else {
            state = .failed(AppLocalization.string("定位权限未开启"))
            return
        }

        if let currentTrack, currentTrack.status == .paused {
            await resume(track: currentTrack)
            return
        }

        if let pausedTrack = try? repository.fetchPausedTrack() {
            await resume(track: pausedTrack)
            return
        }

        let track = Track.makeNew(accuracy: selectedAccuracy, deviceID: repository.deviceID)

        do {
            try repository.insertTrack(track)
            applyCurrentTrack(track)
            state = .recording
            pointCount = 0
            elapsedSeconds = 0
            finishedTrackID = nil
            pendingPoints = []
            lastLocation = nil
            currentDistanceMeters = 0
            currentMaxSpeedMetersPerSecond = nil
            activeBaseElapsedSeconds = 0
            activeStartedAt = SyncableTimestamp.nowMilliseconds()
            lastFlushDate = Date()
            startElapsedTimer(baseSeconds: 0, activeStartedAt: activeStartedAt)
            startLocationUpdates(trackID: track.id, accuracy: selectedAccuracy)
            await syncEngine.enqueueScan()
        } catch {
            state = .failed(AppLocalization.format("创建轨迹失败: %@", error.localizedDescription))
        }
    }

    func pause() async {
        guard let track = currentTrack else { return }

        recordingTask?.cancel()
        recordingTask = nil
        elapsedTask?.cancel()
        elapsedTask = nil
        await locationService.stop()

        do {
            let paused = try pauseCurrentTrack(track)
            if let paused {
                try restore(track: paused)
            }
            state = .paused
            activeStartedAt = nil
            lastLocation = nil
            await syncEngine.enqueueScan()
        } catch {
            state = .failed(AppLocalization.format("暂停轨迹失败: %@", error.localizedDescription))
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
            let finished = try finishCurrentTrack(track)
            currentTrack = finished
            finishedTrackID = finished?.id
            state = .stopped
            currentTrackID = nil
            currentTrackName = nil
            activeStartedAt = nil
            lastLocation = nil
            await syncEngine.enqueueScan()
        } catch {
            state = .failed(AppLocalization.format("保存轨迹失败: %@", error.localizedDescription))
        }
    }

    func prepareForSuspension() {
        guard let track = currentTrack else { return }

        do {
            try flushPendingPoints()

            if isRecording {
                let points = try repository.fetchPoints(trackID: track.id)
                let metrics = TrackSegmentAnalyzer.metrics(for: points)
                currentDistanceMeters = metrics.distanceMeters
                currentMaxSpeedMetersPerSecond = metrics.maxSpeedMetersPerSecond
            }
        } catch {
            state = .failed(AppLocalization.format("写入采样点失败: %@", error.localizedDescription))
        }
    }

    func finishForAppTermination() {
        guard let track = currentTrack else { return }

        recordingTask?.cancel()
        recordingTask = nil
        elapsedTask?.cancel()
        elapsedTask = nil

        do {
            let paused = try pauseCurrentTrack(track)
            currentTrack = paused
            state = .paused
        } catch {
            state = .failed(AppLocalization.format("暂停轨迹失败: %@", error.localizedDescription))
        }
    }

    func pauseInterruptedRecordingIfNeeded() async {
        guard currentTrack == nil, isRecording == false else { return }

        do {
            let pausedTracks = try repository.pauseInterruptedTracks()
            if let pausedTrack = pausedTracks.first {
                try restore(track: pausedTrack)
            } else if let pausedTrack = try repository.fetchPausedTrack() {
                try restore(track: pausedTrack)
            } else {
                return
            }

            state = .paused
            await syncEngine.enqueueScan()
        } catch {
            state = .failed(AppLocalization.format("恢复轨迹失败: %@", error.localizedDescription))
        }
    }

    func applyUpdatedTrackDetails(_ track: Track) {
        guard currentTrackID == track.id else { return }

        currentTrack = track
        currentTrackName = track.name
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
                state = .failed(AppLocalization.format("写入采样点失败: %@", error.localizedDescription))
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

    private func pauseCurrentTrack(_ track: Track) throws -> Track? {
        try flushPendingPoints()
        let points = try repository.fetchPoints(trackID: track.id)
        let metrics = TrackSegmentAnalyzer.metrics(for: points)
        let durationSeconds = max(metrics.durationSeconds, currentElapsedSeconds())
        try regenerateAutoSegments(trackID: track.id, points: points)

        return try repository.pauseTrack(
            id: track.id,
            distanceMeters: metrics.distanceMeters,
            durationSeconds: durationSeconds,
            averageSpeedMetersPerSecond: metrics.averageSpeedMetersPerSecond,
            maxSpeedMetersPerSecond: metrics.maxSpeedMetersPerSecond
        )
    }

    private func finishCurrentTrack(_ track: Track) throws -> Track? {
        try flushPendingPoints()
        let points = try repository.fetchPoints(trackID: track.id)
        let metrics = TrackSegmentAnalyzer.metrics(for: points)
        try regenerateAutoSegments(trackID: track.id, points: points)

        let endedAt = SyncableTimestamp.nowMilliseconds()
        let durationSeconds = max(metrics.durationSeconds, currentElapsedSeconds(now: endedAt))

        return try repository.finishTrack(
            id: track.id,
            endedAt: endedAt,
            distanceMeters: metrics.distanceMeters,
            durationSeconds: durationSeconds,
            averageSpeedMetersPerSecond: metrics.averageSpeedMetersPerSecond,
            maxSpeedMetersPerSecond: metrics.maxSpeedMetersPerSecond
        )
    }

    private func resume(track: Track) async {
        do {
            guard let resumed = try repository.resumeTrack(id: track.id) else { return }
            try restore(track: resumed)
            state = .recording
            finishedTrackID = nil
            pendingPoints = []
            lastLocation = nil
            activeStartedAt = SyncableTimestamp.nowMilliseconds()
            startElapsedTimer(baseSeconds: activeBaseElapsedSeconds, activeStartedAt: activeStartedAt)
            startLocationUpdates(trackID: resumed.id, accuracy: selectedAccuracy)
            await syncEngine.enqueueScan()
        } catch {
            state = .failed(AppLocalization.format("恢复轨迹失败: %@", error.localizedDescription))
        }
    }

    private func restore(track: Track) throws {
        applyCurrentTrack(track)
        let points = try repository.fetchPoints(trackID: track.id)
        let metrics = TrackSegmentAnalyzer.metrics(for: points)
        let durationSeconds = max(track.durationSeconds, metrics.durationSeconds)

        pointCount = points.count
        elapsedSeconds = durationSeconds
        activeBaseElapsedSeconds = durationSeconds
        currentDistanceMeters = metrics.distanceMeters
        currentMaxSpeedMetersPerSecond = metrics.maxSpeedMetersPerSecond
        currentCoordinate = points.last?.coordinate
        lastLocation = nil
        lastFlushDate = Date()
    }

    private func applyCurrentTrack(_ track: Track) {
        currentTrack = track
        currentTrackID = track.id
        currentTrackName = track.name
    }

    private func regenerateAutoSegments(trackID: String, points: [TrackPoint]) throws {
        let segments = TrackSegmentAnalyzer.segments(for: points, trackID: trackID, deviceID: repository.deviceID)
        try repository.replaceAutoSegments(trackID: trackID, with: segments)
    }

    private func startElapsedTimer(baseSeconds: Int64, activeStartedAt: Int64?) {
        elapsedTask?.cancel()
        elapsedTask = Task { [weak self] in
            while Task.isCancelled == false {
                let now = SyncableTimestamp.nowMilliseconds()
                let activeSeconds = activeStartedAt.map { max(0, (now - $0) / 1000) } ?? 0
                self?.elapsedSeconds = baseSeconds + activeSeconds
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }

    private func currentElapsedSeconds(now: Int64 = SyncableTimestamp.nowMilliseconds()) -> Int64 {
        let activeSeconds = activeStartedAt.map { max(0, (now - $0) / 1000) } ?? 0
        return max(elapsedSeconds, activeBaseElapsedSeconds + activeSeconds)
    }
}
