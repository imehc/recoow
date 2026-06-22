import MapKit
import SwiftUI

struct TrackDetailView: View {
    @Environment(AppContainer.self) private var container
    @State private var track: Track?
    @State private var points: [TrackPoint] = []
    @State private var segments: [TrackSegment] = []
    @State private var displayCoordinates: [CLLocationCoordinate2D] = []
    @State private var errorMessage: String?
    @State private var cameraPosition: MapCameraPosition = .automatic
    @State private var editingTrack: Track?
    @State private var segmentEditorRequest: TrackSegmentEditorRequest?
    @State private var selectedSegmentID: String?
    @State private var selectedPlaceID: String?
    @State private var selectedQualityOccurrenceID: String?
    @State private var detailMode: TrackDetailMode = .playback
    @State private var playbackIndex = 0
    @State private var isPlaybackPlaying = false
    @State private var playbackSpeed: TrackPlaybackSpeed = .normal
    @State private var followsPlayback = false
    @State private var showsDetectedPlaces = true
    @State private var showsQualityIssues = true
    @State private var showsPlaybackPath = true
    @State private var associatedBills: [BillRecord] = []
    @State private var associatedDiaryEntries: [DiaryEntry] = []

    let trackID: String

    var body: some View {
        Form {
            Section {
                TrackMapView(
                    cameraPosition: $cameraPosition,
                    selectedSegmentID: $selectedSegmentID,
                    points: points,
                    segments: segments,
                    displayCoordinates: displayCoordinates,
                    playbackCoordinate: playbackCoordinate,
                    playbackCoordinates: playbackCoordinates,
                    detectedPlaces: detectedPlaces,
                    selectedPlaceID: selectedPlaceID,
                    qualityOccurrences: qualityOccurrences,
                    selectedQualityOccurrenceID: selectedQualityOccurrenceID,
                    showsDetectedPlaces: $showsDetectedPlaces,
                    showsQualityIssues: $showsQualityIssues,
                    showsPlaybackPath: $showsPlaybackPath,
                    selectPlace: selectPlace,
                    selectQualityOccurrence: focusQualityOccurrence,
                    showOverview: showOverview
                )
                .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0))

                if segments.isEmpty == false {
                    TrackSegmentStrip(
                        segments: segments,
                        selectedSegmentID: selectedSegmentID,
                        totalDistanceMeters: displayDistanceMeters,
                        showOverview: showOverview,
                        selectSegment: selectSegment
                    )
                    .padding(.vertical, 4)
                }

                if let selectedSegment {
                    TrackSelectedSegmentSummary(
                        segment: selectedSegment,
                        allowsEditing: isActiveTrack == false,
                        canSplit: splitCandidatePoints(for: selectedSegment).isEmpty == false,
                        canMergePrevious: previousSegment(for: selectedSegment) != nil,
                        canMergeNext: nextSegment(for: selectedSegment) != nil,
                        canAdjustBoundaries: boundaryCandidatePoints(for: selectedSegment).count > 2 &&
                            (previousSegment(for: selectedSegment) != nil || nextSegment(for: selectedSegment) != nil),
                        updateMotionType: { motionType in
                            updateSegment(selectedSegment, motionType: motionType)
                        },
                        splitSegment: {
                            segmentEditorRequest = TrackSegmentEditorRequest(segmentID: selectedSegment.id, mode: .split)
                        },
                        editBoundaries: {
                            segmentEditorRequest = TrackSegmentEditorRequest(segmentID: selectedSegment.id, mode: .boundaries)
                        },
                        mergePrevious: {
                            mergeSegment(selectedSegment, direction: .previous)
                        },
                        mergeNext: {
                            mergeSegment(selectedSegment, direction: .next)
                        }
                    )
                }

                if points.count > 1 {
                    Picker(AppLocalization.string("轨迹详情"), selection: $detailMode) {
                        ForEach(TrackDetailMode.allCases) { mode in
                            Label(mode.title, systemImage: mode.systemImage)
                                .tag(mode)
                        }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()

                    switch detailMode {
                    case .playback:
                        TrackPlaybackPanel(
                            points: points,
                            currentSegment: playbackSegment,
                            playbackIndex: $playbackIndex,
                            isPlaying: $isPlaybackPlaying,
                            playbackSpeed: $playbackSpeed,
                            followsPlayback: $followsPlayback,
                            togglePlayback: togglePlayback,
                            focusPlaybackPoint: focusPlaybackPoint
                        )
                    case .insights:
                        TrackInsightsPanel(
                            segments: segments,
                            detectedPlaces: detectedPlaces,
                            qualityIssues: qualityIssues,
                            selectedPlaceID: selectedPlaceID,
                            selectedQualityOccurrenceID: selectedQualityOccurrenceID,
                            selectPlace: selectPlace,
                            selectQualityIssue: focusQualityIssue,
                            selectQualityOccurrence: focusQualityOccurrence
                        )
                    case .related:
                        TrackRelatedRecordsPanel(
                            bills: associatedBills,
                            diaryEntries: associatedDiaryEntries
                        )
                    }
                }
            }

            if let track {
                Section(AppLocalization.string("摘要")) {
                    LabeledContent(AppLocalization.string("名称"), value: track.name)

                    if let note = track.note, note.isEmpty == false {
                        LabeledContent {
                            Text(note)
                                .multilineTextAlignment(.trailing)
                        } label: {
                            Text(AppLocalization.string("备注"))
                        }
                    }

                    LabeledContent(AppLocalization.string("状态")) {
                        Text(statusTitle)
                            .foregroundStyle(statusColor)
                    }

                    LabeledContent(AppLocalization.string("距离"), value: AppFormatters.distance(displayDistanceMeters))
                    LabeledContent(AppLocalization.string("时长"), value: AppFormatters.duration(displayDurationSeconds))
                    LabeledContent(AppLocalization.string("采样点"), value: "\(displayPointCount)")
                    LabeledContent(AppLocalization.string("精度"), value: "\(track.desiredAccuracyMeters)m")
                    LabeledContent(AppLocalization.string("平均速度"), value: AppFormatters.speed(displayAverageSpeed))
                    LabeledContent(AppLocalization.string("最高速度"), value: AppFormatters.speed(displayMaxSpeed))
                }
            } else if errorMessage == nil {
                Section {
                    ProgressView(AppLocalization.string("正在加载"))
                }
            }

            if let errorMessage {
                Section {
                    Label(errorMessage, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                }
            }
        }
        .navigationTitle(track?.name ?? AppLocalization.string("轨迹详情"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if let track {
                    Button(AppLocalization.string("编辑"), systemImage: "square.and.pencil") {
                        editingTrack = track
                    }
                }
            }
        }
        .sheet(item: $editingTrack) { track in
            NavigationStack {
                TrackEditView(track: track) { updatedTrack in
                    self.track = updatedTrack
                }
            }
        }
        .sheet(item: $segmentEditorRequest) { request in
            if let segment = segments.first(where: { $0.id == request.segmentID }) {
                switch request.mode {
                case .split:
                    NavigationStack {
                        TrackSegmentSplitEditorSheet(
                            segment: segment,
                            points: points
                        ) { timestampMilliseconds in
                            splitSegment(segment, at: timestampMilliseconds)
                        }
                    }
                case .boundaries:
                    NavigationStack {
                        TrackSegmentBoundaryEditorSheet(
                            segment: segment,
                            previousSegment: previousSegment(for: segment),
                            nextSegment: nextSegment(for: segment),
                            points: points
                        ) { startedAt, endedAt in
                            updateSegmentBoundaries(segment, startedAt: startedAt, endedAt: endedAt)
                        }
                    }
                }
            } else {
                ContentUnavailableView(AppLocalization.string("分段不存在"), systemImage: "point.topleft.down.curvedto.point.bottomright.up")
            }
        }
        .task(id: trackID) {
            load()
        }
        .onChange(of: container.locationTrackerViewModel.pointCount) { _, _ in
            reloadIfActive()
        }
        .onChange(of: playbackIndex) { _, _ in
            highlightPlaybackSegment()
            followPlaybackIfNeeded()
        }
        .onChange(of: selectedSegmentID) { _, newValue in
            guard newValue != nil else { return }
            selectedPlaceID = nil
            selectedQualityOccurrenceID = nil
        }
        .onChange(of: detailMode) { _, newMode in
            if newMode == .playback {
                highlightPlaybackSegment()
            }
        }
        .task(id: playbackTaskTrigger) {
            guard isPlaybackPlaying else { return }

            while Task.isCancelled == false, isPlaybackPlaying {
                try? await Task.sleep(nanoseconds: playbackSpeed.intervalNanoseconds)
                advancePlayback()
            }
        }
    }

    private func load() {
        do {
            track = try container.trackRepository.fetchTrack(id: trackID)
            points = try container.trackRepository.fetchPoints(trackID: trackID)
            let storedSegments = try container.trackRepository.fetchSegments(trackID: trackID)
            if storedSegments.isEmpty || isActiveTrack {
                let analyzedSegments = TrackSegmentAnalyzer.segments(
                    for: points,
                    trackID: trackID,
                    deviceID: container.trackRepository.deviceID
                )

                if storedSegments.isEmpty, isActiveTrack == false, analyzedSegments.isEmpty == false {
                    try container.trackRepository.replaceAutoSegments(trackID: trackID, with: analyzedSegments)
                }

                segments = analyzedSegments
            } else {
                segments = storedSegments
            }
            displayCoordinates = points.map { point in
                CoordinateTransform.mapDisplayCoordinate(forWGS84: point.coordinate)
            }
            let relatedRecordsErrorMessage = loadRelatedRecords()
            cameraPosition = Self.cameraPosition(for: displayCoordinates)
            playbackIndex = min(playbackIndex, max(points.count - 1, 0))
            let shouldResetSelection = selectedSegmentID.map { selectedSegmentID in
                segments.contains { $0.id == selectedSegmentID } == false
            } ?? false

            if shouldResetSelection {
                selectedSegmentID = nil
            }
            errorMessage = relatedRecordsErrorMessage
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func loadRelatedRecords() -> String? {
        guard let first = points.first, let last = points.last else {
            associatedBills = []
            associatedDiaryEntries = []
            return nil
        }

        do {
            associatedBills = try container.billRepository.fetchBills(
                from: first.timestampMilliseconds,
                to: last.timestampMilliseconds
            )
            associatedDiaryEntries = try container.diaryRepository.fetchEntries(
                from: first.timestampMilliseconds,
                to: last.timestampMilliseconds
            )
            return nil
        } catch {
            associatedBills = []
            associatedDiaryEntries = []
            return error.localizedDescription
        }
    }

    private func reloadIfActive() {
        guard isActiveTrack else { return }
        load()
    }

    private var isActiveTrack: Bool {
        container.locationTrackerViewModel.isRecording &&
            container.locationTrackerViewModel.currentTrackID == trackID
    }

    private var isCurrentTrack: Bool {
        container.locationTrackerViewModel.currentTrackID == trackID
    }

    private var statusTitle: String {
        if isActiveTrack {
            return AppLocalization.string("记录中")
        }

        if isCurrentTrack, container.locationTrackerViewModel.isPaused {
            return AppLocalization.string("已暂停")
        }

        return track?.status.title ?? AppLocalization.string("未知")
    }

    private var statusColor: Color {
        if isActiveTrack {
            return .green
        }

        if isCurrentTrack, container.locationTrackerViewModel.isPaused {
            return .orange
        }

        return .secondary
    }

    private var displayPointCount: Int {
        if isActiveTrack {
            return max(points.count, container.locationTrackerViewModel.pointCount)
        }

        return points.count
    }

    private var displayDistanceMeters: Double {
        if isCurrentTrack {
            return container.locationTrackerViewModel.currentDistanceMeters
        }

        if let distanceMeters = track?.distanceMeters, distanceMeters > 0 {
            return distanceMeters
        }

        return calculatedDistanceMeters
    }

    private var displayDurationSeconds: Int64 {
        if isCurrentTrack {
            return container.locationTrackerViewModel.elapsedSeconds
        }

        if let durationSeconds = track?.durationSeconds, durationSeconds > 0 {
            return durationSeconds
        }

        return calculatedDurationSeconds
    }

    private var displayAverageSpeed: Double? {
        if let averageSpeed = track?.averageSpeedMetersPerSecond {
            return averageSpeed
        }

        guard displayDistanceMeters > 0, displayDurationSeconds > 0 else { return nil }
        return displayDistanceMeters / Double(displayDurationSeconds)
    }

    private var displayMaxSpeed: Double? {
        if isCurrentTrack, let speed = container.locationTrackerViewModel.currentMaxSpeedMetersPerSecond {
            return speed
        }

        if let speed = track?.maxSpeedMetersPerSecond {
            return speed
        }

        return points.compactMap(\.speedMetersPerSecond).max()
    }

    private var selectedSegment: TrackSegment? {
        guard let selectedSegmentID else { return nil }
        return segments.first { $0.id == selectedSegmentID }
    }

    private var playbackCoordinate: CLLocationCoordinate2D? {
        guard points.indices.contains(playbackIndex) else { return nil }
        return CoordinateTransform.mapDisplayCoordinate(forWGS84: points[playbackIndex].coordinate)
    }

    private var playbackCoordinates: [CLLocationCoordinate2D] {
        guard showsPlaybackPath, displayCoordinates.isEmpty == false else { return [] }
        let upperBound = min(playbackIndex, displayCoordinates.count - 1)
        guard upperBound > 0 else { return [] }
        return Array(displayCoordinates[0...upperBound])
    }

    private var playbackSegment: TrackSegment? {
        guard points.indices.contains(playbackIndex) else { return nil }
        let timestamp = points[playbackIndex].timestampMilliseconds
        return segments.first { segment in
            timestamp >= segment.startedAt && timestamp <= segment.endedAt
        }
    }

    private var detectedPlaces: [TrackDetectedPlace] {
        TrackPlaceDetector.detectPlaces(from: points)
    }

    private var qualityIssues: [TrackQualityIssue] {
        TrackQualityAnalyzer.issues(for: points)
    }

    private var qualityOccurrences: [TrackQualityOccurrence] {
        qualityIssues.flatMap(\.occurrences)
    }

    private var playbackTaskTrigger: String {
        "\(isPlaybackPlaying)-\(playbackSpeed.rawValue)"
    }

    private var calculatedDurationSeconds: Int64 {
        guard let first = points.first, let last = points.last else { return 0 }
        return max(0, (last.timestampMilliseconds - first.timestampMilliseconds) / 1000)
    }

    private var calculatedDistanceMeters: Double {
        guard points.count > 1 else { return 0 }

        return zip(points, points.dropFirst()).reduce(0) { partialResult, pair in
            let start = CLLocation(latitude: pair.0.latitude, longitude: pair.0.longitude)
            let end = CLLocation(latitude: pair.1.latitude, longitude: pair.1.longitude)
            return partialResult + end.distance(from: start)
        }
    }

    private static func cameraPosition(
        for coordinates: [CLLocationCoordinate2D],
        paddingMultiplier: Double = 1.4,
        minimumDelta: CLLocationDegrees = 0.01
    ) -> MapCameraPosition {
        guard let first = coordinates.first else { return .automatic }

        var minLatitude = first.latitude
        var maxLatitude = first.latitude
        var minLongitude = first.longitude
        var maxLongitude = first.longitude

        for coordinate in coordinates {
            minLatitude = min(minLatitude, coordinate.latitude)
            maxLatitude = max(maxLatitude, coordinate.latitude)
            minLongitude = min(minLongitude, coordinate.longitude)
            maxLongitude = max(maxLongitude, coordinate.longitude)
        }

        let center = CLLocationCoordinate2D(
            latitude: (minLatitude + maxLatitude) / 2,
            longitude: (minLongitude + maxLongitude) / 2
        )
        let span = MKCoordinateSpan(
            latitudeDelta: max((maxLatitude - minLatitude) * paddingMultiplier, minimumDelta),
            longitudeDelta: max((maxLongitude - minLongitude) * paddingMultiplier, minimumDelta)
        )
        return .region(MKCoordinateRegion(center: center, span: span))
    }

    private func updateSegment(_ segment: TrackSegment, motionType: TrackMotionType) {
        do {
            guard let updatedSegment = try container.trackRepository.updateSegmentMotionType(
                id: segment.id,
                motionType: motionType
            ) else {
                errorMessage = AppLocalization.string("未找到可更新的运动分段")
                return
            }

            if let index = segments.firstIndex(where: { $0.id == updatedSegment.id }) {
                segments[index] = updatedSegment
            }
            errorMessage = nil

            Task {
                await container.syncEngine.enqueueScan()
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func splitSegment(_ segment: TrackSegment, at timestampMilliseconds: Int64) {
        do {
            guard let updatedSegment = try container.trackRepository.splitSegment(
                id: segment.id,
                at: timestampMilliseconds
            ) else {
                errorMessage = AppLocalization.string("当前采样点不能拆分分段")
                return
            }

            finishSegmentEdit(selecting: updatedSegment.id)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func mergeSegment(_ segment: TrackSegment, direction: TrackSegmentMergeDirection) {
        do {
            guard let updatedSegment = try container.trackRepository.mergeSegment(
                id: segment.id,
                direction: direction
            ) else {
                errorMessage = AppLocalization.string("没有可合并的相邻分段")
                return
            }

            finishSegmentEdit(selecting: updatedSegment.id)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func updateSegmentBoundaries(
        _ segment: TrackSegment,
        startedAt: Int64,
        endedAt: Int64
    ) {
        do {
            guard let updatedSegment = try container.trackRepository.updateSegmentBoundaries(
                id: segment.id,
                startedAt: startedAt,
                endedAt: endedAt
            ) else {
                errorMessage = AppLocalization.string("当前边界不能保存")
                return
            }

            finishSegmentEdit(selecting: updatedSegment.id)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func finishSegmentEdit(selecting segmentID: String) {
        segmentEditorRequest = nil
        selectedSegmentID = segmentID
        load()

        if let segment = segments.first(where: { $0.id == segmentID }) {
            selectSegment(segment)
        }

        Task {
            await container.syncEngine.enqueueScan()
        }
    }

    private func selectSegment(_ segment: TrackSegment) {
        selectedSegmentID = segment.id
        selectedPlaceID = nil
        selectedQualityOccurrenceID = nil
        let coordinates = displayCoordinates(for: segment)
        guard coordinates.isEmpty == false else { return }
        cameraPosition = Self.cameraPosition(for: coordinates, paddingMultiplier: 1.8, minimumDelta: 0.005)
    }

    private func showOverview() {
        selectedSegmentID = nil
        selectedPlaceID = nil
        selectedQualityOccurrenceID = nil
        cameraPosition = Self.cameraPosition(for: displayCoordinates)
    }

    private func selectPlace(_ place: TrackDetectedPlace) {
        selectedPlaceID = place.id
        selectedSegmentID = nil
        selectedQualityOccurrenceID = nil
        detailMode = .insights
        isPlaybackPlaying = false
        playbackIndex = nearestPointIndex(to: place.startedAt)
        cameraPosition = Self.cameraPosition(
            for: [place.displayCoordinate],
            paddingMultiplier: 1,
            minimumDelta: 0.004
        )
    }

    private func focusQualityIssue(_ issue: TrackQualityIssue) {
        guard let occurrence = issue.occurrences.first else { return }
        focusQualityOccurrence(occurrence)
    }

    private func focusQualityOccurrence(_ occurrence: TrackQualityOccurrence) {
        selectedQualityOccurrenceID = occurrence.id
        selectedPlaceID = nil
        selectedSegmentID = nil
        detailMode = .insights
        isPlaybackPlaying = false
        playbackIndex = nearestPointIndex(to: occurrence.timestampMilliseconds)
        cameraPosition = Self.cameraPosition(
            for: [occurrence.displayCoordinate],
            paddingMultiplier: 1,
            minimumDelta: 0.004
        )
    }

    private func togglePlayback() {
        guard points.count > 1 else { return }

        if isPlaybackPlaying {
            isPlaybackPlaying = false
            return
        }

        if playbackIndex >= points.count - 1 {
            playbackIndex = 0
        }

        showOverview()
        detailMode = .playback
        highlightPlaybackSegment()
        isPlaybackPlaying = true
    }

    private func focusPlaybackPoint() {
        guard let playbackCoordinate else { return }
        detailMode = .playback
        followsPlayback = true
        cameraPosition = Self.cameraPosition(
            for: [playbackCoordinate],
            paddingMultiplier: 1,
            minimumDelta: 0.004
        )
        highlightPlaybackSegment()
    }

    private func highlightPlaybackSegment() {
        guard detailMode == .playback, let playbackSegment else { return }
        selectedSegmentID = playbackSegment.id
    }

    private func followPlaybackIfNeeded() {
        guard followsPlayback, detailMode == .playback, let playbackCoordinate else { return }
        cameraPosition = Self.cameraPosition(
            for: [playbackCoordinate],
            paddingMultiplier: 1,
            minimumDelta: 0.004
        )
    }

    private func advancePlayback() {
        guard isPlaybackPlaying else { return }

        if playbackIndex >= points.count - 1 {
            isPlaybackPlaying = false
        } else {
            playbackIndex += 1
        }
    }

    private func displayCoordinates(for segment: TrackSegment) -> [CLLocationCoordinate2D] {
        points
            .filter { point in
                point.timestampMilliseconds >= segment.startedAt &&
                    point.timestampMilliseconds <= segment.endedAt
            }
            .map { point in
                CoordinateTransform.mapDisplayCoordinate(forWGS84: point.coordinate)
            }
    }

    private func previousSegment(for segment: TrackSegment) -> TrackSegment? {
        guard let index = segments.firstIndex(where: { $0.id == segment.id }),
              index > segments.startIndex else {
            return nil
        }

        return segments[segments.index(before: index)]
    }

    private func nextSegment(for segment: TrackSegment) -> TrackSegment? {
        guard let index = segments.firstIndex(where: { $0.id == segment.id }) else {
            return nil
        }

        let nextIndex = segments.index(after: index)
        guard nextIndex < segments.endIndex else { return nil }
        return segments[nextIndex]
    }

    private func splitCandidatePoints(for segment: TrackSegment) -> [TrackPoint] {
        points.filter { point in
            point.timestampMilliseconds > segment.startedAt &&
                point.timestampMilliseconds < segment.endedAt
        }
    }

    private func boundaryCandidatePoints(for segment: TrackSegment) -> [TrackPoint] {
        let lowerBound = previousSegment(for: segment)?.startedAt ?? segment.startedAt
        let upperBound = nextSegment(for: segment)?.endedAt ?? segment.endedAt

        return points.filter { point in
            point.timestampMilliseconds >= lowerBound &&
                point.timestampMilliseconds <= upperBound
        }
    }

    private func nearestPointIndex(to timestampMilliseconds: Int64) -> Int {
        guard points.isEmpty == false else { return 0 }

        return points.indices.min { lhs, rhs in
            abs(points[lhs].timestampMilliseconds - timestampMilliseconds) <
                abs(points[rhs].timestampMilliseconds - timestampMilliseconds)
        } ?? 0
    }
}

private enum TrackDetailMode: String, CaseIterable, Identifiable {
    case playback
    case insights
    case related

    var id: String { rawValue }

    var title: String {
        switch self {
        case .playback:
            AppLocalization.string("回放")
        case .insights:
            AppLocalization.string("洞察")
        case .related:
            AppLocalization.string("关联")
        }
    }

    var systemImage: String {
        switch self {
        case .playback:
            "play.circle"
        case .insights:
            "sparkline"
        case .related:
            "link"
        }
    }
}

private enum TrackSegmentEditorMode: String {
    case split
    case boundaries
}

private struct TrackSegmentEditorRequest: Identifiable {
    let segmentID: String
    let mode: TrackSegmentEditorMode

    var id: String {
        "\(mode.rawValue)-\(segmentID)"
    }
}

private enum TrackPlaybackSpeed: Double, CaseIterable, Identifiable {
    case normal = 1
    case double = 2
    case quadruple = 4

    var id: Double { rawValue }

    var title: String {
        switch self {
        case .normal:
            "1x"
        case .double:
            "2x"
        case .quadruple:
            "4x"
        }
    }

    var intervalNanoseconds: UInt64 {
        UInt64(700_000_000 / rawValue)
    }
}

private struct TrackPlaybackPanel: View {
    @Environment(\.locale) private var locale

    let points: [TrackPoint]
    let currentSegment: TrackSegment?
    @Binding var playbackIndex: Int
    @Binding var isPlaying: Bool
    @Binding var playbackSpeed: TrackPlaybackSpeed
    @Binding var followsPlayback: Bool
    let togglePlayback: () -> Void
    let focusPlaybackPoint: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Button(action: togglePlayback) {
                    Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.white)
                        .frame(width: 44, height: 44)
                        .background(Color.accentColor, in: .circle)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(AppLocalization.string(isPlaying ? "暂停回放" : "开始回放"))

                VStack(alignment: .leading, spacing: 4) {
                    Text(currentTimeText)
                        .font(.subheadline.weight(.semibold))

                    Text(currentMetricText)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Spacer(minLength: 8)

                Text(progressText)
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(.secondary)

                Button(action: focusPlaybackPoint) {
                    Image(systemName: "location.fill")
                        .font(.body.weight(.semibold))
                        .frame(width: 44, height: 44)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(AppLocalization.string("定位回放位置"))
            }

            Slider(
                value: Binding(
                    get: { Double(playbackIndex) },
                    set: { newValue in
                        isPlaying = false
                        playbackIndex = min(max(Int(newValue.rounded()), 0), max(points.count - 1, 0))
                    }
                ),
                in: 0...Double(max(points.count - 1, 1)),
                step: 1
            )
            .disabled(points.count < 2)
            .accessibilityLabel(AppLocalization.string("回放进度"))

            HStack(spacing: 12) {
                Text(AppLocalization.string("速度"))
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.secondary)

                Picker(AppLocalization.string("回放速度"), selection: $playbackSpeed) {
                    ForEach(TrackPlaybackSpeed.allCases) { speed in
                        Text(speed.title)
                            .tag(speed)
                    }
                }
                .pickerStyle(.segmented)
                .frame(maxWidth: 180)

                Spacer(minLength: 0)

                Button {
                    followsPlayback.toggle()
                    if followsPlayback {
                        focusPlaybackPoint()
                    }
                } label: {
                    Label(AppLocalization.string("跟随"), systemImage: followsPlayback ? "location.north.line.fill" : "location.north.line")
                        .font(.footnote.weight(.semibold))
                        .labelStyle(.iconOnly)
                        .foregroundStyle(followsPlayback ? Color.accentColor : Color(.secondaryLabel))
                        .frame(width: 44, height: 36)
                        .background(Color(.tertiarySystemGroupedBackground), in: .rect(cornerRadius: AppDesign.cornerRadius))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(AppLocalization.string(followsPlayback ? "关闭跟随回放" : "跟随回放"))
            }

            if let currentSegment {
                HStack(spacing: 8) {
                    Image(systemName: currentSegment.motionType.systemImage)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.white)
                        .frame(width: 24, height: 24)
                        .background(currentSegment.motionType.color, in: .circle)

                    Text(currentSegment.motionType.title)
                        .font(.footnote.weight(.semibold))

                    Text(AppFormatters.distance(currentSegment.distanceMeters))
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    Spacer(minLength: 8)

                    Text(AppFormatters.duration(currentSegment.durationSeconds))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .accessibilityElement(children: .combine)
            }
        }
        .padding(.vertical, 4)
    }

    private var currentPoint: TrackPoint? {
        guard points.indices.contains(playbackIndex) else { return nil }
        return points[playbackIndex]
    }

    private var currentTimeText: String {
        guard let currentPoint else { return AppLocalization.string("暂无采样点") }
        return AppFormatters.time(milliseconds: currentPoint.timestampMilliseconds, locale: locale)
    }

    private var currentMetricText: String {
        guard let currentPoint else { return AppLocalization.string("等待轨迹数据") }

        let speedText = AppFormatters.speed(currentPoint.speedMetersPerSecond)
        let distanceText = AppFormatters.distance(distance(to: playbackIndex))
        return "\(distanceText) · \(speedText)"
    }

    private var progressText: String {
        guard points.count > 1 else { return "0%" }
        let progress = Double(playbackIndex) / Double(points.count - 1)
        return "\(Int((progress * 100).rounded()))%"
    }

    private func distance(to targetIndex: Int) -> Double {
        guard targetIndex > 0, points.count > 1 else { return 0 }
        let upperBound = min(targetIndex, points.count - 1)

        return (1...upperBound).reduce(0) { partialResult, index in
            let previous = CLLocation(latitude: points[index - 1].latitude, longitude: points[index - 1].longitude)
            let current = CLLocation(latitude: points[index].latitude, longitude: points[index].longitude)
            return partialResult + current.distance(from: previous)
        }
    }
}

private struct TrackInsightsPanel: View {
    let segments: [TrackSegment]
    let detectedPlaces: [TrackDetectedPlace]
    let qualityIssues: [TrackQualityIssue]
    let selectedPlaceID: String?
    let selectedQualityOccurrenceID: String?
    let selectPlace: (TrackDetectedPlace) -> Void
    let selectQualityIssue: (TrackQualityIssue) -> Void
    let selectQualityOccurrence: (TrackQualityOccurrence) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if motionSummaries.isEmpty == false {
                VStack(alignment: .leading, spacing: 8) {
                    Text(AppLocalization.string("运动构成"))
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.secondary)

                    ForEach(motionSummaries) { summary in
                        HStack(spacing: 10) {
                            Image(systemName: summary.motionType.systemImage)
                                .foregroundStyle(.white)
                                .frame(width: 26, height: 26)
                                .background(summary.motionType.color, in: .circle)

                            Text(summary.motionType.title)
                                .font(.subheadline)

                            Spacer(minLength: 8)

                            Text(AppFormatters.distance(summary.distanceMeters))
                                .font(.footnote)
                                .foregroundStyle(.secondary)

                            Text(AppFormatters.duration(summary.durationSeconds))
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }

            Divider()

            VStack(alignment: .leading, spacing: 8) {
                Text(AppLocalization.string("停留识别"))
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.secondary)

                if detectedPlaces.isEmpty {
                    Label(AppLocalization.string("未识别到明显停留点"), systemImage: "location.slash")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(detectedPlaces) { place in
                        TrackDetectedPlaceRow(
                            place: place,
                            isSelected: selectedPlaceID == place.id
                        ) {
                            selectPlace(place)
                        }
                    }
                }
            }

            Divider()

            VStack(alignment: .leading, spacing: 8) {
                Text(AppLocalization.string("轨迹质量"))
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.secondary)

                if qualityIssues.isEmpty {
                    Label(AppLocalization.string("未发现明显异常"), systemImage: "checkmark.seal")
                        .font(.footnote)
                        .foregroundStyle(.green)
                } else {
                    ForEach(qualityIssues) { issue in
                        TrackQualityIssueRow(
                            issue: issue,
                            selectIssue: {
                                selectQualityIssue(issue)
                            },
                            selectedOccurrenceID: selectedQualityOccurrenceID,
                            selectOccurrence: selectQualityOccurrence
                        )
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }

    private var motionSummaries: [TrackMotionSummary] {
        let grouped = Dictionary(grouping: segments, by: \.motionType)
        return grouped.map { motionType, segments in
            TrackMotionSummary(
                motionType: motionType,
                distanceMeters: segments.reduce(0) { $0 + $1.distanceMeters },
                durationSeconds: segments.reduce(0) { $0 + $1.durationSeconds }
            )
        }
        .sorted { lhs, rhs in
            if lhs.durationSeconds == rhs.durationSeconds {
                return lhs.motionType.title < rhs.motionType.title
            }

            return lhs.durationSeconds > rhs.durationSeconds
        }
    }
}

private struct TrackRelatedRecordsPanel: View {
    let bills: [BillRecord]
    let diaryEntries: [DiaryEntry]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if bills.isEmpty, diaryEntries.isEmpty {
                Label(AppLocalization.string("当前轨迹时间段内没有账单或日记"), systemImage: "link")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                if bills.isEmpty == false {
                    RelatedGroupHeader(title: AppLocalization.string("账单"), count: bills.count)

                    ForEach(bills.prefix(5)) { bill in
                        TrackRelatedBillRow(bill: bill)
                    }
                }

                if diaryEntries.isEmpty == false {
                    if bills.isEmpty == false {
                        Divider()
                    }

                    RelatedGroupHeader(title: AppLocalization.string("日记"), count: diaryEntries.count)

                    ForEach(diaryEntries.prefix(5)) { entry in
                        TrackRelatedDiaryRow(entry: entry)
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }
}

private struct RelatedGroupHeader: View {
    let title: String
    let count: Int

    var body: some View {
        HStack {
            Text(title)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.secondary)

            Spacer()

            Text("\(count)")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
        }
    }
}

private struct TrackRelatedBillRow: View {
    let bill: BillRecord

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: bill.billType == .expense ? bill.billCategory.systemImage : bill.billIncomeCategory.systemImage)
                .foregroundStyle(.white)
                .frame(width: 28, height: 28)
                .background(bill.billType.amountTint, in: .rect(cornerRadius: AppDesign.iconCornerRadius))

            VStack(alignment: .leading, spacing: 3) {
                Text(bill.title)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)

                Text(AppFormatters.dateTime(milliseconds: bill.occurredAt))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer(minLength: 8)

            Text(bill.displayAmount)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(bill.billType.amountTint)
        }
    }
}

private struct TrackRelatedDiaryRow: View {
    let entry: DiaryEntry

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: entry.diaryMood.systemImage)
                .foregroundStyle(.white)
                .frame(width: 28, height: 28)
                .background(entry.diaryMood.tint, in: .rect(cornerRadius: AppDesign.iconCornerRadius))

            VStack(alignment: .leading, spacing: 3) {
                Text(entry.title)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)

                Text(entry.previewText)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: 8)

            Text(AppFormatters.dateTime(milliseconds: entry.occurredAt))
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}

private struct TrackDetectedPlaceRow: View {
    @Environment(\.locale) private var locale

    let place: TrackDetectedPlace
    let isSelected: Bool
    let selectPlace: () -> Void

    var body: some View {
        Button(action: selectPlace) {
            HStack(spacing: 10) {
                Image(systemName: "mappin.circle.fill")
                    .foregroundStyle(.white)
                    .frame(width: 28, height: 28)
                    .background(Color.orange, in: .circle)

                VStack(alignment: .leading, spacing: 3) {
                    Text(place.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)

                    Text(timeRangeText)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Spacer(minLength: 8)

                Text(AppFormatters.duration(place.durationSeconds))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .padding(.vertical, 6)
            .padding(.horizontal, 8)
            .background(isSelected ? Color.orange.opacity(0.12) : Color.clear)
            .clipShape(.rect(cornerRadius: AppDesign.cornerRadius))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(AppLocalization.format("%@，%@", place.title, timeRangeText))
        .accessibilityHint(AppLocalization.string("在地图中定位该停留点"))
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    private var timeRangeText: String {
        "\(AppFormatters.time(milliseconds: place.startedAt, locale: locale)) - \(AppFormatters.time(milliseconds: place.endedAt, locale: locale))"
    }
}

private struct TrackQualityIssueRow: View {
    @Environment(\.locale) private var locale

    let issue: TrackQualityIssue
    let selectIssue: () -> Void
    let selectedOccurrenceID: String?
    let selectOccurrence: (TrackQualityOccurrence) -> Void

    private var isSelected: Bool {
        issue.occurrences.contains { $0.id == selectedOccurrenceID }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button(action: selectIssue) {
                HStack(spacing: 10) {
                    Image(systemName: issue.systemImage)
                        .foregroundStyle(.white)
                        .frame(width: 28, height: 28)
                        .background(issue.tint, in: .circle)

                    VStack(alignment: .leading, spacing: 3) {
                        Text(issue.title)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.primary)

                        Text(issue.detail)
                            .font(.caption)
                            .foregroundStyle(.secondary)

                        if let firstOccurrence = issue.occurrences.first {
                            Text(firstOccurrence.detail)
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }

                    Spacer(minLength: 8)

                    Text("\(issue.count)")
                        .font(.footnote.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 6)
                .padding(.horizontal, 8)
                .background(isSelected ? issue.tint.opacity(0.12) : Color.clear)
                .clipShape(.rect(cornerRadius: AppDesign.cornerRadius))
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityHint(AppLocalization.string("在地图中定位第一处异常"))
            .accessibilityAddTraits(isSelected ? .isSelected : [])

            if isSelected {
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(issue.occurrences) { occurrence in
                        Button {
                            selectOccurrence(occurrence)
                        } label: {
                            HStack(spacing: 8) {
                                Circle()
                                    .fill(occurrence.id == selectedOccurrenceID ? issue.tint : Color(.tertiaryLabel))
                                    .frame(width: 8, height: 8)

                                Text(AppFormatters.time(milliseconds: occurrence.timestampMilliseconds, locale: locale))
                                    .font(.caption.monospacedDigit())
                                    .foregroundStyle(.secondary)

                                Text(occurrence.detail)
                                    .font(.caption)
                                    .foregroundStyle(.primary)
                                    .lineLimit(1)

                                Spacer(minLength: 8)

                                Image(systemName: "location")
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(issue.tint)
                            }
                            .frame(minHeight: 36)
                            .padding(.horizontal, 10)
                            .background(
                                occurrence.id == selectedOccurrenceID ? issue.tint.opacity(0.1) : Color(.tertiarySystemGroupedBackground)
                            )
                            .clipShape(.rect(cornerRadius: AppDesign.cornerRadius))
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(
                            AppLocalization.format(
                                "%@，%@",
                                AppFormatters.time(milliseconds: occurrence.timestampMilliseconds, locale: locale),
                                occurrence.detail
                            )
                        )
                        .accessibilityHint(AppLocalization.string("在地图中定位该异常"))
                    }

                    if issue.count > issue.occurrences.count {
                        Text(AppLocalization.format("已展示前 %d 处，共 %d 处", issue.occurrences.count, issue.count))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 10)
                            .padding(.top, 2)
                    }
                }
                .padding(.leading, 38)
            }
        }
    }
}

private struct TrackMotionSummary: Identifiable {
    let motionType: TrackMotionType
    let distanceMeters: Double
    let durationSeconds: Int64

    var id: String { motionType.id }
}

struct TrackDetectedPlace: Identifiable {
    let id: String
    let title: String
    let startedAt: Int64
    let endedAt: Int64
    let durationSeconds: Int64
    let latitude: Double
    let longitude: Double

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    var displayCoordinate: CLLocationCoordinate2D {
        CoordinateTransform.mapDisplayCoordinate(forWGS84: coordinate)
    }
}

private struct TrackQualityIssue: Identifiable {
    let kind: TrackQualityIssueKind
    let detail: String
    let totalCount: Int
    let occurrences: [TrackQualityOccurrence]

    var id: String { kind.rawValue }
    var count: Int { totalCount }
    var title: String { kind.title }
    var systemImage: String { kind.systemImage }
    var tint: Color { kind.tint }
}

enum TrackQualityIssueKind: String {
    case weakAccuracy
    case timeGap
    case speedSpike

    var title: String {
        switch self {
        case .weakAccuracy:
            AppLocalization.string("定位精度偏低")
        case .timeGap:
            AppLocalization.string("记录存在间断")
        case .speedSpike:
            AppLocalization.string("存在速度异常")
        }
    }

    var systemImage: String {
        switch self {
        case .weakAccuracy:
            "scope"
        case .timeGap:
            "pause.circle"
        case .speedSpike:
            "speedometer"
        }
    }

    var tint: Color {
        switch self {
        case .weakAccuracy:
            .orange
        case .timeGap:
            .purple
        case .speedSpike:
            .red
        }
    }
}

struct TrackQualityOccurrence: Identifiable {
    let id: String
    let kind: TrackQualityIssueKind
    let timestampMilliseconds: Int64
    let latitude: Double
    let longitude: Double
    let detail: String

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    var displayCoordinate: CLLocationCoordinate2D {
        CoordinateTransform.mapDisplayCoordinate(forWGS84: coordinate)
    }
}

private enum TrackQualityAnalyzer {
    private static let weakAccuracyThresholdMeters = 100.0
    private static let timeGapThresholdMilliseconds: Int64 = 2 * 60 * 1000
    private static let speedSpikeThresholdMetersPerSecond = 80.0
    private static let maximumOccurrencesPerIssue = 8

    static func issues(for points: [TrackPoint]) -> [TrackQualityIssue] {
        let sortedPoints = points.sorted { $0.timestampMilliseconds < $1.timestampMilliseconds }
        guard sortedPoints.isEmpty == false else { return [] }

        var issues: [TrackQualityIssue] = []
        let weakAccuracyPoints = sortedPoints
            .filter { ($0.horizontalAccuracy ?? 0) > weakAccuracyThresholdMeters }
        let weakAccuracyOccurrences = weakAccuracyPoints
            .prefix(maximumOccurrencesPerIssue)
            .map { point in
                TrackQualityOccurrence(
                    id: "weak-\(point.timestampMilliseconds)",
                    kind: .weakAccuracy,
                    timestampMilliseconds: point.timestampMilliseconds,
                    latitude: point.latitude,
                    longitude: point.longitude,
                    detail: AppLocalization.format("精度约 %d m", Int(point.horizontalAccuracy ?? 0))
                )
            }

        if weakAccuracyOccurrences.isEmpty == false {
            issues.append(
                TrackQualityIssue(
                    kind: .weakAccuracy,
                    detail: AppLocalization.format("水平精度超过 %d m 的采样点", Int(weakAccuracyThresholdMeters)),
                    totalCount: weakAccuracyPoints.count,
                    occurrences: Array(weakAccuracyOccurrences)
                )
            )
        }

        var timeGapCount = 0
        var speedSpikeCount = 0
        var timeGapOccurrences: [TrackQualityOccurrence] = []
        var speedSpikeOccurrences: [TrackQualityOccurrence] = []

        for (start, end) in zip(sortedPoints, sortedPoints.dropFirst()) {
            let deltaMilliseconds = end.timestampMilliseconds - start.timestampMilliseconds

            if deltaMilliseconds > timeGapThresholdMilliseconds {
                timeGapCount += 1

                if timeGapOccurrences.count < maximumOccurrencesPerIssue {
                    timeGapOccurrences.append(
                        TrackQualityOccurrence(
                            id: "gap-\(start.timestampMilliseconds)-\(end.timestampMilliseconds)",
                            kind: .timeGap,
                            timestampMilliseconds: end.timestampMilliseconds,
                            latitude: end.latitude,
                            longitude: end.longitude,
                            detail: AppLocalization.format("间隔 %@", AppFormatters.duration(deltaMilliseconds / 1000))
                        )
                    )
                }
            }

            guard deltaMilliseconds > 0 else { continue }
            let startLocation = CLLocation(latitude: start.latitude, longitude: start.longitude)
            let endLocation = CLLocation(latitude: end.latitude, longitude: end.longitude)
            let inferredSpeed = endLocation.distance(from: startLocation) / (Double(deltaMilliseconds) / 1000)

            if inferredSpeed > speedSpikeThresholdMetersPerSecond {
                speedSpikeCount += 1

                if speedSpikeOccurrences.count < maximumOccurrencesPerIssue {
                    speedSpikeOccurrences.append(
                        TrackQualityOccurrence(
                            id: "speed-\(start.timestampMilliseconds)-\(end.timestampMilliseconds)",
                            kind: .speedSpike,
                            timestampMilliseconds: end.timestampMilliseconds,
                            latitude: end.latitude,
                            longitude: end.longitude,
                            detail: AppLocalization.format("推算 %@", AppFormatters.speed(inferredSpeed))
                        )
                    )
                }
            }
        }

        if timeGapOccurrences.isEmpty == false {
            issues.append(
                TrackQualityIssue(
                    kind: .timeGap,
                    detail: AppLocalization.string("相邻采样间隔超过 2 分钟"),
                    totalCount: timeGapCount,
                    occurrences: timeGapOccurrences
                )
            )
        }

        if speedSpikeOccurrences.isEmpty == false {
            issues.append(
                TrackQualityIssue(
                    kind: .speedSpike,
                    detail: AppLocalization.format("两点推算速度超过 %d m/s", Int(speedSpikeThresholdMetersPerSecond)),
                    totalCount: speedSpikeCount,
                    occurrences: speedSpikeOccurrences
                )
            )
        }

        return issues
    }
}

private enum TrackPlaceDetector {
    private static let radiusMeters: CLLocationDistance = 80
    private static let minimumDurationMilliseconds: Int64 = 5 * 60 * 1000
    private static let minimumPointCount = 3

    static func detectPlaces(from points: [TrackPoint]) -> [TrackDetectedPlace] {
        let sortedPoints = points.sorted { $0.timestampMilliseconds < $1.timestampMilliseconds }
        guard sortedPoints.count >= minimumPointCount else { return [] }

        var places: [TrackDetectedPlace] = []
        var startIndex = 0
        var anchorLocation = location(for: sortedPoints[0])

        for index in 1..<sortedPoints.count {
            let currentLocation = location(for: sortedPoints[index])

            if currentLocation.distance(from: anchorLocation) > radiusMeters {
                appendPlace(
                    points: Array(sortedPoints[startIndex..<index]),
                    index: places.count,
                    to: &places
                )
                startIndex = index
                anchorLocation = currentLocation
            }
        }

        appendPlace(
            points: Array(sortedPoints[startIndex..<sortedPoints.count]),
            index: places.count,
            to: &places
        )

        return Array(places.prefix(6))
    }

    private static func appendPlace(
        points: [TrackPoint],
        index: Int,
        to places: inout [TrackDetectedPlace]
    ) {
        guard points.count >= minimumPointCount,
              let first = points.first,
              let last = points.last else {
            return
        }

        let durationMilliseconds = last.timestampMilliseconds - first.timestampMilliseconds
        guard durationMilliseconds >= minimumDurationMilliseconds else { return }

        let latitude = points.reduce(0) { $0 + $1.latitude } / Double(points.count)
        let longitude = points.reduce(0) { $0 + $1.longitude } / Double(points.count)

        places.append(
            TrackDetectedPlace(
                id: "\(first.timestampMilliseconds)-\(last.timestampMilliseconds)",
                title: AppLocalization.format("停留点 %d", index + 1),
                startedAt: first.timestampMilliseconds,
                endedAt: last.timestampMilliseconds,
                durationSeconds: max(0, durationMilliseconds / 1000),
                latitude: latitude,
                longitude: longitude
            )
        )
    }

    private static func location(for point: TrackPoint) -> CLLocation {
        CLLocation(latitude: point.latitude, longitude: point.longitude)
    }
}

private struct TrackSegmentSplitEditorSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.locale) private var locale

    let segment: TrackSegment
    let candidates: [TrackPoint]
    let splitSegment: (Int64) -> Void

    @State private var selectedIndex: Int

    init(
        segment: TrackSegment,
        points: [TrackPoint],
        splitSegment: @escaping (Int64) -> Void
    ) {
        self.segment = segment
        let candidates = points.filter { point in
            point.timestampMilliseconds > segment.startedAt &&
                point.timestampMilliseconds < segment.endedAt
        }
        self.candidates = candidates
        self.splitSegment = splitSegment
        _selectedIndex = State(initialValue: max(0, candidates.count / 2))
    }

    var body: some View {
        Form {
            if candidates.isEmpty {
                ContentUnavailableView(AppLocalization.string("当前分段不能拆分"), systemImage: "scissors")
            } else {
                Section {
                    LabeledContent(AppLocalization.string("原始范围"), value: originalRangeText)
                    LabeledContent(AppLocalization.string("拆分点"), value: selectedTimeText)

                    Slider(
                        value: Binding(
                            get: { Double(selectedIndex) },
                            set: { value in
                                selectedIndex = min(max(Int(value.rounded()), 0), candidates.count - 1)
                            }
                        ),
                        in: 0...Double(max(candidates.count - 1, 1)),
                        step: 1
                    )
                    .accessibilityLabel(AppLocalization.string("拆分点"))
                } footer: {
                    Text(AppLocalization.string("拆分后会保留当前分段作为前半段，并创建一个新的后半段。"))
                }
            }
        }
        .navigationTitle(AppLocalization.string("拆分分段"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(AppLocalization.string("取消")) {
                    dismiss()
                }
            }

            ToolbarItem(placement: .confirmationAction) {
                Button(AppLocalization.string("保存")) {
                    splitSegment(candidates[selectedIndex].timestampMilliseconds)
                    dismiss()
                }
                .disabled(candidates.isEmpty)
            }
        }
    }

    private var originalRangeText: String {
        "\(AppFormatters.time(milliseconds: segment.startedAt, locale: locale)) - \(AppFormatters.time(milliseconds: segment.endedAt, locale: locale))"
    }

    private var selectedTimeText: String {
        guard candidates.indices.contains(selectedIndex) else { return "-" }
        return AppFormatters.time(milliseconds: candidates[selectedIndex].timestampMilliseconds, locale: locale)
    }
}

private struct TrackSegmentBoundaryEditorSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.locale) private var locale

    let segment: TrackSegment
    let previousSegment: TrackSegment?
    let nextSegment: TrackSegment?
    let candidates: [TrackPoint]
    let saveBoundaries: (Int64, Int64) -> Void

    @State private var startIndex: Int
    @State private var endIndex: Int

    init(
        segment: TrackSegment,
        previousSegment: TrackSegment?,
        nextSegment: TrackSegment?,
        points: [TrackPoint],
        saveBoundaries: @escaping (Int64, Int64) -> Void
    ) {
        self.segment = segment
        self.previousSegment = previousSegment
        self.nextSegment = nextSegment
        let lowerBound = previousSegment?.startedAt ?? segment.startedAt
        let upperBound = nextSegment?.endedAt ?? segment.endedAt
        let candidates = points.filter { point in
            point.timestampMilliseconds >= lowerBound &&
                point.timestampMilliseconds <= upperBound
        }
        self.candidates = candidates
        self.saveBoundaries = saveBoundaries

        let startIndex = Self.nearestIndex(in: candidates, to: segment.startedAt)
        let endIndex = Self.nearestIndex(in: candidates, to: segment.endedAt)
        _startIndex = State(initialValue: startIndex)
        _endIndex = State(initialValue: max(endIndex, startIndex + 1))
    }

    var body: some View {
        Form {
            if candidates.count < 3 {
                ContentUnavailableView(AppLocalization.string("当前分段不能调整边界"), systemImage: "slider.horizontal.2.square")
            } else {
                Section {
                    LabeledContent(AppLocalization.string("当前范围"), value: originalRangeText)
                    LabeledContent(AppLocalization.string("调整后"), value: editedRangeText)
                    LabeledContent(AppLocalization.string("调整后时长"), value: AppFormatters.duration(durationSeconds))
                }

                Section(AppLocalization.string("开始边界")) {
                    Slider(
                        value: Binding(
                            get: { Double(startIndex) },
                            set: { value in
                                startIndex = clampedStartIndex(Int(value.rounded()))
                                endIndex = max(endIndex, startIndex + 1)
                            }
                        ),
                        in: Double(minStartIndex)...Double(maxStartIndex),
                        step: 1
                    )
                    .disabled(canEditStart == false)
                    .accessibilityLabel(AppLocalization.string("开始边界"))

                    Text(startTimeText)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section(AppLocalization.string("结束边界")) {
                    Slider(
                        value: Binding(
                            get: { Double(endIndex) },
                            set: { value in
                                endIndex = clampedEndIndex(Int(value.rounded()))
                                startIndex = min(startIndex, endIndex - 1)
                            }
                        ),
                        in: Double(minEndIndex)...Double(maxEndIndex),
                        step: 1
                    )
                    .disabled(canEditEnd == false)
                    .accessibilityLabel(AppLocalization.string("结束边界"))

                    Text(endTimeText)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle(AppLocalization.string("调整边界"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(AppLocalization.string("取消")) {
                    dismiss()
                }
            }

            ToolbarItem(placement: .confirmationAction) {
                Button(AppLocalization.string("保存")) {
                    saveBoundaries(
                        candidates[startIndex].timestampMilliseconds,
                        candidates[endIndex].timestampMilliseconds
                    )
                    dismiss()
                }
                .disabled(canSave == false)
            }
        }
    }

    private var canEditStart: Bool {
        previousSegment != nil
    }

    private var canEditEnd: Bool {
        nextSegment != nil
    }

    private var minStartIndex: Int {
        previousSegment == nil ? startIndex : 1
    }

    private var maxStartIndex: Int {
        max(minStartIndex, endIndex - 1)
    }

    private var minEndIndex: Int {
        min(max(startIndex + 1, 1), maxEndIndex)
    }

    private var maxEndIndex: Int {
        guard candidates.isEmpty == false else { return 0 }
        return nextSegment == nil ? endIndex : max(0, candidates.count - 2)
    }

    private var canSave: Bool {
        candidates.indices.contains(startIndex) &&
            candidates.indices.contains(endIndex) &&
            startIndex < endIndex &&
            (candidates[startIndex].timestampMilliseconds != segment.startedAt ||
                candidates[endIndex].timestampMilliseconds != segment.endedAt)
    }

    private var durationSeconds: Int64 {
        guard candidates.indices.contains(startIndex), candidates.indices.contains(endIndex) else { return 0 }
        return max(0, (candidates[endIndex].timestampMilliseconds - candidates[startIndex].timestampMilliseconds) / 1000)
    }

    private var originalRangeText: String {
        "\(AppFormatters.time(milliseconds: segment.startedAt, locale: locale)) - \(AppFormatters.time(milliseconds: segment.endedAt, locale: locale))"
    }

    private var editedRangeText: String {
        "\(startTimeText) - \(endTimeText)"
    }

    private var startTimeText: String {
        guard candidates.indices.contains(startIndex) else { return "-" }
        return AppFormatters.time(milliseconds: candidates[startIndex].timestampMilliseconds, locale: locale)
    }

    private var endTimeText: String {
        guard candidates.indices.contains(endIndex) else { return "-" }
        return AppFormatters.time(milliseconds: candidates[endIndex].timestampMilliseconds, locale: locale)
    }

    private func clampedStartIndex(_ index: Int) -> Int {
        min(max(index, minStartIndex), maxStartIndex)
    }

    private func clampedEndIndex(_ index: Int) -> Int {
        min(max(index, minEndIndex), maxEndIndex)
    }

    private static func nearestIndex(in points: [TrackPoint], to timestampMilliseconds: Int64) -> Int {
        guard points.isEmpty == false else { return 0 }
        return points.indices.min { lhs, rhs in
            abs(points[lhs].timestampMilliseconds - timestampMilliseconds) <
                abs(points[rhs].timestampMilliseconds - timestampMilliseconds)
        } ?? 0
    }
}

private struct TrackSelectedSegmentSummary: View {
    @Environment(\.locale) private var locale

    let segment: TrackSegment
    let allowsEditing: Bool
    let canSplit: Bool
    let canMergePrevious: Bool
    let canMergeNext: Bool
    let canAdjustBoundaries: Bool
    let updateMotionType: (TrackMotionType) -> Void
    let splitSegment: () -> Void
    let editBoundaries: () -> Void
    let mergePrevious: () -> Void
    let mergeNext: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: segment.motionType.systemImage)
                .foregroundStyle(.white)
                .frame(width: 28, height: 28)
                .background(segment.motionType.color, in: .circle)

            VStack(alignment: .leading, spacing: 3) {
                Text(segment.motionType.title)
                    .font(.subheadline.weight(.semibold))

                Text(timeRangeText)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Spacer(minLength: 8)

            Text(AppFormatters.distance(segment.distanceMeters))
                .font(.footnote)
                .foregroundStyle(.secondary)

            if allowsEditing {
                Menu {
                    Section(AppLocalization.string("类型")) {
                        ForEach(TrackMotionType.allCases) { motionType in
                            Button {
                                updateMotionType(motionType)
                            } label: {
                                Label(motionType.title, systemImage: motionType.systemImage)
                            }
                        }
                    }

                    Section(AppLocalization.string("编辑")) {
                        Button(action: splitSegment) {
                            Label(AppLocalization.string("拆分分段"), systemImage: "scissors")
                        }
                        .disabled(canSplit == false)

                        Button(action: editBoundaries) {
                            Label(AppLocalization.string("调整边界"), systemImage: "slider.horizontal.2.square")
                        }
                        .disabled(canAdjustBoundaries == false)
                    }

                    Section(AppLocalization.string("合并")) {
                        Button(action: mergePrevious) {
                            Label(AppLocalization.string("与上一段合并"), systemImage: "arrow.up.to.line.compact")
                        }
                        .disabled(canMergePrevious == false)

                        Button(action: mergeNext) {
                            Label(AppLocalization.string("与下一段合并"), systemImage: "arrow.down.to.line.compact")
                        }
                        .disabled(canMergeNext == false)
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .font(.title3)
                        .frame(width: 44, height: 44)
                        .contentShape(.rect)
                }
                .accessibilityLabel(AppLocalization.string("编辑分段"))
            }
        }
        .padding(.vertical, 4)
    }

    private var timeRangeText: String {
        "\(AppFormatters.time(milliseconds: segment.startedAt, locale: locale)) - \(AppFormatters.time(milliseconds: segment.endedAt, locale: locale))"
    }
}

private struct TrackSegmentStrip: View {
    @Environment(\.locale) private var locale

    let segments: [TrackSegment]
    let selectedSegmentID: String?
    let totalDistanceMeters: Double
    let showOverview: () -> Void
    let selectSegment: (TrackSegment) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                TrackSegmentOverviewStripItem(
                    isSelected: selectedSegmentID == nil,
                    distanceText: AppFormatters.distance(totalDistanceMeters),
                    showOverview: showOverview
                )

                ForEach(segments) { segment in
                    TrackSegmentStripItem(
                        segment: segment,
                        isSelected: selectedSegmentID == segment.id,
                        timeRangeText: timeRangeText(for: segment)
                    ) {
                        selectSegment(segment)
                    }
                }
            }
            .padding(.vertical, 2)
        }
    }

    private func timeRangeText(for segment: TrackSegment) -> String {
        "\(AppFormatters.time(milliseconds: segment.startedAt, locale: locale)) - \(AppFormatters.time(milliseconds: segment.endedAt, locale: locale))"
    }
}

private struct TrackSegmentOverviewStripItem: View {
    let isSelected: Bool
    let distanceText: String
    let showOverview: () -> Void

    var body: some View {
        Button(action: showOverview) {
            HStack(spacing: 8) {
                Image(systemName: "map")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white)
                    .frame(width: 24, height: 24)
                    .background(Color.accentColor, in: .circle)

                VStack(alignment: .leading, spacing: 2) {
                    Text(AppLocalization.string("全览"))
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.primary)

                    Text(AppLocalization.string("全部路线"))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                Text(distanceText)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .frame(minHeight: 44)
            .background(isSelected ? Color.accentColor.opacity(0.14) : Color(.secondarySystemGroupedBackground))
            .overlay {
                RoundedRectangle(cornerRadius: AppDesign.cornerRadius)
                    .stroke(isSelected ? Color.accentColor : Color(.separator), lineWidth: isSelected ? 1.5 : 0.5)
            }
            .clipShape(.rect(cornerRadius: AppDesign.cornerRadius))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(AppLocalization.string("全览，全部路线"))
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

private struct TrackSegmentStripItem: View {
    let segment: TrackSegment
    let isSelected: Bool
    let timeRangeText: String
    let selectSegment: () -> Void

    var body: some View {
        Button(action: selectSegment) {
            HStack(spacing: 8) {
                Image(systemName: segment.motionType.systemImage)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white)
                    .frame(width: 24, height: 24)
                    .background(segment.motionType.color, in: .circle)

                VStack(alignment: .leading, spacing: 2) {
                    Text(segment.motionType.title)
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.primary)

                    Text(timeRangeText)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                Text(AppFormatters.distance(segment.distanceMeters))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .frame(minHeight: 44)
            .background(isSelected ? segment.motionType.color.opacity(0.14) : Color(.secondarySystemGroupedBackground))
            .overlay {
                RoundedRectangle(cornerRadius: AppDesign.cornerRadius)
                    .stroke(isSelected ? segment.motionType.color : Color(.separator), lineWidth: isSelected ? 1.5 : 0.5)
            }
            .clipShape(.rect(cornerRadius: AppDesign.cornerRadius))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(segment.motionType.title)，\(timeRangeText)")
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

#Preview {
    NavigationStack {
        TrackDetailView(trackID: "preview")
            .environment(AppContainer.preview)
    }
}
